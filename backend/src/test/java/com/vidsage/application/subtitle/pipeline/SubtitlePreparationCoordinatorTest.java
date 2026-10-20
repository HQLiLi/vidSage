package com.vidsage.application.subtitle.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.IntermediateFileOwner;
import com.vidsage.application.media.pipeline.AudioPreparationCoordinator;
import com.vidsage.application.transcription.pipeline.Transcriber;
import com.vidsage.domain.transcription.model.TranscriptionResult;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import com.vidsage.domain.media.model.AudioStageOutput;
import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.common.SubtitleStageOutput;
import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.transcription.port.ASRProvider;
import com.vidsage.shared.subtitle.SrtRenderer;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * SubtitlePreparationCoordinator 的纯单元测试：不起 Spring 容器，只 new 被测对象；编排路径的依赖用
 * mock 注入。纯变换（时间格式 / SRT 渲染 / 产物名）已随方法搬到 {@code shared/subtitle/} 下的
 * 镜像测试类，本类只留服务编排用例（断点复用、产物门禁、进度映射、异常路径）。
 * 会写文件的用例用 {@code @TempDir(CleanupMode.NEVER)}——Windows 上清理偶发失败。
 */
class SubtitlePreparationCoordinatorTest {

    private static final IntermediateFileOwner OWNER = new IntermediateFileOwner(7L, 3L);

    // ---------- prepare 编排 ----------

    @Test
    void emptyTranscriptionProducesZeroByteSrtAndValidJson(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var service = serviceWith(
                tempDir, AudioSpec.M4A_AAC_48K_MONO, List.of(), "zh", mapper);

        SubtitleStageOutput artifact = service.prepare(input(tempDir), tempDir, ProgressListener.noop(), true, OWNER);

        assertEquals(0, artifact.segmentCount());
        assertEquals(0, artifact.srtSizeBytes());

        String json = Files.readString(artifact.segmentsPath());
        var track = mapper.readValue(json, SubtitleTrack.class);
        assertEquals(0, track.segments().size(), "空转写 JSON 里 segments 应为 []");
    }

    @Test
    void checkpointHitsWhenSegmentsJsonExists(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 预置非空 segments JSON → prepare 应跳过音频准备与转写
        var track = new SubtitleTrack("zh",
                List.of(new TranscriptionSegment(0, 1440, "已有内容。")), null);
        Path segmentsPath = tempDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null));
        Files.writeString(segmentsPath, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(track));

        var audioPrep = mock(AudioPreparationCoordinator.class);
        var transcribe = mock(Transcriber.class);
        var service = new SubtitlePreparationCoordinator(audioPrep, transcribe,
                mockAsr(tempDir, AudioSpec.M4A_AAC_48K_MONO), mapper);

        SubtitleStageOutput artifact = service.prepare(input(tempDir), tempDir, ProgressListener.noop(), true, OWNER);

        verify(audioPrep, never()).prepare(any(), any(), any(), any(), any());
        verify(transcribe, never()).transcribe(any(), anyString());
        assertEquals(1, artifact.segmentCount());
        assertEquals("zh", artifact.language());
    }

    @Test
    void ignoresFileCheckpointWhenArtifactIsNotReusable(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 盘上有完好的 segments JSON，但产物表说它已作废 → 必须真重跑，不许拿旧文件当检查点
        var track = new SubtitleTrack("zh",
                List.of(new TranscriptionSegment(0, 1440, "旧内容。")), null);
        Path segmentsPath = tempDir.resolve(SubtitleArtifactSpec.ORIGINAL_SEGMENTS.getFileName(null));
        Files.writeString(segmentsPath, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(track));

        var audioPrep = mock(AudioPreparationCoordinator.class);
        var transcribe = mock(Transcriber.class);
        when(audioPrep.prepare(any(), any(), any(), any(), any())).thenReturn(
                new AudioStageOutput(tempDir.resolve("audio.m4a"), AudioSpec.M4A_AAC_48K_MONO, 100));
        when(transcribe.transcribe(any(), any())).thenReturn(
                new TranscriptionResult(List.of(new TranscriptionSegment(0, 1440, "新内容。")), "zh"));
        var service = new SubtitlePreparationCoordinator(audioPrep, transcribe,
                mockAsr(tempDir, AudioSpec.M4A_AAC_48K_MONO), mapper);

        SubtitleStageOutput artifact = service.prepare(input(tempDir), tempDir, ProgressListener.noop(), false, OWNER);

        verify(audioPrep).prepare(any(), any(), any(), any(), any());
        verify(transcribe).transcribe(any(), any());
        SubtitleTrack written = mapper.readValue(Files.readString(artifact.segmentsPath()), SubtitleTrack.class);
        assertEquals("新内容。", written.segments().getFirst().text(),
                "产物表说不可复用时必须真重跑，写回的是新内容而不是旧检查点");
    }

    @Test
    void srtAloneDoesNotCountAsCheckpoint(
            @TempDir(cleanup = CleanupMode.NEVER) Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 只有 SRT 在、segments JSON 不在 → 必须重新跑，不能跳过（决策 4 的回归测试）
        Files.writeString(tempDir.resolve(SubtitleArtifactSpec.ORIGINAL_SRT.getFileName(null)),
                SrtRenderer.toSrt(List.of(new TranscriptionSegment(0, 1440, "陈旧 SRT。"))));

        var audioPrep = mock(AudioPreparationCoordinator.class);
        var transcribe = mock(Transcriber.class);
        var audioSpec = AudioSpec.M4A_AAC_48K_MONO;
        when(audioPrep.prepare(any(), any(), any(), any(), any())).thenReturn(new AudioStageOutput(
                tempDir.resolve("audio.m4a"), audioSpec, 100));
        when(transcribe.transcribe(any(), any())).thenReturn(
                new TranscriptionResult(List.of(new TranscriptionSegment(0, 1440, "新转写。")), "zh"));
        var service = new SubtitlePreparationCoordinator(audioPrep, transcribe,
                mockAsr(tempDir, audioSpec), mapper);

        SubtitleStageOutput artifact = service.prepare(input(tempDir), tempDir, ProgressListener.noop(), true, OWNER);

        verify(transcribe).transcribe(any(), any());
        assertEquals(1, artifact.segmentCount(), "SRT 在而 segments JSON 不在时必须重新转写");
    }

    // ---------- 辅助 ----------

    private static MediaInput input(Path tempDir) {
        return new MediaInput.LocalPath(tempDir.resolve("source.mp4"));
    }

    /** 组装被测服务：除特意留出的依赖外全用真实 mock，编排路径覆盖静态断言。 */
    private static SubtitlePreparationCoordinator serviceWith(
            Path tempDir, AudioSpec audioSpec,
            List<TranscriptionSegment> segments, String language, ObjectMapper mapper) {
        var audioPrep = mock(AudioPreparationCoordinator.class);
        var transcribe = mock(Transcriber.class);
        when(audioPrep.prepare(any(), any(), any(), any(), any())).thenReturn(
                new AudioStageOutput(tempDir.resolve("audio.m4a"), audioSpec, 100));
        when(transcribe.transcribe(any(), any())).thenReturn(new TranscriptionResult(segments, language));
        return new SubtitlePreparationCoordinator(audioPrep, transcribe,
                mockAsr(tempDir, audioSpec), mapper);
    }

    private static ASRProvider mockAsr(Path tempDir, AudioSpec audioSpec) {
        var asr = mock(ASRProvider.class);
        when(asr.requiredAudioSpec()).thenReturn(audioSpec);
        return asr;
    }
}
