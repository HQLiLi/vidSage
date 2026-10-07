package com.vidsage.infrastructure.provider.download.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vidsage.domain.media.model.AcquiredMedia;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * YtDlpMediaAcquirer 的纯单元测试：<b>不起 Spring 容器</b>，Mockito 替掉 {@link YtDlpRunner}，
 * 用真实 ObjectMapper 解析内置的 info json，覆盖配方参数、元数据映射、产物定位兜底、
 * WARNING 提取与失败分类。<b>不依赖网络</b>（真实下载作为实现期手动证据）。
 */
class YtDlpMediaAcquirerTest {

    private static final String URL = "http://www.bilibili.com/video/av117317904177698";

    /** 与 ExternalToolProperties.defaultTimeoutSeconds 的 @DefaultValue 一致；用于断言超时消息带真实秒数。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    // 不清理临时目录：Windows 上偶发环境性句柄占用会让清理失败并把测试判红（本仓库已两次独立复现），
    // 宁可留下临时目录，也不要让环境问题影响测试判定（见 implement.md 第三步的实测记录）。
    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    private final YtDlpRunner ytDlp = mock(YtDlpRunner.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AtomicReference<List<String>> capturedCommand = new AtomicReference<>();

    private YtDlpMediaAcquirer acquirer() {
        return new YtDlpMediaAcquirer(ytDlp, objectMapper);
    }

    // ---------- 配方与元数据映射 ----------

    @Test
    void buildsRecipeWithFixedTemplateAndUrlLast() throws IOException {
        stubRun(0, "", false, infoJson("mp4", "中文标题测试", 47.717), "source.mp4");

        acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir);

        List<String> args = capturedCommand.get();
        assertContainsSequence(args, "--no-playlist", "--no-progress", "--write-info-json",
                "-o", "source.%(ext)s", "-P", workDir.toString());
        assertEquals(URL, args.getLast(), "URL 必须在参数最后");
        assertFalse(args.contains("--no-simulate"), () -> "不应出现未在配方里的参数：" + args);
    }

    @Test
    void mapsInfoJsonToAcquiredMedia() throws IOException {
        stubRun(0, "", false, infoJson("mp4", "中文标题测试", 47.717), "source.mp4");

        AcquiredMedia media = acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir);

        assertEquals(workDir.resolve("source.mp4"), media.mediaPath());
        assertEquals("中文标题测试", media.title(), "标题必须是正常中文（编码坑回归）");
        assertEquals(47717, media.durationMs(), "毫秒 = 秒 × 1000 四舍五入");
        assertTrue(media.infoJsonPath().isPresent());
        assertEquals(workDir.resolve("source.info.json"), media.infoJsonPath().orElseThrow());
    }

    @Test
    void toleratesUnknownFieldsFromRealInfoJson() throws IOException {
        // 真实 info json 有几十个字段（id / subtitles / automatic_captions / requested_downloads /
        // _filename / filepath ...）。@JsonIgnoreProperties(ignoreUnknown = true) 是这条路径不炸的
        // 唯一保障，而内置的 infoJson(...) 只写已知字段、从不触发它，故此处显式造一份「像真的」输入回归。
        String realLikeInfoJson = """
                {"id":"BV1LLhW6uE12","ext":"mp4","title":"真实标题","duration":47.717,
                 "subtitles":[],"automatic_captions":null,"requested_downloads":[],
                 "_filename":"x.mp4","filepath":null,"is_live":false}""";
        stubRun(0, "", false, realLikeInfoJson, "source.mp4");

        AcquiredMedia media = acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir);

        assertEquals(workDir.resolve("source.mp4"), media.mediaPath(), "ext 推导不受未知字段影响");
        assertEquals("真实标题", media.title());
        assertEquals(47717, media.durationMs());
    }

    @Test
    void roundsDurationSecondsToMillis() {
        assertEquals(47717, YtDlpMediaAcquirer.durationMsOf(
                new YtDlpMediaAcquirer.YtDlpInfoJson("mp4", "t", 47.7166)));
        assertEquals(0, YtDlpMediaAcquirer.durationMsOf(
                new YtDlpMediaAcquirer.YtDlpInfoJson("mp4", "t", 0.0)));
    }

    // ---------- 产物定位 ----------

    @Test
    void locatesProductByScanWhenExtMissing() throws IOException {
        stubRun(0, "", false, infoJson(null, "t", 12.0), "source.webm");

        AcquiredMedia media = acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir);

        assertEquals(workDir.resolve("source.webm"), media.mediaPath(), "ext 缺失时应靠目录扫描兜底");
    }

    @Test
    void excludesInfoJsonPartAndYtdlFromScan() throws IOException {
        Files.writeString(workDir.resolve("source.info.json"), "{}");
        Files.writeString(workDir.resolve("source.mp4.part"), "half");
        Files.writeString(workDir.resolve("source.mp4.ytdl"), "marker");
        Files.write(workDir.resolve("source.mp4"), new byte[8]);

        List<Path> products = YtDlpMediaAcquirer.scanProducts(workDir);

        assertEquals(List.of(workDir.resolve("source.mp4")), products);
    }

    @Test
    void throwsWhenNoProductFound() throws IOException {
        stubRun(0, "", false, infoJson("mp4", "t", 12.0), null);
        Files.writeString(workDir.resolve("source.info.json"), infoJson("mp4", "t", 12.0));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir));

        assertTrue(error.getMessage().contains("无法定位 yt-dlp 下载产物"), error.getMessage());
        assertTrue(error.getMessage().contains("source.info.json"), () -> "应附目录清单不要猜：" + error.getMessage());
    }

    @Test
    void throwsWhenMultipleProductsFound() throws IOException {
        stubRun(0, "", false, infoJson(null, "t", 12.0), null);
        Files.writeString(workDir.resolve("source.info.json"), infoJson(null, "t", 12.0));
        Files.write(workDir.resolve("source.mp4"), new byte[8]);
        Files.write(workDir.resolve("source.webm"), new byte[8]);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir));

        assertTrue(error.getMessage().contains("实际 2 个"), error.getMessage());
    }

    // ---------- 元数据缺失 ----------

    @Test
    void throwsWhenTitleMissing() throws IOException {
        stubRun(0, "", false, infoJson("mp4", null, 12.0), "source.mp4");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir));

        assertTrue(error.getMessage().contains("title"), error.getMessage());
    }

    @Test
    void throwsWhenDurationMissing() throws IOException {
        stubRun(0, "", false, infoJson("mp4", "t", null), "source.mp4");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir));

        assertTrue(error.getMessage().contains("duration"), error.getMessage());
    }

    @Test
    void throwsWhenInfoJsonMissingDespiteSuccess() {
        stubRun(0, "", false, null, "source.mp4");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir));

        assertTrue(error.getMessage().contains("info json"), error.getMessage());
    }

    // ---------- 失败分类 ----------

    @Test
    void throwsOnTimeout() {
        when(ytDlp.timeoutSeconds()).thenReturn(DEFAULT_TIMEOUT_SECONDS);
        stubRun(-1, "", true, null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir));

        assertTrue(error.getMessage().contains("超时"), error.getMessage());
        assertTrue(error.getMessage().contains(DEFAULT_TIMEOUT_SECONDS + " 秒"),
                () -> "消息须带真实秒数，用户才知道往哪调：" + error.getMessage());
        assertTrue(error.getMessage().contains("vidsage.tools.default-timeout-seconds"), error.getMessage());
    }

    @Test
    void appendsStderrTailOnUnknownFailure() {
        String message = YtDlpMediaAcquirer.failureMessage(1, "ERROR: something went wrong");

        assertTrue(message.contains("yt-dlp 下载失败（exit=1）"), message);
        assertTrue(message.contains("ERROR: something went wrong"), message);
    }

    @Test
    void explainsBotDetectionWithCookiesConfig() {
        String stderr = "ERROR: [youtube] abc: Sign in to confirm you're not a bot. "
                + "Use --cookies-from-browser or --cookies for the authentication.";

        String message = YtDlpMediaAcquirer.failureMessage(1, stderr);

        assertTrue(message.contains("vidsage.download.yt-dlp.cookies-file"),
                () -> "bot 检测应给出 cookies 配置项：" + message);
    }

    @Test
    void explainsInvalidUrl() {
        String message = YtDlpMediaAcquirer.failureMessage(1,
                "ERROR: [generic] 'foo.mp4' is not a valid URL");

        assertTrue(message.contains("不是 yt-dlp 支持的 URL"), message);
        assertTrue(message.contains("localPath"), message);
    }

    @Test
    void extractsWarningLines() {
        String stderr = "WARNING: [BiliBili] Format(s) 1080P 高码率 are missing; you have to become a premium member\n"
                + "[info] Downloading 1 format(s): 100026+30280\n"
                + "WARNING: [youtube] No title found in player responses\n";

        List<String> warnings = YtDlpMediaAcquirer.warningLines(stderr);

        assertEquals(2, warnings.size());
        assertTrue(warnings.getFirst().contains("premium member"), warnings.toString());
    }

    @Test
    void returnsNoWarningsForBlankStderr() {
        assertTrue(YtDlpMediaAcquirer.warningLines(null).isEmpty());
        assertTrue(YtDlpMediaAcquirer.warningLines("").isEmpty());
        assertTrue(YtDlpMediaAcquirer.warningLines("just progress\n").isEmpty());
    }

    @Test
    void extractsFormatAvailabilityNoticesFromStdout() {
        // 实测：B站 会员画质限制写在 stdout 的 [BiliBili] 行，无 WARNING 前缀
        String stdout = "[BiliBili] BV1: Downloading video formats for cid 1\n"
                + "[BiliBili] Format(s) 1080P 高码率 are missing; you have to become a premium member "
                + "to download them. Use --cookies-from-browser or --cookies for the authentication.\n"
                + "[info] BV1: Downloading 1 format(s): 100026+30280\n";

        List<String> notices = YtDlpMediaAcquirer.formatAvailabilityNotices(stdout);

        assertEquals(1, notices.size());
        assertTrue(notices.getFirst().contains("premium member"), notices.toString());
    }

    @Test
    void returnsNoFormatNoticesForBlankOrOrdinaryOutput() {
        assertTrue(YtDlpMediaAcquirer.formatAvailabilityNotices(null).isEmpty());
        assertTrue(YtDlpMediaAcquirer.formatAvailabilityNotices("[info] fine\n").isEmpty());
    }

    // ---------- 卫语句与分派 ----------

    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl("  "), workDir));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> acquirer().acquire(null, workDir));
    }

    @Test
    void rejectsUnsupportedInputType() throws IOException {
        Path local = workDir.resolve("a.mp4");
        Files.writeString(local, "x");

        assertThrows(IllegalArgumentException.class,
                () -> acquirer().acquire(new MediaInput.LocalPath(local), workDir));
    }

    @Test
    void rejectsMissingWorkDir() {
        assertThrows(IllegalArgumentException.class,
                () -> acquirer().acquire(new MediaInput.RemoteUrl(URL), workDir.resolve("no-such-dir")));
    }

    @Test
    void supportsOnlyRemoteUrl() {
        YtDlpMediaAcquirer acquirer = acquirer();
        assertTrue(acquirer.supports(new MediaInput.RemoteUrl(URL)));
        assertFalse(acquirer.supports(new MediaInput.LocalPath(workDir.resolve("a.mp4"))));
        assertFalse(acquirer.supports(null));
    }

    // ---------- 断点复用检查（reuseExisting） ----------

    @Test
    void reusesCompleteProductWithoutRunningYtDlp() throws IOException {
        Files.writeString(workDir.resolve("source.info.json"), infoJson("mp4", "复用的标题", 12.5));
        Files.write(workDir.resolve("source.mp4"), new byte[16]);

        Optional<AcquiredMedia> reused = acquirer().reuseExisting(new MediaInput.RemoteUrl(URL), workDir);

        assertTrue(reused.isPresent());
        assertEquals(workDir.resolve("source.mp4"), reused.orElseThrow().mediaPath());
        assertEquals("复用的标题", reused.orElseThrow().title());
        assertEquals(12_500, reused.orElseThrow().durationMs());
        verifyNoInteractions(ytDlp);
    }

    @Test
    void doesNotReuseWhenInfoJsonMissing() throws IOException {
        Files.write(workDir.resolve("source.mp4"), new byte[16]);

        assertTrue(acquirer().reuseExisting(new MediaInput.RemoteUrl(URL), workDir).isEmpty());
    }

    @Test
    void doesNotReuseWhenProductIsZeroBytes() throws IOException {
        Files.writeString(workDir.resolve("source.info.json"), infoJson("mp4", "t", 12.0));
        Files.write(workDir.resolve("source.mp4"), new byte[0]);

        assertTrue(acquirer().reuseExisting(new MediaInput.RemoteUrl(URL), workDir).isEmpty(),
                "0 字节是下载中断留下的空壳，不算可复用的完整产物");
    }

    @Test
    void doesNotReuseWhenMetadataIncomplete() throws IOException {
        Files.writeString(workDir.resolve("source.info.json"), infoJson("mp4", null, 12.0));
        Files.write(workDir.resolve("source.mp4"), new byte[16]);

        assertTrue(acquirer().reuseExisting(new MediaInput.RemoteUrl(URL), workDir).isEmpty(),
                "标题缺失时造不出 AcquiredMedia，应降级为不可复用");
    }

    @Test
    void doesNotReuseCorruptInfoJsonButDoesNotThrow() throws IOException {
        Files.writeString(workDir.resolve("source.info.json"), "not-json");
        Files.write(workDir.resolve("source.mp4"), new byte[16]);

        assertTrue(acquirer().reuseExisting(new MediaInput.RemoteUrl(URL), workDir).isEmpty(),
                "损坏的 info json 应降级为不可复用，交给重新下载恢复");
    }

    @Test
    void rejectsWrongInputTypeForReuse() {
        assertThrows(IllegalArgumentException.class,
                () -> acquirer().reuseExisting(new MediaInput.LocalPath(workDir.resolve("a.mp4")), workDir));
        assertThrows(IllegalArgumentException.class, () -> acquirer().reuseExisting(null, workDir));
    }

    // ---------- 辅助 ----------

    /** 造 info json 文本；字段为 null 时省略该键，用于覆盖「缺字段」路径。 */
    private static String infoJson(String ext, String title, Double duration) {
        StringBuilder json = new StringBuilder("{");
        appendField(json, "ext", ext == null ? null : "\"" + ext + "\"");
        appendField(json, "title", title == null ? null : "\"" + title + "\"");
        appendField(json, "duration", duration == null ? null : duration.toString());
        return json.append("}").toString();
    }

    private static void appendField(StringBuilder json, String key, String value) {
        if (value == null) {
            return;
        }
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(key).append("\":").append(value);
    }

    /**
     * 让 mock 的 yt-dlp 模拟一次运行：按入参写 info json 与产物文件，并回传退出码 / stderr / 超时标记。
     */
    private void stubRun(int exitCode, String stderr, boolean timedOut, String infoJson, String productName) {
        when(ytDlp.run(anyList(), any())).thenAnswer(invocation -> {
            List<String> args = invocation.getArgument(0);
            Path dir = invocation.getArgument(1);
            capturedCommand.set(List.copyOf(args));
            if (infoJson != null) {
                Files.writeString(dir.resolve("source.info.json"), infoJson);
            }
            if (productName != null) {
                Files.write(dir.resolve(productName), new byte[16]);
            }
            return new ProcessOutcome(exitCode, "", stderr, List.copyOf(args), timedOut, 5);
        });
    }

    private static void assertContainsSequence(List<String> command, String... expected) {
        List<String> want = List.of(expected);
        assertTrue(java.util.Collections.indexOfSubList(command, want) >= 0,
                () -> "命令缺少片段 " + want + "，实际为：" + command);
    }
}
