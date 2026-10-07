package com.vidsage.shared.subtitle;

import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import java.util.List;
import java.util.Locale;

/**
 * 把转写结果拼成 SRT 文本：时间戳格式化 + 整份 SRT 渲染。
 *
 * <p>全静态、无状态，构造器私有，不要 new 它。
 */
public final class SrtRenderer {

    private SrtRenderer() {
    }

    /**
     * 毫秒 → SRT 时间戳 {@code HH:MM:SS,mmm}（逗号分隔毫秒，SRT 规范形式）。
     *
     * <p>超过一天的毫秒数会自然进位进「小时」位（{@code 90000000 → 25:00:00,000}），符合 SRT
     * 对长时长的处理。负值钳到 {@code 00:00:00,000}（异常段落防御，见 research/asr-cloud-behavior.md
     * §三；DashScope 正常返回不产生，但钳掉比传出负时间戳稳）。
     */
    public static String formatTimestamp(long millis) {
        long safe = Math.max(millis, 0L);
        long hours = safe / 3_600_000L;
        long minutes = (safe % 3_600_000L) / 60_000L;
        long seconds = (safe % 60_000L) / 1_000L;
        long ms = safe % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, ms);
    }

    /**
     * 渲染整份 SRT（design 决策 7：自己写，不引库）。序号从 1 起，块间空一行，{@code \n} 换行。
     * 句数为 0 时返回空字符串（产出 0 字节文件）。
     */
    public static String toSrt(List<TranscriptionSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            TranscriptionSegment s = segments.get(i);
            sb.append(i + 1).append('\n')
                    .append(formatTimestamp(s.beginTimeMs())).append(" --> ")
                    .append(formatTimestamp(s.endTimeMs())).append('\n')
                    .append(s.text() == null ? "" : s.text()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 渲染整份双语 SRT：每条上行原文、下行译文，其余格式（序号、时间轴、空行、{@code \n}）同
     * {@link #toSrt(List)}。句数为 0 时返回空字符串。
     *
     * <p><b>译文与原文相同时该条只渲染一行</b>（不把同一句话写两遍）：跳过的产物（源语种等于目标
     * 语种）因此不会出现两行一模一样的字幕，「跳过」在 SRT 里自然可读，不需要额外标记。判据是
     * 两侧都按空串处理后再比（同 {@code toSrt} 对 {@code null} 文本的处理），故 {@code null} 与空串
     * 视为同一行。
     */
    public static String toBilingualSrt(List<TranslatedSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            TranslatedSegment s = segments.get(i);
            String source = s.sourceText() == null ? "" : s.sourceText();
            String translated = s.translatedText() == null ? "" : s.translatedText();
            sb.append(i + 1).append('\n')
                    .append(formatTimestamp(s.beginTimeMs())).append(" --> ")
                    .append(formatTimestamp(s.endTimeMs())).append('\n')
                    .append(source).append('\n');
            if (!translated.equals(source)) {
                sb.append(translated).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
