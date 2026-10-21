package com.vidsage.shared.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * ArtifactFileUtils 的纯单元测试：直接读写临时目录里的真实文件，不起 Spring 容器。
 */
class ArtifactFileUtilsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 只为 JSON 往返造的小 record。 */
    record Sample(String name, int value) {
    }

    // 本类会真的写文件；Windows 上 @TempDir 的清理偶发失败（本仓库已两次独立复现），
    // 宁可留下临时目录，也不要让环境问题把测试判红。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    @Test
    void sizeOrZeroReturnsZeroForMissingFile() {
        assertEquals(0L, ArtifactFileUtils.sizeOrZero(tempDir.resolve("nope.bin")));
    }

    @Test
    void sizeOrZeroReturnsZeroForEmptyFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("empty.bin"));

        assertEquals(0L, ArtifactFileUtils.sizeOrZero(file));
    }

    @Test
    void sizeOrZeroReturnsByteCountForContent() throws IOException {
        Path file = Files.write(tempDir.resolve("data.bin"), new byte[7]);

        assertEquals(7L, ArtifactFileUtils.sizeOrZero(file));
    }

    @Test
    void sizeOrZeroReturnsZeroForDirectory() throws IOException {
        // 目录在部分文件系统（如 ext4）上也有非 0 的「大小」，守卫必须把它挡成 0——
        // 否则目录会冒充成合法产物（这正是归一六份副本时修的那个 bug）
        Path dir = Files.createDirectory(tempDir.resolve("a-directory"));

        assertEquals(0L, ArtifactFileUtils.sizeOrZero(dir));
    }

    @Test
    void isNonEmptyRejectsMissingAndEmptyFiles() throws IOException {
        Path empty = Files.createFile(tempDir.resolve("empty2.bin"));

        assertFalse(ArtifactFileUtils.isNonEmpty(tempDir.resolve("nope.bin")));
        assertFalse(ArtifactFileUtils.isNonEmpty(empty));
    }

    @Test
    void isNonEmptyAcceptsFileWithContent() throws IOException {
        Path file = Files.write(tempDir.resolve("data2.bin"), new byte[] {1, 2, 3});

        assertTrue(ArtifactFileUtils.isNonEmpty(file));
    }

    @Test
    void isRegularFileTreatsEmptyFileAsPresent() throws IOException {
        Path empty = Files.createFile(tempDir.resolve("empty.srt"));
        Path directory = Files.createDirectory(tempDir.resolve("a-dir"));

        assertTrue(ArtifactFileUtils.isRegularFile(empty), "0 字节 SRT 是合法产物，不能当成缺失");
        assertFalse(ArtifactFileUtils.isRegularFile(directory));
        assertFalse(ArtifactFileUtils.isRegularFile(tempDir.resolve("nope.srt")));
    }

    @Test
    void sha256ReturnsLowercaseHexOfContent() throws IOException {
        Path file = Files.writeString(tempDir.resolve("abc.txt"), "abc", StandardCharsets.UTF_8);

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ArtifactFileUtils.sha256(file));
    }

    @Test
    void sha256ThrowsIllegalStateWhenFileCannotBeRead() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ArtifactFileUtils.sha256(tempDir.resolve("missing.bin")));

        assertTrue(e.getMessage().contains("missing.bin"), "消息要带文件名，便于定位");
    }

    @Test
    void writeAtomicallyWritesContentAndLeavesNoTmpFile() throws IOException {
        Path target = tempDir.resolve("artifact.srt");
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        ArtifactFileUtils.writeAtomically(content, target);

        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(Files.exists(tempDir.resolve("artifact.srt.tmp")));
    }

    @Test
    void writeAtomicallyReplacesExistingFile() throws IOException {
        Path target = Files.write(tempDir.resolve("artifact.json"), "old".getBytes(StandardCharsets.UTF_8));
        byte[] content = "new".getBytes(StandardCharsets.UTF_8);

        ArtifactFileUtils.writeAtomically(content, target);

        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void moveAtomicallyMovesSourceToTarget() throws IOException {
        Path from = Files.write(tempDir.resolve("audio.wav.part"), new byte[] {9});
        Path to = tempDir.resolve("audio.wav");

        ArtifactFileUtils.moveAtomically(from, to);

        assertFalse(Files.exists(from));
        assertTrue(Files.exists(to));
    }

    @Test
    void deleteQuietlyRemovesFileAndIgnoresMissingOne() throws IOException {
        Path file = Files.write(tempDir.resolve("tmp.bin"), new byte[] {1});

        ArtifactFileUtils.deleteQuietly(file);
        assertFalse(Files.exists(file));
        // 文件本就不存在时也不能抛
        ArtifactFileUtils.deleteQuietly(tempDir.resolve("already-gone.bin"));
    }

    @Test
    void writeJsonAtomicallyWritesPrettyJsonAndLeavesNoTmpFile() throws IOException {
        Path target = tempDir.resolve("checkpoint.json");

        ArtifactFileUtils.writeJsonAtomically(new Sample("米特号", 7), JSON, target);

        String json = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"name\""), "该写出字段名");
        assertTrue(json.contains("米特号"), "该写出中文值");
        assertTrue(json.contains("\n"), "该是带缩进的 JSON，不是紧凑一行");
        assertFalse(Files.exists(tempDir.resolve("checkpoint.json.tmp")));
    }

    @Test
    void readJsonOrNullRoundTripsWrittenObject() throws IOException {
        Path target = tempDir.resolve("checkpoint2.json");
        ArtifactFileUtils.writeJsonAtomically(new Sample("剑术", 3), JSON, target);

        Sample read = ArtifactFileUtils.readJsonOrNull(target, JSON, Sample.class);

        assertEquals("剑术", read.name());
        assertEquals(3, read.value());
    }

    @Test
    void readJsonOrNullReturnsNullForMissingEmptyAndBrokenFile() throws IOException {
        assertNull(ArtifactFileUtils.readJsonOrNull(tempDir.resolve("nope.json"), JSON, Sample.class),
                "文件不存在当没有检查点");

        Path empty = Files.createFile(tempDir.resolve("empty.json"));
        assertNull(ArtifactFileUtils.readJsonOrNull(empty, JSON, Sample.class), "空文件当没有检查点");

        Path broken = Files.writeString(tempDir.resolve("broken.json"), "这不是 JSON",
                StandardCharsets.UTF_8);
        assertNull(ArtifactFileUtils.readJsonOrNull(broken, JSON, Sample.class), "解析失败当没有检查点");
    }
}
