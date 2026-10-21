package com.vidsage.domain.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link Workspace} 的内容指纹判据：首跑（两列全空）与「内容没变」（两列都非空且逐列相等）。
 * 只空一列不算首跑、也不算没变——比不全就当变了，方向往安全靠。
 */
class WorkspaceTest {

    private static final LocalDateTime PUBLISHED = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void newWorkspaceHasUnknownFingerprint() {
        Workspace workspace = urlWorkspace();

        assertTrue(workspace.hasUnknownFingerprint());
        assertFalse(workspace.matchesFingerprint(new ContentFingerprint(1_000, PUBLISHED)));
    }

    @Test
    void updateFingerprintMakesItKnownAndMatchable() {
        Workspace workspace = urlWorkspace();

        workspace.updateFingerprint(47_717, PUBLISHED);

        assertFalse(workspace.hasUnknownFingerprint());
        assertTrue(workspace.matchesFingerprint(new ContentFingerprint(47_717, PUBLISHED)));
    }

    @Test
    void matchesFingerprintRequiresBothColumnsEqual() {
        Workspace workspace = urlWorkspace();
        workspace.updateFingerprint(47_717, PUBLISHED);

        assertFalse(workspace.matchesFingerprint(new ContentFingerprint(47_718, PUBLISHED)),
                "时长不同就是内容变了");
        assertFalse(workspace.matchesFingerprint(new ContentFingerprint(47_717, PUBLISHED.plusSeconds(1))),
                "发布时间不同就是内容变了");
    }

    @Test
    void oneNullColumnIsNotUnknownButCountsAsChanged() {
        Workspace workspace = urlWorkspace();
        workspace.updateFingerprint(47_717, null);

        assertFalse(workspace.hasUnknownFingerprint(), "只空一列不是首跑");
        assertFalse(workspace.matchesFingerprint(new ContentFingerprint(47_717, PUBLISHED)),
                "比不全就当变了，安全方向是宁可多作废一次");
    }

    private static Workspace urlWorkspace() {
        return Workspace.of(SourceType.URL, new SourceKey("BV1|1"), "https://example.com/v", "https://example.com/v");
    }
}
