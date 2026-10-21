package com.vidsage.domain.subtitle.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.transcription.model.TranscriptionSegment;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Bcp47LanguagePolicy 的纯单元测试：语种归一化与跳过翻译时的照抄。
 *
 * <p>「是不是同一门语言」那套判据已搬到 {@code SupportedLanguage.isSameLanguage}
 * （跳过判据改成枚举相等之后），它的用例在 {@code SupportedLanguageTest}。
 */
class Bcp47LanguagePolicyTest {

    @Test
    void resolveTargetLanguageNormalizes() {
        assertEquals("zh", Bcp47LanguagePolicy.resolveTargetLanguage(" ZH "));
    }

    @Test
    void resolveTargetLanguageBlankThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Bcp47LanguagePolicy.resolveTargetLanguage("  "));
        assertTrue(e.getMessage().contains("vidsage.subtitle.translate.target-language"),
                "消息应指明配置键");
    }

    @Test
    void resolveTargetLanguageNullThrows() {
        assertThrows(IllegalStateException.class,
                () -> Bcp47LanguagePolicy.resolveTargetLanguage(null));
    }

    @Test
    void skipAllCopiesSourceTextAsTranslation() {
        List<TranslatedSegment> skipped = Bcp47LanguagePolicy.skipAll(List.of(
                new TranscriptionSegment(0, 5000, "我防关这边露出"),
                new TranscriptionSegment(5000, 10000, "今天天气不错")));

        assertEquals(2, skipped.size());
        assertEquals(0, skipped.get(0).beginTimeMs(), "时间戳原样带过");
        assertEquals(5000, skipped.get(0).endTimeMs());
        assertEquals("我防关这边露出", skipped.get(0).sourceText());
        for (TranslatedSegment seg : skipped) {
            assertEquals(seg.sourceText(), seg.translatedText(), "跳过时译文应等于原文");
        }
    }
}
