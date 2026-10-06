/**
 * 字幕上下文：字幕清洗、翻译与术语查证的领域概念——源/清洗/翻译各阶段的轨道与产物
 * （{@code SubtitleTrack} / {@code CleanedSubtitle} / {@code TranslatedSubtitle} /
 * {@code TranslatedTrack} / {@code TranslatedSegment} / {@code SubtitleArtifact}）、
 * 路线 {@code SubtitleRoute}、跨批上下文 {@code SubtitleContext}、术语查证
 * {@code TermLookupCommand} / {@code TermLookupResult} / {@code VerifiedTerm} / {@code VerifiedTerms}；
 * 端口接口 {@code TermLookupProvider}。
 *
 * <p>纯函数领域政策（2026-10-06 业务逻辑下沉 Phase B 落地，无状态、零 I/O，事实由应用层采集、
 * 判据单源在此）：{@code Bcp47Language}（语种政策：resolve 目标语种/源语种、主语言子标签比较、
 * 同语种照抄）、{@code TermVerification}（术语查证政策：触发三条件、official/common/none 记账、
 * 失败词不进产物/全失败不落盘）、{@code CheckpointPolicy}（检查点语义：JSON 非空 = 完成、
 * 0 字节 = 未完成、SRT 是派生视图不参与幂等；上下文缓存-or-摘要且空摘要绝不落盘）。
 */
package com.vidsage.domain.subtitle;
