package com.vidsage.application.task.query;

/**
 * 任务产物文件（清单项）：任务读模型 {@code TaskSnapshot.artifacts} 的元素，
 * JSON 形状即 {@code {name, sizeBytes}}。
 *
 * <p>应用层读模型零 web 注解。只暴露文件名与
 * 字节数；工作区目录、绝对路径等内部信息不出境——文件名可直接拼产物下载端点的路径下载。
 *
 * @param name      产物文件名（不含路径）
 * @param sizeBytes 文件字节数
 */
public record TaskArtifact(
        String name,
        long sizeBytes) {
}
