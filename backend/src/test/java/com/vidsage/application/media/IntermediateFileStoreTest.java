package com.vidsage.application.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.intermediate.model.IntermediateFileStatus;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import com.vidsage.domain.intermediate.port.IntermediateFileRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * {@link IntermediateFileStore} 的单元测试：复用判据是「表里 READY 且文件在」、
 * 登记先落盘后写表、回收是两阶段。仓储用 mock，文件用真实临时目录。
 */
class IntermediateFileStoreTest {

    private static final long TASK_ID = 7L;
    private static final long RUN_ID = 3L;

    private final IntermediateFileRepository repository = mock(IntermediateFileRepository.class);
    private final IntermediateFileStore store = new IntermediateFileStore(repository);

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    // ---------- 复用判据 ----------

    @Test
    void reusableOnlyWhenRowIsReadyAndFilePresent() throws IOException {
        when(repository.findReadyByTaskIdAndFileName(TASK_ID, "source.mp4"))
                .thenReturn(Optional.of(file("source.mp4", IntermediateFileStatus.READY)));
        Files.write(workDir.resolve("source.mp4"), new byte[4]);

        assertTrue(store.isReusable(TASK_ID, "source.mp4", workDir));
    }

    @Test
    void notReusableWhenReadyRowButFileMissing() {
        when(repository.findReadyByTaskIdAndFileName(TASK_ID, "source.mp4"))
                .thenReturn(Optional.of(file("source.mp4", IntermediateFileStatus.READY)));

        assertFalse(store.isReusable(TASK_ID, "source.mp4", workDir),
                "表说 READY 但文件不在，不能当可复用——放回去才重新可见");
        verify(repository, never()).markDeleted(anyLong(), any());
    }

    @Test
    void notReusableWithoutReadyRow() {
        when(repository.findReadyByTaskIdAndFileName(TASK_ID, "source.mp4")).thenReturn(Optional.empty());

        assertFalse(store.isReusable(TASK_ID, "source.mp4", workDir));
    }

    // ---------- 登记 ----------

    @Test
    void recordProducedInsertsWhenNoRowExists() {
        when(repository.findByTaskIdAndFileName(TASK_ID, "audio-16k1ch.m4a")).thenReturn(Optional.empty());

        store.recordProduced(TASK_ID, RUN_ID, IntermediateFileType.AUDIO, workDir,
                workDir.resolve("audio-16k1ch.m4a"));

        ArgumentCaptor<IntermediateFile> captor = ArgumentCaptor.forClass(IntermediateFile.class);
        verify(repository).insertReady(captor.capture());
        assertEquals("audio-16k1ch.m4a", captor.getValue().getFileName());
        assertEquals(IntermediateFileType.AUDIO, captor.getValue().getFileType());
        assertEquals(IntermediateFileStatus.READY, captor.getValue().getStatus());
        assertEquals(RUN_ID, captor.getValue().getDerivedFromRunId());
    }

    @Test
    void recordProducedRevivesExistingRowWithTypeAndRun() {
        when(repository.findByTaskIdAndFileName(TASK_ID, "audio-16k1ch.m4a"))
                .thenReturn(Optional.of(file("audio-16k1ch.m4a", IntermediateFileStatus.SUPERSEDED)));

        store.recordProduced(TASK_ID, RUN_ID, IntermediateFileType.AUDIO, workDir,
                workDir.resolve("audio-16k1ch.m4a"));

        verify(repository).markReady(eq(11L), eq(IntermediateFileType.AUDIO), eq(RUN_ID), any(LocalDateTime.class));
        verify(repository, never()).insertReady(any());
    }

    @Test
    void recordProducedSkipsFileOutsideWorkspace() {
        store.recordProduced(TASK_ID, RUN_ID, IntermediateFileType.SOURCE_MEDIA, workDir,
                workDir.getParent().resolve("outside.mp4"));

        verifyNoInteractions(repository);
    }

    // ---------- 级联与回收 ----------

    @Test
    void findReadyFiltersOutNonReadyRows() {
        when(repository.findByTaskId(TASK_ID)).thenReturn(List.of(
                file("a.mp4", IntermediateFileStatus.READY),
                file("b.mp4", IntermediateFileStatus.SUPERSEDED),
                file("c.mp4", IntermediateFileStatus.DELETED)));

        List<IntermediateFile> ready = store.findReady(TASK_ID);

        assertEquals(1, ready.size());
        assertEquals("a.mp4", ready.getFirst().getFileName());
    }

    @Test
    void supersedeAllDelegatesWithGivenTimestamp() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 19, 12, 0);

        assertEquals(0, store.supersedeAll(TASK_ID, at));
        verify(repository).markSupersededByTaskId(TASK_ID, at);
    }

    @Test
    void reclaimDeletesFileThenMarksDeleted() throws IOException {
        Path file = workDir.resolve("source.mp4");
        Files.write(file, new byte[4]);

        store.reclaimSuperseded(file("source.mp4", IntermediateFileStatus.SUPERSEDED), workDir);

        assertFalse(Files.exists(file), "回收成功时文件应已删掉");
        verify(repository).markDeleted(eq(11L), any(LocalDateTime.class));
    }

    @Test
    void reclaimKeepsSupersededWhenDeletionFails() throws IOException {
        // 非空目录删不掉：模拟文件被占用 / 权限不足
        Path directory = workDir.resolve("source.mp4");
        Files.createDirectory(directory);
        Files.writeString(directory.resolve("child"), "x");

        store.reclaimSuperseded(file("source.mp4", IntermediateFileStatus.SUPERSEDED), workDir);

        verify(repository, never()).markDeleted(anyLong(), any());
    }

    private static IntermediateFile file(String fileName, IntermediateFileStatus status) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 12, 0);
        return IntermediateFile.restore(11L, TASK_ID, fileName, IntermediateFileType.SOURCE_MEDIA,
                status, RUN_ID, now, now);
    }
}
