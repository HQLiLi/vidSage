package com.vidsage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.model.asr.AudioSource;
import com.vidsage.model.asr.TranscriptionResult;
import com.vidsage.model.asr.TranscriptionSegment;
import com.vidsage.model.media.AudioArtifact;
import com.vidsage.model.media.AudioContainer;
import com.vidsage.model.media.AudioSpec;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.progress.ProgressListener;
import com.vidsage.model.subtitle.SubtitleArtifact;
import com.vidsage.model.subtitle.SubtitleRoute;
import com.vidsage.model.subtitle.SubtitleTrack;
import com.vidsage.provider.asr.ASRProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitleService 的纯单元测试：不起 Spring 容器，只 new 被测对象；编排路径的依赖用
 * mock 注入，纯变换（时间格式 / SRT 渲染 / 产物名）直接调包级 static。
 * 会写文件的用例用 {@code @TempDir(CleanupMode.NEVER)}——Windows 上清理偶发失败。
 */
class SubtitleServiceTest {

    private static final SubtitleRoute ROUTE = SubtitleRoute.ASR_CLOUD;

    // ---------- routeToken ----------

    @Test
    void derivesKebabTokenFromAllFourRouteValues() {
        assertEquals("platform-manual", SubtitleService.routeToken(SubtitleRoute.PLATFORM_MANUAL));
        assertEquals("platform-auto", SubtitleService.routeToken(SubtitleRoute.PLATFORM_AUTO));
        assertEquals("asr-local", SubtitleService.routeToken(SubtitleRoute.ASR_LOCAL));
        assertEquals("asr-cloud", SubtitleService.routeToken(SubtitleRoute.ASR_CLOUD));
    }

    @Test
    void artifactFileNamesCarryRouteFingerprint() {
        assertEquals("subtitles-asr-cloud.srt", SubtitleService.srtFileName(SubtitleRoute.ASR_CLOUD));
        assertEquals("segments-asr-cloud.json", SubtitleService.segmentsFileName(SubtitleRoute.ASR_CLOUD));
        assertEquals("subtitles-asr-local.srt", SubtitleService.srtFileName(SubtitleRoute.ASR_LOCAL));
        assertEquals("segments-asr-local.json", SubtitleService.segmentsFileName(SubtitleRoute.ASR_LOCAL));
    }

    // ---------- formatTimestamp ----------

    @Test
    void formatsSubMillisecondTimes() {
        assertEquals("00:00:00,000", SubtitleService.formatTimestamp(0));
        assertEquals("00:00:00,999", SubtitleService.formatTimestamp(999));
        assertEquals("00:00:01,000", SubtitleService.formatTimestamp(1000));
    }

    @Test
    void formatsTimesBeyondOneHour() {
        assertEquals("01:00:00,000", SubtitleService.formatTimestamp(3_600_000));
        assertEquals("01:01:01,001", SubtitleService.formatTimestamp(3_661_001));
        // 超过 24 小时自然进位进小时位（不绕回天）
        assertEquals("25:00:00,000", SubtitleService.formatTimestamp(90_000_000));
    }

    @Test
    void clampsNegativeTimestampsToZero() {
        assertEquals("00:00:00,000", SubtitleService.formatTimestamp(-1));
        assertEquals("00:00:00,000", SubtitleService.formatTimestamp(-999_999));
    }

    // ---------- toSrt ----------

    @Test
    void rendersEmptySegmentsAsEmptyString() {
        assertEquals("", SubtitleService.toSrt(List.of()));
    }

    @Test
    void rendersBlocksWithSequenceAndBlankLines() {
        var result = SubtitleService.toSrt(List.of(
                new TranscriptionSegment(0, 1440, "欢迎使用阿里云。"),
                new TranscriptionSegment(1440, 3000, "今天天气不错。")));

        String expected = """
                1
                00:00:00,000 --> 00:00:01,440
                欢迎使用阿里云。

                2
                00:00:01,440 --> 00:00:03,000
                今天天气不错。

                """;
        assertEquals(expected, result);
    }

    // ---------- prepare 编排 ----------

