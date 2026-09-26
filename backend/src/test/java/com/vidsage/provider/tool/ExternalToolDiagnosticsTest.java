package com.vidsage.provider.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.model.process.ExternalToolReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * ExternalToolDiagnostics 的纯单元测试：不起 Spring 容器、不拉起真实外部进程。
 * 版本解析与新鲜度用包级 static 方法直接喂真实输出字符串；路径解析用受控 PATH。
 *
 * <p>本类的 {@code @TempDir} 一律用 {@code CleanupMode.NEVER}：Windows 上偶发
 * 环境性句柄占用会让清理失败并把测试判红（本仓库已两次独立复现），宁可留下临时目录，
 * 也不要让环境问题影响测试判定。
 */
class ExternalToolDiagnosticsTest {

    @Test
    void reportsUnavailableWhenConfiguredPathMissing() {
        var diagnostics = diagnosticsWith(
                new ExternalToolProperties("/definitely/missing/yt-dlp", "", "", 300, 90));

        ExternalToolReport report = diagnostics.diagnoseYtDlp();

        assertFalse(report.available());
        assertNull(report.version());
        assertNull(report.path());
        assertTrue(report.hint().contains("yt-dlp"), "hint must name the tool: " + report.hint());
        assertTrue(report.hint().contains("vidsage.tools.yt-dlp-path"),
                "hint must say what to configure: " + report.hint());
    }

    @Test
    void doesNotFindBinaryWhenAbsentFromPath(@TempDir(cleanup = CleanupMode.NEVER) Path emptyDir) {
        assertTrue(ExternalToolDiagnostics.findInPath("ffmpeg", emptyDir.toString(), false).isEmpty());
        assertTrue(ExternalToolDiagnostics.findInPath("ffmpeg", "", false).isEmpty());
        assertTrue(ExternalToolDiagnostics.findInPath("ffmpeg", null, false).isEmpty());
    }

    @Test
    void findsBinaryOnPath(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path binary = dir.resolve("ffmpeg");
        Files.writeString(binary, "not a real binary");
        Path other = dir.resolve("junk");

        assertEquals(binary,
                ExternalToolDiagnostics.findInPath("ffmpeg", dir + java.io.File.pathSeparator + other, false)
                        .orElseThrow());
    }

    @Test
    void matchesExeSuffixOnWindows(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path binary = dir.resolve("ffprobe.exe");
        Files.writeString(binary, "not a real binary");

        assertEquals(binary,
                ExternalToolDiagnostics.findInPath("ffprobe", dir.toString(), true).orElseThrow());
    }

    @Test
    void parsesYtDlpVersion() {
        assertEquals("2026.08.19", ExternalToolDiagnostics.parseYtDlpVersion("2026.08.19\n"));
    }

    @Test
    void parsesFfmpegBannerVersion() {
        String banner = "ffmpeg version 9.0.2-essentials_build-www.gyan.dev "
                + "Copyright (c) 2000-2025 the FFmpeg developers\nbuilt with gcc 15.1.0\n";

        assertEquals("9.0.2-essentials_build-www.gyan.dev",
                ExternalToolDiagnostics.parseBannerVersion(banner));
    }

    @Test
    void returnsNullWhenVersionUnparsable() {
        assertNull(ExternalToolDiagnostics.parseYtDlpVersion(""));
        assertNull(ExternalToolDiagnostics.parseYtDlpVersion(null));
        assertNull(ExternalToolDiagnostics.parseBannerVersion("not a version banner"));
    }

    @Test
    void computesDaysSinceRelease() {
        assertEquals(37,
                ExternalToolDiagnostics.daysSinceRelease("2026.08.19", LocalDate.of(2026, 9, 25))
                        .orElseThrow());
    }

    @Test
    void returnsEmptyDaysForNonDateVersion() {
        assertTrue(ExternalToolDiagnostics.daysSinceRelease("stable", LocalDate.of(2026, 9, 25)).isEmpty());
        assertTrue(ExternalToolDiagnostics.daysSinceRelease(null, LocalDate.of(2026, 9, 25)).isEmpty());
    }

