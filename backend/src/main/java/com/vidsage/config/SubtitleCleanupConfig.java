package com.vidsage.config;

import com.vidsage.utils.subtitle.SubtitleCorrector;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 字幕清洗的装配：把 {@link SubtitleCorrector.Llm} 接上 spring-ai 的 {@link ChatModel} bean。
 *
 * <p><b>为什么在这里、不直接注 {@code ChatModel} 进服务</b>：{@code service/} 依赖 spring-ai 的类型
 * 会破「SDK 类型不外泄」的纪律（见 directory-structure / provider-pattern）。{@code ChatModel} 只在
 * 这个装配角落出现一次，服务只依赖我们自己的 {@code SubtitleCorrector.Llm} 接口。
 * proxyBeanMethods = false: 每次都调用都实例化一个对象
 * proxyBeanMethods = true: 调用时返回同一个实例对象
 * 
 */
@Configuration(proxyBeanMethods = false)
public class SubtitleCleanupConfig {

    /**
     * 暴露一个 {@code SubtitleCorrector.Llm} 给 {@code SubtitleCleanupService} 注入。
     *
     * <p>用 {@link ChatModel#call(org.springframework.ai.chat.messages.Message...)} 默认方法最省：
     * 直接传 system + user 两段消息、返回纯文本，不必碰 {@code ChatResponse}/{@code content()}。
     *
     * @param chatModel spring-ai 模型 bean（deepseek starter 自动装配）
     * @return 字幕纠错的 LLM 调用口
     */
    @Bean
    SubtitleCorrector.Llm subtitleLlm(ChatModel chatModel) {
        return (system, user) -> chatModel.call(
                new SystemMessage(system),
                new UserMessage(user));
    }
}