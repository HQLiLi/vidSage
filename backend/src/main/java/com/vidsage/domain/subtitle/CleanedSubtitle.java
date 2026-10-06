package com.vidsage.domain.subtitle;

import java.nio.file.Path;

/**
 * 字幕清洗产出描述：两个 -clean 产物路径 + 路线 + 语种 + 句数 + SRT 字节数 + 纠错统计。
 *
 * <p>**本类型不直接对外**：含 {@code Path} 与枚举，HTTP 响应走 {@code web/dto/} 展平后再返（同
 * {@link SubtitleArtifact} 的处理），故**不加 {@code @Schema}**。
 *
 * @param srtPath       清洗后 SRT 文件路径（{@code subtitles-<route>-clean.srt}），已原子落盘
 * @param segmentsPath  清洗后 segments JSON 路径（{@code segments-<route>-clean.json}），是检查点
 * @param route         字幕来源路线
 * @param language      识别出的语种，可能为 {@code null}
 * @param segmentCount  清洗后句数（可能因切分大于原始句数，也可能因空段丢弃更少）
 * @param srtSizeBytes  清洗后 SRT 文件字节数，可为 0
 * @param correctedCount 本趟纠错实际改变文本的段数；断点复用（跳过重跑）时为 0
 * @param retrySteps    本趟纠错触发的校验重试轮数；断点复用时为 0
 * @param termHits      本趟命中术语表的术语数（跨批次累计）；断点复用时为 0
 * @param termApplied   本趟已替换成 fixed 写法的术语数（跨批次累计）；断点复用时为 0
 */
public record CleanedSubtitle(Path srtPath, Path segmentsPath, SubtitleRoute route,
                              String language, int segmentCount, long srtSizeBytes,
                              int correctedCount, int retrySteps,
                              int termHits, int termApplied) {
}