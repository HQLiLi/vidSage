package com.vidsage.web.dto;

import com.vidsage.model.subtitle.SubtitleArtifact;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 产字幕响应：由 {@link SubtitleArtifact} <b>展平映射</b>而来——路径转字符串、枚举转名字。
 *
 * <p><b>为什么不直接复用 {@code SubtitleArtifact}</b>：它含 {@code java.nio.file.Path} 与枚举，
 * 直接当 HTTP 形状暴露会把内部模型演进绑到对外接口上。展平后每个字段形状明确、可独立加
 * {@code @Schema}，且不必给 {@code model/subtitle/} 的内部契约补 HTTP 注解。
 *
 * @param route        字幕来源路线（枚举名，如 {@code ASR_CLOUD}）
 * @param language     识别出的语种，可能为 {@code null}
 * @param segmentCount 句数；0 表示空转写（纯音乐/无语音，合法产出）
 * @param srtPath      SRT 字幕文件绝对路径
 * @param segmentsPath 段落 JSON 文件绝对路径
 * @param srtSizeBytes SRT 文件字节数，可为 0（空转写）
 */
@Schema(description = "字幕产物（绝对路径 + 展平的元信息）")
public record PreparedSubtitleResponse(
        @Schema(description = "字幕来源路线", example = "ASR_CLOUD")
        String route,
        @Schema(description = "识别出的语种；可能为 null", example = "zh", nullable = true)
        String language,
        @Schema(description = "句数；0 表示空转写（合法产出）", example = "12")
        int segmentCount,
        @Schema(description = "SRT 字幕文件绝对路径",
                example = "F:/vidsage/data/works/media-3f2a1b/subtitles-asr-cloud.srt")
        String srtPath,
        @Schema(description = "段落 JSON 文件绝对路径",
                example = "F:/vidsage/data/works/media-3f2a1b/segments-asr-cloud.json")
        String segmentsPath,
        @Schema(description = "SRT 文件字节数；空转写为 0", example = "1024")
        long srtSizeBytes) {

    /**
     * 由 {@link SubtitleArtifact} 映射：路径转字符串、枚举转名字。
     *
     * @param artifact 字幕产物
     * @return HTTP 响应体
     */
    public static PreparedSubtitleResponse from(SubtitleArtifact artifact) {
        return new PreparedSubtitleResponse(
                artifact.route().name(),
                artifact.language(),
                artifact.segmentCount(),
                artifact.srtPath().toString(),
                artifact.segmentsPath().toString(),
                artifact.srtSizeBytes());
    }
}