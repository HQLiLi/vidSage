package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import java.util.Optional;

/**
 * 字幕摘要的出口：把整篇字幕文本提炼成主题、源语种和专有名词术语表，
 * 给字幕清洗与翻译当跨批上下文用。
 *
 * <p>摘要只是增益，不是前置条件——失败一律降级为空结果，不抛异常。
 */
public interface SubtitleContextSummarizer {

    /**
     * 摘要出跨批上下文（主题 + 源语种 + 术语）。
     *
     * <p><b>关键词为什么进摘要器</b>：摘要器能顺着上下文推出「这里该是哪个专有名词」，产出
     * {@code asr → fixed} 的术语条目；只有走这条通道，关键词引发的替换才吃得到清洗阶段既有的
     * <b>相似度基线兜底</b>——只走提示词通道的话，长句里被换掉的专有名词会被「改太狠」守卫判失败。
     * <b>但光靠它不够</b>：摘要失败会降级成空上下文且不落盘，用户亲手填的关键词就一个字都没生效
     * 且毫无提示，所以清洗提示词里另有一条独立通道（见 {@link SubtitleCleanser} 的 keywords）。
     *
     * @param content  全篇字幕拼接文本
     * @param keywords 用户手填的关键词；没有就传空清单，不要传 {@code null}
     * @return 摘要结果；失败一律为空 {@link Optional} 而不抛异常。空结果绝不能当成功落盘
     *         （见 {@link CheckpointPolicy} 的上下文政策）
     */
    Optional<SubtitleContext> summarize(String content, SubtitleKeywords keywords);
}
