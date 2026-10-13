package com.vidsage.domain.subtitle.model.common;

import lombok.Getter;

import java.util.EnumMap;
import java.util.Map;

/**
 * 从媒体源生成字幕管线的所有阶段。
 *
 * <p><b>枚举声明顺序就是进度区间的累加顺序，不要随意调换。</b>
 */
@Getter
public enum SubtitleStageName {

    ACQUIRE_MEDIA("acquire-media", 42),
    EXTRACT_AUDIO("extract-audio", 42),
    TRANSCRIBE_AUDIO("transcribe-audio", 49),
    WRITE_SUBTITLES("write-subtitles", 7),
    CLEAN_SUBTITLES("clean-subtitles", 60),
    TRANSLATE_SUBTITLES("translate-subtitles", 0, false);

    private final String stageName;

    private final int weight;

    /**
     * 是否处于主管线上。
     */
    private final boolean inMainPipeline;

    /**
     * 主管线各阶段的任务级区间 [起点, 终点)，类加载时按声明顺序累加算出。
     * 翻译不在表里，调 start()/end() 会抛。
     */
    private static final Map<SubtitleStageName, int[]> RANGES = new EnumMap<>(SubtitleStageName.class);

    static {
        int total = 0;
        for (SubtitleStageName stage : values()) {
            if (stage.inMainPipeline) {
                total += stage.weight;
            }
        }
        int done = 0;
        for (SubtitleStageName stage : values()) {
            if (stage.inMainPipeline) {
                int start = done * 100 / total;
                done += stage.weight;
                RANGES.put(stage, new int[]{start, done * 100 / total});
            }
        }
    }

    SubtitleStageName(String stageName, int weight) {
        this(stageName, weight, true);
    }

    SubtitleStageName(String stageName, int weight, boolean inMainPipeline) {
        this.stageName = stageName;
        this.weight = weight;
        this.inMainPipeline = inMainPipeline;
    }

    /**
     * 取本阶段的任务级区间。
     *
     * @throws IllegalStateException 本阶段不在区间表里（翻译）
     */
    private int[] getRange() {
        int[] range = RANGES.get(this);
        if (range == null) {
            throw new IllegalStateException(stageName + " 不在主管线权重表里，没有任务级区间");
        }
        return range;
    }


    /**
     * 本阶段在任务级进度里的起点
     */
    public int start() {
        return getRange()[0];
    }

    /**
     * 本阶段在任务级进度里的终点
     */
    public int end() {
        return getRange()[1];
    }

}
