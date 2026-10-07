package com.vidsage.infrastructure.provider.download.local;

import com.vidsage.domain.media.model.AcquiredMedia;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.infrastructure.process.ProcessOutcome;
import com.vidsage.domain.media.port.MediaAcquirer;
import com.vidsage.infrastructure.process.ExternalProcessRunner;
import com.vidsage.infrastructure.process.ProcessToolDiagnostics;
import com.vidsage.infrastructure.process.ExternalToolProperties;
import com.vidsage.shared.fs.ArtifactFiles;
import com.vidsage.shared.text.TextTail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地文件路线的输入获取：直接采用本机媒体文件，不下载、不拷贝。
 *
 * <p>承接两种输入：{@link MediaInput.LocalPath}（调用方给的本机文件）与
 * {@link MediaInput.InWorkspace}（MVP-1 上传落盘后的工作区文件）——两者都是「文件已在本机」，
 * 采用方式完全一致。
 *
 * <p>标题取文件名；时长用 {@code ffprobe} 补齐（ffprobe 本就在必检工具清单里），
 * 顺带起到「是否是可解析的媒体文件」的校验作用。本地路线没有 info json，
 * 故 {@link AcquiredMedia#infoJsonPath()} 恒为 {@link Optional#empty()}。
 *
 * <p>两个下载器同时驻留容器，故<b>不加</b> {@code @ConditionalOnProperty}。
 */
@Slf4j
@Component
public class LocalFileMediaAcquirer implements MediaAcquirer {

    /** 失败消息里附带的 ffprobe stderr 行数上限（与执行器一致）。 */
    private static final int STDERR_TAIL_LINES = 20;

    private final ExternalProcessRunner runner;
    private final ProcessToolDiagnostics tools;
    private final ExternalToolProperties properties;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param runner     外部进程执行器（ffprobe 经它调用）
     * @param tools      工具路径解析（ffprobe 路径的唯一入口）
     * @param properties 外部工具配置（取默认超时）
     */
    public LocalFileMediaAcquirer(ExternalProcessRunner runner, ProcessToolDiagnostics tools,
                                  ExternalToolProperties properties) {
        this.runner = runner;
        this.tools = tools;
        this.properties = properties;
    }

    @Override
    public boolean supports(MediaInput input) {
        return input instanceof MediaInput.LocalPath || input instanceof MediaInput.InWorkspace;
    }

    /**
     * 本地路线恒返回空：它<b>不下载、不拷贝</b>——输入文件本身就是产物，始终原地可读，
     * 没有「上一轮留下的下载物」可复用。若这里返回已存在的文件，反而会让获取媒体阶段永远被
     * 跳过、把「文件不存在 / 不可读」的校验与 ffprobe 时长读取一并省掉。
     *
     * <p>因此本路线每次都由 {@link #acquire} 重新校验并读时长；真正的断点复用落在
     * 「不重复抽音频」（由阶段编排判断音频产物是否存在）。
     *
     * @return 恒为 {@link Optional#empty()}
     */
    @Override
    public Optional<AcquiredMedia> reuseExisting(MediaInput input, Path workDir) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.LocalPath（本地文件）或 MediaInput.RemoteUrl（远程 URL）");
        }
        if (!supports(input)) {
            throw new IllegalArgumentException("LocalFileMediaAcquirer 只支持本地文件输入（LocalPath / InWorkspace）："
                    + "当前为 " + input.getClass().getSimpleName() + "，远程 URL 请交给 yt-dlp 路线");
        }
        return Optional.empty();
    }

    @Override
    public AcquiredMedia acquire(MediaInput input, Path workDir) {
        // 卫语句前置：先失败再干活
        if (input == null) {
            throw new IllegalArgumentException(
                    "媒体输入不能为空：请传 MediaInput.LocalPath（本地文件）或 MediaInput.RemoteUrl（远程 URL）");
        }
        if (!supports(input)) {
            throw new IllegalArgumentException("LocalFileMediaAcquirer 只支持本地文件输入（LocalPath / InWorkspace）："
                    + "当前为 " + input.getClass().getSimpleName() + "，远程 URL 请交给 yt-dlp 路线");
        }
        // 两种本地形态取同一个「媒体文件路径」：LocalPath 本身，或 InWorkspace 携带的工作区文件
        Path file = switch (input) {
            case MediaInput.LocalPath local -> local.value();
            case MediaInput.InWorkspace in -> in.value();
            default -> throw new IllegalArgumentException("LocalFileMediaAcquirer 只支持本地文件输入"
                    + "（LocalPath / InWorkspace）：当前为 " + input.getClass().getSimpleName());
        };
        if (file == null) {
            throw new IllegalArgumentException("本地媒体文件路径不能为空：请检查路径，或改传 url");
        }
        file = file.toAbsolutePath();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("本地媒体文件不存在或不可读：" + file + "；请检查路径，或改传 url");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("本地媒体文件不是普通文件：" + file + "；请传具体的媒体文件，或改传 url");
        }
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("本地媒体文件不可读：" + file
                    + "；请给服务运行账号授予读权限，或改传 url");
        }
        // 媒体时长（毫秒）
        long durationMs = probeDurationMs(file);
        // 本地路线不拷贝，直接采用；workDir 留给 2b 落字幕产物用，本阶段不需要
        log.info("采用本地媒体文件: file={}, durationMs={}, sizeBytes={}",
                file.getFileName(), durationMs, ArtifactFiles.sizeOrZero(file));
        return new AcquiredMedia(file, file.getFileName().toString(), durationMs, Optional.empty());
    }

    /**
     * 用 ffprobe 读取媒体时长（秒，浮点）并转毫秒。
     *
     * @param file 本地媒体文件（已校验存在且可读）
     * @return 时长毫秒
     * @throws IllegalStateException    未找到 ffprobe，或 ffprobe 超时
     * @throws IllegalArgumentException ffprobe 无法把该文件解析成媒体（不是调用方以为的那个文件）
     */
    long probeDurationMs(Path file) {
        var command = List.of(tools.requireFfprobe().toString(), "-v", "error",
                "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.toString());
        ProcessOutcome outcome = runner.run(command, null, Duration.ofSeconds(properties.defaultTimeoutSeconds()));
        if (outcome.timedOut()) {
            throw new IllegalStateException("外部命令执行超时（" + properties.defaultTimeoutSeconds()
                    + " 秒）：ffprobe；可用 vidsage.tools.default-timeout-seconds 调整");
        }
        if (outcome.exitCode() != 0) {
            // 文件存在且可读，ffprobe 却打不开：多半不是可解析的媒体文件，属调用方输入问题
            throw new IllegalArgumentException("无法读取媒体时长（ffprobe exit=" + outcome.exitCode() + "）："
                    + TextTail.tailLines(outcome.stderr(), STDERR_TAIL_LINES) + "；请确认为可解析的媒体文件，或改传 url");
        }
        return parseDurationMs(outcome.stdout())
                .orElseThrow(() -> new IllegalArgumentException("ffprobe 未返回可解析的时长："
                        + outcome.stdout().trim() + "；请确认为可解析的媒体文件，或改传 url"));
    }

    /**
     * 解析 ffprobe 的 {@code format=duration} 输出（形如 {@code 47.717000}）为毫秒。
     *
     * <p>包级 static：测试可直接喂字符串，不必拉起真实 ffprobe（见 quality-guidelines）。
     *
     * @param stdout ffprobe 的标准输出
     * @return 毫秒；输出为空、非数字或为负时返回空
     */
    static OptionalLong parseDurationMs(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return OptionalLong.empty();
        }
        String value = stdout.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse(null);
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds < 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Math.round(seconds * 1000));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
