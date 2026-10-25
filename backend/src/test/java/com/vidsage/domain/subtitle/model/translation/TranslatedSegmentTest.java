package com.vidsage.domain.subtitle.model.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TranslatedSegment} 的 id 规则：与原文段同一套时间派生，旧 JSON 缺字段时补上。
 */
class TranslatedSegmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void derivesIdFromTimeWindow() {
        TranslatedSegment segment = new TranslatedSegment(0, 3520, "今天天气不错", "Nice weather today");

        assertEquals("seg-0-3520", segment.id(), "译文段的 id 与原文段同一套规则");
    }

    @Test
    void keepsExplicitId() {
        TranslatedSegment segment = new TranslatedSegment(0, 3520, "今天天气不错", "Nice weather today", "seg-custom");

        assertEquals("seg-custom", segment.id());
    }

    @Test
    void writesIdIntoJson() {
        String json = JSON.writeValueAsString(
                new TranslatedSegment(0, 1440, "欢迎使用。", "Welcome."));

        assertTrue(json.contains("\"id\":\"seg-0-1440\""), "新产物落盘时该带 id，实际：" + json);
    }

    @Test
    void derivesIdWhenDeserializingLegacySegmentWithoutId() {
        TranslatedSegment segment = JSON.readValue("""
                {"beginTimeMs": 0, "endTimeMs": 1440, "sourceText": "欢迎使用。", "translatedText": "Welcome."}
                """, TranslatedSegment.class);

        assertEquals("seg-0-1440", segment.id(), "旧产物没有 id 字段，读出来要按时间戳补上");
        assertEquals("Welcome.", segment.translatedText());
    }

    @Test
    void roundTripsIdThroughJson() {
        TranslatedSegment original = new TranslatedSegment(0, 1440, "你好", "Hello");
        TranslatedSegment read = JSON.readValue(JSON.writeValueAsString(original), TranslatedSegment.class);

        assertEquals(original, read);
    }
}
