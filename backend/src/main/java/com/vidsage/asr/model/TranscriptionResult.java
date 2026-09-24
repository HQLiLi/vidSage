package com.vidsage.asr.model;

import java.util.List;

/**
 * 一次转写的完整结果。
 *
 * @param segments 句级分段，时间升序；无分段时为空列表而非 {@code null}
 * @param language 识别出的语种，可能为 {@code null}
 */
public record TranscriptionResult(List<TranscriptionSegment> segments, String language) {
}
