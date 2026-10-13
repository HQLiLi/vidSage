package com.vidsage.domain.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link SourceUri} 的纯单元测试：输入身份规范化公式（URL 原文 / 本地三元组）的稳定性与区分度。
 * 内容寻址规则断言迁自 WorkspaceServiceTest（断言值不变）——目录名由「身份 → key → 目录名」
 * 链路决定，身份稳定即同一输入落回同一目录。
 */
class SourceUriTest {

    // ---------- 远程 URL 身份 ----------

    @Test
    void sameRemoteUrlMapsToSameDirectory() {
        assertEquals(dirNameOf("https://example.com/video/1"), dirNameOf("https://example.com/video/1"),
                "同一输入必须落回同一目录");
    }

    @Test
    void differentRemoteUrlsMapToDifferentDirectories() {
        assertNotEquals(dirNameOf("https://example.com/a"), dirNameOf("https://example.com/b"));
    }

    @Test
    void remoteUrlIdentityIsUrlVerbatim() {
        assertEquals("https://example.com/v", SourceUri.ofRemoteUrl("https://example.com/v").value(),
                "URL 原文原样参与哈希，不做任何改写");
        assertEquals("", SourceUri.ofRemoteUrl(null).value(), "URL 为 null 时按空串参与哈希");
    }

    @Test
    void remoteUrlAndLocalPathDifferForSameLiteral() {
        var remote = SourceUri.ofRemoteUrl("media.mp4");
        // 文件不存在的本地路线：绝对路径 + 占位大小与时间戳（原 localPathKey 公式）
        var local = SourceUri.ofLocalFile(Path.of("media.mp4"), SourceUri.UNAVAILABLE, SourceUri.UNAVAILABLE);

        assertNotEquals(remote, local);
    }

    // ---------- 本地文件身份 ----------

    @Test
    void localFileIdentityJoinsAbsolutePathSizeAndMtime() {
        Path absolute = Path.of("video.mp4").toAbsolutePath().normalize();

        var identity = SourceUri.ofLocalFile(absolute, 16L, 1728190000000L);

        assertEquals(absolute + "|16|1728190000000", identity.value(), "身份 = 绝对路径|大小|毫秒时间戳");
    }

    @Test
    void localFileIdentityNormalizesRelativePath() {
        Path absolute = Path.of("video.mp4").toAbsolutePath().normalize();

        assertEquals(SourceUri.ofLocalFile(absolute, 16L, 1L),
                SourceUri.ofLocalFile(Path.of("video.mp4"), 16L, 1L),
                "相对路径与同一文件的绝对路径必须规范化出同一身份");
    }

    @Test
    void localFileIdentityToleratesNullPath() {
        var identity = SourceUri.ofLocalFile(null, SourceUri.UNAVAILABLE, SourceUri.UNAVAILABLE);

        assertEquals("<null>|-1|-1", identity.value(), "路径为 null 时整条身份为占位串");
    }

    /**
     * 完整寻址链：身份 → key → 目录名（等价于原 workspaceFor(RemoteUrl) 产出的目录名，去掉落盘）。
     */
    private static String dirNameOf(String url) {
        return WorkspaceDir.of(url, WorkspaceKey.ofHash(SourceUri.ofRemoteUrl(url).value())).value();
    }
}
