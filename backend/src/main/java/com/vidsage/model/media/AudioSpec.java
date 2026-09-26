package com.vidsage.model.media;

/**
 * 调用方指定的音频输出规格。本环节不猜测「要什么规格」，一律由调用方传入
 * （例如由 ASR provider 声明它需要 WAV 还是 M4A）。
 *
 * <p>字段只描述「我要什么音频」，不含任何 ffmpeg 参数名与编码器名；
 * 「容器 → 编码器/参数」的映射只写在具体抽取实现里。
 *
 * @param container       目标容器（WAV / M4A）
 * @param sampleRateHz    采样率（赫兹），如 16000；<b>不要传 0</b>，必须给出明确采样率
 * @param channels        声道数，如 1（单声道）；<b>不要传 0</b>
 * @param bitrateKbps     有损容器的目标码率（kbps），如 48；<b>无损容器传 0 表示不适用</b>
 * @param audioTrackIndex 取第几条音频轨（从 0 起）。<b>多音轨媒体必须由调用方显式决定</b>——
 *                        抽取实现的默认选轨跟随元数据 disposition，可能静默取错轨
 */
public record AudioSpec(AudioContainer container, int sampleRateHz, int channels,
                        int bitrateKbps, int audioTrackIndex) {

    /** 本地 FunASR：零解码，模型输入本就是 16k 单声道 wav。 */
    public static final AudioSpec WAV_PCM_16K_MONO = new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 0);

    /** 云端 DashScope：体积优先（wav 的 1/4～1/8），走上传带宽。 */
    public static final AudioSpec M4A_AAC_48K_MONO = new AudioSpec(AudioContainer.M4A, 16000, 1, 48, 0);
}
