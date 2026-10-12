package com.vidsage.domain.subtitle.model.keyword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SubtitleKeywords} 的纯单元测试：归一化、去重保序、上限校验与指纹。
 *
 * <p>不连库也不起容器——这份值对象没有 I/O，全部逻辑都在紧凑构造器与两个实例方法里。
 */
class SubtitleKeywordsTest {

    // ---------- 归一化 ----------

    @Test
    void trimsEachKeywordAndDropsBlankOnes() {
        SubtitleKeywords keywords = new SubtitleKeywords(
                Arrays.asList("  影视飓风  ", "", "   ", "Tim"));

        assertEquals(List.of("影视飓风", "Tim"), keywords.values(), "首尾空白应去掉，纯空白条目应丢弃");
    }

    @Test
    void replacesStructuralCharactersWithSpaces() {
        SubtitleKeywords keywords = new SubtitleKeywords(
                List.of("影视\n飓风", "Tim\rCook", "a\tb"));

        assertEquals(List.of("影视 飓风", "Tim Cook", "a b"), keywords.values(),
                "换行 / 回车 / 制表符都是结构性字符，应换成空格而不是留在原地");
    }

    @Test
    void keepsContentCharactersThatAreNotStructural() {
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("C++ <memory>"));

        assertEquals(List.of("C++ <memory>"), keywords.values(),
                "尖括号是内容性字符，剥掉会误伤真术语——不做输入侧黑名单");
    }

    @Test
    void nullListAndNullElementsAreTreatedAsEmpty() {
        assertTrue(new SubtitleKeywords(null).isEmpty(), "null 列表当空列表");
        assertTrue(new SubtitleKeywords(List.of()).isEmpty(), "空列表就是空的");
        assertEquals(List.of("A"), new SubtitleKeywords(Arrays.asList(null, "A")).values(),
                "null 条目直接丢弃，不该让整个请求 500");
    }

    // ---------- 去重与顺序 ----------

    @Test
    void deduplicatesKeepingFirstOccurrenceOrder() {
        SubtitleKeywords keywords = new SubtitleKeywords(List.of("B", "A", "B", "C", "A"));

        assertEquals(List.of("B", "A", "C"), keywords.values(),
                "去重要保留首次出现顺序——那正是用户填写顺序，GET 得原样还给他");
    }

    // ---------- 上限校验 ----------

    @Test
    void acceptsExactlyTheLimit() {
        assertEquals(50, new SubtitleKeywords(distinctKeywords(50)).values().size(),
                "50 条应放行（边界含端点）");
    }

    @Test
    void rejectsTooManyKeywords() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SubtitleKeywords(distinctKeywords(51)));

        assertTrue(e.getMessage().contains("51"), "报错要说清实际收到了多少条：");
    }

    @Test
    void rejectsKeywordLongerThanLimit() {
        String tooLong = "词".repeat(51);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SubtitleKeywords(List.of(tooLong)));

        assertTrue(e.getMessage().contains("51"), "报错要说清实际长度：");
    }

    @Test
    void countsLengthAfterNormalization() {
        // 前后各 10 个空格归一化后就没了，不该因为原始长度超限而被拒
        String padded = "          影视飓风          ";

        assertEquals(List.of("影视飓风"), new SubtitleKeywords(List.of(padded)).values());
    }

    /**
     * 造 count 条互不相同的关键词——去重发生在上限校验之前，重复条目会被先去掉，
     * 用重复值造不出「超限」这个前提。
     */
    private static List<String> distinctKeywords(int count) {
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add("词" + i);
        }
        return values;
    }

    // ---------- 指纹 ----------

    @Test
    void fingerprintIsStableForSameContent() {
        assertEquals(new SubtitleKeywords(List.of("A", "B")).computeFingerprint(),
                new SubtitleKeywords(List.of("A", "B")).computeFingerprint(),
                "同样内容永远同样指纹");
    }

    @Test
    void fingerprintIgnoresFillingOrder() {
        assertEquals(new SubtitleKeywords(List.of("A", "B")).computeFingerprint(),
                new SubtitleKeywords(List.of("B", "A")).computeFingerprint(),
                "用户调换填写顺序不该触发一次重跑");
    }

    @Test
    void fingerprintChangesWhenContentChanges() {
        assertNotEquals(new SubtitleKeywords(List.of("A")).computeFingerprint(),
                new SubtitleKeywords(List.of("B")).computeFingerprint());
    }

    @Test
    void fingerprintOfEmptyListIsAUsableValue() {
        String fingerprint = new SubtitleKeywords(List.of()).computeFingerprint();

        assertFalse(fingerprint.isBlank(), "空清单也要有指纹：它是参与判定的一个正经事实");
        assertEquals(16, fingerprint.length(), "16 位十六进制");
        assertEquals(fingerprint, new SubtitleKeywords(null).computeFingerprint(),
                "null 列表归一化后就是空清单，两者指纹必须一致");
    }
}
