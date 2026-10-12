package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import java.util.Optional;

/**
 * 字幕摘要的出口：把整篇字幕文本提炼成主题、源语种和专有名词术语表，
 * 给字幕清洗与翻译当跨批上下文用。
 *
 * <p>实现在 {@code infrastructure/provider/llm/}（当前是调 LLM 的
 * {@code LlmSubtitleContextSummarizer}），由 Spring 注进来。
 *
 * <p>摘要只是增益，不是前置条件——失败一律降级为空结果，不抛异常。
 */
public interface SubtitleContextSummarizer {

    /**
     * 摘要出跨批上下文（主题 + 源语种 + 术语）。
     *
     * <p>用 {@link Optional} 而不是「失败返回空上下文」：调用方要靠「成不成功」决定落不落盘——
     * 一旦把空上下文落盘，下次会命中这份空缓存，摘要就永久丢了，而且不报错。
     *
     * <p><b>关键词为什么进摘要器</b>（design 决策 2）：用户手填的名字 ASR 几乎必然转错，而摘要器
     * 能顺着上下文推算出「这里该是哪个专有名词」，从而产出 {@code asr → fixed} 的术语条目。
     * 走这一条通道，关键词引发的替换就能吃到清洗阶段既有的<b>相似度基线兜底</b>
     * （{@code baselineOf}）——否则长句里被换掉的专有名词会被「改太狠」守卫判失败，而那条守卫
     * 无从知道这是合法替换。<b>光靠它不够</b>：摘要失败会降级成空上下文且不落盘，
     * 那时用户亲手填的关键词一个字都没生效却毫无提示，所以清洗提示词里另外还有一条独立通道
     * （见 {@link SubtitleCleanser} 的 keywords 参数）。
     *
     * @param content  全篇字幕拼接文本
     * @param keywords 用户手填的关键词；没有就传空清单，不要传 {@code null}
     * @return 摘要结果；LLM 报错或回复解析不出 JSON 时为空 {@link Optional}，不抛异常
     */
    Optional<SubtitleContext> summarize(String content, SubtitleKeywords keywords);
}
