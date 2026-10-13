package com.vidsage.infrastructure.provider.llm.termlookup.dashscope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DashScope 术语查证的调参，前缀 {@code vidsage.term-lookup.dashscope}，见 application.yml。
 *
 * <p>key 复用 ASR 的 {@code DASHSCOPE_API_KEY}（百炼平台一个 key 通吃文本模型，零新增账号）。
 *
 * @param apiKey         DashScope API key；缺席时查证调用抛 {@link IllegalStateException}，由上层降级
 * @param model          查证用的模型（默认随 yml：qwen3.7-flash）
 * @param baseUrl        OpenAI 兼容端点根。**必须走兼容端点自拼 JSON**：DashScope SDK 因 Jackson
 *                       同坐标冲突在本项目被禁用（pom 有实测记录），且官方文档证实兼容客户端层
 *                       可能不转发 {@code enable_search} 这类扩展参数
 * @param searchStrategy 检索策略；standard = 非流式可用，agent = 强制流式（冒烟实测）
 * @param temperature    采样温度。查证是「从检索结果里做确定性抽取」，不是创作：默认温度下同一词
 *                       逐轮会得出不同结论（实测「只狼」一轮 Sekiro 一轮 Sengoku），压到近 0 换取
 *                       结论稳定；残余抖动由「删产物重查 + 人工审 note」兜底
 */
@ConfigurationProperties(prefix = "vidsage.term-lookup.dashscope")
public record DashScopeTermLookupProperties(
        String apiKey,
        @DefaultValue("qwen3.7-flash") String model,
        @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1") String baseUrl,
        @DefaultValue("standard") String searchStrategy,
        @DefaultValue("0.1") double temperature) {
}
