package com.vidsage.infrastructure.provider.asr.dashscope;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DashScope 转写配置。application.yml 中的 vidsage.asr.dashscope.* 会覆盖
 * 这里的 @DefaultValue 兜底值；完整配置块已写在 yml 中便于查看。
 *
 * @param apiKey               DashScope API key，生产从环境变量 DASHSCOPE_API_KEY 注入
 * @param model                异步转写模型名
 * @param transcriptionTimeout 一次转写的总时长上限。它管的是「整个转写最多等多久」，
 *                             不是「单次 HTTP 请求等多久」——后者走 spring.http.clients.read-timeout。
 *                             转写是分钟级操作，这个值天然要长得多，所以两者不能合成一个。
 */
@ConfigurationProperties(prefix = "vidsage.asr.dashscope")
public record DashScopeProperties(
        String apiKey,
        @DefaultValue("qwen-audio-3.1-asr-flash-filetrans") String model,
        @DefaultValue("15m") Duration transcriptionTimeout) {
}
