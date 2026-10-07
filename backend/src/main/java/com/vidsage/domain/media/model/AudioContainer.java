package com.vidsage.domain.media.model;

/**
 * 音频容器大类。契约只暴露「容器类型」，具体编码器与参数由各抽取实现自行映射，
 * 不在契约里出现 ffmpeg 词汇。
 */
public enum AudioContainer {

    /** WAV（PCM 无损）。零解码，适合同机本地推理。 */
    WAV,

    /** M4A（AAC 有损）。体积小，适合需要上传的云端接口。 */
    M4A
}
