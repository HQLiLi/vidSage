package com.vidsage.application.maintenance.query;

import java.time.LocalDateTime;

/**
 * 只读对账的读模型：全量扫工作区目录与两张文件表，把不一致四件事全报。
 *
 * <p><b>零 web 注解</b>——对外形状在 {@code web/dto/ReconcileResponse}，那边做纯映射
 * （判据见 directory-structure.md 的「契约类的位置」判据 2）。
 *
 * @param checkedAt      本次扫描时刻（服务器本地时间）
 * @param summary        扫到的目录数与文件数
 * @param danglingFiles  ① 表 {@code READY} 但文件不在
 * @param reclaimPending ② 表 {@code SUPERSEDED} / {@code DELETED} 但登记的文件还在盘上
 * @param orphanFiles    ③ 盘上有文件、表里没有任何登记、后缀不在白名单
 * @param hashMismatch   ④ 表 {@code READY} 但文件哈希对不上
 */
public record ReconcileReport(
        LocalDateTime checkedAt,
        ReconcileSummary summary,
        ReconcileIssueGroup danglingFiles,
        ReconcileIssueGroup reclaimPending,
        ReconcileIssueGroup orphanFiles,
        ReconcileIssueGroup hashMismatch) {
}
