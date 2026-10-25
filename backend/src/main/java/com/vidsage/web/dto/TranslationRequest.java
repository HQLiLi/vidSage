package com.vidsage.web.dto;


import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 翻译请求体。
 *
 * <p><b>{@code force} 用包装类型 {@code Boolean} 而不是 {@code boolean}</b>：Jackson 3 绑 record 时，
 * 请求体里缺这个字段会把 {@code null} 塞进构造器参数，primitive 会直接报「Cannot map null into type
 * boolean」变成 400。缺省语义（不带 = false）由 Controller 的 {@code Boolean.TRUE.equals(...)} 落实。
 */
@Schema(description = "翻译请求体")
public record TranslationRequest(
        @Schema(description = "源语种", requiredMode = Schema.RequiredMode.REQUIRED) String sourceLanguage,
        @Schema(description = "目标语种", requiredMode = Schema.RequiredMode.REQUIRED) String targetLanguage,
        @Schema(description = "是否强制重跑：该语种已有完好译文时默认跳过，true 则重新翻译并换版；"
                + "请求体不带这个字段等同于 false",
                defaultValue = "false") Boolean force
) {
}
