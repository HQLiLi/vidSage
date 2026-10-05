package com.vidsage.domain.media;

import java.nio.file.Path;

/**
 * 音频抽取阶段的产物。
 *
 * @param audioPath 产出音频文件路径。已原子落盘——<b>路径存在即代表文件完整</b>，
 *                  不会出现「非空但被截断」的半成品
 * @param spec      实际使用的规格（原样回传调用方传入的规格）
 * @param sizeBytes 产物字节数，恒 &gt; 0（0 字节视为抽取失败，不会构成本类型）
 */
public record AudioArtifact(Path audioPath, AudioSpec spec, long sizeBytes) {
}
