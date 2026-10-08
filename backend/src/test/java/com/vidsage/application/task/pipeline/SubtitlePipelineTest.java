package com.vidsage.application.task.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.WorkspaceService;
import com.vidsage.application.subtitle.pipeline.SubtitleCleanupCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitlePreparationCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitleTranslationCoordinator;
import com.vidsage.application.task.TaskProgressMessage;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.config.TaskProperties;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.vidsage.domain.task.port.VideoTaskRepository;
import com.vidsage.infrastructure.fs.NioFileStateReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * {@link SubtitlePipeline} 的单元测试：依赖全部 mock，工作区用真实实例 + 临时目录；
 * 订阅注册表用真实实例（广播 / 终态收口的行为直接落在真注册表上验证）。
 * 同步用例直接调包内可见的 {@code runPipeline(taskNo)} 绕开虚拟线程的时序，翻译触发用例直接调
 * {@code translateTask(taskNo)}（同步段）+ Mockito timeout 等异步段；并发用例走 {@code execute()}
 * 用 Mockito 的 timeout / after 验证信号量限流。
 *
 * <p>2026-10-02 两段式：自动管线到清洗为止（prepare 0..70 / clean 70..100），翻译拆出为
 * {@code translateTask} 按需触发（幂等 / 不重入 / 终态收口各有钉子）。
 */
class SubtitlePipelineTest {

    @TempDir
    Path tempDir;

    private final VideoTaskRepository repository = Mockito.mock(VideoTaskRepository.class);
    private final SubtitlePreparationCoordinator subtitlePreparationCoordinator = Mockito.mock(SubtitlePreparationCoordinator.class);
    private final SubtitleCleanupCoordinator cleanupStage = Mockito.mock(SubtitleCleanupCoordinator.class);
    private final SubtitleTranslationCoordinator translationStage = Mockito.mock(SubtitleTranslationCoordinator.class);
    private final TaskSubscriptionRegistry registry = new TaskSubscriptionRegistry();

    private SubtitlePipeline service(int maxConcurrent) {
        return new SubtitlePipeline(repository,
                new WorkspaceService(new WorkspaceProperties(tempDir.resolve("works").toString()),
                        new NioFileStateReader()),
                subtitlePreparationCoordinator, cleanupStage, translationStage, registry, new TaskProperties(maxConcurrent));
    }

    // ---------- 正常路径（自动管线：prepare → clean，两段式后不含翻译） ----------

