package com.vidsage.domain.intermediate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.intermediate.model.IntermediateFileStatus;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link IntermediateFile} 状态机的纯单元测试：不起容器。
 *
 * <p>逐值覆盖：{@code READY}（出生 / 复活）⇄ {@code SUPERSEDED} → {@code DELETED}；
 * 非法迁移抛 {@link IllegalStateException}。只有三个值——{@code CREATING} / {@code FAILED} 对纯缓存是硬套。
 */
class IntermediateFileTest {

    // ---------- 出生状态 ----------

    @Test
    void ofReadyStartsReady() {
        IntermediateFile file = IntermediateFile.ofReady(7L, "source.mp4", IntermediateFileType.SOURCE_MEDIA, 3L);

        assertEquals(IntermediateFileStatus.READY, file.getStatus());
        assertEquals(7L, file.getTaskId());
        assertEquals("source.mp4", file.getFileName());
        assertEquals(IntermediateFileType.SOURCE_MEDIA, file.getFileType());
        assertEquals(3L, file.getDerivedFromRunId());
        assertNull(file.getId(), "未落库前 id 应为 null");
        assertNotNull(file.getCreatedAt());
        assertEquals(file.getCreatedAt(), file.getUpdatedAt());
    }

    @Test
    void restoreRebuildsAnyStatus() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 17, 10, 0);
        IntermediateFile file = IntermediateFile.restore(11L, 7L, "audio.mp3", IntermediateFileType.AUDIO,
                IntermediateFileStatus.DELETED, 3L, created, created.plusMinutes(1));

        assertEquals(11L, file.getId());
        assertEquals(IntermediateFileStatus.DELETED, file.getStatus());
        assertEquals(IntermediateFileType.AUDIO, file.getFileType());
        assertEquals(created.plusMinutes(1), file.getUpdatedAt());
    }

    // ---------- 合法迁移 ----------

    @Test
    void supersedeTransitionsReadyToSuperseded() {
        IntermediateFile file = ready();

        file.supersede();

        assertEquals(IntermediateFileStatus.SUPERSEDED, file.getStatus());
        assertFalse(file.getUpdatedAt().isBefore(file.getCreatedAt()), "变更应刷 updatedAt");
    }

    @Test
    void confirmDeletionTransitionsSupersededToDeleted() {
        IntermediateFile file = ready();
        file.supersede();

        file.confirmDeletion();

        assertEquals(IntermediateFileStatus.DELETED, file.getStatus());
    }

    @Test
    void reviveFromSuperseded() {
        IntermediateFile file = ready();
        file.supersede();

        file.revive();

        assertEquals(IntermediateFileStatus.READY, file.getStatus(), "SUPERSEDED 可以重新产出回 READY");
    }

    @Test
    void reviveFromDeleted() {
        IntermediateFile file = ready();
        file.supersede();
        file.confirmDeletion();

        file.revive();

        assertEquals(IntermediateFileStatus.READY, file.getStatus(), "DELETED 也可以重新产出回 READY");
    }

    // ---------- 非法迁移拒绝 ----------

    @Test
    void throwsWhenSupersedingFromNonReadyStates() {
        IntermediateFile superseded = ready();
        superseded.supersede();
        IntermediateFile deleted = ready();
        deleted.supersede();
        deleted.confirmDeletion();

        IllegalStateException e = assertThrows(IllegalStateException.class, superseded::supersede);
        assertTrue(e.getMessage().contains("非法状态迁移"), e.getMessage());
        assertThrows(IllegalStateException.class, deleted::supersede);
    }

    @Test
    void throwsWhenConfirmingDeletionFromNonSupersededStates() {
        IntermediateFile ready = ready();
        IntermediateFile deleted = ready();
        deleted.supersede();
        deleted.confirmDeletion();

        assertThrows(IllegalStateException.class, ready::confirmDeletion);
        assertThrows(IllegalStateException.class, deleted::confirmDeletion);
    }

    @Test
    void throwsWhenRevivingWhileAlreadyReady() {
        IntermediateFile ready = ready();

        assertThrows(IllegalStateException.class, ready::revive,
                "已在 READY 的中间产物不需要「复活」，只有 SUPERSEDED / DELETED 能回 READY");
    }

    // ---------- 工厂 ----------

    private static IntermediateFile ready() {
        return IntermediateFile.ofReady(7L, "source.mp4", IntermediateFileType.SOURCE_MEDIA, 3L);
    }
}
