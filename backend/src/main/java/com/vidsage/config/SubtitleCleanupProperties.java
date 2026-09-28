package com.vidsage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 字幕清洗（切分 + 纠错）的调参，前缀 {@code vidsage.subtitle.cleanup}，见 application.yml。
 *
 * @param maxSegmentMs         单句时长预算（毫秒），超过会被切分器切开
 * @param maxBatchChars        一次最多多少字
 * @param batchSize            一次丢给 LLM 修正的句数（一「批」）
 * @param maxSteps             纠错最多重试轮数（模型掉行 / 改过头就重来，最多重来几次）
 * @param similarityNormal     长句（&gt;10字）的「最小改动」底线（0~1），低于此判「改太狠」→ 重来
 * @param similarityShortText  短句（&lt;=10字）的「最小改动」底线，放宽到更低
 * @param historyMaxChars      跨批对话历史的字符上限：把前几批的「批次原文 + 已采纳的纠正结果」作为
 *                             多轮历史带给后续批次，让模型看得见自己前面把专有名词定成了什么写法。
 *                             累计超过就从最老的一轮开始丢。设为 0 或负数即关闭（退回「每批只看自己」）。
 *                             按字符估 token，中文约 1 字 ≈ 1 token。
 *                             <b>缺席时绑定成 0 = 静默关闭</b>，所以 yml 里必须显式写。
 */
@ConfigurationProperties(prefix = "vidsage.subtitle.cleanup")
public record SubtitleCleanupProperties(
        long maxSegmentMs,
        int maxBatchChars,
        int batchSize,
        int maxSteps,
        double similarityNormal,
        double similarityShortText,
        int historyMaxChars) {
}