package com.vidsage.model.asr;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 一次转写的完整结果。
 *
 * @param segments 句级分段，时间升序；无分段时为空列表而非 {@code null}
 * @param language 识别出的语种，可能为 {@code null}
 */
@Schema(description = "一次转写的完整结果")
public record TranscriptionResult(
        @Schema(description = "句级分段，时间升序；无分段时为空列表而非 null")
        List<TranscriptionSegment> segments,
        @Schema(description = "识别出的语种，可能为 null", example = "zh", nullable = true)
        String language) {
}
