package com.vidsage.web.dto;

import com.vidsage.application.task.query.TaskArtifact;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务产物文件响应：{@link TaskResponse} artifacts 清单的元素。
 *
 * @param name      产物文件名（不含路径）；可直接拼产物下载端点
 *                  {@code GET /api/tasks/{taskNo}/artifacts/{fileName}} 下载
 * @param sizeBytes 文件字节数
 */
@Schema(description = "任务产物文件（清单项）")
public record TaskArtifactResponse(
        @Schema(description = "产物文件名（不含路径），可直接用于产物下载端点",
                example = "original-subtitles.srt") String name,
        @Schema(description = "文件字节数", example = "10240") long sizeBytes) {

    /** 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应、直接透传。 */
    public static TaskArtifactResponse from(TaskArtifact artifact) {
        return new TaskArtifactResponse(artifact.name(), artifact.sizeBytes());
    }
}
