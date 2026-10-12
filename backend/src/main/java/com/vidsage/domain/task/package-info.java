/**
 * 任务上下文：任务生命周期与任务词汇的家。
 *
 * <p>根只留主状态机 {@code VideoTask}——它承载合法迁移规则，也带两处查询谓词
 * {@code isEventTerminal}（终态判定，web 收口引用）与 {@code ACTIVE_STATUSES}（active 判据，
 * repository 查重引用）。
 *
 * <p>{@code model/} 装数据词汇——状态枚举 {@code TaskStatus} / {@code TaskInputType} /
 * {@code TranslationStatus} 与任务号值对象 {@code TaskNo}；{@code policy/} 装无状态政策
 * {@code TaskStageEvent}（任务状态 → 阶段事件的渲染）；{@code port/} 装端口接口
 * {@code VideoTaskRepository}，由 {@code infrastructure/persistence/repository/} 的
 * {@code MybatisVideoTaskRepository} 实现。
 */
package com.vidsage.domain.task;
