package com.vidsage.infrastructure.fs;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.shared.model.WorkspaceDir;
import com.vidsage.domain.shared.model.WorkspaceKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/***
 * 工作区目录管理器 的实现类
 */
@Slf4j
@Component
public class NioWorkspaceDirectoryManager implements WorkspaceDirectoryManager {

    private final WorkspaceProperties properties;

    public NioWorkspaceDirectoryManager(WorkspaceProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析工作区目录并确保它存在（已存在时是幂等空操作）。
     *
     * @param label 给人看的目录名标签
     * @param key   内容寻址 key
     * @return 已创建的工作区目录绝对路径
     * @throws IllegalStateException 目录创建失败（根路径不可写等）
     */
    @Override
    public Path ensureWorkspaceDirectory(String label, WorkspaceKey key) {
        return ensureCreated(getWorkspaceRoot().resolve(WorkspaceDir.of(label, key).value()));
    }

    /**
     * 获取工作区根目录绝对路径
     *
     * @return 根目录绝对路径
     */
    @Override
    public Path getWorkspaceRoot() {
        return getRootDir().toAbsolutePath();
    }

    /**
     * 工作区根目录（按配置解析，<b>不建目录</b>；root 未配置时回退
     * {@link WorkspaceDir#FALLBACK_NAME}）。
     */
    private Path getRootDir() {
        return Path.of(properties.root() == null ? WorkspaceDir.FALLBACK_NAME : properties.root());
    }

    /**
     * 确保目录存在并返回其绝对路径
     * 不存在则创建目录
     * 已存在时是幂等空操作
     */
    private Path ensureCreated(Path dir) {
        Path absolute = dir.toAbsolutePath();
        try {
            // 如果已存在则静默返回
            Files.createDirectories(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建任务工作目录：" + absolute
                    + "；请检查 vidsage.workspace.root 是否存在且可写。原因：" + e.getMessage(), e);
        }
        log.debug("任务工作目录: dir={}", absolute.getFileName());
        return absolute;
    }
}
