package com.vidsage.application.media.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.port.ContentFingerprintProbe;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentFingerprintProbeDispatcher} 的单元测试：按输入类型选实现、没有实现时
 * 明确回答「不支持」（不是「取不到」）、探测结果原样透传。
 */
class ContentFingerprintProbeDispatcherTest {

    private static final MediaInput.RemoteUrl REMOTE = new MediaInput.RemoteUrl("https://example.com/v");
    private static final ContentFingerprint FINGERPRINT =
            new ContentFingerprint(47_717, LocalDateTime.of(2026, 1, 1, 0, 0));

    @Test
    void supportsOnlyWhenSomeProbeAcceptsTheInput() {
        ContentFingerprintProbe probe = probeAccepting(REMOTE);
        ContentFingerprintProbeDispatcher dispatcher = new ContentFingerprintProbeDispatcher(List.of(probe));

        assertTrue(dispatcher.supports(REMOTE));
        assertFalse(dispatcher.supports(new MediaInput.LocalPath(java.nio.file.Path.of("a.mp4"))));
        assertFalse(dispatcher.supports(null));
    }

    @Test
    void delegatesProbeToSelectedImplementation() {
        ContentFingerprintProbe probe = probeAccepting(REMOTE);
        when(probe.probe(REMOTE)).thenReturn(Optional.of(FINGERPRINT));
        ContentFingerprintProbeDispatcher dispatcher = new ContentFingerprintProbeDispatcher(List.of(probe));

        assertEquals(Optional.of(FINGERPRINT), dispatcher.probe(REMOTE));
        verify(probe).probe(REMOTE);
    }

    @Test
    void returnsEmptyWhenNoProbeSupportsTheInput() {
        ContentFingerprintProbe probe = probeAccepting(REMOTE);
        ContentFingerprintProbeDispatcher dispatcher = new ContentFingerprintProbeDispatcher(List.of(probe));

        assertTrue(dispatcher.probe(new MediaInput.LocalPath(java.nio.file.Path.of("a.mp4"))).isEmpty());
        verify(probe, never()).probe(any());
    }

    private static ContentFingerprintProbe probeAccepting(MediaInput input) {
        ContentFingerprintProbe probe = mock(ContentFingerprintProbe.class);
        when(probe.supports(any())).thenAnswer(invocation -> invocation.getArgument(0) == input);
        return probe;
    }
}
