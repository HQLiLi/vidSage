package com.vidsage.application.task.query;

import java.nio.file.Path;

/**
 * 产物文件（下载用内部契约）：{@code VideoTaskService.openArtifact} 的返回值，
 * 携带已通过路径穿越校验的绝对路径，供 web 层流式读取。
 *
 * <p><b>不是对外契约</b>：{@code path} 指向服务端工作区内部位置，绝不进 JSON——
 * 对外的清单项是 {@link TaskArtifact}（name + sizeBytes），两者刻意分开，
 * 免得带路径的形状被顺手序列化出去。
 *
 * @param path      产物绝对路径（已在任务工作区内，服务层保证）
 * @param name      产物文件名（不含路径，进 Content-Disposition）
 * @param sizeBytes 文件字节数（进 Content-Length）
 */
public record TaskArtifactFile(Path path, String name, long sizeBytes) {
}
