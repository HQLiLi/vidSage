package com.vidsage.provider.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.model.process.ProcessOutcome;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * ExternalProcessRunner 的纯单元测试：不起 Spring 容器，用 JDK 自带的 java
 * 拉起测试作用域的 {@link ProcessHelperMain} 制造各种子进程行为，走真实的
 * {@link ProcessBuilder} 路径。
 */
class ExternalProcessRunnerTest {

    /** 正常用例的宽松超时：够 JVM 启动。 */
    private static final Duration GENEROUS = Duration.ofSeconds(60);

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void decodesStdoutUsingConfiguredEncoding() throws Exception {
        var runner = new ExternalProcessRunner(GBK);

        ProcessOutcome outcome = runner.run(helperCommand("gbk"), null, GENEROUS);

        assertEquals(0, outcome.exitCode());
        assertEquals("中文标题测试", outcome.stdout(),
                "GBK bytes must be decoded with the native encoding, not UTF-8");
    }

    @Test
    void resolvesConsoleCharsetFromNativeEncodingProperty() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding == null || nativeEncoding.isBlank()) {
            // 属性缺失（JDK 18 以下或极端环境）时兜底 UTF-8
            assertEquals(StandardCharsets.UTF_8, ExternalProcessRunner.resolveConsoleCharset());
            return;
        }
        assertEquals(Charset.forName(nativeEncoding), ExternalProcessRunner.resolveConsoleCharset());
    }

    @Test
    void doesNotDeadlockWhenBothStreamsAreLarge() throws Exception {
        var runner = new ExternalProcessRunner(GBK);

        ProcessOutcome outcome = runner.run(helperCommand("huge"), null, GENEROUS);

        assertEquals(0, outcome.exitCode());
        assertEquals(1024 * 1024, outcome.stdout().length(), "stdout must be fully drained");
        assertEquals(1024 * 1024, outcome.stderr().length(), "stderr must be fully drained");
    }

    @Test
    void returnsNonZeroExitCodeWithoutThrowing() throws Exception {
        var runner = new ExternalProcessRunner(GBK);

        ProcessOutcome outcome = runner.run(helperCommand("fail"), null, GENEROUS);

        assertEquals(3, outcome.exitCode());
        assertFalse(outcome.timedOut());
        assertTrue(outcome.stderr().contains("boom"),
                "stderr should carry the child output: " + outcome.stderr());
    }

    @Test
    void timesOutAndReclaimsProcess() throws Exception {
        var runner = new ExternalProcessRunner(GBK);

        ProcessOutcome outcome = runner.run(helperCommand("sleep"), null, Duration.ofMillis(500));

        assertTrue(outcome.timedOut(), "sleeping helper must be marked timed out");
        assertEquals(-1, outcome.exitCode(), "timed-out process reports -1, not a real exit code");
    }

    @Test
    void drainReturnsEmptyWhenStreamNeverCompletes() {
        // 永不完成的 future 模拟「读线程被孤儿进程挂住」：无界 get() 会永久阻塞，
        // 有界等待必须在一个 grace 周期后返回空串（确定性，无需真造出孤儿进程）
        CompletableFuture<byte[]> neverCompletes = new CompletableFuture<>();
        long graceMs = 100;

        long start = System.currentTimeMillis();
        String drained = ExternalProcessRunner.drain(neverCompletes, GBK, graceMs);
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals("", drained, "a hung stream read must degrade to empty output, not block forever");
        assertTrue(elapsedMs < 5_000, "drain must return within a bounded time, took " + elapsedMs + "ms");
    }

    @Test
    void echoesCommandAsGiven() throws Exception {
        var runner = new ExternalProcessRunner(GBK);
        List<String> command = helperCommand("gbk");

        ProcessOutcome outcome = runner.run(command, null, GENEROUS);

        assertEquals(command, outcome.command(), "caller must be able to reproduce the exact command");
    }

    @Test
    void rejectsEmptyCommand() {
        var runner = new ExternalProcessRunner(GBK);

        assertThrows(IllegalArgumentException.class, () -> runner.run(List.of(), null, GENEROUS));
    }

    @Test
    void rejectsNonPositiveTimeout() throws Exception {
        var runner = new ExternalProcessRunner(GBK);
        List<String> command = helperCommand("gbk");

        assertThrows(IllegalArgumentException.class, () -> runner.run(command, null, Duration.ZERO));
    }

    @Test
    void failsWhenExecutableMissing() {
        var runner = new ExternalProcessRunner(GBK);

        assertThrows(IllegalStateException.class,
                () -> runner.run(List.of("definitely-missing-binary-xyz"), null, GENEROUS));
    }

    /**
     * 用 JDK 自带的 java 启动 {@link ProcessHelperMain}。classpath 取该类的
     * 编译产物目录（它只依赖 JDK），跨平台且不受 Surefire 类路径形态影响。
     */
    private static List<String> helperCommand(String mode) throws Exception {
        String classpath = Path.of(ProcessHelperMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        return List.of(javaBinary(), "-cp", classpath, ProcessHelperMain.class.getName(), mode);
    }

    /**
     * 当前 JVM 对应的 java 可执行文件路径（Windows 需带 .exe 后缀）。
     */
    private static String javaBinary() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String name = windows ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
