/**
 * 媒体获取与音频上下文：{@code MediaInput}（sealed 输入模型，含内嵌 record 变体）、
 * 获取产物 {@code AcquiredMedia}、音频工件与规格 {@code AudioArtifact} / {@code AudioContainer}
 * / {@code AudioSpec}；端口接口 {@code MediaAcquirer}（获取媒体）与 {@code AudioExtractor}
 * （抽取音频），由 infrastructure/provider 的下载与 ffmpeg 适配器实现。
 */
package com.vidsage.domain.media;
