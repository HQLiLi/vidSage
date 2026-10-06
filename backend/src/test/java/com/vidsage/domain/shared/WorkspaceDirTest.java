package com.vidsage.domain.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceDir} 的纯单元测试：目录名公式（{@code sanitize(标签)-key}）与名字安全化规则
 * （非法字符 / Windows 保留名 / 结尾点空格 / 长度上限）。迁自 WorkspaceServiceTest（断言值不变）。
 */
class WorkspaceDirTest {

    /** 任取的 12 位十六进制 key：目录名公式对 key 只做原样拼接。 */
    private static final WorkspaceKey KEY = new WorkspaceKey("a1b2c3d4e5f6");

    // ---------- 目录名公式 ----------

    @Test
    void dirNameIsSanitizedLabelDashKey() {
        assertEquals("上传 视频_mp4-a1b2c3d4e5f6", WorkspaceDir.of("上传 视频:mp4", KEY).value(),
                "目录名 = sanitize(标签)-<key>，key 原样使用、不重算");
    }

    @Test
    void directoryNameEndsWithTwelveHexHash() {
        String dirName = dirNameOf("https://example.com/x");

        assertTrue(dirName.matches(".+-[0-9a-f]{12}"),
                () -> "目录名应以 12 位十六进制摘要结尾：" + dirName);
    }

    // ---------- 名字安全化 ----------

    @Test
    void sanitizesIllegalCharactersInLabel() {
        String name = WorkspaceDir.of("http://a.com/b?c=1:d*e", KEY).value();

        assertFalse(name.chars().anyMatch(c -> "<>:\"/\\|?*".indexOf(c) >= 0),
                () -> "目录名不得含非法字符：" + name);
    }

    @Test
    void fallsBackToMediaForWindowsReservedName() {
        assertTrue(WorkspaceDir.of("NUL", KEY).value().startsWith(WorkspaceDir.FALLBACK_NAME + "-"),
                () -> "保留名应回退：" + WorkspaceDir.of("NUL", KEY).value());
    }

    @Test
    void fallsBackToMediaForReservedNameWithExtension() {
        assertTrue(WorkspaceDir.of("con.txt", KEY).value()
                .startsWith(WorkspaceDir.FALLBACK_NAME + "-"));
    }

    @Test
    void fallsBackToMediaForEmptyLabel() {
        assertTrue(WorkspaceDir.of("   .  ", KEY).value()
                .startsWith(WorkspaceDir.FALLBACK_NAME + "-"));
    }

    @Test
    void stripsTrailingDotsAndSpaces() {
        assertTrue(WorkspaceDir.of("video. ", KEY).value().startsWith("video-"),
                () -> "结尾点与空格应被去掉：" + WorkspaceDir.of("video. ", KEY).value());
    }

    @Test
    void truncatesOverlongLabel() {
        assertTrue(WorkspaceDir.of("a".repeat(200), KEY).value()
                        .startsWith("a".repeat(WorkspaceDir.MAX_NAME_LENGTH) + "-"),
                () -> "超长标签应被截断：" + WorkspaceDir.of("a".repeat(200), KEY).value());
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

    /** 完整寻址链的目录名（身份 → key → 目录名），与原 workspaceFor(RemoteUrl) 的目录名产物一致。 */
    private static String dirNameOf(String url) {
        return WorkspaceDir.of(url, WorkspaceKey.ofHash(SourceUri.ofRemoteUrl(url).value())).value();
    }
}
