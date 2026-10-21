package com.vidsage.domain.task.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TaskNo#random()} 静态工厂的纯单元测试：产物是 32 位十六进制（去掉连字符的随机 UUID），
 * 每次生成互不相同。
 */
class TaskNoTest {

    @Test
    void randomIsThirtyTwoHexChars() {
        TaskNo taskNo = TaskNo.random();

        assertEquals(32, taskNo.value().length());
        assertTrue(taskNo.value().matches("[0-9a-f]{32}"), taskNo.value());
    }

    @Test
    void randomIsNotBlank() {
        assertTrue(!TaskNo.random().value().isBlank());
    }

    @Test
    void randomYieldsDistinctValues() {
        assertNotEquals(TaskNo.random(), TaskNo.random());
    }
}
