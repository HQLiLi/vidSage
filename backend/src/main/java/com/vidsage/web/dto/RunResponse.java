package com.vidsage.web.dto;

import com.vidsage.application.task.query.RunSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 任务视图里的最近一次执行（{@link TaskResponse#lastRun()} 的形状）。
 *
 * <p>主状态、当前阶段、失败原因、起止时间都收在这里，任务视图上不再压平一遍。
 *
 * @param runNo          任务内递增序号
 * @param runType        执行类型：{@code MAIN} / {@code RECLEAN} / {@code TRANSLATE}
 * @param targetLanguage 目标语种；只有 TRANSLATE 有值
 * @param status         执行状态：{@code RUNNING} / {@code SUCCEEDED} / {@code FAILED} / {@code INTERRUPTED}
 * @param currentStage   当前阶段名；尚未进入第一个阶段时为 {@code null}
 * @param errorMessage   失败原因（中文，面向用户）；无失败时为 {@code null}
 * @param startedAt      开始执行时间
 * @param finishedAt     终态时间；未到终态时为 {@code null}
 */
@Schema(description = "最近一次执行")
public record RunResponse(
        @Schema(description = "任务内递增序号，从 1 起", example = "3") int runNo,
        @Schema(description = "执行类型：MAIN（主管线）/ RECLEAN（重新生成字幕）/ TRANSLATE（翻译）")
        String runType,
        @Schema(description = "目标语种；只有 TRANSLATE 有值") String targetLanguage,
        @Schema(description = "执行状态：RUNNING（执行中）/ SUCCEEDED（成功）/ FAILED（失败）/ INTERRUPTED（上次被中断）")
        String status,
        @Schema(description = "当前阶段名；尚未进入第一个阶段时为 null") String currentStage,
        @Schema(description = "失败原因（中文）；无失败时为 null") String errorMessage,
        @Schema(description = "开始执行时间") LocalDateTime startedAt,
        @Schema(description = "终态时间；未到终态时为 null") LocalDateTime finishedAt) {

    /**
     * 应用层读模型 → 对外响应的纯映射（无业务逻辑）：字段一一对应，枚举取名字符串。
     *
     * @param summary 应用层读模型
     * @return 对外响应
     */
    public static RunResponse from(RunSummary summary) {
        return new RunResponse(summary.runNo(), summary.runType().name(), summary.targetLanguage(),
                summary.status().name(), summary.currentStage(), summary.errorMessage(),
                summary.startedAt(), summary.finishedAt());
    }
}
