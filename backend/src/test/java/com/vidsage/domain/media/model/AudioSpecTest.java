package com.vidsage.domain.media.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link AudioSpec} 的构造期不变量：违反契约的取值<b>无法构造出实例</b>。
 *
 * <p><b>本测试类是两个用例的归宿</b>：原 {@code FfmpegAudioExtractorTest.rejectsInvalidSpecFields}
 * 与 {@code rejectsZeroBitrateForLossyContainer}。校验从抽取实现搬进紧凑构造器后，非法值在
 * <b>构造那一刻</b>就抛，测不到 extractor 了，故整类改测构造器本身——覆盖面相同，归属更准。
 *
 * <p>异常类型是 {@link IllegalStateException} 而非 {@code IllegalArgumentException}，这是刻意的：
 * 走到构造器才发现的非法值意味着服务端自己算错了（调用方给的非法值应在 HTTP 边界被卫语句拦下，
 * 见 {@code MediaController.toAudioSpec}），故映射为 500 / ERROR 而非 400 / WARN。
 */
class AudioSpecTest {

    @Test
    void rejectsNullContainer() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AudioSpec(null, 16000, 1, 0, 0));

        assertTrue(error.getMessage().contains("音频容器不能为空"),
                () -> "应指出容器缺失：" + error.getMessage());
    }

    @Test
    void rejectsZeroBitrateForLossyContainer() {
        // 无损容器（WAV）的 bitrateKbps = 0 是契约规定的不适用值，不该被拦；
        // 只有有损容器（M4A）传 0 才是非法——否则 ffmpeg 会拿到 -b:a 0k
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AudioSpec(AudioContainer.M4A, 16000, 1, 0, 0));

        // 断言移植自原 FfmpegAudioExtractorTest.rejectsZeroBitrateForLossyContainer
        assertTrue(error.getMessage().contains("AudioSpec.M4A_AAC_48K_MONO"),
                () -> "应给出 M4A 预置常量名：" + error.getMessage());
    }

    @Test
    void rejectsNonPositiveSampleRate() {
        // 边界值 0：契约要求「必须给出明确采样率」
        assertThrows(IllegalStateException.class,
                () -> new AudioSpec(AudioContainer.WAV, 0, 1, 0, 0));
    }

    @Test
    void rejectsNonPositiveChannels() {
        // 边界值 0
        assertThrows(IllegalStateException.class,
                () -> new AudioSpec(AudioContainer.WAV, 16000, 0, 0, 0));
    }

    @Test
    void rejectsNegativeAudioTrackIndex() {
        // 边界值 -1：序号从 0 起计，负值非法
        assertThrows(IllegalStateException.class,
                () -> new AudioSpec(AudioContainer.WAV, 16000, 1, 0, -1));
    }

    @Test
    void constructsLocalAsrPreset() {
        // 正例：预置常量必须构造成功，且字段与契约注释一致。
        // 它同时覆盖「WAV 的 bitrateKbps = 0 不触发有损容器判据」这一边界。
        assertEquals(AudioContainer.WAV, AudioSpec.WAV_PCM_16K_MONO.container());
        assertEquals(16000, AudioSpec.WAV_PCM_16K_MONO.sampleRateHz());
        assertEquals(1, AudioSpec.WAV_PCM_16K_MONO.channels());
        assertEquals(0, AudioSpec.WAV_PCM_16K_MONO.bitrateKbps());
        assertEquals(0, AudioSpec.WAV_PCM_16K_MONO.audioTrackIndex());
    }

    @Test
    void constructsCloudAsrPreset() {
        // 正例：有损容器带正码率，必须通过
        assertEquals(AudioContainer.M4A, AudioSpec.M4A_AAC_48K_MONO.container());
        assertEquals(16000, AudioSpec.M4A_AAC_48K_MONO.sampleRateHz());
        assertEquals(1, AudioSpec.M4A_AAC_48K_MONO.channels());
        assertEquals(48, AudioSpec.M4A_AAC_48K_MONO.bitrateKbps());
        assertEquals(0, AudioSpec.M4A_AAC_48K_MONO.audioTrackIndex());
    }
}
