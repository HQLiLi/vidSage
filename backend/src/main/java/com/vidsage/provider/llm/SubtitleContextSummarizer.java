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
 * {@code ContentSummarizer} bean 上，本类只管发请求、把拿到的主题与术语表打进 DEBUG 日志、兜底。
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
            SubtitleContext context = summarizer.prompt()
                    .user(content)
                    .call()
                    .entity(SubtitleContext.class);
            // 摘要结果是清洗侧唯一的黑盒：主题与术语表都在这一个调用里定形，之后每批提示词都照它拼。
            // 打 DEBUG 是为了复跑时能判定「术语表到底给了什么」——术语替换出错时，究竟该怪摘要层
            // 编了个错写法、还是清洗层越界改了表外的词，只有这条日志分得清。
            // Provider 内部节点用 DEBUG，级别分层见 logging-guidelines。
            log.debug("字幕摘要结果: theme={}, terms={}",
                    context == null ? "" : context.theme(), termsText(context));
            return context;
        } catch (RuntimeException e) {
            log.warn("字幕摘要失败，降级为空上下文继续清洗: reason={}", e.getMessage());
            return SubtitleContext.empty();
        }
    }


    /**
     * 把术语表渲染成一行，只给日志用。
     *
     * <p>逐字段拼，不把 {@code SubtitleContext} 整个塞进日志——契约 record 整体进日志是
     * logging-guidelines 明令不要的写法。
     *
     * @param context 摘要结果，可为 {@code null}
     * @return {@code asr → fixed（note）} 用分号相连；无术语时为「无」
     */
    private static String termsText(SubtitleContext context) {
        if (context == null || context.terms().isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (SubtitleContext.Term t : context.terms()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(t.asr()).append(" → ").append(t.fixed());
            if (t.note() != null && !t.note().isBlank()) {
                sb.append("（").append(t.note()).append("）");
            }
        }
        return sb.toString();
    }
}
