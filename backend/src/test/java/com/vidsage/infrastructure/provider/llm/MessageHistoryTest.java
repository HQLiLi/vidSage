package com.vidsage.infrastructure.provider.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * {@link MessageHistory} 的纯单元测试：<b>不起 Spring 容器</b>。
 */
class MessageHistoryTest {

    @Test
    void 历史超上限时丢掉最老的一轮往返() {
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage("u1"), new AssistantMessage("a1"),
                new UserMessage("u2"), new AssistantMessage("a2"),
                new UserMessage("u3"), new AssistantMessage("a3")));   // 共 12 字符

        MessageHistory.trimHistory(history, 9);   // 丢一对变 8 字符 ≤ 9，停

        assertEquals(4, history.size(), "只丢最老的一对");
        assertEquals("u2", history.getFirst().getText());
        assertEquals(UserMessage.class, history.getFirst().getClass(), "历史必须以 user 开头");
        assertEquals(AssistantMessage.class, history.getLast().getClass());
    }

    @Test
    void 只有一轮历史时即使超限也不丢() {
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage("u1"), new AssistantMessage("a1")));

        MessageHistory.trimHistory(history, 1);

        assertEquals(2, history.size(), "刚追加的那一对无论多长都要保留");
        assertEquals("u1", history.getFirst().getText());
    }

    @Test
    void 累计字符数只算历史消息() {
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage("你好"), new AssistantMessage("Hello")));

        assertEquals(7, MessageHistory.chars(history), "2 + 5 个字符");
    }
}
