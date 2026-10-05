/**
 * 领域共享内核：跨上下文共用的领域词汇——{@code StageEvent}（管线阶段事件）、
 * {@code ProgressListener}（进度回调端口）、{@code WorkspaceKey}（工作区键值对象）。
 * 后者被 task 与 media 两上下文共用，放共享内核以避免上下文间横向依赖。
 */
package com.vidsage.domain.shared;
