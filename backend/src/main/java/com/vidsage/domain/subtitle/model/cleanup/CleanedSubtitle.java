package com.vidsage.domain.subtitle.model.cleanup;

import java.nio.file.Path;

/**
 * 字幕清洗阶段的产出描述：两条 -clean 产物与它们的元数据，含本趟纠错统计。
 * <b>不直接对外</b>——含 {@code Path} 与枚举，响应走 {@code web/dto/} 展平后再返，不加 {@code @Schema}。
 *
 * @param srtPath        清洗后 SRT 文件路径（{@code clean-subtitles.srt}），已原子落盘
 * @param segmentsPath   清洗后 segments JSON 路径（{@code clean-segments.json}），是检查点
 * @param language       识别出的语种，可能为 {@code null}
 * @param segmentCount   清洗后句数（可能因切分大于原始句数，也可能因空段丢弃更少）
 * @param srtSizeBytes   清洗后 SRT 文件字节数，可为 0
 * @param correctedCount 本趟纠错实际改变文本的段数；断点复用（跳过重跑）时为 0
 * @param retrySteps     本趟纠错触发的校验重试轮数；断点复用时为 0
 * @param termHits       本趟命中术语表的术语数（跨批次累计）；断点复用时为 0
 * @param termApplied    本趟已替换成 fixed 写法的术语数（跨批次累计）；断点复用时为 0
 */
public record CleanedSubtitle(Path srtPath, Path segmentsPath,
                              String language, int segmentCount, long srtSizeBytes,
                              int correctedCount, int retrySteps,
                              int termHits, int termApplied) {
}