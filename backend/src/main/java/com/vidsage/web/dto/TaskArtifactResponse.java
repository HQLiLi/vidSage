package com.vidsage.web.dto;

import com.vidsage.application.task.query.TaskArtifact;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务产物文件响应：{@link TaskResponse} artifacts 清单的元素（2026-10-06 读模型拆分，
 * directory-structure.md「契约类的位置」判据 2——对外响应类命名 {@code XXResponse}、
 * 落 {@code web/dto/}、@Schema 集中于此）。
 *
 * <p>契约注解自拆分前 application 层的产物清单项类逐条迁入、注解参数未改写；应用层读模型
 * {@link TaskArtifact} 零 web 注解，经 {@link #from(TaskArtifact)} 纯映射——
 * JSON 字段名 / 类型与拆分前逐一一致，前端零感知。
 *
 * @param name      产物文件名（不含路径）；可直接拼产物下载端点
 *                  {@code GET /api/tasks/{taskNo}/artifacts/{fileName}} 下载
 * @param sizeBytes 文件字节数
 */
@Schema(description = "任务产物文件（清单项）")
public record TaskArtifactResponse(
        @Schema(description = "产物文件名（不含路径），可直接用于产物下载端点",
                example = "subtitles-asr-cloud-translate-ja.srt") String name,
        @Schema(description = "文件字节数", example = "10240") long sizeBytes) {

    /** 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应、直接透传。 */
    public static TaskArtifactResponse from(TaskArtifact artifact) {
        return new TaskArtifactResponse(artifact.name(), artifact.sizeBytes());
    }
}
