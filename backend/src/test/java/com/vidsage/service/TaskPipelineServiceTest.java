package com.vidsage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.config.TaskProperties;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.progress.ProgressListener;
import com.vidsage.model.progress.StageEvent;
import com.vidsage.model.progress.TaskProgressMessage;
import com.vidsage.model.task.TaskInputType;
import com.vidsage.model.task.TaskStatus;
import com.vidsage.persistence.repository.VideoTaskRepository;
import com.vidsage.provider.llm.SubtitleTranslator;
import com.vidsage.subscribe.TaskSubscriptionRegistry;
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
 * {@link TaskPipelineService} 的单元测试：依赖全部 mock，工作区用真实实例 + 临时目录；
 * 订阅注册表用真实实例（广播 / 终态收口的行为直接落在真注册表上验证）。
 * 同步用例直接调包内可见的 {@code run(taskNo)} 绕开虚拟线程时序；并发用例走 {@code execute()}
 * 用 Mockito 的 timeout / after 验证信号量限流。
 */
class TaskPipelineServiceTest {

    @TempDir
    Path tempDir;

    private final VideoTaskRepository repository = Mockito.mock(VideoTaskRepository.class);
    private final SubtitleService subtitleService = Mockito.mock(SubtitleService.class);
    private final SubtitleCleanupService cleanupService = Mockito.mock(SubtitleCleanupService.class);
    private final SubtitleTranslationService translationService = Mockito.mock(SubtitleTranslationService.class);
    private final TaskSubscriptionRegistry registry = new TaskSubscriptionRegistry();

    private TaskPipelineService service(int maxConcurrent) {
        return new TaskPipelineService(repository,
                new WorkspaceService(new WorkspaceProperties(tempDir.resolve("works").toString())),
                subtitleService, cleanupService, translationService, registry, new TaskProperties(maxConcurrent));
    }

    // ---------- 正常路径 ----------

