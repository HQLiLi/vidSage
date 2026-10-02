package com.vidsage.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.model.task.TaskInputType;
import com.vidsage.model.task.TaskStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link VideoTask} 状态机的纯单元测试：不起容器。
 *
 * <p>覆盖「合法迁移三条」「非法迁移拒绝」（重点钉住 SUCCEEDED → RUNNING 这类终态再迁移）
 * 「阶段名推进的时机约束」「newPending / restore 的出生状态」。
 */
class VideoTaskTest {

    // ---------- 出生状态 ----------

    @Test
    void newPendingStartsAsPendingWithGeneratedTaskNo() {
        VideoTask task = VideoTask.newPending(TaskInputType.URL, "https://example.com/v", "a1b2c3d4e5f6", "dir-a1b2c3d4e5f6");

        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertTrue(task.getTaskNo().matches("[0-9a-f]{32}"), () -> "taskNo 应为 32 位十六进制：" + task.getTaskNo());
        assertNull(task.getId(), "未落库前 id 应为 null");
        assertNotNull(task.getCreatedAt());
        assertEquals(task.getCreatedAt(), task.getUpdatedAt());
        assertNull(task.getStartedAt(), "未开始前 startedAt 应为 null");
        assertNull(task.getFinishedAt(), "未到终态前 finishedAt 应为 null");
        assertNull(task.getCurrentStage());
        assertNull(task.getErrorMessage());
    }

    @Test
    void restoreRebuildsPersistedFieldsVerbatim() {
        LocalDateTime created = LocalDateTime.of(2026, 9, 30, 10, 0);
        VideoTask task = VideoTask.restore(7L, "abc123", TaskInputType.UPLOAD, "demo.mp4",
                "a1b2c3d4e5f6", "dir", TaskStatus.FAILED, "translate", "翻译失败", created,
                created.plusMinutes(5), created.plusSeconds(1), created.plusMinutes(5));

        assertEquals(7L, task.getId());
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("translate", task.getCurrentStage());
        assertEquals("翻译失败", task.getErrorMessage());
        assertEquals(created, task.getCreatedAt());
        assertEquals(created.plusMinutes(5), task.getFinishedAt());
    }

    // ---------- 合法迁移 ----------

    @Test
    void startTransitionsPendingToRunning() {
        VideoTask task = newPending();

        task.start();

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertNotNull(task.getStartedAt(), "start 应落 startedAt");
        assertNotNull(task.getUpdatedAt());
    }

    @Test
    void succeedTransitionsRunningToSucceeded() {
        VideoTask task = running();

        task.succeed();

        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
        assertNotNull(task.getFinishedAt(), "succeed 应落 finishedAt");
    }

    @Test
    void failTransitionsRunningToFailedWithMessage() {
        VideoTask task = running();

        task.fail("转写失败：请检查 DASHSCOPE_API_KEY");

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("转写失败：请检查 DASHSCOPE_API_KEY", task.getErrorMessage());
        assertNotNull(task.getFinishedAt());
    }

    @Test
    void updateStageRecordsCurrentStageWhileRunning() {
        VideoTask task = running();

        task.updateStage("extract-audio");

        assertEquals("extract-audio", task.getCurrentStage());
        assertEquals(TaskStatus.RUNNING, task.getStatus());
    }

    // ---------- 非法迁移拒绝 ----------

    @Test
    void rejectsStartWhenAlreadyRunning() {
        VideoTask task = running();

        IllegalStateException e = assertThrows(IllegalStateException.class, task::start);

        assertTrue(e.getMessage().contains("非法状态迁移"), e.getMessage());
    }

    @Test
    void rejectsStartFromTerminalStates() {
        VideoTask succeeded = running();
        succeeded.succeed();
        VideoTask failed = running();
        failed.fail("x");

        // dispatch 点名要求钉住的迁移：SUCCEEDED → RUNNING 必须被拒绝
        assertThrows(IllegalStateException.class, succeeded::start);
        assertThrows(IllegalStateException.class, failed::start);
        // 终态不可变：状态与时间戳不被破坏
        assertEquals(TaskStatus.SUCCEEDED, succeeded.getStatus());
        assertEquals(TaskStatus.FAILED, failed.getStatus());
    }

    @Test
    void rejectsSucceedFromPending() {
        VideoTask task = newPending();

        assertThrows(IllegalStateException.class, task::succeed);
        assertEquals(TaskStatus.PENDING, task.getStatus());
    }

    @Test
    void rejectsFailFromPendingAndTerminalStates() {
        VideoTask pending = newPending();
        VideoTask succeeded = running();
        succeeded.succeed();
        VideoTask failed = running();
        failed.fail("x");

        assertThrows(IllegalStateException.class, () -> pending.fail("y"));
        assertThrows(IllegalStateException.class, () -> succeeded.fail("y"));
        assertThrows(IllegalStateException.class, () -> failed.fail("y"));
    }

    @Test
    void rejectsUpdateStageWhenNotRunning() {
        VideoTask pending = newPending();
        VideoTask succeeded = running();
        succeeded.succeed();

        assertThrows(IllegalStateException.class, () -> pending.updateStage("clean"));
        assertThrows(IllegalStateException.class, () -> succeeded.updateStage("clean"));
        assertNull(pending.getCurrentStage(), "被拒绝的阶段推进不应留下脏状态");
    }

    // ---------- 工厂 ----------

    private static VideoTask newPending() {
        return VideoTask.newPending(TaskInputType.URL, "https://example.com/v", "a1b2c3d4e5f6", "dir");
    }

    private static VideoTask running() {
        VideoTask task = newPending();
        task.start();
        return task;
    }
}
