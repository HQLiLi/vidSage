/**
 * 字幕上下文：字幕清洗、翻译与术语查证。根只剩本文件，构件按角色分桶，数据词汇再按子主题分二层。
 *
 * <p>{@code port/} 装端口接口 {@code TermLookupProvider} / {@code SubtitleCleanser} /
 * {@code SubtitleTranslator} / {@code SubtitleContextSummarizer}
 * ——由 {@code infrastructure/provider/llm/} 的 {@code Llm*} 适配器实现。
 *
 * <p>{@code policy/} 装纯函数领域政策：无状态、零 I/O，事实由应用层采集、判据单源在此。
 * {@code Bcp47LanguagePolicy}（语种政策：resolve 目标语种 / 源语种、主语言子标签比较）、
 * {@code TermVerification}（术语查证政策：触发三条件 + official/common/none 记账）、
 * {@code CheckpointPolicy}（检查点语义 + 上下文缓存-or-摘要）。
 *
 * <p>{@code model/} 按子主题再分一层：{@code cleanup/} 装清洗阶段的轨道与产物
 * （{@code SubtitleTrack} / {@code CleanedSubtitle} / {@code CleanseResult}）；{@code translation/}
 * 装翻译阶段的轨道与产物（{@code TranslatedTrack} / {@code TranslatedSubtitle} / {@code TranslatedSegment}
 * / {@code TranslateResult}）；{@code term/} 装术语查证词汇（{@code TermLookupCommand} /
 * {@code TermLookupResult} / {@code VerifiedTerm} / {@code VerifiedTerms}）；{@code common/}
 * 装跨环节共用词汇（{@code SubtitleArtifact} / {@code SubtitleContext}）。
 */
package com.vidsage.domain.subtitle;
