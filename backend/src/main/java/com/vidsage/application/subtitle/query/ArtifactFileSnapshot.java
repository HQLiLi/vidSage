package com.vidsage.application.subtitle.query;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;

/**
 * 产物清单里的一项文件：文件位与字节数。
 *
 * @param kind      文件位（决定清单里的键与下载时的文件名段）
 * @param sizeBytes 文件当前字节数
 */
public record ArtifactFileSnapshot(ArtifactFileKind kind, long sizeBytes) {
}
