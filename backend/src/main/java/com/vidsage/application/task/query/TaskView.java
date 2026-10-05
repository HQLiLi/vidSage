package com.vidsage.application.task.query;

import com.vidsage.domain.task.TaskInputType;
import com.vidsage.domain.task.TaskStatus;
import com.vidsage.domain.task.TranslationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务视图：任务提交与查询的 HTTP 响应契约（JSON 形状即可直接对外，故按 directory-structure.md
 * 「契约类的位置」判据 1 落在 application/task/query/ 并补 @Schema）。
 *
 * <p>刻意不含数据库自增 {@code id} 与 workspace_key / workspace_dir 等内部字段——对外只暴露 taskNo；
 * SSE 快照形状是 {@code application/task/TaskProgressMessage}，两者按用途分开。
 *
 * @param taskNo            对外任务号（32 位短 UUID；数据库自增 id 不外泄）
 * @param inputType         输入类型
 * @param sourceUri         来源：URL 原文或上传文件原始名（仅随响应返回给提交方，日志不落原值）
 * @param status            任务状态
 * @param translationStatus 翻译子状态（2026-10-02 两段式：翻译按需触发）；{@code null} = 从未触发
 * @param currentStage      最近阶段名（与 StageEvent.stage 同词表）；尚未产生阶段事件时为 {@code null}
 * @param errorMessage      终态错误信息（中文，面向用户；主任务失败或翻译子状态失败）；无错误时为 {@code null}
 * @param createdAt         创建时间
 * @param startedAt         开始执行时间；未开始时为 {@code null}
 * @param finishedAt        到达终态的时间；未到终态时为 {@code null}
 * @param artifacts         产物清单（扫描工作区内已知后缀：*.srt 与 segments-*.json）；
 *                          刚提交尚未产出、工作区目录缺失时为空表，不为 {@code null}
 */
@Schema(description = "任务视图")
public record TaskView(
        @Schema(description = "对外任务号", example = "3f2a9c1e7b5d4a6089abcdef01234567") String taskNo,
        @Schema(description = "输入类型") TaskInputType inputType,
        @Schema(description = "来源：URL 原文或上传文件原始名") String sourceUri,
        @Schema(description = "任务状态") TaskStatus status,
        @Schema(description = "翻译子状态；null 表示从未触发（主任务成功后按需触发翻译）")
        TranslationStatus translationStatus,
        @Schema(description = "最近阶段名；未开始时为 null") String currentStage,
        @Schema(description = "终态错误信息（中文）；无错误时为 null") String errorMessage,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "开始执行时间；未开始时为 null") LocalDateTime startedAt,
        @Schema(description = "终态时间；未到终态时为 null") LocalDateTime finishedAt,
        @Schema(description = "产物清单（*.srt 与 segments-*.json）；尚无产物时为空表") List<TaskArtifact> artifacts) {
}
