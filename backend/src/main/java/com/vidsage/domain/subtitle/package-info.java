/**
 * 字幕上下文：字幕清洗、翻译与术语查证。根只剩本文件，构件按角色分桶，数据词汇再按子主题分二层。
 *
 * <p>{@code port/} 装端口接口 {@code TermLookupProvider} / {@code SubtitleCleanser} /
 * {@code SubtitleTranslator} / {@code SubtitleContextSummarizer}（2026-10-06 端口倒置：三个原本被
 * application 直接引用的 infrastructure 具体类改为定义在此、由 {@code infrastructure/provider/llm/}
 * 的 {@code Llm*} 实现）。{@code policy/} 装纯函数领域政策（2026-10-06 业务逻辑下沉 Phase B 落地，
 * 无状态、零 I/O，事实由应用层采集、判据单源在此）：{@code Bcp47Language}（语种政策：resolve 目标语种/
 * 源语种、主语言子标签比较、同语种照抄）、{@code TermVerification}（术语查证政策：触发三条件、
 * official/common/none 记账、失败词不进产物/全失败不落盘）、{@code CheckpointPolicy}
 * （检查点语义：JSON 非空 = 完成、0 字节 = 未完成、SRT 是派生视图不参与幂等；上下文缓存-or-摘要
 * 且空摘要绝不落盘）。
 *
 * <p>{@code model/} 按子主题再分一层：{@code cleanup/} 装清洗阶段的轨道与产物
 * （{@code SubtitleTrack} / {@code CleanedSubtitle} / {@code CleanseResult}）；{@code translation/}
 * 装翻译阶段的轨道与产物（{@code TranslatedTrack} / {@code TranslatedSubtitle} / {@code TranslatedSegment}
 * / {@code TranslateResult}）；{@code term/} 装术语查证词汇（{@code TermLookupCommand} /
 * {@code TermLookupResult} / {@code VerifiedTerm} / {@code VerifiedTerms}）；{@code common/}
 * 装跨环节共用词汇（{@code SubtitleArtifact} / {@code SubtitleRoute} / {@code SubtitleContext}）。
 */
package com.vidsage.domain.subtitle;
