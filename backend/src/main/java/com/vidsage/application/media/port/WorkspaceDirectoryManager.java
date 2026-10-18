package com.vidsage.application.media.port;

import com.vidsage.domain.workspace.model.SourceKey;

import java.nio.file.Path;

/**
 * 工作区目录管理器
 */
public interface WorkspaceDirectoryManager {
    /**
     * 解析工作区目录并确保它存在（已存在时是幂等空操作）。目录名由身份键推出。
     *
     * @param key 身份键（URL 路线 {@code bvid|p}；上传路线内容 sha256）
     * @return 已创建的工作区目录绝对路径
     * @throws IllegalStateException 目录创建失败（根路径不可写等）
     */
    Path ensureWorkspaceDirectory(SourceKey key);

    /**
     * 获取工作区根目录绝对路径
     *
     * @return 根目录绝对路径
     */
    Path getWorkspaceRoot();
}
