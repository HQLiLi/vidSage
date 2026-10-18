package com.vidsage.domain.subtitle.model;

/**
 * 产物状态：一版产物的生命周期。合法迁移的权威定义在 {@code SubtitleArtifact} 状态机，此处不重复。
 * 取值与数据库 {@code subtitle_artifact.status} 列一一对应。{@code READY} 是唯一有效态（业务查询只认它）。
 */
public enum SubtitleArtifactStatus {

    /** 占位：行在、文件还没写完；该行永远全 NULL。 */
    CREATING,

    /** 当前有效版。 */
    READY,

    /** 不再是当前版（同 (task,type,lang) 换版，或被上游级联）。 */
    SUPERSEDED,

    /** 这一版没成（收口置位，或启动收口把残留 CREATING 落下来）。 */
    FAILED,

    /** 文件已确认回收。 */
    DELETED
}
