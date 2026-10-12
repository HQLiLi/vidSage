package com.vidsage.application.task.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.shared.model.WorkspaceKey;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskSessionRegistry} 的纯单元测试：钉住「抢登记 / 退登记」这对锁语义。
 *
 * <p>PRD 的 AC9 / AC10（并发 409 后无残留、失败路径退干净）在 HTTP 层还要再验一遍，
 * 这里先把地基钉住：登记表本身不漏、不串。
 */
class TaskSessionRegistryTest {

    private static final WorkspaceKey KEY_A = new WorkspaceKey("a1b2c3d4e5f6");
    private static final WorkspaceKey KEY_B = new WorkspaceKey("b1b2c3d4e5f6");

    @Test
    void tryStartGrantsFirstAndRejectsSecond() {
        TaskSessionRegistry registry = new TaskSessionRegistry();

        assertTrue(registry.tryStart(KEY_A, "CLEAN_SUBTITLES"), "第一个会话抢到登记");
        assertFalse(registry.tryStart(KEY_A, "CLEAN_SUBTITLES"), "同一工作区已在跑，后到者必须抢不到（409 的依据）");
        assertEquals("CLEAN_SUBTITLES", registry.findRunningStage(KEY_A), "登记的阶段名要能读回来给快照用");
    }

    @Test
    void workspacesAreIndependent() {
        TaskSessionRegistry registry = new TaskSessionRegistry();
        assertTrue(registry.tryStart(KEY_A, "CLEAN_SUBTITLES"));

        assertTrue(registry.tryStart(KEY_B, "CLEAN_SUBTITLES"),
                "互斥是工作区级的：另一个工作区的会话不该被挡");
    }

    @Test
    void finishReleasesTheLockForNextSession() {
        // AC9 的下半句：409 之后没有登记残留——紧接着再触发一次必须能跑通
        TaskSessionRegistry registry = new TaskSessionRegistry();
        assertTrue(registry.tryStart(KEY_A, "CLEAN_SUBTITLES"));
        assertFalse(registry.tryStart(KEY_A, "CLEAN_SUBTITLES"), "先证明确实被挡住");

        registry.finish(KEY_A);

        assertNull(registry.findRunningStage(KEY_A));
        assertTrue(registry.tryStart(KEY_A, "CLEAN_SUBTITLES"), "退干净了就该放得进下一个会话");
    }

    @Test
    void finishIsIdempotent() {
        // AC10 的地基：异常路径上 finally 可能连着补一次，重复 finish 必须无害
        TaskSessionRegistry registry = new TaskSessionRegistry();
        registry.finish(KEY_A);
        registry.finish(KEY_A);

        assertTrue(registry.tryStart(KEY_A, "CLEAN_SUBTITLES"), "没登记过的工作区 finish 完照样抢得进");
    }

    @Test
    void findRunningStageReturnsNullWhenNeverStarted() {
        assertNull(new TaskSessionRegistry().findRunningStage(KEY_A));
    }
}
