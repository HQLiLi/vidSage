package com.vidsage.domain.task.policy;

import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.model.TaskStatus;
import org.springframework.util.StringUtils;

/**
 * 任务状态 → 阶段事件的渲染政策（纯函数领域政策，无 I/O、无状态）：把持久化的任务状态摊成
 * 一条粗粒度 {@link StageEvent}，供 SSE 快照与管线终态广播共用同一份形状。
 *
 * <p>进度取状态的大致位置（排队 / 执行中 0、成功 100、失败 0），不追求精确——精确进度由增量
 * 事件携带，快照只保证形状合法。失败事件的消息带终态错误文本，是 SSE 上唯一的错误出口；
 * {@code current_stage} 尚空时阶段名允许为 {@code null}，形状仍合法。
 *
 * <p><b>翻译子状态覆盖（2026-10-02 两段式）</b>：主任务 SUCCEEDED 且翻译子状态存在时，改由翻译
 * 子状态粗粒度还原（{@link #fromTranslation}）——翻译是主任务成功后的第二生命周期，重连订阅者
 * 最关心的是它。主管线终态广播发生时翻译子状态必为 null（翻译只能在主状态 SUCCEEDED 后触发），
 * 不受这条分支影响。
 *
 * <p>原是两个内联在 {@code VideoTaskService} 里的包内可见 static，2026-10-06 包结构整理时下沉
 * 至此：判据只吃 domain 类型、与 I/O 无关，住在任务领域更合适；下沉后是 public 政策，
 * {@code VideoTaskService}（快照）与 {@code VideoTaskPipeline}（两处终态广播）跨包共用同一份形状。
 */
public final class TaskStageEvent {

    private TaskStageEvent() {
    }

    /**
     * 由持久化状态构造阶段事件：主状态渲染；SUCCEEDED 且翻译子状态存在时转派
     * {@link #fromTranslation} 走翻译子状态。
     *
     * @param task 任务实例；{@code current_stage} 可为 {@code null}
     * @return 粗粒度阶段事件；{@code current_stage} 为空且状态非 FAILED 时为 {@code null}
     */
    public static StageEvent from(VideoTask task) {
        if (task.getStatus() == TaskStatus.SUCCEEDED && task.getTranslationStatus() != null) {
            return fromTranslation(task);
        }
        String stage = task.getCurrentStage();
        return switch (task.getStatus()) {
            case PENDING -> stage == null ? null
                    : new StageEvent(stage, 0, "任务排队中，等待执行", StageEvent.StageState.RUNNING);
            case RUNNING -> stage == null ? null
                    : new StageEvent(stage, 0, "任务执行中", StageEvent.StageState.RUNNING);
            case SUCCEEDED -> stage == null ? null
                    : new StageEvent(stage, 100, "任务已完成", StageEvent.StageState.SUCCEEDED);
            case FAILED -> new StageEvent(stage, 0,
                    StringUtils.hasText(task.getErrorMessage()) ? task.getErrorMessage() : "任务已失败",
                    StageEvent.StageState.FAILED);
        };
    }

    /**
     * 翻译子状态的粗粒度阶段事件（stage 固定 translate，进度取子状态粗粒度位置：进行中 0、
     * 完成 100、失败 0 + 失败信息）。与主状态渲染（{@link #from}）同哲学：精确进度由增量事件
     * 携带，快照 / 终态广播只保证形状合法。
     *
     * <p>前置条件：{@code task.getTranslationStatus() != null}（调用点已保证——主管线终态广播时
     * 翻译从未触发，走不到这里；由 {@link #from} 转派时也已判过）。
     *
     * @param task 翻译子状态非 {@code null} 的任务
     * @return 翻译阶段事件（stage 恒为 {@link StageEvent#STAGE_TRANSLATE}）
     */
    public static StageEvent fromTranslation(VideoTask task) {
        return switch (task.getTranslationStatus()) {
            case RUNNING -> new StageEvent(StageEvent.STAGE_TRANSLATE, 0, "翻译进行中",
                    StageEvent.StageState.RUNNING);
            case SUCCEEDED -> new StageEvent(StageEvent.STAGE_TRANSLATE, 100, "翻译完成",
                    StageEvent.StageState.SUCCEEDED);
            case FAILED -> new StageEvent(StageEvent.STAGE_TRANSLATE, 0,
                    StringUtils.hasText(task.getErrorMessage()) ? task.getErrorMessage() : "翻译已失败",
                    StageEvent.StageState.FAILED);
        };
    }
}
