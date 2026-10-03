package com.vidsage.provider.llm;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 跨批对话历史的公共操作：按「一对往返」为单位裁剪、算累计字符数。
 *
 * <p>字幕清洗与翻译两个 worker 都要维护一份「前几批的原文 + 已采纳的回复」历史，裁剪规矩也一样：
 * 以一对往返（user + assistant）为单位从最老的开始丢，保证历史永远 user 开头、assistant 结尾、
 * 条数为偶数。只按条裁会造出「孤立的 assistant」，模型看见一个没有对应问题的回答。
 *
 * <p>这条不变量很微妙，两份实现必然分叉，故抽到一处共用（同一判据只有一份，见 directory-structure.md
 * 的同名规则）。它是一条已定型的纯函数判定，不是 design 决策 8 要推迟的「切批判据与校验循环」那套
 * 骨架——那些骨架里两个 worker 接的东西并不一样，抽了会返工。
 *
 * <p>包级可见：只有 {@code provider/llm/} 下的两个 worker 用它。
 */
final class MessageHistory {

    private MessageHistory() {
        // 纯静态工具，不实例化
    }


    /**
     * 按字符上限裁剪跨批历史，以「一对往返」为单位从最老的开始丢。
     *
     * <p>守卫是 {@code size() > 2}：保证刚追加的那一对无论多长都保留。否则遇到「单批正文就超上限」
     * 的配置会把历史清空到 0，等于白算一次裁剪。
     *
     * @param history  跨批历史，原地裁剪
     * @param maxChars 字符上限
     */
    static void trimHistory(List<Message> history, int maxChars) {
        while (history.size() > 2 && chars(history) > maxChars) {
            history.subList(0, 2).clear();
        }
    }


    /**
     * 跨批历史的累计字符数。
     *
     * <p>只算历史，不含系统提示词、格式说明与本批任务——那些每次必须发、裁不掉，
     * 计进来会让上限的语义变成「整条请求的预算」，那是另一个旋钮。
     *
     * <p>是字符不是 token，属<b>粗代理</b>：中文约 1 字 ≈ 1 token（估算准），
     * 英文约 4 字 ≈ 1 token（偏保守，会提前裁剪）。不引入 tokenizer。
     *
     * @param history 跨批历史
     * @return 累计字符数
     */
    static int chars(List<Message> history) {
        int total = 0;
        for (Message m : history) {
            total += safe(m.getText()).length();
        }
        return total;
    }


    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
