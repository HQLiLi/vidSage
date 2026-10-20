package com.vidsage.application.subtitle.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.IntermediateFileOwner;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.task.TaskRunSupervisor;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.infrastructure.fs.NioWorkspaceDirectoryManager;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * {@link SubtitlePipeline} 的单元测试：阶段构件与收口机制类全部 mock，工作区管理器用真实实例 +
 * 临时目录。三条执行体（主管线 / 重新生成 / 翻译）各覆盖「正常走到底收 SUCCEEDED」「阶段抛出收 FAILED」
 * 「排队被打断也收口」三类退出路径，外加目录与媒体输入的还原。
 *
 * <p>同步用例直接调包内可见的 {@code runPipeline(run, ref, workspace)} 绕开虚拟线程的时序；
 * 排队被打断的用例注入假闸门（body 不跑、直接回调）。
 */
class SubtitlePipelineTest {

    @TempDir
    Path tempDir;

    private final SubtitleKeywordRepository keywordRepository = Mockito.mock(SubtitleKeywordRepository.class);
    private final SubtitlePreparationCoordinator subtitlePreparationCoordinator =
            Mockito.mock(SubtitlePreparationCoordinator.class);
    private final SubtitleCleanupCoordinator cleanupStage = Mockito.mock(SubtitleCleanupCoordinator.class);
    private final SubtitleTranslationCoordinator translationStage =
            Mockito.mock(SubtitleTranslationCoordinator.class);
    private final SubtitleArtifactStore artifactStore = Mockito.mock(SubtitleArtifactStore.class);
    private final SubtitleFingerprintCoordinator fingerprintCoordinator =
            Mockito.mock(SubtitleFingerprintCoordinator.class);
    private final TaskRunSupervisor supervisor = Mockito.mock(TaskRunSupervisor.class);

    private static final SupportedLanguage SOURCE_LANG = SupportedLanguage.JAPANESE;
    private static final SupportedLanguage TARGET_LANG = SupportedLanguage.SIMPLE_CHINESE;
    private static final long TASK_ID = 7L;
    private static final long RUN_ID = 1L;
    private static final long CLEANED_ARTIFACT_ID = 12L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";

    private SubtitlePipeline pipeline(TaskConcurrencyGate gate) {
        // 该任务没有存过关键词：读回空清单（不是 null），清洗照常按原样纠错
        when(keywordRepository.findByTaskId(anyLong())).thenReturn(new SubtitleKeywords(List.of()));
        // 占位与落定只验接线顺序：占位给回带 id 的同类型产物，落定原样返回
        when(artifactStore.createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any())).thenAnswer(invocation -> {
            ArtifactType type = invocation.getArgument(2);
            return SubtitleArtifact.restore(type == ArtifactType.ORIGINAL ? 11L : 12L,
                    invocation.getArgument(0), invocation.getArgument(1), type, invocation.getArgument(3),
                    1, SubtitleArtifactStatus.CREATING, invocation.getArgument(4), invocation.getArgument(5),
                    null, null, null, null, null, null, null, null, LocalDateTime.now());
        });
        when(artifactStore.settleArtifact(any(SubtitleArtifact.class), any(Path.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 清洗落定不返回值（void），mock 默认就是「什么都不做、不抛」
        return new SubtitlePipeline(keywordRepository, subtitlePreparationCoordinator, cleanupStage,
                translationStage, artifactStore, fingerprintCoordinator, supervisor, gate,
                new NioWorkspaceDirectoryManager(new WorkspaceProperties(tempDir.resolve("works").toString())));
    }

    // ---------- 主管线（MAIN） ----------

    @Test
    void runsPrepareThenCleanAndFinishesRun() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        Workspace workspace = urlWorkspace("https://example.com/v");
        // clean 的桩发一条阶段事件，验证回调驱动收口机制类的 advance
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(3);
            listener.onProgress(new StageProgress(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 0,
                    "开始清洗", StageProgress.State.RUNNING));
            return null;
        }).when(cleanupStage).clean(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                any(ProgressListener.class), anyBoolean());

