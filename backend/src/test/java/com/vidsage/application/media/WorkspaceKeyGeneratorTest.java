package com.vidsage.application.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.model.MediaFileInfo;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.infrastructure.fs.NioFileStateReader;
import com.vidsage.infrastructure.fs.NioWorkspaceDirectoryManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkspaceKeyGenerator 的纯单元测试：<b>不起 Spring 容器</b>。
 */
class WorkspaceKeyGeneratorTest {

    // 本类会真的建目录（写文件系统），沿用 implement.md 记录的环境性清理规避：不清理临时目录。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final NioFileStateReader fileStateReader = new NioFileStateReader();

    private Path root() {
        return tempDir.resolve("works");
    }

    private WorkspaceKeyGenerator generator() {
        return new WorkspaceKeyGenerator(fileStateReader);
    }

    private WorkspaceDirectoryManager manager() {
        return new NioWorkspaceDirectoryManager(new WorkspaceProperties(root().toString()));
    }

    /** 语义陷阱：先取 key 再落目录，两行不能并一行。 */
    private Path workDir(MediaInput input) {
        WorkspaceKey key = generator().parseMediaToWorkspaceKey(input);
        return manager().ensureWorkspaceDirectory(input.dirLabel(), key);
    }

    // ---------- 内容寻址（本地文件 stat 路线：FileStateReader 供事实，身份公式在 domain） ----------

    @Test
    void sameLocalFileMapsToSameDirectory() throws IOException {
        Path media = writeMedia("sample.mp4", 16);
        var input = new MediaInput.LocalPath(media);

        assertEquals(workDir(input), workDir(input));
    }

    @Test
    void localFileKeyIncorporatesSize() throws IOException {
        // 路径相同但内容大小不同 → 身份不同（内容寻址，而非仅凭文件名）
        Path small = writeMedia("same-name.bin", 8);
        var input = new MediaInput.LocalPath(small);
        WorkspaceKey before = generator().parseMediaToWorkspaceKey(input);
        Files.write(small, new byte[64]);

        assertNotEquals(before, generator().parseMediaToWorkspaceKey(input));
    }

    @Test
    void missingFileYieldsPlaceholderIdentity() {
        Path missing = tempDir.resolve("nope.mp4");
        var input = new MediaInput.LocalPath(missing);
        MediaFileInfo info = fileStateReader.getFileState(missing);

        // 文件不存在 → 占位事实（size/mtime 均为 -1）而不是异常；「文件不存在」的报错属于输入获取适配器
        assertFalse(info.isAvailable());
        // 占位事实照常定身份：重复读取稳定，照样能算出目录
        assertEquals(input.getContentKey(info), input.getContentKey(fileStateReader.getFileState(missing)));
        assertTrue(Files.isDirectory(workDir(input)));
    }

    // ---------- 可读标签 ----------

    @Test
    void localFilePathNameIsUsedAsLabel() throws IOException {
        Path media = writeMedia("中文 视频.mp4", 8);

        Path dir = workDir(new MediaInput.LocalPath(media));

        assertTrue(dir.getFileName().toString().startsWith("中文 视频.mp4-"),
                () -> "本地路线应以文件名做可读标签：" + dir.getFileName());
    }

    // ---------- null 卫语句 ----------

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> generator().parseMediaToWorkspaceKey(null));
    }

    private Path writeMedia(String name, int size) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[size]);
        return file;
    }
}
