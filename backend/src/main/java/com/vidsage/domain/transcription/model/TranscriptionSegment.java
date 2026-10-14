package com.vidsage.domain.transcription.model;

/**
 * 一句转写结果，时间戳用于前端点击引用跳转。
 *
 * @param beginTimeMs 句子开始时间（毫秒） 如：0
 * @param endTimeMs   句子结束时间（毫秒） 如：3520
 * @param text        句子识别文本 如：今天天气不错
 */
public record TranscriptionSegment(long beginTimeMs, long endTimeMs, String text) { }
