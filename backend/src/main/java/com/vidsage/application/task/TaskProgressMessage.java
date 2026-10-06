package com.vidsage.application.task;

import com.vidsage.domain.shared.StageEvent;
import com.vidsage.domain.task.TaskStatus;
import com.vidsage.domain.task.TranslationStatus;
import com.vidsage.domain.task.VideoTask;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务进度事件：SSE payload 的定锤形态（design.md「SSE 契约」）——
 * {@code GET /api/tasks/{taskNo}/events} 以 {@code event: progress} 推送，data 即本类型的 JSON。
 *
 * <p>任务级状态 + 最近阶段事件的组合：订阅方先靠 {@code taskStatus} 判任务整体走到哪了，
 * 再用 {@code stage} 渲染阶段内进度。终态（SUCCEEDED / FAILED）事件是最后一条——推完即完成
 * SSE 连接，故终态事件允许不带阶段细节（{@code stage} 为 {@code null}，状态本身就是信息）。
 *
 * <p><b>translationStatus（2026-10-02 两段式新增）</b>：翻译按需触发后其事件仍走同一事件流，
 * 但翻译事件的 taskStatus 恒为主状态 SUCCEEDED——若不携带翻译子状态，终态判定
 * （「主状态终态且翻译非 RUNNING」才收口连接，见 {@code TaskSseHub} / 订阅端点）会在第一条翻译
 * 进度事件后把连接收掉。主管线事件的 translationStatus 为 {@code null}，快照按翻译子状态粗粒度还原。
 *
 * <p>形状直接对外，按 directory-structure.md「契约类的位置」判据 1 落在 application/task/ 并补 @Schema。
 * {@link StageEvent} 本体保持最小不动（其 Javadoc 声明的「过渡形态」由本类型收口）。
 *
 * @param taskNo            对外任务号，与订阅端点的 taskNo 一致
 * @param taskStatus        事件发出时的任务主状态
 * @param stage             最近阶段事件；终态广播可无阶段细节（为 {@code null}），订阅端建立连接前若任务
 *                          尚未产生任何阶段事件（current_stage 为空）同样为 {@code null}
 * @param translationStatus 翻译子状态；从未触发（含全部主管线事件）时为 {@code null}，
 *                          翻译事件携带当前子状态（RUNNING / SUCCEEDED / FAILED）
 */
@Schema(description = "任务进度事件（SSE 事件名 progress 的 data）")
public record TaskProgressMessage(
        @Schema(description = "对外任务号", example = "3f2a9c1e7b5d4a6089abcdef01234567") String taskNo,
        @Schema(description = "事件发出时的任务主状态") TaskStatus taskStatus,
        @Schema(description = "最近阶段事件；终态广播或尚无阶段事件时为 null") StageEvent stage,
        @Schema(description = "翻译子状态；从未触发时为 null（翻译按需触发，事件自成 0..100 段）")
        TranslationStatus translationStatus) {

    /**
     * 任务是不是走到终态了（不再有任何后续事件）——主状态终态、且翻译不在进行中。
     *
     * <p>判据单源在 {@link VideoTask#isEventSettled}，这里只是转个手：web 层（SSE 收口判定）
     * 因此不必认识 {@code VideoTask} 等领域类型。
     *
     * <p><b>方法名刻意不用 {@code isSettled} 这种 getter 形状</b>——本 record 直接序列化成 SSE 的
     * data，Jackson 会把 {@code isXxx()} 当成属性写进 JSON，凭空多出一个字段，那是契约变更。
     *
     * @return 已到终态为 {@code true}
     */
    public boolean settled() {
        return VideoTask.isEventSettled(taskStatus, translationStatus);
    }
}
