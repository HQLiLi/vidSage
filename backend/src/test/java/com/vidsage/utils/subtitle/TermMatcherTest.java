package com.vidsage.utils.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.model.subtitle.SubtitleContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TermMatcher 的纯单测：直接喂键值对与上下文，不起 Spring 容器。
 *
 * <p>钉的是原 {@code SubtitleCleanser.hitTerms} 的判据——搬迁是有意为之，
 * 判据一字不改，这批用例就是「没搬丢东西」的证据。
 */
class TermMatcherTest {

    private static Map<String, String> batch(String... texts) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < texts.length; i++) {
            map.put(String.valueOf(i + 1), texts[i]);
        }
        return map;
    }

    private static SubtitleContext contextWith(SubtitleContext.Term... terms) {
        return new SubtitleContext("剑术", "", List.of(terms));
    }

    @Test
    void hitsTermsAppearingInBatch() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTerms(batch("米特号是基础斩击"), ctx);

        assertEquals(1, hits.size(), "只该命中出现过的术语");
        assertEquals("米特号", hits.getFirst().asr());
    }

    @Test
    void preservesContextOrderAcrossBatches() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTerms(batch("米特号与帕姆都在"), ctx);

        assertEquals(List.of("米特号", "帕姆"), hits.stream().map(SubtitleContext.Term::asr).toList(),
                "命中顺序应与术语表一致");
    }

    @Test
    void matchesAcrossAllBatchLinesAndIgnoresCase() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("Mittelhau", "Mittelhau", "x"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTerms(batch("无关的一句", "这里提到 mittelhau"), ctx);

        assertEquals(1, hits.size(), "命中判定要拼起整批、且不区分大小写");
    }

    @Test
    void skipsBlankAsrAndUnmatchedTerms() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("   ", "whatever", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTerms(batch("米特号是基础斩击"), ctx);

        assertTrue(hits.isEmpty(), "空白 asr 跳过，没出现的术语也不命中");
    }

    @Test
    void returnsEmptyForNullContextOrEmptyTerms() {
        assertTrue(TermMatcher.hitTerms(batch("随便"), null).isEmpty(), "无上下文时无命中");
        assertTrue(TermMatcher.hitTerms(batch("随便"), SubtitleContext.empty()).isEmpty(),
                "无术语时无命中");
    }
}
