package com.vidsage.utils.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vidsage.model.asr.TranscriptionSegment;
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
}
