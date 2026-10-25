package com.vidsage.shared.error;

/**
 * 把异常渲染成文本。
 *
 * <p>刻意<b>只取类名与栈帧、不取 message</b>：异常消息可能带 URL（下载器会把外部命令的 stderr
 * 拼进去），而日志有「来源原文不得落盘」的红线。消息本身已经落在 {@code run.error_message} 里，
 * 排查时去那儿看。
 */
public final class ThrowableUtils {

    private ThrowableUtils() {
    }

    /**
     * 异常的类名与调用栈帧（<b>不含 message</b>），沿 cause 链一路展开。
     *
     * @param t 入口异常；不可为 {@code null}
     * @return 形如 {@code 类名\n\tat ...} 的多行文本，cause 链以 {@code Caused by: } 分段
     */
    public static String stackOf(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (out.length() > 0) {
                out.append("\nCaused by: ");
            }
            out.append(cur.getClass().getName());
            for (StackTraceElement frame : cur.getStackTrace()) {
                out.append("\n\tat ").append(frame);
            }
        }
        return out.toString();
    }
}