    @Test
    void runsStagesInOrderAndSucceedsForUrlTask() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        // clean / translate 的桩各自发一条阶段事件（真实服务层会发「开始」事件），验证回调驱动 current_stage
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(2);
            listener.onEvent(new StageEvent("clean", 0, "开始清洗", StageEvent.StageState.RUNNING));
            return null;
        }).when(cleanupService).clean(any(MediaInput.class), anyString(), any(ProgressListener.class));
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onEvent(new StageEvent("translate", 100, "翻译完成", StageEvent.StageState.SUCCEEDED));
            return null;
        }).when(translationService).translate(any(MediaInput.class), any(ProgressListener.class));
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        MediaInput input = new MediaInput.RemoteUrl("https://example.com/v");
        InOrder order = inOrder(subtitleService, cleanupService, translationService);
        order.verify(subtitleService).prepare(eq(input), any(ProgressListener.class));
        order.verify(cleanupService).clean(eq(input), eq(""), any(ProgressListener.class));
        order.verify(translationService).translate(eq(input), any(ProgressListener.class));

        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
        assertNotNull(task.getFinishedAt(), "成功应落 finished_at");
        assertEquals(SubtitleTranslator.STAGE_TRANSLATE, task.getCurrentStage(), "最后一个阶段应留痕在 current_stage");
        // start + clean 回调 + translate 回调 + succeed（mock 的 prepare 不发事件）
        verify(repository, Mockito.times(4)).update(task);
    }

    @Test
    void reconstructsUploadedSourcePathFromPersistedWorkspaceDir() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "demo.mp4", "a1b2c3d4e5f6", "demo.mp4-a1b2c3d4e5f6");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        ArgumentCaptor<MediaInput> captor = ArgumentCaptor.forClass(MediaInput.class);
        verify(subtitleService).prepare(captor.capture(), any(ProgressListener.class));
        MediaInput input = captor.getValue();
        assertTrue(input instanceof MediaInput.InWorkspace, () -> "上传任务应还原为 InWorkspace：" + input);
        MediaInput.InWorkspace in = (MediaInput.InWorkspace) input;
        Path expectedDir = tempDir.resolve("works").resolve("demo.mp4-a1b2c3d4e5f6");
        assertEquals(expectedDir.resolve("source.mp4"), in.value(),
                "上传任务必须按持久化的 workspace_dir 定位 source.mp4，不能从输入重算 key");
        assertEquals(expectedDir, in.workspaceDir(), "InWorkspace 必须携带已解析的工作区目录，下游零重算");
        assertEquals("a1b2c3d4e5f6", in.workspaceKey(), "内容 key 应随输入携带");
    }

    @Test
    void taskProgressListenerPersistsStageNameFromPrepareEvents() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        // prepare 回调里先推一个阶段事件再抛异常：失败消息嵌入的阶段名即为回调落库的 current_stage
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onEvent(new StageEvent("extract-audio", 30, "音频抽取完成", StageEvent.StageState.SUCCEEDED));
            throw new IllegalStateException("boom");
        }).when(subtitleService).prepare(any(MediaInput.class), any(ProgressListener.class));
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("extract-audio", task.getCurrentStage(), "阶段事件的 stage 名应已落库");
        assertEquals("任务在 extract-audio 阶段失败：boom", task.getErrorMessage());
    }

    // ---------- 失败路径 ----------

    @Test
    void prepareFailureFailsTaskAndSkipsRemainingStages() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(subtitleService.prepare(any(MediaInput.class), any(ProgressListener.class)))
                .thenThrow(new IllegalStateException("ASR 转写任务失败：quota exceeded"));
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("任务执行失败：ASR 转写任务失败：quota exceeded", task.getErrorMessage(),
                "首个阶段尚无阶段名，错误信息不带阶段前缀");
        assertNotNull(task.getFinishedAt());
        verify(cleanupService, never()).clean(any(), anyString(), any());
        verify(translationService, never()).translate(any(), any());
    }

    @Test
    void cleanFailureFailsTaskAndSkipsTranslate() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        // 真实 clean 会先发「开始清洗」再干活：失败消息里带上阶段名依赖这条事件已落库
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(2);
            listener.onEvent(new StageEvent("clean", 0, "开始清洗", StageEvent.StageState.RUNNING));
            throw new IllegalStateException("字幕尚未获取：缺 segments-cloud.json");
        }).when(cleanupService).clean(any(MediaInput.class), anyString(), any(ProgressListener.class));
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("任务在 clean 阶段失败：字幕尚未获取：缺 segments-cloud.json", task.getErrorMessage());
        verify(translationService, never()).translate(any(), any());
    }

    @Test
    void failMessageFallsBackToExceptionClassNameWhenMessageNull() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(subtitleService.prepare(any(MediaInput.class), any(ProgressListener.class)))
                .thenThrow(new NullPointerException());
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        assertEquals("任务执行失败：NullPointerException", task.getErrorMessage());
        assertEquals(TaskStatus.FAILED, task.getStatus());
    }

    // ---------- 进度广播与终态收口 ----------

    @Test
    void stageEventsAreBroadcastAsTaskProgressMessagesWithRemappedProgress() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo(), received::add);
        // prepare 收到的是 remap(composite, 0, 60) 后的 listener：直接回调 50 → 广播出去应为 30
        doAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onEvent(new StageEvent("extract-audio", 50, "音频抽取完成", StageEvent.StageState.SUCCEEDED));
            return null;
        }).when(subtitleService).prepare(any(MediaInput.class), any(ProgressListener.class));
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        // 阶段事件 + 成功后的终态广播
        assertEquals(2, received.size(), () -> "阶段事件应组 TaskProgressMessage 广播给订阅者：" + received);
        TaskProgressMessage event = received.get(0);
        assertEquals(task.getTaskNo(), event.taskNo());
        assertEquals(TaskStatus.RUNNING, event.taskStatus());
        assertEquals("extract-audio", event.stage().stage());
        assertEquals(30, event.stage().progress(), "prepare 区间 0..60 的中点：50 应映射为 30");
        assertEquals(TaskStatus.SUCCEEDED, received.get(1).taskStatus(), "终态广播随后到达");
    }

    @Test
    void successBroadcastsTerminalEventAndCompletesSubscription() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo(), received::add);
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        assertEquals(1, received.size(), "终态广播是唯一一条（mock 服务不发阶段事件）");
        TaskProgressMessage event = received.get(0);
        assertEquals(TaskStatus.SUCCEEDED, event.taskStatus());
        // complete 清空了订阅者：终态之后再广播该任务的事件无人可收
        registry.broadcast(task.getTaskNo(), new TaskProgressMessage(task.getTaskNo(), TaskStatus.FAILED, null));
        assertEquals(1, received.size(), "终态收口后注册表不得再向订阅者投递事件");
    }

    @Test
    void failureBroadcastsTerminalEventWithErrorMessage() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo(), received::add);
        when(subtitleService.prepare(any(MediaInput.class), any(ProgressListener.class)))
                .thenThrow(new IllegalStateException("quota exceeded"));
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        assertEquals(1, received.size());
        TaskProgressMessage event = received.get(0);
        assertEquals(TaskStatus.FAILED, event.taskStatus());
        assertEquals("任务执行失败：quota exceeded", event.stage().message(), "终态事件的消息应携带错误信息");
        registry.broadcast(task.getTaskNo(), new TaskProgressMessage(task.getTaskNo(), TaskStatus.FAILED, null));
        assertEquals(1, received.size(), "终态收口后注册表不得再向订阅者投递事件");
    }

    @Test
    void brokenSubscriberDoesNotBreakPipelineOrOtherSubscribers() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo(), event -> {
            throw new IllegalStateException("写入失败（模拟断连的 emitter）");
        });
        registry.subscribe(task.getTaskNo(), received::add);
        TaskPipelineService pipeline = service(2);

        pipeline.run(task.getTaskNo());

        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(), "单个订阅者失败不得把任务拖成 FAILED");
        assertEquals(1, received.size(), "坏订阅者之外的其他订阅者照常收到终态事件");
        assertEquals(TaskStatus.SUCCEEDED, received.get(0).taskStatus());
    }

    // ---------- 启动守卫 ----------

    @Test
    void missingTaskRecordAbortsBeforeAnyStage() {
        when(repository.findByTaskNo("ghost")).thenReturn(Optional.empty());

        service(2).run("ghost");

        verify(subtitleService, never()).prepare(any(), any());
        verify(repository, never()).update(any());
    }

    @Test
    void nonPendingTaskIsSkippedWithoutBeingFailed() {
        VideoTask task = urlTask("https://example.com/v");
        task.start();   // 已被另一次 execute 启动过
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));

        service(2).run(task.getTaskNo());

        verify(subtitleService, never()).prepare(any(), any());
        assertEquals(TaskStatus.RUNNING, task.getStatus(), "重复触发不得把别人的执行打成 FAILED");
        verify(repository, never()).update(any());
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
        }).when(subtitleService).prepare(eq(new MediaInput.RemoteUrl("https://example.com/first")),
                any(ProgressListener.class));

        TaskPipelineService pipeline = service(1);   // 并发上限 1：第二个任务必须排队
        pipeline.execute(first.getTaskNo());
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "第一个任务应开始执行");

        pipeline.execute(second.getTaskNo());
        // 配额被第一个任务占着：第二个任务保持 PENDING，不得进入 prepare
        verify(subtitleService, org.mockito.Mockito.after(300).never())
                .prepare(eq(new MediaInput.RemoteUrl("https://example.com/second")), any(ProgressListener.class));
        assertEquals(TaskStatus.PENDING, second.getStatus(), "排队中的任务必须保持 PENDING");

        releaseFirst.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while ((first.getStatus() != TaskStatus.SUCCEEDED || second.getStatus() != TaskStatus.SUCCEEDED)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        verify(subtitleService, timeout(5000)).prepare(
                eq(new MediaInput.RemoteUrl("https://example.com/second")), any(ProgressListener.class));
        assertEquals(TaskStatus.SUCCEEDED, first.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, second.getStatus());
    }

    // ---------- 工厂 ----------

    private static VideoTask urlTask(String url) {
        return VideoTask.newPending(TaskInputType.URL, url, "a1b2c3d4e5f6", "dir-a1b2c3d4e5f6");
    }
}
