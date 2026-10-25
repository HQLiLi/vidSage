package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.IntermediateFileOwner;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.subtitle.pipeline.SubtitleCleanupCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitleFingerprintCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitlePreparationCoordinator;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.subtitle.model.cleanup.CleanedSubtitle;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.model.common.SubtitleStageOutput;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.ResolvedMediaIdentity;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.MediaIdentityResolver;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import com.vidsage.infrastructure.fs.NioWorkspaceDirectoryManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link SubtitleMainService} 的纯单元测试：MAIN 一条流程的同步提交段（算身份键 / 落库 / 抢互斥）
 * 与异步执行段（指纹探测 → 准备 → 清洗 → 收口）都住在这个类里，故两类用例同住一个测试类。
 *
 * <p>执行段的用例直接调包内可见的 {@code runPipeline(run, ref, workspace)} 绕开虚拟线程的时序；
 * 排队被打断的用例注入假闸门（body 不跑、直接回调）。阶段协调器、产物库、收口机制类全部 mock，
 * 工作区管理器用真实实例 + 临时目录。
 */
class SubtitleMainServiceTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final WorkspaceRepository workspaceRepository = Mockito.mock(WorkspaceRepository.class);
    private final SubtitleTaskRepository subtitleTaskRepository = Mockito.mock(SubtitleTaskRepository.class);
    private final SubtitleKeywordRepository keywordRepository = Mockito.mock(SubtitleKeywordRepository.class);
    private final MediaIdentityResolver mediaIdentityResolver = Mockito.mock(MediaIdentityResolver.class);
    private final TaskRunSupervisor supervisor = Mockito.mock(TaskRunSupervisor.class);
    private final TaskConcurrencyGate concurrencyGate = Mockito.mock(TaskConcurrencyGate.class);
    private final SubtitlePreparationCoordinator subtitlePreparationCoordinator =
            Mockito.mock(SubtitlePreparationCoordinator.class);
    private final SubtitleCleanupCoordinator cleanupStage = Mockito.mock(SubtitleCleanupCoordinator.class);
    private final SubtitleArtifactStore artifactStore = Mockito.mock(SubtitleArtifactStore.class);
    private final SubtitleFingerprintCoordinator fingerprintCoordinator =
            Mockito.mock(SubtitleFingerprintCoordinator.class);
    /** 收口机制类给出的阶段回调：各协调器收到的必须是这一个，事件也回到它身上。 */
    private final ProgressListener listener = Mockito.mock(ProgressListener.class);

    private static final long TASK_ID = 7L;
    private static final long RUN_ID = 1L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";
    private static final String URL = "https://www.bilibili.com/video/BV11QMd6WEJp";
    private static final String SOURCE_KEY = "BV11QMd6WEJp|1";

    private SubtitleMainService service() {
        return service(concurrencyGate, inlineTransactionManager());
    }

    private SubtitleMainService service(TaskConcurrencyGate gate) {
        return service(gate, inlineTransactionManager());
    }

    private SubtitleMainService service(PlatformTransactionManager transactionManager) {
        return service(concurrencyGate, transactionManager);
    }

    private SubtitleMainService service(TaskConcurrencyGate gate, PlatformTransactionManager transactionManager) {
        // 该任务没有存过关键词：读回空清单（不是 null），清洗照常按原样纠错
        when(keywordRepository.findByTaskId(anyLong())).thenReturn(new SubtitleKeywords(List.of()));
        // 阶段回调由收口机制类给出：被 mock 之后默认给 null，这里换成可观察的那个
        when(supervisor.listener(any(), any())).thenReturn(listener);
        // 占位与落定只验接线顺序：占位给回带 id 的同类型产物，落定原样返回
        when(artifactStore.createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ArtifactType type = invocation.getArgument(2);
                    return SubtitleArtifact.restore(type == ArtifactType.ORIGINAL ? 11L : 12L,
                            invocation.getArgument(0), invocation.getArgument(1), type, invocation.getArgument(3),
                            1, SubtitleArtifactStatus.CREATING, invocation.getArgument(4), invocation.getArgument(5),
                            null, null, null, null, null, null, null, null, LocalDateTime.now());
                });
        when(artifactStore.settleArtifact(any(SubtitleArtifact.class), any(Path.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new SubtitleMainService(workspaceRepository, subtitleTaskRepository,
                keywordRepository, mediaIdentityResolver, supervisor, gate, subtitlePreparationCoordinator,
                cleanupStage, artifactStore, fingerprintCoordinator, directoryManager(), transactionManager);
    }

    private NioWorkspaceDirectoryManager directoryManager() {
        return new NioWorkspaceDirectoryManager(new WorkspaceProperties(tempDir.resolve("works").toString()));
    }

    // ---------- 主管线执行段（MAIN） ----------

    @Test
    void runsPrepareThenCleanAndFinishesRun() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        Workspace workspace = urlWorkspace();
        // clean 的桩发一条阶段事件，验证事件回到了收口机制类给出的那个回调上
        doAnswer(invocation -> {
            invocation.getArgument(3, ProgressListener.class).onProgress(
                    new StageProgress(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 0,
                            "开始清洗", StageProgress.State.RUNNING));
            return null;
        }).when(cleanupStage).clean(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                any(ProgressListener.class), anyBoolean());

        service().runPipeline(run, ref, workspace);

        MediaInput input = new MediaInput.RemoteUrl(URL);
        InOrder order = inOrder(subtitlePreparationCoordinator, cleanupStage);
        order.verify(subtitlePreparationCoordinator).prepare(eq(input), any(Path.class),
                same(listener), any(IntermediateFileOwner.class));
        order.verify(cleanupStage).clean(eq(input), any(Path.class), any(SubtitleKeywords.class),
                same(listener), anyBoolean());
        verify(listener).onProgress(any(StageProgress.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void wrapsEachStageWithArtifactPlaceholderAndSettle() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        when(keywordRepository.findByTaskId(TASK_ID)).thenReturn(keywords);

        service().runPipeline(run, ref, urlWorkspace());

        InOrder order = inOrder(artifactStore, subtitlePreparationCoordinator, cleanupStage);
        order.verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID), eq(ArtifactType.ORIGINAL),
                eq(""), isNull(), isNull());
        order.verify(subtitlePreparationCoordinator).prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), any(IntermediateFileOwner.class));
        order.verify(artifactStore).settleArtifact(any(SubtitleArtifact.class), any(Path.class));
        order.verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID), eq(ArtifactType.CLEANED),
                eq(""), eq(11L), eq(keywords.computeFingerprint()));
        order.verify(cleanupStage).clean(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                any(ProgressListener.class), anyBoolean());
        order.verify(artifactStore).settleCleanedAndSupersedeDownstream(any(SubtitleArtifact.class), any(Path.class));
        verify(supervisor).finish(ref, run, null);
        verify(artifactStore, times(2)).createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void passesArtifactReadabilityToPreparationAndCleanup() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        // 产物表说两版都完好 → 两个门禁都要拿到 true
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.CLEANED), eq(""), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));

        service().runPipeline(run, ref(run), urlWorkspace());

        verify(subtitlePreparationCoordinator).reuseIfAvailable(any(Path.class), eq(true),
                any(ProgressListener.class));
        verify(cleanupStage).reuseIfAvailable(any(Path.class), any(SubtitleKeywords.class), eq(true),
                any(ProgressListener.class));
    }

    @Test
    void probesFingerprintBeforePreparing() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);

        service().runPipeline(run, ref, urlWorkspace());

        InOrder order = inOrder(fingerprintCoordinator, subtitlePreparationCoordinator);
        order.verify(fingerprintCoordinator).detectContentChange(eq(TASK_ID), any(Workspace.class),
                any(MediaInput.class), any(Path.class), any(ProgressListener.class));
        order.verify(subtitlePreparationCoordinator).prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), any(IntermediateFileOwner.class));
    }

    @Test
    void reuseHitOnOriginalSkipsPlaceholderAndSettle() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any(Path.class)))
                .thenReturn(Optional.of(originalReady(21L)));
        when(subtitlePreparationCoordinator.reuseIfAvailable(any(Path.class), eq(true), any(ProgressListener.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleStageOutput.class)));

        service().runPipeline(run, ref(run), urlWorkspace());

        // 复用命中：原始产物那一版不插占位、不落定、不真跑
        verify(artifactStore, never()).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID),
                eq(ArtifactType.ORIGINAL), any(), any(), any());
        verify(artifactStore, never()).settleArtifact(any(), any());
        verify(subtitlePreparationCoordinator, never()).prepare(any(), any(), any(), any());
    }

    @Test
    void reuseHitOnCleanedSkipsPlaceholderAndSettleAndCascade() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.CLEANED), eq(""), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));
        when(cleanupStage.reuseIfAvailable(any(Path.class), any(SubtitleKeywords.class), eq(true),
                any(ProgressListener.class)))
                .thenReturn(Optional.of(Mockito.mock(CleanedSubtitle.class)));

        service().runPipeline(run, ref, urlWorkspace());

        // 复用命中：清洗那一版不插占位、不落定，下游译文与它们的文件一个字节不动
        verify(artifactStore, never()).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID),
                eq(ArtifactType.CLEANED), any(), any(), any());
        verify(cleanupStage, never()).clean(any(), any(), any(), any(), anyBoolean());
        verify(artifactStore, never()).settleCleanedAndSupersedeDownstream(any(), any());
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void cleanedMissPointsToStillReadyOriginalWhenOriginalIsReused() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        when(keywordRepository.findByTaskId(TASK_ID)).thenReturn(keywords);
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any(Path.class)))
                .thenReturn(Optional.of(originalReady(21L)));
        when(subtitlePreparationCoordinator.reuseIfAvailable(any(Path.class), eq(true), any(ProgressListener.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleStageOutput.class)));

        service().runPipeline(run, ref(run), urlWorkspace());

        verify(artifactStore, never()).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID),
                eq(ArtifactType.ORIGINAL), any(), any(), any());
        // 新清洗产物的溯源要指向那条仍 READY 的旧原始产物，而不是一个凭空的新行
        verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID), eq(ArtifactType.CLEANED),
                eq(""), eq(21L), eq(keywords.computeFingerprint()));
    }

    @Test
    void throwsWhenArtifactTableSaysNoIntactOriginalButPreparationReportsReuse() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        // findIntactArtifact 默认返回空（表说没有完好的原始产物），但协调器报告可复用 → 判据不一致
        when(subtitlePreparationCoordinator.reuseIfAvailable(any(Path.class), eq(false), any(ProgressListener.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleStageOutput.class)));

        service().runPipeline(run, ref, urlWorkspace());

        ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);
        verify(supervisor).fail(eq(ref), eq(run), cause.capture());
        assertTrue(cause.getValue().getMessage().contains("判据不一致"),
                () -> "应把判据不一致的原因报出来：" + cause.getValue().getMessage());
        verify(artifactStore, never()).createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void readsKeywordsByTaskId() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);

        service().runPipeline(run, ref(run), urlWorkspace());

        verify(keywordRepository).findByTaskId(TASK_ID);
    }

    @Test
    void uploadWorkspaceRebuildsInWorkspaceInputAtSanitizedSourceKeyDir() {
        String sha256 = "a".repeat(64);
        Workspace workspace = Workspace.restore(TASK_ID, SourceType.UPLOAD, new SourceKey(sha256),
                "demo.mp4", null, null, null, LocalDateTime.now(), LocalDateTime.now());
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);

        service().runPipeline(run, ref(run), workspace);

        ArgumentCaptor<MediaInput> captor = ArgumentCaptor.forClass(MediaInput.class);
        verify(subtitlePreparationCoordinator).prepare(captor.capture(), any(Path.class),
                any(ProgressListener.class), any(IntermediateFileOwner.class));
        MediaInput input = captor.getValue();
        assertTrue(input instanceof MediaInput.InWorkspace, () -> "上传任务应还原为 InWorkspace：" + input);
        Path expectedDir = tempDir.resolve("works").resolve(sha256);
        assertEquals(expectedDir.resolve("source.mp4"), ((MediaInput.InWorkspace) input).value(),
                "上传任务必须按身份键推出的目录定位 source.mp4");
    }

    @Test
    void prepareFailureFinishesRunWithReasonAndSkipsClean() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        IllegalStateException boom = new IllegalStateException("ASR 转写任务失败：quota exceeded");
        when(subtitlePreparationCoordinator.prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), any(IntermediateFileOwner.class)))
                .thenThrow(boom);

        service().runPipeline(run, ref, urlWorkspace());

        verify(supervisor).fail(eq(ref), eq(run), same(boom));
        verify(cleanupStage, never()).clean(any(), any(), any(), any(), anyBoolean());
        verify(artifactStore, never()).settleArtifact(any(), any());
    }

    @Test
    void settleFailureFinishesRunAndSkipsClean() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        SubtitleMainService service = service();
        // 覆盖 helper 里的默认落定桩：这一趟要在落定处失败
        IllegalStateException boom = new IllegalStateException("产物文件读取失败：original-subtitles.srt");
        when(artifactStore.settleArtifact(any(SubtitleArtifact.class), any(Path.class)))
                .thenThrow(boom);

        service.runPipeline(run, ref, urlWorkspace());

        verify(supervisor).fail(eq(ref), eq(run), same(boom));
        verify(cleanupStage, never()).clean(any(), any(), any(), any(), anyBoolean());
    }

    // ---------- 提交路径：URL ----------

    @Test
    void submitUrlCreatesIdentityAndDispatchesMainRun() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        stubUrlSubmission(workspace, run);

        // 同步跑的假闸门：派发出去的执行体当场跑完，好在它的副作用上验「流进去的是哪条 run、哪个 workspace」
        TaskRunRef ref = service(inlineGate()).submitUrl(URL, null);

        assertEquals(TASK_NO, ref.taskNo());
        assertEquals(1, ref.runNo());
        verify(supervisor).start(any(SubtitleTask.class), eq(TaskRunType.MAIN), isNull());
        // 执行体拿到的正是这个 workspace（经指纹探测观测，按实例比对）与这条 run（经收口观测）
        verify(fingerprintCoordinator).detectContentChange(eq(TASK_ID), eq(workspace),
                eq(new MediaInput.RemoteUrl(URL)), any(Path.class), any(ProgressListener.class));
        verify(subtitlePreparationCoordinator).prepare(eq(new MediaInput.RemoteUrl(URL)), any(Path.class),
                any(ProgressListener.class), any(IntermediateFileOwner.class));
        verify(supervisor).finish(ref(run), run, null);
    }

    @Test
    void secondSubmitInsertsNewRunInsteadOfConflicting() {
        Workspace workspace = urlWorkspace();
        TaskRun secondRun = runningRun(TaskRunType.MAIN, null, 2);
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(secondRun));

        TaskRunRef ref = service().submitUrl(URL, null);

        assertEquals(2, ref.runNo(), "同一 URL 二次提交是插新 run，不是 409");
        verify(workspaceRepository, never()).insert(any());
        verify(subtitleTaskRepository, never()).insert(any());
    }

    @Test
    void conflictWhenTaskAlreadyHasRunningRun() {
        Workspace workspace = urlWorkspace();
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.empty());
        when(supervisor.buildTaskConflictException(any()))
                .thenReturn(new TaskConflictException(TASK_NO, 3));

        TaskConflictException e = assertThrows(TaskConflictException.class,
                () -> service().submitUrl(URL, null));

        assertEquals(TASK_NO, e.getTaskNo());
        assertEquals(3, e.getRunNo(), "409 要带既有 run 的 runNo");
        verify(concurrencyGate, never()).submit(any(), any());
    }

    @Test
    void concurrentDuplicateSourceFallsBackToLockedRead() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.empty());
        when(workspaceRepository.insert(any())).thenThrow(new DuplicateKeyException("撞 uk_source"));
        when(workspaceRepository.findForShareBySourceTypeAndSourceKey(any(), any()))
                .thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.empty());
        when(subtitleTaskRepository.insert(any())).thenThrow(new DuplicateKeyException("撞 uk_workspace"));
        when(subtitleTaskRepository.findForShareByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));

        TaskRunRef ref = service().submitUrl(URL, null);

        assertEquals(TASK_NO, ref.taskNo(), "并发撞键收敛到同一条任务，不报 409");
        verify(workspaceRepository).findForShareBySourceTypeAndSourceKey(any(), any());
        verify(subtitleTaskRepository).findForShareByWorkspaceId(TASK_ID);
    }

    @Test
    void identityIsResolvedBeforeTheTransactionOpens() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        List<String> order = new ArrayList<>();
        when(mediaIdentityResolver.resolve(anyString())).thenAnswer(invocation -> {
            order.add("resolve");
            return identity();
        });
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.of(workspace));
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.of(task()));
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));
        PlatformTransactionManager recording = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                order.add("begin");
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };

        service(recording).submitUrl(URL, null);

        assertEquals(List.of("resolve", "begin"), order, "身份键要走网络，必须在事务外先算完");
    }

    @Test
    void keywordsWrittenOnlyWhenRequestCarriesThem() {
        Workspace workspace = urlWorkspace();
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        stubUrlSubmission(workspace, run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));

        service().submitUrl(URL, List.of("影视飓风"));
        verify(keywordRepository).replace(TASK_ID, keywords);

        Mockito.reset(keywordRepository);
        service().submitUrl(URL, null);
        verify(keywordRepository, never()).replace(anyLong(), any());
    }

    @Test
    void blankUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service().submitUrl("  ", null));
    }

    // ---------- 提交路径：上传 ----------

    @Test
    void submitUploadUsesFullSha() throws Exception {
        byte[] media = "hello world".getBytes(StandardCharsets.UTF_8);
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(media));
        // 落库后的身份带回内部 id（真实 insert 会回填）；测试用 restore 模拟，否则后续按 id 查任务会 NPE
        LocalDateTime now = LocalDateTime.now();
        Workspace uploaded = Workspace.restore(TASK_ID, SourceType.UPLOAD, new SourceKey(expectedSha),
                "demo.mp4", null, null, null, now, now);
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.empty());
        when(workspaceRepository.insert(any())).thenReturn(uploaded);
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.empty());
        when(subtitleTaskRepository.insert(any())).thenReturn(task());
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));

        service().submitUpload(new ByteArrayInputStream(media), "我的 视频.mp4", null);

        ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).insert(captor.capture());
        assertEquals(expectedSha, captor.getValue().getSourceKey().value(),
                "上传路线身份键是内容 sha256 全 64 位");
        assertEquals(64, captor.getValue().getSourceKey().value().length());
        assertTrue(Files.isRegularFile(tempDir.resolve("works").resolve(expectedSha).resolve("source.mp4")),
                "上传文件按身份键归位到工作区");
    }

    @Test
    void submitUploadRejectsNullContent() {
        InputStream content = null;

        assertThrows(IllegalArgumentException.class,
                () -> service().submitUpload(content, "demo.mp4", null));
    }

    // ---------- 排队被打断 ----------

    @Test
    void queuedMainRunInterruptFinishesRunWithoutRunningStages() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        stubUrlSubmission(urlWorkspace(), run);

        service(interruptOnQueueGate()).submitUrl(URL, null);

        verify(supervisor).finish(ref(run), run, "任务在等待执行配额时被中断：请重新提交");
        verify(subtitlePreparationCoordinator, never()).prepare(any(), any(), any(), any());
    }

    // ---------- 假闸门 ----------

    /** 假闸门①：立刻同步跑 body（好在执行体的副作用上验「流进去的是哪条 run、哪个 workspace」）。 */
    private static TaskConcurrencyGate inlineGate() {
        return (body, onQueueInterrupted) -> body.run();
    }

    /** 假闸门②：模拟「排队被打断」——body 不跑，直接回调。 */
    private static TaskConcurrencyGate interruptOnQueueGate() {
        return (body, onQueueInterrupted) -> onQueueInterrupted.run();
    }

    // ---------- 夹具 ----------

    private void stubUrlSubmission(Workspace workspace, TaskRun run) {
        when(mediaIdentityResolver.resolve(anyString())).thenReturn(identity());
        when(workspaceRepository.findBySourceTypeAndSourceKey(any(), any())).thenReturn(Optional.empty());
        when(workspaceRepository.insert(any())).thenReturn(workspace);
        when(subtitleTaskRepository.findByWorkspaceId(TASK_ID)).thenReturn(Optional.empty());
        when(subtitleTaskRepository.insert(any())).thenReturn(task());
        when(supervisor.start(any(), eq(TaskRunType.MAIN), isNull())).thenReturn(Optional.of(run));
    }

    private static ResolvedMediaIdentity identity() {
        return new ResolvedMediaIdentity(new SourceKey(SOURCE_KEY), URL);
    }

    private static Workspace urlWorkspace() {
        LocalDateTime now = LocalDateTime.now();
        return Workspace.restore(TASK_ID, SourceType.URL, new SourceKey(SOURCE_KEY), URL, URL, null, null, now, now);
    }

    private static SubtitleTask task() {
        LocalDateTime now = LocalDateTime.now();
        return SubtitleTask.restore(TASK_ID, new TaskNo(TASK_NO), TASK_ID, now, now);
    }

    private static TaskRun runningRun(TaskRunType type, String targetLanguage, int runNo) {
        LocalDateTime now = LocalDateTime.now();
        return TaskRun.restore(1L, TASK_ID, runNo, type, targetLanguage, TaskRunStatus.RUNNING, null, null,
                now, null, now);
    }

    private static RunRef ref(TaskRun run) {
        return RunRef.of(new TaskNo(TASK_NO), run);
    }

    /** 一版完好的原始产物：用来当「产物表说这一版还在」的那条行，id 是断言溯源时的依据。 */
    private static SubtitleArtifact originalReady(long id) {
        return SubtitleArtifact.restore(id, TASK_ID, RUN_ID, ArtifactType.ORIGINAL, "", 3,
                SubtitleArtifactStatus.READY, null, null, "original-segments.json",
                "original-subtitles.srt", null, null, "a", "b", null, null, LocalDateTime.now());
    }

    /** 事务桩：就地跑回调，不连真库，也不改事务语义。 */
    private static PlatformTransactionManager inlineTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
