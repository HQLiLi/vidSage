package com.vidsage.domain.subtitle.model.common;

import lombok.Getter;

/***
 * 从媒体源生成字幕管线的所有阶段
 */
@Getter
public enum SubtitleStageName {

    /**
     * 抓取媒体源
     */
    ACQUIRE_MEDIA("acquire-media"),
    /**
     * 从媒体源中提取音频
     */
    EXTRACT_AUDIO("extract-audio"),
    /**
     * 调 ASR，把音轨转成文本
     */
    TRANSCRIBE_AUDIO("transcribe-audio"),
    /**
     * 通过音轨转写的文本生成字幕
     */
    WRITE_SUBTITLES("write-subtitles"),
    /**
     * 清洗音频生成的字幕
     */
    CLEAN_SUBTITLES("clean-subtitles"),
    /**
     * 翻译字幕
     */
    TRANSLATE_SUBTITLES("translate-subtitles");

    private final String stageName;

    SubtitleStageName(String stageName) {
        this.stageName = stageName;
    }

}
