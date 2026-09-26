package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 转写请求体。
 *
 * @param audioUrl      公网可访问的音频 URL；与 audioFilePath 只能传一个
 * @param audioFilePath 服务端可读的本地音频文件路径（云端实现会先上传到临时存储）；与 audioUrl 只能传一个
 * @param language      可选语种提示（如 "zh"）
 */
@Schema(description = "转写请求体：audioUrl 与 audioFilePath 只能传一个")
public record TranscriptionRequest(
        @Schema(description = "公网可访问的音频 URL；与 audioFilePath 只能传一个",
                example = "https://example.com/audio/sample.mp3")
        String audioUrl,
        @Schema(description = "服务端可读的本地音频文件路径（云端实现会先上传到临时存储）；与 audioUrl 只能传一个",
                example = "/data/audio/sample.mp3")
        String audioFilePath,
        @Schema(description = "可选语种提示（如 \"zh\"）", example = "zh")
        String language) {
}
