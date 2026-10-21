/**
 * 领域共享内核：几个上下文都要用的领域词汇。根只剩本文件，构件按角色分进两个桶。
 *
 * <p>{@code model/} 装值对象 {@code StageProgress}（阶段进度）；{@code port/} 装进度回调端口
 * {@code ProgressListener}。媒体身份键与工作区目录名见 {@code domain/workspace/}。
 */
package com.vidsage.domain.shared;