    @Test
    void warnsWhenYtDlpVersionOlderThanThreshold() {
        Optional<String> warning = ExternalToolDiagnostics.freshnessWarning(
                "2026.05.01", LocalDate.of(2026, 9, 25), 90);

        assertTrue(warning.isPresent(), "stale yt-dlp must produce a warning");
        assertTrue(warning.get().contains("2026.05.01"), warning.get());
        assertTrue(warning.get().contains("不自动更新"), warning.get());
    }

    @Test
    void doesNotWarnWhenYtDlpFresh() {
        assertTrue(ExternalToolDiagnostics.freshnessWarning(
                "2026.08.19", LocalDate.of(2026, 9, 25), 90).isEmpty());
        assertTrue(ExternalToolDiagnostics.freshnessWarning(
                "stable", LocalDate.of(2026, 9, 25), 90).isEmpty());
    }

    @Test
    void missingHintExplainsWhatToConfigure() {
        assertEquals("未找到 ffmpeg：请设置 vidsage.tools.ffmpeg-path 或加入 PATH；"
                        + "该路径同时供 Java 抽音频与 yt-dlp --ffmpeg-location 使用",
                ExternalToolDiagnostics.missingHint(ExternalToolDiagnostics.TOOL_FFMPEG, ""));

        String ytDlpHint = ExternalToolDiagnostics.missingHint(ExternalToolDiagnostics.TOOL_YT_DLP, "");
        assertTrue(ytDlpHint.contains("vidsage.tools.yt-dlp-path"), ytDlpHint);
        assertTrue(ytDlpHint.contains("tools/yt-dlp.exe"), ytDlpHint);
    }

    @Test
    void missingHintPointsAtBrokenConfiguredPath() {
        String configuredPath = "/missing/yt-dlp";
        String hint = ExternalToolDiagnostics.missingHint(
                ExternalToolDiagnostics.TOOL_YT_DLP, configuredPath);

        assertTrue(hint.contains(Path.of(configuredPath).toAbsolutePath().toString()), hint);
        assertTrue(hint.contains("vidsage.tools.yt-dlp-path"), hint);
    }

    /**
     * 诊断对象只在路径解析与提示语路径上被测：这些用例不会真正拉起外部进程。
     */
    private static ExternalToolDiagnostics diagnosticsWith(ExternalToolProperties properties) {
        return new ExternalToolDiagnostics(new ExternalProcessRunner(), properties);
    }

    @Test
    void requireFfprobeReturnsResolvedAbsolutePath(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path fake = dir.resolve("ffprobe.exe");
        Files.writeString(fake, "not a real binary");
        var diagnostics = diagnosticsWith(new ExternalToolProperties("", "", fake.toString(), 300, 90));

        assertEquals(fake.toAbsolutePath(), diagnostics.requireFfprobe());
    }

    @Test
    void requireFfprobeThrowsWithHintWhenMissing() {
        var diagnostics = diagnosticsWith(new ExternalToolProperties("", "", "/definitely/missing/ffprobe", 300, 90));

        IllegalStateException error = assertThrows(IllegalStateException.class, diagnostics::requireFfprobe);

        assertTrue(error.getMessage().contains("ffprobe"), error.getMessage());
        assertTrue(error.getMessage().contains("vidsage.tools.ffprobe-path"),
                () -> "应给出要修正的配置项：" + error.getMessage());
    }

    @Test
    void requireYtDlpThrowsWithHintWhenMissing() {
        var diagnostics = diagnosticsWith(new ExternalToolProperties("/missing/yt-dlp", "", "", 300, 90));

        IllegalStateException error = assertThrows(IllegalStateException.class, diagnostics::requireYtDlp);

        assertTrue(error.getMessage().contains("vidsage.tools.yt-dlp-path"), error.getMessage());
    }

    @Test
    void requireYtDlpReturnsResolvedAbsolutePath(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path fake = dir.resolve("yt-dlp.exe");
        Files.writeString(fake, "not a real binary");
        var diagnostics = diagnosticsWith(new ExternalToolProperties(fake.toString(), "", "", 300, 90));

        assertEquals(fake.toAbsolutePath(), diagnostics.requireYtDlp());
    }
}
