package com.vidsage.utils.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * ArtifactFiles 的纯单元测试：直接读写临时目录里的真实文件，不起 Spring 容器。
 */
class ArtifactFilesTest {

    // 本类会真的写文件；Windows 上 @TempDir 的清理偶发失败（本仓库已两次独立复现），
    // 宁可留下临时目录，也不要让环境问题把测试判红。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    @Test
    void sizeOrZeroReturnsZeroForMissingFile() {
        assertEquals(0L, ArtifactFiles.sizeOrZero(tempDir.resolve("nope.bin")));
    }

    @Test
    void sizeOrZeroReturnsZeroForEmptyFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("empty.bin"));

        assertEquals(0L, ArtifactFiles.sizeOrZero(file));
    }

    @Test
    void sizeOrZeroReturnsByteCountForContent() throws IOException {
        Path file = Files.write(tempDir.resolve("data.bin"), new byte[7]);

        assertEquals(7L, ArtifactFiles.sizeOrZero(file));
    }

    @Test
    void sizeOrZeroReturnsZeroForDirectory() throws IOException {
        // 目录在部分文件系统（如 ext4）上也有非 0 的「大小」，守卫必须把它挡成 0——
        // 否则目录会冒充成合法产物（这正是归一六份副本时修的那个 bug）
        Path dir = Files.createDirectory(tempDir.resolve("a-directory"));

        assertEquals(0L, ArtifactFiles.sizeOrZero(dir));
    }

    @Test
    void isNonEmptyRejectsMissingAndEmptyFiles() throws IOException {
        Path empty = Files.createFile(tempDir.resolve("empty2.bin"));

        assertFalse(ArtifactFiles.isNonEmpty(tempDir.resolve("nope.bin")));
        assertFalse(ArtifactFiles.isNonEmpty(empty));
    }

    @Test
    void isNonEmptyAcceptsFileWithContent() throws IOException {
        Path file = Files.write(tempDir.resolve("data2.bin"), new byte[] {1, 2, 3});

        assertTrue(ArtifactFiles.isNonEmpty(file));
    }

    @Test
    void writeAtomicallyWritesContentAndLeavesNoTmpFile() throws IOException {
        Path target = tempDir.resolve("artifact.srt");
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        ArtifactFiles.writeAtomically(content, target);

        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(Files.exists(tempDir.resolve("artifact.srt.tmp")));
    }

    @Test
    void writeAtomicallyReplacesExistingFile() throws IOException {
        Path target = Files.write(tempDir.resolve("artifact.json"), "old".getBytes(StandardCharsets.UTF_8));
        byte[] content = "new".getBytes(StandardCharsets.UTF_8);

        ArtifactFiles.writeAtomically(content, target);

        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void moveAtomicallyMovesSourceToTarget() throws IOException {
        Path from = Files.write(tempDir.resolve("audio.wav.part"), new byte[] {9});
        Path to = tempDir.resolve("audio.wav");

        ArtifactFiles.moveAtomically(from, to);

        assertFalse(Files.exists(from));
        assertTrue(Files.exists(to));
    }

    @Test
    void deleteQuietlyRemovesFileAndIgnoresMissingOne() throws IOException {
        Path file = Files.write(tempDir.resolve("tmp.bin"), new byte[] {1});

        ArtifactFiles.deleteQuietly(file);
        assertFalse(Files.exists(file));
        // 文件本就不存在时也不能抛
        ArtifactFiles.deleteQuietly(tempDir.resolve("already-gone.bin"));
    }
}
