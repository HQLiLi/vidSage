package com.vidsage.web.dto;

import com.vidsage.application.task.query.TaskSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务响应：任务提交与查询端点的对外 HTTP 契约。
 *
 * <p>SSE 快照形状是 {@code application/task/message/TaskProgressMessage}，两者按用途分开。
 *
 * @param taskNo       对外任务号（32 位短 UUID；数据库自增 id 不外泄）
 * @param inputType    输入类型：{@code URL}（链接提交）/ {@code UPLOAD}（本站文件上传）
 * @param sourceUri    来源：URL 原文或上传文件原始名。<b>约束：本字段值不得直接写入日志</b>
 *                     ——含用户原始文件名，属敏感信息
 * @param status       任务状态：{@code PENDING} / {@code RUNNING} / {@code SUCCEEDED} / {@code FAILED}
 * @param currentStage 最MybatisVideoTaskRepository近阶段名（与 StageProgress.stage 同词表）；尚未产生阶段事件时为 {@code null}
 * @param errorMessage 终态错误信息（中文，面向用户；主任务失败或翻译子状态失败）；无错误时为 {@code null}
 * @param createdAt    创建时间
 * @param startedAt    开始执行时间；未开始时为 {@code null}
 * @param finishedAt   到达终态的时间；未到终态时为 {@code null}
 * @param artifacts    产物清单（扫描工作区内已知后缀：*.srt 与 segments-*.json）；
 *                     刚提交尚未产出、工作区目录缺失时为空表，不为 {@code null}
 */
@Schema(description = "任务视图")
public record TaskResponse(
        @Schema(description = "对外任务号", example = "3f2a9c1e7b5d4a6089abcdef01234567") String taskNo,
        @Schema(description = "输入类型：URL（链接提交）/ UPLOAD（本站文件上传）") String inputType,
        @Schema(description = "来源：URL 原文或上传文件原始名") String sourceUri,
        @Schema(description = "任务状态：PENDING（排队中）/ RUNNING（执行中）/ SUCCEEDED（成功）/ FAILED（失败）")
        String status,
        @Schema(description = "最近阶段名；未开始时为 null") String currentStage,
        @Schema(description = "终态错误信息（中文）；无错误时为 null") String errorMessage,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "开始执行时间；未开始时为 null") LocalDateTime startedAt,
        @Schema(description = "终态时间；未到终态时为 null") LocalDateTime finishedAt,
        @Schema(description = "产物清单（*.srt 与 segments-*.json）；尚无产物时为空表") List<TaskArtifactResponse> artifacts) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应，产物清单逐元素映射。
     */
    public static TaskResponse from(TaskSnapshot snapshot) {
        return new TaskResponse(snapshot.taskNo(), snapshot.inputType().name(), snapshot.sourceUri(),
                snapshot.status().name(),
                snapshot.currentStage(), snapshot.errorMessage(),
                snapshot.createdAt(), snapshot.startedAt(), snapshot.finishedAt(),
                snapshot.artifacts().stream().map(TaskArtifactResponse::from).toList());
    }
}
