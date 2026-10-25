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
import com.vidsage.application.subtitle.pipeline.SubtitleTranslationCoordinator;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * {@link SubtitleTranslateService} 的纯单元测试：TRANSLATE 一条流程的同步守卫段（语种白名单、
 * 取任务与工作区、要求一版完好清洗产物、抢互斥）与异步执行段（已有译文就 SKIPPED → 翻译 →
 * 落占位 → 落定 → 收口）都住在这个类里，故两类用例同住一个测试类。
 *
 * <p>阶段协调器与产物库全部 mock，事务与身份定位用真实协作件 + 临时目录；执行段用例注入
 * 「立刻同步跑 body」的假闸门，排队被打断的用例注入「body 不跑、直接回调」的假闸门。
 */
class SubtitleTranslateServiceTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final SubtitleTaskRepository subtitleTaskRepository = Mockito.mock(SubtitleTaskRepository.class);
    private final WorkspaceRepository workspaceRepository = Mockito.mock(WorkspaceRepository.class);
    private final TaskRunSupervisor supervisor = Mockito.mock(TaskRunSupervisor.class);
    private final TaskConcurrencyGate concurrencyGate = Mockito.mock(TaskConcurrencyGate.class);
    private final SubtitleTranslationCoordinator translationStage =
            Mockito.mock(SubtitleTranslationCoordinator.class);
    private final SubtitleArtifactStore artifactStore = Mockito.mock(SubtitleArtifactStore.class);
    private final ProgressListener listener = Mockito.mock(ProgressListener.class);

    private static final SupportedLanguage SOURCE_LANG = SupportedLanguage.JAPANESE;
    private static final SupportedLanguage TARGET_LANG = SupportedLanguage.SIMPLE_CHINESE;
    private static final long TASK_ID = 7L;
    private static final long RUN_ID = 1L;
    private static final long CLEANED_ARTIFACT_ID = 12L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";
    private static final String URL = "https://www.bilibili.com/video/BV11QMd6WEJp";
    private static final String SOURCE_KEY = "BV11QMd6WEJp|1";

    private SubtitleTranslateService service() {
        return service(concurrencyGate);
    }

    private SubtitleTranslateService service(TaskConcurrencyGate gate) {
        when(supervisor.listener(any(), any())).thenReturn(listener);
        // 占位只验接线：给回一版带 id 的 CREATING 译文产物，落定原样返回
        when(artifactStore.createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> SubtitleArtifact.restore(13L, invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
                        1, SubtitleArtifactStatus.CREATING, invocation.getArgument(4), invocation.getArgument(5),
                        null, null, null, null, null, null, null, null, LocalDateTime.now()));
        when(artifactStore.settleArtifact(any(SubtitleArtifact.class), any(Path.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new SubtitleTranslateService(
                new SubtitleTaskFinder(subtitleTaskRepository, workspaceRepository, directoryManager()),
                supervisor, gate, translationStage, artifactStore, directoryManager());
    }

    private NioWorkspaceDirectoryManager directoryManager() {
        return new NioWorkspaceDirectoryManager(new WorkspaceProperties(tempDir.resolve("works").toString()));
    }

    // ---------- 执行段 ----------

    @Test
    void translateRunsTranslationThenCreatesPlaceholderAndSettles() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);
        stubGuard(run, cleanedReady(CLEANED_ARTIFACT_ID));

        service(inlineGate()).translate(TASK_NO, SOURCE_LANG.getCode(), TARGET_LANG.getCode(), false);

        InOrder order = inOrder(translationStage, artifactStore);
        order.verify(translationStage).translate(eq(TASK_ID), eq(new MediaInput.RemoteUrl(URL)),
                any(Path.class), eq(SOURCE_LANG), eq(TARGET_LANG), any(ProgressListener.class));
        order.verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID),
                eq(ArtifactType.TRANSLATED), eq(TARGET_LANG.getCode()), eq(CLEANED_ARTIFACT_ID), isNull());
        order.verify(artifactStore).settleArtifact(any(SubtitleArtifact.class), any(Path.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void translateReusesCurrentIntactTranslationWithoutCallingStage() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);
        stubGuard(run, cleanedReady(CLEANED_ARTIFACT_ID));
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.TRANSLATED),
                eq(TARGET_LANG.getCode()), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));

        service(inlineGate()).translate(TASK_NO, SOURCE_LANG.getCode(), TARGET_LANG.getCode(), false);

        verify(translationStage, never()).translate(anyLong(), any(), any(), any(), any(), any());
        verify(artifactStore, never()).createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any());
        verify(artifactStore, never()).settleArtifact(any(), any());
        verify(supervisor).finish(ref, run, null);
        ArgumentCaptor<StageProgress> stage = ArgumentCaptor.forClass(StageProgress.class);
        verify(listener).onProgress(stage.capture());
        assertEquals(StageProgress.State.SKIPPED, stage.getValue().state(),
                "复用命中要告诉订阅者这条 run 是跳过收尾的");
    }

    @Test
    void forceTranslationRerunsEvenWhenIntactTranslationExists() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);
        stubGuard(run, cleanedReady(CLEANED_ARTIFACT_ID));
        // 即使库里有完好译文，force=true 也不该走复用分支
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.TRANSLATED),
                eq(TARGET_LANG.getCode()), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));

        service(inlineGate()).translate(TASK_NO, SOURCE_LANG.getCode(), TARGET_LANG.getCode(), true);

        verify(translationStage).translate(eq(TASK_ID), eq(new MediaInput.RemoteUrl(URL)),
                any(Path.class), eq(SOURCE_LANG), eq(TARGET_LANG), any(ProgressListener.class));
        verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID),
                eq(ArtifactType.TRANSLATED), eq(TARGET_LANG.getCode()), eq(CLEANED_ARTIFACT_ID), isNull());
        verify(artifactStore).settleArtifact(any(SubtitleArtifact.class), any(Path.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void translateFailureFinishesRunWithoutPlaceholder() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);
        stubGuard(run, cleanedReady(CLEANED_ARTIFACT_ID));
        IllegalStateException boom = new IllegalStateException("翻译失败");
        doThrow(boom)
                .when(translationStage).translate(anyLong(), any(), any(), any(), any(), any());

        service(inlineGate()).translate(TASK_NO, SOURCE_LANG.getCode(), TARGET_LANG.getCode(), false);

        verify(supervisor).fail(eq(ref), eq(run), same(boom));
        verify(artifactStore, never()).createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any());
        verify(artifactStore, never()).settleArtifact(any(), any());
    }

    @Test
    void queuedTranslationInterruptFinishesRun() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);
        stubGuard(run, cleanedReady(CLEANED_ARTIFACT_ID));

        service(interruptOnQueueGate()).translate(TASK_NO, SOURCE_LANG.getCode(), TARGET_LANG.getCode(), false);

        verify(supervisor).finish(ref, run, "翻译在等待执行配额时被中断：请重新触发翻译");
        verify(translationStage, never()).translate(anyLong(), any(), any(), any(), any(), any());
    }

    // ---------- 守卫段 ----------

    @Test
    void translateRejectsWhenNoIntactCleaned() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.CLEANED), eq(""), any()))
                .thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().translate(TASK_NO, SOURCE_LANG.getCode(), TARGET_LANG.getCode(), false),
                "没有完好的清洗产物 → 400，不是 409");

        assertTrue(e.getMessage().contains("清洗后的字幕产物"));
        verify(supervisor, never()).start(any(), any(), any());
    }

    @Test
    void translateDispatchesTranslateRunWhenIntactCleanedExists() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        stubGuard(run, cleanedReady(CLEANED_ARTIFACT_ID));

        // 同步跑的假闸门：派发出去的执行体当场跑完，好在它的副作用上验「流进去的是哪条 run、哪个 workspace」
        TaskRunRef ref = service(inlineGate()).translate(TASK_NO, SOURCE_LANG.getCode(), TARGET_LANG.getCode(), false);

        assertEquals(3, ref.runNo());
        // 执行体拿到的正是这条 run（经收口观测）与这个 workspace（经媒体输入还原观测）
        verify(translationStage).translate(eq(TASK_ID), eq(new MediaInput.RemoteUrl(URL)), any(Path.class),
                eq(SOURCE_LANG), eq(TARGET_LANG), any(ProgressListener.class));
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

    /** 把守卫段的前置条件都摆好：任务 / 工作区 / 完好清洗产物 / 抢到互斥。 */
    private void stubGuard(TaskRun run, SubtitleArtifact cleaned) {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.CLEANED), eq(""), any(Path.class)))
                .thenReturn(Optional.of(cleaned));
        when(supervisor.start(any(SubtitleTask.class), eq(TaskRunType.TRANSLATE), eq(TARGET_LANG.getCode())))
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

    /** 一版完好的清洗产物：id 是新译文产物溯源字段的断言依据。 */
    private static SubtitleArtifact cleanedReady(long id) {
        return SubtitleArtifact.restore(id, TASK_ID, RUN_ID, ArtifactType.CLEANED, "", 1,
                SubtitleArtifactStatus.READY, null, "fp-1", "clean-segments.json",
                "clean-subtitles.srt", "context.json", null, "a", "b", "c", null, LocalDateTime.now());
    }
}
