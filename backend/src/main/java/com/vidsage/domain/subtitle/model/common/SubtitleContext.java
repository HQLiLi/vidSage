package com.vidsage.domain.subtitle.model.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;


/**
 * 字幕上下文：一次摘要把全篇读进去后提炼出的东西，清洗与翻译共用同一份。
 *
 * <p>{@code degraded} 是「摘不出来时的标记」：这种上下文照样会落盘（表示这次试过了），
 * 但读回时带标记的那份绝不算缓存命中，下次仍会重试摘要。
 *
 * @param theme          视频主题，给两侧提示词当全局背景
 * @param sourceLanguage 源语种的 BCP-47 短码（如 zh / en / ja），模型拿不准时为空串；
 *                       翻译侧拿它决定要不要跳过
 * @param terms          全篇专有名词表
 * @param degraded       降级标记：摘不出来时的空壳为 {@code true}，正常摘要（哪怕内容为空）为 {@code false}
 */
public record SubtitleContext(String theme, String sourceLanguage, List<Term> terms, boolean degraded) {

    /**
     * 三个字段的便利构造：没有降级标记时就是正常上下文。
     */
    public SubtitleContext(String theme, String sourceLanguage, List<Term> terms) {
        this(theme, sourceLanguage, terms, false);
    }

    /**
     * 反序列化入口：摘要模型只回主题 / 源语种 / 术语，<b>不会回降级标记</b>（那是本地概念），
     * 缺失或为 {@code null} 时按 {@code false} 处理；读回本地落盘的上下文时同样走这里。
     *
     * @param theme          视频主题
     * @param sourceLanguage 源语种短码
     * @param terms          术语表
     * @param degraded       降级标记；缺失 / {@code null} 视为 {@code false}
     * @return 上下文
     */
    @JsonCreator
    public static SubtitleContext fromJson(@JsonProperty("theme") String theme,
                                           @JsonProperty("sourceLanguage") String sourceLanguage,
                                           @JsonProperty("terms") List<Term> terms,
                                           @JsonProperty("degraded") Boolean degraded) {
        return new SubtitleContext(theme, sourceLanguage, terms, degraded != null && degraded);
    }

    /**
     * 紧凑构造器兜底：null 一律收敛成空串 / 空表，调用方不必判空。
     */
    public SubtitleContext {
        theme = theme == null ? "" : theme;
        sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
        terms = terms == null ? List.of() : List.copyOf(terms);
    }

    /**
     * 一条术语（摘要层的原始视角，未做任何查证）。
     *
     * @param asr   字幕原文里的写法（可能有同音误写，靠它匹配），如：亮子纠缠
     * @param fixed 正确的写法，也是字幕里应当替换成的写法；正确写法是外语原文时就写原文，如：Mittelhau
     * @param note  替换理由：说清 asr 错在哪、为什么该换成 fixed；可为空串
     */
    public record Term(String asr, String fixed, String note) {
    }

    /**
     * 空上下文（非降级）：主题与源语种为空串、术语为空，但它是摘要「成功返回空内容」的结果，
     * 可以当缓存直接使用。
     *
     * @return 未带降级标记的空上下文
     */
    public static SubtitleContext empty() {
        return new SubtitleContext("", "", List.of());
    }

    /**
     * 降级上下文：摘要失败时写出的空壳，带降级标记。
     *
     * <p>落盘只为留下「这次试过了」的痕迹；读回时按标记当作未命中，下次仍会重新摘要。
     *
     * @return 带降级标记的空上下文
     */
    public static SubtitleContext degradedEmpty() {
        return new SubtitleContext("", "", List.of(), true);
    }
}
