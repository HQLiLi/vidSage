package com.vidsage.infrastructure.provider.download.ytdlp;

import com.vidsage.infrastructure.process.ProcessOutcome;
import com.vidsage.infrastructure.process.ExternalProcessRunner;
import com.vidsage.infrastructure.process.ProcessToolDiagnostics;
import com.vidsage.infrastructure.process.ExternalToolProperties;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * yt-dlp 调用的公共收口：二进制解析、公共参数注入（{@code --ffmpeg-location} / {@code --cookies}）、
 * 超时，全部收敛在这里，<b>调用方只给额外参数</b>。
 *
 * <p>参数以 {@code List<String>} 逐项交给 {@link ExternalProcessRunner}，不拼字符串、不经 shell；
 * 公共参数排在调用方额外参数之前，调用方把 URL 放在自己参数的最后即可。
 */
@Slf4j
@Component
@EnableConfigurationProperties(YtDlpProperties.class)
public class YtDlpRunner {

    private final ExternalProcessRunner runner;
    private final ProcessToolDiagnostics tools;
    private final ExternalToolProperties toolProperties;
    private final YtDlpProperties ytDlpProperties;

    /**
     * @param runner          外部进程执行器（全项目唯一进程出口）
     * @param tools           工具路径解析（yt-dlp / ffmpeg 路径的唯一入口）
     * @param toolProperties  外部工具配置（取默认超时、判断 ffmpeg 是否已配置）
     * @param ytDlpProperties yt-dlp 专用配置（cookies 文件）
     */
    public YtDlpRunner(ExternalProcessRunner runner, ProcessToolDiagnostics tools,
                       ExternalToolProperties toolProperties, YtDlpProperties ytDlpProperties) {
        this.runner = runner;
        this.tools = tools;
        this.toolProperties = toolProperties;
        this.ytDlpProperties = ytDlpProperties;
    }

    /**
     * 执行一次 yt-dlp：命令行 = 解析到的二进制 + 公共参数 + 调用方额外参数。
     *
     * @param extraArgs yt-dlp 专属参数（含末尾的 URL / 子命令），逐项传递；不可为空
     * @param workDir   子进程工作目录；可为 {@code null}（继承当前目录）
     * @return 进程结果；<b>非零退出码不抛异常</b>，由调用方分类
     * @throws IllegalArgumentException extraArgs 为空
     * @throws IllegalStateException    未找到 yt-dlp、配置的 cookies 文件不存在，或进程无法启动
     */
    public ProcessOutcome run(List<String> extraArgs, Path workDir) {
        if (extraArgs == null || extraArgs.isEmpty()) {
            throw new IllegalArgumentException("yt-dlp 参数不能为空：请至少传入目标 URL 或子命令");
        }
        List<String> command = new ArrayList<>();
        command.add(tools.requireYtDlp().toString());
        appendFfmpegLocation(command);
        appendCookies(command);
        command.addAll(extraArgs);

        Duration timeout = Duration.ofSeconds(timeoutSeconds());
        // 脱敏：只记参数个数与工作目录，不记参数内容（可能含 URL 等用户内容）
        log.debug("执行 yt-dlp: extraArgCount={}, workingDir={}", extraArgs.size(), workDir);
        return runner.run(command, workDir, timeout);
    }

    /**
     * 本执行器实际使用的超时秒数，与 {@link #run} 交给执行器的值<b>同源</b>。
     *
     * <p>供调用方在超时的错误消息里写出真实秒数。放在这里而不是让调用方自行解析配置，
     * 是为了避免「超时值」有两处持有、日后调阈值时两处漂移。
     *
     * @return 默认超时秒数
     */
    long timeoutSeconds() {
        return toolProperties.defaultTimeoutSeconds();
    }

    /**
     * 注入 {@code --ffmpeg-location}（配置了 ffmpeg 才注入）。
     *
     * <p>未配置时 ffmpeg 由 yt-dlp 自行从 PATH 发现（实测可发现）；配置后注入解析到的绝对路径，
     * 与 Java 抽音频走同一处配置项（{@code vidsage.tools.ffmpeg-path}，一处配置、两处使用）。
     */
    private void appendFfmpegLocation(List<String> command) {
        if (!StringUtils.hasText(toolProperties.ffmpegPath())) {
            return;
        }
        command.add("--ffmpeg-location");
        command.add(tools.requireFfmpeg().toString());
    }

    /**
     * 注入 {@code --cookies}（仅在配置了 cookies 文件时）。
     *
     * <p>配置了却指向不存在的文件时提前失败并给出「怎么补」——否则错误只会以 yt-dlp 的英文
     * 输出形式暴露，使用者难以对应到配置项。
     */
    private void appendCookies(List<String> command) {
        if (!StringUtils.hasText(ytDlpProperties.cookiesFile())) {
            return;
        }
        Path cookies;
        try {
            cookies = Path.of(ytDlpProperties.cookiesFile()).toAbsolutePath();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("配置的 cookies 文件路径非法：" + ytDlpProperties.cookiesFile()
                    + "；请修正 vidsage.download.yt-dlp.cookies-file，或清空该配置");
        }
        if (!Files.isRegularFile(cookies)) {
            throw new IllegalStateException("配置的 cookies 文件不存在：" + cookies
                    + "；请修正 vidsage.download.yt-dlp.cookies-file，或清空该配置");
        }
        command.add("--cookies");
        command.add(cookies.toString());
    }
}
