package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 产字幕请求体。
 *
 * <p>与 {@code PrepareAudioRequest} 同形：{@code url} 与 {@code localPath} 只能传一个。
 * 不含音频规格参数——音频规格由 provider 自述（见 {@code model/media/AudioSpec} 与
 * {@code provider/asr} 的 {@code requiredAudioSpec()}），此端点定位是「一键出字幕」，
 * 不重复暴露容器 / 采样率 / 音频轨等细节。
 *
 * @param url       公网媒体页面地址；与 {@code localPath} 只能传一个
 * @param localPath 服务端可读的本地媒体文件路径；与 {@code url} 只能传一个
 */
@Schema(description = "产字幕请求体：url 与 localPath 只能传一个，音频规格由 ASR provider 自述")
public record PrepareSubtitleRequest(
        @Schema(description = "公网媒体页面地址；与 localPath 只能传一个", nullable = true,
                example = "https://www.bilibili.com/video/BV1xx411c7mD")
        String url,
        @Schema(description = "服务端可读的本地媒体文件路径；与 url 只能传一个", nullable = true,
                example = "F:/media/sample.mp4")
        String localPath) {
}