package com.vidsage.model.task;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务生命周期状态。合法迁移只有三条，权威定义在 {@code domain/task/VideoTask} 状态机：
 * PENDING → RUNNING（开始执行）、RUNNING → SUCCEEDED（全部阶段完成）、RUNNING → FAILED（任一阶段失败）。
 * SUCCEEDED / FAILED 是终态，不可再迁移。本表与数据库 {@code task.status} 的 ENUM 值一一对应。
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
