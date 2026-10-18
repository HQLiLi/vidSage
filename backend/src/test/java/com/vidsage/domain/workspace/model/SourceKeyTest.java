package com.vidsage.domain.workspace.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link SourceKey} 值对象的纯单元测试：硬不变量（非空白 + 不超列宽）与形态透传。
 * 形态构造（bvid|p 拼装 / sha256 判定 / 剥跟踪参数）是 URL 规范化策略的活，不在此测。
 */
class SourceKeyTest {

    @Test
    void acceptsBilibiliStyleKey() {
        assertEquals("BV11QMd6WEJp|1", new SourceKey("BV11QMd6WEJp|1").value());
    }

    @Test
    void acceptsUploadSha256Key() {
        String sha256 = "a".repeat(64);

        assertEquals(sha256, new SourceKey(sha256).value());
    }

    @Test
    void acceptsKeyAtColumnWidthLimit() {
        String maxLength = "a".repeat(SourceKey.MAX_LENGTH);

        assertEquals(SourceKey.MAX_LENGTH, new SourceKey(maxLength).value().length());
    }

    @Test
    void throwsWhenValueIsBlank() {
        assertThrows(IllegalStateException.class, () -> new SourceKey(null));
        assertThrows(IllegalStateException.class, () -> new SourceKey(""));
        assertThrows(IllegalStateException.class, () -> new SourceKey("   "));
    }

    @Test
    void throwsWhenValueExceedsColumnWidth() {
        String tooLong = "a".repeat(SourceKey.MAX_LENGTH + 1);

        assertThrows(IllegalStateException.class, () -> new SourceKey(tooLong),
                "超过 workspace.source_key（VARCHAR(512)）的值落不进库，应在构造时就被拒");
    }
}
