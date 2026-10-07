package com.vidsage.shared.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TermMatcher 的纯单测：直接喂键值对与上下文，不起 Spring 容器。
 *
 * <p>钉的是原 {@code SubtitleCleanser.hitTerms} 的判据——搬迁是有意为之，
 * 判据一字不改，这批用例就是「没搬丢东西」的证据。
 *
 * <p>另有一组用例专门钉<b>两个入口的差别</b>：清洗按 asr、翻译按 fixed，输入文本不同。
 * 两种匹配只在「术语已被清洗替换或故意不换」时才分道扬镳，必须各钉一条，
 * 否则将来有人把两个入口合并成一个，测试不会红。
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

    // ------------------------------------------------------------------
    // asr 入口（清洗用）：判据与搬迁前一致
    // ------------------------------------------------------------------

    @Test
    void hitsTermsAppearingInBatch() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTermsByAsr(batch("米特号是基础斩击"), ctx);

        assertEquals(1, hits.size(), "只该命中出现过的术语");
        assertEquals("米特号", hits.getFirst().asr());
    }

    @Test
    void preservesContextOrderAcrossBatches() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTermsByAsr(batch("米特号与帕姆都在"), ctx);

        assertEquals(List.of("米特号", "帕姆"), hits.stream().map(SubtitleContext.Term::asr).toList(),
                "命中顺序应与术语表一致");
    }

    @Test
    void matchesAcrossAllBatchLinesAndIgnoresCase() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("Mittelhau", "Mittelhau", "x"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTermsByAsr(batch("无关的一句", "这里提到 mittelhau"), ctx);

        assertEquals(1, hits.size(), "命中判定要拼起整批、且不区分大小写");
    }

    @Test
    void skipsBlankAsrAndUnmatchedTerms() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("   ", "whatever", "x"),
                new SubtitleContext.Term("帕姆", "Pommel", "y"));

        List<SubtitleContext.Term> hits = TermMatcher.hitTermsByAsr(batch("米特号是基础斩击"), ctx);

        assertTrue(hits.isEmpty(), "空白 asr 跳过，没出现的术语也不命中");
    }

    @Test
    void returnsEmptyForNullContextOrEmptyTerms() {
        assertTrue(TermMatcher.hitTermsByAsr(batch("随便"), null).isEmpty(), "无上下文时无命中");
        assertTrue(TermMatcher.hitTermsByAsr(batch("随便"), SubtitleContext.empty()).isEmpty(),
                "无术语时无命中");
    }

    // ------------------------------------------------------------------
    // fixed 入口（翻译用）：两个入口的差别
    // ------------------------------------------------------------------

    @Test
    void 清洗已替换的术语_按fixed命中而按asr不命中() {
        // 清洗把「米特号」换成了「Mittelhau」，-clean 文本里只剩 fixed
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "x"));
        Map<String, String> cleaned = batch("Mittelhau 是基础斩击");

        List<SubtitleContext.Term> byFixed = TermMatcher.hitTermsByFixed(cleaned, ctx);
        List<SubtitleContext.Term> byAsr = TermMatcher.hitTermsByAsr(cleaned, ctx);

        assertEquals(1, byFixed.size(), "翻译按 fixed 匹配，该命中");
        assertEquals("米特号", byFixed.getFirst().asr());
        assertTrue(byAsr.isEmpty(), "清洗后的文本里没有 asr，按 asr 匹配会漏掉它");
    }

    @Test
    void 清洗故意没换的术语_按fixed不命中() {
        // 清洗判断「这句里它不是那个词」而保留了 asr，-clean 文本里只有 asr
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("米特号", "Mittelhau", "x"));
        Map<String, String> cleaned = batch("米特号是基础斩击");

        assertTrue(TermMatcher.hitTermsByFixed(cleaned, ctx).isEmpty(),
                "正文里没有 fixed，翻译不该把它放进参考去覆盖清洗的判断");
    }

    @Test
    void asr与fixed相同时两个入口都命中() {
        SubtitleContext ctx = contextWith(
                new SubtitleContext.Term("只狼", "只狼", "本来就写对了"));
        Map<String, String> text = batch("只狼的弹刀");

        assertEquals(1, TermMatcher.hitTermsByAsr(text, ctx).size(), "asr==fixed，按 asr 命中");
        assertEquals(1, TermMatcher.hitTermsByFixed(text, ctx).size(), "asr==fixed，按 fixed 也命中");
    }
}
