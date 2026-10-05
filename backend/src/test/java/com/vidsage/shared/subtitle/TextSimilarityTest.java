package com.vidsage.shared.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * TextSimilarity.ratio 的纯单测：不起容器，手算式子验证对齐 difflib.SequenceMatcher.ratio()。
 */
class TextSimilarityTest {

    @Test
    void identicalTextsReturnOne() {
        assertEquals(1.0, TextSimilarity.ratio("同一个字幕", "同一个字幕"), 1e-9);
    }

    @Test
    void disjointTextsReturnZero() {
        assertEquals(0.0, TextSimilarity.ratio("abc", "xyz"), 1e-9);
    }

    @Test
    void matchesReferenceExample() {
        // LCS("abcde","abfce") = "abce"，长 4 → 2*4/(5+5) = 0.8
        assertEquals(0.8, TextSimilarity.ratio("abcde", "abfce"), 1e-9);
    }

    @Test
    void chineseLightEditScoresHigh() {
        // 原文「我防关这边露出」，改错为「我防到这边露出」——只改一字（关→到）。
        // 公共子串为「我防这边露出」6 字，ratio = 2*6/(7+7) = 12/14，应明显高于 0.7 的正常阈值
        double s = TextSimilarity.ratio("我防关这边露出", "我防到这边露出");
        assertEquals(12.0 / 14.0, s, 1e-9);
    }

    @Test
    void emptyAndNullVariants() {
        assertEquals(1.0, TextSimilarity.ratio("", ""), 1e-9);
        assertEquals(0.0, TextSimilarity.ratio("", "abc"), 1e-9);
        assertEquals(1.0, TextSimilarity.ratio(null, null), 1e-9);
        assertEquals(0.0, TextSimilarity.ratio(null, "abc"), 1e-9);
    }
}