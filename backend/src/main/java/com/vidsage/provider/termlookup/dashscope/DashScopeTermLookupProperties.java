package com.vidsage.provider.termlookup.dashscope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DashScope 术语查证的调参，前缀 {@code vidsage.term-lookup.dashscope}，见 application.yml。
 *
 * <p>key 复用 ASR 的 {@code DASHSCOPE_API_KEY}（百炼平台一个 key 通吃文本模型，零新增账号）。
 *
 * @param apiKey         DashScope API key；缺席时查证调用抛 {@link IllegalStateException}，由上层降级
 * @param model          查证用的模型。冒烟实测（2026-09-29）：qwen-flash + standard 检索策略
 *                       非流式可用；Omni 系列虽支持搜索但强制 agent 策略 → 思考模式 → 必须流式，故不用
 * @param baseUrl        OpenAI 兼容端点根。**必须走兼容端点自拼 JSON**：DashScope SDK 因 Jackson
 *                       同坐标冲突在本项目被禁用（pom 有实测记录），且官方文档证实兼容客户端层
 *                       可能不转发 {@code enable_search} 这类扩展参数
 * @param searchStrategy 检索策略；standard = 非流式可用，agent = 强制流式（冒烟实测）
 */
@ConfigurationProperties(prefix = "vidsage.term-lookup.dashscope")
public record DashScopeTermLookupProperties(
        String apiKey,
        @DefaultValue("qwen-flash") String model,
        @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1") String baseUrl,
        @DefaultValue("standard") String searchStrategy) {
}
