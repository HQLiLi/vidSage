package com.vidsage.application.task.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.vidsage.domain.task.policy.TaskStageEvent;
import com.vidsage.domain.task.port.VideoTaskRepository;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * {@link TaskProgressNotifier} 的单元测试：仓储用 Mockito mock、注册表用真实实例
 * （订阅是否被清直接落在真注册表上验证），不起 Spring 容器。
 *
 * <p>钉住 design.md 保真清单的三处：落库先于广播（第 5 条）、终态广播抛异常也必须清订阅
 * （第 6 条）、翻译进度不落库（第 8 条）。
 */
class TaskProgressNotifierTest {

    private final VideoTaskRepository repository = Mockito.mock(VideoTaskRepository.class);
    private final TaskSubscriptionRegistry registry = new TaskSubscriptionRegistry();
    private final TaskProgressNotifier notifier = new TaskProgressNotifier(repository, registry);

    // ---------- 阶段进度 ----------

    @Test
    void saveCurrentStageAndBroadcastUpdatesStagePersistsThenBroadcasts() {
        // 广播要进 inOrder，注册表这例用 mock（真注册表不参与 Mockito 顺序验证）
        TaskSubscriptionRegistry broadcastRecorder = Mockito.mock(TaskSubscriptionRegistry.class);
        TaskProgressNotifier local = new TaskProgressNotifier(repository, broadcastRecorder);
        VideoTask task = Mockito.spy(runningTask());
        StageEvent event = new StageEvent(SubtitleStageName.EXTRACT_AUDIO.getStageName(), 30,
                "音频抽取完成", StageEvent.StageState.SUCCEEDED);

        local.saveCurrentStageAndBroadcast(task, event);

        // 顺序即保真清单第 5 条：updateStage → 落库 → 广播，落库必须先于广播
        InOrder order = inOrder(task, repository, broadcastRecorder);
        order.verify(task).updateStage(event.stage());
        order.verify(repository).update(task);
        order.verify(broadcastRecorder).broadcast(eq(task.getTaskNo().value()), any(TaskProgressMessage.class));
        assertEquals(event.stage(), task.getCurrentStage(), "阶段名应已推进到 current_stage");
    }

    @Test
    void broadcastTranslationStageBroadcastsWithoutPersisting() {
        VideoTask task = runningTask();
        task.updateStage(SubtitleStageName.EXTRACT_AUDIO.getStageName());
        StageEvent event = new StageEvent(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), 40,
                "第 1/2 批", StageEvent.StageState.RUNNING);
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);

        notifier.broadcastTranslationStage(task, event);

        assertEquals(1, received.size(), "翻译阶段事件应广播一次");
        assertEquals(event.stage(), received.get(0).stage().stage());
        verify(repository, never()).update(any());
        assertEquals(SubtitleStageName.EXTRACT_AUDIO.getStageName(), task.getCurrentStage(),
                "翻译进度刻意不落 current_stage（保真清单第 8 条）");
    }

    // ---------- 终态 ----------

    @Test
    void completeTaskBroadcastsFromStageEventAndClearsSubscription() {
        VideoTask task = succeededTask();
        StageEvent expected = TaskStageEvent.from(task);
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);

        notifier.completeTask(task);

        verify(repository).update(task);
        assertEquals(1, received.size(), "终态只广播一条");
        assertEquals(expected, received.get(0).stage(), "主管线终态的 stage 取 TaskStageEvent.from");
        assertEquals(TaskStatus.SUCCEEDED, received.get(0).taskStatus());
        // complete 生效：终态之后再广播无人可收
        registry.broadcast(task.getTaskNo().value(),
                new TaskProgressMessage(task.getTaskNo().value(), TaskStatus.FAILED, null, null));
        assertEquals(1, received.size(), "终态收口后注册表不得再向订阅者投递事件");
    }

    @Test
    void completeTranslationBroadcastsFromTranslationStageAndClearsSubscription() {
        VideoTask task = succeededWithTranslation();
        StageEvent expected = TaskStageEvent.fromTranslation(task);
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);

        notifier.completeTranslation(task);

        verify(repository).update(task);
        assertEquals(1, received.size(), "翻译终态只广播一条");
        assertEquals(expected, received.get(0).stage(), "翻译终态的 stage 取 TaskStageEvent.fromTranslation");
        assertEquals(TranslationStatus.SUCCEEDED, received.get(0).translationStatus());
        registry.broadcast(task.getTaskNo().value(),
                new TaskProgressMessage(task.getTaskNo().value(), TaskStatus.FAILED, null, null));
        assertEquals(1, received.size(), "翻译终态收口后注册表不得再投递");
    }

    @Test
    void completeTaskClearsSubscriptionEvenWhenBroadcastThrows() {
        TaskSubscriptionRegistry throwing = Mockito.mock(TaskSubscriptionRegistry.class);
        TaskProgressNotifier local = new TaskProgressNotifier(repository, throwing);
        VideoTask task = succeededTask();
        doThrow(new IllegalStateException("模拟广播失败")).when(throwing).broadcast(anyString(), any());

        assertThrows(IllegalStateException.class, () -> local.completeTask(task),
                "广播异常必须原样上抛，不得被吞");

        verify(throwing).complete(task.getTaskNo().value());
        verify(repository).update(task);
    }

    // ---------- 快照 ----------

    @Test
    void createSnapshotAssemblesWithoutSendingOrPersisting() {
        VideoTask task = succeededTask();
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);

        TaskProgressMessage snapshot = notifier.createSnapshot(task);

        assertEquals(task.getTaskNo().value(), snapshot.taskNo());
        assertEquals(task.getStatus(), snapshot.taskStatus());
        assertEquals(TaskStageEvent.from(task), snapshot.stage(), "快照与终态广播挑同一个渲染器");
        assertEquals(task.getTranslationStatus(), snapshot.translationStatus());
        verify(repository, never()).update(any());
        assertEquals(0, received.size(), "快照只组装不发送，不碰注册表");
    }

    // ---------- 工厂 ----------

    private static VideoTask runningTask() {
        VideoTask task = VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir-a1b2c3d4e5f6");
        task.start();
        return task;
    }

    /** 主任务已成功、当前阶段留痕的终态任务——主管线终态用例的起点。 */
    private static VideoTask succeededTask() {
        VideoTask task = runningTask();
        task.updateStage(SubtitleStageName.CLEAN_SUBTITLES.getStageName());
        task.succeed();
        return task;
    }

    /** 主任务成功且翻译已完成——翻译终态用例的起点。 */
    private static VideoTask succeededWithTranslation() {
        VideoTask task = succeededTask();
        task.translationStart();
        task.translationSucceed();
        return task;
    }
}
