package com.vidsage.model.subtitle;

import java.util.List;


/**
 * 字幕上下文：一次摘要把全篇读进去后提炼出的东西，清洗与翻译共用同一份。
 *
 * <p>三样东西各有用处：{@code theme} 给两边的提示词当全局背景；{@code terms} 是整篇出现的
 * 专有名词表；{@code sourceLanguage} 是模型推断出的源语种，翻译后拿它决定要不要跳过。
 *
 * @param theme          视频主题
 * @param sourceLanguage 源语种的 BCP-47 短码（如 zh / en / ja），模型拿不准时为空串。
 *                       字段名不叫 language：{@link SubtitleTrack#language()} 已经叫这个，
 *                       在同时有目标语种的语境里两者会看岔
 * @param terms          全篇专有名词表
 */
public record SubtitleContext(String theme, String sourceLanguage, List<Term> terms) {

    /**
     * 紧凑构造器兜底：null 一律收敛成空值，调用方不必判空。
     */
    public SubtitleContext {
        theme = theme == null ? "" : theme;
        sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
        terms = terms == null ? List.of() : List.copyOf(terms);
    }

    /**
     * 一条术语。
     *
     * @param asr          字幕原文里的写法（可能有同音误写，靠它匹配），如：亮子纠缠
     * @param fixed        正确的写法，也是字幕里应当替换成的写法；正确写法是外语原文时就写原文，如：Mittelhau
     * @param note         替换理由：说清 asr 错在哪、为什么该换成 fixed；可为空串
     * @param official     目标语种的官方/通用译名（联网查证的产出）。<b>三态语义，不得收敛</b>：
     *                     {@code null} = 未查证（旧缓存缺字段，或上次查证失败——要补跑）；
     *                     {@code ""} = 查过但没有有据可依的译名（弃权，不再重搜）；
     *                     非空 = 查到的译名。旧的持久化文件没有这两个键，反序列化天然得 {@code null}
     * @param officialNote 查证结论的说明：译名等级（官方/通用）与证据来源 host；查证失败为 {@code null}
     */
    public record Term(String asr, String fixed, String note, String official, String officialNote) {

        /**
         * 旧形态便利构造器：不关心查证产出的调用点（清洗/翻译的术语命中判定等）用它，
         * official 语义为「未查证」（{@code null}），与旧缓存反序列化的结果一致。
         */
        public Term(String asr, String fixed, String note) {
            this(asr, fixed, note, null, null);
        }
    }

    /**
     * 空上下文，供摘要失败时降级用。
     *
     * @return 主题为空串、源语种为空串、术语为空的上下文
     */
    public static SubtitleContext empty() {
        return new SubtitleContext("", "", List.of());
    }
}
