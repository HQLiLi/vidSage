package com.vidsage.infrastructure.provider.llm;

import java.util.List;

/**
 * 提示词里的「数据块」渲染：把一段<b>外部来源</b>的文本裹进固定标签，并做结构性字符归一。
 *
 * <p>归一的是结构性字符（换行、回车与其它 ISO 控制字符 → 空格）：内容里的一个换行就能从结构上
 * 凭空造出「新的一段指令」。内容性字符（{@code <}、{@code "}、{@code {} 等）<b>保留</b>——
 * 剥字符是黑名单思路，会误伤真术语。真正的兜底在输出侧：键集必须一致 + 相似度阈值 + 全败保留原文。
 */
final class PromptDataBlock {

    private PromptDataBlock() {
    }

    /**
     * 渲染一段外部文本成带标签的数据块：{@code <tag>…</tag>}。
     *
     * @param tag  标签名（如 {@code theme}）；由调用点固定，不含用户内容
     * @param body 外部文本，可为 {@code null}（当空串处理）
     * @return 带标签的数据块；body 为空白时仍渲染出标签对（形状恒定，省掉调用点的判空分支）
     */
    static String render(String tag, String body) {
        return "<" + tag + ">\n" + normalize(body) + "\n</" + tag + ">";
    }

    /**
     * 渲染多行外部文本成带标签的数据块：<b>逐条归一</b>后再用我们自己的换行拼回去。
     *
     * <p>逐条是关键：一段内容里的换行若原样留在块里，它就能独占一整行、伪装成一条新指令。
     * 归一之后每条内容都占不了两行，而多条之间的分隔换行是我们自己发的，不是内容伪造的。
     *
     * @param tag   标签名（如 {@code subsequent_content}）
     * @param lines 外部文本各条，可为空列表
     * @return 带标签的数据块；无内容时仍渲染出标签对
     */
    static String renderLines(String tag, List<String> lines) {
        // 逐条归一再自己拼换行——不能借道 render：它会把整个 body 归一化，
        // 连我们自己发的行分隔换行也换成空格，多行数据块会挤成一行
        StringBuilder sb = new StringBuilder("<").append(tag).append(">\n");
        for (String line : lines) {
            sb.append(normalize(line)).append('\n');
        }
        return sb.append("</").append(tag).append('>').toString();
    }

    /**
     * 结构性字符归一：换行、回车与其它 ISO 控制字符换成空格，再去掉首尾空白。
     * 内容性字符一律原样保留（理由见类注释）。
     *
     * @param raw 原始文本，可为 {@code null}
     * @return 归一后的文本；{@code null} 得空串
     */
    static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        return sb.toString().trim();
    }
}
