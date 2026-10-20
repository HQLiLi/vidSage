package com.vidsage.web.dto;

import com.vidsage.application.maintenance.query.ReconcileReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 只读对账的对外响应：全量扫工作区目录与两张文件表，把不一致四件事全报（只报不修）。
 *
 * <p>四类<b>恒在</b>，空的一类给 {@code {"count": 0, "items": []}}。形状直接对外
 * （{@code MaintenanceController} 就返回它）。
 *
 * @param checkedAt      本次扫描时刻（服务器本地时间）
 * @param summary        扫到的目录数与文件数
 * @param danglingFiles  ① 表 {@code READY} 但文件不在
 * @param reclaimPending ② 表 {@code SUPERSEDED} / {@code DELETED} 但登记的文件还在盘上
 * @param orphanFiles    ③ 盘上有文件、表里没有任何登记、后缀不在白名单
 * @param hashMismatch   ④ 表 {@code READY} 但文件哈希对不上
 */
@Schema(description = "只读对账报告：全量扫工作区目录与两张文件表，把不一致四件事全报（只报不修）")
public record ReconcileResponse(
        @Schema(description = "本次扫描时刻（服务器本地时间）")
        LocalDateTime checkedAt,
        @Schema(description = "扫到的目录数与文件数")
        ReconcileSummaryResponse summary,
        @Schema(description = "① 表 READY 但文件不在")
        ReconcileIssueGroupResponse danglingFiles,
        @Schema(description = "② 表 SUPERSEDED / DELETED 但登记的文件还在盘上")
        ReconcileIssueGroupResponse reclaimPending,
        @Schema(description = "③ 盘上有文件、表里没有任何登记、后缀不在白名单")
        ReconcileIssueGroupResponse orphanFiles,
        @Schema(description = "④ 表 READY 但文件哈希对不上")
        ReconcileIssueGroupResponse hashMismatch) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应，四类逐个映射。
     *
     * @param report 应用层读模型
     * @return 对外响应
     */
    public static ReconcileResponse from(ReconcileReport report) {
        return new ReconcileResponse(report.checkedAt(), ReconcileSummaryResponse.from(report.summary()),
                ReconcileIssueGroupResponse.from(report.danglingFiles()),
                ReconcileIssueGroupResponse.from(report.reclaimPending()),
                ReconcileIssueGroupResponse.from(report.orphanFiles()),
                ReconcileIssueGroupResponse.from(report.hashMismatch()));
    }
}
