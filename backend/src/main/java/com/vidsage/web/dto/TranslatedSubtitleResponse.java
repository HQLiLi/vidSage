package com.vidsage.web.dto;

import com.vidsage.model.subtitle.TranslatedSubtitle;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 字幕翻译响应：由 {@link TranslatedSubtitle} <b>展平映射</b>而来——路径转字符串、枚举转名字。
 *
 * <p>与 {@code CleanedSubtitleResponse} 同判据：{@code TranslatedSubtitle} 含 {@code Path} 与枚举，
 * 直接当 HTTP 形状会把内部模型演进绑到对外接口上，故在这里展平。
 *
 * @param route             字幕来源路线（枚举名，如 {@code ASR_CLOUD}）
 * @param sourceLanguage    判定跳过时采用的源语种；可能为空串（摘要与 ASR 都没给出，此时不跳过）
 * @param targetLanguage    目标语种（来自配置）
 * @param skipped           是否跳过翻译（源语种等于目标语种，未调用 LLM）
 * @param segmentCount      译文段落数，与输入逐条一致
 * @param srtPath           双语 SRT 文件绝对路径
 * @param segmentsPath      翻译后 segments JSON 文件绝对路径（检查点）
 * @param srtSizeBytes      双语 SRT 文件字节数，可为 0
 * @param retrySteps        本趟翻译触发的校验重试轮数；断点复用时为 0
 * @param untranslatedCount 本趟用尽重试后回退成原文的段数；断点复用时为 0
 */
@Schema(description = "字幕翻译产物（绝对路径 + 展平的元信息与翻译统计）")
public record TranslatedSubtitleResponse(
        @Schema(description = "字幕来源路线", example = "ASR_CLOUD")
        String route,
        @Schema(description = "判定跳过时采用的源语种；可能为空串（源语种未知，此时不跳过）", example = "zh")
        String sourceLanguage,
        @Schema(description = "目标语种（BCP-47 短码，来自配置）", example = "zh")
        String targetLanguage,
        @Schema(description = "是否跳过翻译（源语种等于目标语种，未调用 LLM）", example = "false")
        boolean skipped,
        @Schema(description = "译文段落数，与输入逐条一致", example = "12")
        int segmentCount,
        @Schema(description = "双语 SRT 字幕文件绝对路径",
                example = "F:/vidsage/data/works/media-3f2a1b/subtitles-asr-cloud-translate-zh.srt")
        String srtPath,
        @Schema(description = "翻译后段落 JSON 文件绝对路径（检查点）",
                example = "F:/vidsage/data/works/media-3f2a1b/segments-asr-cloud-translate-zh.json")
        String segmentsPath,
        @Schema(description = "双语 SRT 文件字节数；空为 0", example = "2048")
        long srtSizeBytes,
        @Schema(description = "本趟翻译触发的校验重试轮数；断点复用时为 0", example = "0")
        int retrySteps,
        @Schema(description = "本趟用尽重试后回退成原文的段数；断点复用时为 0", example = "0")
        int untranslatedCount) {

    /**
     * 由 {@link TranslatedSubtitle} 映射：路径转字符串、枚举转名字。
     *
     * @param translated 字幕翻译产物
     * @return HTTP 响应体
     */
    public static TranslatedSubtitleResponse from(TranslatedSubtitle translated) {
        return new TranslatedSubtitleResponse(
                translated.route().name(),
                translated.sourceLanguage(),
                translated.targetLanguage(),
                translated.skipped(),
                translated.segmentCount(),
                translated.srtPath().toString(),
                translated.segmentsPath().toString(),
                translated.srtSizeBytes(),
                translated.retrySteps(),
                translated.untranslatedCount());
    }
}
