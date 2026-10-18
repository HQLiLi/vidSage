package com.vidsage.domain.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.subtitle.model.TaskRunStatus;
import com.vidsage.domain.subtitle.model.TaskRunType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskRun} 状态机的纯单元测试：不起容器。
 *
 * <p>逐值覆盖：{@code RUNNING} 出生 → {@code SUCCEEDED} / {@code FAILED}；
 * 非法迁移（终态再迁移、非 RUNNING 迁移）抛 {@link IllegalStateException}。
 * {@code INTERRUPTED} 由启动收口的批量 UPDATE 补记，实体<b>没有</b>对应方法——用 {@code restore} 造出来验证。
 */
class TaskRunTest {

    // ---------- 出生状态 ----------

    @Test
    void ofStartsRunningWithStartedAt() {
        TaskRun run = TaskRun.of(7L, TaskRunType.TRANSLATE, "zh-Hans");

        assertEquals(TaskRunStatus.RUNNING, run.getStatus());
        assertEquals(7L, run.getTaskId());
        assertEquals(TaskRunType.TRANSLATE, run.getRunType());
        assertEquals("zh-Hans", run.getTargetLanguage());
        assertNotNull(run.getStartedAt(), "of 应落 startedAt");
        assertEquals(run.getStartedAt(), run.getCreatedAt());
        assertNull(run.getId(), "未落库前 id 应为 null");
        assertNull(run.getRunNo(), "run_no 由数据库 MAX+1 生成，落库前应为 null");
        assertNull(run.getFinishedAt(), "未到终态前 finishedAt 应为 null");
        assertNull(run.getCurrentStage());
        assertNull(run.getErrorMessage());
    }

    @Test
    void ofCarriesNullTargetLanguageForNonTranslateRuns() {
        TaskRun run = TaskRun.of(7L, TaskRunType.MAIN, null);

        assertNull(run.getTargetLanguage(), "只有 TRANSLATE 有目标语种");
    }

    @Test
    void restoreRebuildsAnyStatusIncludingInterrupted() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 17, 10, 0);
        TaskRun run = TaskRun.restore(9L, 7L, 3, TaskRunType.MAIN, null,
                TaskRunStatus.INTERRUPTED,
                "transcribe-audio", "上次被中断了", created, created.plusMinutes(5), created);

        assertEquals(9L, run.getId());
        assertEquals(3, run.getRunNo());
        assertEquals(TaskRunStatus.INTERRUPTED, run.getStatus(),
                "restore 接受任何状态——INTERRUPTED 的唯一写入者是启动收口，实体没有 interrupt()");
        assertEquals("transcribe-audio", run.getCurrentStage());
        assertEquals("上次被中断了", run.getErrorMessage());
        assertEquals(created.plusMinutes(5), run.getFinishedAt());
    }

    // ---------- 合法迁移 ----------

    @Test
    void succeedTransitionsRunningToSucceeded() {
        TaskRun run = running();

        run.succeed();

        assertEquals(TaskRunStatus.SUCCEEDED, run.getStatus());
        assertNotNull(run.getFinishedAt(), "succeed 应落 finishedAt");
    }

    @Test
    void failTransitionsRunningToFailedWithMessage() {
        TaskRun run = running();

        run.fail("转写失败：请检查 DASHSCOPE_API_KEY");

        assertEquals(TaskRunStatus.FAILED, run.getStatus());
        assertEquals("转写失败：请检查 DASHSCOPE_API_KEY", run.getErrorMessage());
        assertNotNull(run.getFinishedAt());
    }

    @Test
    void updateStageRecordsCurrentStageWhileRunning() {
        TaskRun run = running();

        run.updateStage("clean-subtitles");

        assertEquals("clean-subtitles", run.getCurrentStage());
        assertEquals(TaskRunStatus.RUNNING, run.getStatus());
    }

    // ---------- 非法迁移拒绝 ----------

    @Test
    void throwsWhenSucceedingFromNonRunningStates() {
        TaskRun succeeded = succeeded();
        TaskRun failed = failed();
        TaskRun interrupted = restoreWithStatus(TaskRunStatus.INTERRUPTED);

        assertThrows(IllegalStateException.class, succeeded::succeed);
        assertThrows(IllegalStateException.class, failed::succeed);
        assertThrows(IllegalStateException.class, interrupted::succeed);
        assertEquals(TaskRunStatus.SUCCEEDED, succeeded.getStatus(), "被拒绝的迁移不应改动状态");
    }

    @Test
    void throwsWhenFailingFromNonRunningStates() {
        TaskRun succeeded = succeeded();
        TaskRun interrupted = restoreWithStatus(TaskRunStatus.INTERRUPTED);

        assertThrows(IllegalStateException.class, () -> succeeded.fail("x"));
        assertThrows(IllegalStateException.class, () -> interrupted.fail("x"));
        assertNull(succeeded.getErrorMessage(), "被拒绝的失败不应留下脏状态");
    }

    @Test
    void throwsWhenUpdatingStageFromNonRunningStates() {
        TaskRun succeeded = succeeded();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> succeeded.updateStage("x"));

        assertTrue(e.getMessage().contains("非法状态迁移"), e.getMessage());
        assertNull(succeeded.getCurrentStage(), "被拒绝的阶段推进不应留下脏状态");
    }

    // ---------- 工厂 ----------

    private static TaskRun running() {
        return TaskRun.of(7L, TaskRunType.MAIN, null);
    }

    private static TaskRun succeeded() {
        TaskRun run = running();
        run.succeed();
        return run;
    }

    private static TaskRun failed() {
        TaskRun run = running();
        run.fail("x");
        return run;
    }

    private static TaskRun restoreWithStatus(TaskRunStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return TaskRun.restore(1L, 7L, 1, TaskRunType.MAIN, null, status, null, null, now, null, now);
    }
}
