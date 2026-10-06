package com.vidsage.infrastructure.process;

import com.vidsage.application.diagnostics.ToolDiagnosticReport;
import com.vidsage.application.diagnostics.ToolDiagnostics;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 外部工具诊断：探测 yt-dlp / ffmpeg / ffprobe 的存在性、解析到的绝对路径与版本，
 * 并对日期式版本的 yt-dlp 给出新鲜度告警（<b>不自动更新</b>）。
 *
 * <p>路径解析规则：<b>配置路径优先，否则扫 PATH</b>。配置了路径就只认它——配置错误时
 * 不静默回退到 PATH，避免「以为在用自带的那个，其实跑的是另一个」。
 *
 * <p>诊断只在被调用时探测，<b>不在应用启动时强校验</b>，否则缺工具的机器起不来服务。
 *
 * <p>实现 application 层定义的出站端口 {@link ToolDiagnostics}——这是全项目唯一一处
 * infrastructure → application 的依赖，窄口只开在「实现端口」这一件事上。
 * {@code requireFfmpeg} / {@code requireFfprobe} / {@code requireYtDlp} 三个「要路径」的方法
 * <b>不在端口上</b>，只给同层的 {@code FfmpegAudioExtractor} / {@code YtDlpRunner} 用。
 */
@Slf4j
@Component
@EnableConfigurationProperties(ExternalToolProperties.class)
public class ProcessToolDiagnostics implements ToolDiagnostics {

    static final String TOOL_YT_DLP = "yt-dlp";
    static final String TOOL_FFMPEG = "ffmpeg";
    static final String TOOL_FFPROBE = "ffprobe";

    /** ffmpeg / ffprobe 的版本横幅首行形如 "ffmpeg version 9.0.2-... Copyright ..."。 */
    private static final Pattern VERSION_BANNER = Pattern.compile("^\\S+ version (\\S+)");

    /** yt-dlp 的版本号为日期式 YYYY.MM.DD。 */
    private static final Pattern YT_DLP_DATE_VERSION = Pattern.compile("^(\\d{4})\\.(\\d{2})\\.(\\d{2})");

