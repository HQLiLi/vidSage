package com.vidsage.web.dto;


import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "翻译请求体")
public record TranslationRequest(
        @Schema(description = "源语种", requiredMode = Schema.RequiredMode.REQUIRED) String sourceLanguage,
        @Schema(description = "目标语种", requiredMode = Schema.RequiredMode.REQUIRED) String targetLanguage
) {
}
