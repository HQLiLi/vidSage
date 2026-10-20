package com.vidsage.application.subtitle.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.pipeline.ContentFingerprintProbeDispatcher;
import com.vidsage.application.workspace.WorkspaceFingerprintStore;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SubtitleFingerprintCoordinator} 的单元测试：两种跳过（路线没这一维 / 远程没探测器）、
 * 取不到指纹降级、首跑只写指纹、内容未变不动作、内容变了走级联、级联失败必须抛。
 */
class SubtitleFingerprintCoordinatorTest {

    private static final long TASK_ID = 7L;
    private static final long WORKSPACE_ID = 9L;
    private static final LocalDateTime PUBLISHED = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final ContentFingerprint FINGERPRINT = new ContentFingerprint(47_717, PUBLISHED);
    private static final MediaInput.RemoteUrl REMOTE = new MediaInput.RemoteUrl("https://example.com/v");
    private static final String STAGE = SubtitleStageName.PROBE_FINGERPRINT.getStageName();

    private final ContentFingerprintProbeDispatcher probeDispatcher = mock(ContentFingerprintProbeDispatcher.class);
    private final WorkspaceFingerprintStore fingerprintStore = mock(WorkspaceFingerprintStore.class);
    private final SubtitleFingerprintCoordinator coordinator =
            new SubtitleFingerprintCoordinator(probeDispatcher, fingerprintStore);
    private final RecordingListener listener = new RecordingListener();

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @Test
    void skipsProbeForNonRemoteRouteWithoutAskingAnyProbe() {
        MediaInput.InWorkspace upload = new MediaInput.InWorkspace(workDir.resolve("source.mp4"));

        coordinator.detectContentChange(TASK_ID, uploadWorkspace(), upload, workDir, listener);

        // 路线分叉在问探测器之前：上传 / 本机路线根本不该被问到「有没有实现」
        verify(probeDispatcher, never()).supports(any());
        verify(probeDispatcher, never()).probe(any());
        verifyNoInteractions(fingerprintStore);
        assertEquals(List.of("SKIPPED"), states());
        assertEquals(STAGE, listener.events().getFirst().stage());
    }

    @Test
    void degradesWhenRemoteRouteHasNoProbeImplementation() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(false);

        coordinator.detectContentChange(TASK_ID, urlWorkspace(47_717, PUBLISHED), REMOTE, workDir, listener);

        verify(probeDispatcher, never()).probe(any());
        verifyNoInteractions(fingerprintStore);
        assertEquals(List.of("SKIPPED"), states());
        assertEquals(STAGE, listener.events().getFirst().stage());
    }

    @Test
    void degradesWhenProbeThrows() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(true);
        when(probeDispatcher.probe(REMOTE)).thenThrow(new IllegalStateException("yt-dlp 探测失败"));

        coordinator.detectContentChange(TASK_ID, urlWorkspace(47_717, PUBLISHED), REMOTE, workDir, listener);

        verifyNoInteractions(fingerprintStore);
        assertEquals(List.of("RUNNING", "SKIPPED"), states());
    }

    @Test
    void degradesWhenProbeReturnsEmpty() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(true);
        when(probeDispatcher.probe(REMOTE)).thenReturn(Optional.empty());

        coordinator.detectContentChange(TASK_ID, urlWorkspace(47_717, PUBLISHED), REMOTE, workDir, listener);

        verifyNoInteractions(fingerprintStore);
        assertEquals(List.of("RUNNING", "SKIPPED"), states());
    }

    @Test
    void firstRunOnlyWritesFingerprint() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(true);
        when(probeDispatcher.probe(REMOTE)).thenReturn(Optional.of(FINGERPRINT));

        coordinator.detectContentChange(TASK_ID, urlWorkspace(null, null), REMOTE, workDir, listener);

        verify(fingerprintStore).writeFingerprint(WORKSPACE_ID, FINGERPRINT);
        verify(fingerprintStore, never()).supersedeOnSourceMediaChange(anyLong(), anyLong(), any(), any());
        assertEquals(List.of("RUNNING", "SUCCEEDED"), states());
    }

    @Test
    void unchangedContentDoesNothing() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(true);
        when(probeDispatcher.probe(REMOTE)).thenReturn(Optional.of(FINGERPRINT));

        coordinator.detectContentChange(TASK_ID, urlWorkspace(47_717, PUBLISHED), REMOTE, workDir, listener);

        verifyNoInteractions(fingerprintStore);
        assertEquals(List.of("RUNNING", "SUCCEEDED"), states());
    }

    @Test
    void changedContentCascades() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(true);
        when(probeDispatcher.probe(REMOTE)).thenReturn(Optional.of(FINGERPRINT));

        coordinator.detectContentChange(TASK_ID, urlWorkspace(1_000, PUBLISHED), REMOTE, workDir, listener);

        verify(fingerprintStore).supersedeOnSourceMediaChange(TASK_ID, WORKSPACE_ID, FINGERPRINT, workDir);
        assertEquals(List.of("RUNNING", "SUCCEEDED"), states());
    }

    @Test
    void oneNullColumnCountsAsChanged() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(true);
        when(probeDispatcher.probe(REMOTE)).thenReturn(Optional.of(FINGERPRINT));

        // 只空一列不是首跑：比不全就当变了，走级联
        coordinator.detectContentChange(TASK_ID, urlWorkspace(47_717, null), REMOTE, workDir, listener);

        verify(fingerprintStore).supersedeOnSourceMediaChange(TASK_ID, WORKSPACE_ID, FINGERPRINT, workDir);
    }

    @Test
    void cascadeFailurePropagates() {
        when(probeDispatcher.supports(REMOTE)).thenReturn(true);
        when(probeDispatcher.probe(REMOTE)).thenReturn(Optional.of(FINGERPRINT));
        org.mockito.Mockito.doThrow(new IllegalStateException("级联事务失败"))
                .when(fingerprintStore).supersedeOnSourceMediaChange(anyLong(), anyLong(), any(), any());

        assertThrows(IllegalStateException.class, () -> coordinator.detectContentChange(
                TASK_ID, urlWorkspace(1_000, PUBLISHED), REMOTE, workDir, listener));
    }

    /** URL 路线的媒体身份：指纹两列按入参给（首跑传两个 {@code null}）。 */
    private static Workspace urlWorkspace(Integer durationMs, LocalDateTime publishedAt) {
        return workspace(SourceType.URL, new SourceKey("BV1|1"), "https://example.com/v",
                "https://example.com/v", durationMs, publishedAt);
    }

    /** 上传路线的媒体身份：内容身份是 sha256，指纹两列恒空。 */
    private static Workspace uploadWorkspace() {
        return workspace(SourceType.UPLOAD, new SourceKey("a".repeat(64)), "upload.mp4", null, null, null);
    }

    private static Workspace workspace(SourceType sourceType, SourceKey sourceKey, String sourceUri,
                                       String canonicalUrl, Integer durationMs, LocalDateTime publishedAt) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 12, 0);
        return Workspace.restore(WORKSPACE_ID, sourceType, sourceKey, sourceUri, canonicalUrl,
                durationMs, publishedAt, now, now);
    }

    private List<String> states() {
        return listener.events().stream().map(event -> event.state().name()).toList();
    }

    /** 记录全部阶段事件的监听器实现。 */
    private static final class RecordingListener implements ProgressListener {

        private final List<StageProgress> events = new ArrayList<>();

        @Override
        public void onProgress(StageProgress event) {
            events.add(event);
        }

        List<StageProgress> events() {
            return events;
        }
    }
}
