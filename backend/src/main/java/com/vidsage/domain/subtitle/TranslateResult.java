package com.vidsage.domain.subtitle;

import java.util.List;

/**
 * 一趟字幕翻译的产出：逐条双语段落，外带重试轮数与回退段数。
 *
 * @param segments          逐条双语段落，时间轴与输入逐条一致
 * @param retrySteps        本趟触发的校验重试轮数
 * @param untranslatedCount 本趟回退原文（用尽重试仍没拿到可用译文）的段数；跨批次累计
 */
public record TranslateResult(List<TranslatedSegment> segments,
                              int retrySteps,
                              int untranslatedCount) {
}
