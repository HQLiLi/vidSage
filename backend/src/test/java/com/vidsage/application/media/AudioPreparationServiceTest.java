package com.vidsage.application.media;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.domain.media.AcquiredMedia;
import com.vidsage.domain.media.AudioArtifact;
import com.vidsage.domain.media.AudioContainer;
import com.vidsage.domain.media.AudioSpec;
import com.vidsage.domain.media.MediaInput;
import com.vidsage.domain.shared.ProgressListener;
import com.vidsage.domain.shared.StageEvent;
import com.vidsage.domain.media.AudioExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * AudioPreparationService 的纯单元测试：<b>不起 Spring 容器</b>，Mockito 替掉工作目录 / 输入获取 /
 * 音频抽取，只验证阶段编排、断点复用判定、事件序列与失败路径。
 */
class AudioPreparationServiceTest {

    // 本类会真的写音频产物文件，沿用 implement.md 记录的环境性清理规避：不清理临时目录。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final MediaAcquisitionService acquisitionService = mock(MediaAcquisitionService.class);
    private final AudioExtractor audioExtractor = mock(AudioExtractor.class);
    private final RecordingListener listener = new RecordingListener();

    private final MediaInput.RemoteUrl input = new MediaInput.RemoteUrl("https://example.com/video/1");

    @BeforeEach
    void setUp() {
        when(workspaceService.workspaceFor(any())).thenReturn(workDir);
    }

    private AudioPreparationService service() {
        return new AudioPreparationService(workspaceService, acquisitionService, audioExtractor);
    }

    private AcquiredMedia acquiredMedia() {
        return new AcquiredMedia(workDir.resolve("source.mp4"), "示例标题", 47_717, Optional.empty());
    }

    // ---------- 首次运行 ----------

    @Test
    void runsBothStagesOnFirstRun() {
        AcquiredMedia media = acquiredMedia();
        Path audioPath = workDir.resolve("audio-16k1ch-t0.wav");
        var artifact = new AudioArtifact(audioPath, AudioSpec.WAV_PCM_16K_MONO, 12);
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.empty());
        when(acquisitionService.acquire(input, workDir)).thenReturn(media);
        when(audioExtractor.extract(media.mediaPath(), audioPath, AudioSpec.WAV_PCM_16K_MONO)).thenReturn(artifact);

