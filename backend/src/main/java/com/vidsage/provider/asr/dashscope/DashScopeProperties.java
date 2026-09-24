package com.vidsage.provider.asr.dashscope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DashScope 转写配置。application.yml 中的 vidsage.asr.dashscope.* 会覆盖
 * 这里的 @DefaultValue 兜底值；完整配置块已写在 yml 中便于查看。
 * 提交与轮询由官方 SDK 内部处理，这里只关心鉴权与模型选择。
 *
 * @param apiKey DashScope API key，生产从环境变量 DASHSCOPE_API_KEY 注入
 * @param model  异步转写模型名
 */
@ConfigurationProperties(prefix = "vidsage.asr.dashscope")
public record DashScopeProperties(
        String apiKey,
        @DefaultValue("qwen-audio-3.1-asr-flash-filetrans") String model) {
}
