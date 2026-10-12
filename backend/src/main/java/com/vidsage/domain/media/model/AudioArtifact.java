package com.vidsage.domain.media.model;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 音频抽取阶段的产物。
 *
 * @param audioPath 产出音频文件路径。已原子落盘——<b>路径存在即代表文件完整</b>，
 *                  不会出现「非空但被截断」的半成品
 * @param spec      实际使用的规格（原样回传调用方传入的规格）
 * @param sizeBytes 产物字节数，恒 &gt; 0（0 字节视为抽取失败，不会构成本类型）
 */
public record AudioArtifact(Path audioPath, AudioSpec spec, long sizeBytes) {

    /**
     * 音频产物文件名，带<b>完整规格指纹</b>：{@code audio-<采样率>k<声道>ch[-<码率>k]-t<音频轨号>.<扩展名>}，
     * 如 {@code audio-16k1ch48k-t0.m4a}、{@code audio-16k1ch-t1.wav}（无损容器无码率段）。
     *
     * <p><b>为什么必须带完整指纹</b>：若只按容器命名（{@code audio.wav}），同一输入先用 16k 抽一次、
     * 再用 8k（或码率 / 轨号不同）抽一次时，第二次会命中第一次的文件被断点判据当成已完成而跳过，
     * 返回一份<b>与请求规格不符</b>的音频且不报错。指纹因此覆盖<b>所有影响产物内容的参数</b>
     * （采样率、声道、码率、音频轨号），且不给轨号 0 开省略特例——特例就是漏判的入口。
     *
     * <p>命名规则单源在此：改这里的字符串会让已有缓存全部失配，等于把存量音频产物从「已完成」
     * 打回「未完成」。
     *
     * @param spec 已校验的音频规格（容器非空、各数值合法性由 {@link AudioSpec} 的紧凑构造器保证）
     * @return 音频产物文件名（不含目录）
     */
    public static String fileName(AudioSpec spec) {
        StringBuilder name = new StringBuilder("audio-")
                .append(sampleRateKilo(spec.sampleRateHz()))
                .append(spec.channels())
                .append("ch");
        if (spec.container() == AudioContainer.M4A) {
            // 只有有损容器写码率段；无损容器（WAV）契约规定 bitrateKbps = 0 表示不适用
            name.append(spec.bitrateKbps()).append('k');
        }
        name.append("-t").append(spec.audioTrackIndex());
        return name.append('.').append(spec.container().name().toLowerCase(Locale.ROOT)).toString();
    }

    /**
     * 采样率 → 文件名里的 kHz 片段（{@code 16000 → 16k}、{@code 8000 → 8k}）。
     *
     * <p>非整千赫兹保留小数（如 {@code 44100 → 44.1k}）：整数截断会让不同采样率映射到同一名字，
     * 重新引入「按容器命名」那类静默复用漏洞。
     */
    private static String sampleRateKilo(int sampleRateHz) {
        if (sampleRateHz % 1000 == 0) {
            return (sampleRateHz / 1000) + "k";
        }
        return (sampleRateHz / 1000.0) + "k";
    }
}
