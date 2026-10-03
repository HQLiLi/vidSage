package com.vidsage.web.dto;

import com.vidsage.model.task.TaskView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 409 冲突响应体：错误说明 + 既有任务视图。
 *
 * <p>是既有 {@code { "error": message }} 形状的<b>有意扩展</b>：冲突的可操作出路是
 * 「改用既有任务」，光有文字说明不够，把视图一并返回，调用方拿 {@code existingTask.taskNo}
 * 即可直接查询 / 订阅进度，不必再发一次查询。
 *
 * @param error        中文错误说明
 * @param existingTask 已存在的进行中任务视图（PENDING / RUNNING）
 */
public record TaskConflictResponse(
        @Schema(description = "错误说明") String error,
        @Schema(description = "既有任务视图，可直接拿它的 taskNo 继续跟进") TaskView existingTask) {
}
