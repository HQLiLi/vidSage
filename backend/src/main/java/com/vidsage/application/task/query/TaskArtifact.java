package com.vidsage.application.task.query;

/**
 * 任务产物文件（清单项）：任务读模型 {@code TaskSnapshot.artifacts} 的元素，随
 * {@code web/dto/TaskResponse} 对外（JSON 形状即 { name, sizeBytes }）。
 *
 * <p>应用层读模型零 web 注解（2026-10-06 读模型拆分，directory-structure.md「契约类的位置」
 * 判据 2）。只暴露文件名与字节数；工作区目录、绝对路径等内部信息不出境——文件名可直接拼
 * 产物下载端点 {@code GET /api/tasks/{taskNo}/artifacts/{fileName}} 下载。
 *
 * @param name      产物文件名（不含路径）
 * @param sizeBytes 文件字节数
 */
public record TaskArtifact(
        String name,
        long sizeBytes) {
}
