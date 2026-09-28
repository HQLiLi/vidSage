package com.vidsage.utils.subtitle;

import com.vidsage.model.subtitle.SubtitleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 术语命中判定：从全篇术语表里挑出「本批正文真出现过」的那几条。
 *
 * <p>判据只有一条：把本批正文拼成小写后，看它是否包含术语 {@code asr} 的小写原文。
 * 空白的 {@code asr} 直接跳过（它匹配不了任何东西，留着还会误命中所有文本）。
 *
 * <p><b>为什么单独抽成一个类</b>：清洗侧的术语参考渲染、校验基线、替换指标三处共用同一份判定，
 * 翻译是第四个使用者。同一判据必须只有一份——分三处各判一遍，将来改判据时只会改到一半，
 * 而「改到一半」的症状是静默的（渲染出来的术语与基线/指标对不上，没人报错）。
 *
 * <p>全静态、无状态，构造器私有，不要 new 它。定位与 {@link TextSimilarity} 类似：
 * 都是被 {@code provider/llm/} 下的 worker 共用的纯算法助手，跨包使用所以是 {@code public}。
 */
public final class TermMatcher {

    private TermMatcher() {
        // 纯静态工具，不实例化
    }

    /**
     * 挑出本批正文里真出现过的术语。
     *
     * @param original 本批原文（键为条号，值为字幕文本）
     * @param context  字幕上下文；可为 {@code null}
     * @return 命中的术语，顺序与 {@code context.terms()} 一致；无命中返回空列表
     */
    public static List<SubtitleContext.Term> hitTerms(Map<String, String> original, SubtitleContext context) {
        if (context == null || context.terms().isEmpty()) {
            return List.of();
        }
        String batchText = String.join("\n", original.values()).toLowerCase(Locale.ROOT);
        List<SubtitleContext.Term> hits = new ArrayList<>();
        for (SubtitleContext.Term t : context.terms()) {
            if (t.asr().isBlank()) {
                continue;
            }
            if (batchText.contains(t.asr().toLowerCase(Locale.ROOT))) {
                hits.add(t);
            }
        }
        return hits;
    }
}
