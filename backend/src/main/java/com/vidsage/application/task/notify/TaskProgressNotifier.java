package com.vidsage.application.task.notify;

import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.session.TaskSessionRegistry;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.policy.TaskStageEvent;
import com.vidsage.domain.task.port.VideoTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务进度通知器
 */
@Slf4j
@Component
public class TaskProgressNotifier {
    private final VideoTaskRepository videoTaskRepository;

    private final TaskSubscriptionRegistry taskSubscriptionRegistry;

    private final TaskSessionRegistry taskSessionRegistry;

    public TaskProgressNotifier(VideoTaskRepository videoTaskRepository,
                                TaskSubscriptionRegistry taskSubscriptionRegistry,
                                TaskSessionRegistry taskSessionRegistry) {
        this.videoTaskRepository = videoTaskRepository;
        this.taskSubscriptionRegistry = taskSubscriptionRegistry;
        this.taskSessionRegistry = taskSessionRegistry;
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
        // 主状态收敛那一刻没有后置会话：开后置会话的前置条件是主状态已 SUCCEEDED、且同步段抢登记，
        // 主管线 RUNNING 期间根本开不出来——传 null 即可
        broadcastTerminal(task, TaskStageEvent.from(task, null));
    }

    /**
     * 完成翻译任务
     *
     * @param task 任务
     */
    public void completeTranslation(VideoTask task) {
        videoTaskRepository.update(task);
        broadcastTerminal(task,  new StageProgress(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(),
            100, "翻译完成", StageProgress.State.SUCCEEDED));
    }

    /**
     * 阶段进度，只广播不落库（与 {@link #broadcastTranslationStage} 同形的「不落 current_stage」通道）。
     *
     * <p>后置会话（重新生成字幕）走这条：主状态已终态，{@code updateStage} 的 RUNNING 守卫本来也会拒；
     * 精确进度由增量事件携带，重连快照由登记表粗粒度还原。
     *
     * @param task 任务
     * @param stageProgress 阶段进度
     */
    public void broadcastStage(VideoTask task, StageProgress stageProgress) {
        broadcast(task, stageProgress);
    }

    /**
     * 后置会话失败收口：落失败原因（主状态保持 SUCCEEDED，旧字幕还在），广播带原因的 FAILED
     * 阶段事件并清空订阅者（失败可重新触发，收口后重连拿失败快照）。落库再失败只记 ERROR：
     * 虚拟线程无人接异常，吞掉等于静默。
     *
     * @param task   任务（主状态必须是 SUCCEEDED）
     * @param reason 失败原因（不含前缀）；调用方组装成「重新生成失败：」落 error_message
     */
    public void failRegenerate(VideoTask task, String reason) {
        String message = VideoTask.REGENERATE_FAILURE_PREFIX + reason;
        try {
            task.recordRegenerateFailure(message);
            videoTaskRepository.update(task);
            broadcastTerminal(task, new StageProgress(
                    SubtitleStageName.CLEAN_SUBTITLES.getStageName(),
                    0, message, StageProgress.State.FAILED));
            log.error("字幕重新生成失败: taskNo={}, message={}", task.getTaskNo().value(), message);
        } catch (RuntimeException e) {
            log.error("重新生成失败状态落库失败，任务可能停留在 RUNNING 的会话里: taskNo={}, reason={}",
                    task.getTaskNo().value(), e.getMessage(), e);
        }
    }

    /**
     * 组装 SSE 连接建立时的<b>快照事件</b>（DB 快照即权威，事件不落库）：
     * 只组装不发送——发送由 web 层决定，本方法不碰注册表、不落库。
     *
     * @param task 任务实例
     * @return 快照事件
     */
    public TaskProgressMessage createSnapshot(VideoTask task) {
        // 登记查询在调用方（政策保持纯函数）：在途后置会话把快照渲染成该阶段的 RUNNING，
        // terminal 派生据此判 false——重连的人不会一连上就被终态关掉
        String runningStage = taskSessionRegistry.findRunningStage(task.getWorkspaceKey());
        return TaskProgressMessage.of(task.getTaskNo().value(), task.getStatus(),
                TaskStageEvent.from(task, runningStage));
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
                TaskProgressMessage.of(taskNo, task.getStatus(), stageProgress)
        );
    }

    /**
     * 终态广播：告诉每个订阅者订阅结束；
     * 在广播结束后清除该任务的所有订阅关系。
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
