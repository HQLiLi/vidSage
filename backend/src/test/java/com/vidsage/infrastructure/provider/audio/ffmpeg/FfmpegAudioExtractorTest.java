package com.vidsage.infrastructure.provider.audio.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.domain.media.model.AudioArtifact;
import com.vidsage.domain.media.model.AudioContainer;
import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.infrastructure.process.ProcessOutcome;
import com.vidsage.infrastructure.process.ExternalProcessRunner;
import com.vidsage.infrastructure.process.ProcessToolDiagnostics;
import com.vidsage.infrastructure.process.ExternalToolProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * FfmpegAudioExtractor 的纯单元测试：<b>不起 Spring 容器</b>，用 Mockito 替掉
 * {@link ExternalProcessRunner} 与 {@link ProcessToolDiagnostics}，只断言命令组装、
 * 原子落盘与失败路径。真实 ffmpeg 的端到端证据在实现期单独实测（见报告）。
 */
class FfmpegAudioExtractorTest {

    /** 假的 ffmpeg 路径：只用于断言命令首项，不会真的执行。 */
    private static final Path FFMPEG = Path.of("C:", "ffmpeg", "bin", "ffmpeg.exe");

    /** 默认超时（秒），与 ExternalToolProperties 的 @DefaultValue 一致。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    // 不清理临时目录：Windows 上偶发环境性句柄占用会让清理失败并把测试判红（本仓库已两次独立复现，
    // 本类正是其中之一），宁可留下临时目录，也不要让环境问题影响测试判定。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final ExternalProcessRunner runner = mock(ExternalProcessRunner.class);
    private final ProcessToolDiagnostics tools = mock(ProcessToolDiagnostics.class);
    private final ExternalToolProperties properties =
            new ExternalToolProperties(null, null, null, DEFAULT_TIMEOUT_SECONDS, 90);

    /** 记录 mock 收到的最后一条命令行，供参数断言。 */
    private final AtomicReference<List<String>> capturedCommand = new AtomicReference<>();

    // ---------- 参数组装 ----------

    @Test
    void buildsWavCommandWithPcmAndExplicitTrack() throws IOException {
        stubOutcome(0, "", 32, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO);

        List<String> command = capturedCommand.get();
        assertContainsSequence(command, "-vn", "-map", "0:a:0", "-ac", "1", "-ar", "16000",
                "-c:a", "pcm_s16le");
        assertContainsSequence(command, "-f", "wav");
        assertTrue(command.contains("-hide_banner"), () -> "缺 -hide_banner：" + command);
        assertTrue(command.contains("-nostdin"), () -> "缺 -nostdin：" + command);
    }

    @Test
    void buildsM4aCommandWithAacBitrate() throws IOException {
        stubOutcome(0, "", 32, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.m4a");

        extractor.extract(media, out, AudioSpec.M4A_AAC_48K_MONO);

        List<String> command = capturedCommand.get();
        assertContainsSequence(command, "-c:a", "aac", "-b:a", "48k");
        assertContainsSequence(command, "-f", "ipod");
    }

    @Test
    void mapsExplicitAudioTrackIndex() throws IOException {
        stubOutcome(0, "", 32, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("multi.mp4");
        Path out = tempDir.resolve("audio.m4a");
        var spec = new AudioSpec(AudioContainer.M4A, 16000, 1, 48, 2);

        extractor.extract(media, out, spec);

        assertContainsSequence(capturedCommand.get(), "-map", "0:a:2");
    }

    @Test
    void writesToPartFileBeforeFinal() throws IOException {
        stubOutcome(0, "", 32, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO);

        assertEquals(out.resolveSibling("audio.wav.part").toString(), capturedCommand.get().getLast(),
                "ffmpeg 必须写临时 .part 文件，而不是最终产物名");
    }

    // ---------- 原子落盘 ----------

    @Test
    void landsOutputAtomicallyAndDropsPartFile() throws IOException {
        stubOutcome(0, "", 16, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        AudioArtifact artifact = extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO);

        assertEquals(out, artifact.audioPath());
        assertEquals(16, artifact.sizeBytes());
        assertTrue(Files.isRegularFile(out), "成功后最终产物必须存在");
        assertFalse(Files.exists(out.resolveSibling("audio.wav.part")), "临时 .part 必须已被改名");
    }

    @Test
    void removesPartFileAndLeavesNoFinalOnNonZeroExit() throws IOException {
        stubOutcome(1, "Invalid data found when processing input", 32, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        assertThrows(IllegalStateException.class, () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));

        assertFalse(Files.exists(out), "失败时不得残留最终产物文件名");
        assertFalse(Files.exists(out.resolveSibling("audio.wav.part")), "失败时必须清掉临时 .part");
    }

    @Test
    void removesPartFileAndLeavesNoFinalOnTimeout() throws IOException {
        stubOutcome(-1, "", 32, true);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));

        assertTrue(error.getMessage().contains("超时"), () -> "超时消息应说明原因：" + error.getMessage());
        assertTrue(error.getMessage().contains("vidsage.tools.default-timeout-seconds"),
                () -> "超时消息应给出可调整的配置项：" + error.getMessage());
        assertFalse(Files.exists(out), "超时时不得残留最终产物");
        assertFalse(Files.exists(out.resolveSibling("audio.wav.part")), "超时时必须清掉临时 .part");
    }

