package com.vidsage.provider.download.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.model.media.AcquiredMedia;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.process.ProcessOutcome;
import com.vidsage.provider.tool.ExternalProcessRunner;
import com.vidsage.provider.tool.ExternalToolDiagnostics;
import com.vidsage.provider.tool.ExternalToolProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * LocalFileMediaAcquirer 的纯单元测试：<b>不起 Spring 容器</b>，Mockito 替掉执行器与工具解析，
 * 只断言采用逻辑、ffprobe 命令与时长解析、失败路径。<b>不拉起真实 ffprobe</b>
 * （真实端到端证据在实现期单独实测）。
 */
class LocalFileMediaAcquirerTest {

    private static final Path FFPROBE = Path.of("C:", "ffmpeg", "bin", "ffprobe.exe");
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    // 不清理临时目录：Windows 上偶发环境性句柄占用会让清理失败并把测试判红（本仓库已两次独立复现，
    // 本类正是其中之一），宁可留下临时目录，也不要让环境问题影响测试判定。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final ExternalProcessRunner runner = mock(ExternalProcessRunner.class);
    private final ExternalToolDiagnostics tools = mock(ExternalToolDiagnostics.class);
    private final ExternalToolProperties properties =
            new ExternalToolProperties(null, null, FFPROBE.toString(), DEFAULT_TIMEOUT_SECONDS, 90);
    private final AtomicReference<List<String>> capturedCommand = new AtomicReference<>();

    @Test
    void adoptsExistingFileWithFilenameAsTitle() throws IOException {
        stubFfprobe(0, "47.717000\n");
        Path media = writeMedia("sample.mp4");

        AcquiredMedia acquired = newAcquirer().acquire(new MediaInput.LocalPath(media), tempDir);

        assertEquals(media.toAbsolutePath(), acquired.mediaPath(), "本地路线直接采用，不拷贝");
        assertEquals("sample.mp4", acquired.title(), "标题取文件名");
        assertEquals(47717, acquired.durationMs());
        assertTrue(acquired.infoJsonPath().isEmpty(), "本地路线没有 info json");
    }

    @Test
    void probesFormatDurationWithFfprobe() throws IOException {
        stubFfprobe(0, "12.000000\n");
        Path media = writeMedia("a.mkv");

        newAcquirer().acquire(new MediaInput.LocalPath(media), tempDir);

        List<String> command = capturedCommand.get();
        assertEquals(FFPROBE.toString(), command.getFirst());
        assertTrue(command.contains("-show_entries"), command.toString());
        assertTrue(command.contains("format=duration"), command.toString());
        assertEquals(media.toAbsolutePath().toString(), command.getLast());
    }

    @Test
    void throwsWhenFileMissing() {
        Path missing = tempDir.resolve("nope.mp4");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> newAcquirer().acquire(new MediaInput.LocalPath(missing), tempDir));

