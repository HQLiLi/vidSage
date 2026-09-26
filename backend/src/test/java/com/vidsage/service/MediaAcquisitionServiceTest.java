package com.vidsage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.model.media.AcquiredMedia;
import com.vidsage.model.media.MediaInput;
import com.vidsage.provider.download.MediaAcquirer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * MediaAcquisitionService 的纯单元测试：<b>不起 Spring 容器</b>，用 Mockito 造两个互斥的适配器，
 * 验证「按 supports 分派」「无匹配时报错」「两个方法各自委托」。
 */
class MediaAcquisitionServiceTest {

    private static final Path WORK_DIR = Path.of("C:", "works", "task");
    private static final MediaInput.RemoteUrl REMOTE = new MediaInput.RemoteUrl("https://example.com/v");
    private static final MediaInput LOCAL = new MediaInput.LocalPath(Path.of("C:", "media", "a.mp4"));
    private static final AcquiredMedia MEDIA = new AcquiredMedia(Path.of("C:", "media", "x.mp4"), "t", 1000,
            Optional.empty());

    private final MediaAcquirer remoteAcquirer = mock(MediaAcquirer.class);
    private final MediaAcquirer localAcquirer = mock(MediaAcquirer.class);

    private MediaAcquisitionService service() {
        when(remoteAcquirer.supports(any())).thenAnswer(i -> i.getArgument(0) instanceof MediaInput.RemoteUrl);
        when(localAcquirer.supports(any())).thenAnswer(i -> i.getArgument(0) instanceof MediaInput.LocalPath);
        return new MediaAcquisitionService(List.of(remoteAcquirer, localAcquirer));
    }

    @Test
    void dispatchesRemoteUrlToYtDlpRoute() {
        when(remoteAcquirer.acquire(REMOTE, WORK_DIR)).thenReturn(MEDIA);

        assertSame(MEDIA, service().acquire(REMOTE, WORK_DIR));

        verify(remoteAcquirer).acquire(REMOTE, WORK_DIR);
        verify(localAcquirer, never()).acquire(any(), any());
    }

    @Test
    void dispatchesLocalPathToLocalRoute() {
        when(localAcquirer.acquire(LOCAL, WORK_DIR)).thenReturn(MEDIA);

        assertSame(MEDIA, service().acquire(LOCAL, WORK_DIR));

        verify(localAcquirer).acquire(LOCAL, WORK_DIR);
        verify(remoteAcquirer, never()).acquire(any(), any());
    }

    @Test
    void reuseExistingDelegatesToSupportingAcquirer() {
        when(remoteAcquirer.reuseExisting(REMOTE, WORK_DIR)).thenReturn(Optional.of(MEDIA));

        assertEquals(Optional.of(MEDIA), service().reuseExisting(REMOTE, WORK_DIR));

        verify(localAcquirer, never()).reuseExisting(any(), any());
    }

    @Test
    void reuseExistingPropagatesEmptyFromAcquirer() {
        when(remoteAcquirer.reuseExisting(REMOTE, WORK_DIR)).thenReturn(Optional.empty());

        assertTrue(service().reuseExisting(REMOTE, WORK_DIR).isEmpty());
    }

    @Test
    void throwsWhenNoAcquirerSupportsInput() {
        MediaAcquirer unsupported = mock(MediaAcquirer.class);
        when(unsupported.supports(any())).thenReturn(false);
        var service = new MediaAcquisitionService(List.of(unsupported));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.acquire(REMOTE, WORK_DIR));

        assertTrue(error.getMessage().contains("RemoteUrl"),
                () -> "错误信息应说清输入类型：" + error.getMessage());
        assertTrue(error.getMessage().contains("MediaAcquirer"),
                () -> "错误信息应指向缺失的适配器：" + error.getMessage());
    }

    @Test
    void rejectsNullInput() {
        var service = service();

        assertThrows(IllegalArgumentException.class, () -> service.acquire(null, WORK_DIR));
        assertThrows(IllegalArgumentException.class, () -> service.reuseExisting(null, WORK_DIR));
    }
}
