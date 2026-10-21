package com.vidsage.application.workspace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.IntermediateFileStore;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * {@link WorkspaceFingerprintStore} 的单元测试：作废按类型分流、整批写在一个事务里、
 * 文件回收在事务提交之后。仓储与两个 store 都用 mock，事务边界用不启 Spring 上下文的
 * 记录型事务管理器钉住。
 */
class WorkspaceFingerprintStoreTest {

    private static final long TASK_ID = 7L;
    private static final long WORKSPACE_ID = 9L;
    private static final LocalDateTime PUBLISHED = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final ContentFingerprint FINGERPRINT = new ContentFingerprint(47_717, PUBLISHED);

    private final List<String> events = new ArrayList<>();
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final SubtitleArtifactRepository artifactRepository = mock(SubtitleArtifactRepository.class);
    private final SubtitleArtifactStore artifactStore = mock(SubtitleArtifactStore.class);
    private final IntermediateFileStore intermediateFileStore = mock(IntermediateFileStore.class);
    private final WorkspaceFingerprintStore store = new WorkspaceFingerprintStore(
            workspaceRepository, artifactRepository, artifactStore, intermediateFileStore,
            new RecordingTransactionManager(events));

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @Test
    void writeFingerprintIssuesSingleUpdate() {
        store.writeFingerprint(WORKSPACE_ID, FINGERPRINT);

        verify(workspaceRepository).updateFingerprint(eq(WORKSPACE_ID), eq(47_717), eq(PUBLISHED),
                any(LocalDateTime.class));
        assertEquals(List.of(), events, "首跑写指纹是单条 UPDATE，不需要事务");
    }

    @Test
    void supersedeOnSourceMediaChangeSupersedesByTypeInOneTransactionAndReclaimsAfterCommit() {
        SubtitleArtifact original = artifact(ArtifactType.ORIGINAL, 1L);
        SubtitleArtifact cleaned = artifact(ArtifactType.CLEANED, 2L);
        SubtitleArtifact translated = artifact(ArtifactType.TRANSLATED, 3L);
        IntermediateFile intermediate = intermediate(11L);
        when(artifactRepository.findReadyByTaskId(TASK_ID)).thenReturn(List.of(original, cleaned, translated));
        when(intermediateFileStore.findReady(TASK_ID)).thenReturn(List.of(intermediate));
        recordWrites();

        store.supersedeOnSourceMediaChange(TASK_ID, WORKSPACE_ID, FINGERPRINT, workDir);

        assertEquals(List.of(
                "begin",
                "clear:1",
                "clear:2",
                "bulk-translated",
                "supersede-intermediates",
                "fingerprint",
                "commit",
                "reclaim-artifact:3",
                "reclaim-intermediate:11"), events,
                "作废与写指纹在同一事务内，文件回收在提交之后");
    }

    @Test
    void supersedeOnSourceMediaChangeRollsBackWhenAnyWriteFails() {
        SubtitleArtifact original = artifact(ArtifactType.ORIGINAL, 1L);
        when(artifactRepository.findReadyByTaskId(TASK_ID)).thenReturn(List.of(original));
        doThrow(new IllegalStateException("db down")).when(artifactRepository).markSupersededAndClearFiles(1L);

        assertThrows(IllegalStateException.class,
                () -> store.supersedeOnSourceMediaChange(TASK_ID, WORKSPACE_ID, FINGERPRINT, workDir));

        assertEquals(List.of("begin", "rollback"), events, "任一步失败必须整批回滚，不能写半截指纹");
        verify(workspaceRepository, never()).updateFingerprint(anyLong(), any(), any(), any());
    }

    @Test
    void reclaimFailureAfterCommitDoesNotFailTheCall() {
        SubtitleArtifact translated = artifact(ArtifactType.TRANSLATED, 3L);
        when(artifactRepository.findReadyByTaskId(TASK_ID)).thenReturn(List.of(translated));
        when(intermediateFileStore.findReady(TASK_ID)).thenReturn(List.of());
        doAnswer(invocation -> {
            events.add("fingerprint");
            return 1;
        }).when(workspaceRepository).updateFingerprint(anyLong(), any(), any(), any());
        doThrow(new IllegalStateException("reclaim boom")).when(artifactStore).reclaimSuperseded(any(), any());

        assertDoesNotThrow(() -> store.supersedeOnSourceMediaChange(TASK_ID, WORKSPACE_ID, FINGERPRINT, workDir));

        assertEquals(List.of("begin", "fingerprint", "commit"), events,
                "事务已提交；回收失败只记 WARN，不能让本次执行失败");
    }

    @Test
    void supersedeOnSourceMediaChangeWithoutTranslationsSkipsBulkUpdate() {
        when(artifactRepository.findReadyByTaskId(TASK_ID)).thenReturn(List.of());
        when(intermediateFileStore.findReady(TASK_ID)).thenReturn(List.of());
        recordWrites();

        store.supersedeOnSourceMediaChange(TASK_ID, WORKSPACE_ID, FINGERPRINT, workDir);

        verify(artifactRepository, never()).markSupersededByTaskIdAndType(anyLong(), any());
        verify(intermediateFileStore).supersedeAll(eq(TASK_ID), any(LocalDateTime.class));
        verify(workspaceRepository).updateFingerprint(eq(WORKSPACE_ID), eq(47_717), eq(PUBLISHED),
                any(LocalDateTime.class));
        assertEquals(List.of("begin", "supersede-intermediates", "fingerprint", "commit"), events);
    }

    private void recordWrites() {
        doAnswer(invocation -> {
            events.add("clear:" + invocation.getArgument(0));
            return 1;
        }).when(artifactRepository).markSupersededAndClearFiles(anyLong());
        doAnswer(invocation -> {
            events.add("bulk-translated");
            return 1;
        }).when(artifactRepository).markSupersededByTaskIdAndType(anyLong(), any());
        doAnswer(invocation -> {
            events.add("supersede-intermediates");
            return 1;
        }).when(intermediateFileStore).supersedeAll(anyLong(), any());
        doAnswer(invocation -> {
            events.add("fingerprint");
            return 1;
        }).when(workspaceRepository).updateFingerprint(anyLong(), any(), any(), any());
        doAnswer(invocation -> {
            events.add("reclaim-artifact:" + ((SubtitleArtifact) invocation.getArgument(0)).getId());
            return null;
        }).when(artifactStore).reclaimSuperseded(any(), any());
        doAnswer(invocation -> {
            events.add("reclaim-intermediate:" + ((IntermediateFile) invocation.getArgument(0)).getId());
            return null;
        }).when(intermediateFileStore).reclaimSuperseded(any(), any());
    }

    private static SubtitleArtifact artifact(ArtifactType type, long id) {
        SubtitleArtifact artifact = mock(SubtitleArtifact.class);
        when(artifact.getArtifactType()).thenReturn(type);
        when(artifact.getId()).thenReturn(id);
        return artifact;
    }

    private static IntermediateFile intermediate(long id) {
        IntermediateFile file = mock(IntermediateFile.class);
        when(file.getId()).thenReturn(id);
        return file;
    }

    /**
     * 记录型事务管理器：只把 begin / commit / rollback 记进事件表，不连真库。
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<String> events;

        RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }
}
