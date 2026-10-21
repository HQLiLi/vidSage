package com.vidsage.domain.subtitle.model.cleanup;

/**
 * 一次清洗的意图：首次清洗，还是用户点名要的重生成。
 *
 * <p>两个值各自带两件事——开始事件报什么文案、摘要降级能不能忍。这两件永远成对出现，
 * 拆开写只会是同一个信息的两种说法，所以收在一个枚举里。
 *
 * <p>它同时是「我是谁」的答案：清洗只有这两个入口，调用方挑的入口就是身份，
 * 实现不必再拿一个布尔去反推。
 */
public enum CleanMode {

    /** 首次清洗：摘要降级也要出字幕，宁可没上下文——这条链不能因为摘要失败就交不出东西。 */
    FIRST_CLEAN("开始清洗", false),

    /**
     * 按当前关键词重新生成：摘要降级即整体失败。
     *
     * <p>用户已明确要求「用这批关键词重生成」，拿空上下文出一份没纠错的字幕等于没执行他的要求，
     * 而且他看不出来。
     */
    RECLEAN("开始重新生成字幕", true);

    private final String startMessage;

    private final boolean requireContextSummary;

    CleanMode(String startMessage, boolean requireContextSummary) {
        this.startMessage = startMessage;
        this.requireContextSummary = requireContextSummary;
    }

    /**
     * 开始事件上报的文案。
     */
    public String startMessage() {
        return startMessage;
    }

    /**
     * 摘要降级时要不要整体失败：{@code true} = 必须有一份真摘要才继续。
     */
    public boolean requireContextSummary() {
        return requireContextSummary;
    }
}
