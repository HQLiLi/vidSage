package com.vidsage.application.task.notify;

import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.policy.TaskStageEvent;
import com.vidsage.domain.task.port.VideoTaskRepository;
import org.springframework.stereotype.Component;

/**
 * 任务进度通知器
 */
@Component
public class TaskProgressNotifier {
    private final VideoTaskRepository videoTaskRepository;

    private final TaskSubscriptionRegistry taskSubscriptionRegistry;

    public TaskProgressNotifier(VideoTaskRepository videoTaskRepository, TaskSubscriptionRegistry taskSubscriptionRegistry) {
        this.videoTaskRepository = videoTaskRepository;
        this.taskSubscriptionRegistry = taskSubscriptionRegistry;
    }

    /**
     * 推进任务，先将任务状态落库再广播
     *
     * @param videoTask     任务
     * @param stageProgress 阶段进度
     */
    public void saveCurrentStageAndBroadcast(VideoTask videoTask, StageProgress stageProgress) {
        videoTask.updateStage(stageProgress.stage());
        videoTaskRepository.update(videoTask);
        broadcast(videoTask, stageProgress);
    }

    /**
     * 翻译阶段推进，只广播
     *
     * @param videoTask     任务
     * @param stageProgress 阶段进度
     */
    public void broadcastTranslationStage(VideoTask videoTask, StageProgress stageProgress) {
        broadcast(videoTask, stageProgress);
    }

    /**
     * 任务完成
     */
    public void completeTask(VideoTask task) {
        videoTaskRepository.update(task);
        broadcastTerminal(task, TaskStageEvent.from(task));
    }

    /**
     * 完成翻译任务
     *
     * @param task 任务
     */
    public void completeTranslation(VideoTask task) {
        videoTaskRepository.update(task);
        broadcastTerminal(task, TaskStageEvent.fromTranslation(task));
    }

    /**
     * 组装 SSE 连接建立时的<b>快照事件</b>（design.md「SSE 契约」：DB 快照即权威，事件不落库）：
     * 只组装不发送——发送由 web 层决定，本方法不碰注册表、不落库。
     *
     * @param task 任务实例
     * @return 快照事件
     */
    public TaskProgressMessage createSnapshot(VideoTask task) {
        return TaskProgressMessage.of(task.getTaskNo().value(), task.getStatus(),
                TaskStageEvent.from(task), task.getTranslationStatus());
    }


    /**
     * 广播指定任务的状态
     *
     * @param task          任务
     * @param stageProgress 阶段进度
     */
    private void broadcast(VideoTask task, StageProgress stageProgress) {
        String taskNo = task.getTaskNo().value();
        taskSubscriptionRegistry.broadcast(taskNo,
                TaskProgressMessage.of(taskNo, task.getStatus(), stageProgress, task.getTranslationStatus())
        );
    }

    /**
     * 终态广播：告诉每个订阅者订阅结束。
     * 在广播结束后清除该任务的所有订阅关系
     *
     * @param task          任务
     * @param stageProgress 阶段进度
     */
    private void broadcastTerminal(VideoTask task, StageProgress stageProgress) {
        try {
            broadcast(task, stageProgress);
        } finally {
            taskSubscriptionRegistry.complete(task.getTaskNo().value());
        }
    }


}
