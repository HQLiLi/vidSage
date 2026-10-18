package com.vidsage.domain.subtitle.model;

/**
 * 中间产物类型：task 级缓存里的哪一类文件。取值与数据库 {@code intermediate_file.file_type} 列一一对应。
 * ASR 结果直接写成 {@code original-segments.json}，中间没有落盘环节，所以这里没有 ASR 原始输出这一类。
 */
public enum IntermediateFileType {

    /** 媒体源文件（{@code source.<ext>}）。 */
    SOURCE_MEDIA,

    /** yt-dlp 的元数据（{@code source.info.json}）。 */
    INFO_JSON,

    /** 抽出的音频（{@code audio-<规格>-t<n>.<ext>}）。 */
    AUDIO
}
