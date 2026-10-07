package com.vidsage.domain.subtitle.model.common;

import java.nio.file.Path;

/**
 * 字幕产出描述：两个产物路径 + 路线 + 语种 + 句数 + SRT 字节数。
 *
 * <p>**本类型不直接对外**：含 {@code Path} 与枚举，HTTP 响应走 {@code web/dto/} 展平后再返
 * （见设计 §七），故**不加 {@code @Schema}**——与 {@code domain/media} 加 {@code @Schema} 的
 * 类型不同，那些会直接序列化成响应，这里不会。
 *
 * @param srtPath       SRT 字幕文件路径。已原子落盘——路径存在即代表文件完整。空转写时是
 *                      0 字节文件（合法产出，见设计决策 5）
 * @param segmentsPath  段落 JSON 文件路径（{@code segments-<路线>.json}），此文件是检查点
 * @param route         字幕来源路线
 * @param language      识别出的语种，可能为 {@code null}
 * @param segmentCount  句数；0 表示空转写
 * @param srtSizeBytes  SRT 文件字节数，可为 0（空转写）
 */
public record SubtitleArtifact(Path srtPath, Path segmentsPath, SubtitleRoute route,
                               String language, int segmentCount, long srtSizeBytes) {
}