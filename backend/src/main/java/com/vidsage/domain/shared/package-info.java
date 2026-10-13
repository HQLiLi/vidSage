/**
 * 领域共享内核：几个上下文都要用的领域词汇。根只剩本文件，构件按角色分进两个桶。
 *
 * <p>{@code model/} 装值对象：{@code WorkspaceKey}（工作区键，含 ofHash / ofDigest 工厂）、
 * {@code SourceUri}（输入身份规范化串）与 {@code WorkspaceDir}（工作区目录名）是 task 与 media
 * 共用的寻址词汇，{@code StageProgress} 是阶段进度；{@code port/} 装进度回调端口
 * {@code ProgressListener}。
 */
package com.vidsage.domain.shared;
