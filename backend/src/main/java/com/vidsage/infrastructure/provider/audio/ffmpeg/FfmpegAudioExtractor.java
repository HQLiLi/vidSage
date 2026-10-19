package com.vidsage.infrastructure.provider.audio.ffmpeg;

import com.vidsage.domain.media.model.AudioStageOutput;
import com.vidsage.domain.media.model.AudioContainer;
import com.vidsage.domain.media.model.AudioSpec;
import com.vidsage.infrastructure.process.ProcessOutcome;
import com.vidsage.domain.media.port.AudioExtractor;
import com.vidsage.infrastructure.process.ExternalProcessRunner;
import com.vidsage.infrastructure.process.ProcessToolDiagnostics;
import com.vidsage.infrastructure.process.ExternalToolProperties;
import com.vidsage.shared.fs.ArtifactFileUtils;
import com.vidsage.shared.text.TextTail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于 ffmpeg 的音频抽取实现。
 *
 * <p>用命令行调 ffmpeg（本机外部进程），参数逐项经 {@link ExternalProcessRunner} 传递，
 * 不拼字符串、不经 shell。
 *
 * <p><b>原子落盘</b>：先写 {@code <outputPath>.part}，成功后 {@link Files#move} 原子改名成最终产物，
 * 失败/超时则删掉 {@code .part}。原子性封在本类内部——调用方拿到的最终路径一旦存在即为完整文件
 * （「文件存在且大小 &gt; 0」才是可靠的断点判据）。
 *
 * <p>输出名以 {@code .part} 结尾会让 ffmpeg 的扩展名推断失效，因此命令里<b>显式</b>用 {@code -f}
 * 指定 muxer（实测见类内 {@link #muxerFor} 注释）。
 */
@Slf4j
@Component
public class FfmpegAudioExtractor implements AudioExtractor {

    /** 失败消息里附带的 stderr 行数上限。 */
    private static final int STDERR_TAIL_LINES = 20;

    /** 临时产物后缀：先写它，成功后原子改名成最终产物。 */
    private static final String PART_SUFFIX = ".part";

    /**
     * ffmpeg 报「{@code -map} 取值非法」（通常音频轨越界）时的稳定子串。
     * 实测原文形如 {@code Failed to set value '0:a:9' for option 'map': Invalid argument}。
     * <b>该报错不含总轨数</b>，故消息里不写「共 N 条轨」。
     */
    private static final String MAP_OPTION_MARKER = "option 'map'";

    /** ffmpeg 报「未知编码器」时的稳定子串，如 {@code Unknown encoder 'aac'}。 */
    private static final String UNKNOWN_ENCODER_MARKER = "Unknown encoder";

    private final ExternalProcessRunner runner;
    private final ProcessToolDiagnostics tools;
    private final ExternalToolProperties properties;

    /**
     * @param runner     外部进程执行器（全项目唯一进程出口）
     * @param tools      工具路径解析（ffmpeg 路径一处配置、两处使用的同一入口）
     * @param properties 外部工具配置（取默认超时）
     */
    public FfmpegAudioExtractor(ExternalProcessRunner runner, ProcessToolDiagnostics tools,
                                ExternalToolProperties properties) {
        this.runner = runner;
        this.tools = tools;
        this.properties = properties;
    }

    @Override
    public AudioStageOutput extract(Path mediaPath, Path outputPath, AudioSpec spec) {
        // 卫语句前置：先失败再干活
        if (mediaPath == null) {
            throw new IllegalArgumentException("媒体文件路径不能为空：请传本地已存在的媒体文件路径");
        }
        if (outputPath == null) {
            throw new IllegalArgumentException("输出路径不能为空：请传目标音频文件的完整路径");
        }
        if (spec == null) {
            throw new IllegalArgumentException("音频规格不能为空：请传 AudioSpec（如 AudioSpec.WAV_PCM_16K_MONO）");
        }
        // 不必再校验 spec 字段：其合法性由 AudioSpec 的紧凑构造器结构性保证

        Path media = mediaPath.toAbsolutePath();
        if (!Files.isRegularFile(media) || !Files.isReadable(media)) {
            throw new IllegalArgumentException("本地媒体文件不存在或不可读：" + media
                    + "；请检查路径，或改用 url 获取");
        }
        Path output = outputPath.toAbsolutePath();
        Path outputDir = output.getParent();
        if (outputDir == null || !Files.isDirectory(outputDir) || !Files.isWritable(outputDir)) {
            throw new IllegalArgumentException("输出目录不存在或不可写：" + outputDir
                    + "；请先创建该目录并确保可写");
        }
        if (Files.exists(output)) {
            // 不覆盖已有产物：Files.move(...ATOMIC_MOVE) 在目标已存在时的行为实现相关
            // （可能抛 FileAlreadyExistsException，也可能替换），不能依赖；且静默覆盖会掩盖
            // 「两条不同规格映射到同一路径」这类缺陷。正常路径下上层已先判存在即跳过
            throw new IllegalStateException("音频产物已存在：" + output
                    + "；extractor 不覆盖已存在的产物，请先删除该文件，或由上层断点复用逻辑跳过本阶段");
        }

        Path ffmpeg = tools.requireFfmpeg();
        Path part = partPath(output);
        // 清掉上一次失败/中断可能残留的半截临时文件，避免它被误当作本次产物
        ArtifactFileUtils.deleteQuietly(part);

        List<String> command = buildCommand(ffmpeg, media, part, spec);
        // 脱敏红线：不整体打印 AudioSpec / ExternalToolProperties（record 的 toString 会带出配置），逐字段打
        log.debug("开始抽取音频: container={}, sampleRateHz={}, channels={}, bitrateKbps={}, audioTrackIndex={}",
                spec.container(), spec.sampleRateHz(), spec.channels(), spec.bitrateKbps(), spec.audioTrackIndex());

        ProcessOutcome outcome = runner.run(command, null, Duration.ofSeconds(properties.defaultTimeoutSeconds()));

        if (outcome.timedOut()) {
            ArtifactFileUtils.deleteQuietly(part);
            // 失败信息分层：面向用户的异常消息有界、可操作（不带 stderr 全文），
            // 完整 stderr 在此落 DEBUG 供深挖——否则完整内容既不在消息也不在日志里
            log.debug("ffmpeg 抽音频超时，完整 stderr: {}", outcome.stderr());
            throw new IllegalStateException("外部命令执行超时（" + properties.defaultTimeoutSeconds()
                    + " 秒）：ffmpeg；可用 vidsage.tools.default-timeout-seconds 调整");
        }
        if (outcome.exitCode() != 0) {
            ArtifactFileUtils.deleteQuietly(part);
            // 失败信息分层：异常消息只带 stderr 尾部 20 行（有界、可操作），
            // 完整 stderr 在此落 DEBUG 供深挖（执行器 WARN 也只打尾部）
            log.debug("ffmpeg 抽音频失败，完整 stderr: exit={}, stderr={}", outcome.exitCode(), outcome.stderr());
            throw new IllegalStateException(failureMessage(outcome.exitCode(), outcome.stderr(), spec));
        }
        long sizeBytes = ArtifactFileUtils.sizeOrZero(part);
        if (sizeBytes <= 0) {
            // ffmpeg 声称成功却没产出：不能静默返回空文件
            ArtifactFileUtils.deleteQuietly(part);
            throw new IllegalStateException("ffmpeg 声称成功但未产出音频（输出缺失或为空）：" + part
                    + "；请确认输入媒体含音频轨，或改用 AudioSpec.audioTrackIndex 指定其它音频轨");
        }
        ArtifactFileUtils.moveAtomically(part, output);
        long finalSize = ArtifactFileUtils.sizeOrZero(output);
        log.info("音频抽取完成: exit=0, container={}, sizeBytes={}, elapsedMs={}",
                spec.container(), finalSize, outcome.elapsedMs());
        return new AudioStageOutput(output, spec, finalSize);
    }

    /**
     * 组装 ffmpeg 命令行（不含任何字符串拼接）。
     *
     * @param ffmpeg ffmpeg 可执行文件路径
     * @param media  输入媒体绝对路径
     * @param part   临时输出路径（以 {@code .part} 结尾）
     * @param spec   输出规格
     * @return 可直接交给 {@link ExternalProcessRunner} 的命令行
     */
    private static List<String> buildCommand(Path ffmpeg, Path media, Path part, AudioSpec spec) {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.add("-hide_banner");
        // -nostdin：ffmpeg 默认会读 stdin，子进程可能因此阻塞等交互键
        command.add("-nostdin");
        // -y：临时文件可能残留，直接覆盖，避免交互提示
        command.add("-y");
        command.add("-i");
        command.add(media.toString());
        command.add("-vn");
        // 显式选轨：ffmpeg 的默认音频选择跟随元数据的 default disposition，多音轨源上不写 -map
        // 会静默抽到非预期轨且不报错（实测：第 2 轨标 default 后默认就抽第 2 轨）
        command.add("-map");
        command.add("0:a:" + spec.audioTrackIndex());
        command.add("-ac");
        command.add(Integer.toString(spec.channels()));
        command.add("-ar");
        command.add(Integer.toString(spec.sampleRateHz()));
        switch (spec.container()) {
            case WAV -> {
                command.add("-c:a");
                command.add("pcm_s16le");
            }
            case M4A -> {
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add(spec.bitrateKbps() + "k");
            }
        }
        // -f 必需：输出名以 .part 结尾，扩展名推断失效（实测 ffmpeg 报
        // "Unable to choose an output format for 'xxx.part'"），必须显式给 muxer
        command.add("-f");
        command.add(muxerFor(spec.container()));
        command.add(part.toString());
        return command;
    }

    /**
     * 容器 → ffmpeg muxer 名。
     *
     * <p>实测：{@code -f wav} 正常产出 PCM WAV；
     * M4A 用 {@code -f ipod}（产物 {@code ftyp} brand 为 {@code M4A}），而 {@code -f mp4}
     * 产出的是 {@code isom} brand —— 取 {@code ipod} 才是标准的 .m4a 品牌，也顺带让行为不依赖
     * ffmpeg 的扩展名表。
     */
    private static String muxerFor(AudioContainer container) {
        return switch (container) {
            case WAV -> "wav";
            case M4A -> "ipod";
        };
    }

    /**
     * 生成失败消息，含「发生了什么 + 怎么处理」，并附 ffmpeg stderr 尾部供定位。
     *
     * <p>只对两条**有稳定子串**的失败做专门指引（{@code -map} 越界、编码器缺失）；其余一律
     * 「未知失败 + stderr 尾部」，<b>不按 stderr 文案猜业务错误类型</b>（文案属外部实现细节，
     * 会随 ffmpeg 版本变化）。
     */
    private static String failureMessage(int exitCode, String stderr, AudioSpec spec) {
        String tail = TextTail.tailLines(stderr, STDERR_TAIL_LINES);
        String raw = stderr == null ? "" : stderr;
        if (raw.contains(MAP_OPTION_MARKER)) {
            // 透传 ffmpeg 原文；不写「共 N 条轨」——该报错不含总轨数
            return "ffmpeg 抽音频失败（exit=" + exitCode + "）：" + tail
                    + "；音频轨不存在，请检查 AudioSpec.audioTrackIndex（当前为 "
                    + spec.audioTrackIndex() + "）";
        }
        if (raw.contains(UNKNOWN_ENCODER_MARKER)) {
            // 按容器分流：WAV 失败时不能再建议「改用 WAV」——那正是失败的规格
            return switch (spec.container()) {
                case M4A -> "该 ffmpeg 构建不支持 aac 编码器：请把 vidsage.tools.ffmpeg-path 指向完整构建"
                        + "（容器里的精简构建常缺），或改用 AudioSpec.WAV_PCM_16K_MONO；ffmpeg 原文：" + tail;
                case WAV -> "该 ffmpeg 构建不支持 pcm_s16le 编码器：请把 vidsage.tools.ffmpeg-path 指向完整构建"
                        + "（容器里的精简构建常缺）；ffmpeg 原文：" + tail;
            };
        }
        return "ffmpeg 抽音频失败（exit=" + exitCode + "）：" + tail;
    }

    /**
     * 临时产物路径：最终产物名 + {@code .part}。
     */
    private static Path partPath(Path output) {
        return output.resolveSibling(output.getFileName().toString() + PART_SUFFIX);
    }
}
