package com.vidsage.application.task.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskProgressMessage} 的纯单元测试：钉住终态判据的**出处**，不是判据本身。
 *
 * <p>判据有两截（2026-10-12 起）：主状态与翻译子状态的收敛判定在
 * {@code VideoTask.isEventTerminal}（真值表由 {@code VideoTaskTest} 钉住），
 * 「有没有在途后置会话」隐式编在 stage 的状态位里（RUNNING 态 = 在跑）。
 * 给什么状态都由本类的紧凑构造器重算，构造方传进来的实参一律被覆盖——
 * 这是「即便用错也不会错」的形状，前端据此不再自己拼状态组合。
 */
class TaskProgressMessageTest {

    private static final String TASK_NO = "t0";

    private static final StageProgress STAGE = new StageProgress(
            SubtitleStageName.TRANSCRIBE_AUDIO.getStageName(), 70, "开始转写", StageProgress.State.RUNNING);

    /** 终态语义的 stage：SUCCEEDED 态（终态广播挑的就是它）。 */
    private static final StageProgress DONE_STAGE = new StageProgress(
            SubtitleStageName.CLEAN_SUBTITLES.getStageName(), 100, "清洗完成", StageProgress.State.SUCCEEDED);

    @Test
    void terminalIsTrueForTerminalStatePairs() {
        assertTrue(TaskProgressMessage.of(TASK_NO, TaskStatus.SUCCEEDED, DONE_STAGE, null).terminal(),
                "主任务成功 + 翻译从未触发 → 终态");
        assertTrue(TaskProgressMessage.of(TASK_NO, TaskStatus.SUCCEEDED, DONE_STAGE, TranslationStatus.SUCCEEDED)
                .terminal(), "翻译已完成 → 终态");
        assertTrue(TaskProgressMessage.of(TASK_NO, TaskStatus.FAILED, DONE_STAGE, null).terminal(),
                "主任务失败 → 终态");
        assertTrue(TaskProgressMessage.of(TASK_NO, TaskStatus.SUCCEEDED, null, null).terminal(),
                "主状态终态但无阶段细节（终态广播可无 stage）→ 终态");
    }

    @Test
    void runningStageBeatsTerminalMainStateWhilePostSessionRuns() {
        // 后置会话（重新生成字幕）跑起来时主状态已 SUCCEEDED，但阶段事件是 RUNNING 态——
        // 此时绝不能发 terminal，否则重连的人一连上就被收口，后半程进度全收不到（AC12 的地基）
        assertFalse(TaskProgressMessage.of(TASK_NO, TaskStatus.SUCCEEDED, STAGE, null).terminal(),
                "主状态终态但 stage 在跑 → 有在途后置会话，未收敛");
    }

    @Test
    void terminalIsFalseWhileTheStreamStillHasEvents() {
        assertFalse(TaskProgressMessage.of(TASK_NO, TaskStatus.SUCCEEDED, STAGE, TranslationStatus.RUNNING)
                .terminal(), "翻译进行中 → 未收敛（只看主状态会把翻译期间的连接提前收掉）");
        assertFalse(TaskProgressMessage.of(TASK_NO, TaskStatus.PENDING, STAGE, null).terminal(),
                "排队中 → 未收敛");
        assertFalse(TaskProgressMessage.of(TASK_NO, TaskStatus.RUNNING, STAGE, null).terminal(),
                "执行中 → 未收敛");
    }

    @Test
    void canonicalConstructorOverwritesCallerSuppliedTerminal() {
        // 5 参规范构造器仍公开（record 规范构造器的可见性不得低于类型自身，收不成私有），
        // 所以「传错值也写不进去」只能靠紧凑构造器无条件重算来保证，不靠调用方自觉
        assertTrue(new TaskProgressMessage(TASK_NO, TaskStatus.SUCCEEDED, DONE_STAGE, null, false).terminal(),
                "传 false，但状态已收敛 → 应被重算为 true");
        assertFalse(new TaskProgressMessage(TASK_NO, TaskStatus.RUNNING, STAGE, null, true).terminal(),
                "传 true，但事件流仍在跑 → 应被重算为 false");
    }
}
