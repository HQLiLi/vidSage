package com.vidsage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 字幕清洗（切分 + 纠错）的调参，前缀 {@code vidsage.subtitle.cleanup}，见 application.yml。
 *
 * @param maxSegmentMs         单句时长预算（毫秒），超过会被切分器切开
 * @param batchSize            一次丢给 LLM 修正的句数（一「批」）
 * @param maxSteps             纠错最多重试轮数（模型掉行 / 改过头就重来，最多重来几次）
 * @param similarityNormal     长句（&gt;10字）的「最小改动」底线（0~1），低于此判「改太狠」→ 重来
 * @param similarityShortText  短句（&lt;=10字）的「最小改动」底线，放宽到更低
 */
@ConfigurationProperties(prefix = "vidsage.subtitle.cleanup")
public record SubtitleCleanupProperties(
        long maxSegmentMs,
        int batchSize,
        int maxSteps,
        double similarityNormal,
        double similarityShortText) {
}