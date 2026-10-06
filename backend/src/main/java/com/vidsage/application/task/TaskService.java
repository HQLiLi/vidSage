package com.vidsage.application.task;

import com.vidsage.application.media.WorkspaceService;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.query.TaskArtifact;
import com.vidsage.application.task.query.TaskArtifactFile;
import com.vidsage.application.task.query.TaskSnapshot;
import com.vidsage.domain.shared.SourceUri;
import com.vidsage.domain.shared.StageEvent;
import com.vidsage.domain.shared.WorkspaceDir;
import com.vidsage.domain.shared.WorkspaceKey;
import com.vidsage.domain.task.TaskArtifactScan;
import com.vidsage.domain.task.TaskNo;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.media.MediaInput;
import com.vidsage.domain.task.TaskInputType;
import com.vidsage.domain.task.TaskStatus;
import com.vidsage.infrastructure.persistence.repository.VideoTaskRepository;
import com.vidsage.shared.fs.ArtifactFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 任务提交与查询的唯一入口：校验、查重、落库、上传落盘（design.md「数据流」）。
 * 异步执行不在这里——落库后委托 {@link TaskPipelineService#execute} 启动管线，HTTP 秒级返回。
 *
 * <p>两条提交路线收敛到 {@link #submit}：
 * <ul>
 *   <li><b>URL</b>（POST /api/tasks）：workspace_key = {@code WorkspaceService.normalize → WorkspaceKey.ofHash}，
 *       <b>只算 key 不触发建目录</b>——目录由管线内的各阶段服务按同一公式懒创建；</li>
 *   <li><b>上传</b>（POST /api/tasks/upload）：流式写盘<b>边写边算 sha256</b>，key = 内容摘要前 12 位，
 *       工作区 = {@code sanitize(原始文件名)-<key>/source.mp4}（断点复用按内容寻址，比「路径+时间戳」指纹稳），
 *       以 {@code MediaInput.InWorkspace} 直通下游——目录已解析，各阶段服务零重算。
 *       请求体是流不是 MultipartFile——HTTP 类型不进 application/。</li>
 * </ul>
 *
 * <p>查询面：{@link #findByTaskNo}（视图 + 产物清单）、{@link #snapshot}（SSE 快照，DB 即权威）、
 * {@link #openArtifact}（产物下载，含路径穿越防护）。未知 taskNo 统一抛 {@link TaskNotFoundException}
 * → 404，查询 / 订阅 / 产物三端点口径一致。
 *
 * <p>查重：同 workspace_key 且状态 PENDING / RUNNING → 抛 {@link TaskConflictException}（409）。
 * 查重位置在<b>各自路线第一处副作用之前</b>：URL 路线在落库前；上传路线在临时文件移入工作区前
 * （key 必须等整份文件流写完才算得出，故晚于流式落盘、早于建工作区目录——避免留下无任务的孤儿目录）。
 */
@Slf4j
@Service
public class TaskService {

    /** 临时文件名前缀：上传先落根目录临时文件算内容摘要，再按 key 归位。 */
    private static final String TEMP_FILE_PREFIX = "upload-";

    /** 临时文件名后缀：明确「未归位」语义，也便于排查残留。 */
    private static final String TEMP_FILE_SUFFIX = ".part";

    private final VideoTaskRepository repository;
    private final WorkspaceService workspaceService;
    private final TaskPipelineService pipelineService;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param repository       任务持久化入口（application/ 唯一可见的持久化门面）
     * @param workspaceService 工作区根解析与显式 key 建目录
     * @param pipelineService  异步管线（insert 成功后异步启动）
     */
    public TaskService(VideoTaskRepository repository, WorkspaceService workspaceService,
                       TaskPipelineService pipelineService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.pipelineService = pipelineService;
    }

    /**
     * URL 提交路线：算工作区 key → 查重 → 落库 → 异步启动管线。
     *
     * @param url 公网媒体页面地址（B 站 / YouTube 等）；不可为空白
     * @return 新任务的 PENDING 视图
     * @throws IllegalArgumentException url 为空白
     * @throws TaskConflictException    同 URL 已有进行中任务（409）
     */
    public TaskSnapshot submitUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 不能为空：请传公网媒体页面地址（如 B 站 / YouTube 链接），"
                    + "上传本地文件请走 POST /api/tasks/upload");
        }
        MediaInput.RemoteUrl input = new MediaInput.RemoteUrl(url.trim());
        SourceUri identity = WorkspaceService.normalize(input);
        WorkspaceKey workspaceKey = WorkspaceKey.ofHash(identity.value());
        rejectIfActive(workspaceKey);
        // 目录名公式单源在 WorkspaceDir.of（sanitize(标签)-key）：
        // 管线阶段会用同一公式懒建出同一目录，落点天然一致
        String dirName = WorkspaceDir.of(input.value(), workspaceKey).value();
        return submit(input, TaskInputType.URL, input.value(), workspaceKey, dirName);
    }

    /**
     * 上传提交路线：流式写盘边写边算 sha256 → 按内容 key 归位到工作区 → 查重 → 落库 → 异步启动管线。
     *
     * <p>流程：先落<b>临时文件</b>（工作区根下）并同步算内容摘要 → 查重（命中即弃临时文件抛 409，
     * 不留孤儿目录）→ 建内容寻址工作区 → 临时文件改名归位为 {@code source.mp4}（同盘 rename，不整份复制）。
     * 同一文件重复上传命中同一目录，既有产物天然可断点复用。
     *
     * @param content          上传文件内容流；不可为 {@code null}（空文件由 HTTP 边界卫语句拦下）
     * @param originalFileName 上传文件原始名（只进 source_uri 与目录标签，不进日志）；可空白，空白时目录标签回退
     * @return 新任务的 PENDING 视图
     * @throws IllegalArgumentException 内容流为 {@code null}
     * @throws TaskConflictException    同内容已有进行中任务（409）
     * @throws IllegalStateException    落盘失败（工作区根不可写等）
     */
    public TaskSnapshot submitUpload(InputStream content, String originalFileName) {
        if (content == null) {
            throw new IllegalArgumentException("上传内容不能为空：请重新选择文件后再提交");
        }
        try {
            Path root = workspaceService.workspaceRoot();
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
            boolean settled = false;
            try {
                WorkspaceKey workspaceKey = streamToTemp(content, temp);
                rejectIfActive(workspaceKey);
                Path source = settleUpload(temp, originalFileName, workspaceKey);
                settled = true;
                // 以 InWorkspace 直通下游（design.md 权衡记录）：LocalPath 会被各阶段服务按
                // 「路径+大小+mtime」重算出另一个目录——任务 workspace_dir 与产物落点分家，
                // 且 mtime 随重传漂移、断点复用失效。变体选择与 source.mp4 落点单源在
                // MediaInput.ofTaskInput，管线重建走同一工厂，两个时机的输入身份必然一致
                MediaInput input = MediaInput.ofTaskInput(TaskInputType.UPLOAD, originalFileName,
                        source.getParent(), workspaceKey);
                return submit(input, TaskInputType.UPLOAD, originalFileName, workspaceKey,
                        source.getParent().getFileName().toString());
            } finally {
                if (!settled) {
                    // 查重拒绝 / 归位失败：临时文件必须清掉，不让半成品留在工作区根
                    Files.deleteIfExists(temp);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("上传文件落盘失败：" + e.getMessage()
                    + "；请检查 vidsage.workspace.root 是否可写后重试", e);
        } catch (NoSuchAlgorithmException e) {
            // 标准 JVM 必带 SHA-256；走到这里说明运行环境被裁剪过
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法按内容寻址落盘上传文件", e);
        }
    }

    /**
     * 按对外任务号查询任务视图（含产物清单）。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 任务视图（artifacts 为工作区扫描结果，无产物时为空表）
     * @throws IllegalArgumentException taskNo 为空白（HTTP 边界映射 400）
     * @throws TaskNotFoundException    任务不存在（HTTP 边界映射 404）
     */
    public TaskSnapshot findByTaskNo(String taskNo) {
        return toSnapshot(loadTask(taskNo));
    }

    /**
     * 按需触发翻译（2026-10-02 两段式）：主任务 SUCCEEDED 后由用户显式触发——不看翻译的人不花
     * LLM 钱。判定与幂等语义在 {@link TaskPipelineService#translateTask}（未知任务 404、
     * 未就绪 400、翻译中不重入、检查点已在则幂等收敛不发 LLM）；本方法只做装载、委托与
     * <b>重读回视图</b>——translateTask 内部可能已推进翻译子状态（启动 RUNNING / 幂等收敛
     * SUCCEEDED），重读保证 202 响应的视图反映触发后的状态。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 触发后的任务视图（translationStatus 反映翻译子状态）
     * @throws IllegalArgumentException taskNo 为空白（400）或字幕尚未就绪（400）
     * @throws TaskNotFoundException    任务不存在（404）
     */
    public TaskSnapshot translate(String taskNo) {
        VideoTask task = loadTask(taskNo);
        pipelineService.translateTask(task.getTaskNo());
        return repository.findByTaskNo(task.getTaskNo())
                .map(this::toSnapshot)
                .orElseGet(() -> toSnapshot(task));
    }

    /**
     * 组装 SSE 连接建立时的<b>快照事件</b>（design.md「SSE 契约」：DB 快照即权威，事件不落库）。
     * 任务当前状态 + 由 current_stage / status 构造的最近阶段事件；前端刷新页面重连后拿它恢复进度，
     * 无需事件回放。主任务 SUCCEEDED 且翻译子状态存在时按翻译子状态粗粒度还原
     * （见 {@link #stageEventOf}）。
     *
     * <p>快照经 {@code TaskController} 通过 {@code web/sse/TaskSseHub} 推送；阶段事件的进度值取
     * 状态的粗粒度位置（进行中为 0、完成为 100）——精确进度由增量事件携带，快照只保证形状合法。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 快照事件（taskNo + 任务状态 + 最近阶段事件 + 翻译子状态；尚无阶段事件时 stage 为 {@code null}）
     * @throws IllegalArgumentException taskNo 为空白（HTTP 边界映射 400）
     * @throws TaskNotFoundException    任务不存在（HTTP 边界映射 404）
     */
    public TaskProgressMessage snapshot(String taskNo) {
        VideoTask task = loadTask(taskNo);
        return new TaskProgressMessage(task.getTaskNo().value(), task.getStatus(), stageEventOf(task),
                task.getTranslationStatus());
    }

    /**
     * 解析一个产物文件供下载：按 taskNo 定位任务工作区，resolve 产物名并<b>验证未逃逸出工作区目录</b>
     * （防 {@code ../} / 绝对路径 / 反斜杠等路径穿越），再验证确为普通文件。
     *
     * <p>穿越防护的判据是「normalize 后必须仍以工作区目录开头」——URL 编码绕过（{@code %2e%2e} 等）
     * 在 servlet 层解码后落到的就是同一种逃逸形态，此处一并拦下；双重编码产物只是普通不存在的文件名，
     * 自然走「产物不存在」分支。穿越是调用方制造的攻击路径 → {@link IllegalArgumentException} → 400。
     *
     * @param taskNo   对外任务号；不可为空白
     * @param fileName 产物文件名（来自产物清单的 name）；不可为空白
     * @return 产物文件（绝对路径 + 文件名 + 字节数）
     * @throws IllegalArgumentException taskNo / fileName 为空白、产物名越出工作区（穿越攻击）、
     *                                  或产物不存在（均映射 400）
     * @throws TaskNotFoundException    任务不存在（HTTP 边界映射 404）
     */
    public TaskArtifactFile openArtifact(String taskNo, String fileName) {
        VideoTask task = loadTask(taskNo);
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("fileName 不能为空：请从 GET /api/tasks/{taskNo} 响应的 "
                    + "artifacts 清单里取产物名");
        }
        Path dir = workspaceService.workspaceRoot()
                .resolve(task.getWorkspaceDir())
                .normalize();
        Path resolved = dir.resolve(fileName).normalize();
        if (!resolved.startsWith(dir)) {
            // 绝对路径入参 resolve 会整条替换基准目录、../ 与 ..\ 会向上逃逸，两种都到不了这里
            throw new IllegalArgumentException("非法产物路径：" + fileName
                    + "；产物名只能是不含路径分隔符的文件名，请从 GET /api/tasks/{taskNo} 响应的 artifacts 清单里取");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("产物不存在：" + fileName
                    + "；请核对 GET /api/tasks/{taskNo} 响应的 artifacts 清单");
        }
        return new TaskArtifactFile(resolved, resolved.getFileName().toString(), ArtifactFiles.sizeOrZero(resolved));
    }

    /**
     * 按 taskNo 装载任务：空白 taskNo 抛 {@link IllegalArgumentException}（400），
     * 未知 taskNo 抛 {@link TaskNotFoundException}（查询 / 订阅 / 产物三端点统一 404）。
     */
    private VideoTask loadTask(String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        return repository.findByTaskNo(new TaskNo(taskNo))
                .orElseThrow(() -> new TaskNotFoundException(taskNo));
    }

    /**
     * 由持久化状态构造阶段事件（进度取状态粗粒度位置：排队 / 执行中 0、成功 100、失败 0，
     * 失败事件的消息携带终态错误——SSE 上唯一的错误出口；current_stage 尚空时阶段名允许为
     * {@code null}，形状仍合法）。
     *
     * <p><b>翻译子状态覆盖（2026-10-02 两段式）</b>：主任务 SUCCEEDED 且翻译子状态存在时，
     * 快照 / 终态广播的阶段事件改由翻译子状态粗粒度还原（{@link #translationStageEventOf}）——
     * 翻译是主任务成功后的第二生命周期，重连订阅者最关心的是它。主管线终态广播发生时翻译子状态
     * 必为 null（翻译只能在主状态 SUCCEEDED 后触发），不受本分支影响。
     *
     * <p>两个使用者共用一份形状：SSE 快照（{@link #snapshot}）与管线的终态广播
     * （{@code TaskPipelineService}，包内可见 static）——在线订阅者与重连订阅者看到的
     * 终态渲染因此一致。
     */
    static StageEvent stageEventOf(VideoTask task) {
        if (task.getStatus() == TaskStatus.SUCCEEDED && task.getTranslationStatus() != null) {
            return translationStageEventOf(task);
        }
        String stage = task.getCurrentStage();
        return switch (task.getStatus()) {
            case PENDING -> stage == null ? null
                    : new StageEvent(stage, 0, "任务排队中，等待执行", StageEvent.StageState.RUNNING);
            case RUNNING -> stage == null ? null
                    : new StageEvent(stage, 0, "任务执行中", StageEvent.StageState.RUNNING);
            case SUCCEEDED -> stage == null ? null
                    : new StageEvent(stage, 100, "任务已完成", StageEvent.StageState.SUCCEEDED);
            case FAILED -> new StageEvent(stage, 0,
                    StringUtils.hasText(task.getErrorMessage()) ? task.getErrorMessage() : "任务已失败",
                    StageEvent.StageState.FAILED);
        };
    }

    /**
     * 翻译子状态的粗粒度阶段事件（stage 固定 translate，进度取子状态粗粒度位置：
     * 进行中 0、完成 100、失败 0 + 失败信息）。与主任务 {@link #stageEventOf} 同哲学：
     * 精确进度由增量事件携带，快照 / 终态广播只保证形状合法。
     *
     * <p>两个使用者共用一份形状：SSE 快照（经 {@link #stageEventOf} 分派）与管线的翻译终态广播
     * （{@code TaskPipelineService}，包内可见 static）。
     *
     * <p>前置条件：{@code task.getTranslationStatus() != null}（调用点已保证——主管线终态广播时
     * 翻译从未触发，走不到这里）。
     */
    static StageEvent translationStageEventOf(VideoTask task) {
        return switch (task.getTranslationStatus()) {
            case RUNNING -> new StageEvent(StageEvent.STAGE_TRANSLATE, 0, "翻译进行中",
                    StageEvent.StageState.RUNNING);
            case SUCCEEDED -> new StageEvent(StageEvent.STAGE_TRANSLATE, 100, "翻译完成",
                    StageEvent.StageState.SUCCEEDED);
            case FAILED -> new StageEvent(StageEvent.STAGE_TRANSLATE, 0,
                    StringUtils.hasText(task.getErrorMessage()) ? task.getErrorMessage() : "翻译已失败",
                    StageEvent.StageState.FAILED);
        };
    }

    /**
     * 落库 PENDING 并异步启动管线；两条路线收敛于此，调用前各自完成查重与副作用准备。
     * dirName 由路线显式传入：上传路线的目录标签是原始文件名、LocalPath 输入的标签却是
     * source.mp4，从输入重算会写错 workspace_dir。
     */
    private TaskSnapshot submit(MediaInput input, TaskInputType inputType, String sourceUri,
                            WorkspaceKey workspaceKey, String dirName) {
        VideoTask task = VideoTask.newPending(inputType, sourceUri, workspaceKey, dirName);
        VideoTask inserted = repository.insert(task);
        // 不打 workspaceDir：URL 路线的目录名内嵌 sanitize 后的 URL（查询串参数值原样保留），
        // 上传路线内嵌原始文件名——均属用户资源，踩日志脱敏红线；需要时按 taskNo 查库
        log.info("任务已创建: taskNo={}, inputType={}, status={}",
                inserted.getTaskNo().value(), inserted.getInputType(), inserted.getStatus());
        pipelineService.execute(inserted.getTaskNo());
        return toSnapshot(inserted);
    }

    /**
     * 查重：同工作区 key 且 PENDING / RUNNING → 抛冲突异常（409，响应体附既有任务）。
     */
    private void rejectIfActive(WorkspaceKey workspaceKey) {
        Optional<VideoTask> active = repository.findActiveByWorkspaceKey(workspaceKey);
        if (active.isPresent()) {
            throw new TaskConflictException(toSnapshot(active.get()));
        }
    }

    /**
     * 把上传流写进临时文件，边写边算 sha256，返回内容 key（摘要前 12 位十六进制的工作区短哈希，
     * 宽度与格式单源在 {@link WorkspaceKey#ofDigest}）。
     */
    private WorkspaceKey streamToTemp(InputStream content, Path temp) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream digesting = new DigestInputStream(content, digest)) {
            Files.copy(digesting, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        return WorkspaceKey.ofDigest(digest.digest());
    }

    /**
     * 临时文件归位：建内容寻址工作区目录，临时文件改名成 {@code MediaInput.UPLOAD_SOURCE_FILE_NAME}
     * （同一根下，rename 语义；文件名单源在 {@link MediaInput}）。
     *
     * @return source.mp4 绝对路径（管线重建输入时按同一名字定位）
     */
    private Path settleUpload(Path temp, String originalFileName, WorkspaceKey workspaceKey) throws IOException {
        Path dir = workspaceService.workspaceFor(originalFileName, workspaceKey);
        Path source = dir.resolve(MediaInput.UPLOAD_SOURCE_FILE_NAME);
        Files.move(temp, source, StandardCopyOption.REPLACE_EXISTING);
        return source;
    }

    /**
     * 领域对象 → 应用层读模型快照（零 web 注解；对外契约注解集中在 {@code web/dto/TaskResponse}，
     * 由其静态工厂映射）。内部字段（id / workspace_key / workspace_dir）不出境；
     * 产物清单由工作区扫描得出（刚提交 / 目录缺失时为空表）。
     */
    private TaskSnapshot toSnapshot(VideoTask task) {
        return new TaskSnapshot(task.getTaskNo().value(), task.getInputType(), task.getSourceUri(),
                task.getStatus(), task.getTranslationStatus(), task.getCurrentStage(), task.getErrorMessage(),
                task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt(),
                scanArtifacts(task.getTaskNo().value(), task.getWorkspaceDir()));
    }

    /**
     * 扫描任务工作区，收集对外可见的产物清单（可见性判据单源在 {@link TaskArtifactScan}）。
     *
     * <p>目录不存在（URL 任务尚未建目录 / 环境差异）或列举失败时返回空表：清单是展示信息，
     * 读不出来不该把整个任务查询打挂。
     *
     * @param workspaceDir 任务工作区相对路径（相对 vidsage.workspace.root）；可为 {@code null}
     * @return 产物清单（按文件名排序）；无产物时为空表
     */
    private List<TaskArtifact> scanArtifacts(String taskNo, String workspaceDir) {
        if (!StringUtils.hasText(workspaceDir)) {
            return List.of();
        }
        Path dir = workspaceService.workspaceRoot().resolve(workspaceDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(TaskArtifactScan::isVisible)
                    .sorted()
                    .map(name -> new TaskArtifact(name, ArtifactFiles.sizeOrZero(dir.resolve(name))))
                    .toList();
        } catch (IOException e) {
            // 不打目录名：它内嵌 URL / 原始文件名（用户资源，脱敏红线），按 taskNo 查库即可定位
            log.warn("任务产物清单扫描失败，按空表返回: taskNo={}, reason={}", taskNo, e.getMessage());
            return List.of();
        }
    }
}
