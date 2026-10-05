package com.vidsage.application.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.MediaInput;
import com.vidsage.domain.shared.WorkspaceKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkspaceService 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>覆盖「内容寻址的稳定性」「不同输入落不同目录」「名字安全化（保留名 / 非法字符 / 结尾点空格 /
 * 长度上限）」「懒创建」。
 */
class WorkspaceServiceTest {

    // 本类会真的建目录（写文件系统），沿用 implement.md 记录的环境性清理规避：不清理临时目录。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private Path root() {
        return tempDir.resolve("works");
    }

    private WorkspaceService service() {
        return new WorkspaceService(new WorkspaceProperties(root().toString()));
    }

    // ---------- 内容寻址 ----------

    @Test
    void sameRemoteUrlMapsToSameDirectory() {
        var input = new MediaInput.RemoteUrl("https://example.com/video/1");

        Path first = service().workspaceFor(input);
        Path second = service().workspaceFor(input);

        assertEquals(first, second, "同一输入必须落回同一目录");
    }

    @Test
    void differentRemoteUrlsMapToDifferentDirectories() {
        Path first = service().workspaceFor(new MediaInput.RemoteUrl("https://example.com/a"));
        Path second = service().workspaceFor(new MediaInput.RemoteUrl("https://example.com/b"));

        assertNotEquals(first, second);
    }

    @Test
    void sameLocalFileMapsToSameDirectory() throws IOException {
        Path media = writeMedia("sample.mp4", 16);
        var input = new MediaInput.LocalPath(media);

        assertEquals(service().workspaceFor(input), service().workspaceFor(input));
    }

    @Test
    void localFileKeyIncorporatesSize() throws IOException {
        // 路径相同但内容大小不同 → 规范化串不同（内容寻址，而非仅凭文件名）
        Path small = writeMedia("same-name.bin", 8);
        String smallKey = WorkspaceService.localPathKey(small);
        Files.write(small, new byte[64]);

        assertNotEquals(smallKey, WorkspaceService.localPathKey(small));
    }

    @Test
    void remoteUrlAndLocalPathDifferForSameLiteral() {
        var remote = new MediaInput.RemoteUrl("media.mp4");
        var local = new MediaInput.LocalPath(Path.of("media.mp4"));

        assertNotEquals(WorkspaceService.normalize(remote), WorkspaceService.normalize(local));
    }

    @Test
    void localPathKeyToleratesMissingFile() {
        String key = WorkspaceService.localPathKey(tempDir.resolve("nope.mp4"));

        // 文件不存在时用占位值，不在服务层报「文件不存在」——那是输入获取适配器的职责
        assertTrue(key.endsWith("|-1|-1"), key);
    }

    @Test
    void directoryNameEndsWithTwelveHexHash() {
        Path dir = service().workspaceFor(new MediaInput.RemoteUrl("https://example.com/x"));

        assertTrue(dir.getFileName().toString().matches(".+-[0-9a-f]{12}"),
                () -> "目录名应以 12 位十六进制摘要结尾：" + dir.getFileName());
    }

    // ---------- 名字安全化 ----------

    @Test
    void sanitizesIllegalCharactersInLabel() {
        Path dir = service().workspaceFor(new MediaInput.RemoteUrl("http://a.com/b?c=1:d*e"));

        String name = dir.getFileName().toString();
        assertFalse(name.chars().anyMatch(c -> "<>:\"/\\|?*".indexOf(c) >= 0),
                () -> "目录名不得含非法字符：" + name);
    }

    @Test
    void localFilePathNameIsUsedAsLabel() throws IOException {
        Path media = writeMedia("中文 视频.mp4", 8);

        Path dir = service().workspaceFor(new MediaInput.LocalPath(media));

        assertTrue(dir.getFileName().toString().startsWith("中文 视频.mp4-"),
                () -> "本地路线应以文件名做可读标签：" + dir.getFileName());
    }

    @Test
    void fallsBackToMediaForWindowsReservedName() {
        Path dir = service().workspaceFor(new MediaInput.RemoteUrl("NUL"));

        assertTrue(dir.getFileName().toString().startsWith(
                WorkspaceService.FALLBACK_NAME + "-"), () -> "保留名应回退：" + dir.getFileName());
    }

    @Test
    void fallsBackToMediaForReservedNameWithExtension() {
        assertTrue(service().workspaceFor(new MediaInput.RemoteUrl("con.txt")).getFileName().toString()
                .startsWith(WorkspaceService.FALLBACK_NAME + "-"));
    }

    @Test
    void fallsBackToMediaForEmptyLabel() {
        assertTrue(service().workspaceFor(new MediaInput.RemoteUrl("   .  ")).getFileName().toString()
                .startsWith(WorkspaceService.FALLBACK_NAME + "-"));
    }

    @Test
    void stripsTrailingDotsAndSpaces() {
        Path dir = service().workspaceFor(new MediaInput.RemoteUrl("video. "));

        assertTrue(dir.getFileName().toString().startsWith("video-"),
                () -> "结尾点与空格应被去掉：" + dir.getFileName());
    }

    @Test
    void truncatesOverlongLabel() {
        Path dir = service().workspaceFor(new MediaInput.RemoteUrl("a".repeat(200)));

        assertTrue(dir.getFileName().toString()
                        .startsWith("a".repeat(WorkspaceService.MAX_NAME_LENGTH) + "-"),
                () -> "超长标签应被截断：" + dir.getFileName());
    }

    @Test
    void sanitizeNameHandlesEdgeCases() {
        assertEquals("a_b_c", WorkspaceService.sanitizeName("a<b>c"));
        assertEquals(WorkspaceService.FALLBACK_NAME, WorkspaceService.sanitizeName(null));
        assertEquals(WorkspaceService.FALLBACK_NAME, WorkspaceService.sanitizeName("   "));
        assertEquals(WorkspaceService.FALLBACK_NAME, WorkspaceService.sanitizeName("..."));
        assertEquals(WorkspaceService.FALLBACK_NAME, WorkspaceService.sanitizeName("nul.txt"));
        assertEquals("COM10", WorkspaceService.sanitizeName("COM10"), "只有 COM1-9 是保留名");
        assertEquals("CONSOLE", WorkspaceService.sanitizeName("CONSOLE"));
    }

    @Test
    void isReservedNameOnlyMatchesWindowsDeviceNames() {
        assertTrue(WorkspaceService.isReservedName("CON"));
        assertTrue(WorkspaceService.isReservedName("con"));
        assertTrue(WorkspaceService.isReservedName("CON.txt"));
        assertTrue(WorkspaceService.isReservedName("LPT9"));
        assertFalse(WorkspaceService.isReservedName("COM10"));
        assertFalse(WorkspaceService.isReservedName("LPT0"));
        assertFalse(WorkspaceService.isReservedName("console"));
    }

    @Test
    void shortHashIsStableTwelveHexChars() {
        WorkspaceKey hash = WorkspaceService.shortHash("https://example.com/x");

        assertEquals(12, hash.value().length());
        assertTrue(hash.value().matches("[0-9a-f]{12}"), hash.value());
        assertEquals(hash, WorkspaceService.shortHash("https://example.com/x"));
        assertNotEquals(hash, WorkspaceService.shortHash("https://example.com/y"));
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
        assertEquals(WorkspaceService.FALLBACK_NAME + "-a1b2c3d4e5f6",
                service().workspaceFor("  ", new WorkspaceKey("a1b2c3d4e5f6")).getFileName().toString());
        assertEquals(WorkspaceService.FALLBACK_NAME + "-a1b2c3d4e5f6",
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
