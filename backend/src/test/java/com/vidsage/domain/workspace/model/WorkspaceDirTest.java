package com.vidsage.domain.workspace.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceDir} 的纯单元测试：目录名公式（身份键整条走安全化）与名字安全化规则
 * （非法字符 / Windows 保留名 / 结尾点空格 / 长度上限）。
 */
class WorkspaceDirTest {

    // ---------- 目录名公式 ----------

    @Test
    void dirNameIsSanitizedSourceKey() {
        assertEquals("BV11QMd6WEJp_1", WorkspaceDir.of(new SourceKey("BV11QMd6WEJp|1")).value(),
                "目录名 = sanitize(身份键)；URL 路线的竖线换成下划线");
    }

    @Test
    void dirNameKeepsSha256Verbatim() {
        String sha256 = "a".repeat(64);

        assertEquals(sha256, WorkspaceDir.of(new SourceKey(sha256)).value(),
                "上传路线的 sha256 十六进制原样保留，不会被改写");
    }

    @Test
    void distinctSourceKeysMapToDistinctDirNames() {
        assertNotEquals(WorkspaceDir.of(new SourceKey("BV11QMd6WEJp|1")).value(),
                WorkspaceDir.of(new SourceKey("BV11QMd6WEJp|2")).value(),
                "不同分 P 必须落回不同目录");
    }

    // ---------- 名字安全化 ----------

    @Test
    void sanitizesIllegalCharactersInSourceKey() {
        String name = WorkspaceDir.of(new SourceKey("http://a.com/b?c=1:d*e")).value();

        assertFalse(name.chars().anyMatch(c -> "<>:\"/\\|?*".indexOf(c) >= 0),
                () -> "目录名不得含非法字符：" + name);
    }

    @Test
    void fallsBackToMediaForWindowsReservedName() {
        assertEquals(WorkspaceDir.FALLBACK_NAME, WorkspaceDir.of(new SourceKey("NUL")).value(),
                "保留名应回退");
    }

    @Test
    void fallsBackToMediaForReservedNameWithExtension() {
        assertEquals(WorkspaceDir.FALLBACK_NAME, WorkspaceDir.of(new SourceKey("con.txt")).value());
    }

    @Test
    void stripsTrailingDotsAndSpaces() {
        assertTrue(WorkspaceDir.of(new SourceKey("video. ")).value().startsWith("video"),
                () -> "结尾点与空格应被去掉：" + WorkspaceDir.of(new SourceKey("video. ")).value());
    }

    @Test
    void truncatesOverlongSourceKey() {
        assertTrue(WorkspaceDir.of(new SourceKey("a".repeat(200))).value()
                        .startsWith("a".repeat(WorkspaceDir.MAX_NAME_LENGTH)),
                () -> "超长身份键应被截断：" + WorkspaceDir.of(new SourceKey("a".repeat(200))).value());
    }

    @Test
    void sanitizeNameHandlesEdgeCases() {
        assertEquals("a_b_c", WorkspaceDir.sanitizeName("a<b>c"));
        assertEquals(WorkspaceDir.FALLBACK_NAME, WorkspaceDir.sanitizeName(null));
        assertEquals(WorkspaceDir.FALLBACK_NAME, WorkspaceDir.sanitizeName("   "));
        assertEquals(WorkspaceDir.FALLBACK_NAME, WorkspaceDir.sanitizeName("..."));
        assertEquals(WorkspaceDir.FALLBACK_NAME, WorkspaceDir.sanitizeName("nul.txt"));
        assertEquals("COM10", WorkspaceDir.sanitizeName("COM10"), "只有 COM1-9 是保留名");
        assertEquals("CONSOLE", WorkspaceDir.sanitizeName("CONSOLE"));
    }

    @Test
    void isReservedNameOnlyMatchesWindowsDeviceNames() {
        assertTrue(WorkspaceDir.isReservedName("CON"));
        assertTrue(WorkspaceDir.isReservedName("con"));
        assertTrue(WorkspaceDir.isReservedName("CON.txt"));
        assertTrue(WorkspaceDir.isReservedName("LPT9"));
        assertFalse(WorkspaceDir.isReservedName("COM10"));
        assertFalse(WorkspaceDir.isReservedName("LPT0"));
        assertFalse(WorkspaceDir.isReservedName("console"));
    }
}
