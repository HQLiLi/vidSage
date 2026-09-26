package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 抽音频请求体。
 *
 * @param url             公网媒体页面地址；与 {@code localPath} 只能传一个
 * @param localPath       服务端可读的本地媒体文件路径；与 {@code url} 只能传一个
 * @param audioContainer  音频容器，必填：{@code WAV} 或 {@code M4A}
 * @param audioTrackIndex 音频轨序号（从 0 起）；<b>不传默认 0</b>，多音轨媒体请显式指定，
 *                        否则抽取实现的默认选轨可能静默取错轨
 */
@Schema(description = "抽音频请求体：url 与 localPath 只能传一个，audioContainer 必填")
public record PrepareAudioRequest(
        @Schema(description = "公网媒体页面地址；与 localPath 只能传一个", nullable = true,
                example = "https://www.bilibili.com/video/BV1xx411c7mD")
        String url,
        @Schema(description = "服务端可读的本地媒体文件路径；与 url 只能传一个", nullable = true,
                example = "F:/media/sample.mp4")
        String localPath,
        @Schema(description = "转换后的音频容器，必填", example = "WAV", allowableValues = {"WAV", "M4A"})
        String audioContainer,
        @Schema(description = "音频轨序号（从 0 起）；不传默认 0，多音轨媒体请显式指定",
                nullable = true, example = "0")
        Integer audioTrackIndex) {
}
