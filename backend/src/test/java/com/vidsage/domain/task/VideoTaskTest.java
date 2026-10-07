package com.vidsage.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link VideoTask} 状态机的纯单元测试：不起容器。
 *
 * <p>覆盖「主状态机合法迁移三条」「非法迁移拒绝」（重点钉住 SUCCEEDED → RUNNING 这类终态再迁移）
 * 「阶段名推进的时机约束」「翻译子状态迁移（2026-10-02 两段式：仅主状态 SUCCEEDED 后流转）」
 * 「newPending / restore 的出生状态」「查询谓词（isEventSettled 终态判定 / ACTIVE_STATUSES active
 * 判据，2026-10-06 单源化）」。
 */
class VideoTaskTest {

    // ---------- 出生状态 ----------

    @Test
    void newPendingStartsAsPendingWithGeneratedTaskNo() {
        VideoTask task = VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir-a1b2c3d4e5f6");

        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertTrue(task.getTaskNo().value().matches("[0-9a-f]{32}"),
                () -> "taskNo 应为 32 位十六进制：" + task.getTaskNo().value());
        assertNull(task.getId(), "未落库前 id 应为 null");
        assertNotNull(task.getCreatedAt());
        assertEquals(task.getCreatedAt(), task.getUpdatedAt());
        assertNull(task.getStartedAt(), "未开始前 startedAt 应为 null");
        assertNull(task.getFinishedAt(), "未到终态前 finishedAt 应为 null");
        assertNull(task.getCurrentStage());
        assertNull(task.getErrorMessage());
        assertNull(task.getTranslationStatus(), "新建任务从未触发翻译，翻译子状态应为 null");
    }

