package com.vidsage.shared.subtitle;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 术语命中判定：从全篇术语表里挑出「本批正文真出现过」的那几条。
 *
 * <p>判据只有一条：把本批正文拼成小写后，看它是否包含某个术语字段的小写原文。空白的那个字段
 * 直接跳过（它匹配不了任何东西，留着还会误命中所有文本）。
 *
 * <p><b>为什么有两个入口而不是一个</b>：清洗与翻译的判据相同，但<b>拿哪个字段去匹配不一样</b>，
 * 因为两者的输入文本不同——清洗吃原始 ASR 字幕（写的是 asr 那个错写法），翻译吃清洗后的字幕
 * （命中过的术语已多被替换成 fixed）。用一个入口硬套会同时踩两个坑：清洗后的文本里 asr 已被换掉，
 * 按 asr 匹配会漏掉本该保留原文的专有名词；而清洗故意没换的术语在文本里仍是 asr，按 asr 匹配又会
 * 把 fixed 写进参考，与正文自相矛盾。所以两个入口各自标明匹配哪个字段，判据本体共用一份。
 */
public final class TermMatcher {

    private TermMatcher() {
    }

    /**
     * 清洗侧的术语命中：拿术语的 {@code asr} 去匹配。
     *
     * <p><b>输入是原始 ASR 字幕</b>（{@code segments-<route>.json}）——里面写的是模型可能记错的
     * {@code asr} 写法，清洗要据此把词换成 {@code fixed}，所以这里必须匹配 {@code asr}。
     *
     * @param original 本批原文（键为条号，值为字幕文本）
     * @param context  字幕上下文；可为 {@code null}
     * @return 命中的术语，顺序与 {@code context.terms()} 一致；无命中返回空列表
     */
    public static List<SubtitleContext.Term> hitTermsByAsr(Map<String, String> original,
                                                           SubtitleContext context) {
        return hit(original, context, true);
    }

    /**
     * 翻译侧的术语命中：拿术语的 {@code fixed} 去匹配。
     *
     * <p><b>输入是清洗后的字幕</b>（{@code segments-<route>-clean.json}）——清洗已经把命中术语的字面
     * 从 {@code asr} 换成了 {@code fixed}，正文里现在出现的是 {@code fixed}，故这里必须匹配
     * {@code fixed}。拿 {@code asr} 匹配会漏掉这些已被替换的术语（它们正是要保留原文不译的专有名词）；
     * 而清洗判断读不通、故意不换的术语正文里仍是 {@code asr}，也不再被选中——清洗已就那句上下文判过
     * 「它在这里不是那个词」，翻译不该再把 {@code fixed} 写进参考去覆盖这个判断。
     *
     * @param original 本批清洗后原文（键为条号，值为字幕文本）
     * @param context  字幕上下文；可为 {@code null}
     * @return 命中的术语，顺序与 {@code context.terms()} 一致；无命中返回空列表
     */
    public static List<SubtitleContext.Term> hitTermsByFixed(Map<String, String> original,
                                                             SubtitleContext context) {
        return hit(original, context, false);
    }

    /**
     * 命中判定的本体。
     *
     * @param original 本批原文
     * @param context  字幕上下文
     * @param byAsr    {@code true} 拿 {@code asr} 匹配（清洗），{@code false} 拿 {@code fixed} 匹配（翻译）
     * @return 命中的术语，顺序与 {@code context.terms()} 一致
     */
    private static List<SubtitleContext.Term> hit(Map<String, String> original,
                                                  SubtitleContext context, boolean byAsr) {
        if (context == null || context.terms().isEmpty()) {
            return List.of();
        }
        String batchText = String.join("\n", original.values()).toLowerCase(Locale.ROOT);
        List<SubtitleContext.Term> hits = new ArrayList<>();
        for (SubtitleContext.Term t : context.terms()) {
            String key = byAsr ? t.asr() : t.fixed();
            if (key.isBlank()) {
                continue;
            }
            if (batchText.contains(key.toLowerCase(Locale.ROOT))) {
                hits.add(t);
            }
        }
        return hits;
    }
}
