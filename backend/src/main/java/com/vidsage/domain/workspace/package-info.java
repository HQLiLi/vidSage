/**
 * 媒体身份上下文：一个媒体来源（URL / 上传）的身份与内容指纹的家。
 *
 * <p>根只留 {@code Workspace} 实体——它持有身份键（{@code source_type} + {@code source_key}）
 * 与内容指纹（{@code duration_ms} + {@code published_at} 两个可读列），且只有一个变更入口
 * {@code updateFingerprint}（指纹探测阶段写入）。
 *
 * <p>{@code model/} 装数据词汇——来源类型枚举 {@code SourceType} 与身份键值对象 {@code SourceKey}
 * （URL 路线 {@code bvid|p}、上传路线内容 sha256）；{@code port/} 装端口接口
 * {@code WorkspaceRepository}，由
 * {@code infrastructure/persistence/repository/} 的 {@code MybatisWorkspaceRepository} 实现。
 */
package com.vidsage.domain.workspace;
