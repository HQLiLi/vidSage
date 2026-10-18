package com.vidsage.application.task.query;

import com.vidsage.domain.workspace.model.SourceType;
import java.time.LocalDateTime;

/**
 * 任务视图：任务的身份，加上最近一次执行。
 *
 * <p>刻意不含数据库自增 {@code id} 与内部的 workspace 行 id：对外只暴露 taskNo。
 * 状态、当前阶段、失败原因、起止时间全部收在 {@link #lastRun} 里，不在本层压平——
 * 压平是渲染层的事（前端要哪一项，从 lastRun 取即可）。
 *
 * @param taskNo     对外任务号（32 位短 UUID；数据库自增 id 不外泄）
 * @param sourceType 来源类型（URL / UPLOAD）
 * @param sourceUri  来源：URL 原文或上传文件原始名。<b>约束：本字段值不得直接写入日志</b>
 *                   ——含用户原始文件名，属敏感信息
 * @param createdAt  创建时间
 * @param lastRun    最近一次执行（含正在跑的那条）；从未执行时为 {@code null}
 */
public record TaskSnapshot(
        String taskNo,
        SourceType sourceType,
        String sourceUri,
        LocalDateTime createdAt,
        RunSummary lastRun) {
}
