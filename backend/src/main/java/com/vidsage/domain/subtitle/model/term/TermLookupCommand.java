package com.vidsage.domain.subtitle.model.term;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;

/**
 * 一次术语译名查证的入参：拿一个术语去查它在目标语种里的官方/通用译名。
 *
 * @param term           术语的正确写法（{@link SubtitleContext.Term#fixed()}），查证按它发起搜索
 * @param targetLanguage 目标语种（BCP-47 短码，如 en / ja）；查证找的是这个语种里的译名
 * @param theme          视频主题（摘要层产出），给查证模型做消歧上下文（同一个词在不同领域指不同东西）；可为空串
 */
public record TermLookupCommand(String term, String targetLanguage, String theme) {
}
