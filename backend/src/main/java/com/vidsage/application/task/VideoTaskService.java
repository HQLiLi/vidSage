package com.vidsage.application.task;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.notify.TaskProgressNotifier;
import com.vidsage.application.task.pipeline.SubtitlePipeline;
import com.vidsage.application.task.query.TaskArtifact;
import com.vidsage.application.task.query.TaskArtifactFile;
import com.vidsage.application.task.query.TaskSnapshot;
import com.vidsage.application.task.query.KeywordsSnapshot;
import com.vidsage.application.task.session.TaskSessionRegistry;
import com.vidsage.domain.media.model.MediaFileInfo;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.WorkspaceDir;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.policy.TaskStageEvent;
import com.vidsage.domain.task.port.VideoTaskRepository;
import com.vidsage.shared.fs.ArtifactFiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

/**
 * 任务提交与查询的唯一入口：校验、查重、落库、上传落盘（design.md「数据流」）。
 * 异步执行不在这里——落库后委托 {@link SubtitlePipeline#execute} 启动管线，HTTP 秒级返回。
 *
 * <p>两条提交路线收敛到 {@link #handleSubmit}：
 * <ul>
 *   <li><b>URL</b>（POST /api/tasks）：workspace_key = {@code RemoteUrl.getContentKey → WorkspaceKey.ofHash}，
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
public class VideoTaskService {

    /**
     * 临时文件名前缀：上传先落根目录临时文件算内容摘要，再按 key 归位。
     */
    private static final String TEMP_FILE_PREFIX = "upload-";

    /**
     * 临时文件名后缀：明确「未归位」语义，也便于排查残留。
     */
    private static final String TEMP_FILE_SUFFIX = ".part";

    private final VideoTaskRepository repository;
    private final SubtitleKeywordRepository keywordRepository;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;
    private final SubtitlePipeline pipeline;
    private final TaskProgressNotifier taskProgressNotifier;
    private final TaskSessionRegistry taskSessionRegistry;


    public VideoTaskService(VideoTaskRepository repository,
                            SubtitleKeywordRepository keywordRepository,
                            SubtitlePipeline pipeline,
                            WorkspaceDirectoryManager workspaceDirectoryManager,
                            TaskProgressNotifier taskProgressNotifier,
                            TaskSessionRegistry taskSessionRegistry
    ) {
        this.repository = repository;
        this.keywordRepository = keywordRepository;
        this.pipeline = pipeline;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
        this.taskProgressNotifier = taskProgressNotifier;
        this.taskSessionRegistry = taskSessionRegistry;
    }

    /**
     * URL 提交路线：算工作区 key → 查重 → 落库 → 异步启动管线。
     *
     * @param url      公网媒体页面地址（B 站 / YouTube 等）；不可为空白
     * @param keywords 可选关键词清单；{@code null}（字段缺席）= 本次不带关键词、不动库里那份，
     *                 {@code []}（显式空数组）= 落一份空清单（该工作区此后按原样清洗）
     * @return 新任务的 PENDING 视图
     * @throws IllegalArgumentException url 为空白，或关键词超限（单条 &gt; 50 字 / 总数 &gt; 50）
     * @throws TaskConflictException    同 URL 已有进行中任务（409）
     */
    public TaskSnapshot submitUrl(String url, List<String> keywords) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 不能为空：请传公网媒体页面地址（如 B 站 / YouTube 链接），"
                    + "上传本地文件请走 POST /api/tasks/upload");
        }
        // 关键词上限校验放在最早：错在调用方 → IAE → 400，且要早于一切 I/O（error-handling 边界卫语句）
        SubtitleKeywords validated = keywords == null ? null : new SubtitleKeywords(keywords);
        MediaInput.RemoteUrl input = new MediaInput.RemoteUrl(url.trim());
        WorkspaceKey workspaceKey = input.getContentKey(MediaFileInfo.NOT_APPLICABLE);
        // 查重，判断是否已经有相同的工作区目录
        rejectIfActive(workspaceKey);
        String directoryName = WorkspaceDir.of(input.value(), workspaceKey).value();
        return handleSubmit(input, TaskInputType.URL, input.value(), workspaceKey, directoryName, validated);
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
     * @param keywords         可选关键词清单；{@code null} = 本次不带关键词、不动库里那份
     * @return 新任务的 PENDING 视图
     * @throws IllegalArgumentException 内容流为 {@code null}，或关键词超限（单条 &gt; 50 字 / 总数 &gt; 50）
     * @throws TaskConflictException    同内容已有进行中任务（409）
     * @throws IllegalStateException    落盘失败（工作区根不可写等）
     */
    public TaskSnapshot submitUpload(InputStream content, String originalFileName, List<String> keywords) {
        if (content == null) {
            throw new IllegalArgumentException("上传内容不能为空：请重新选择文件后再提交");
        }
        // 与 URL 路线同一口径：超限在流式落盘之前拦下（400），不留半成品
        SubtitleKeywords validated = keywords == null ? null : new SubtitleKeywords(keywords);
        try {
            Path root = workspaceDirectoryManager.getWorkspaceRoot();
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
                return handleSubmit(input, TaskInputType.UPLOAD, originalFileName, workspaceKey,
                        source.getParent().getFileName().toString(), validated);
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
     * LLM 钱。判定与幂等语义在 {@link SubtitlePipeline#translateTask}（未知任务 404、
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
        pipeline.translateTask(task.getTaskNo());
        return repository.findByTaskNo(task.getTaskNo())
                .map(this::toSnapshot)
                .orElseGet(() -> toSnapshot(task));
    }

    /**
     * 重新生成字幕（后置会话）的同步段：全部判定在这里完成，抢到登记才把异步体交给管线。
     * 判定顺序（design §3.2；卫语句早于 I/O、409 在 202 之前）：
     * <ol>
     *   <li>关键词构造与上限校验（非 null 时）→ IAE 400，<b>不落库、不重跑</b>（AC8）；</li>
     *   <li>装载任务 → 404；</li>
     *   <li>主状态非 SUCCEEDED → IAE 400「字幕尚未就绪」（与 translate 同一文案口径）；</li>
     *   <li>抢工作区登记 → 抢不到 409（附既有任务快照——冲突的出路是去订阅它）；</li>
     *   <li>keywords 非 null 落库（null = 不动已有列表）→ 读回生效清单 → 委派管线异步体。</li>
     * </ol>
     *
     * @param taskNo  对外任务号
     * @param keywords 请求体里的关键词：{@code null} = 不改变已有列表；{@code []} = 清空
     * @return 任务视图（主状态本就不变，视图反映触发后的快照）
     * @throws TaskNotFoundException    任务不存在（404）
     * @throws IllegalArgumentException 字幕尚未就绪（400）、关键词超限（400）
     * @throws TaskConflictException    同工作区已有后置会话在跑（409）
     */
    public TaskSnapshot regenerate(String taskNo, List<String> keywords) {
        SubtitleKeywords validated = keywords == null ? null : new SubtitleKeywords(keywords);
        VideoTask task = loadTask(taskNo);
        if (task.getStatus() != TaskStatus.SUCCEEDED) {
            throw new IllegalArgumentException("字幕尚未就绪：任务状态为 " + task.getStatus()
                    + "，重新生成只能在任务成功（SUCCEEDED）后触发；请先等主管线完成（可查 "
                    + "GET /api/tasks/" + taskNo + " 或订阅其 SSE 事件）再试");
        }
        // 工作区级互斥在同步段判（202 之前）：抢不到就 409，不排队等锁——202 后排队是骗人。
        // 文案不能用查重那套「请勿重复提交」：冲突方手里就是这个任务号（status 还是 SUCCEEDED）
        if (!taskSessionRegistry.tryStart(task.getWorkspaceKey(), SubtitleStageName.CLEAN_SUBTITLES.getStageName())) {
            throw new TaskConflictException(toSnapshot(task),
                    "该工作区已有一次字幕重新生成在跑：taskNo=" + task.getTaskNo().value()
                            + "；请等它跑完再触发，或用响应体 existingTask 里的 taskNo 订阅其 SSE 事件看进度");
        }
        boolean handedOff = false;
        // tryStart 成功后的全部代码就在下面这个 try 里——落库 / 委派任何一步抛了，
        // 都由 finally 的 handedOff 判据退登记，否则该工作区留下永远在跑的幽灵（AC10）
        try {
            if (validated != null) {
                keywordRepository.replace(task.getWorkspaceKey(), validated);
            }
            SubtitleKeywords effective = validated != null ? validated
                    : keywordRepository.findByWorkspaceKey(task.getWorkspaceKey());
            pipeline.regenerateSubtitles(task, effective);
            handedOff = true;
        } finally {
            // 正常路径登记由异步体 finally 退；只有「拿到登记后同步段就抛了」这条异常路径在本层退。
            // handedOff 让两种退出互斥：异步体已经在跑时绝不能在同步段把它退掉
            if (!handedOff) {
                taskSessionRegistry.finish(task.getWorkspaceKey());
            }
        }
        log.info("字幕重新生成已触发: taskNo={}, keywordsSupplied={}", taskNo, validated != null);
        return toSnapshot(task);
    }

    /**
     * 读回该任务工作区的关键词清单与指纹（前端拿指纹与产物里的 keywordsFingerprint 比对，
     * 判断「这份字幕对不对得上当前关键词」）。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 关键词清单（用户填写顺序）与指纹；从没填过时为空清单（不是 null）
     * @throws TaskNotFoundException 任务不存在（404）
     */
    public KeywordsSnapshot findKeywords(String taskNo) {
        VideoTask task = loadTask(taskNo);
        SubtitleKeywords keywords = keywordRepository.findByWorkspaceKey(task.getWorkspaceKey());
        return new KeywordsSnapshot(keywords.values(), keywords.computeFingerprint());
    }

    /**
     * 组装 SSE 连接建立时的<b>快照事件</b>
     * 任务当前状态 + 由 current_stage / status 构造的最近阶段事件；前端刷新页面重连后拿它恢复进度，
     * 无需事件回放。主任务 SUCCEEDED 且翻译子状态存在时按翻译子状态粗粒度还原
     * （见 {@link TaskStageEvent#from}）。
     *
     * <p>快照经 {@code TaskController} 通过 {@code web/sse/TaskSseConnectionManager} 推送；阶段事件的进度值取
     * 状态的粗粒度位置（进行中为 0、完成为 100）——精确进度由增量事件携带，快照只保证形状合法。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 快照事件（taskNo + 任务状态 + 最近阶段事件 + 翻译子状态；尚无阶段事件时 stage 为 {@code null}）
     * @throws IllegalArgumentException taskNo 为空白（HTTP 边界映射 400）
     * @throws TaskNotFoundException    任务不存在（HTTP 边界映射 404）
     */
    public TaskProgressMessage snapshot(String taskNo) {
        VideoTask task = loadTask(taskNo);
        return taskProgressNotifier.createSnapshot(task);
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
        Path dir = workspaceDirectoryManager.getWorkspaceRoot()
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
     * 落库 PENDING 并异步启动管线；两条路线收敛于此，调用前各自完成查重与副作用准备。
     * dirName 由路线显式传入：上传路线的目录标签是原始文件名、LocalPath 输入的标签却是
     * source.mp4，从输入重算会写错 workspace_dir。
     */
    private TaskSnapshot handleSubmit(MediaInput input, TaskInputType inputType, String sourceUri,
                                      WorkspaceKey workspaceKey, String dirName, SubtitleKeywords keywords) {
        // 关键词先于任务落库：超限早在入口校验过，这里只剩「存」这一个动作（null = 本次不带关键词，不动库）
        if (keywords != null) {
            keywordRepository.replace(workspaceKey, keywords);
        }
        VideoTask task = VideoTask.newPending(inputType, sourceUri, workspaceKey, dirName);
        VideoTask inserted = repository.insert(task);
        log.info("任务已创建: taskNo={}, inputType={}, status={}",
                inserted.getTaskNo().value(), inserted.getInputType(), inserted.getStatus());
        pipeline.execute(inserted.getTaskNo());
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
        Path dir =
                workspaceDirectoryManager.ensureWorkspaceDirectory(originalFileName, workspaceKey);
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
     * 扫描任务工作区，收集对外可见的产物清单
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
        Path dir = workspaceDirectoryManager.getWorkspaceRoot().resolve(workspaceDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(SubtitleArtifactSpec::isDownloadable)
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
