package com.vidsage.application.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.AudioPreparationStage;
import com.vidsage.application.media.WorkspaceService;
import com.vidsage.application.transcription.TranscriptionStage;
import com.vidsage.domain.transcription.AudioSource;
import com.vidsage.domain.transcription.TranscriptionResult;
import com.vidsage.domain.transcription.TranscriptionSegment;
import com.vidsage.domain.media.AudioArtifact;
import com.vidsage.domain.media.AudioContainer;
import com.vidsage.domain.media.AudioSpec;
import com.vidsage.domain.media.MediaInput;
import com.vidsage.domain.shared.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.SubtitleRoute;
import com.vidsage.domain.subtitle.SubtitleTrack;
import com.vidsage.domain.transcription.ASRProvider;
import com.vidsage.shared.subtitle.SrtRenderer;
import com.vidsage.shared.subtitle.SubtitleArtifactNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitlePreparationStage 的纯单元测试：不起 Spring 容器，只 new 被测对象；编排路径的依赖用
 * mock 注入。纯变换（时间格式 / SRT 渲染 / 产物名）已随方法搬到 {@code shared/subtitle/} 下的
 * 镜像测试类，本类只留服务编排用例（断点复用、进度映射、异常路径）。
 * 会写文件的用例用 {@code @TempDir(CleanupMode.NEVER)}——Windows 上清理偶发失败。
 */
class SubtitlePreparationStageTest {

    private static final SubtitleRoute ROUTE = SubtitleRoute.ASR_CLOUD;

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
        Path segmentsPath = tempDir.resolve(SubtitleArtifactNames.segmentsFileName(ROUTE));
        Files.writeString(segmentsPath, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(track));

        var audioPrep = mock(AudioPreparationStage.class);
        var transcribe = mock(TranscriptionStage.class);
        var service = new SubtitlePreparationStage(tempDirWorkspace(tempDir), audioPrep, transcribe,
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
        Files.writeString(tempDir.resolve(SubtitleArtifactNames.srtFileName(ROUTE)),
                SrtRenderer.toSrt(List.of(new TranscriptionSegment(0, 1440, "陈旧 SRT。"))));

        var audioPrep = mock(AudioPreparationStage.class);
        var transcribe = mock(TranscriptionStage.class);
        var audioSpec = AudioSpec.M4A_AAC_48K_MONO;
        when(audioPrep.prepare(any(), any(), any())).thenReturn(new AudioArtifact(
                tempDir.resolve("audio.m4a"), audioSpec, 100));
        when(transcribe.transcribe(any(), any())).thenReturn(
                new TranscriptionResult(List.of(new TranscriptionSegment(0, 1440, "新转写。")), "zh"));
        var service = new SubtitlePreparationStage(tempDirWorkspace(tempDir), audioPrep, transcribe,
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
    private static SubtitlePreparationStage serviceWith(
            Path tempDir, SubtitleRoute route, AudioSpec audioSpec,
            List<TranscriptionSegment> segments, String language, ObjectMapper mapper) {
        var audioPrep = mock(AudioPreparationStage.class);
        var transcribe = mock(TranscriptionStage.class);
        when(audioPrep.prepare(any(), any(), any())).thenReturn(
                new AudioArtifact(tempDir.resolve("audio.m4a"), audioSpec, 100));
        when(transcribe.transcribe(any(), any())).thenReturn(new TranscriptionResult(segments, language));
        return new SubtitlePreparationStage(tempDirWorkspace(tempDir), audioPrep, transcribe,
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