package com.vidsage.application.media.port;

import com.vidsage.domain.shared.model.WorkspaceKey;

import java.nio.file.Path;

/**
 * 工作区目录管理器
 */
public interface WorkspaceDirectoryManager {
    /**
     * 解析工作区目录并确保它存在（已存在时是幂等空操作）。
     *
     * @param label 给人看的目录名标签
     * @param key   内容寻址 key
     * @return 已创建的工作区目录绝对路径
     * @throws IllegalStateException 目录创建失败（根路径不可写等）
     */
    Path ensureWorkspaceDirectory(String label, WorkspaceKey key);

    /**
     * 获取工作区根目录绝对路径
     *
     * @return 根目录绝对路径
     */
    Path getWorkspaceRoot();
}
