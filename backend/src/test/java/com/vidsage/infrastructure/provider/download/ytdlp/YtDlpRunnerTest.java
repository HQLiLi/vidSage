package com.vidsage.infrastructure.provider.download.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidsage.infrastructure.process.ProcessOutcome;
import com.vidsage.infrastructure.process.ExternalProcessRunner;
import com.vidsage.infrastructure.process.ProcessToolDiagnostics;
import com.vidsage.infrastructure.process.ExternalToolProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * YtDlpRunner 的纯单元测试：<b>不起 Spring 容器</b>，用 Mockito 替掉执行器与工具解析，
 * 只断言「二进制在前、公共参数注入、额外参数原样在后」的命令组装与配置校验。
 */
class YtDlpRunnerTest {

    private static final Path YT_DLP = Path.of("C:", "tools", "yt-dlp.exe");
    private static final Path FFMPEG = Path.of("C:", "ffmpeg", "bin", "ffmpeg.exe");
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    // 不清理临时目录：Windows 上偶发环境性句柄占用会让清理失败并把测试判红（本仓库已两次独立复现），
    // 宁可留下临时目录，也不要让环境问题影响测试判定。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private final ExternalProcessRunner runner = mock(ExternalProcessRunner.class);
    private final ProcessToolDiagnostics tools = mock(ProcessToolDiagnostics.class);
    private final AtomicReference<List<String>> capturedCommand = new AtomicReference<>();

    @Test
    void putsBinaryFirstAndAppendsExtraArgsWithUrlLast() {
        YtDlpRunner ytDlp = newRunner("", new YtDlpProperties(null));

        ytDlp.run(List.of("--skip-download", "https://example.com/v"), tempDir);

        List<String> command = capturedCommand.get();
        assertEquals(YT_DLP.toString(), command.getFirst(), "命令首项必须是解析到的 yt-dlp 二进制");
        assertEquals("https://example.com/v", command.getLast(), "额外参数须原样在末尾（URL 最后）");
        assertEquals(List.of("--skip-download", "https://example.com/v"), command.subList(1, command.size()),
                "未配置 ffmpeg / cookies 时，公共参数区应为空");
    }

    @Test
    void injectsFfmpegLocationWhenFfmpegConfigured() {
        YtDlpRunner ytDlp = newRunner(FFMPEG.toString(), new YtDlpProperties(null));

        ytDlp.run(List.of("https://example.com/v"), tempDir);

        List<String> command = capturedCommand.get();
        assertEquals(1, indexOf(command, "--ffmpeg-location"));
        assertEquals(FFMPEG.toString(), command.get(indexOf(command, "--ffmpeg-location") + 1),
                "注入的应是解析到的绝对 ffmpeg 路径");
    }

    @Test
    void omitsFfmpegLocationWhenNotConfigured() {
        YtDlpRunner ytDlp = newRunner("", new YtDlpProperties(null));

        ytDlp.run(List.of("https://example.com/v"), tempDir);

        assertFalse(capturedCommand.get().contains("--ffmpeg-location"),
                "未配置 ffmpeg 时不应注入 --ffmpeg-location（由 yt-dlp 自行从 PATH 发现）");
    }

    @Test
    void injectsCookiesWhenConfigured() throws IOException {
        Path cookies = tempDir.resolve("cookies.txt");
        Files.writeString(cookies, "# Netscape HTTP Cookie File\n");
        YtDlpRunner ytDlp = newRunner("", new YtDlpProperties(cookies.toString()));

        ytDlp.run(List.of("https://example.com/v"), tempDir);

        List<String> command = capturedCommand.get();
        assertEquals(cookies.toAbsolutePath().toString(), command.get(indexOf(command, "--cookies") + 1));
    }

    @Test
    void omitsCookiesWhenBlank() {
        YtDlpRunner ytDlp = newRunner("", new YtDlpProperties("   "));

        ytDlp.run(List.of("https://example.com/v"), tempDir);

        assertFalse(capturedCommand.get().contains("--cookies"), "cookies 配置为空白时不应注入");
    }

    @Test
    void failsEarlyWhenConfiguredCookiesFileMissing() {
        YtDlpRunner ytDlp = newRunner("", new YtDlpProperties(tempDir.resolve("nope.txt").toString()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ytDlp.run(List.of("https://example.com/v"), tempDir));

        assertTrue(error.getMessage().contains("vidsage.download.yt-dlp.cookies-file"),
                () -> "应指出要修正的配置项：" + error.getMessage());
        assertTrue(error.getMessage().contains("不存在"), () -> "应说明缺什么：" + error.getMessage());
    }

    @Test
    void reportsConfiguredTimeoutSeconds() {
        YtDlpRunner ytDlp = newRunner("", new YtDlpProperties(null));

        assertEquals(DEFAULT_TIMEOUT_SECONDS, ytDlp.timeoutSeconds(),
                "调用方构造超时消息要用它，须与 run 实际交给执行器的值同源");
    }

    @Test
    void rejectsEmptyExtraArgs() {
        YtDlpRunner ytDlp = newRunner("", new YtDlpProperties(null));

        assertThrows(IllegalArgumentException.class, () -> ytDlp.run(List.of(), tempDir));
    }

    @Test
    void propagatesMissingYtDlpHint() {
        when(tools.requireYtDlp()).thenThrow(new IllegalStateException(
                "未找到 yt-dlp：请设置 vidsage.tools.yt-dlp-path 或将 yt-dlp 放入 PATH"));
        YtDlpRunner ytDlp = new YtDlpRunner(runner, tools,
                new ExternalToolProperties(null, null, null, DEFAULT_TIMEOUT_SECONDS, 90), new YtDlpProperties(null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ytDlp.run(List.of("https://example.com/v"), tempDir));

        assertTrue(error.getMessage().contains("vidsage.tools.yt-dlp-path"), () -> error.getMessage());
    }

    /** 构造被测对象：二进制解析被 mock，ffmpeg 路径按入参（空 = 未配置），并记录命令行。 */
    private YtDlpRunner newRunner(String ffmpegPath, YtDlpProperties ytDlpProperties) {
        when(tools.requireYtDlp()).thenReturn(YT_DLP);
        when(tools.requireFfmpeg()).thenReturn(FFMPEG);
        when(runner.run(anyList(), any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            capturedCommand.set(List.copyOf(command));
            return new ProcessOutcome(0, "", "", List.copyOf(command), false, 5);
        });
        return new YtDlpRunner(runner, tools,
                new ExternalToolProperties(YT_DLP.toString(), ffmpegPath, null, DEFAULT_TIMEOUT_SECONDS, 90),
                ytDlpProperties);
    }

    private static int indexOf(List<String> command, String value) {
        int index = command.indexOf(value);
        assertTrue(index >= 0, () -> "命令缺少 " + value + "：" + command);
        return index;
    }
}
