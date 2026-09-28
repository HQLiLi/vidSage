package com.vidsage.model.subtitle;

import java.util.List;


/**
 * LLM字幕清洗用的上下文
 *
 * @param theme 视频主题
 * @param terms
 */
public record SubtitleContext(String theme, List<Term> terms) {

    /**
     * 紧凑构造器兜底：null 一律收敛成空值，调用方不必判空。
     */
    public SubtitleContext {
        theme = theme == null ? "" : theme;
        terms = terms == null ? List.of() : List.copyOf(terms);
    }

    /**
     * 一条术语。
     *
     * @param asr   字幕原文里的写法（可能有同音误写，靠它匹配），如：亮子纠缠
     * @param fixed 正确的写法，也是字幕里应当替换成的写法；正确写法是外语原文时就写原文，如：Mittelhau
     * @param note  替换理由：说清 asr 错在哪、为什么该换成 fixed；可为空串
     */
    public record Term(String asr, String fixed, String note) {
    }

    /**
     * 空上下文，供摘要失败时降级用。
     *
     * @return 主题为空串、术语为空的上下文
     */
    public static SubtitleContext empty() {
        return new SubtitleContext("", List.of());
    }
}
