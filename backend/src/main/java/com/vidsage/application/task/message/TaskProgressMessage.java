package com.vidsage.application.task.message;

import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.vidsage.domain.task.VideoTask;

/**
 * 任务进度消息：SSE 事件与连接快照的载荷。
 *
 * @param taskNo            对外任务号，与订阅端点的 taskNo 一致
 * @param taskStatus        事件发出时的任务主状态
 * @param stage             最近阶段事件；终态广播可无阶段细节（为 {@code null}），订阅端建立连接前若任务
 *                          尚未产生任何阶段事件（current_stage 为空）同样为 {@code null}
 * @param translationStatus 翻译子状态；从未触发（含全部主管线事件）时为 {@code null}，
 *                          翻译事件携带当前子状态（RUNNING / SUCCEEDED / FAILED）
 * @param terminal          任务是否结束（已完成或者任务失败）
 */
public record TaskProgressMessage(
        String taskNo,
        TaskStatus taskStatus,
        StageProgress stage,
        TranslationStatus translationStatus,
        boolean terminal
) {
    /**
     * terminal 派生：主状态与翻译子状态都收敛了（{@link VideoTask#isEventTerminal}），
     * <b>且没有在途后置会话</b>（阶段事件不是 RUNNING 态）。
     *
     * <p>为什么不需要新字段（隐式派生）：后置会话在跑时，快照的 stage 必定是
     * RUNNING 态（由 {@code TaskStageEvent.from} 按登记表产出）；主状态终态时，DB 派生的 stage 必定不是
     * RUNNING。两个事实合起来，「stage 非 RUNNING」就是「没有在途会话」的等价判据——
     * 在途会话这件事被隐式编码进 stage 的状态位，保住「一条判据一个来源」，代价是读代码要意识到这层含义。
     */
    public TaskProgressMessage {
        terminal = VideoTask.isEventTerminal(taskStatus, translationStatus)
                && (stage == null || stage.state() != StageProgress.State.RUNNING);
    }

    public static TaskProgressMessage of(String taskNo, TaskStatus taskStatus,
                                         StageProgress stage, TranslationStatus translationStatus) {
        return new TaskProgressMessage(taskNo, taskStatus, stage, translationStatus, false);
    }
}
