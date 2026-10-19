package com.vidsage.application.task;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.subtitle.pipeline.SubtitlePipeline;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.ResolvedMediaIdentity;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.MediaIdentityResolver;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 任务往库里写得动的四个入口：URL 提交、上传、重新生成字幕、翻译。
 *
 * <p>两条提交路线（URL / 上传）收敛到 {@link #handleSubmit}，差别只在身份键怎么算：
 * URL 路线走规范化策略（可能跟一次短链跳转），上传路线流式落盘并边写边算内容摘要。
 * <b>两者都在事务外完成</b>——身份键计算与文件 IO 都是慢操作，事务里不放慢操作。
 *
 * <p>两行落库（workspace + subtitle_task）在同一事务里同生，插 run 抢互斥也在同一事务里；
 * 并发提交同一个新来源时撞唯一键不报冲突，而是在同一事务里锁定读重查（见
 * {@link #findOrCreateWorkspace} / {@link #findOrCreateTask}）。事务由 {@link TransactionTemplate}
 * 显式圈定：身份键在事务外算完再进来，同类内直接调用，不隔 Spring 代理边界。
 *
 * <p>未知 taskNo 一律抛 {@link TaskNotFoundException} → 404；同一任务已有 run 在跑抛
 * {@link TaskConflictException} → 409（响应体带 {@code {taskNo, runNo}}）。
 * 「产物尚未就绪」是 {@link IllegalArgumentException} → 400，与 409 分得很清。
 *
 * <p>查询与 SSE 快照走 {@link SubtitleTaskQueryService}，本类不读。
 */
@Slf4j
@Service
public class SubtitleTaskService {

    /**
     * 临时文件名前缀：上传先落根目录临时文件算内容摘要，再按身份键归位。
     */
    private static final String TEMP_FILE_PREFIX = "upload-";

    /**
     * 临时文件名后缀：明确「未归位」语义，也便于排查残留。
     */
    private static final String TEMP_FILE_SUFFIX = ".part";

    private final WorkspaceRepository workspaceRepository;
    private final SubtitleTaskRepository subtitleTaskRepository;
    private final TaskRunRepository taskRunRepository;
    private final SubtitleKeywordRepository keywordRepository;
    private final MediaIdentityResolver mediaIdentityResolver;
    private final TaskRunSupervisor supervisor;
    private final SubtitlePipeline pipeline;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;
    private final SubtitleTaskFinder finder;
    private final SubtitleArtifactStore artifactStore;
    private final TransactionTemplate transactionTemplate;

    public SubtitleTaskService(WorkspaceRepository workspaceRepository,
                               SubtitleTaskRepository subtitleTaskRepository,
                               TaskRunRepository taskRunRepository,
                               SubtitleKeywordRepository keywordRepository,
                               MediaIdentityResolver mediaIdentityResolver,
                               TaskRunSupervisor supervisor,
                               SubtitlePipeline pipeline,
                               WorkspaceDirectoryManager workspaceDirectoryManager,
                               SubtitleTaskFinder finder,
                               SubtitleArtifactStore artifactStore,
                               PlatformTransactionManager transactionManager
    ) {
        this.workspaceRepository = workspaceRepository;
        this.subtitleTaskRepository = subtitleTaskRepository;
        this.taskRunRepository = taskRunRepository;
        this.keywordRepository = keywordRepository;
        this.mediaIdentityResolver = mediaIdentityResolver;
        this.supervisor = supervisor;
        this.pipeline = pipeline;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
        this.finder = finder;
        this.artifactStore = artifactStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * URL 提交路线：算身份键（事务外）→ 事务内落库 + 抢互斥 → 派发主管线。
     *
     * @param url      公网媒体页面地址（B 站 / YouTube 等）；不可为空白
     * @param keywords 可选关键词清单；{@code null}（字段缺席）= 本次不带关键词、不动库里那份，
     *                 {@code []}（显式空数组）= 落一份空清单（该任务此后按原样清洗）
     * @return 本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException url 为空白，或关键词超限（单条 &gt; 50 字 / 总数 &gt; 50）
     * @throws TaskConflictException    该任务已有一次执行在跑（409）
     */
    public TaskRunRef submitUrl(String url, List<String> keywords) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 不能为空：请传公网媒体页面地址（如 B 站 / YouTube 链接），"
                    + "上传本地文件请走 POST /api/tasks/upload");
        }
        // 关键词上限校验放在最早：错在调用方 → IAE → 400，且要早于一切 I/O
        SubtitleKeywords validated = keywords == null ? null : new SubtitleKeywords(keywords);
        String trimmedUrl = url.trim();
        // 身份键要走网络（短链跟跳转），必须留在事务外
        ResolvedMediaIdentity identity = mediaIdentityResolver.resolve(trimmedUrl);
        return handleSubmit(SourceType.URL, identity.sourceKey(), trimmedUrl, identity.canonicalUrl(), validated);
    }

    /**
     * 上传提交路线：流式写盘边写边算内容摘要（事务外）→ 按内容归位到工作区 → 事务内落库 + 抢互斥。
     *
     * <p>归位用同盘 rename，不整份复制；同一文件重复上传命中同一目录，既有产物天然可断点复用。
     *
     * @param content          上传文件内容流；不可为 {@code null}（空文件由 HTTP 边界卫语句拦下）
     * @param originalFileName 上传文件原始名（只进 source_uri，不进日志）；可空白
     * @param keywords         可选关键词清单；{@code null} = 本次不带关键词、不动库里那份
     * @return 本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException 内容流为 {@code null}，或关键词超限（单条 &gt; 50 字 / 总数 &gt; 50）
     * @throws TaskConflictException    该任务已有一次执行在跑（409）
     * @throws IllegalStateException    落盘失败（工作区根不可写等）
     */
    public TaskRunRef submitUpload(InputStream content, String originalFileName, List<String> keywords) {
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
                SourceKey sourceKey = new SourceKey(streamToTemp(content, temp));
                settleUpload(temp, sourceKey);
                settled = true;
                return handleSubmit(SourceType.UPLOAD, sourceKey, originalFileName, null, validated);
            } finally {
                if (!settled) {
                    // 归位失败 / 提交抛错：临时文件必须清掉，不让半成品留在工作区根
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
     * 重新生成字幕（RECLEAN run）：按当前关键词只重跑清洗，不重跑转写、不重抽音频。
     *
     * @param taskNo   对外任务号；不可为空白
     * @param keywords 请求体里的关键词：{@code null} = 不改变已有列表；{@code []} = 清空
     * @return 本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException taskNo 为空白、关键词超限、或原始字幕尚未就绪（400）
     * @throws TaskNotFoundException    任务不存在（404）
     * @throws TaskConflictException    该任务已有一次执行在跑（409）
     */
    public TaskRunRef regenerate(String taskNo, List<String> keywords) {
        SubtitleKeywords validated = keywords == null ? null : new SubtitleKeywords(keywords);
        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        SubtitleArtifact originalArtifact = requireReadableArtifact(task, workspace, ArtifactType.ORIGINAL,
                "原始转写产物（ORIGINAL）");
        TaskRun run = supervisor.start(task, TaskRunType.RECLEAN, null).orElseThrow(() -> conflictFor(task));
        if (validated != null) {
            keywordRepository.replace(task.getId(), validated);
        }
        SubtitleKeywords effectiveKeywords = validated != null ? validated : keywordRepository.findByTaskId(task.getId());
        RunRef ref = RunRef.of(task.getTaskNo(), run);
        log.info("字幕重新生成已触发: taskNo={}, runNo={}, keywordsSupplied={}",
                ref.taskNo().value(), ref.runNo(), validated != null);
        pipeline.regenerate(run, ref, workspace, effectiveKeywords, originalArtifact.getId());
        return new TaskRunRef(ref.taskNo().value(), ref.runNo());
    }

    /**
     * 翻译（TRANSLATE run）：在已有清洗产物上产出译文。
     *
     * @param taskNo         对外任务号；不可为空白
     * @param sourceLanguage 源语种
     * @param targetLanguage 目标语种
     * @return 本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException taskNo 为空白、语种不在白名单、或清洗字幕尚未就绪（400）
     * @throws TaskNotFoundException    任务不存在（404）
     * @throws TaskConflictException    该任务已有一次执行在跑（409）
     */
    public TaskRunRef translate(String taskNo, String sourceLanguage, String targetLanguage) {
        SupportedLanguage source = SupportedLanguage.parse(sourceLanguage);
        SupportedLanguage target = SupportedLanguage.parse(targetLanguage);
        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        requireReadableArtifact(task, workspace, ArtifactType.CLEANED, "清洗后的字幕产物（CLEANED）");
        TaskRun run = supervisor.start(task, TaskRunType.TRANSLATE, target.getCode())
                .orElseThrow(() -> conflictFor(task));
        RunRef ref = RunRef.of(task.getTaskNo(), run);
        log.info("字幕翻译已触发: taskNo={}, runNo={}, source={}, target={}",
                ref.taskNo().value(), ref.runNo(), source.getCode(), target.getCode());
        pipeline.translate(run, ref, workspace, source, target);
        return new TaskRunRef(ref.taskNo().value(), ref.runNo());
    }

    /**
     * 两条提交路线收敛于此：事务内落 workspace / subtitle_task、抢 MAIN 互斥、覆盖写关键词，
     * 事务提交后派发主管线。身份键已在事务外算好，这里只做落库。
     */
    private TaskRunRef handleSubmit(SourceType sourceType, SourceKey sourceKey, String sourceUri,
                                    String canonicalUrl, SubtitleKeywords keywords) {
        Submission submission = transactionTemplate.execute(status -> {
            Workspace workspace = findOrCreateWorkspace(sourceType, sourceKey, sourceUri, canonicalUrl);
            SubtitleTask task = findOrCreateTask(workspace);
            TaskRun run = supervisor.start(task, TaskRunType.MAIN, null).orElseThrow(() -> conflictFor(task));
            if (keywords != null) {
                keywordRepository.replace(task.getId(), keywords);
            }
            return new Submission(workspace, task, run);
        });
        RunRef ref = RunRef.of(submission.task().getTaskNo(), submission.run());
        log.info("任务已创建: taskNo={}, runNo={}, sourceType={}",
                ref.taskNo().value(), ref.runNo(), sourceType);
        // 事务已提交：这时才把异步执行体派发出去，避免执行体读不到还没提交的行
        pipeline.run(submission.run(), ref, submission.workspace());
        return new TaskRunRef(ref.taskNo().value(), ref.runNo());
    }

    /**
     * 命中就返回既有身份，未命中就插一行。撞唯一键（两个请求同时提交同一个新来源）时
     * 不报冲突、不重试插入、不回滚重开，而是在<b>同一事务</b>里锁定读重查——{@code FOR SHARE}
     * 绕过一致性快照，读到对方刚提交的行。
     */
    private Workspace findOrCreateWorkspace(SourceType sourceType, SourceKey sourceKey, String sourceUri,
                                            String canonicalUrl) {
        Optional<Workspace> existing = workspaceRepository.findBySourceTypeAndSourceKey(sourceType, sourceKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return workspaceRepository.insert(Workspace.of(sourceType, sourceKey, sourceUri, canonicalUrl));
        } catch (DuplicateKeyException e) {
            return workspaceRepository.findForShareBySourceTypeAndSourceKey(sourceType, sourceKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "并发提交同一媒体时未能读回已落库的 workspace：sourceType=" + sourceType, e));
        }
    }

    /**
     * 一个媒体对应一个任务：命中就返回既有任务，未命中就插一行。撞唯一键的处理与
     * {@link #findOrCreateWorkspace} 同形，也要锁定读——普通 SELECT 读不到对方刚提交的行。
     */
    private SubtitleTask findOrCreateTask(Workspace workspace) {
        Optional<SubtitleTask> existing = subtitleTaskRepository.findByWorkspaceId(workspace.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return subtitleTaskRepository.insert(SubtitleTask.of(workspace.getId()));
        } catch (DuplicateKeyException e) {
            return subtitleTaskRepository.findForShareByWorkspaceId(workspace.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "并发提交同一媒体时未能读回已落库的 subtitle_task：workspaceId="
                                    + workspace.getId(), e));
        }
    }

    /**
     * 撞互斥键时的冲突：插 run 只告诉「有人正在跑」，既有 run 的序号要再取一次
     * （取不到说明状态自相矛盾，属服务端缺陷）。
     *
     * <p>取既有 run 用<b>锁定读</b>：并发分支里整个提交跑在同一事务、快照已陈旧，
     * 普通 SELECT 看不到对方刚提交的 RUNNING 行，会误判成「撞键却无既有 run」而 500。
     */
    private TaskConflictException conflictFor(SubtitleTask task) {
        int runNo = taskRunRepository.findLatestForShareByTaskId(task.getId())
                .map(TaskRun::getRunNo)
                .orElseThrow(() -> new IllegalStateException(
                        "互斥撞键但取不到既有 run：taskId=" + task.getId()));
        return new TaskConflictException(task.getTaskNo().value(), runNo);
    }

    /**
     * 前置守卫：该任务必须有一版可读的当前产物（行是 READY，而且该带的文件都在盘上），并把它交出来。
     *
     * <p>返回的是<b>当下</b>那一版，调用方拿它的 id 填新产物的溯源字段；那个字段只用于审计，
     * 不参与任何读取判据。
     *
     * <p>读路径只把缺文件的产物当没有，<b>不做自愈重跑</b>——重新生成由用户重新触发。
     */
    private SubtitleArtifact requireReadableArtifact(SubtitleTask task, Workspace workspace,
                                                     ArtifactType type, String description) {
        Path workDir = finder.workspaceDir(workspace);
        return artifactStore.findReadableReady(task.getId(), type, "", workDir)
                .orElseThrow(() -> new IllegalArgumentException("字幕尚未就绪：该任务还没有可读的 " + description
                        + "；请先等主管线跑完（可查 GET /api/tasks/{taskNo} 或订阅其 SSE 事件）再试"));
    }

    /**
     * 把上传流写进临时文件，边写边算 sha256，返回<b>全 64 位十六进制</b>的内容身份
     * （{@code workspace.source_key} 是 VARCHAR(512)，装得下整条摘要）。
     */
    private String streamToTemp(InputStream content, Path temp) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream digesting = new DigestInputStream(content, digest)) {
            Files.copy(digesting, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 临时文件归位：按身份键建工作区目录，临时文件改名成
     * {@code MediaInput.UPLOAD_SOURCE_FILE_NAME}（同一根下，rename 语义；文件名单源在 {@link MediaInput}）。
     */
    private void settleUpload(Path temp, SourceKey sourceKey) throws IOException {
        Path dir = workspaceDirectoryManager.ensureWorkspaceDirectory(sourceKey);
        Files.move(temp, dir.resolve(MediaInput.UPLOAD_SOURCE_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 一次提交在事务内凑齐的三件东西：媒体身份、字幕任务、本次执行。
     */
    private record Submission(Workspace workspace, SubtitleTask task, TaskRun run) {
    }
}
