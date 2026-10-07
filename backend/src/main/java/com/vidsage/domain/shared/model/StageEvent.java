package com.vidsage.domain.shared.model;

/**
 * 阶段进度事件。刻意保持最小（阶段名 + 进度 + 消息 + 状态），
 * 形状参考 oss-references 第 2 条的 {@code {progress, stage, message, state}}。
 *
 * <p><b>这是过渡形态</b>：真实形状应由 MVP-1 的 SSE payload 定锤，届时以彼为准。
 *
 * <p><b>阶段词表</b>：LLM worker 原本各自持有阶段名常量（{@code SubtitleTranslator.STAGE_TRANSLATE}、
 * {@code SubtitleCleanser.STAGE_CLEAN}），application 层为取常量被迫 import infrastructure 类；
 * 2026-10-06 随逻辑下沉归位本类（domain/shared 是进度词汇的家），infrastructure 侧改引这里，
 * 词表不再住实现类里。
 *
 * @param stage    阶段名，如 "acquire" / "extract-audio" / {@link #STAGE_TRANSLATE}
 * @param progress 阶段内进度百分比，取值 0–100
 * @param message  面向用户的中文描述
 * @param state    阶段状态
 */
public record StageEvent(String stage, int progress, String message, StageState state) {

    /** 翻译阶段名（{@code SubtitleTranslator} 的批级事件与翻译编排共用）。 */
    public static final String STAGE_TRANSLATE = "translate";

    /** 清洗阶段名（{@code SubtitleCleanser} 的批级事件与清洗编排共用）。 */
    public static final String STAGE_CLEAN = "clean";

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