        assertTrue(error.getMessage().contains(missing.toAbsolutePath().toString()), error.getMessage());
        assertTrue(error.getMessage().contains("改传 url"), () -> "应给出替代做法：" + error.getMessage());
    }

    @Test
    void throwsWhenPathIsDirectory() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> newAcquirer().acquire(new MediaInput.LocalPath(tempDir), tempDir));

        assertTrue(error.getMessage().contains("不是普通文件"), error.getMessage());
    }

    @Test
    void throwsWhenFfprobeOutputUnparsable() throws IOException {
        stubFfprobe(0, "N/A\n");
        Path media = writeMedia("broken.bin");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> newAcquirer().acquire(new MediaInput.LocalPath(media), tempDir));

        assertTrue(error.getMessage().contains("未返回可解析的时长"), error.getMessage());
    }

    @Test
    void throwsWhenFfprobeFails() throws IOException {
        stubFfprobeResult(1, "", "broken.bin: Invalid data found when processing input\n", false);
        Path media = writeMedia("broken.bin");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> newAcquirer().acquire(new MediaInput.LocalPath(media), tempDir));

        assertTrue(error.getMessage().contains("exit=1"), error.getMessage());
        assertTrue(error.getMessage().contains("Invalid data found"), () -> "应附 ffprobe 原文：" + error.getMessage());
    }

    @Test
    void throwsWhenFfprobeTimesOut() throws IOException {
        stubFfprobeResult(-1, "", "", true);
        Path media = writeMedia("slow.mp4");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> newAcquirer().acquire(new MediaInput.LocalPath(media), tempDir));

        assertTrue(error.getMessage().contains("超时"), error.getMessage());
        assertTrue(error.getMessage().contains(DEFAULT_TIMEOUT_SECONDS + " 秒"),
                () -> "消息须带真实秒数，用户才知道往哪调：" + error.getMessage());
    }

    @Test
    void parsesDurationSecondsToMillis() {
        assertEquals(47717, LocalFileMediaAcquirer.parseDurationMs("47.717000\n").orElseThrow());
        assertEquals(0, LocalFileMediaAcquirer.parseDurationMs("0.000000\n").orElseThrow());
        assertEquals(OptionalLong.empty(), LocalFileMediaAcquirer.parseDurationMs(""));
        assertEquals(OptionalLong.empty(), LocalFileMediaAcquirer.parseDurationMs(null));
        assertEquals(OptionalLong.empty(), LocalFileMediaAcquirer.parseDurationMs("abc"));
        assertEquals(OptionalLong.empty(), LocalFileMediaAcquirer.parseDurationMs("-3.0"));
    }

    @Test
    void supportsOnlyLocalPath() {
        LocalFileMediaAcquirer acquirer = newAcquirer();
        assertTrue(acquirer.supports(new MediaInput.LocalPath(tempDir.resolve("a.mp4"))));
        assertFalse(acquirer.supports(new MediaInput.RemoteUrl("http://example.com/v")));
        assertFalse(acquirer.supports(null));
    }

    @Test
    void rejectsUnsupportedInputType() {
        assertThrows(IllegalArgumentException.class,
                () -> newAcquirer().acquire(new MediaInput.RemoteUrl("http://example.com/v"), tempDir));
    }

    @Test
    void neverReusesLocalRoute() throws IOException {
        // 本地路线不下载、不拷贝：输入文件本身就是产物，没有可复用的下载物。
        // 恒返回空，让 acquire 每次都重新校验并读时长——断点复用落在「不重复抽音频」。
        Path media = writeMedia("sample.mp4");

        assertTrue(newAcquirer().reuseExisting(new MediaInput.LocalPath(media), tempDir).isEmpty());
    }

    @Test
    void reuseRejectsNullAndUnsupportedInput() {
        assertThrows(IllegalArgumentException.class, () -> newAcquirer().reuseExisting(null, tempDir));
        assertThrows(IllegalArgumentException.class,
                () -> newAcquirer().reuseExisting(new MediaInput.RemoteUrl("http://example.com/v"), tempDir));
    }

    private LocalFileMediaAcquirer newAcquirer() {
        when(tools.requireFfprobe()).thenReturn(FFPROBE);
        return new LocalFileMediaAcquirer(runner, tools, properties);
    }

    private Path writeMedia(String name) throws IOException {
        Path media = tempDir.resolve(name);
        Files.write(media, new byte[16]);
        return media;
    }

    /** 让 mock 的 ffprobe 回传指定的 stdout 与退出码，并记录命令行。 */
    private void stubFfprobe(int exitCode, String stdout) {
        stubFfprobeResult(exitCode, stdout, "", false);
    }

    private void stubFfprobeResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        when(runner.run(anyList(), any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            capturedCommand.set(List.copyOf(command));
            return new ProcessOutcome(exitCode, stdout, stderr, List.copyOf(command), timedOut, 5);
        });
    }
}
