/**
 * 任务上下文：任务生命周期与任务词汇的家。
 *
 * <p>根只留主状态机 {@code VideoTask}——它承载合法迁移规则，也带两处查询谓词
 * {@code isEventTerminal}（终态判定，web 收口引用）与 {@code ACTIVE_STATUSES}（active 判据，
 * repository 查重引用）。其余构件按角色分桶：
 *
 * <p>{@code model/} 装数据词汇——状态枚举 {@code TaskStatus} / {@code TaskInputType} /
 * {@code TranslationStatus} 与任务号值对象 {@code TaskNo}；{@code policy/} 装无状态政策
 * 2026-10-06 业务逻辑下沉 Phase C 落地）；{@code port/} 装端口接口 {@code VideoTaskRepository}
 * （2026-10-06 端口倒置：原为 infrastructure 的具体类，现改为定义在此、由
 * {@code infrastructure/persistence/repository/MybatisVideoTaskRepository} 实现）。
 */
package com.vidsage.domain.task;
