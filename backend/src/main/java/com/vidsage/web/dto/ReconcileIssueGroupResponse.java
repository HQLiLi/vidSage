package com.vidsage.web.dto;

import com.vidsage.application.maintenance.query.ReconcileIssueGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 一类对账问题的分组：条数 + 明细。
 *
 * <p>{@code count} 与 {@code items.length} 相等——冗余是为了 curl 一眼看数。
 *
 * @param count 本类条数，与 {@code items} 长度相等
 * @param items 本类明细；空类为空数组（不是 {@code null}）
 */
@Schema(description = "一类对账问题的分组：条数 + 明细")
public record ReconcileIssueGroupResponse(
        @Schema(description = "本类条数，与 items 长度相等", example = "1")
        int count,
        @Schema(description = "本类明细；空类为空数组（不是 null）")
        List<ReconcileIssueResponse> items) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）。
     *
     * @param group 应用层读模型
     * @return 对外响应
     */
    public static ReconcileIssueGroupResponse from(ReconcileIssueGroup group) {
        return new ReconcileIssueGroupResponse(group.count(),
                group.items().stream().map(ReconcileIssueResponse::from).toList());
    }
}
