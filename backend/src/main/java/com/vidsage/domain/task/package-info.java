/**
 * 任务上下文：任务与执行轴的实体与词汇。
 *
 * <p>根装两个实体：{@code SubtitleTask}（字幕任务身份，一个媒体对应一个对外稳定的 task_no）与
 * {@code TaskRun}（一次执行，插入即抢互斥）。{@code model/} 装数据词汇——对外任务号值对象
 * {@code TaskNo}、一条 run 的对外引用 {@code RunRef}、执行类型 {@code TaskRunType} 与执行状态
 * {@code TaskRunStatus}；{@code port/} 装持久化端口 {@code SubtitleTaskRepository} 与
 * {@code TaskRunRepository}，由 persistence 层实现。
 */
package com.vidsage.domain.task;
