package com.vidsage.domain.transcription.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一句转写结果，时间戳用于前端点击引用跳转。
 *
 * @param beginTimeMs 句子开始时间（毫秒）
 * @param endTimeMs   句子结束时间（毫秒）
 * @param text        句子识别文本
 */
@Schema(description = "一句转写结果，时间戳用于前端点击引用跳转")
public record TranscriptionSegment(
        @Schema(description = "句子开始时间（毫秒）", example = "0")
        long beginTimeMs,
        @Schema(description = "句子结束时间（毫秒）", example = "3520")
        long endTimeMs,
        @Schema(description = "句子识别文本", example = "今天天气不错")
        String text) {
}
