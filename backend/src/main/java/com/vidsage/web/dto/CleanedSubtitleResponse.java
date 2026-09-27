package com.vidsage.web.dto;

import com.vidsage.model.subtitle.CleanedSubtitle;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 字幕清洗响应：由 {@link CleanedSubtitle} <b>展平映射</b>而来——路径转字符串、枚举转名字。
 *
 * <p>与 {@code PreparedSubtitleResponse} 同判据：{@code CleanedSubtitle} 含 {@code Path} 与枚举，
 * 直接当 HTTP 形状会把内部模型演进绑到对外接口上，故在这里展平。
 *
 * @param route          字幕来源路线（枚举名，如 {@code ASR_CLOUD}）
 * @param language       识别出的语种，可能为 {@code null}
 * @param segmentCount   清洗后句数（可能因切分大于原始句数，因空段丢弃更少）
 * @param srtPath        清洗后 SRT 文件绝对路径
 * @param segmentsPath   清洗后 segments JSON 文件绝对路径（检查点）
 * @param srtSizeBytes   清洗后 SRT 文件字节数，可为 0
 * @param correctedCount 本趟纠错实际改变文本的段数；断点复用时为 0
 * @param retrySteps     本趟纠错触发的校验重试轮数；断点复用时为 0
 */
@Schema(description = "字幕清洗产物（绝对路径 + 展平的元信息与纠错统计）")
public record CleanedSubtitleResponse(
        @Schema(description = "字幕来源路线", example = "ASR_CLOUD")
        String route,
        @Schema(description = "识别出的语种；可能为 null", example = "zh", nullable = true)
        String language,
        @Schema(description = "清洗后句数", example = "12")
        int segmentCount,
        @Schema(description = "清洗后 SRT 字幕文件绝对路径",
                example = "F:/vidsage/data/works/media-3f2a1b/subtitles-asr-cloud-clean.srt")
        String srtPath,
        @Schema(description = "清洗后段落 JSON 文件绝对路径",
                example = "F:/vidsage/data/works/media-3f2a1b/segments-asr-cloud-clean.json")
        String segmentsPath,
        @Schema(description = "清洗后 SRT 文件字节数；空为 0", example = "2048")
        long srtSizeBytes,
        @Schema(description = "本趟纠错改变文本的段数；断点复用时为 0", example = "3")
        int correctedCount,
        @Schema(description = "本趟纠错触发的校验重试轮数；断点复用时为 0", example = "0")
        int retrySteps) {

    /**
     * 由 {@link CleanedSubtitle} 映射：路径转字符串、枚举转名字。
     *
     * @param cleaned 字幕清洗产物
     * @return HTTP 响应体
     */
    public static CleanedSubtitleResponse from(CleanedSubtitle cleaned) {
        return new CleanedSubtitleResponse(
                cleaned.route().name(),
                cleaned.language(),
                cleaned.segmentCount(),
                cleaned.srtPath().toString(),
                cleaned.segmentsPath().toString(),
                cleaned.srtSizeBytes(),
                cleaned.correctedCount(),
                cleaned.retrySteps());
    }
}