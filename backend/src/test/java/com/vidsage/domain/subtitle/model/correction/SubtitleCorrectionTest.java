package com.vidsage.domain.subtitle.model.correction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SubtitleCorrection} 紧凑构造器的形状约束：唯一键里的 lang 不能是 null / 不能带错，
 * 源文本按列约束收敛成空串。
 */
class SubtitleCorrectionTest {

    private static final long TASK_ID = 7L;

    @Test
    void normalizesNullLangToEmptyStringForTextCorrection() {
        SubtitleCorrection correction = new SubtitleCorrection(null, TASK_ID, "seg-0-1440",
                SubtitleCorrectionField.TEXT, null, "改前原文", "改后原文", null, null);

        assertEquals("", correction.lang(),
                "原文纠错没有语种维度，null 必须收敛成空串——唯一约束不比较 NULL，否则能插两条");
    }

    @Test
    void dropsLangSuppliedToTextCorrection() {
        SubtitleCorrection correction = new SubtitleCorrection(null, TASK_ID, "seg-0-1440",
                SubtitleCorrectionField.TEXT, "en", "改前原文", "改后原文", null, null);

        assertEquals("", correction.lang(), "原文纠错带什么 lang 都不算数，存空串才和删除路径对得上");
    }

    @Test
    void keepsLangForTranslationCorrection() {
        SubtitleCorrection correction = new SubtitleCorrection(null, TASK_ID, "seg-0-1440",
                SubtitleCorrectionField.TRANSLATION, "en", "原文", "译文", null, null);

        assertEquals("en", correction.lang());
    }

    @Test
    void rejectsTranslationCorrectionWithoutLang() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SubtitleCorrection(null, TASK_ID, "seg-0-1440",
                        SubtitleCorrectionField.TRANSLATION, "  ", "原文", "译文", null, null));

        assertTrue(e.getMessage().contains("lang"), "报错要说清缺的是 lang");
    }

    @Test
    void rejectsMissingField() {
        assertThrows(IllegalStateException.class,
                () -> new SubtitleCorrection(null, TASK_ID, "seg-0-1440", null, "", "原文", "改后", null, null));
    }

    @Test
    void normalizesNullSourceTextToEmptyString() {
        SubtitleCorrection correction = new SubtitleCorrection(null, TASK_ID, "seg-0-1440",
                SubtitleCorrectionField.TEXT, "", null, "改后原文", null, null);

        assertEquals("", correction.sourceText(), "source_text 列是 NOT NULL，空文本比 null 更贴近事实");
    }
}
