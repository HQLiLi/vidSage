package com.vidsage.domain.media.model;

import java.nio.file.Path;

/**
 * 一次媒体文件的观测快照：路径 + 文件系统上的两个读数。
 *
 * @param path         文件路径；{@link #NOT_APPLICABLE} 里为 {@code null}
 * @param sizeBytes    文件大小（字节）
 * @param modifyTimeMs 上次修改时间（毫秒）
 */
public record MediaFileInfo(Path path, long sizeBytes, long modifyTimeMs) {

    /**
     * 读数取不到的占位值：文件不存在或不可读时，大小与修改时间都取它。
     */
    public static final long UNAVAILABLE = -1L;

    /**
     * URL / 上传路线的哨兵：没有文件可观测，path 为 null、数值全 -1，接收方一律忽略。
     */
    public static final MediaFileInfo NOT_APPLICABLE = new MediaFileInfo(null, UNAVAILABLE, UNAVAILABLE);

    /**
     * 三个读数是否都取到了；任一是占位（{@code null} / {@link #UNAVAILABLE}）即为 {@code false}。
     */
    public boolean isAvailable() {
        return path != null && sizeBytes != UNAVAILABLE && modifyTimeMs != UNAVAILABLE;
    }
}
