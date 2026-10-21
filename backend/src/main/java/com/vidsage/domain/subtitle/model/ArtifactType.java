package com.vidsage.domain.subtitle.model;

/**
 * 产物类型：一行 artifact 属于哪一代逻辑版本。取值与数据库 {@code subtitle_artifact.artifact_type} 列一一对应。
 */
public enum ArtifactType {

    /** 原始转写产物（无语言维度）：segments + subtitles。 */
    ORIGINAL,

    /** 清洗后产物（无语言维度，带关键词指纹）：segments + subtitles + context。 */
    CLEANED,

    /** 翻译产物（带目标语种）：segments + subtitles + terms。 */
    TRANSLATED
}
