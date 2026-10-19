package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 产物清单里的一项文件：下载地址与字节数。
 *
 * @param url       下载地址（相对本站，直接 GET 即可拿到文件字节）
 * @param sizeBytes 文件当前字节数
 */
@Schema(description = "产物文件")
public record ArtifactFileResponse(
        @Schema(description = "下载地址（相对本站）",
                example = "/api/tasks/3f2a9c1e7b5d4a6089abcdef01234567/artifacts/CLEANED/segments.json")
        String url,
        @Schema(description = "文件当前字节数", example = "10240") long sizeBytes) {
}
