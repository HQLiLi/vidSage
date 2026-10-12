package com.vidsage.domain.media.port;

import com.vidsage.domain.media.model.AudioArtifact;
import com.vidsage.domain.media.model.AudioSpec;
import java.nio.file.Path;

/**
 * 音频抽取抽象：把「一个媒体文件」按调用方指定的规格抽成音频文件。
 *
 * <p>输出路径由调用方给定——实现不猜文件名、不决定落盘位置。产物<b>原子落盘</b>，
 * 所以路径一旦存在即代表文件完整，不会出现「非空但被截断」的半成品。
 */
public interface AudioExtractor {

    /**
     * 从媒体文件中抽取音频并原子落盘到 {@code outputPath}。
     *
     * @param mediaPath  输入媒体文件路径（本地已存在的视频或音频文件）
     * @param outputPath 目标音频文件路径；<b>父目录须已存在且可写</b>，文件本身不应已存在
     * @param spec       输出规格（容器、采样率、声道、码率、音频轨），由调用方指定；
     *                   多音轨媒体必须由调用方显式给出要取的轨，不要依赖实现的默认选轨
     * @return 音频产物（路径 + 规格 + 字节数），{@code sizeBytes} 恒 &gt; 0
     * @throws IllegalArgumentException 入参不合法（媒体不存在/不可读、输出目录不可写、规格为空或字段非法）
     * @throws IllegalStateException    配置缺失（找不到 ffmpeg）、<b>目标产物已存在</b>（本接口不覆盖已有产物，
     *                                 上位调用方应先判存在即跳过）或外部命令未按预期完成（非零退出、超时、产物为空）
     */
    AudioArtifact extract(Path mediaPath, Path outputPath, AudioSpec spec);
}
