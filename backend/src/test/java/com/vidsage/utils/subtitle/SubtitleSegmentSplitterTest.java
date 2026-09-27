package com.vidsage.utils.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.model.asr.TranscriptionSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SubtitleSegmentSplitter 的纯单测：不起容器，只 new 被测类传预算，验证超长段切分、时长守恒、
 * 空段丢弃与构造卫语句。
 */
class SubtitleSegmentSplitterTest {

    /** 预算 8 秒。 */
    private static final long BUDGET_MS = 8000L;

    /**
     * 累加所有结果段的时长总和。
     */
    private static long totalDuration(List<TranscriptionSegment> segs) {
        long sum = 0;
        for (TranscriptionSegment s : segs) {
            sum += s.endTimeMs() - s.beginTimeMs();
        }
        return sum;
    }

    @Test
    void rejectsNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class, () -> new SubtitleSegmentSplitter(0));
        assertThrows(IllegalArgumentException.class, () -> new SubtitleSegmentSplitter(-100));
    }

    @Test
    void shortSegmentPassedThroughUnchanged() {
        SubtitleSegmentSplitter splitter = new SubtitleSegmentSplitter(BUDGET_MS);
        TranscriptionSegment seg = new TranscriptionSegment(1000, 5000, "这么短不用切");
        // 时长才 4 秒 ≤ 8 秒预算，应原样保留（同一段、同文本）
        List<TranscriptionSegment> out = splitter.split(List.of(seg));
        assertEquals(1, out.size());
        assertEquals(seg, out.get(0));
    }

    @Test
    void longSegmentWithPunctuationSplitsAndConservesTime() {
        SubtitleSegmentSplitter splitter = new SubtitleSegmentSplitter(BUDGET_MS);
        String longText = "震刀、弹刀、打铁是一种游戏中防御对方的进攻，从而让对方产生大量的架势条气节，"
                + "处决对手之后可以给对方大量的伤害的技术。";
        List<TranscriptionSegment> one = List.of(new TranscriptionSegment(0, 15000, longText));

        List<TranscriptionSegment> out = splitter.split(one);

        // 一定被切开
        assertTrue(out.size() >= 2, "15000ms 的长句应被切成多段，实际=" + out.size());
        // 时间守恒：首段 begin=0、末段 end=15000、时长总和=15000
        assertEquals(0, out.get(0).beginTimeMs());
        assertEquals(15000, out.get(out.size() - 1).endTimeMs());
        assertEquals(15000, totalDuration(out));
        // 每段都有内容且有正时长
        for (TranscriptionSegment s : out) {
            assertTrue(s.endTimeMs() > s.beginTimeMs());
            assertTrue(s.text() != null && !s.text().isBlank(), "子段文本不应为空: " + s.text());
        }
    }

    @Test
    void longSegmentWithoutPunctuationHardSplits() {
        SubtitleSegmentSplitter splitter = new SubtitleSegmentSplitter(BUDGET_MS);
        // 40 个汉字、无标点、20 秒：预算 8 秒 → 窗口 16 字 → 切成 3 段（16/16/8）
        String noPunct = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        List<TranscriptionSegment> one = List.of(new TranscriptionSegment(0, 20000, noPunct));

        List<TranscriptionSegment> out = splitter.split(one);

        assertTrue(out.size() >= 2);
        assertEquals(20000, totalDuration(out));
        assertEquals(0, out.get(0).beginTimeMs());
        assertEquals(20000, out.get(out.size() - 1).endTimeMs());
    }

    @Test
    void blankSegmentIsDropped() {
        SubtitleSegmentSplitter splitter = new SubtitleSegmentSplitter(BUDGET_MS);
        TranscriptionSegment blank = new TranscriptionSegment(0, 1000, "   ");
        List<TranscriptionSegment> out = splitter.split(List.of(blank));
        assertTrue(out.isEmpty(), "空文本段应被丢弃，而不是保留");
    }

    @Test
    void orderStaysAscending() {
        SubtitleSegmentSplitter splitter = new SubtitleSegmentSplitter(BUDGET_MS);
        List<TranscriptionSegment> out = splitter.split(List.of(
                new TranscriptionSegment(0, 20000, "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十")));
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i).beginTimeMs() >= out.get(i - 1).endTimeMs(),
                    "结果段应保持时间升序，非法于第 " + i + " 段");
        }
    }
}