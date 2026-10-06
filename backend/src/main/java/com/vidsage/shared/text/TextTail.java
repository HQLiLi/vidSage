package com.vidsage.shared.text;

import org.springframework.util.StringUtils;

/**
 * 文本尾部截取助手：外部程序报错时全文可能很长，尾部通常已含失败原因。
 *
 * <p>全静态、无状态，构造器私有，不要 new 它。
 */
public final class TextTail {

    private TextTail() {
    }

    /**
     * 取文本尾部若干行：全文可能很长（含输入流转储），尾部通常已含失败原因。
     *
     * <p>判空用 {@code StringUtils.hasText}：它是纯静态工具、不牵扯 Spring 容器，且与库内
     * 其他判空写法保持一致（error-handling 规定字符串判空一律用它）。
     *
     * @param text     原始文本；{@code null}、空串或纯空白返回空串
     * @param maxLines 保留的最大行数；行数不足时返回全文
     * @return 尾部至多 {@code maxLines} 行，以 {@code \n} 连接；无内容时为空串
     */
    public static String tailLines(String text, int maxLines) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        var lines = text.lines().toList();
        int from = Math.max(0, lines.size() - maxLines);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
