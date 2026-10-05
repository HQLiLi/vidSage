package com.vidsage.application.task.query;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务产物文件（清单项）：{@code GET /api/tasks/{taskNo}} 响应里 artifacts 列表的元素。
 *
 * <p>JSON 形状直接对外（判据 1），落在 application/task/query/ 并补 @Schema。只暴露文件名与字节数；
 * 工作区目录、绝对路径等内部信息不出境——文件名可直接拼产物下载端点
 * {@code GET /api/tasks/{taskNo}/artifacts/{fileName}} 下载。
 *
 * @param name      产物文件名（不含路径）
 * @param sizeBytes 文件字节数
 */
@Schema(description = "任务产物文件（清单项）")
public record TaskArtifact(
        @Schema(description = "产物文件名（不含路径），可直接用于产物下载端点",
                example = "subtitles-asr-cloud-translate-ja.srt") String name,
        @Schema(description = "文件字节数", example = "10240") long sizeBytes) {
}
