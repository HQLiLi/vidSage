package com.vidsage.infrastructure.provider.llm;

import com.vidsage.domain.subtitle.SubtitleContext;
import com.vidsage.domain.subtitle.SubtitleContextSummarizer;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 字幕摘要器：把整篇字幕文本丢给 LLM，提炼出主题、源语种和专有名词术语表，
 * 给字幕清洗与翻译当跨批上下文用。
 *
 * <p>system prompt（怎么提主题、语种怎么给、术语要哪些字段）挂在 {@code ChatClientConfig} 的
 * {@code ContentSummarizer} bean 上，本类只管发请求、把拿到的东西打进 DEBUG 日志、兜底。
 *
 * <p>摘要只是增益，不是前置条件：LLM 报错、返回解析不出 JSON，一律返回空 {@link Optional}
 * 让调用方降级——宁可没上下文，不能让整个清洗挂掉。
 *
 * <p><b>只碰计算、不碰 I/O</b>：上下文要不要落盘、落哪个文件、断点怎么判，全在 service 层。
 * 本类不读也不写任何产物文件（见 design §3.2）。
 *
 * <p>实现领域端口 {@link SubtitleContextSummarizer}——上层只认那个接口，不认识本类。
 */
@Slf4j
@Component
public class LlmSubtitleContextSummarizer implements SubtitleContextSummarizer {

    private final ChatClient summarizer;

    public LlmSubtitleContextSummarizer(@Qualifier("ContentSummarizer") ChatClient summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * 摘要出跨批上下文（主题 + 源语种 + 术语）。
     *
     * <p><b>为什么用 Optional 而不是「失败返回空上下文」</b>：调用方要靠「成不成功」决定落不落盘。
     * 降级结果绝不能写进缓存文件——一旦把空上下文落盘，下次会命中这份空缓存，摘要就永久丢了，
     * 而且不报错。所以成功才 {@code present}，失败返回空 {@link Optional}。
     *
     * @param content 全篇字幕拼接文本
     * @return 摘要结果；LLM 报错或回复解析不出 JSON 时为空 {@link Optional}，不抛异常
     */
    public Optional<SubtitleContext> summarize(String content) {
        try {
            SubtitleContext context = summarizer.prompt()
                    .user(content)
                    .call()
                    .entity(SubtitleContext.class);
            // 摘要结果是清洗与翻译共用的黑盒：主题、源语种、术语表都在这一个调用里定形，之后每批提示词都照它拼。
            // 打 DEBUG 是为了复跑时能判定「到底给了什么」——术语替换出错时，究竟该怪摘要层
            // 编了个错写法、还是清洗层越界改了表外的词，只有这条日志分得清。
            // Provider 内部节点用 DEBUG，级别分层见 logging-guidelines。
            log.debug("字幕摘要结果: theme={}, sourceLanguage={}, terms={}",
                    context == null ? "" : context.theme(),
                    context == null ? "" : context.sourceLanguage(),
                    termsText(context));
            return Optional.ofNullable(context);
        } catch (RuntimeException e) {
            log.warn("字幕摘要失败，降级为空上下文继续清洗: reason={}", e.getMessage());
            return Optional.empty();
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
