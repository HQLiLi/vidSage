package com.vidsage.domain.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceKey} 静态工厂的纯单元测试：sha256 前 12 位十六进制的稳定性、宽度单源与
 * 两条路线（哈希身份串 / 已算好摘要）的产物一致。shortHash 断言迁自 WorkspaceServiceTest
 * （断言值不变）。
 */
class WorkspaceKeyTest {

    @Test
    void shortHashIsStableTwelveHexChars() {
        WorkspaceKey hash = WorkspaceKey.ofHash("https://example.com/x");

        assertEquals(12, hash.value().length());
        assertTrue(hash.value().matches("[0-9a-f]{12}"), hash.value());
        assertEquals(hash, WorkspaceKey.ofHash("https://example.com/x"));
        assertNotEquals(hash, WorkspaceKey.ofHash("https://example.com/y"));
    }

    @Test
    void ofDigestMatchesOfHashForSameInput() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("https://example.com/x".getBytes(StandardCharsets.UTF_8));

        assertEquals(WorkspaceKey.ofHash("https://example.com/x"), WorkspaceKey.ofDigest(digest),
                "上传路线（流式边写边算摘要）与常规路线（哈希身份串）必须产出同一形态的 key");
    }

    @Test
    void ofDigestKeepsTwelveHexWidth() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(new byte[0]);

        assertEquals(12, WorkspaceKey.ofDigest(digest).value().length());
        assertTrue(WorkspaceKey.ofDigest(digest).value().matches("[0-9a-f]{12}"));
    }
}