        pipeline(inlineGate()).runPipeline(run, ref, workspace);

        MediaInput input = new MediaInput.RemoteUrl("https://example.com/v");
        InOrder order = inOrder(subtitlePreparationCoordinator, cleanupStage);
        order.verify(subtitlePreparationCoordinator).prepare(eq(input), any(Path.class), any(ProgressListener.class),
                anyBoolean(), any(IntermediateFileOwner.class));
        order.verify(cleanupStage).clean(eq(input), any(Path.class), any(SubtitleKeywords.class),
                any(ProgressListener.class), anyBoolean());
        verify(supervisor).advance(eq(ref), eq(run), any(StageProgress.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void wrapsEachStageWithArtifactPlaceholderAndSettle() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of());
        when(keywordRepository.findByTaskId(TASK_ID)).thenReturn(keywords);

        pipeline(inlineGate()).runPipeline(run, ref, urlWorkspace("https://example.com/v"));

        InOrder order = inOrder(artifactStore, subtitlePreparationCoordinator, cleanupStage);
        order.verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID), eq(ArtifactType.ORIGINAL),
                eq(""), isNull(), isNull());
        order.verify(subtitlePreparationCoordinator).prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), anyBoolean(), any(IntermediateFileOwner.class));
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
        // 产物表说两版都完好 → 两个门禁都要开
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.ORIGINAL), eq(""), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.CLEANED), eq(""), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));

        pipeline(inlineGate()).runPipeline(run, ref(run), urlWorkspace("https://example.com/v"));

        verify(subtitlePreparationCoordinator).prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), eq(true), any(IntermediateFileOwner.class));
        verify(cleanupStage).clean(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                any(ProgressListener.class), eq(true));
    }

    @Test
    void probesFingerprintBeforePreparing() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);

        pipeline(inlineGate()).runPipeline(run, ref, urlWorkspace("https://example.com/v"));

        InOrder order = inOrder(fingerprintCoordinator, subtitlePreparationCoordinator);
        order.verify(fingerprintCoordinator).detectContentChange(eq(TASK_ID), any(Workspace.class),
                any(MediaInput.class), any(Path.class), any(ProgressListener.class));
        order.verify(subtitlePreparationCoordinator).prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), anyBoolean(), any(IntermediateFileOwner.class));
    }

    @Test
    void readsKeywordsByTaskId() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);

        pipeline(inlineGate()).runPipeline(run, ref(run), urlWorkspace("https://example.com/v"));

        verify(keywordRepository).findByTaskId(TASK_ID);
    }

    @Test
    void uploadWorkspaceRebuildsInWorkspaceInputAtSanitizedSourceKeyDir() {
        String sha256 = "a".repeat(64);
        Workspace workspace = Workspace.of(SourceType.UPLOAD, new SourceKey(sha256), "demo.mp4", null);
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);

        pipeline(inlineGate()).runPipeline(run, ref(run), workspace);

        ArgumentCaptor<MediaInput> captor = ArgumentCaptor.forClass(MediaInput.class);
        verify(subtitlePreparationCoordinator).prepare(captor.capture(), any(Path.class), any(ProgressListener.class),
                anyBoolean(), any(IntermediateFileOwner.class));
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
        when(subtitlePreparationCoordinator.prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), anyBoolean(), any(IntermediateFileOwner.class)))
                .thenThrow(new IllegalStateException("ASR 转写任务失败：quota exceeded"));

        pipeline(inlineGate()).runPipeline(run, ref, urlWorkspace("https://example.com/v"));

        verify(supervisor).finish(ref, run, "ASR 转写任务失败：quota exceeded");
        verify(cleanupStage, never()).clean(any(), any(), any(), any(), anyBoolean());
        verify(artifactStore, never()).settleArtifact(any(), any());
    }

    @Test
    void settleFailureFinishesRunAndSkipsClean() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        SubtitlePipeline pipeline = pipeline(inlineGate());
        // 覆盖 helper 里的默认落定桩：这一趟要在落定处失败
        when(artifactStore.settleArtifact(any(SubtitleArtifact.class), any(Path.class)))
                .thenThrow(new IllegalStateException("产物文件读取失败：original-subtitles.srt"));

        pipeline.runPipeline(run, ref, urlWorkspace("https://example.com/v"));

        verify(supervisor).finish(ref, run, "产物文件读取失败：original-subtitles.srt");
        verify(cleanupStage, never()).clean(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void failureMessageFallsBackToExceptionClassNameWhenMessageNull() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);
        when(subtitlePreparationCoordinator.prepare(any(MediaInput.class), any(Path.class),
                any(ProgressListener.class), anyBoolean(), any(IntermediateFileOwner.class)))
                .thenThrow(new NullPointerException());

        pipeline(inlineGate()).runPipeline(run, ref, urlWorkspace("https://example.com/v"));

        verify(supervisor).finish(ref, run, "NullPointerException");
    }

    @Test
    void queuedMainRunInterruptFinishesRunWithoutRunningStages() {
        TaskRun run = runningRun(TaskRunType.MAIN, null, 1);
        RunRef ref = ref(run);

        pipeline(interruptOnQueueGate()).run(run, ref, urlWorkspace("https://example.com/v"));

        verify(supervisor).finish(ref, run, "任务在等待执行配额时被中断：请重新提交");
        verify(subtitlePreparationCoordinator, never()).prepare(any(), any(), any(), anyBoolean(), any());
    }

    // ---------- 重新生成字幕（RECLEAN） ----------

    @Test
    void regenerateRunsCleanupAndFinishesRun() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));

        pipeline(inlineGate()).regenerate(run, ref, urlWorkspace("https://example.com/v"), keywords, 11L);

        verify(cleanupStage).regenerate(eq(new MediaInput.RemoteUrl("https://example.com/v")),
                any(Path.class), eq(keywords), any(ProgressListener.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void regenerateWrapsCleanupWithArtifactPlaceholderAndSettle() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("影视飓风"));

        pipeline(inlineGate()).regenerate(run, ref, urlWorkspace("https://example.com/v"), keywords, 11L);

        InOrder order = inOrder(artifactStore, cleanupStage);
        order.verify(artifactStore).createCreatingPlaceholder(eq(TASK_ID), eq(RUN_ID), eq(ArtifactType.CLEANED),
                eq(""), eq(11L), eq(keywords.computeFingerprint()));
        order.verify(cleanupStage).regenerate(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                any(ProgressListener.class));
        order.verify(artifactStore).settleCleanedAndSupersedeDownstream(any(SubtitleArtifact.class), any(Path.class));
        verify(supervisor).finish(ref, run, null);
    }

    @Test
    void regenerateFailureFinishesRunWithReason() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);
        doThrow(new IllegalStateException("字幕摘要失败"))
                .when(cleanupStage).regenerate(any(MediaInput.class), any(Path.class), any(SubtitleKeywords.class),
                        any(ProgressListener.class));

        pipeline(inlineGate()).regenerate(run, ref, urlWorkspace("https://example.com/v"),
                new SubtitleKeywords(List.of()), 11L);

        verify(supervisor).finish(ref, run, "字幕摘要失败");
        verify(artifactStore, never()).settleCleanedAndSupersedeDownstream(any(), any());
    }

    @Test
    void queuedRegenerateInterruptFinishesRun() {
        TaskRun run = runningRun(TaskRunType.RECLEAN, null, 2);
        RunRef ref = ref(run);

        pipeline(interruptOnQueueGate()).regenerate(run, ref, urlWorkspace("https://example.com/v"),
                new SubtitleKeywords(List.of()), 11L);

        verify(supervisor).finish(ref, run, "重新生成在等待执行配额时被中断：请重新触发");
        verify(cleanupStage, never()).regenerate(any(), any(), any(), any());
    }

    // ---------- 翻译（TRANSLATE） ----------

    @Test
    void translateRunsTranslationThenCreatesPlaceholderAndSettles() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);

        pipeline(inlineGate()).translate(run, ref, urlWorkspace("https://example.com/v"),
                SOURCE_LANG, TARGET_LANG, CLEANED_ARTIFACT_ID);

        InOrder order = inOrder(translationStage, artifactStore);
        order.verify(translationStage).translate(eq(new MediaInput.RemoteUrl("https://example.com/v")),
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
        when(artifactStore.findIntactArtifact(eq(TASK_ID), eq(ArtifactType.TRANSLATED),
                eq(TARGET_LANG.getCode()), any(Path.class)))
                .thenReturn(Optional.of(Mockito.mock(SubtitleArtifact.class)));

        pipeline(inlineGate()).translate(run, ref, urlWorkspace("https://example.com/v"),
                SOURCE_LANG, TARGET_LANG, CLEANED_ARTIFACT_ID);

        verify(translationStage, never()).translate(any(), any(), any(), any(), any());
        verify(artifactStore, never()).createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any());
        verify(artifactStore, never()).settleArtifact(any(), any());
        verify(supervisor).finish(ref, run, null);
        ArgumentCaptor<StageProgress> stage = ArgumentCaptor.forClass(StageProgress.class);
        verify(supervisor).advance(eq(ref), eq(run), stage.capture());
        assertEquals(StageProgress.State.SKIPPED, stage.getValue().state(),
                "复用命中要告诉订阅者这条 run 是跳过收尾的");
    }

    @Test
    void translateFailureFinishesRunWithoutPlaceholder() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);
        doThrow(new IllegalStateException("翻译失败"))
                .when(translationStage).translate(any(), any(), any(), any(), any());

        pipeline(inlineGate()).translate(run, ref, urlWorkspace("https://example.com/v"),
                SOURCE_LANG, TARGET_LANG, CLEANED_ARTIFACT_ID);

        verify(supervisor).finish(ref, run, "翻译失败");
        verify(artifactStore, never()).createCreatingPlaceholder(anyLong(), anyLong(), any(), any(), any(), any());
        verify(artifactStore, never()).settleArtifact(any(), any());
    }

    @Test
    void queuedTranslationInterruptFinishesRun() {
        TaskRun run = runningRun(TaskRunType.TRANSLATE, TARGET_LANG.getCode(), 3);
        RunRef ref = ref(run);

        pipeline(interruptOnQueueGate()).translate(run, ref, urlWorkspace("https://example.com/v"),
                SOURCE_LANG, TARGET_LANG, CLEANED_ARTIFACT_ID);

        verify(supervisor).finish(ref, run, "翻译在等待执行配额时被中断：请重新触发翻译");
        verify(translationStage, never()).translate(any(), any(), any(), any(), any());
    }

    // ---------- 假闸门 ----------

    /** 假闸门①：立刻同步跑 body（只看管线在「拿到许可」之后跑成什么样）。 */
    private static TaskConcurrencyGate inlineGate() {
        return (body, onQueueInterrupted) -> body.run();
    }

    /** 假闸门②：模拟「排队被打断」——body 不跑，直接回调。 */
    private static TaskConcurrencyGate interruptOnQueueGate() {
        return (body, onQueueInterrupted) -> onQueueInterrupted.run();
    }

    // ---------- 工厂 ----------

    private static TaskRun runningRun(TaskRunType type, String targetLanguage, int runNo) {
        LocalDateTime now = LocalDateTime.now();
        return TaskRun.restore(1L, TASK_ID, runNo, type, targetLanguage, TaskRunStatus.RUNNING, null, null,
                now, null, now);
    }

    private static RunRef ref(TaskRun run) {
        return RunRef.of(new TaskNo(TASK_NO), run);
    }

    private static Workspace urlWorkspace(String url) {
        return Workspace.of(SourceType.URL, new SourceKey("BV11QMd6WEJp|1"), url, url);
    }
}
