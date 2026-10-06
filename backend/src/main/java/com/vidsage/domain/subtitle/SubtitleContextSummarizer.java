package com.vidsage.domain.subtitle;

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
     * @param content 全篇字幕拼接文本
     * @return 摘要结果；LLM 报错或回复解析不出 JSON 时为空 {@link Optional}，不抛异常
     */
    Optional<SubtitleContext> summarize(String content);
}
