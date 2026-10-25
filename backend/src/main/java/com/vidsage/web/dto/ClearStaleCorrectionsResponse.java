package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 清理失效纠错的结果（DELETE /api/tasks/{taskNo}/corrections?staleOnly=true）。
 *
 * @param removed 实际删掉的条数
 */
@Schema(description = "清理失效纠错的结果")
public record ClearStaleCorrectionsResponse(
        @Schema(description = "实际删掉的条数", example = "2") int removed) {
}
