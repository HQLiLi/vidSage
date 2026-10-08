package com.vidsage.application.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.model.MediaFileInfo;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.WorkspaceDir;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.infrastructure.fs.NioFileStateReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkspaceService 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>本类只留<b>文件系统与编排</b>断言（懒创建、根解析、null 输入卫语句、显式 key 入口落盘、
 * 本地文件 stat 进身份）。寻址 / 命名公式已随 {@code SourceUri} / {@code WorkspaceDir} /
 * {@code WorkspaceKey.ofHash} 下沉 domain/shared，对应规则断言迁至同包测试类
 * （{@code SourceUriTest} / {@code WorkspaceDirTest} / {@code WorkspaceKeyTest}），断言值不变。
 */
class WorkspaceServiceTest {

    // 本类会真的建目录（写文件系统），沿用 implement.md 记录的环境性清理规避：不清理临时目录。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final NioFileStateReader fileStateReader = new NioFileStateReader();

    private Path root() {
        return tempDir.resolve("works");
    }

    private WorkspaceService service() {
        return new WorkspaceService(new WorkspaceProperties(root().toString()), fileStateReader);
    }

    // ---------- 内容寻址（本地文件 stat 路线：FileStateReader 供事实，身份公式在 domain） ----------

    @Test
    void sameLocalFileMapsToSameDirectory() throws IOException {
        Path media = writeMedia("sample.mp4", 16);
        var input = new MediaInput.LocalPath(media);

        assertEquals(service().workspaceFor(input), service().workspaceFor(input));
    }

    @Test
    void localFileKeyIncorporatesSize() throws IOException {
        // 路径相同但内容大小不同 → 身份不同（内容寻址，而非仅凭文件名）
        Path small = writeMedia("same-name.bin", 8);
        var input = new MediaInput.LocalPath(small);
        WorkspaceKey before = input.getContentKey(fileStateReader.getFileState(small));
        Files.write(small, new byte[64]);

        assertNotEquals(before, input.getContentKey(fileStateReader.getFileState(small)));
    }

    @Test
    void missingFileYieldsPlaceholderIdentity() {
        Path missing = tempDir.resolve("nope.mp4");
        var input = new MediaInput.LocalPath(missing);
        MediaFileInfo info = fileStateReader.getFileState(missing);

        // 文件不存在 → 占位事实（size/mtime 均为 -1）而不是异常；「文件不存在」的报错属于输入获取适配器
        assertFalse(info.isAvailable());
        // 占位事实照常定身份：重复读取稳定，workspaceFor 照样能算出目录
        assertEquals(input.getContentKey(info), input.getContentKey(fileStateReader.getFileState(missing)));
        assertTrue(Files.isDirectory(service().workspaceFor(input)));
    }

    // ---------- 可读标签 ----------

    @Test
    void localFilePathNameIsUsedAsLabel() throws IOException {
        Path media = writeMedia("中文 视频.mp4", 8);

        Path dir = service().workspaceFor(new MediaInput.LocalPath(media));

        assertTrue(dir.getFileName().toString().startsWith("中文 视频.mp4-"),
                () -> "本地路线应以文件名做可读标签：" + dir.getFileName());
    }

    // ---------- 懒创建 ----------

    @Test
    void createsDirectoryAndNestedRootLazily() {
        Path dir = service().workspaceFor(new MediaInput.RemoteUrl("https://example.com/x"));

        assertTrue(Files.isDirectory(dir), "调用后目录必须已存在");
        assertTrue(Files.isDirectory(root()), "根目录应被一并创建");
        // 目录在调用前不存在：本断言通过「先算路径再判存在」间接保证——根在调用前必然不存在
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> service().workspaceFor(null));
    }

    // ---------- 显式 key 入口（MVP-1 上传路线） ----------

    @Test
    void explicitKeyEntryCreatesDirectoryWithSanitizedLabelAndGivenKey() {
        Path dir = service().workspaceFor("上传 视频:mp4", new WorkspaceKey("a1b2c3d4e5f6"));

        assertEquals("上传 视频_mp4-a1b2c3d4e5f6", dir.getFileName().toString(),
                "目录名 = sanitize(标签)-<显式 key>，key 原样使用、不重算");
        assertTrue(Files.isDirectory(dir), "调用后目录必须已存在");
    }

    @Test
    void explicitKeyEntryFallsBackLabelForBlankOrNull() {
        assertEquals(WorkspaceDir.FALLBACK_NAME + "-a1b2c3d4e5f6",
                service().workspaceFor("  ", new WorkspaceKey("a1b2c3d4e5f6")).getFileName().toString());
        assertEquals(WorkspaceDir.FALLBACK_NAME + "-a1b2c3d4e5f6",
                service().workspaceFor(null, new WorkspaceKey("a1b2c3d4e5f6")).getFileName().toString());
    }

    @Test
    void workspaceRootResolvesWithoutCreatingDirectory() {
        Path root = service().workspaceRoot();

        assertTrue(root.isAbsolute(), "根路径应为绝对路径");
        assertFalse(Files.exists(root), "workspaceRoot 是纯解析，不得有建目录副作用");
        assertEquals(root, service().workspaceRoot(), "同一配置解析结果稳定");
    }

    private Path writeMedia(String name, int size) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[size]);
        return file;
    }
}