    @Test
    void refusesToOverwriteExistingOutput() throws IOException {
        stubOutcome(0, "", 32, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");
        byte[] existing = "existing-artifact".getBytes();
        Files.write(out, existing);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));

        assertTrue(error.getMessage().contains("已存在"), () -> "消息应说明产物已存在：" + error.getMessage());
        assertTrue(error.getMessage().contains(out.toString()), () -> "消息应给出冲突路径：" + error.getMessage());
        assertArrayEquals(existing, Files.readAllBytes(out), "已存在的产物不得被覆盖");
        assertFalse(Files.exists(out.resolveSibling("audio.wav.part")), "拒绝覆盖时不得留下临时 .part");
    }

    // ---------- 失败分类 ----------

    @Test
    void echoesNegativeExitCodeAndStderrVerbatim() throws IOException {
        // ffmpeg 用 AVERROR(EINVAL) = -22 这类负值返回参数错误，Windows 下 Java 读回负 int；
        // 断言消息原样透出该值、未做无符号归一化（研究 §8.6-8）
        stubOutcome(-22, "boom: unsupported codec", 0, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));

        assertTrue(error.getMessage().contains("boom: unsupported codec"),
                () -> "消息应含 stderr 原文：" + error.getMessage());
        assertTrue(error.getMessage().contains("exit=-22"),
                () -> "消息应原样透出负退出码（不做无符号归一化）：" + error.getMessage());
    }

    @Test
    void pointsAtAudioTrackIndexWhenMapFails() throws IOException {
        // 实测 ffmpeg 对 -map 越界返回 AVERROR(EINVAL) = -22（研究 §8.6-8），stub 照实
        stubOutcome(-22, "Failed to set value '0:a:9' for option 'map': Invalid argument", 0, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("multi.mp4");
        Path out = tempDir.resolve("audio.wav");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));

        assertTrue(error.getMessage().contains("option 'map'"),
                () -> "应透传 ffmpeg 原文：" + error.getMessage());
        assertTrue(error.getMessage().contains("AudioSpec.audioTrackIndex"),
                () -> "应指向 audioTrackIndex：" + error.getMessage());
        assertTrue(error.getMessage().contains("exit=-22"),
                () -> "应原样透出实测的负退出码：" + error.getMessage());
    }

    @Test
    void explainsMissingEncoderWhenFfmpegLacksAac() throws IOException {
        stubOutcome(1, "Unknown encoder 'aac'", 0, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.m4a");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extract(media, out, AudioSpec.M4A_AAC_48K_MONO));

        String message = error.getMessage();
        assertTrue(message.contains("不支持 aac 编码器"), () -> "应说明编码器缺失：" + message);
        assertTrue(message.contains("vidsage.tools.ffmpeg-path"), () -> "应给出配置项：" + message);
        assertTrue(message.contains("AudioSpec.WAV_PCM_16K_MONO"), () -> "应给出确有帮助的替代做法：" + message);
    }

    @Test
    void omitsAlternateContainerHintWhenWavEncoderMissing() throws IOException {
        stubOutcome(1, "Unknown encoder 'pcm_s16le'", 0, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));

        String message = error.getMessage();
        assertTrue(message.contains("vidsage.tools.ffmpeg-path"), () -> "应给出配置项：" + message);
        assertTrue(message.contains("pcm_s16le"), () -> "应说明缺失的编码器：" + message);
        assertFalse(message.contains("改用"), () -> "WAV 失败时不得再建议改用其它容器（那正是失败的规格）：" + message);
    }

    // ---------- 产物校验 ----------

    @Test
    void throwsWhenOutputIsEmpty() throws IOException {
        stubOutcome(0, "", 0, false);
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("audio.wav");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));

        assertTrue(error.getMessage().contains("未产出音频"), () -> "应说明未产出：" + error.getMessage());
        assertFalse(Files.exists(out), "空产物不得被当成成品");
        assertFalse(Files.exists(out.resolveSibling("audio.wav.part")), "空临时文件应被清理");
    }

    // ---------- 卫语句 ----------

    @Test
    void rejectsMissingMediaFile() {
        FfmpegAudioExtractor extractor = newExtractor();
        Path missing = tempDir.resolve("nope.mp4");
        Path out = tempDir.resolve("audio.wav");

        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(missing, out, AudioSpec.WAV_PCM_16K_MONO));
    }

    @Test
    void rejectsNullSpec() throws IOException {
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");

        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(media, tempDir.resolve("audio.wav"), null));
    }

    @Test
    void rejectsMissingOutputDirectory() throws IOException {
        FfmpegAudioExtractor extractor = newExtractor();
        Path media = dummyMedia("in.mp4");
        Path out = tempDir.resolve("no-such-dir").resolve("audio.wav");

        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(media, out, AudioSpec.WAV_PCM_16K_MONO));
    }

    // 原 rejectsInvalidSpecFields / rejectsZeroBitrateForLossyContainer 已移除：
    // spec 字段校验搬进 AudioSpec 的紧凑构造器后，非法值在构造那一刻就抛，测不到 extractor 了。
    // 两者守护的契约改由 domain/media/AudioSpecTest 覆盖（含「消息含 AudioSpec.M4A_AAC_48K_MONO」
    // 那条断言），归属比留在本类更准。

    /** 构造被测对象：工具路径解析被 mock 成固定 ffmpeg 路径，避免依赖运行机器。 */
    private FfmpegAudioExtractor newExtractor() {
        when(tools.requireFfmpeg()).thenReturn(FFMPEG);
        return new FfmpegAudioExtractor(runner, tools, properties);
    }

    /**
     * 让 mock 执行器模拟一次 ffmpeg 运行：按 {@code partBytes} 写临时产物（&lt;0 表示不写），
     * 并回传给定的退出码 / stderr / 超时标记。
     */
    private void stubOutcome(int exitCode, String stderr, long partBytes, boolean timedOut) {
        when(runner.run(anyList(), any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            capturedCommand.set(List.copyOf(command));
            if (partBytes >= 0) {
                Files.write(Path.of(command.getLast()), new byte[(int) partBytes]);
            }
            return new ProcessOutcome(exitCode, "", stderr, List.copyOf(command), timedOut, 12);
        });
    }

    /** 在临时目录造一个占位媒体文件（内容无关紧要，只过存在性/可读校验）。 */
    private Path dummyMedia(String name) throws IOException {
        Path media = tempDir.resolve(name);
        Files.writeString(media, "dummy-media");
        return media;
    }

    /** 断言命令行里出现了给定的连续子序列（顺序敏感，中间可有其它参数）。 */
    private static void assertContainsSequence(List<String> command, String... expected) {
        List<String> want = List.of(expected);
        assertTrue(Collections.indexOfSubList(command, want) >= 0,
                () -> "命令缺少片段 " + want + "，实际为：" + command);
    }
}
