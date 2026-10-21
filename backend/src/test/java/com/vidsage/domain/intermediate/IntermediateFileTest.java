package com.vidsage.domain.intermediate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vidsage.domain.intermediate.model.IntermediateFileStatus;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link IntermediateFile} 的纯单元测试：不起容器。
 *
 * <p>本类只有出生与还原两个入口——状态迁移不在这里建模（作废与回收都是仓储上的集合式 UPDATE，
 * 见 {@code .trellis/spec/backend/state-machine-guidelines.md}），所以也不测迁移。
 */
class IntermediateFileTest {

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
}
