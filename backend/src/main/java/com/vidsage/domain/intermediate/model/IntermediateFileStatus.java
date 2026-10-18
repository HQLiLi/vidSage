package com.vidsage.domain.intermediate.model;

/**
 * 中间产物状态：只有三个值。纯缓存没有「登记了但文件还没写」的窗口，故没有 {@code CREATING}；
 * {@code FAILED} 对它是零路径的死值，也不留。合法迁移的权威定义在 {@code IntermediateFile} 状态机。
 * 取值与数据库 {@code intermediate_file.status} 列一一对应。
 */
public enum IntermediateFileStatus {

    /** 文件在、是有效缓存（也是唯一允许「复活」的值）。 */
    READY,

    /** 已作废，文件待删。 */
    SUPERSEDED,

    /** 文件已确认回收。 */
    DELETED
}
