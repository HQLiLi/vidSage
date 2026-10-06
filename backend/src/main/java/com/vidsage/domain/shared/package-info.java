/**
 * 领域共享内核：跨上下文共用的领域词汇——{@code StageEvent}（管线阶段事件）、
 * {@code ProgressListener}（进度回调端口）、{@code WorkspaceKey}（工作区键值对象，含 ofHash /
 * ofDigest 生成工厂）、{@code SourceUri}（输入身份规范化串）与 {@code WorkspaceDir}（工作区目录名）。
 * 后三者与 WorkspaceKey 同为 task 与 media 两上下文共用的寻址词汇，放共享内核以避免上下文间横向依赖。
 */
package com.vidsage.domain.shared;
