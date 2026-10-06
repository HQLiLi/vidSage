package com.vidsage.infrastructure.process;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * ExternalProcessRunnerTest 的子进程替身：按参数制造四种确定的输出/退出行为，
 * 让执行器的「编码 / 防死锁 / 非零退出 / 超时」四条路径都能在无外部依赖下被真实走一遍。
 *
 * <p>由 JDK 自带的 java 启动（{@code java -cp <test-classes> ...ProcessHelperMain <mode>}），
 * 跨平台、不依赖 yt-dlp / ffmpeg。它是测试作用域工具，不进主代码。
 */
public final class ProcessHelperMain {

    private ProcessHelperMain() {
    }

    /**
     * @param args 单一参数，取值 gbk / huge / fail / sleep
     */
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "gbk" -> writeGbkChineseToStdout();
            case "huge" -> writeHugeOutputToBothStreams();
            case "fail" -> writeFailure();
            case "sleep" -> Thread.sleep(60_000);
            default -> {
                System.err.println("unknown mode: " + mode);
                System.exit(2);
            }
        }
    }

    /**
     * 输出 GBK 编码的中文：验证执行器按 native.encoding 解码，而不是按 Java 默认的 UTF-8。
     */
    private static void writeGbkChineseToStdout() throws IOException {
        System.out.write("中文标题测试".getBytes(Charset.forName("GBK")));
        System.out.flush();
    }

    /**
     * stdout 与 stderr 各写约 1 MB，塞满管道缓冲区：验证执行器并发读尽、不会互锁。
     */
    private static void writeHugeOutputToBothStreams() throws IOException {
        byte[] chunk = "x".repeat(1024).getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 1024; i++) {
            System.out.write(chunk);
            System.err.write(chunk);
        }
        System.out.flush();
        System.err.flush();
    }

    /**
     * 非零退出（3）并写 stderr：验证执行器不抛异常、原样把退出码装进结果。
     */
    private static void writeFailure() {
        System.err.println("boom: simulated failure");
        System.err.flush();
        System.exit(3);
    }
}
