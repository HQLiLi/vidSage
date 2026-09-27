package com.vidsage.utils.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * 产物落盘的公共助手：读文件大小、原子写盘、原子改名、静默清理。
 *
 * <p>全静态、无状态，构造器私有，不要 new 它。
 *
 * <p><b>两套原子落盘是有意保留的，别合并</b>：
 * <ul>
 *   <li>{@link #writeAtomically} 用 {@code .tmp} + {@code REPLACE_EXISTING}——字节由我们自己写；</li>
 *   <li>{@link #moveAtomically} 用 {@code .part} + {@code ATOMIC_MOVE}——ffmpeg 自己写完了，我们只做改名。</li>
 * </ul>
 * 两者机制不同，合成一个会丢掉 {@code ATOMIC_MOVE} 的语义。
 */
@Slf4j
public final class ArtifactFiles {


    private ArtifactFiles() {
    }

    /**
     * 文件大小；不存在、不是普通文件或读取失败时返回 0。
     *
     * <p>先判 {@code isRegularFile} 再读大小：目录在部分文件系统上也有「大小」（ext4 上是 4096），
     * 不挡的话目录会冒充成合法产物。
     */
    public static long sizeOrZero(Path path) {
        try {
            return Files.isRegularFile(path) ? Math.max(Files.size(path), 0L) : 0L;
        } catch (IOException e) {
            // 读不到就当没有产物。这是断点判定的正常路径（文件还没生成），不记日志
            return 0L;
        }
    }

    /**
     * 文件存在且大小 &gt; 0（0 字节视为不存在，用于产物断点判定）。
     */
    public static boolean isNonEmpty(Path path) {
        return sizeOrZero(path) > 0;
    }

    /**
     * 原子写盘：先写 {@code <name>.tmp} 再替换到目标名，避免断电留下半截文件冒充成品。
     */
    public static void writeAtomically(byte[] bytes, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("产物写入失败：" + target
                    + "；请检查磁盘空间与目录写权限。原因：" + e.getMessage(), e);
        }
    }

    /**
     * 原子改名落盘；失败时清掉临时文件并抛异常，绝不让半截文件冒充成最终产物。
     */
    public static void moveAtomically(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(from);
            throw new IllegalStateException("音频产物落盘失败：" + to + "；" + e.getMessage(), e);
        }
    }

    /**
     * 删除文件（不存在即忽略）。清理是尽力而为：失败只记 DEBUG，不掩盖原本要抛的异常。
     */
    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("清理临时文件失败（忽略）: file={}, reason={}", path.getFileName(), e.getMessage());
        }
    }
}
