package com.vidsage.provider.llm;

import com.vidsage.model.subtitle.SubtitleContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 字幕摘要器：把整篇字幕文本丢给 LLM，提炼出主题和专有名词术语表，给字幕纠错当跨批上下文用。
 *
 * <p>system prompt（怎么提主题、术语要哪些字段）挂在 {@code ChatClientConfig} 的
 * {@code ContentSummarizer} bean 上，本类只管发请求和兜底。
 *
 * <p>摘要只是纠错的增益，不是前置条件：LLM 报错、返回解析不出 JSON，一律降级成
 * 空上下文继续清洗——宁可没上下文，不能让整个清洗挂掉。
 */
@Slf4j
@Component
public class SubtitleContextSummarizer {

    private final ChatClient summarizer;

    public SubtitleContextSummarizer(@Qualifier("ContentSummarizer") ChatClient summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * 摘要出跨批上下文（主题 + 术语）。
     *
     * @param content 全篇字幕拼接文本
     * @return 主题与术语；摘要失败时为空上下文（主题空串、术语空表），不抛异常
     */
    public SubtitleContext summarize(String content) {
        try {
            return summarizer.prompt()
                    .user(content)
                    .call()
                    .entity(SubtitleContext.class);
        } catch (RuntimeException e) {
            log.warn("字幕摘要失败，降级为空上下文继续清洗: reason={}", e.getMessage());
            return SubtitleContext.empty();
        }
    }
}
