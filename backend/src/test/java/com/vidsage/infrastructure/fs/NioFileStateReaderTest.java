package com.vidsage.infrastructure.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.media.model.MediaFileInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * NioFileStateReader 的单测（批次 3，端口实现的行为契约）：
 * 读数、-1 占位不抛、快照 path 与传入原样一致。
 */
class NioFileStateReaderTest {

    // 建临时文件，沿用本项目环境性清理规避：不清理临时目录
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final NioFileStateReader reader = new NioFileStateReader();

    @Test
    void readsSizeAndMtimeForRegularFile() throws IOException {
        Path file = tempDir.resolve("clip.m4a");
        Files.write(file, new byte[64]);

        MediaFileInfo info = reader.getFileState(file);

        assertEquals(file, info.path(), "快照 path 与传入原样一致，不做 absolute 化");
        assertEquals(64, info.sizeBytes());
        assertTrue(info.modifyTimeMs() > 0);
        assertTrue(info.isAvailable());
    }

    @Test
    void missingFileYieldsPlaceholderWithoutThrowing() {
        Path missing = tempDir.resolve("nope.mp4");

        MediaFileInfo info = assertDoesNotThrow(() -> reader.getFileState(missing));

        // path 仍与传入一致——否则 MediaInput.LocalPath 的一致性断言会被占位快照误判
        assertEquals(missing, info.path());
        assertEquals(MediaFileInfo.UNAVAILABLE, info.sizeBytes());
        assertEquals(MediaFileInfo.UNAVAILABLE, info.modifyTimeMs());
        assertFalse(info.isAvailable());
    }

    @Test
    void directoryYieldsPlaceholderWithoutThrowing() {
        Path dir = tempDir.resolve("sub");
        assertDoesNotThrow(() -> Files.createDirectories(dir));

        MediaFileInfo info = assertDoesNotThrow(() -> reader.getFileState(dir));

        assertEquals(dir, info.path());
        assertEquals(MediaFileInfo.UNAVAILABLE, info.sizeBytes());
        assertFalse(info.isAvailable());
    }

    @Test
    void nullPathReturnsSentinel() {
        assertSame(MediaFileInfo.NOT_APPLICABLE, reader.getFileState(null));
    }
}
