package com.vidsage.provider.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.model.subtitle.SubtitleContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link SubtitleContextSummarizer} 的纯单元测试：<b>不起 Spring 容器</b>，也不打网络。
 *
 * <p>同 {@code SubtitleCleanserTest} 的手法：真 {@code ChatClient} + 桩 {@code ChatModel}。
 * 重点钉的是<b>降级纪律</b>——摘要只是纠错的增益，任何失败（请求炸、回复不是 JSON）
 * 都得降级成空上下文，不能把清洗带挂。
 */
class SubtitleContextSummarizerTest {

    /** 按剧本回一句的桩。 */
    private static ChatModel scriptedModel(String reply) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    /** 只会炸的桩，模拟网络 / 限流故障。 */
    private static ChatModel throwingModel() {
        return prompt -> {
            throw new IllegalStateException("连接被拒");
        };
    }

    private static SubtitleContextSummarizer summarizerOver(ChatModel model) {
        return new SubtitleContextSummarizer(ChatClient.builder(model).build());
    }

    @Test
    void 正常回复解析出主题与术语() {
        ChatModel model = scriptedModel(
                "{\"theme\": \"中世纪德国剑术\", \"terms\": ["
                        + "{\"asr\": \"亮子纠缠\", \"fixed\": \"量子纠缠\", \"note\": \"物理术语\"}]}");

        SubtitleContext context = summarizerOver(model).summarize("亮子纠缠是什么");

        assertEquals("中世纪德国剑术", context.theme());
        assertEquals(1, context.terms().size());
        assertEquals("亮子纠缠", context.terms().getFirst().asr());
        assertEquals("量子纠缠", context.terms().getFirst().fixed());
        assertEquals("物理术语", context.terms().getFirst().note());
    }

    @Test
    void 请求异常降级为空上下文_不外抛() {
        SubtitleContext context = summarizerOver(throwingModel()).summarize("随便什么内容");

        assertEquals("", context.theme());
        assertTrue(context.terms().isEmpty(), "失败降级应是空术语表");
    }

    @Test
    void 回复不是合法JSON降级为空上下文_不外抛() {
        ChatModel model = scriptedModel("这不是JSON，是模型的一段废话");

        SubtitleContext context = summarizerOver(model).summarize("随便什么内容");

        assertEquals("", context.theme());
        assertTrue(context.terms().isEmpty(), "坏 JSON 降级应是空术语表");
    }
}
