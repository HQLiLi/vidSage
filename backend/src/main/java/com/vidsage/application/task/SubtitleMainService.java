package com.vidsage.application.task;

import com.vidsage.application.media.IntermediateFileOwner;
import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.subtitle.pipeline.SubtitleCleanupCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitleFingerprintCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitlePreparationCoordinator;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.ResolvedMediaIdentity;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.MediaIdentityResolver;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import com.vidsage.shared.error.ThrowableUtils;
import com.vidsage.shared.media.MediaInputUtils;
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
 * 提交媒体转字幕任务（MAIN run）的完整流程：同步段算身份键、落库、抢互斥，异步段跑
 * 指纹探测 → 音频准备与转写 → 清洗，最后收口终态。
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
 * <p>同一任务已有 run 在跑抛 {@link TaskConflictException} → 409（响应体带
 * {@code {taskNo, runNo}}）。本类只写不读：任务查询与 SSE 快照走 {@link SubtitleTaskQueryService}。
 */
@Slf4j
@Service
public class SubtitleMainService {

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
    private final SubtitleKeywordRepository keywordRepository;
    private final MediaIdentityResolver mediaIdentityResolver;
    private final TaskRunSupervisor supervisor;
    private final TaskConcurrencyGate concurrencyGate;
    private final SubtitlePreparationCoordinator subtitlePreparationCoordinator;
    private final SubtitleCleanupCoordinator cleanupStage;
    private final SubtitleArtifactStore artifactStore;
    private final SubtitleFingerprintCoordinator fingerprintCoordinator;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;
    private final TransactionTemplate transactionTemplate;

