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
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link VideoTask} 状态机的纯单元测试：不起容器。
 *
 * <p>覆盖「主状态机合法迁移三条」「非法迁移拒绝」（重点钉住 SUCCEEDED → RUNNING 这类终态再迁移）
 * 「阶段名推进的时机约束」「翻译 / 重新生成失败记录的落与清（error_message 按前缀各认各的）」
 * 「newPending / restore 的出生状态」「查询谓词（isEventTerminal 终态判定 / ACTIVE_STATUSES active 判据）」。
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
    }

    @Test
    void restoreRebuildsPersistedFieldsVerbatim() {
        LocalDateTime created = LocalDateTime.of(2026, 9, 30, 10, 0);
        VideoTask task = VideoTask.restore(7L, new TaskNo("abc123"), TaskInputType.UPLOAD, "demo.mp4",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir", TaskStatus.FAILED, SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(),
                "翻译失败", created,
                created.plusMinutes(5), created.plusSeconds(1), created.plusMinutes(5));

        assertEquals(7L, task.getId());
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), task.getCurrentStage());
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

    // ---------- 翻译失败记录 ----------
    // 翻译子状态已整条下线：不再有 RUNNING / SUCCEEDED / FAILED 迁移，
    // 「翻译在不在跑」改由内存登记表回答，DB 这侧只剩一条「最近一次失败」的文案。

    @Test
    void recordTranslationFailureWritesMessageAndKeepsMainSucceeded() {
        VideoTask task = succeeded();

        task.recordTranslationFailure("翻译失败：LLM 超时");

        assertEquals("翻译失败：LLM 超时", task.getErrorMessage(), "失败原因落 error_message");
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(),
                "翻译失败不回退主状态——字幕与已有译文一个字没少，这次没译成不是任务失败");
    }

    @Test
    void rejectsTranslationFailureWhenMainNotSucceeded() {
        VideoTask pending = newPending();
        VideoTask running = running();

        assertThrows(IllegalStateException.class, () -> pending.recordTranslationFailure("翻译失败：x"),
                "主任务 PENDING 不该有翻译失败可记（同步段已挡住，这里防漏）");
        assertThrows(IllegalStateException.class, () -> running.recordTranslationFailure("翻译失败：x"),
                "主任务 RUNNING 不该有翻译失败可记");
        assertNull(pending.getErrorMessage(), "被拒绝的记录不应留下脏状态");
    }

    @Test
    void clearTranslationFailureRemovesOnlyOwnPrefix() {
        VideoTask task = succeeded();
        task.recordTranslationFailure(VideoTask.TRANSLATION_FAILURE_PREFIX + "LLM 超时");

        task.clearTranslationFailure();

        assertNull(task.getErrorMessage(), "上一次翻译的失败原因应随成功一起清掉");
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void clearTranslationFailureKeepsForeignErrorMessages() {
        VideoTask regenerateFailed = succeeded();
        regenerateFailed.recordRegenerateFailure(VideoTask.REGENERATE_FAILURE_PREFIX + "字幕摘要失败");

        regenerateFailed.clearTranslationFailure();

        assertEquals(VideoTask.REGENERATE_FAILURE_PREFIX + "字幕摘要失败", regenerateFailed.getErrorMessage(),
                "「重新生成失败：」是另一台生命周期的记录，不归翻译清除");
        VideoTask untouched = succeeded();
        untouched.clearTranslationFailure();
        assertNull(untouched.getErrorMessage(), "没有失败记录时清除是无害空操作");
    }

    @Test
    void regenerateFailureRecordsMessageAndKeepsMainState() {
        VideoTask task = succeeded();

        task.recordRegenerateFailure("重新生成失败：字幕摘要失败，无法按当前关键词重新生成");

        assertEquals("重新生成失败：字幕摘要失败，无法按当前关键词重新生成", task.getErrorMessage(),
                "失败原因带前缀落 error_message（AC5）");
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(),
                "重生成失败不得动主状态——旧字幕一个字没少，任务本身还是成功的");
        assertNotNull(task.getUpdatedAt());
    }

    @Test
    void rejectsRegenerateFailureWhenMainNotSucceeded() {
        VideoTask pending = newPending();
        assertThrows(IllegalStateException.class,
                () -> pending.recordRegenerateFailure("x"),
                "主任务非 SUCCEEDED 不该有重生成失败可记（同步段已挡住，这里防漏）");
    }

    @Test
    void clearRegenerateFailureRemovesOnlyOwnPrefix() {
        VideoTask task = succeeded();
        task.recordRegenerateFailure(VideoTask.REGENERATE_FAILURE_PREFIX + "字幕摘要失败");

        task.clearRegenerateFailure();

        assertNull(task.getErrorMessage(), "上一次重生成的失败原因应随成功一起清掉");
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void clearRegenerateFailureKeepsForeignErrorMessages() {
        VideoTask translationFailed = succeeded();
        translationFailed.recordTranslationFailure(VideoTask.TRANSLATION_FAILURE_PREFIX + "第一次的失败原因");
        String kept = translationFailed.getErrorMessage();

        translationFailed.clearRegenerateFailure();

        assertEquals(kept, translationFailed.getErrorMessage(),
                "「翻译失败：」是另一台生命周期的记录，不归重生成清除");
        VideoTask untouched = succeeded();
        untouched.clearRegenerateFailure();
        assertNull(untouched.getErrorMessage(), "没有失败记录时清除是无害空操作");
    }



    // ---------- 查询谓词 ----------

    @Test
    void eventTerminalFollowsMainStatusOnly() {
        // 主状态是终态即收敛；「有没有在途后置会话」不在这里判——那是登记表的活，
        // 由进度消息的 stage 状态位补上第二截（见 TaskProgressMessage 的紧凑构造器）
        assertTrue(VideoTask.isEventTerminal(TaskStatus.SUCCEEDED), "主任务成功 → 已收敛");
        assertTrue(VideoTask.isEventTerminal(TaskStatus.FAILED), "主任务失败 → 已收敛");
        assertFalse(VideoTask.isEventTerminal(TaskStatus.PENDING), "排队中 → 未收敛");
        assertFalse(VideoTask.isEventTerminal(TaskStatus.RUNNING), "执行中 → 未收敛");
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

    /** 主任务已成功的任务。 */
    private static VideoTask succeeded() {
        VideoTask task = running();
        task.succeed();
        return task;
    }
}