    @Test
    void emptyTranscriptionProducesZeroByteSrtAndValidJson(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var service = serviceWith(
                tempDir, ROUTE, AudioSpec.M4A_AAC_48K_MONO, List.of(), "zh", mapper);

        SubtitleArtifact artifact = service.prepare(input(tempDir), ProgressListener.noop());

        assertEquals(ROUTE, artifact.route());
        assertEquals(0, artifact.segmentCount());
        assertEquals(0, artifact.srtSizeBytes());

        String json = Files.readString(artifact.segmentsPath());
        var track = mapper.readValue(json, SubtitleTrack.class);
        assertEquals(0, track.segments().size(), "空转写 JSON 里 segments 应为 []");
        assertEquals(ROUTE, track.route());
    }

    @Test
    void checkpointHitsWhenSegmentsJsonExists(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 预置非空 segments JSON → prepare 应跳过音频准备与转写
        var track = new SubtitleTrack(ROUTE, "zh",
                List.of(new TranscriptionSegment(0, 1440, "已有内容。")));
        Path segmentsPath = tempDir.resolve(SubtitleService.segmentsFileName(ROUTE));
        Files.writeString(segmentsPath, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(track));

        var audioPrep = mock(AudioPreparationService.class);
        var transcribe = mock(TranscriptionService.class);
        var service = new SubtitleService(tempDirWorkspace(tempDir), audioPrep, transcribe,
                mockAsr(tempDir, ROUTE, AudioSpec.M4A_AAC_48K_MONO), mapper);

        SubtitleArtifact artifact = service.prepare(input(tempDir), ProgressListener.noop());

        verify(audioPrep, never()).prepare(any(), any(), any());
        verify(transcribe, never()).transcribe(any(), anyString());
        assertEquals(1, artifact.segmentCount());
        assertEquals("zh", artifact.language());
    }

    @Test
    void srtAloneDoesNotCountAsCheckpoint(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 只有 SRT 在、segments JSON 不在 → 必须重新跑，不能跳过（决策 4 的回归测试）
        Files.writeString(tempDir.resolve(SubtitleService.srtFileName(ROUTE)),
                SubtitleService.toSrt(List.of(new TranscriptionSegment(0, 1440, "陈旧 SRT。"))));

        var audioPrep = mock(AudioPreparationService.class);
        var transcribe = mock(TranscriptionService.class);
        var audioSpec = AudioSpec.M4A_AAC_48K_MONO;
        when(audioPrep.prepare(any(), any(), any())).thenReturn(new AudioArtifact(
                tempDir.resolve("audio.m4a"), audioSpec, 100));
        when(transcribe.transcribe(any(), any())).thenReturn(
                new TranscriptionResult(List.of(new TranscriptionSegment(0, 1440, "新转写。")), "zh"));
        var service = new SubtitleService(tempDirWorkspace(tempDir), audioPrep, transcribe,
                mockAsr(tempDir, ROUTE, audioSpec), mapper);

        SubtitleArtifact artifact = service.prepare(input(tempDir), ProgressListener.noop());

        verify(transcribe).transcribe(any(), any());
        assertEquals(1, artifact.segmentCount(), "SRT 在而 segments JSON 不在时必须重新转写");
    }

    // ---------- 辅助 ----------

    private static MediaInput input(Path tempDir) {
        return new MediaInput.LocalPath(tempDir.resolve("source.mp4"));
    }

    /** 组装被测服务：除特意留出的依赖外全用真实 mock，编排路径覆盖静态断言。 */
    private static SubtitleService serviceWith(
            Path tempDir, SubtitleRoute route, AudioSpec audioSpec,
            List<TranscriptionSegment> segments, String language, ObjectMapper mapper) {
        var audioPrep = mock(AudioPreparationService.class);
        var transcribe = mock(TranscriptionService.class);
        when(audioPrep.prepare(any(), any(), any())).thenReturn(
                new AudioArtifact(tempDir.resolve("audio.m4a"), audioSpec, 100));
        when(transcribe.transcribe(any(), any())).thenReturn(new TranscriptionResult(segments, language));
        return new SubtitleService(tempDirWorkspace(tempDir), audioPrep, transcribe,
                mockAsr(tempDir, route, audioSpec), mapper);
    }

    private static WorkspaceService tempDirWorkspace(Path tempDir) {
        var ws = mock(WorkspaceService.class);
        when(ws.workspaceFor(any())).thenReturn(tempDir);
        return ws;
    }

    private static ASRProvider mockAsr(Path tempDir, SubtitleRoute route, AudioSpec audioSpec) {
        var asr = mock(ASRProvider.class);
        when(asr.route()).thenReturn(route);
        when(asr.requiredAudioSpec()).thenReturn(audioSpec);
        return asr;
    }
}