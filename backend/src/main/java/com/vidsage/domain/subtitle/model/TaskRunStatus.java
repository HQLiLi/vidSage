package com.vidsage.domain.subtitle.model;

/**
 * 执行状态：一条 run 的生命周期。合法迁移的权威定义在 {@code TaskRun} 状态机，此处不重复。
 * 取值与数据库 {@code task_run.status} 列一一对应。<b>没有 PENDING</b>——插行即 RUNNING（插入即抢锁）。
 */
public enum TaskRunStatus {

    /** 正在跑；一个 task 同一时刻至多一条（{@code uk_active_run} 保证互斥）。 */
    RUNNING,

    /** 跑完了，产物齐了。 */
    SUCCEEDED,

    /** 跑了，<b>知道</b>它没成（收口无条件置位）。 */
    FAILED,

    /** <b>不知道</b>跑成没跑成（进程没了，启动收口补记）。 */
    INTERRUPTED
}
