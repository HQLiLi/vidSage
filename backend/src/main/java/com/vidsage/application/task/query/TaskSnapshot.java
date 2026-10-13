package com.vidsage.application.task.query;

import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务快照：任务提交与查询的对外读模型。
 *
 * <p>刻意不含数据库自增 {@code id} 与 workspace_key / workspace_dir 等内部字段，
 * 对外只暴露 taskNo。
 *
 * @param taskNo            对外任务号（32 位短 UUID；数据库自增 id 不外泄）
 * @param inputType         输入类型
 * @param sourceUri         来源：URL 原文或上传文件原始名。<b>约束：本字段值不得直接写入日志</b>
 *                          ——含用户原始文件名，属敏感信息
 * @param status            任务状态
 * @param translationStatus 翻译子状态（翻译按需触发）；{@code null} = 从未触发
 * @param currentStage      最近阶段名（与 StageProgress.stage 同词表）；尚未产生阶段事件时为 {@code null}
 * @param errorMessage      终态错误信息（中文，面向用户；主任务失败或翻译子状态失败）；无错误时为 {@code null}
 * @param createdAt         创建时间
 * @param startedAt         开始执行时间；未开始时为 {@code null}
 * @param finishedAt        到达终态的时间；未到终态时为 {@code null}
 * @param artifacts         产物清单（扫描工作区内已知后缀：*.srt 与 segments-*.json）；
 *                          刚提交尚未产出、工作区目录缺失时为空表，不为 {@code null}
 */
public record TaskSnapshot(
        String taskNo,
        TaskInputType inputType,
        String sourceUri,
        TaskStatus status,
        TranslationStatus translationStatus,
        String currentStage,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<TaskArtifact> artifacts) {
}
