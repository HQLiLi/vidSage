package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 保存字幕纠错请求体（PUT /api/tasks/{taskNo}/corrections/{segmentId}）。
 *
 * <p>{@code field} 原样透传给应用层：取值 {@code text} / {@code translation} 的解析与落库
 * 归领域枚举 {@code SubtitleCorrectionField}，本层不抄一份映射，也不引 domain 的类型。
 *
 * @param field 改哪一项：{@code text} = 原文，{@code translation} = 译文
 * @param lang  译文纠错的目标语种短码（如 {@code en}）；{@code field=text} 时可留空
 * @param value 用户改后的文本；保存前会去掉首尾空白，不能为空、最长 2000 字符
 */
@Schema(description = "保存字幕纠错请求体")
public record SaveCorrectionRequest(
        @Schema(description = "改哪一项：text = 原文，translation = 译文",
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"text", "translation"}, example = "text") String field,
        @Schema(description = "译文纠错的目标语种短码；field=text 时可留空", example = "en") String lang,
        @Schema(description = "用户改后的文本；去掉首尾空白后不能为空，最长 2000 字符",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "今天天气不错") String value) {
}
