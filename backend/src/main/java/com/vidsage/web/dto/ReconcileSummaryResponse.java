package com.vidsage.web.dto;

import com.vidsage.application.maintenance.query.ReconcileSummary;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 对账扫到的工作区目录数与普通文件总数。
 *
 * <p>这两个数是<b>给验收用的实测值</b>，不是产品指标。
 *
 * @param dirs  工作区根下的一层子目录数
 * @param files 这些目录下的普通文件总数（含 {@code .tmp} / {@code .part} / {@code .ytdl} 这类过渡态）
 */
@Schema(description = "对账扫到的工作区目录数与普通文件总数（给验收用的实测值，不是产品指标）")
public record ReconcileSummaryResponse(
        @Schema(description = "工作区根下的一层子目录数", example = "3")
        int dirs,
        @Schema(description = "这些目录下的普通文件总数（含过渡态后缀）", example = "23")
        int files) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应。
     *
     * @param summary 应用层读模型
     * @return 对外响应
     */
    public static ReconcileSummaryResponse from(ReconcileSummary summary) {
        return new ReconcileSummaryResponse(summary.dirs(), summary.files());
    }
}
