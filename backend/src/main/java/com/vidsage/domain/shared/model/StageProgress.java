package com.vidsage.domain.shared.model;

/**
 * 一条阶段进度：阶段名、阶段内进度、面向用户的描述、状态。
 *
 * @param stage    阶段名
 * @param progress <b>阶段内</b>进度百分比，取值 0–100（不是任务级进度，任务级区间见 {@code SubtitleStageName}）
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
