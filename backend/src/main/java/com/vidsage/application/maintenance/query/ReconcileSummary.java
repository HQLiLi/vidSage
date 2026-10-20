package com.vidsage.application.maintenance.query;

/**
 * 对账扫到的工作区目录数与普通文件总数。
 *
 * <p>这两个数是<b>给验收用的实测值</b>，不是产品指标。
 *
 * @param dirs  工作区根下的一层子目录数
 * @param files 这些目录下的普通文件总数（含 {@code .tmp} / {@code .part} / {@code .ytdl} 这类过渡态）
 */
public record ReconcileSummary(int dirs, int files) {
}
