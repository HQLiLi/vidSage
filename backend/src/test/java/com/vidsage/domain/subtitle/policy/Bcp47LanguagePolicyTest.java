package com.vidsage.domain.subtitle.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.transcription.model.TranscriptionSegment;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Bcp47LanguagePolicy 的纯单元测试：语种归一化与源语种推断。
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
    void mainLanguageSubtagComparisonIgnoresRegionAndCase() {
        assertTrue(Bcp47LanguagePolicy.isSameMainLanguage("zh-CN", "zh"));
        assertTrue(Bcp47LanguagePolicy.isSameMainLanguage("zh-Hant", "ZH"));
        assertTrue(Bcp47LanguagePolicy.isSameMainLanguage(" zh ", "zh"));
        assertTrue(Bcp47LanguagePolicy.isSameMainLanguage("zh-tw", "zh-cn"));
        assertFalse(Bcp47LanguagePolicy.isSameMainLanguage("zh", "en"));
        assertFalse(Bcp47LanguagePolicy.isSameMainLanguage("", "zh"));
        assertFalse(Bcp47LanguagePolicy.isSameMainLanguage("zh", "zhx"));
        // 两边都取不到语种：「未知 vs 未知」不是「同一门语言」——判成同一门会导致静默漏译
        assertFalse(Bcp47LanguagePolicy.isSameMainLanguage("", ""));
        assertFalse(Bcp47LanguagePolicy.isSameMainLanguage(null, null));
        assertFalse(Bcp47LanguagePolicy.isSameMainLanguage("  ", null));
    }

    @Test
    void sourceLanguageResolvesFromContextFirstThenTrack() {
        assertEquals("de", Bcp47LanguagePolicy.resolveSourceLanguage(
                new SubtitleContext("主题", "de", List.of()), "zh"), "摘要给的语种优先");
        assertEquals("zh", Bcp47LanguagePolicy.resolveSourceLanguage(
                SubtitleContext.empty(), "zh"), "摘要为空时回退 ASR 语种");
        assertEquals("", Bcp47LanguagePolicy.resolveSourceLanguage(
                SubtitleContext.empty(), null), "两者皆空时为空串");
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
