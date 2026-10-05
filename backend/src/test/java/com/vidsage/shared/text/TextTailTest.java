package com.vidsage.shared.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * TextTail 的纯单测：不起 Spring 容器，直接喂字符串断言尾部截取。
 */
class TextTailTest {

    @Test
    void returnsEmptyForNull() {
        assertEquals("", TextTail.tailLines(null, 3));
    }

    @Test
    void returnsEmptyForBlank() {
        assertEquals("", TextTail.tailLines("   \n  \n", 3));
        assertEquals("", TextTail.tailLines("", 3));
    }

    @Test
    void returnsWholeTextWhenFewerLinesThanMax() {
        assertEquals("a\nb", TextTail.tailLines("a\nb", 3));
    }

    @Test
    void returnsLastLinesWhenMoreLinesThanMax() {
        assertEquals("c\nd", TextTail.tailLines("a\nb\nc\nd", 2));
    }

    @Test
    void returnsWholeTextWhenLineCountEqualsMax() {
        assertEquals("a\nb\nc", TextTail.tailLines("a\nb\nc", 3));
    }
}
