package com.vidsage.domain.task.policy;

import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.model.TaskStatus;
import org.springframework.util.StringUtils;

/**
 * 任务状态 → 阶段事件的渲染政策（纯函数领域政策，无 I/O、无状态）：把持久化的任务状态摊成
 * 一条粗粒度 {@link StageProgress}，供 SSE 快照与管线终态广播共用同一份形状。
 *
 * <p>进度取状态的大致位置（排队 / 执行中 0、成功 100、失败 0），不追求精确——精确进度由增量
 * 事件携带，快照只保证形状合法。失败事件的消息带终态错误文本，是 SSE 上唯一的错误出口；
 * {@code current_stage} 尚空时阶段名允许为 {@code null}，形状仍合法。
 */
public final class TaskStageEvent {

    private TaskStageEvent() {
    }

    /**
     * 由持久化状态构造阶段事件：主状态渲染；SUCCEEDED 且翻译子状态存在时转派
     *
     * <p><b>登记表查询在调用方</b>：后置会话在跑时，快照要渲染成该阶段的 RUNNING 事件，
     * 而「有没有在途会话」是 {@code TaskSessionRegistry} 里的事实——查完把事实当入参传进来，
     * 本政策保持纯函数。没人跑会话就传 {@code null}。
     *
     * @param task         任务实例；{@code current_stage} 可为 {@code null}
     * @param runningStage 在途后置会话的阶段名；{@code null} = 没有在途会话
     * @return 粗粒度阶段事件；{@code runningStage} 为 {@code null} 且 {@code current_stage} 为空
     * 且状态非 FAILED 时为 {@code null}
     */
    public static StageProgress from(VideoTask task, String runningStage) {
        if (runningStage != null) {
            // 后置会话在跑：非终态、进度 0（精确进度由增量事件携带，快照只保证形状合法）
            return new StageProgress(runningStage, 0, "任务执行中", StageProgress.State.RUNNING);
        }
        String stage = task.getCurrentStage();
        return switch (task.getStatus()) {
            case PENDING -> stage == null ? null
                    : new StageProgress(stage, 0, "任务排队中，等待执行", StageProgress.State.RUNNING);
            case RUNNING -> stage == null ? null
                    : new StageProgress(stage, 0, "任务执行中", StageProgress.State.RUNNING);
            case SUCCEEDED -> stage == null ? null
                    : new StageProgress(stage, 100, "任务已完成", StageProgress.State.SUCCEEDED);
            case FAILED -> new StageProgress(stage, 0,
                    StringUtils.hasText(task.getErrorMessage()) ? task.getErrorMessage() : "任务已失败",
                    StageProgress.State.FAILED);
        };
    }

}
