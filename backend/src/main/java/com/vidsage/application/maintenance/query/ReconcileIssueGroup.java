package com.vidsage.application.maintenance.query;

import java.util.List;

/**
 * 一类问题的分组：条数 + 明细。
 *
 * <p>四类<b>恒在</b>，空的一类给 {@code count 0} + 空明细，不是 {@code null}、也不省略。
 * {@code count} 与 {@code items} 长度相等——冗余是为了 curl 一眼看数。
 *
 * @param count 本类条数，与 {@code items} 长度相等
 * @param items 本类明细；空类为空列表（不是 {@code null}）
 */
public record ReconcileIssueGroup(int count, List<ReconcileIssue> items) {

    /**
     * 由明细造一个分组，条数取明细长度（保证 {@code count == items.size()}）。
     *
     * @param items 本类明细；{@code null} 视为空
     * @return 条数已对齐的分组
     */
    public static ReconcileIssueGroup of(List<ReconcileIssue> items) {
        List<ReconcileIssue> safeItems = items == null ? List.of() : List.copyOf(items);
        return new ReconcileIssueGroup(safeItems.size(), safeItems);
    }
}
