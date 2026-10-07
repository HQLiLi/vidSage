package com.vidsage.infrastructure.provider.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link LlmSubtitleContextSummarizer} 的纯单元测试：<b>不起 Spring 容器</b>，也不打网络。
 *
 * <p>同 {@code SubtitleCleanserTest} 的手法：真 {@code ChatClient} + 桩 {@code ChatModel}。
 * 重点钉的是<b>成功与降级要分得开</b>——摘要只是增益，失败（请求炸、回复不是 JSON）要返回空
 * {@link Optional}，让调用方知道别把这次结果落盘（空缓存一落盘，摘要就永久丢了）。
 */
class LlmSubtitleContextSummarizerTest {

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

    private static LlmSubtitleContextSummarizer summarizerOver(ChatModel model) {
        return new LlmSubtitleContextSummarizer(ChatClient.builder(model).build());
    }

    @Test
    void 正常回复解析出主题语种与术语() {
        ChatModel model = scriptedModel(
                "{\"theme\": \"中世纪德国剑术\", \"sourceLanguage\": \"zh\", \"terms\": ["
                        + "{\"asr\": \"亮子纠缠\", \"fixed\": \"量子纠缠\", \"note\": \"物理术语\"}]}");

        SubtitleContext context = summarizerOver(model).summarize("亮子纠缠是什么").orElseThrow();

        assertEquals("中世纪德国剑术", context.theme());
        assertEquals("zh", context.sourceLanguage());
        assertEquals(1, context.terms().size());
        assertEquals("亮子纠缠", context.terms().getFirst().asr());
        assertEquals("量子纠缠", context.terms().getFirst().fixed());
        assertEquals("物理术语", context.terms().getFirst().note());
    }

    @Test
    void 请求异常返回空Optional_不外抛() {
        Optional<SubtitleContext> context = summarizerOver(throwingModel()).summarize("随便什么内容");

        assertTrue(context.isEmpty(), "请求失败该返回空 Optional，交给调用方降级");
    }

    @Test
    void 回复不是合法JSON返回空Optional_不外抛() {
        ChatModel model = scriptedModel("这不是JSON，是模型的一段废话");

        Optional<SubtitleContext> context = summarizerOver(model).summarize("随便什么内容");

        assertTrue(context.isEmpty(), "坏 JSON 该返回空 Optional，交给调用方降级");
    }
}
