package com.vidsage.domain.subtitle.model.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SubtitleCorrectionField} 的词形转换：对外 {@code text} / {@code translation} 与枚举
 * 两个方向都只在这一处定义（{@code parse} / {@code token}）。
 */
class SubtitleCorrectionFieldTest {

    @Test
    void parsesTokensIgnoringCaseAndSurroundingBlanks() {
        assertEquals(SubtitleCorrectionField.TEXT, SubtitleCorrectionField.parse(" text "));
        assertEquals(SubtitleCorrectionField.TRANSLATION, SubtitleCorrectionField.parse("Translation"));
    }

    @Test
    void tokenAndParseRoundTrip() {
        for (SubtitleCorrectionField field : SubtitleCorrectionField.values()) {
            assertEquals(field, SubtitleCorrectionField.parse(field.token()), "parse 与 token 必须互逆");
        }
        assertEquals("text", SubtitleCorrectionField.TEXT.token());
        assertEquals("translation", SubtitleCorrectionField.TRANSLATION.token());
    }

    @Test
    void rejectsBlankToken() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SubtitleCorrectionField.parse("  "));

        assertTrue(e.getMessage().contains("text"), "报错要告诉调用方该传什么");
    }

    @Test
    void rejectsUnknownToken() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SubtitleCorrectionField.parse("subtitle"));

        assertTrue(e.getMessage().contains("subtitle"), "报错要带上传错的原值");
        assertTrue(e.getMessage().contains("translation"));
    }
}
