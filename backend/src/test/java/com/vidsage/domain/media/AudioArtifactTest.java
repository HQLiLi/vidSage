package com.vidsage.domain.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AudioArtifact#fileName} 规格指纹命名的纯单元测试（自 AudioPreparationServiceTest 随命名
 * 规则迁入，断言值不变）：指纹覆盖所有影响产物内容的参数，不同规格不得映射到同一文件名。
 */
class AudioArtifactTest {

    @Test
    void namesAudioFileWithFullSpecFingerprint() {
        // 有损容器（M4A）带码率段；无损容器（WAV）不带码率段
        assertEquals("audio-16k1ch48k-t0.m4a",
                AudioArtifact.fileName(AudioSpec.M4A_AAC_48K_MONO));
        assertEquals("audio-16k1ch-t0.wav",
                AudioArtifact.fileName(AudioSpec.WAV_PCM_16K_MONO));
        // 轨号 0 也照写 -t0（不做省略特例）；-t 始终带连字符，与 M4A 的 -t0 保持同一形状
        assertEquals("audio-16k1ch-t1.wav",
                AudioArtifact.fileName(new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 1)));
        // 非整千赫兹保留小数，避免与其它采样率截断成同一名字
        assertEquals("audio-44.1k1ch-t0.wav",
                AudioArtifact.fileName(new AudioSpec(AudioContainer.WAV, 44100, 1, 0, 0)));
    }

    @Test
    void differentSpecsMapToDifferentFileNames() {
        // 改 1 的回归：同一输入、仅轨号（或采样率）不同的 spec，不得映射到同一文件，
        // 否则第二次会命中第一次的产物被断点复用跳过，静默返回与请求规格不符的音频。
        List<String> names = List.of(
                AudioArtifact.fileName(new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 0)),
                AudioArtifact.fileName(new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 1)),
                AudioArtifact.fileName(new AudioSpec(AudioContainer.WAV, 8000, 1, 0, 0)));

        assertEquals("audio-16k1ch-t0.wav", names.get(0));
        assertEquals("audio-16k1ch-t1.wav", names.get(1));
        assertEquals("audio-8k1ch-t0.wav", names.get(2));
        assertEquals(3, names.stream().distinct().count(),
                () -> "不同规格必须映射到不同文件，否则断点复用会静默跳过：" + names);
    }
}