        AudioArtifact result = service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, listener);

        assertSame(artifact, result);
        verify(audioExtractor).extract(media.mediaPath(), audioPath, AudioSpec.WAV_PCM_16K_MONO);
        assertEquals(List.of(
                "acquire/RUNNING", "acquire/SUCCEEDED",
                "extract-audio/RUNNING", "extract-audio/SUCCEEDED"), states());
        assertEquals(List.of(0, 50, 50, 100), progresses(), "进度应两段加权且单调");
    }

    @Test
    void usesM4aFileNameForM4aSpec() {
        AcquiredMedia media = acquiredMedia();
        Path audioPath = workDir.resolve("audio-16k1ch48k-t0.m4a");
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.of(media));
        when(audioExtractor.extract(media.mediaPath(), audioPath, AudioSpec.M4A_AAC_48K_MONO))
                .thenReturn(new AudioArtifact(audioPath, AudioSpec.M4A_AAC_48K_MONO, 9));

        service().prepare(input, AudioSpec.M4A_AAC_48K_MONO, listener);

        verify(audioExtractor).extract(media.mediaPath(), audioPath, AudioSpec.M4A_AAC_48K_MONO);
    }

    @Test
    void namesAudioFileWithFullSpecFingerprint() {
        // 有损容器（M4A）带码率段；无损容器（WAV）不带码率段
        assertEquals("audio-16k1ch48k-t0.m4a",
                AudioPreparationService.audioFileName(AudioSpec.M4A_AAC_48K_MONO));
        assertEquals("audio-16k1ch-t0.wav",
                AudioPreparationService.audioFileName(AudioSpec.WAV_PCM_16K_MONO));
        // 轨号 0 也照写 -t0（不做省略特例）；-t 始终带连字符，与 M4A 的 -t0 保持同一形状
        assertEquals("audio-16k1ch-t1.wav",
                AudioPreparationService.audioFileName(new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 1)));
        // 非整千赫兹保留小数，避免与其它采样率截断成同一名字
        assertEquals("audio-44.1k1ch-t0.wav",
                AudioPreparationService.audioFileName(new AudioSpec(AudioContainer.WAV, 44100, 1, 0, 0)));
    }

    @Test
    void differentSpecsMapToDifferentAudioPaths() {
        // 改 1 的回归：同一输入、仅轨号（或采样率）不同的 spec，不得映射到同一文件，
        // 否则第二次会命中第一次的产物被断点复用跳过，静默返回与请求规格不符的音频。
        AcquiredMedia media = acquiredMedia();
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.of(media));
        when(audioExtractor.extract(any(), any(), any())).thenAnswer(invocation ->
                new AudioArtifact(invocation.getArgument(1), invocation.getArgument(2), 1));

        AudioSpec track0 = new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 0);
        AudioSpec track1 = new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 1);
        AudioSpec rate8k = new AudioSpec(AudioContainer.WAV, 8000, 1, 0, 0);

        Path p0 = service().prepare(input, track0, listener).audioPath();
        Path p1 = service().prepare(input, track1, listener).audioPath();
        Path p8 = service().prepare(input, rate8k, listener).audioPath();

        assertEquals(workDir.resolve("audio-16k1ch-t0.wav"), p0);
        assertEquals(workDir.resolve("audio-16k1ch-t1.wav"), p1);
        assertEquals(workDir.resolve("audio-8k1ch-t0.wav"), p8);
        assertEquals(3, List.of(p0, p1, p8).stream().distinct().count(),
                () -> "不同规格必须映射到不同文件，否则断点复用会静默跳过：" + List.of(p0, p1, p8));
    }

    // ---------- 断点复用 ----------

    @Test
    void skipsAcquireWhenMediaCanBeReused() {
        AcquiredMedia media = acquiredMedia();
        Path audioPath = workDir.resolve("audio-16k1ch-t0.wav");
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.of(media));
        when(audioExtractor.extract(any(), any(), any()))
                .thenReturn(new AudioArtifact(audioPath, AudioSpec.WAV_PCM_16K_MONO, 8));

        service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, listener);

        verify(acquisitionService, never()).acquire(any(), any());
        assertEquals(List.of("acquire/SKIPPED", "extract-audio/RUNNING", "extract-audio/SUCCEEDED"),
                states());
        assertEquals(50, listener.events().getFirst().progress(), "跳过的 acquire 进度取该阶段终点值");
    }

    @Test
    void skipsExtractWhenAudioAlreadyExists() throws IOException {
        AcquiredMedia media = acquiredMedia();
        Files.write(workDir.resolve("audio-16k1ch-t0.wav"), new byte[5]);
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.of(media));

        AudioArtifact result = service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, listener);

        verify(audioExtractor, never()).extract(any(), any(), any());
        assertEquals(workDir.resolve("audio-16k1ch-t0.wav"), result.audioPath());
        assertEquals(5, result.sizeBytes());
        assertEquals(List.of("acquire/SKIPPED", "extract-audio/SKIPPED"), states());
        assertEquals(100, listener.events().getLast().progress(), "跳过的 extract 进度取该阶段终点值");
    }

    @Test
    void treatsZeroByteAudioAsMissing() throws IOException {
        AcquiredMedia media = acquiredMedia();
        Path audioPath = workDir.resolve("audio-16k1ch-t0.wav");
        Files.write(audioPath, new byte[0]);
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.of(media));
        when(audioExtractor.extract(media.mediaPath(), audioPath, AudioSpec.WAV_PCM_16K_MONO))
                .thenReturn(new AudioArtifact(audioPath, AudioSpec.WAV_PCM_16K_MONO, 7));

        service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, listener);

        verify(audioExtractor).extract(media.mediaPath(), audioPath, AudioSpec.WAV_PCM_16K_MONO);
        assertEquals(List.of("acquire/SKIPPED", "extract-audio/RUNNING", "extract-audio/SUCCEEDED"),
                states());
    }

    // ---------- 失败路径 ----------

    @Test
    void emitsFailedAndRethrowsWhenExtractorFails() {
        AcquiredMedia media = acquiredMedia();
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.of(media));
        when(audioExtractor.extract(any(), any(), any()))
                .thenThrow(new IllegalStateException("ffmpeg 抽音频失败（exit=1）：boom"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, listener));

        assertTrue(error.getMessage().contains("boom"));
        assertEquals(List.of("acquire/SKIPPED", "extract-audio/RUNNING", "extract-audio/FAILED"), states());
        assertTrue(listener.events().getLast().message().contains("boom"),
                () -> "FAILED 消息应取异常 message：" + listener.events().getLast().message());
    }

    @Test
    void emitsFailedAndRethrowsWhenAcquireFails() {
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.empty());
        when(acquisitionService.acquire(input, workDir))
                .thenThrow(new IllegalArgumentException("本地媒体文件不存在或不可读：F:/x.mp4"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, listener));

        assertTrue(error.getMessage().contains("本地媒体文件不存在"));
        assertEquals(List.of("acquire/RUNNING", "acquire/FAILED"), states());
        assertTrue(listener.events().getLast().message().contains("本地媒体文件不存在"),
                () -> "FAILED 消息应取异常 message：" + listener.events().getLast().message());
    }

    @Test
    void emitsFailedAndRethrowsWhenReuseCheckFails() {
        when(acquisitionService.reuseExisting(input, workDir))
                .thenThrow(new IllegalStateException("扫描下载工作目录失败"));

        var error = assertThrows(IllegalStateException.class,
                () -> service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, listener));

        assertTrue(error.getMessage().contains("扫描下载工作目录失败"));
        assertEquals(List.of("acquire/FAILED"), states());
    }

    // ---------- 无监听重载与卫语句 ----------

    @Test
    void overloadWithoutListenerDoesNotThrow() {
        AcquiredMedia media = acquiredMedia();
        Path audioPath = workDir.resolve("audio-16k1ch-t0.wav");
        when(acquisitionService.reuseExisting(input, workDir)).thenReturn(Optional.of(media));
        when(audioExtractor.extract(media.mediaPath(), audioPath, AudioSpec.WAV_PCM_16K_MONO))
                .thenReturn(new AudioArtifact(audioPath, AudioSpec.WAV_PCM_16K_MONO, 3));

        assertDoesNotThrow(() -> service().prepare(input, AudioSpec.WAV_PCM_16K_MONO));
    }

    @Test
    void rejectsNullListener() {
        // 改 3：显式传 null 不再被静默当成 noop()——那会让「忘了传 listener」变成进度无声消失
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service().prepare(input, AudioSpec.WAV_PCM_16K_MONO, null));

        assertTrue(error.getMessage().contains("prepare(input, spec)"),
                () -> "消息应指出两参重载的替代做法：" + error.getMessage());
    }

    // 原 rejectsInvalidSpecEvenWhenAudioFileExists 已删除（刻意，非遗漏）。
    //
    // 它守护的是一次真实修复（当时注释为「改 2：spec 校验前置到断点判断之前」）：断点分支会直接
    // 返回产物、不经过抽取实现，故「传了非法 spec 而对应文件恰好存在」时必须先抛，不得返回一份
    // 未经校验的产物。
    //
    // 删除理由：spec 字段校验搬进 AudioSpec 的紧凑构造器后，非法 AudioSpec 不可能存在，
    // 「非法 spec + 断点命中」这个场景**结构性无法构造**——无法改写，只能删。它守护的整类 bug
    // 随之消失，这是构造器方案的最强收益，不是测试覆盖的损失；契约的另一半（契约本身不可被违反）
    // 由 domain/media/AudioSpecTest 的构造期拒绝用例覆盖。

    @Test
    void rejectsNullInputAndNullSpec() {
        var service = service();

        assertThrows(IllegalArgumentException.class, () -> service.prepare(null, AudioSpec.WAV_PCM_16K_MONO));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(input, null));
    }

    // ---------- 辅助 ----------

    private List<String> states() {
        return listener.events().stream().map(event -> event.stage() + "/" + event.state()).toList();
    }

    private List<Integer> progresses() {
        return listener.events().stream().map(StageEvent::progress).toList();
    }

    /** 记录全部阶段事件的监听器实现。 */
    private static final class RecordingListener implements ProgressListener {

        private final List<StageEvent> events = new ArrayList<>();

        @Override
        public void onEvent(StageEvent event) {
            events.add(event);
        }

        List<StageEvent> events() {
            return events;
        }
    }
}