    private final ExternalProcessRunner runner;
    private final ExternalToolProperties properties;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     */
    public ProcessToolDiagnostics(ExternalProcessRunner runner, ExternalToolProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    /**
     * 诊断全部外部工具。
     *
     * @return yt-dlp / ffmpeg / ffprobe 三份报告，顺序固定
     */
    @Override
    public List<ToolDiagnosticReport> diagnoseAll() {
        return List.of(
                diagnoseYtDlp(),
                diagnose(TOOL_FFMPEG, properties.ffmpegPath(), "-version"),
                diagnose(TOOL_FFPROBE, properties.ffprobePath(), "-version"));
    }

    /**
     * 诊断 yt-dlp：除存在性与版本外，额外按 yt-dlp-freshness-warn-days 判断新鲜度。
     *
     * @return yt-dlp 的诊断报告
     */
    ToolDiagnosticReport diagnoseYtDlp() {
        return diagnose(TOOL_YT_DLP, properties.ytDlpPath(), "--version");
    }

    /**
     * 诊断单个工具。
     *
     * @param toolName       工具名，用于生成提示语
     * @param configuredPath 配置的二进制路径；空白表示从 PATH 解析
     * @param versionFlag    探测版本的命令行开关（yt-dlp 是 --version，ffmpeg/ffprobe 是 -version）
     * @return 诊断报告；不可用时 hint 给出「缺什么 + 怎么补」
     */
    ToolDiagnosticReport diagnose(String toolName, String configuredPath, String versionFlag) {
        Optional<Path> resolved = resolveBinary(toolName, configuredPath);
        if (resolved.isEmpty()) {
            return new ToolDiagnosticReport(toolName, false, null, null, missingHint(toolName, configuredPath));
        }
        Path binary = resolved.get().toAbsolutePath();
        Optional<String> version = probeVersion(toolName, binary, versionFlag);
        if (version.isEmpty()) {
            return new ToolDiagnosticReport(toolName, true, null, binary.toString(),
                    "已找到 " + toolName + "（" + binary + "）但无法解析版本：请确认该二进制可独立执行");
        }
        String hint = TOOL_YT_DLP.equals(toolName)
                ? freshnessWarning(version.get(), LocalDate.now(), properties.ytDlpFreshnessWarnDays()).orElse(null)
                : null;
        return new ToolDiagnosticReport(toolName, true, version.get(), binary.toString(), hint);
    }

    /**
     * 解析 ffmpeg 可执行文件路径；未找到时抛异常，消息含「缺什么 + 怎么补」。
     *
     * <p>供抽音频实现使用：ffmpeg 路径是「一处配置、两处使用」（Java 抽音频 +
     * yt-dlp {@code --ffmpeg-location}），解析规则与提示语的唯一入口留在本类，
     * 避免调用方各写一份。</p>
     *
     * @return ffmpeg 可执行文件的绝对路径
     * @throws IllegalStateException 未配置且不在 PATH 上；消息说明如何补齐
     */
    public Path requireFfmpeg() {
        return resolveBinary(TOOL_FFMPEG, properties.ffmpegPath())
                .map(Path::toAbsolutePath)
                .orElseThrow(() -> new IllegalStateException(missingHint(TOOL_FFMPEG, properties.ffmpegPath())));
    }

    /**
     * 解析 ffprobe 可执行文件路径；未找到时抛异常，消息含「缺什么 + 怎么补」。
     *
     * <p>供本地输入路线读取媒体时长使用。与 {@link #requireFfmpeg()} 共用同一条
     * 「配置优先，否则扫 PATH」规则与同一份提示文案（{@link #requireResolved}），
     * 不另写第二份。
     *
     * @return ffprobe 可执行文件的绝对路径
     * @throws IllegalStateException 未配置且不在 PATH 上；消息说明如何补齐
     */
    public Path requireFfprobe() {
        return requireResolved(TOOL_FFPROBE, properties.ffprobePath());
    }

    /**
     * 解析 yt-dlp 可执行文件路径；未找到时抛异常，消息含「缺什么 + 怎么补」。
     *
     * <p>供 yt-dlp 下载实现使用——二进制解析收敛在它里面，调用方只给额外参数。
     *
     * @return yt-dlp 可执行文件的绝对路径
     * @throws IllegalStateException 未配置且不在 PATH 上；消息说明如何补齐
     */
    public Path requireYtDlp() {
        return requireResolved(TOOL_YT_DLP, properties.ytDlpPath());
    }

    /**
     * 解析工具路径并要求存在。解析规则（{@link #resolveBinary}）与提示语（{@link #missingHint}）
     * 都是既有实现的复用，不新造一份。
     */
    private static Path requireResolved(String toolName, String configuredPath) {
        return resolveBinary(toolName, configuredPath)
                .map(Path::toAbsolutePath)
                .orElseThrow(() -> new IllegalStateException(missingHint(toolName, configuredPath)));
    }

    /**
     * 解析二进制：配置了路径则只认该路径（不存在即视为缺失，不回退 PATH）；否则扫 PATH。
     */
    static Optional<Path> resolveBinary(String toolName, String configuredPath) {
        if (StringUtils.hasText(configuredPath)) {
            try {
                Path path = Path.of(configuredPath);
                return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
            } catch (InvalidPathException e) {
                // 配置串本身不是合法路径（含 * / NUL 等）：按「配置不可用」处理，
                // 让调用方拿到 missingHint 的「怎么补」，而不是裸抛 JDK 的英文异常
                return Optional.empty();
            }
        }
        return findInPath(toolName);
    }

    /**
     * 跑一次 {@code <binary> <versionFlag>} 并解析版本；进程非正常结束或无法解析时返回空。
     */
    private Optional<String> probeVersion(String toolName, Path binary, String versionFlag) {
        ProcessOutcome outcome = runner.run(
                List.of(binary.toString(), versionFlag), null,
                Duration.ofSeconds(properties.defaultTimeoutSeconds()));
        if (outcome.timedOut() || outcome.exitCode() != 0) {
            log.debug("探测 {} 版本失败: exit={}, timedOut={}", toolName, outcome.exitCode(), outcome.timedOut());
            return Optional.empty();
        }
        String version = TOOL_YT_DLP.equals(toolName)
                ? parseYtDlpVersion(outcome.stdout())
                : parseBannerVersion(outcome.stdout());
        return Optional.ofNullable(version);
    }

    /**
     * yt-dlp 的 {@code --version} 输出即一行版本号（形如 "2026.08.19"）。
     *
     * <p>包级 static：测试可直接喂真实输出字符串，无需起进程（见 quality-guidelines）。
     *
     * @param output 命令的标准输出
     * @return 解析出的版本；无有效行时返回 {@code null}
     */
    static String parseYtDlpVersion(String output) {
        return firstNonBlankLine(output);
    }

    /**
     * ffmpeg / ffprobe 的 {@code -version} 横幅首行形如
     * "ffmpeg version 9.0.2-essentials_build-www.gyan.dev Copyright ..."。
     *
     * <p>包级 static：测试可直接喂真实输出字符串。
     *
     * @param output 命令的标准输出
     * @return 解析出的版本；无法匹配时返回 {@code null}
     */
    static String parseBannerVersion(String output) {
        String firstLine = firstNonBlankLine(output);
        if (firstLine == null) {
            return null;
        }
        Matcher matcher = VERSION_BANNER.matcher(firstLine);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 计算 yt-dlp 版本号（YYYY.MM.DD）距指定日期的天数。
     *
     * @param ytDlpVersion 版本号，形如 "2026.08.19"
     * @param today        参照日期（显式传入，便于测试确定化）
     * @return 距今天数；版本号不是日期式时返回空
     */
    static OptionalLong daysSinceRelease(String ytDlpVersion, LocalDate today) {
        if (ytDlpVersion == null) {
            return OptionalLong.empty();
        }
        Matcher matcher = YT_DLP_DATE_VERSION.matcher(ytDlpVersion.trim());
        if (!matcher.find()) {
            return OptionalLong.empty();
        }
        try {
            LocalDate release = LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
            return OptionalLong.of(ChronoUnit.DAYS.between(release, today));
        } catch (DateTimeException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * 新鲜度告警：版本距今超过阈值时返回告警文案，否则返回空。
     *
     * <p>消息含「问题 + 怎么办」；<b>明确不自动更新</b>——自更新需要能访问 GitHub，
     * 且运行中的 exe 在 Windows 上被文件锁占用，交给使用者显式更新。
     *
     * @param ytDlpVersion yt-dlp 版本号
     * @param today        参照日期
     * @param warnDays     告警阈值（天）
     * @return 超阈值的告警文案；未超阈值或版本不可解析时返回空
     */
    static Optional<String> freshnessWarning(String ytDlpVersion, LocalDate today, long warnDays) {
        OptionalLong days = daysSinceRelease(ytDlpVersion, today);
        if (days.isEmpty() || days.getAsLong() <= warnDays) {
            return Optional.empty();
        }
        return Optional.of("yt-dlp 版本 " + ytDlpVersion + " 距今 " + days.getAsLong() + " 天，超过 " + warnDays
                + " 天阈值；站点解析规则变化会导致下载失败，请更新 tools/yt-dlp.exe（不自动更新）");
    }

    /**
     * 在 PATH 中查找二进制。Windows 上优先匹配 .exe 后缀。
     */
    static Optional<Path> findInPath(String binaryName) {
        return findInPath(binaryName, System.getenv("PATH"), isWindows());
    }

    /**
     * 包级 static 且 PATH 与平台可注入：测试可喂受控 PATH，不受运行机器影响。
     *
     * @param binaryName 二进制名（不带扩展名），如 "ffmpeg"
     * @param pathValue  PATH 环境变量的值；空白视为无 PATH
     * @param windows    是否按 Windows 规则同时匹配 {@code <name>.exe}
     * @return 命中的第一个可执行文件路径
     */
    static Optional<Path> findInPath(String binaryName, String pathValue, boolean windows) {
        if (!StringUtils.hasText(pathValue)) {
            return Optional.empty();
        }
        List<String> names = windows
                ? List.of(binaryName + ".exe", binaryName)
                : List.of(binaryName);
        for (String dir : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (!StringUtils.hasText(dir)) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Path.of(dir.trim(), name);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (InvalidPathException e) {
                    // PATH 里夹了非法路径项时跳过，不影响其余项
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 是否运行在 Windows 上（决定 PATH 查找是否补 .exe 后缀）。
     */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 生成「缺什么 + 怎么补」的提示语；配置了路径但不存在时单独说明，指引修正配置。
     */
    static String missingHint(String toolName, String configuredPath) {
        if (StringUtils.hasText(configuredPath)) {
            return "配置的 " + toolName + " 路径不存在：" + describeConfiguredPath(configuredPath)
                    + "；请修正 " + configKey(toolName) + "，或清空该配置以从 PATH 解析";
        }
        return switch (toolName) {
            case TOOL_YT_DLP -> "未找到 yt-dlp：请设置 " + configKey(toolName)
                    + " 或将 yt-dlp 放入 PATH；本项目自带二进制在 tools/yt-dlp.exe";
            case TOOL_FFMPEG -> "未找到 ffmpeg：请设置 " + configKey(toolName)
                    + " 或加入 PATH；该路径同时供 Java 抽音频与 yt-dlp --ffmpeg-location 使用";
            case TOOL_FFPROBE -> "未找到 ffprobe：请设置 " + configKey(toolName)
                    + " 或加入 PATH；它用于读取媒体的时长与音轨信息";
            default -> "未找到 " + toolName + "：请设置 " + configKey(toolName) + " 或加入 PATH";
        };
    }

    /**
     * 展示配置路径：能解析成路径就给绝对路径（便于定位），配置串本身非法时原样回显。
     * <p>不抛 {@link InvalidPathException}——此时提示语的价值在于「怎么修正配置」，而不是执行细节。
     */
    private static String describeConfiguredPath(String configuredPath) {
        try {
            return Path.of(configuredPath).toAbsolutePath().toString();
        } catch (InvalidPathException e) {
            return configuredPath;
        }
    }

    /**
     * 工具名 → 配置键（config 项名，便于使用者原样复制到 yml）。
     */
    private static String configKey(String toolName) {
        return "vidsage.tools." + toolName + "-path";
    }

    /**
     * 取首行非空白行；无有效行返回 null。
     */
    private static String firstNonBlankLine(String output) {
        if (output == null) {
            return null;
        }
        return output.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse(null);
    }
}
