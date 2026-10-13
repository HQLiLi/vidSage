package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 409 冲突响应体：错误说明 + 既有任务响应。
 *
 * @param error        中文错误说明
 * @param existingTask 已存在的进行中任务响应（PENDING / RUNNING）
 */
public record TaskConflictResponse(
        @Schema(description = "错误说明") String error,
        @Schema(description = "既有任务视图，可直接拿它的 taskNo 继续跟进") TaskResponse existingTask) {
}
