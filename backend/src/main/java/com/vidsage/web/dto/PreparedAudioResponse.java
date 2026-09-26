package com.vidsage.web.dto;

import com.vidsage.model.media.AudioArtifact;
import com.vidsage.model.media.AudioSpec;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 抽音频响应：由 {@link AudioArtifact} <b>展平映射</b>而来——路径转字符串、规格拆成独立字段。
 *
 * <p><b>为什么不直接复用 {@code AudioArtifact}</b>：它含 {@code java.nio.file.Path}（JSON 形状
 * 取决于 Jackson 对 Path 的支持）与嵌套的 {@code AudioSpec} 契约；直接当 HTTP 形状暴露会把
 * 内部模型的演进绑到对外接口上。展平后每个字段形状明确、可独立加 {@code @Schema}，且不必给
 * {@code model/} 的内部契约补 HTTP 注解。
 *
 * @param audioPath       产物音频文件绝对路径
 * @param container       容器类型（WAV / M4A）
 * @param sampleRateHz    采样率（赫兹）
 * @param channels        声道数
 * @param bitrateKbps     码率（kbps）；无损容器（WAV）为 0
 * @param audioTrackIndex 实际取用的音频轨序号（从 0 起）
 * @param sizeBytes       产物字节数，恒 &gt; 0
 */
@Schema(description = "音频抽取产物（绝对路径 + 展平的规格 + 字节数）")
public record PreparedAudioResponse(
        @Schema(description = "产物音频文件绝对路径", example = "F:/vidsage/data/works/media-3f2a1b/audio-16k1ch-t0.wav")
        String audioPath,
        @Schema(description = "容器类型", example = "WAV")
        String container,
        @Schema(description = "采样率（赫兹）", example = "16000")
        int sampleRateHz,
        @Schema(description = "声道数", example = "1")
        int channels,
        @Schema(description = "码率（kbps）；WAV 等无损容器为 0", example = "48")
        int bitrateKbps,
        @Schema(description = "实际取用的音频轨序号（从 0 起）", example = "0")
        int audioTrackIndex,
        @Schema(description = "产物字节数，恒大于 0", example = "1048576")
        long sizeBytes) {

    /**
     * 由 {@link AudioArtifact} 映射：路径转字符串、规格展平。
     *
     * <p>跨包调用，故为 {@code public}（原为 {@code web/controller/} 内的包级可见）。
     *
     * @param artifact 抽取产物
     * @return HTTP 响应体
     */
    public static PreparedAudioResponse from(AudioArtifact artifact) {
        AudioSpec spec = artifact.spec();
        return new PreparedAudioResponse(
                artifact.audioPath().toString(),
                spec.container().name(),
                spec.sampleRateHz(),
                spec.channels(),
                spec.bitrateKbps(),
                spec.audioTrackIndex(),
                artifact.sizeBytes());
    }
}
