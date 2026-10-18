package com.vidsage.application.task.message;

import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.TaskRun;
import com.vidsage.domain.subtitle.model.TaskRunStatus;
import com.vidsage.domain.subtitle.model.TaskRunType;
import com.vidsage.domain.task.model.RunRef;
import org.springframework.util.StringUtils;

/**
 * SSE 进度事件：一条 run 一条流，事件描述这条 run 当前的状态与阶段。
 *
 * <p>三个变体按执行类型分：{@code MAIN} 与 {@code RECLEAN} 是两个不同的任务（流程恰好同形），
 * {@code TRANSLATE} 流程不重合、多带目标语种——这样 {@code targetLanguage} 只属于翻译，
 * 不再是「一个可空字段两种含义」。
 */
public sealed interface TaskRunProgress
        permits TaskRunProgress.Main, TaskRunProgress.Reclean, TaskRunProgress.Translate {

    /** 所属任务的对外号。 */
    String taskNo();

    /** 任务内递增序号。 */
    int runNo();

    /** 执行类型。 */
    TaskRunType runType();

    /** 这条 run 当前的状态。 */
    TaskRunStatus status();

    /** 最近阶段事件；run 还没进第一个阶段时为 {@code null}，形状仍合法。 */
    StageProgress stage();

    /** 这条 run 是否已结束（{@code status != RUNNING}）。 */
    default boolean terminal() {
        return status() != TaskRunStatus.RUNNING;
    }

    /**
     * 组装一条进度事件（唯一入口）：按执行类型分派变体，{@code stage} 为 {@code null} 时
     * 按 run 状态渲染一条粗粒度阶段（连接建立时的快照就走这条路）。对外任务号与 runNo 取自 {@code ref}，
     * 其余从 {@code run} 取。
     *
     * @param ref   这条 run 的对外引用（taskNo 与 runNo 的来源）
     * @param run   已落库的 run
     * @param stage 在途阶段事件；没有（如连接快照）传 {@code null}
     * @return 对应变体的进度事件
     */
    static TaskRunProgress of(RunRef ref, TaskRun run, StageProgress stage) {
        String taskNo = ref.taskNo().value();
        int runNo = ref.runNo();
        StageProgress effectiveStage = stage != null ? stage : renderStage(run);
        return switch (run.getRunType()) {
            case MAIN -> new Main(taskNo, runNo, TaskRunType.MAIN, run.getStatus(), effectiveStage);
            case RECLEAN -> new Reclean(taskNo, runNo, TaskRunType.RECLEAN, run.getStatus(), effectiveStage);
            case TRANSLATE -> new Translate(taskNo, runNo, TaskRunType.TRANSLATE, run.getStatus(), effectiveStage,
                    run.getTargetLanguage());
        };
    }

    /**
     * 把 run 的状态摊成一条粗粒度阶段：进度取状态的大致位置（执行中 0、成功 100、失败 0），
     * 不追求精确——精确进度由增量事件携带，这里只保证快照形状合法。失败事件的消息带终态错误文本，
     * 是 SSE 上唯一的错误出口；{@code current_stage} 尚空时阶段名允许为 {@code null}。
     *
     * @param run 已落库的 run
     * @return 粗粒度阶段；{@code RUNNING} / {@code SUCCEEDED} 且 {@code current_stage} 为空时为 {@code null}
     */
    static StageProgress renderStage(TaskRun run) {
        String stage = run.getCurrentStage();
        return switch (run.getStatus()) {
            case RUNNING -> stage == null ? null
                    : new StageProgress(stage, 0, "任务执行中", StageProgress.State.RUNNING);
            case SUCCEEDED -> stage == null ? null
                    : new StageProgress(stage, 100, "任务已完成", StageProgress.State.SUCCEEDED);
            case FAILED -> new StageProgress(stage, 0,
                    StringUtils.hasText(run.getErrorMessage()) ? run.getErrorMessage() : "任务已失败",
                    StageProgress.State.FAILED);
            case INTERRUPTED -> new StageProgress(stage, 0,
                    StringUtils.hasText(run.getErrorMessage()) ? run.getErrorMessage() : "上次被中断了，重新提交即可",
                    StageProgress.State.FAILED);
        };
    }

    /**
     * 主管线进度。
     *
     * @param taskNo 所属任务的对外号
     * @param runNo  任务内递增序号
     * @param runType 执行类型，恒为 {@code MAIN}
     * @param status 这条 run 的状态
     * @param stage  阶段事件；可为 {@code null}
     */
    record Main(String taskNo, int runNo, TaskRunType runType, TaskRunStatus status, StageProgress stage)
            implements TaskRunProgress {

        public Main {
            validateRunType(runType, TaskRunType.MAIN);
        }
    }

    /**
     * 重新生成字幕的进度。
     *
     * @param taskNo 所属任务的对外号
     * @param runNo  任务内递增序号
     * @param runType 执行类型，恒为 {@code RECLEAN}
     * @param status 这条 run 的状态
     * @param stage  阶段事件；可为 {@code null}
     */
    record Reclean(String taskNo, int runNo, TaskRunType runType, TaskRunStatus status, StageProgress stage)
            implements TaskRunProgress {

        public Reclean {
            validateRunType(runType, TaskRunType.RECLEAN);
        }
    }

    /**
     * 翻译的进度。
     *
     * @param taskNo         所属任务的对外号
     * @param runNo          任务内递增序号
     * @param runType        执行类型，恒为 {@code TRANSLATE}
     * @param status         这条 run 的状态
     * @param stage          阶段事件；可为 {@code null}
     * @param targetLanguage 目标语种
     */
    record Translate(String taskNo, int runNo, TaskRunType runType, TaskRunStatus status, StageProgress stage,
                     String targetLanguage) implements TaskRunProgress {

        public Translate {
            validateRunType(runType, TaskRunType.TRANSLATE);
        }
    }

    /**
     * 变体守卫：挡住「手构出 Main 却填 RECLEAN」。变体与执行类型必须一致。
     */
    private static void validateRunType(TaskRunType actual, TaskRunType expected) {
        if (actual != expected) {
            throw new IllegalStateException("变体与 runType 不符：本变体只承载 " + expected + "，收到 " + actual);
        }
    }
}
