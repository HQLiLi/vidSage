package com.vidsage.domain.shared.model;

/**
 * 一条阶段进度。刻意保持最小（阶段名 + 进度 + 消息 + 状态），
 * 形状参考 oss-references 第 2 条的 {@code {progress, stage, message, state}}。
 *
 * <p><b>不是 SSE 专属</b>：上报它的是 media 与 subtitle 两条线（{@code AudioPreparationCoordinator}、
 * 字幕的三个 coordinator；跨境调 LLM 时由 {@code LlmSubtitleCleanser} / {@code LlmSubtitleTranslator}
 * 按批继续上报）。task 侧的 {@code SubtitlePipeline} 消费它并落 current_stage，SSE 只是链尾那一跳。
 * 住在 {@code domain/shared/} 是因为 {@code infrastructure/} 与 {@code application/} 都要依赖它，
 * 若挪进 {@code application/}，{@code infrastructure/} 引用它就构成反向依赖的分层倒置。
 *
 * @param stage    阶段名
 * @param progress 阶段内进度百分比，取值 0–100
 * @param message  面向用户的中文描述
 * @param state    阶段状态
 */
public record StageProgress(String stage, int progress, String message, State state) {

    /**
     * 阶段状态。
     */
    public enum State {

        /** 开始执行。 */
        RUNNING,

        /** 执行成功。 */
        SUCCEEDED,

        /** 产物已存在且非空，跳过该阶段（断点复用）。 */
        SKIPPED,

        /** 执行失败。 */
        FAILED
    }
}
