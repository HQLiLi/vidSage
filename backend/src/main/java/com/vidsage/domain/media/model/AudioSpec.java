package com.vidsage.domain.media.model;

/**
 * 调用方指定的音频输出规格：本环节不猜测「要什么规格」，一律由调用方传入（例如由 ASR provider
 * 声明它需要 WAV 还是 M4A）。「容器 → 编码器 / 参数」的映射只写在具体抽取实现里。
 *
 * <p><b>字段不变量由紧凑构造器结构性保证</b>：违反契约的取值<b>无法构造出实例</b>，构造即抛
 * {@link IllegalStateException}，使用方无需再做字段校验。选 ISE 而非 IAE 是刻意的：走到构造器
 * 才发现的非法值意味着<b>服务端自己算错了</b>（调用方的非法值该在 HTTP 边界的卫语句就被拦下），
 * 故映射为 500 / ERROR 而非 400 / WARN。
 *
 * @param container       目标容器（WAV / M4A）
 * @param sampleRateHz    采样率（赫兹），如 16000；<b>不要传 0</b>，必须给出明确采样率
 * @param channels        声道数，如 1（单声道）；<b>不要传 0</b>
 * @param bitrateKbps     有损容器的目标码率（kbps），如 48；<b>无损容器传 0 表示不适用</b>
 * @param audioTrackIndex 取第几条音频轨（从 0 起）；多音轨媒体必须显式决定，默认选轨会静默取错轨
 */
public record AudioSpec(AudioContainer container, int sampleRateHz, int channels,
                        int bitrateKbps, int audioTrackIndex) {

    /**
     * 字段不变量（契约级约束，与 ffmpeg 无关）。
     */
    public AudioSpec {
        if (container == null) {
            throw new IllegalStateException("音频容器不能为空：请传 AudioContainer.WAV 或 AudioContainer.M4A");
        }
        if (container == AudioContainer.M4A && bitrateKbps <= 0) {
            // 契约：bitrateKbps 对无损容器传 0 表示不适用；M4A 是有损容器，
            // 传 0 会让 ffmpeg 拿到 -b:a 0k，必须拦下
            throw new IllegalStateException("M4A 是有损容器，码率必须为正数：当前为 " + bitrateKbps
                    + "；请传 AudioSpec.M4A_AAC_48K_MONO，或指定明确码率（如 48）");
        }
        if (sampleRateHz <= 0) {
            throw new IllegalStateException("采样率必须为正数：当前为 " + sampleRateHz
                    + "；请传明确采样率，如 16000");
        }
        if (channels <= 0) {
            throw new IllegalStateException("声道数必须为正数：当前为 " + channels
                    + "；单声道请传 1");
        }
        if (audioTrackIndex < 0) {
            throw new IllegalStateException("音频轨序号不能为负：当前为 " + audioTrackIndex
                    + "；序号从 0 起计");
        }
    }

    /** 本地 FunASR：零解码，模型输入本就是 16k 单声道 wav。 */
    public static final AudioSpec WAV_PCM_16K_MONO = new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 0);

    /** 云端 DashScope：体积优先（wav 的 1/4～1/8），走上传带宽。 */
    public static final AudioSpec M4A_AAC_48K_MONO = new AudioSpec(AudioContainer.M4A, 16000, 1, 48, 0);
}
