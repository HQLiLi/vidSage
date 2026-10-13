package com.vidsage.shared.subtitle;

import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性超长段切分器：把时长超过预算的一句字幕切分成多句可读的短句。
 *
 * <p>不调用任何 LLM，纯规则：按「预算毫秒 ↔ 原句占比」算出一个字符窗口，窗口内优先找标点/
 * 空格断开（中文顿号、逗号、句末标点），找不到就按字符硬切。时间按各子段的字符数比例映射回
 * 原区间，保证时长守恒（最后一段收口到原 end，避免累积舍入漂移）。
 *
 * <p>设计取舍：不做静音检测、不追求语义边界，只求「把 15 秒的长句打散成可读短句」——翻译前
 * 需要的正是清洗后的短句。
 */
public final class SubtitleSegmentSplitter {

    /**
     * 断点字符：中文的顿号/逗号/句末感叹问号/分号冒号，加上英文逗句分号冒号感叹问号。
     * 空格天然是单词边界，也进集合。下标命中即在该字符后切开。
     */
    private static final String BREAK_CHARS = "，。！？；：、.。!?;:";

    private final long maxSegmentMs;

    /**
     * @param maxSegmentMs 单句时长预算（毫秒）；必须为正
     * @throws IllegalArgumentException 预算不大于 0
     */
    public SubtitleSegmentSplitter(long maxSegmentMs) {
        if (maxSegmentMs <= 0) {
            throw new IllegalArgumentException("切分时长预算必须为正整数毫秒：maxSegmentMs=" + maxSegmentMs);
        }
        this.maxSegmentMs = maxSegmentMs;
    }

    /**
     * 一次性切分整份 segments。
     *
     * <p><b>空文本段直接丢弃</b>（纯防御）：ASR 可能吐出没有内容但有时间的占位段，翻译不需要
     * 它们。未超预算的段原样保留；超预算的段打进结果。
     *
     * @param segments 原始句级分段，时间升序
     * @return 切分后的列表，保持升序与内容顺序
     */
    public List<TranscriptionSegment> split(List<TranscriptionSegment> segments) {
        List<TranscriptionSegment> out = new ArrayList<>();
        for (TranscriptionSegment seg : segments) {
            String text = seg.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            long durationMs = seg.endTimeMs() - seg.beginTimeMs();
            if (durationMs <= maxSegmentMs) {
                out.add(seg);
                continue;
            }
            out.addAll(splitLongSegment(seg, text, durationMs));
        }
        return out;
    }

    /**
     * 把单条超长段按预算切成多段。
     *
     * @param seg        原段
     * @param text       原段文本（已清洗掉空）
     * @param durationMs 原段时长
     * @return 切分出的子段列表
     */
    private List<TranscriptionSegment> splitLongSegment(TranscriptionSegment seg, String text, long durationMs) {
        int budget = Math.max(1, (int) Math.round(maxSegmentMs * (double) text.length() / durationMs));
        List<String> pieces = splitText(text, budget);
        long[] bounds = proportionalBounds(seg.beginTimeMs(), seg.endTimeMs(), pieces);

        List<TranscriptionSegment> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            long b = bounds[i];
            long e = bounds[i + 1];
            if (e <= b) {
                continue;   // 退化保护：舍入让某段变 0 时长就丢掉，宁可少一段别出拗口零段
            }
            out.add(new TranscriptionSegment(b, e, pieces.get(i)));
        }
        return out;
    }

    /**
     * 把文本切成窗口大小内的子串列表，优先在标点处断。
     *
     * @param text   原文本
     * @param budget 单窗口字符数上限（已 min 1）
     * @return 子串列表（非空）；都在字符数不超 {@code budget} 的窗口内，且位置连起来=整段文本
     */
    private static List<String> splitText(String text, int budget) {
        List<String> pieces = new ArrayList<>();
        int n = text.length();
        int start = 0;
        while (start < n) {
            int end = start + budget;
            if (end >= n) {
                pieces.add(text.substring(start));
                break;
            }
            int cut = findBreakBefore(text, start, end);
            if (cut > start) {
                pieces.add(text.substring(start, cut));
                start = cut;
            } else {
                pieces.add(text.substring(start, end));
                start = end;
            }
        }
        return pieces;
    }

    /**
     * 在 {@code [start, end) } 内从后往前找第一个断点字符，返回「该字符后一位」的索引；没有返回
     * {@code -1}。
     *
     * @param text            全文
     * @param startInclusive  窗口起点（含）
     * @param endExclusive    窗口终点（不含）
     * @return 断点后索引，或 -1
     */
    private static int findBreakBefore(String text, int startInclusive, int endExclusive) {
        for (int i = endExclusive - 1; i >= startInclusive; i--) {
            if (BREAK_CHARS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 按子段字符数比例把 {@code [beginMs, endMs]} 摊开成逐段边界。
     *
     * <p>返回长度 {@code pieces.size() + 1} 的数组；首尾固定在 {@code beginMs}/{@code endMs}，
     * 中间逐段累加。末边界覆盖为 {@code endMs} 是为抵消舍入漂移，保证时长守恒。
     *
     * @param beginMs 原段开始（毫秒）
     * @param endMs   原段结束（毫秒）
     * @param pieces  子段文本列表
     * @return 每段的 start/end 边界数组
     */
    private static long[] proportionalBounds(long beginMs, long endMs, List<String> pieces) {
        int total = 0;
        for (String p : pieces) {
            total += p.length();
        }
        total = Math.max(total, 1);
        long durationMs = endMs - beginMs;

        long[] bounds = new long[pieces.size() + 1];
        bounds[0] = beginMs;
        long acc = beginMs;
        for (int i = 0; i < pieces.size(); i++) {
            long dur = Math.round(durationMs * (double) pieces.get(i).length() / total);
            acc += dur;
            bounds[i + 1] = acc;
        }
        bounds[pieces.size()] = endMs;
        return bounds;
    }
}