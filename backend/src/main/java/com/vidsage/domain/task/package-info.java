/**
 * 任务上下文：任务生命周期与任务词汇的家——{@code VideoTask}（主状态机 + 翻译子状态机的载体，
 * 兼承载终态谓词 {@code isEventSettled} 与 active 判据 {@code ACTIVE_STATUSES} 两处查询谓词，
 * 供 web 收口与 repository 查重引用）、状态枚举 {@code TaskStatus} / {@code TaskInputType} /
 * {@code TranslationStatus}、任务号值对象 {@code TaskNo}、产物可见性政策
 * {@code TaskArtifactScan}（白名单：{@code *.srt} 与 {@code segments-*.json} 对外可见，
 * 2026-10-06 业务逻辑下沉 Phase C 落地）。
 */
package com.vidsage.domain.task;
