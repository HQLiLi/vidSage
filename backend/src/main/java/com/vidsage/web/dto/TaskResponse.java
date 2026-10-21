package com.vidsage.web.dto;

import com.vidsage.application.task.query.TaskSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 任务响应：任务提交与查询端点的对外 HTTP 契约。
 *
 * <p>只给「身份 + 最近一次执行」：扁平的 status / currentStage / errorMessage / startedAt /
 * finishedAt 五个字段全部删掉——它们都是从 {@link #lastRun} 派生的量，压平是渲染层的事。
 * 产物清单也删了，归产物端点。
 *
 * <p>SSE 快照形状是 {@code application/task/message/TaskRunProgress}，两者按用途分开。
 *
 * @param taskNo     对外任务号（32 位短 UUID；数据库自增 id 不外泄）
 * @param inputType  输入类型：{@code URL}（链接提交）/ {@code UPLOAD}（本站文件上传）
 * @param sourceUri  来源：URL 原文或上传文件原始名。<b>约束：本字段值不得直接写入日志</b>
 *                   ——含用户原始文件名，属敏感信息
 * @param createdAt  创建时间
 * @param lastRun    最近一次执行（含正在跑的那条）；从未执行时为 {@code null}
 */
@Schema(description = "任务视图")
public record TaskResponse(
        @Schema(description = "对外任务号", example = "3f2a9c1e7b5d4a6089abcdef01234567") String taskNo,
        @Schema(description = "输入类型：URL（链接提交）/ UPLOAD（本站文件上传）") String inputType,
        @Schema(description = "来源：URL 原文或上传文件原始名") String sourceUri,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最近一次执行；从未执行时为 null") RunResponse lastRun) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应，lastRun 空则透传 null。
     *
     * @param snapshot 应用层读模型
     * @return 对外响应
     */
    public static TaskResponse from(TaskSnapshot snapshot) {
        return new TaskResponse(snapshot.taskNo(), snapshot.sourceType().name(), snapshot.sourceUri(),
                snapshot.createdAt(),
                snapshot.lastRun() == null ? null : RunResponse.from(snapshot.lastRun()));
    }
}
