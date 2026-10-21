package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.subtitle.pipeline.SubtitleCleanupCoordinator;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
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
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import com.vidsage.infrastructure.fs.NioWorkspaceDirectoryManager;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * {@link SubtitleRecleanService} 的纯单元测试：RECLEAN 一条流程的同步守卫段（取任务与工作区、
 * 要求一版完好原始产物、抢互斥、落关键词）与异步执行段（占位 → 重跑清洗 → 无条件作废下游 → 收口）
 * 都住在这个类里，故两类用例同住一个测试类。
 *
 * <p>阶段协调器与产物库全部 mock，事务与身份定位用真实协作件 + 临时目录；执行段用例注入
 * 「立刻同步跑 body」的假闸门，排队被打断的用例注入「body 不跑、直接回调」的假闸门。
 */
class SubtitleRecleanServiceTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final SubtitleTaskRepository subtitleTaskRepository = Mockito.mock(SubtitleTaskRepository.class);
    private final WorkspaceRepository workspaceRepository = Mockito.mock(WorkspaceRepository.class);
    private final SubtitleKeywordRepository keywordRepository = Mockito.mock(SubtitleKeywordRepository.class);
    private final TaskRunSupervisor supervisor = Mockito.mock(TaskRunSupervisor.class);
    private final TaskConcurrencyGate concurrencyGate = Mockito.mock(TaskConcurrencyGate.class);
    private final SubtitleCleanupCoordinator cleanupStage = Mockito.mock(SubtitleCleanupCoordinator.class);
    private final SubtitleArtifactStore artifactStore = Mockito.mock(SubtitleArtifactStore.class);
    /** 收口机制类给出的阶段回调：清洗协调器收到的必须是这一个。 */
    private final ProgressListener listener = Mockito.mock(ProgressListener.class);

    private static final long TASK_ID = 7L;
    private static final long RUN_ID = 1L;
    private static final long ORIGINAL_ARTIFACT_ID = 11L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";
    private static final String URL = "https://www.bilibili.com/video/BV11QMd6WEJp";
    private static final String SOURCE_KEY = "BV11QMd6WEJp|1";

    private SubtitleRecleanService service() {
        return service(concurrencyGate);
    }

    private SubtitleRecleanService service(TaskConcurrencyGate gate) {
        // 该任务没有存过关键词：读回空清单（不是 null）
        when(keywordRepository.findByTaskId(anyLong())).thenReturn(new SubtitleKeywords(List.of()));
        // 阶段回调由收口机制类给出：被 mock 之后默认给 null，这里换成可观察的那个
        when(supervisor.listener(any(), any())).thenReturn(listener);
        // 占位只验接线：给回一版带 id 的 CREATING 清洗产物，落定（void）默认什么都不做
        when(artifactStore.createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> SubtitleArtifact.restore(12L, invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
                        1, SubtitleArtifactStatus.CREATING, invocation.getArgument(4), invocation.getArgument(5),
                        null, null, null, null, null, null, null, null, LocalDateTime.now()));
        return new SubtitleRecleanService(
                new SubtitleTaskFinder(subtitleTaskRepository, workspaceRepository, directoryManager()),
                keywordRepository, supervisor, gate, cleanupStage, artifactStore,
                directoryManager());
    }

    private NioWorkspaceDirectoryManager directoryManager() {
        return new NioWorkspaceDirectoryManager(new WorkspaceProperties(tempDir.resolve("works").toString()));
    }

    // ---------- 执行段 ----------

    @Test
    void regenerateRunsCleanupAndFinishesRun() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));
        stubGuard(run, originalReady(ORIGINAL_ARTIFACT_ID));

        service(inlineGate()).regenerate(TASK_NO, List.of("影视飓风"));

        verify(cleanupStage).regenerate(eq(new MediaInput.RemoteUrl(URL)),
                any(Path.class), eq(keywords), any(ProgressListener.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void regenerateWrapsCleanupWithArtifactPlaceholderAndSettle() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));
        stubGuard(run, originalReady(ORIGINAL_ARTIFACT_ID));

        service(inlineGate()).regenerate(TASK_NO, List.of("影视飓风"));

        InOrder order = inOrder(artifactStore, cleanupStage);
        order.verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID), eq(ArtifactType.CLEANED),
                eq(""), eq(ORIGINAL_ARTIFACT_ID), eq(keywords.computeFingerprint()));
        order.verify(cleanupStage).regenerate(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                any(ProgressListener.class));
        order.verify(artifactStore).settleCleanedAndSupersedeDownstream(any(SubtitleArtifact.class), any(Path.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void regenerateFailureFinishesRunWithReason() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);
        stubGuard(run, originalReady(ORIGINAL_ARTIFACT_ID));
        IllegalStateException boom = new IllegalStateException("字幕摘要失败");
        doThrow(boom)
                .when(cleanupStage).regenerate(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                        any(ProgressListener.class));

        service(inlineGate()).regenerate(TASK_NO, List.of());

        verify(supervisor).fail(eq(ref), eq(run), same(boom));
        verify(artifactStore, never()).settleCleanedAndSupersedeDownstream(any(), any());
    }

    @Test
    void queuedRegenerateInterruptFinishesRun() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);
        stubGuard(run, originalReady(ORIGINAL_ARTIFACT_ID));

        service(interruptOnQueueGate()).regenerate(TASK_NO, List.of());

        verify(supervisor).finish(ref, run, "重新生成在等待执行配额时被中断：请重新触发");
        verify(cleanupStage, never()).regenerate(any(), any(), any(), any());
    }

    // ---------- 守卫段 ----------

    @Test
    void regenerateRejectsWhenNoIntactOriginal() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any()))
                .thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().regenerate(TASK_NO, null),
                "没有完好的原始产物 → 400，不是 409");

        assertTrue(e.getMessage().contains("原始转写产物"));
        verify(supervisor, never()).start(any(), any(), any());
    }

    @Test
    void regenerateDispatchesRecleanRunWhenIntactOriginalExists() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        stubGuard(run, originalReady(ORIGINAL_ARTIFACT_ID));

        // 同步跑的假闸门：派发出去的执行体当场跑完，好在它的副作用上验「流进去的是哪条 run、哪个 workspace」
        TaskRunRef ref = service(inlineGate()).regenerate(TASK_NO, List.of("影视飓风"));

        assertEquals(2, ref.runNo());
        verify(keywordRepository).replace(TASK_ID, new SubtitleKeywords(List.of("影视飓风")));
        // 执行体拿到的正是这条 run（经收口观测）与这个 workspace（经媒体输入还原观测）
        verify(cleanupStage).regenerate(eq(new MediaInput.RemoteUrl(URL)), any(Path.class),
                any(SubtitleKeywords.class), any(ProgressListener.class));
        verify(supervisor).finish(ref(run), run, null);
    }

    // ---------- 假闸门 ----------

    /** 假闸门①：立刻同步跑 body（只看拿到许可之后跑成什么样）。 */
    private static TaskConcurrencyGate inlineGate() {
        return (body, onQueueInterrupted) -> body.run();
    }

    /** 假闸门②：模拟「排队被打断」——body 不跑，直接回调。 */
    private static TaskConcurrencyGate interruptOnQueueGate() {
        return (body, onQueueInterrupted) -> onQueueInterrupted.run();
    }

    // ---------- 夹具 ----------

    /** 把守卫段的前置条件都摆好：任务 / 工作区 / 完好原始产物 / 抢到互斥。 */
    private void stubGuard(TaskRun run, SubtitleArtifact original) {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any(Path.class)))
                .thenReturn(Optional.of(original));
        when(supervisor.start(any(SubtitleTask.class), eq(TaskRunType.RECLEAN), isNull()))
                .thenReturn(Optional.of(run));
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
        return TaskRun.restore(RUN_ID, TASK_ID, runNo, type, targetLanguage, TaskRunStatus.RUNNING, null, null,
                now, null, now);
    }

    private static RunRef ref(TaskRun run) {
        return RunRef.of(new TaskNo(TASK_NO), run);
    }

    /** 一版完好的原始产物：id 是新清洗产物溯源字段的断言依据。 */
    private static SubtitleArtifact originalReady(long id) {
        return SubtitleArtifact.restore(id, TASK_ID, RUN_ID, ArtifactType.ORIGINAL, "", 3,
                SubtitleArtifactStatus.READY, null, null, "original-segments.json",
                "original-subtitles.srt", null, null, "a", "b", null, null, LocalDateTime.now());
    }
}
