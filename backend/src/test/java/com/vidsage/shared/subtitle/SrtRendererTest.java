package com.vidsage.shared.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vidsage.domain.transcription.TranscriptionSegment;
import com.vidsage.domain.subtitle.TranslatedSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SrtRenderer 的纯单测：时间戳格式化与整份 SRT 渲染，直接调静态方法，不起 Spring 容器。
 */
class SrtRendererTest {

    @Test
    void formatsSubMillisecondTimes() {
        assertEquals("00:00:00,000", SrtRenderer.formatTimestamp(0));
        assertEquals("00:00:00,999", SrtRenderer.formatTimestamp(999));
        assertEquals("00:00:01,000", SrtRenderer.formatTimestamp(1000));
    }

    @Test
    void formatsTimesBeyondOneHour() {
        assertEquals("01:00:00,000", SrtRenderer.formatTimestamp(3_600_000));
        assertEquals("01:01:01,001", SrtRenderer.formatTimestamp(3_661_001));
        // 超过 24 小时自然进位进小时位（不绕回天）
        assertEquals("25:00:00,000", SrtRenderer.formatTimestamp(90_000_000));
    }

    @Test
    void clampsNegativeTimestampsToZero() {
        assertEquals("00:00:00,000", SrtRenderer.formatTimestamp(-1));
        assertEquals("00:00:00,000", SrtRenderer.formatTimestamp(-999_999));
    }

    @Test
    void rendersEmptySegmentsAsEmptyString() {
        assertEquals("", SrtRenderer.toSrt(List.of()));
    }

    @Test
    void rendersBlocksWithSequenceAndBlankLines() {
        var result = SrtRenderer.toSrt(List.of(
                new TranscriptionSegment(0, 1440, "欢迎使用阿里云。"),
                new TranscriptionSegment(1440, 3000, "今天天气不错。")));

        String expected = """
                1
                00:00:00,000 --> 00:00:01,440
                欢迎使用阿里云。

                2
                00:00:01,440 --> 00:00:03,000
                今天天气不错。

                """;
        assertEquals(expected, result);
    }

    @Test
    void rendersEmptyTranslatedSegmentsAsEmptyString() {
        assertEquals("", SrtRenderer.toBilingualSrt(List.of()));
    }

    @Test
    void rendersBilingualBlocksWithSourceThenTranslation() {
        var result = SrtRenderer.toBilingualSrt(List.of(
                new TranslatedSegment(0, 1440, "欢迎使用。", "Welcome."),
                new TranslatedSegment(1440, 3000, "今天天气不错。", "Nice weather today.")));

        String expected = """
                1
                00:00:00,000 --> 00:00:01,440
                欢迎使用。
                Welcome.

                2
                00:00:01,440 --> 00:00:03,000
                今天天气不错。
                Nice weather today.

                """;
        assertEquals(expected, result);
    }

    @Test
    void rendersSingleLineWhenTranslationEqualsSource() {
        // 跳过的产物（源语种==目标语种）译文逐条等于原文，不应出现两行一模一样的字幕
        var result = SrtRenderer.toBilingualSrt(List.of(
                new TranslatedSegment(0, 1440, "hello", "hello"),
                new TranslatedSegment(1440, 3000, "欢迎使用。", "Welcome.")));

        String expected = """
                1
                00:00:00,000 --> 00:00:01,440
                hello

                2
                00:00:01,440 --> 00:00:03,000
                欢迎使用。
                Welcome.

                """;
        assertEquals(expected, result);
    }

    @Test
    void treatsNullTextAsEmptyAndCollapsesEqualNullAndEmptyLines() {
        // null 与空串按同一行处理（同 toSrt 对 null 文本的处理），相等时也只渲染一行
        var result = SrtRenderer.toBilingualSrt(List.of(
                new TranslatedSegment(0, 1000, null, null)));

        assertEquals("1\n00:00:00,000 --> 00:00:01,000\n\n\n", result);
    }
}
