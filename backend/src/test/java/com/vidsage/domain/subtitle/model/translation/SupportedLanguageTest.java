package com.vidsage.domain.subtitle.model.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.exception.UnsupportedLanguageException;
import org.junit.jupiter.api.Test;

/**
 * SupportedLanguage 的纯单元测试：别名解析与「是不是同一门语言」。
 *
 * <p>这两条判据是翻译的入口守卫与跳过开关的**单源**——原
 * {@code Bcp47LanguagePolicy} 的 {@code isSameMainLanguage}（只比主语言，简中选繁体会被误跳）
 * 与 {@code resolveSourceLanguage}（源语种从摘要上下文里推断）都已退场：源语种改成调用方断言。
 */
class SupportedLanguageTest {

    @Test
    void parsesPlainCodeAndCommonAliases() {
        assertEquals(SupportedLanguage.SIMPLE_CHINESE, SupportedLanguage.parse("zh-Hans"));
        assertEquals(SupportedLanguage.SIMPLE_CHINESE, SupportedLanguage.parse("zh-CN"));
        assertEquals(SupportedLanguage.SIMPLE_CHINESE, SupportedLanguage.parse("zh-SG"));
        // 裸 zh 等同于简体——它只是**不进清单**（列两个简中选项会让用户困惑），不是不收
        assertEquals(SupportedLanguage.SIMPLE_CHINESE, SupportedLanguage.parse("zh"));
        assertEquals(SupportedLanguage.TRADITIONAL_CHINESE, SupportedLanguage.parse("zh-TW"));
        assertEquals(SupportedLanguage.TRADITIONAL_CHINESE, SupportedLanguage.parse("zh-Hant"));
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.parse("en"));
        assertEquals(SupportedLanguage.JAPANESE, SupportedLanguage.parse("ja"));
    }

    @Test
    void parsingIgnoresCaseAndSurroundingBlanks() {
        assertEquals(SupportedLanguage.TRADITIONAL_CHINESE, SupportedLanguage.parse(" ZH-hant "));
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.parse("EN"));
    }

    @Test
    void unknownLanguageThrowsBeforeAnyIo() {
        assertThrows(UnsupportedLanguageException.class, () -> SupportedLanguage.parse("de"));
        assertThrows(UnsupportedLanguageException.class, () -> SupportedLanguage.parse(""));
        assertThrows(UnsupportedLanguageException.class, () -> SupportedLanguage.parse(null));
        // 会进产物名与提示词的串：带路径分隔符的一律认不出（白名单是防线）
        assertThrows(UnsupportedLanguageException.class, () -> SupportedLanguage.parse("../../etc"));
    }

    @Test
    void sameLanguageIsEnumEqualityNotMainSubtagComparison() {
        // zh-CN / zh-SG / zh-Hans 归一到简中 → 与简中同门；繁体是同主语言但不同脚本 → **不是**同门
        assertTrue(SupportedLanguage.parse("zh-CN").isSameLanguage(SupportedLanguage.SIMPLE_CHINESE));
        assertFalse(SupportedLanguage.parse("zh-TW").isSameLanguage(SupportedLanguage.SIMPLE_CHINESE),
                "简中视频选繁体必须真翻（原「只比主语言」的判据会在这里静默漏译）");
        assertFalse(SupportedLanguage.SIMPLE_CHINESE.isSameLanguage(SupportedLanguage.JAPANESE));
        assertFalse(SupportedLanguage.ENGLISH.isSameLanguage(null), "空目标语种不构成「同门」");
    }

    @Test
    void codeIsItselfAMatchKeySoTheListRoundTrips() {
        // 清单只回传 code；前端把 code 原样传回来必须认得（否则清单白给）
        for (SupportedLanguage language : SupportedLanguage.values()) {
            assertEquals(language, SupportedLanguage.parse(language.getCode()),
                    () -> "清单里的 code 必须能被 parse 认回来：" + language.getCode());
            assertTrue(language.matches(language.getCode()));
        }
    }
}
