package com.vidsage.domain.subtitle.model.cleanup;

import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.List;

/**
 * 一趟字幕纠错的产出：清洗后的字幕，外带几个用来观察「这趟干了多少事」的计数。
 *
 * <p>计数只用于日志与验收观察，不参与任何判定。
 *
 * @param segments       清洗后的列表
 * @param correctedCount 本趟实际改变文本的段数
 * @param retrySteps     本趟触发的校验重试轮数
 * @param termHits       本趟命中（渲染进术语参考）的术语数，跨批次累计
 * @param termApplied    本趟已替换的术语数，跨批次累计
 */
public record CleanseResult(List<TranscriptionSegment> segments,
                            int correctedCount,
                            int retrySteps,
                            int termHits,
                            int termApplied) {
}
