package com.vidsage.application.task.message;

import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.vidsage.domain.task.VideoTask;

/**
 * 任务进度事件：SSE payload 的定锤形态（design.md「SSE 契约」）——
 * {@code GET /api/tasks/{taskNo}/events} 以 {@code event: progress} 推送，data 即本类型的 JSON。
 *
 * <p>任务级状态 + 最近阶段事件的组合：订阅方先靠 {@code taskStatus} 判任务整体走到哪了，
 * 再用 {@code stage} 渲染阶段内进度。终态（SUCCEEDED / FAILED）事件是最后一条——推完即完成
 * SSE 连接，故终态事件允许不带阶段细节（{@code stage} 为 {@code null}，状态本身就是信息）。
 *
 *
 *
 * @param taskNo            对外任务号，与订阅端点的 taskNo 一致
 * @param taskStatus        事件发出时的任务主状态
 * @param stage             最近阶段事件；终态广播可无阶段细节（为 {@code null}），订阅端建立连接前若任务
 *                          尚未产生任何阶段事件（current_stage 为空）同样为 {@code null}
 * @param translationStatus 翻译子状态；从未触发（含全部主管线事件）时为 {@code null}，
 *                          翻译事件携带当前子状态（RUNNING / SUCCEEDED / FAILED）
 */
public record TaskProgressMessage(
        String taskNo,
        TaskStatus taskStatus,
        StageEvent stage,
        TranslationStatus translationStatus) {

    /**
     * 任务是不是走到终态了（不再有任何后续事件）——主状态终态、且翻译不在进行中。
     *
     * @return 已到终态为 {@code true}
     */
    public boolean settled() {
        return VideoTask.isEventSettled(taskStatus, translationStatus);
    }
}
