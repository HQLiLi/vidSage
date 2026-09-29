package com.vidsage.model.subtitle;

/**
 * 一次术语译名查证的入参：拿一个术语去查它在目标语种里的官方/通用译名。
 *
 * <p>查证以<b>逐条调用</b>的方式进行（触发确定性由上层编排保证，见
 * {@code SubtitleTermVerifier}），本契约只描述「查哪一个、查成什么语种」。
 *
 * @param term           术语的正确写法（{@link SubtitleContext.Term#fixed()}），查证按它发起搜索
 * @param targetLanguage 目标语种（BCP-47 短码，如 en / ja）；查证找的是这个语种里的译名
 * @param theme          视频主题（摘要层产出），给查证模型做消歧上下文（同一个词在不同领域指不同东西）；可为空串
 */
public record TermLookupCommand(String term, String targetLanguage, String theme) {
}
