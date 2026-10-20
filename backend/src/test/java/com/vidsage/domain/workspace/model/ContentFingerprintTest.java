package com.vidsage.domain.workspace.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentFingerprint} 的不变量测试：两列都必须合法，非法取值是服务端自己算错，
 * 抛 {@link IllegalStateException}（不是调用方传参错误）。
 */
class ContentFingerprintTest {

    private static final LocalDateTime PUBLISHED = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void keepsValidValues() {
        ContentFingerprint fingerprint = new ContentFingerprint(47_717, PUBLISHED);

        assertEquals(47_717, fingerprint.durationMs());
        assertEquals(PUBLISHED, fingerprint.publishedAt());
    }

    @Test
    void rejectsNegativeDuration() {
        assertThrows(IllegalStateException.class, () -> new ContentFingerprint(-1, PUBLISHED));
    }

    @Test
    void rejectsNullPublishedAt() {
        assertThrows(IllegalStateException.class, () -> new ContentFingerprint(1_000, null));
    }
}
