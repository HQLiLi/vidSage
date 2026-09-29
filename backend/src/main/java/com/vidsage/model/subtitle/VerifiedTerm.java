package com.vidsage.model.subtitle;

/**
 * 查证后的术语：摘要层产出的原始术语（{@link SubtitleContext.Term}）加上它在目标语种里的
 * 官方/通用译名。
 *
 * <p>与 {@link SubtitleContext.Term} 拆成两个类型是有意的：Term 是摘要层的原始视角，落盘在
 * {@code context-<route>.json} 里、此后永不改写；本类型是查证层的结论，落独立的
 * {@code terms-<route>-<target>.json}。原始与结论互不掺水，也就没有「旧缓存迁移」问题。
 *
 * <p><b>official 无三态</b>：查证调用失败的词根本不进产物文件（下次文件缺失时补查），所以
 * 文件里的每一条都查过。{@code official} 只有两种取值：
 * <ul>
 *   <li>{@code ""}（空串）= 弃权——查过，但没有有据可依的目标语种译名，不再重搜烧钱；</li>
 *   <li>非空 = 查到的译名，等级与证据来源见 {@code officialNote}。</li>
 * </ul>
 *
 * @param asr          字幕原文里的写法（承自 {@link SubtitleContext.Term#asr()}），靠它回查原始位置
 * @param fixed        正确的写法（承自 {@link SubtitleContext.Term#fixed()}），查证按它发起
 * @param note         替换理由（承自 {@link SubtitleContext.Term#note()}）
 * @param official     目标语种的官方/通用译名；空串 = 弃权（唯一空值语义），非空 = 查到
 * @param officialNote 查证结论的说明：译名等级（官方/通用）与证据来源 host；弃权时记弃权原因
 */
public record VerifiedTerm(String asr, String fixed, String note, String official, String officialNote) {
}