    public SubtitleMainService(WorkspaceRepository workspaceRepository,
                               SubtitleTaskRepository subtitleTaskRepository,
                               SubtitleKeywordRepository keywordRepository,
                               MediaIdentityResolver mediaIdentityResolver,
                               TaskRunSupervisor supervisor,
                               TaskConcurrencyGate concurrencyGate,
                               SubtitlePreparationCoordinator subtitlePreparationCoordinator,
                               SubtitleCleanupCoordinator cleanupStage,
                               SubtitleArtifactStore artifactStore,
                               SubtitleFingerprintCoordinator fingerprintCoordinator,
                               WorkspaceDirectoryManager workspaceDirectoryManager,
                               PlatformTransactionManager transactionManager
    ) {
        this.workspaceRepository = workspaceRepository;
        this.subtitleTaskRepository = subtitleTaskRepository;
        this.keywordRepository = keywordRepository;
        this.mediaIdentityResolver = mediaIdentityResolver;
        this.supervisor = supervisor;
        this.concurrencyGate = concurrencyGate;
        this.subtitlePreparationCoordinator = subtitlePreparationCoordinator;
        this.cleanupStage = cleanupStage;
        this.artifactStore = artifactStore;
        this.fingerprintCoordinator = fingerprintCoordinator;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * URL 提交路线：算身份键（事务外）→ 事务内落库 + 抢互斥 → 交给并发闸门执行主管线。
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
     * 两条提交路线收敛于此：事务内落 workspace / subtitle_task、抢 MAIN 互斥、覆盖写关键词，
     * 事务提交后把执行体交给并发闸门。身份键已在事务外算好，这里只做落库。
     */
    private TaskRunRef handleSubmit(SourceType sourceType, SourceKey sourceKey, String sourceUri,
                                    String canonicalUrl, SubtitleKeywords keywords) {
        Submission submission = transactionTemplate.execute(status -> {
            // 数据库创建记录
            Workspace workspace = findOrCreateWorkspace(sourceType, sourceKey, sourceUri, canonicalUrl);
            SubtitleTask task = findOrCreateTask(workspace);
            TaskRun run = supervisor.start(task, TaskRunType.MAIN, null).orElseThrow(() -> supervisor.buildTaskConflictException(task));
            if (keywords != null) {
                keywordRepository.replace(task.getId(), keywords);
            }
            return new Submission(workspace, task, run);
        });
        RunRef ref = RunRef.of(submission.task().getTaskNo(), submission.run());
        log.info("任务已创建: taskNo={}, runNo={}, sourceType={}",
                ref.taskNo().value(), ref.runNo(), sourceType);
        // 事务已提交：这时才把异步执行体派发出去，避免执行体读不到还没提交的行
        concurrencyGate.submit(() -> runPipeline(submission.run(), ref, submission.workspace()),
                () -> {
                    log.warn("主管线等待执行配额时被中断: taskNo={}, runNo={}",
                            ref.taskNo().value(), ref.runNo());
                    supervisor.finish(ref, submission.run(), "任务在等待执行配额时被中断：请重新提交");
                });
        return new TaskRunRef(ref.taskNo().value(), ref.runNo());
    }

    /**
     * 同步执行完整主管线：指纹探测 → 音频准备与转写 → 清洗。
     *
     * <p>收口是无条件的：正常走到底收 SUCCEEDED，任一阶段抛出收 FAILED（把异常消息当失败原因，
     * 拿不到消息就退回异常类名）。
     */
    void runPipeline(TaskRun run, RunRef ref, Workspace workspace) {
        try {
            Path workDir = workspaceDirectoryManager.ensureWorkspaceDirectory(workspace.getSourceKey());
            MediaInput input = MediaInputUtils.from(workspace, workDir);
            log.info("任务开始执行: taskNo={}, runNo={}, sourceType={}",
                    ref.taskNo().value(), ref.runNo(), workspace.getSourceType());
            ProgressListener listener = supervisor.listener(ref, run);

            // 指纹探测在媒体获取之前：内容变了就先把旧产物作废，后面每一步都不会再拿旧文件复用
            fingerprintCoordinator.detectContentChange(run.getTaskId(), workspace, input, workDir, listener);
            IntermediateFileOwner owner = new IntermediateFileOwner(run.getTaskId(), run.getId());

            // 阶段 1：先问能不能复用，命中就整条链不动；不复用才建占位、真跑、落定
            Optional<SubtitleArtifact> intactOriginal = artifactStore
                    .findIntactArtifact(run.getTaskId(), ArtifactType.ORIGINAL, "", workDir);
            SubtitleArtifact original;
            if (subtitlePreparationCoordinator.reuseIfAvailable(workDir, intactOriginal.isPresent(), listener).isPresent()) {
                // 复用命中：产物表那一侧必然有这条完好行（判据要求它先成立），整条链一个字节不动
                original = intactOriginal.orElseThrow(() -> new IllegalStateException(
                        "产物表说没有完好的原始产物，准备阶段却报告可复用：判据不一致"));
            } else {
                original = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                        ArtifactType.ORIGINAL, "", null, null);
                subtitlePreparationCoordinator.prepare(input, workDir, listener, owner);
                original = artifactStore.settleArtifact(original, workDir);
            }

            // 阶段 2：清洗字幕文件（关键词按任务读，跟产物同粒度）。同样先问复用，命中就什么都不做
            SubtitleKeywords keywords = keywordRepository.findByTaskId(run.getTaskId());
            Optional<SubtitleArtifact> intactCleaned = artifactStore
                    .findIntactArtifact(run.getTaskId(), ArtifactType.CLEANED, "", workDir);
            boolean isCleanReusable = cleanupStage.reuseIfAvailable(workDir, keywords, intactCleaned.isPresent(), listener).isPresent();
            if (!isCleanReusable) {
                SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                        ArtifactType.CLEANED, "", original.getId(), keywords.computeFingerprint());
                cleanupStage.clean(input, workDir, keywords, listener, intactCleaned.isPresent());
                // 清洗是唯一有下游的产物：换版时要把基于旧清洗的译文一起作废
                artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
            }

            supervisor.finish(ref, run, null);
            log.info("任务执行成功: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
        } catch (Throwable t) {
            // 接住之后 JVM 不再打这条异常的默认堆栈，自己补一次；只打类型与调用栈
            log.error("任务执行失败: taskNo={}, runNo={}\n{}", ref.taskNo().value(), ref.runNo(),
                    ThrowableUtils.stackOf(t));
            supervisor.fail(ref, run, t);
        }
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
