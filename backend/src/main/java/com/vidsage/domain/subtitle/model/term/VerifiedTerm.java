package com.vidsage.domain.subtitle.model.term;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;

/**
 * 查证后的术语：摘要层产出的原始术语（{@link SubtitleContext.Term}）加上它在目标语种里的官方 / 通用译名。
 * 与 {@link SubtitleContext.Term} 分成两个类型是有意的——那个是摘要层的原始视角、落盘后永不改写，
 * 这个是查证层的结论、落独立的 {@code terms-<route>-<target>.json}。
 *
 * <p><b>{@code official} 没有三态</b>：查证调用失败的词根本不进产物文件（下次缺失时补查），
 * 所以文件里的每一条都查过——空串只表示「查过但弃权」，不表示「没查」。
 *
 * @param asr          字幕原文里的写法（承自 {@link SubtitleContext.Term#asr()}），靠它回查原始位置
 * @param fixed        正确的写法（承自 {@link SubtitleContext.Term#fixed()}），查证按它发起
 * @param note         替换理由（承自 {@link SubtitleContext.Term#note()}）
 * @param official     目标语种的官方/通用译名；空串 = 弃权（唯一空值语义），非空 = 查到
 * @param officialNote 查证结论的说明：译名等级（官方/通用）与证据来源 host；弃权时记弃权原因
 */
public record VerifiedTerm(String asr, String fixed, String note, String official, String officialNote) {
}
