package com.vidsage.domain.workspace.model;

/**
 * 一次媒体身份解析的结果：算出来的身份键，外加一个给人看的规范化 URL。
 *
 * <p>身份键用来认领同一个 {@code workspace}；规范化 URL 只写进
 * {@code workspace.canonical_url}，不参与任何判定。
 *
 * @param sourceKey    身份键（URL 路线 {@code bvid|p}；上传路线内容 sha256）
 * @param canonicalUrl 去掉跟踪参数后的 URL，只给人看
 */
public record ResolvedMediaIdentity(SourceKey sourceKey, String canonicalUrl) {
}
