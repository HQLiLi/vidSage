package com.vidsage.infrastructure.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.shared.model.WorkspaceDir;
import com.vidsage.domain.shared.model.WorkspaceKey;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * NioWorkspaceDirectoryManager 的纯单元测试：<b>不起 Spring 容器</b>。
 *
 * <p>由 {@code WorkspaceServiceTest} 拆分而来（2026-10-09 端口倒置）：本类只留<b>目录定位与创建</b>
 * 断言（懒创建、显式 key 入口、标签回退、根纯解析）；身份路线断言随生成器走
 * {@code WorkspaceKeyGeneratorTest}。断言值与拆分前逐字一致。
 */
class NioWorkspaceDirectoryManagerTest {

    // 本类会真的建目录（写文件系统），沿用 implement.md 记录的环境性清理规避：不清理临时目录。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private Path root() {
        return tempDir.resolve("works");
    }

    private NioWorkspaceDirectoryManager manager() {
        return new NioWorkspaceDirectoryManager(new WorkspaceProperties(root().toString()));
    }

    // ---------- 懒创建 ----------

    @Test
    void createsDirectoryAndNestedRootLazily() {
        Path dir = manager().ensureWorkspaceDirectory("sample", new WorkspaceKey("a1b2c3d4e5f6"));

        assertTrue(Files.isDirectory(dir), "调用后目录必须已存在");
        assertTrue(Files.isDirectory(root()), "根目录应被一并创建");
        // 目录在调用前不存在：本断言通过「先算路径再判存在」间接保证——根在调用前必然不存在
    }

    // ---------- 显式 key 入口（MVP-1 上传路线） ----------

    @Test
    void explicitKeyEntryCreatesDirectoryWithSanitizedLabelAndGivenKey() {
        Path dir = manager().ensureWorkspaceDirectory("上传 视频:mp4", new WorkspaceKey("a1b2c3d4e5f6"));

        assertEquals("上传 视频_mp4-a1b2c3d4e5f6", dir.getFileName().toString(),
                "目录名 = sanitize(标签)-<显式 key>，key 原样使用、不重算");
        assertTrue(Files.isDirectory(dir), "调用后目录必须已存在");
    }

    @Test
    void explicitKeyEntryFallsBackLabelForBlankOrNull() {
        assertEquals(WorkspaceDir.FALLBACK_NAME + "-a1b2c3d4e5f6",
                manager().ensureWorkspaceDirectory("  ", new WorkspaceKey("a1b2c3d4e5f6")).getFileName().toString());
        assertEquals(WorkspaceDir.FALLBACK_NAME + "-a1b2c3d4e5f6",
                manager().ensureWorkspaceDirectory(null, new WorkspaceKey("a1b2c3d4e5f6")).getFileName().toString());
    }

    @Test
    void workspaceRootResolvesWithoutCreatingDirectory() {
        Path root = manager().getWorkspaceRoot();

        assertTrue(root.isAbsolute(), "根路径应为绝对路径");
        assertFalse(Files.exists(root), "getWorkspaceRoot 是纯解析，不得有建目录副作用");
        assertEquals(root, manager().getWorkspaceRoot(), "同一配置解析结果稳定");
    }
}
