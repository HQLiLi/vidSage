package com.vidsage.infrastructure.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.WorkspaceDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * NioWorkspaceDirectoryManager 的纯单元测试：<b>不起 Spring 容器</b>。
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
        Path dir = manager().ensureWorkspaceDirectory(new SourceKey("BV11QMd6WEJp|1"));

        assertTrue(Files.isDirectory(dir), "调用后目录必须已存在");
        assertTrue(Files.isDirectory(root()), "根目录应被一并创建");
        // 目录在调用前不存在：本断言通过「先算路径再判存在」间接保证——根在调用前必然不存在
    }

    // ---------- 目录名公式 ----------

    @Test
    void directoryNameIsSanitizedSourceKey() {
        Path dir = manager().ensureWorkspaceDirectory(new SourceKey("BV11QMd6WEJp|1"));

        assertEquals("BV11QMd6WEJp_1", dir.getFileName().toString(),
                "目录名 = sanitize(身份键)，竖线换成下划线");
        assertTrue(Files.isDirectory(dir), "调用后目录必须已存在");
    }

    @Test
    void fallsBackToMediaForReservedSourceKey() {
        assertEquals(WorkspaceDir.FALLBACK_NAME,
                manager().ensureWorkspaceDirectory(new SourceKey("NUL")).getFileName().toString());
    }

    @Test
    void workspaceRootResolvesWithoutCreatingDirectory() {
        Path root = manager().getWorkspaceRoot();

        assertTrue(root.isAbsolute(), "根路径应为绝对路径");
        assertFalse(Files.exists(root), "getWorkspaceRoot 是纯解析，不得有建目录副作用");
        assertEquals(root, manager().getWorkspaceRoot(), "同一配置解析结果稳定");
    }
}
