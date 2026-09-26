package com.vidsage.model.progress;

/**
 * 阶段进度事件。刻意保持最小（阶段名 + 进度 + 消息 + 状态），
 * 形状参考 oss-references 第 2 条的 {@code {progress, stage, message, state}}。
 *
 * <p><b>这是过渡形态</b>：真实形状应由 MVP-1 的 SSE payload 定锤，届时以彼为准。
 *
 * @param stage    阶段名，如 "acquire" / "extract-audio"
 * @param progress 阶段内进度百分比，取值 0–100
 * @param message  面向用户的中文描述
 * @param state    阶段状态
 */
public record StageEvent(String stage, int progress, String message, StageState state) {

    /**
     * 阶段状态。
     */
    public enum StageState {

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
