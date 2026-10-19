package com.vidsage.application.subtitle.query;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import java.nio.file.Path;

/**
 * 一次产物下载的解析结果：要读的文件路径与文件位。
 *
 * @param path 工作区里的产物文件路径
 * @param kind 文件位（决定响应的 Content-Type）
 */
public record ArtifactDownload(Path path, ArtifactFileKind kind) {
}
