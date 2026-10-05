/**
 * 领域层：业务概念、规则、端口接口的家。
 *
 * <p>按限界上下文分包：{@code task} / {@code media} / {@code transcription} / {@code subtitle}，
 * 加 {@code shared} 跨上下文共享内核。状态机与不变量（如 {@code VideoTask} 拒绝非法状态迁移）
 * 只住在这里，其余层不得自备业务规则。
 *
 * <p>依赖规则：本层不依赖 application / infrastructure / web；application 正向依赖本层；
 * infrastructure 实现本层的端口接口（{@code MediaAcquirer} / {@code AudioExtractor} /
 * {@code ASRProvider} / {@code TermLookupProvider}）——六边形原则：端口在六边形内部，适配器在外。
 */
package com.vidsage.domain;
