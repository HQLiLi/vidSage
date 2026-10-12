package com.vidsage.domain.task.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务生命周期状态。合法迁移的权威定义在 {@code VideoTask} 状态机，此处不重复。
 * SUCCEEDED / FAILED 是终态，不可再迁移。取值与数据库 {@code task.status} 的 ENUM 一一对应。
 */
@Schema(description = "任务状态：PENDING（排队中）/ RUNNING（执行中）/ SUCCEEDED（成功）/ FAILED（失败）")
public enum TaskStatus {

    /** 已落库、排队等待执行（超出 vidsage.task.max-concurrent 的任务在此排队）。 */
    PENDING,

    /** 管线执行中。 */
    RUNNING,

    /** 全部阶段完成；产物已落工作区。 */
    SUCCEEDED,

    /** 执行失败；错误详情见 error_message（中文）。 */
    FAILED
}