    @Test
    void runsStagesInOrderAndSucceedsForUrlTask() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        // clean 的桩发一条阶段事件（真实服务层会发「开始清洗」事件），验证回调驱动 current_stage
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(2);
            listener.onEvent(new StageEvent(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 0, "开始清洗", StageEvent.StageState.RUNNING));
            return null;
        }).when(cleanupStage).clean(any(MediaInput.class), anyString(), any(ProgressListener.class));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        MediaInput input = new MediaInput.RemoteUrl("https://example.com/v");
        InOrder order = inOrder(subtitlePreparationCoordinator, cleanupStage);
        order.verify(subtitlePreparationCoordinator).prepare(eq(input), any(ProgressListener.class));
        order.verify(cleanupStage).clean(eq(input), eq(""), any(ProgressListener.class));

        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
        assertNotNull(task.getFinishedAt(), "成功应落 finished_at");
        assertEquals(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), task.getCurrentStage(), "最后一个阶段应留痕在 current_stage");
        assertNull(task.getTranslationStatus(), "自动管线不触发翻译，翻译子状态保持「从未触发」");
        // start + clean 回调 + succeed（mock 的 prepare 不发事件）
        verify(repository, Mockito.times(3)).update(task);
    }

    @Test
    void autoPipelineNeverInvokesTranslation() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        verify(translationStage, never()).translate(any(), any());
        verify(translationStage, never()).hasTranslationArtifact(any());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(),
                "管线成功即终态——翻译等用户对 SUCCEEDED 任务显式触发");
    }

    @Test
    void reconstructsUploadedSourcePathFromPersistedWorkspaceDir() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4",
                new WorkspaceKey("a1b2c3d4e5f6"), "demo.mp4-a1b2c3d4e5f6");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        ArgumentCaptor<MediaInput> captor = ArgumentCaptor.forClass(MediaInput.class);
        verify(subtitlePreparationCoordinator).prepare(captor.capture(), any(ProgressListener.class));
        MediaInput input = captor.getValue();
        assertTrue(input instanceof MediaInput.InWorkspace, () -> "上传任务应还原为 InWorkspace：" + input);
        MediaInput.InWorkspace in = (MediaInput.InWorkspace) input;
        Path expectedDir = tempDir.resolve("works").resolve("demo.mp4-a1b2c3d4e5f6");
        assertEquals(expectedDir.resolve("source.mp4"), in.value(),
                "上传任务必须按持久化的 workspace_dir 定位 source.mp4，不能从输入重算 key");
        // 身份由 dirLabel + key 随输入携带：workspaceFor 用它们重算目录，必须与持久化的 workspace_dir 逐字一致
        assertEquals(expectedDir, new WorkspaceService(
                        new WorkspaceProperties(tempDir.resolve("works").toString()), new NioFileStateReader())
                .workspaceFor(in),
                "dirLabel + key 重建的目录必须与持久化的 workspace_dir 逐字一致（下游零重算）");
        assertEquals("a1b2c3d4e5f6", in.key().value(), "内容 key 应随输入携带");
    }

    @Test
    void taskProgressListenerPersistsStageNameFromPrepareEvents() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        // prepare 回调里先推一个阶段事件再抛异常：失败消息嵌入的阶段名即为回调落库的 current_stage
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onEvent(new StageEvent(SubtitleStageName.EXTRACT_AUDIO.getStageName(), 30, "音频抽取完成", StageEvent.StageState.SUCCEEDED));
            throw new IllegalStateException("boom");
        }).when(subtitlePreparationCoordinator).prepare(any(MediaInput.class), any(ProgressListener.class));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(SubtitleStageName.EXTRACT_AUDIO.getStageName(), task.getCurrentStage(), "阶段事件的 stage 名应已落库");
        assertEquals("任务执行失败：boom", task.getErrorMessage());
    }

    // ---------- 失败路径 ----------

    @Test
    void prepareFailureFailsTaskAndSkipsRemainingStages() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(subtitlePreparationCoordinator.prepare(any(MediaInput.class), any(ProgressListener.class)))
                .thenThrow(new IllegalStateException("ASR 转写任务失败：quota exceeded"));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("任务执行失败：ASR 转写任务失败：quota exceeded", task.getErrorMessage(),
                "首个阶段尚无阶段名，错误信息不带阶段前缀");
        assertNotNull(task.getFinishedAt());
        verify(cleanupStage, never()).clean(any(), anyString(), any());
    }

    @Test
    void cleanFailureFailsTask() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        // 真实 clean 会先发「开始清洗」再干活
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(2);
            listener.onEvent(new StageEvent(SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 0, "开始清洗", StageEvent.StageState.RUNNING));
            throw new IllegalStateException("字幕尚未获取：缺 segments-cloud.json");
        }).when(cleanupStage).clean(any(MediaInput.class), anyString(), any(ProgressListener.class));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("任务执行失败：字幕尚未获取：缺 segments-cloud.json", task.getErrorMessage());
        verify(translationStage, never()).translate(any(), any());
    }

    @Test
    void failMessageFallsBackToExceptionClassNameWhenMessageNull() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(subtitlePreparationCoordinator.prepare(any(MediaInput.class), any(ProgressListener.class)))
                .thenThrow(new NullPointerException());
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        assertEquals("任务执行失败：NullPointerException", task.getErrorMessage());
        assertEquals(TaskStatus.FAILED, task.getStatus());
    }

    // ---------- 进度广播与终态收口 ----------

    @Test
    void stageEventsAreBroadcastAsTaskProgressMessagesWithRemappedProgress() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);
        // prepare 收到的是 remap(composite, 0, 70) 后的 listener：直接回调 50 → 广播出去应为 35
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onEvent(new StageEvent(SubtitleStageName.EXTRACT_AUDIO.getStageName(), 50, "音频抽取完成", StageEvent.StageState.SUCCEEDED));
            return null;
        }).when(subtitlePreparationCoordinator).prepare(any(MediaInput.class), any(ProgressListener.class));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        // 阶段事件 + 成功后的终态广播
        assertEquals(2, received.size(), () -> "阶段事件应组 TaskProgressMessage 广播给订阅者：" + received);
        TaskProgressMessage event = received.get(0);
        assertEquals(task.getTaskNo().value(), event.taskNo());
        assertEquals(TaskStatus.RUNNING, event.taskStatus());
        assertNull(event.translationStatus(), "主管线事件的翻译子状态为 null（从未触发）");
        assertEquals(SubtitleStageName.EXTRACT_AUDIO.getStageName(), event.stage().stage());
        assertEquals(35, event.stage().progress(), "prepare 区间 0..70 的中点：50 应映射为 35");
        assertEquals(TaskStatus.SUCCEEDED, received.get(1).taskStatus(), "终态广播随后到达");
    }

    @Test
    void successBroadcastsTerminalEventAndCompletesSubscription() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        assertEquals(1, received.size(), "终态广播是唯一一条（mock 服务不发阶段事件）");
        TaskProgressMessage event = received.get(0);
        assertEquals(TaskStatus.SUCCEEDED, event.taskStatus());
        // complete 清空了订阅者：终态之后再广播该任务的事件无人可收
        registry.broadcast(task.getTaskNo().value(),
                new TaskProgressMessage(task.getTaskNo().value(), TaskStatus.FAILED, null, null));
        assertEquals(1, received.size(), "终态收口后注册表不得再向订阅者投递事件");
    }

    @Test
    void failureBroadcastsTerminalEventWithErrorMessage() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);
        when(subtitlePreparationCoordinator.prepare(any(MediaInput.class), any(ProgressListener.class)))
                .thenThrow(new IllegalStateException("quota exceeded"));
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        assertEquals(1, received.size());
        TaskProgressMessage event = received.get(0);
        assertEquals(TaskStatus.FAILED, event.taskStatus());
        assertEquals("任务执行失败：quota exceeded", event.stage().message(), "终态事件的消息应携带错误信息");
        registry.broadcast(task.getTaskNo().value(),
                new TaskProgressMessage(task.getTaskNo().value(), TaskStatus.FAILED, null, null));
        assertEquals(1, received.size(), "终态收口后注册表不得再向订阅者投递事件");
    }

    @Test
    void brokenSubscriberDoesNotBreakPipelineOrOtherSubscribers() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), event -> {
            throw new IllegalStateException("写入失败（模拟断连的 emitter）");
        });
        registry.subscribe(task.getTaskNo().value(), received::add);
        SubtitlePipeline pipeline = service(2);

        pipeline.runPipeline(task.getTaskNo());

        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(), "单个订阅者失败不得把任务拖成 FAILED");
        assertEquals(1, received.size(), "坏订阅者之外的其他订阅者照常收到终态事件");
        assertEquals(TaskStatus.SUCCEEDED, received.get(0).taskStatus());
    }

    // ---------- 启动守卫 ----------

    @Test
    void missingTaskRecordAbortsBeforeAnyStage() {
        when(repository.findByTaskNo(new TaskNo("ghost"))).thenReturn(Optional.empty());

        service(2).runPipeline(new TaskNo("ghost"));

        verify(subtitlePreparationCoordinator, never()).prepare(any(), any());
        verify(repository, never()).update(any());
    }

    @Test
    void nonPendingTaskIsSkippedWithoutBeingFailed() {
        VideoTask task = urlTask("https://example.com/v");
        task.start();   // 已被另一次 execute 启动过
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        service(2).runPipeline(task.getTaskNo());

        verify(subtitlePreparationCoordinator, never()).prepare(any(), any());
        assertEquals(TaskStatus.RUNNING, task.getStatus(), "重复触发不得把别人的执行打成 FAILED");
        verify(repository, never()).update(any());
    }

    // ---------- 按需翻译（2026-10-02 两段式，translateTask） ----------

    @Test
    void translateTaskRejectsWhenMainTaskNotSucceeded() {
        VideoTask pending = urlTask("https://example.com/v");
        when(repository.findByTaskNo(pending.getTaskNo())).thenReturn(Optional.of(pending));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(2).translateTask(pending.getTaskNo()),
                "主任务未 SUCCEEDED 时触发翻译必须被拒（错在调用方触发太早 → 400）");

        assertTrue(e.getMessage().contains("字幕尚未就绪"), e.getMessage());
        assertTrue(e.getMessage().contains(pending.getTaskNo().value()), e.getMessage());
        verify(translationStage, never()).hasTranslationArtifact(any());
        verify(translationStage, never()).translate(any(), any());
    }

    @Test
    void translateTaskThrowsNotFoundWhenTaskMissing() {
        when(repository.findByTaskNo(new TaskNo("ghost"))).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> service(2).translateTask(new TaskNo("ghost")));
    }

    @Test
    void translateTaskReusesExistingArtifactWithoutLLM() {
        VideoTask task = succeededTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        // 检查点已在（上一次成功的翻译，或两段式改造前的存量产物）
        when(translationStage.hasTranslationArtifact(any(MediaInput.class))).thenReturn(true);
        SubtitlePipeline pipeline = service(2);

        pipeline.translateTask(task.getTaskNo());

        assertEquals(TranslationStatus.SUCCEEDED, task.getTranslationStatus(),
                "检查点已在 → 幂等收敛为 SUCCEEDED");
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(), "主状态不被翻译幂等收敛改动");
        verify(repository, times(1)).update(task);
        verify(translationStage, never()).translate(any(), any());
    }

    @Test
    void translateTaskIsNoOpWhenArtifactExistsAndTranslationAlreadySucceeded() {
        VideoTask task = succeededTask("https://example.com/v");
        task.translationStart();
        task.translationSucceed();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(translationStage.hasTranslationArtifact(any(MediaInput.class))).thenReturn(true);
        SubtitlePipeline pipeline = service(2);

        pipeline.translateTask(task.getTaskNo());

        verify(repository, never()).update(task);
        verify(translationStage, never()).translate(any(), any());
    }

    @Test
    void translateTaskDoesNotReenterWhileTranslationRunning() {
        VideoTask task = succeededTask("https://example.com/v");
        task.translationStart();
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        SubtitlePipeline pipeline = service(2);

        pipeline.translateTask(task.getTaskNo());

        assertEquals(TranslationStatus.RUNNING, task.getTranslationStatus(), "进行中的翻译保持 RUNNING");
        // 重复触发在检查点判定之前即被拦下
        verify(translationStage, never()).hasTranslationArtifact(any());
        // 重复触发不得重入翻译
        verify(translationStage, never()).translate(any(), any());
        // 重复触发零落库
        verify(repository, never()).update(task);
    }

    @Test
    void translateTaskStartsTranslationAndCompletesWithTerminalBroadcast() {
        VideoTask task = succeededTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(translationStage.hasTranslationArtifact(any(MediaInput.class))).thenReturn(false);
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onEvent(new StageEvent(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), 40, "第 1/2 批",
                    StageEvent.StageState.RUNNING));
            return null;
        }).when(translationStage).translate(any(MediaInput.class), any(ProgressListener.class));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);
        SubtitlePipeline pipeline = service(2);

        pipeline.translateTask(task.getTaskNo());

        // 同步段：translationStart 已落库，202 响应的视图即反映「已触发」
        assertEquals(TranslationStatus.RUNNING, task.getTranslationStatus());
        // 异步段完成后第二次落库（translationSucceed）——times(2) 即终态同步点
        verify(repository, timeout(2000).times(2)).update(task);
        assertEquals(TranslationStatus.SUCCEEDED, task.getTranslationStatus());

        // 事件流：1 条进度（progress 直通，无 remap）+ 1 条翻译终态
        assertEquals(2, received.size(), () -> "翻译进度 + 终态应广播给订阅者：" + received);
        TaskProgressMessage progress = received.get(0);
        assertEquals(TaskStatus.SUCCEEDED, progress.taskStatus(),
                "翻译事件的 taskStatus 恒为主状态 SUCCEEDED");
        assertEquals(TranslationStatus.RUNNING, progress.translationStatus(),
                "进度事件携带进行中的翻译子状态（SSE 终态判定据此不收口连接）");
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), progress.stage().stage());
        assertEquals(40, progress.stage().progress(), "翻译事件自成 0..100 段，直通不 remap");

        TaskProgressMessage terminal = received.get(1);
        assertEquals(TaskStatus.SUCCEEDED, terminal.taskStatus(), "翻译终态广播的 taskStatus 用主状态");
        assertEquals(TranslationStatus.SUCCEEDED, terminal.translationStatus());
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), terminal.stage().stage());
        assertEquals(100, terminal.stage().progress());
        assertEquals(StageEvent.StageState.SUCCEEDED, terminal.stage().state());

        // 终态收口后注册表不再投递
        registry.broadcast(task.getTaskNo().value(),
                new TaskProgressMessage(task.getTaskNo().value(), TaskStatus.FAILED, null, null));
        assertEquals(2, received.size(), "翻译终态 complete 后订阅者应已清空");
    }

    @Test
    void translationFailureMarksTranslationFailedAndBroadcastsTerminal() {
        VideoTask task = succeededTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(translationStage.hasTranslationArtifact(any(MediaInput.class))).thenReturn(false);
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onEvent(new StageEvent(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), 40, "第 1/2 批",
                    StageEvent.StageState.RUNNING));
            throw new IllegalStateException("LLM 返回掉行");
        }).when(translationStage).translate(any(MediaInput.class), any(ProgressListener.class));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);
        SubtitlePipeline pipeline = service(2);

        pipeline.translateTask(task.getTaskNo());

        // 异步段失败后第二次落库（translationFail）——times(2) 即终态同步点
        verify(repository, timeout(2000).times(2)).update(task);
        assertEquals(TranslationStatus.FAILED, task.getTranslationStatus());
        assertEquals("翻译失败：LLM 返回掉行", task.getErrorMessage(), "失败原因带「翻译失败：」前缀落库");
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(), "翻译失败不得回退主状态");

        assertEquals(2, received.size(), () -> "进度 + 失败终态应广播给订阅者：" + received);
        TaskProgressMessage terminal = received.get(1);
        assertEquals(TaskStatus.SUCCEEDED, terminal.taskStatus());
        assertEquals(TranslationStatus.FAILED, terminal.translationStatus());
        assertEquals(StageEvent.StageState.FAILED, terminal.stage().state());
        assertEquals("翻译失败：LLM 返回掉行", terminal.stage().message(),
                "翻译失败终态事件的消息携带失败原因（SSE 上唯一的错误出口）");

        registry.broadcast(task.getTaskNo().value(),
                new TaskProgressMessage(task.getTaskNo().value(), TaskStatus.FAILED, null, null));
        assertEquals(2, received.size(), "翻译终态 complete 后订阅者应已清空");
    }

    // ---------- 并发限流（走真实 execute + 虚拟线程） ----------

    @Test
    void secondTaskQueuesWhileConcurrencyLimitIsHeld() throws Exception {
        VideoTask first = urlTask("https://example.com/first");
        VideoTask second = urlTask("https://example.com/second");
        when(repository.findByTaskNo(first.getTaskNo())).thenReturn(Optional.of(first));
        when(repository.findByTaskNo(second.getTaskNo())).thenReturn(Optional.of(second));

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstStarted.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return null;
        }).when(subtitlePreparationCoordinator).prepare(eq(new MediaInput.RemoteUrl("https://example.com/first")),
                any(ProgressListener.class));

        SubtitlePipeline pipeline = service(1);   // 并发上限 1：第二个任务必须排队
        pipeline.execute(first.getTaskNo());
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "第一个任务应开始执行");

        pipeline.execute(second.getTaskNo());
        // 配额被第一个任务占着：第二个任务保持 PENDING，不得进入 prepare
        verify(subtitlePreparationCoordinator, org.mockito.Mockito.after(300).never())
                .prepare(eq(new MediaInput.RemoteUrl("https://example.com/second")), any(ProgressListener.class));
        assertEquals(TaskStatus.PENDING, second.getStatus(), "排队中的任务必须保持 PENDING");

        releaseFirst.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while ((first.getStatus() != TaskStatus.SUCCEEDED || second.getStatus() != TaskStatus.SUCCEEDED)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        verify(subtitlePreparationCoordinator, timeout(5000)).prepare(
                eq(new MediaInput.RemoteUrl("https://example.com/second")), any(ProgressListener.class));
        assertEquals(TaskStatus.SUCCEEDED, first.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, second.getStatus());
    }

    // ---------- 工厂 ----------

    private static VideoTask urlTask(String url) {
        return VideoTask.newPending(TaskInputType.URL, url, new WorkspaceKey("a1b2c3d4e5f6"), "dir-a1b2c3d4e5f6");
    }

    /** 主任务已成功的任务（自动管线跑完、翻译从未触发）——按需翻译用例的起点。 */
    private static VideoTask succeededTask(String url) {
        VideoTask task = urlTask(url);
        task.start();
        task.succeed();
        return task;
    }
}
