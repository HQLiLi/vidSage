package com.vidsage.domain.media.model;

import java.nio.file.Path;

/**
 * 媒体信息
 *
 * @param sizeBytes    文件大小
 * @param modifyTimeMs 上次修改时间
 */
public record MediaFileInfo(Path path, long sizeBytes, long modifyTimeMs) {
    public static final long UNAVAILABLE = -1L;
    /**
     * URL / 上传路线的哨兵：没有文件可观测，path 为 null、数值全 -1，接收方一律忽略。
     */
    public static final MediaFileInfo NOT_APPLICABLE = new MediaFileInfo(null, UNAVAILABLE, UNAVAILABLE);

    public boolean isAvailable() {
        return path != null && sizeBytes != UNAVAILABLE && modifyTimeMs != UNAVAILABLE;
    }
}
