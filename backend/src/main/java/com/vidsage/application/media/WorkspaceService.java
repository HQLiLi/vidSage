package com.vidsage.application.media;

import com.vidsage.application.media.port.FileStateReader;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.media.model.MediaFileInfo;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.SourceUri;
import com.vidsage.domain.shared.model.WorkspaceDir;
import com.vidsage.domain.shared.model.WorkspaceKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 获取媒体所在的工作区目录
 */
@Slf4j
@Service
public class WorkspaceService {

    private final WorkspaceProperties properties;

    private final FileStateReader fileStateReader;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param properties 工作目录根配置
     */
    public WorkspaceService(WorkspaceProperties properties, FileStateReader fileStateReader) {
        this.properties = properties;
        this.fileStateReader = fileStateReader;
    }

    /**
     * 获取媒体对应的工作区目录
     *
     * @param input 媒体输入；不可为空
     * @return 工作目录的绝对路径，已确保存在
     * @throws IllegalArgumentException 输入为空
     * @throws IllegalStateException    目录创建失败（根路径不可写等）
     */
    public Path workspaceFor(MediaInput input) {
        if (input == null) {
            throw new IllegalArgumentException("媒体输入不能为空：请传 RemoteUrl / LocalPath / InWorkspace");
        }
        MediaFileInfo mediaFileInfo = getMediaFileInfo(input);
        return ensureCreated(rootDir().resolve(
                WorkspaceDir.of(input.dirLabel(), input.getContentKey(mediaFileInfo)).value()));
    }

    /**
     * 获取媒体对应的工作区目录（刚上传媒体，还在临时区域，根据媒体信息来生成key）
     * @param label 给人看的标签
     * @param key 工作区key
     * @return 已创建的工作区目录路径
     */
    public Path workspaceFor(String label, WorkspaceKey key) {
        return ensureCreated(rootDir().resolve(WorkspaceDir.of(label, key).value()));
    }

    /**
     * 根据媒体源的输入获取媒体信息
     *
     * @param input 媒体输入
     * @return 媒体信息
     */
    private MediaFileInfo getMediaFileInfo(MediaInput input) {
        if (!(input instanceof MediaInput.LocalPath local) || local.value() == null) {
            return MediaFileInfo.NOT_APPLICABLE;
        }
        return fileStateReader.getFileState(local.value());
    }


    /**
     * 工作区根目录的绝对路径（<b>不建目录</b>）。
     *
     * <p>给 MVP-1 的两条新链路用：上传路线在根下落临时文件、按内容寻址归位；任务执行时
     * 按持久化的 workspace_dir 还原媒体文件绝对路径。目录创建仍由 {@link #workspaceFor}
     * 的懒创建触发，本方法保持无副作用。
     *
     * @return 根目录绝对路径（root 未配置时回退 {@link WorkspaceDir#FALLBACK_NAME}，与 workspaceFor 同口径）
     */
    public Path workspaceRoot() {
        return rootDir().toAbsolutePath();
    }

    /**
     * 工作区根目录（按配置解析，<b>不建目录</b>；root 未配置时回退
     * {@link WorkspaceDir#FALLBACK_NAME}）。
     */
    private Path rootDir() {
        return Path.of(properties.root() == null ? WorkspaceDir.FALLBACK_NAME : properties.root());
    }

    /**
     * 确保目录存在并返回其绝对路径（workspaceFor 两条路线的公共尾部；已存在时是幂等空操作）。
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
