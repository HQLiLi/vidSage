package com.vidsage.application.task.query;

import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import java.time.LocalDateTime;

/**
 * 最近一次执行的读模型：任务视图里的 {@code lastRun} 就是它（零 web 注解）。
 *
 * <p>主状态、失败原因、当前阶段都从这里取——同一份事实只留一个来源，不在任务视图上再压平一遍。
 *
 * @param runNo          任务内递增序号
 * @param runType        执行类型（MAIN / RECLEAN / TRANSLATE）
 * @param targetLanguage 目标语种；只有 TRANSLATE 有值
 * @param status         执行状态
 * @param currentStage   当前阶段名；尚未进入第一个阶段时为 {@code null}
 * @param errorMessage   失败原因（中文，面向用户）；无失败时为 {@code null}
 * @param startedAt      开始执行时间
 * @param finishedAt     终态时间；未到终态时为 {@code null}
 */
public record RunSummary(
        int runNo,
        TaskRunType runType,
        String targetLanguage,
        TaskRunStatus status,
        String currentStage,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    /**
     * 由领域对象摊成读模型。
     *
     * @param run 已落库的 run
     * @return 该 run 的读模型
     */
    public static RunSummary from(TaskRun run) {
        return new RunSummary(run.getRunNo(), run.getRunType(), run.getTargetLanguage(), run.getStatus(),
                run.getCurrentStage(), run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt());
    }
}
