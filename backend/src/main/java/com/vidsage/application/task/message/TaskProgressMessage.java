package com.vidsage.application.task.message;

import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.vidsage.domain.task.VideoTask;

/**
 * 任务进度信息，用于SSE负荷。
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
        StageEvent stage,
        TranslationStatus translationStatus,
        boolean terminal
) {
    public TaskProgressMessage {
        terminal = VideoTask.isEventTerminal(taskStatus, translationStatus);
    }

    public static TaskProgressMessage of(String taskNo, TaskStatus taskStatus,
                                         StageEvent stage, TranslationStatus translationStatus) {
        return new TaskProgressMessage(taskNo, taskStatus, stage, translationStatus, false);
    }
}
