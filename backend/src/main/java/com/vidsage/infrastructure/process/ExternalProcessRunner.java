package com.vidsage.infrastructure.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 外部进程执行器：本项目<b>唯一</b>的子进程出口（yt-dlp 与 ffmpeg 共用）。
 *
 * <p>关键行为（逐条有实测依据，见 research/external-process-integration.md）：
 * <ul>
 *   <li>命令行以 {@code List<String>} 逐项传给 {@link ProcessBuilder}，<b>永不拼字符串、永不 shell</b>
 *       —— 规避编码与注入双重风险，也避免 Windows 引号转义地狱；</li>
 *   <li>stdout / stderr <b>各起一条虚拟线程并发读尽</b>，避免双管道写满互锁；不使用
 *       {@code redirectErrorStream(true)}，以免污染正常输出；</li>
 *   <li>输出按 JVM 属性 {@code native.encoding} 解码（本机 yt-dlp 吐 GBK，用 Java 默认字符集
 *       {@code Charset.defaultCharset()}——Java 21 恒为 UTF-8——读会静默乱码），取不到时兜底 UTF-8；</li>
 *   <li>超时后先 {@code destroyForcibly} 后代再杀直接进程，并 {@code waitFor()} 回收；</li>
 *   <li>取流读取结果<b>有界等待</b>（见 {@link #STREAM_DRAIN_GRACE_MS}）：孙子进程若持有管道写端，
 *       读线程可能等不到 EOF，有界等待保证 {@link #run} 仍能返回，不会挂死；</li>
 *   <li><b>非零退出码不抛异常</b>，原样装进 {@link ProcessOutcome}，由调用方分类。</li>
 * </ul>
 *
 * <p><b>已知局限（不假装解决）</b>：Windows 上 {@code destroyForcibly()} 只保证杀掉直接子进程，
 * yt-dlp 内部再拉起的 ffmpeg 可能成为孤儿进程继续写文件。当前按「best-effort 杀进程树」实现
 * （先杀 {@link Process#descendants()}），实测若仍有残留再考虑 Windows Job Object。
 *
 * <p>整个类只在实现内部使用 {@link Process} / {@link ProcessBuilder} / {@link Charset}；
 * 对外只暴露 {@link ProcessOutcome} 这个 record。
 */
@Slf4j
@Component
public class ExternalProcessRunner {

    /** 读 stdout / stderr 的载体：每条流一条虚拟线程（不依赖 Spring 的虚拟线程开关）。 */
    private static final ExecutorService STREAM_READERS = Executors.newVirtualThreadPerTaskExecutor();

    /** 失败时打进日志的 stderr 尾部行数。 */
    private static final int STDERR_TAIL_LINES = 20;

    /**
     * 取流读取结果的等待上限（毫秒）。<b>有界，而不是无界 {@code get()}</b>：超时强杀只保证杀掉
     * 直接子进程，工具自己拉起的孙子进程（yt-dlp -> ffmpeg）可能仍持有 stdout / stderr 写端，
     * 读线程便一直等不到 EOF，无界 {@code get()} 会让 {@link #run} 永不返回，违背「超时不挂死」。
     * 进程<b>正常</b>退出也可能留下持有管道的孤儿，故此处无条件生效，不只在 timedOut 时启用。
     */
    private static final long STREAM_DRAIN_GRACE_MS = 5_000;

    private final Charset consoleCharset;

    /**
     * Spring 使用的构造函数：按 JVM 属性 native.encoding 解析控制台编码。
     */
    public ExternalProcessRunner() {
        this(resolveConsoleCharset());
    }

    /**
     * 包级可见：供测试注入确定性的输出编码，不依赖运行机器的 native.encoding。
     */
    ExternalProcessRunner(Charset consoleCharset) {
        this.consoleCharset = consoleCharset;
    }

    /**
     * 执行一条外部命令，阻塞直到结束或超时。
     *
     * @param command    命令行（第一项为可执行文件）。逐项传递，不经 shell
     * @param workingDir 子进程工作目录；为 {@code null} 时继承当前进程目录
     * @param timeout    超时；到期强制结束进程并标记 timedOut
     * @return 退出码 + stdout + stderr + 实际命令行 + 是否超时 + 耗时。<b>非零退出码不抛异常</b>
     * @throws IllegalArgumentException command 为空，或 timeout 非正
     * @throws IllegalStateException    无法启动进程（可执行文件不存在或不可执行）
     */
    public ProcessOutcome run(List<String> command, Path workingDir, Duration timeout) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("外部命令不能为空：请至少传可执行文件路径");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("超时必须为正数：当前为 " + timeout);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        // DEBUG 打完整命令行：执行器是通用基础设施，具体参数由调用方决定；
        // 若调用方传入了带签名的 URL，请由调用方在更外层做脱敏
        log.debug("执行外部命令: {}", String.join(" ", command));

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动外部命令：" + command.getFirst()
                    + "；请确认该可执行文件存在且在 PATH 上或已正确配置。原因：" + e.getMessage(), e);
        }
        // 立即关闭本端 stdin：子进程（如 ffmpeg）可能阻塞等待交互键
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("关闭子进程 stdin 失败（忽略）: {}", e.getMessage());
        }

        CompletableFuture<byte[]> stdoutFuture = readFully(process.getInputStream());
        CompletableFuture<byte[]> stderrFuture = readFully(process.getErrorStream());

        boolean finished = waitFor(process, timeout, command);
        boolean timedOut = !finished;
        if (timedOut) {
            // 先后代后本体：下载型工具会拉起孙子进程（yt-dlp -> ffmpeg）
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            reclaim(process, command);
        }
        String stdout = drain(stdoutFuture, consoleCharset, STREAM_DRAIN_GRACE_MS);
        String stderr = drain(stderrFuture, consoleCharset, STREAM_DRAIN_GRACE_MS);
        long elapsedMs = System.currentTimeMillis() - start;
        int exitCode = timedOut ? -1 : process.exitValue();

        if (timedOut) {
            log.warn("外部命令超时被强制结束: exe={}, timeoutMs={}, elapsed={}ms, stderrTail={}",
                    command.getFirst(), timeout.toMillis(), elapsedMs, tail(stderr));
        } else if (exitCode != 0) {
            log.warn("外部命令退出码非零: exe={}, exit={}, elapsed={}ms, stderrTail={}",
                    command.getFirst(), exitCode, elapsedMs, tail(stderr));
        } else {
            log.info("外部命令执行成功: exe={}, exit=0, elapsed={}ms", command.getFirst(), elapsedMs);
        }
        return new ProcessOutcome(exitCode, stdout, stderr, List.copyOf(command), timedOut, elapsedMs);
    }

    /**
     * 等待进程结束；被中断时强杀进程、恢复中断标记并向上抛出。
     */
    private static boolean waitFor(Process process, Duration timeout, List<String> command) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("等待外部命令时被中断：" + command.getFirst(), e);
        }
    }

    /**
     * 强制结束后的回收等待，避免留下僵尸进程。被中断时同样恢复中断标记后继续。
     */
    private static void reclaim(Process process, List<String> command) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("回收已强制结束的进程时被中断: exe={}", command.getFirst());
        }
    }

    /**
     * 起一条虚拟线程把整条流读尽。并发读 stdout 与 stderr 是防管道缓冲区写满互锁的关键。
     */
    private static CompletableFuture<byte[]> readFully(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, STREAM_READERS);
    }

    /**
     * 取出读取结果并按指定编码解码，最多等 {@code graceMs} 毫秒。
     *
     * <p>包级可见：便于测试直接喂一个永不完成的 future，确定性验证「读线程被孤儿进程挂住时
     * {@code run()} 仍能在有限时间内返回」。
     *
     * @param future  由 {@link #readFully(InputStream)} 提交的读尽任务
     * @param charset 解码用字符集（生产路径为控制台编码）
     * @param graceMs 等待上限；到期即按空输出返回，不无限阻塞
     * @return 解码后的输出；等待超时或读取失败时为空串（不掩盖调用方的 exitCode / timedOut 信息）
     * @throws IllegalStateException 等待过程中当前线程被中断
     */
    static String drain(CompletableFuture<byte[]> future, Charset charset, long graceMs) {
        try {
            return new String(future.get(graceMs, TimeUnit.MILLISECONDS), charset);
        } catch (TimeoutException e) {
            // 超时强杀只保证杀掉直接子进程，孙子进程可能仍持有管道写端，读线程便等不到 EOF
            log.warn("读取外部进程输出超时（{}ms），可能有孤儿进程仍持有管道，按空输出处理", graceMs);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取外部进程输出时被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // 不抛异常：进程被强制结束（超时 / 中断）时管道可能提前关闭，抛异常会掩盖退出码与
            // timedOut 信息；但读出失败属异常事件——调用方会据 stderr 生成错误消息，静默降级成空串
            // 会让消息缺上下文，故记 WARN 而不是 DEBUG（不落进 quality-guidelines 的「吞异常」反模式）。
            log.warn("读取外部进程输出失败，按空输出处理: {}", cause.getMessage());
            return "";
        }
    }

    /**
     * 解析控制台输出编码：优先 JVM 属性 native.encoding（JDK 18+ 提供），
     * 取不到或非法时兜底 UTF-8。<b>刻意不用 {@code Charset.defaultCharset()}</b>——
     * Java 21 恒为 UTF-8，正是踩坑写法。
     */
    static Charset resolveConsoleCharset() {
        String name = System.getProperty("native.encoding");
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            log.debug("无法解析 native.encoding={}，兜底 UTF-8: {}", name, e.getMessage());
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 取 stderr 尾部若干行用于日志：全文可能很长，尾部通常已含失败原因。
     */
    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> lines = text.lines().toList();
        int from = Math.max(0, lines.size() - STDERR_TAIL_LINES);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