    @Test
    void restoreRebuildsPersistedFieldsVerbatim() {
        LocalDateTime created = LocalDateTime.of(2026, 9, 30, 10, 0);
        VideoTask task = VideoTask.restore(7L, new TaskNo("abc123"), TaskInputType.UPLOAD, "demo.mp4",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir", TaskStatus.FAILED, SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), TranslationStatus.RUNNING,
                "翻译失败", created,
                created.plusMinutes(5), created.plusSeconds(1), created.plusMinutes(5));

        assertEquals(7L, task.getId());
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), task.getCurrentStage());
        assertEquals(TranslationStatus.RUNNING, task.getTranslationStatus(), "翻译子状态应随行还原");
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

        task.updateStage(SubtitleStageName.EXTRACT_AUDIO.getStageName());

        assertEquals(SubtitleStageName.EXTRACT_AUDIO.getStageName(), task.getCurrentStage());
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

        assertThrows(IllegalStateException.class, () -> pending.updateStage(SubtitleStageName.CLEAN_SUBTITLES.getStageName()));
        assertThrows(IllegalStateException.class, () -> succeeded.updateStage(SubtitleStageName.CLEAN_SUBTITLES.getStageName()));
        assertNull(pending.getCurrentStage(), "被拒绝的阶段推进不应留下脏状态");
    }

    // ---------- 翻译子状态迁移（2026-10-02 两段式） ----------

    @Test
    void translationStartRequiresSucceededMainState() {
        VideoTask pending = newPending();
        VideoTask running = running();

        assertThrows(IllegalStateException.class, pending::translationStart,
                "主任务 PENDING 不得启动翻译");
        assertThrows(IllegalStateException.class, running::translationStart,
                "主任务 RUNNING 不得启动翻译");
        assertNull(pending.getTranslationStatus(), "被拒绝的启动不应留下脏状态");
        assertNull(running.getTranslationStatus(), "被拒绝的启动不应留下脏状态");
    }

    @Test
    void translationStartFromNeverTriggeredAndFailedStates() {
        VideoTask fresh = succeeded();
        fresh.translationStart();
        assertEquals(TranslationStatus.RUNNING, fresh.getTranslationStatus(),
                "从未触发（null）→ RUNNING 应允许");
        assertEquals(TaskStatus.SUCCEEDED, fresh.getStatus(), "主状态不受翻译子状态影响");

        VideoTask retried = succeededWithFailedTranslation();
        retried.translationStart();
        assertEquals(TranslationStatus.RUNNING, retried.getTranslationStatus(),
                "失败（FAILED）→ RUNNING（重试）应允许");
    }

    @Test
    void rejectsTranslationStartWhileTranslationRunning() {
        VideoTask task = succeeded();
        task.translationStart();

        IllegalStateException e = assertThrows(IllegalStateException.class, task::translationStart,
                "翻译进行中重复触发必须被拒绝");

        assertTrue(e.getMessage().contains("非法状态迁移"), e.getMessage());
        assertEquals(TranslationStatus.RUNNING, task.getTranslationStatus(), "重复触发不改变状态");
    }

    @Test
    void translationSucceedFromRunningAndIdempotentConvergence() {
        VideoTask normal = succeeded();
        normal.translationStart();
        normal.translationSucceed();
        assertEquals(TranslationStatus.SUCCEEDED, normal.getTranslationStatus());

        // 幂等收敛：触发时发现翻译产物已在（存量任务 translationStatus 为 null / FAILED），
        // 不经 RUNNING 直接收敛
        VideoTask legacy = succeeded();
        legacy.translationSucceed();
        assertEquals(TranslationStatus.SUCCEEDED, legacy.getTranslationStatus(),
                "从未触发 + 检查点已在 → 直接幂等收敛为 SUCCEEDED");

        VideoTask failed = succeededWithFailedTranslation();
        failed.translationSucceed();
        assertEquals(TranslationStatus.SUCCEEDED, failed.getTranslationStatus(),
                "失败 + 检查点已在 → 直接幂等收敛为 SUCCEEDED");
    }

    @Test
    void rejectsTranslationSucceedWhenAlreadySucceededOrMainNotSucceeded() {
        VideoTask done = succeeded();
        done.translationStart();
        done.translationSucceed();
        assertThrows(IllegalStateException.class, done::translationSucceed,
                "翻译已 SUCCEEDED 不得重复收敛");
        assertEquals(TranslationStatus.SUCCEEDED, done.getTranslationStatus());

        VideoTask pending = newPending();
        assertThrows(IllegalStateException.class, pending::translationSucceed,
                "主任务非 SUCCEEDED 不得收敛翻译");
    }

    @Test
    void translationFailRecordsMessageAndKeepsMainState() {
        VideoTask task = succeeded();
        task.translationStart();

        task.translationFail("翻译失败：DEEPSEEK_API_KEY 无效");

        assertEquals(TranslationStatus.FAILED, task.getTranslationStatus());
        assertEquals("翻译失败：DEEPSEEK_API_KEY 无效", task.getErrorMessage(),
                "翻译失败信息复用 error_message（带前缀）");
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(),
                "翻译失败不得回退主状态——主状态机与翻译子状态机相互独立");
    }

    @Test
    void rejectsTranslationFailWhenNotRunningOrMainNotSucceeded() {
        VideoTask notStarted = succeeded();
        assertThrows(IllegalStateException.class, () -> notStarted.translationFail("x"),
                "翻译未 RUNNING 不得标记失败");

        VideoTask pending = newPending();
        assertThrows(IllegalStateException.class, () -> pending.translationFail("x"),
                "主任务非 SUCCEEDED 不得标记翻译失败");

        VideoTask alreadyFailed = succeededWithFailedTranslation();
        assertThrows(IllegalStateException.class, () -> alreadyFailed.translationFail("y"),
                "翻译已 FAILED 不得再次标记失败");
        assertEquals("第一次的失败原因", alreadyFailed.getErrorMessage(),
                "被拒绝的失败标记不应覆盖已有错误信息");
    }

    // ---------- 查询谓词（2026-10-06 终态 / active 判据单源化） ----------

    @Test
    void eventSettledOnlyWhenMainTerminalAndTranslationNotRunning() {
        // 主状态终态且翻译不在 RUNNING 才算收敛：SUCCEEDED / FAILED × {null, SUCCEEDED, FAILED}
        assertTrue(VideoTask.isEventSettled(TaskStatus.SUCCEEDED, null),
                "主任务成功 + 翻译从未触发 → 已收敛");
        assertTrue(VideoTask.isEventSettled(TaskStatus.SUCCEEDED, TranslationStatus.SUCCEEDED),
                "翻译已完成 → 已收敛");
        assertTrue(VideoTask.isEventSettled(TaskStatus.SUCCEEDED, TranslationStatus.FAILED),
                "翻译已失败 → 已收敛");
        assertTrue(VideoTask.isEventSettled(TaskStatus.FAILED, null), "主任务失败 → 已收敛");
        // 翻译进行中：事件流还有后续，连接不能收（原 web 两处重复判定的核心分支）
        assertFalse(VideoTask.isEventSettled(TaskStatus.SUCCEEDED, TranslationStatus.RUNNING),
                "翻译进行中 → 未收敛");
        // 主状态未终态：无论翻译子状态如何都未收敛
        assertFalse(VideoTask.isEventSettled(TaskStatus.PENDING, null), "排队中 → 未收敛");
        assertFalse(VideoTask.isEventSettled(TaskStatus.RUNNING, null), "执行中 → 未收敛");
        assertFalse(VideoTask.isEventSettled(TaskStatus.RUNNING, TranslationStatus.RUNNING),
                "执行中 + 翻译进行中 → 未收敛");
    }

    @Test
    void activeStatusesAreExactlyPendingAndRunning() {
        assertEquals(Set.of(TaskStatus.PENDING, TaskStatus.RUNNING), VideoTask.ACTIVE_STATUSES,
                "active 判据 = 排队 + 执行中；终态不算 active（重新提交同一输入走断点复用）");
    }

    // ---------- 工厂 ----------

    private static VideoTask newPending() {
        return VideoTask.newPending(TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir");
    }

    private static VideoTask running() {
        VideoTask task = newPending();
        task.start();
        return task;
    }

    /** 主任务已成功的任务（翻译从未触发，translationStatus 为 null）。 */
    private static VideoTask succeeded() {
        VideoTask task = running();
        task.succeed();
        return task;
    }

    /** 主任务成功且翻译已失败（带错误信息）的任务——翻译重试 / 幂等收敛用例的起点。 */
    private static VideoTask succeededWithFailedTranslation() {
        VideoTask task = succeeded();
        task.translationStart();
        task.translationFail("第一次的失败原因");
        return task;
    }
}
