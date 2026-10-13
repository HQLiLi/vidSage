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
 * Bcp47Language 的纯单元测试：语种归一化与源语种推断。
 */
class Bcp47LanguageTest {

    @Test
    void resolveTargetLanguageNormalizes() {
        assertEquals("zh", Bcp47Language.resolveTargetLanguage(" ZH "));
    }

    @Test
    void resolveTargetLanguageBlankThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Bcp47Language.resolveTargetLanguage("  "));
        assertTrue(e.getMessage().contains("vidsage.subtitle.translate.target-language"),
                "消息应指明配置键");
    }

    @Test
    void resolveTargetLanguageNullThrows() {
        assertThrows(IllegalStateException.class,
                () -> Bcp47Language.resolveTargetLanguage(null));
    }

    @Test
    void mainLanguageSubtagComparisonIgnoresRegionAndCase() {
        assertTrue(Bcp47Language.isSameMainLanguage("zh-CN", "zh"));
        assertTrue(Bcp47Language.isSameMainLanguage("zh-Hant", "ZH"));
        assertTrue(Bcp47Language.isSameMainLanguage(" zh ", "zh"));
        assertTrue(Bcp47Language.isSameMainLanguage("zh-tw", "zh-cn"));
        assertFalse(Bcp47Language.isSameMainLanguage("zh", "en"));
        assertFalse(Bcp47Language.isSameMainLanguage("", "zh"));
        assertFalse(Bcp47Language.isSameMainLanguage("zh", "zhx"));
        // 两边都取不到语种：「未知 vs 未知」不是「同一门语言」——判成同一门会导致静默漏译
        assertFalse(Bcp47Language.isSameMainLanguage("", ""));
        assertFalse(Bcp47Language.isSameMainLanguage(null, null));
        assertFalse(Bcp47Language.isSameMainLanguage("  ", null));
    }

    @Test
    void sourceLanguageResolvesFromContextFirstThenTrack() {
        assertEquals("de", Bcp47Language.resolveSourceLanguage(
                new SubtitleContext("主题", "de", List.of()), "zh"), "摘要给的语种优先");
        assertEquals("zh", Bcp47Language.resolveSourceLanguage(
                SubtitleContext.empty(), "zh"), "摘要为空时回退 ASR 语种");
        assertEquals("", Bcp47Language.resolveSourceLanguage(
                SubtitleContext.empty(), null), "两者皆空时为空串");
    }

    @Test
    void skipAllCopiesSourceTextAsTranslation() {
        List<TranslatedSegment> skipped = Bcp47Language.skipAll(List.of(
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
