package com.vidsage.domain.media.model;

/**
 * 音频输出容器。编码器与 ffmpeg 参数由抽取实现自行映射，不进本类型。
 */
public enum AudioContainer {

    /** WAV（PCM 无损）。零解码，适合同机本地推理。 */
    WAV,

    /** M4A（AAC 有损）。体积小，适合需要上传的云端接口。 */
    M4A
}
