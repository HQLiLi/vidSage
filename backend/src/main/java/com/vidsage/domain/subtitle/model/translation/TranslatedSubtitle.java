package com.vidsage.domain.subtitle.model.translation;

import com.vidsage.domain.subtitle.model.cleanup.CleanedSubtitle;
import com.vidsage.domain.subtitle.model.common.SubtitleRoute;
import java.nio.file.Path;

/**
 * 字幕翻译产出描述：两个 -translate 产物路径 + 路线 + 源/目标语种 + 句数 + SRT 字节数 + 统计。
 *
 * <p><b>本类型不直接对外</b>：含 {@code Path} 与枚举，HTTP 响应走 {@code web/dto/} 展平后再返
 * （同 {@link CleanedSubtitle} 的处理），故<b>不加 {@code @Schema}</b>。
 *
 * @param srtPath           双语 SRT 文件路径（{@code subtitles-<route>-translate-<target>.srt}），已原子落盘
 * @param segmentsPath      翻译后 segments JSON 路径（{@code segments-<route>-translate-<target>.json}），是检查点
 * @param route             字幕来源路线
 * @param sourceLanguage    判定跳过时采用的源语种，可能为空串（ASR 与摘要都没给出，此时不跳过）
 * @param targetLanguage    目标语种
 * @param skipped           是否跳过翻译（源语种等于目标语种，未调用 LLM）
 * @param segmentCount      译文段落数，与输入逐条一致
 * @param srtSizeBytes      双语 SRT 文件字节数，可为 0
 * @param retrySteps        本趟翻译触发的校验重试轮数；断点复用时为 0
 * @param untranslatedCount 本趟用尽重试后回退成原文的段数；断点复用时为 0
 */
public record TranslatedSubtitle(Path srtPath, Path segmentsPath, SubtitleRoute route,
                                 String sourceLanguage, String targetLanguage, boolean skipped,
                                 int segmentCount, long srtSizeBytes,
                                 int retrySteps, int untranslatedCount) {
}
