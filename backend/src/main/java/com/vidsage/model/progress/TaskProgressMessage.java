package com.vidsage.model.progress;

import com.vidsage.model.task.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务进度事件：SSE payload 的定锤形态（design.md「SSE 契约」）——
 * {@code GET /api/tasks/{taskNo}/events} 以 {@code event: progress} 推送，data 即本类型的 JSON。
 *
 * <p>任务级状态 + 最近阶段事件的组合：订阅方先靠 {@code taskStatus} 判任务整体走到哪了，
 * 再用 {@code stage} 渲染阶段内进度。终态（SUCCEEDED / FAILED）事件是最后一条——推完即完成
 * SSE 连接，故终态事件允许不带阶段细节（{@code stage} 为 {@code null}，状态本身就是信息）。
 *
 * <p>形状直接对外，按 directory-structure.md「契约类的位置」判据 1 落在 model/ 并补 @Schema。
 * {@link StageEvent} 本体保持最小不动（其 Javadoc 声明的「过渡形态」由本类型收口）。
 *
 * @param taskNo     对外任务号，与订阅端点的 taskNo 一致
 * @param taskStatus 事件发出时的任务状态
 * @param stage      最近阶段事件；终态广播可无阶段细节（为 {@code null}），订阅端建立连接前若任务
 *                   尚未产生任何阶段事件（current_stage 为空）同样为 {@code null}
 */
@Schema(description = "任务进度事件（SSE 事件名 progress 的 data）")
public record TaskProgressMessage(
        @Schema(description = "对外任务号", example = "3f2a9c1e7b5d4a6089abcdef01234567") String taskNo,
        @Schema(description = "事件发出时的任务状态") TaskStatus taskStatus,
        @Schema(description = "最近阶段事件；终态广播或尚无阶段事件时为 null") StageEvent stage) {
}
