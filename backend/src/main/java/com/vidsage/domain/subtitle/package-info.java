/**
 * 字幕上下文：字幕产物的版本与词汇，加清洗、翻译、术语查证。
 *
 * <p>根装实体 {@code SubtitleArtifact}（字幕产物版本）与值对象 {@code SubtitleKeywords}。
 * 任务与执行轴的实体（{@code SubtitleTask} / {@code TaskRun}）住在 {@code domain/task/}，
 * 中间产物（{@code IntermediateFile}）住在 {@code domain/intermediate/}。
 *
 * <p>{@code port/} 装端口接口 {@code TermLookupProvider} / {@code SubtitleCleanser} /
 * {@code SubtitleTranslator} / {@code SubtitleContextSummarizer} / {@code SubtitleKeywordRepository} /
 * {@code SubtitleArtifactRepository}——由 {@code infrastructure/} 的适配器与 persistence 层实现。
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
 * {@code TermLookupResult} / {@code VerifiedTerm} / {@code VerifiedTerms}）；{@code keyword/}
 * 装关键词值对象 {@code SubtitleKeywords}；{@code common/} 装跨环节共用词汇
 * （{@code SubtitleStageOutput} / {@code SubtitleContext} / {@code SubtitleStageName} /
 * {@code SubtitleArtifactSpec}）。产物类型 {@code ArtifactType} 与产物状态
 * {@code SubtitleArtifactStatus} 直接住在 {@code model/}。
 */
package com.vidsage.domain.subtitle;
