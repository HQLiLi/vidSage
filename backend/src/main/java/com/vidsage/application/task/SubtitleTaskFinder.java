package com.vidsage.application.task;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.WorkspaceDir;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 按任务号找任务、它的媒体身份、它的工作目录，写侧与读侧共用同一份定位。
 *
 * <p>提交 / 上传 / 重生成 / 翻译与两条查询走的是同一条链：taskNo → 任务行 → 媒体行 → 工作目录。
 * 收在一处，免得两侧各写一份、日后慢慢分叉。
 */
@Component
public class SubtitleTaskFinder {

    private final SubtitleTaskRepository subtitleTaskRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;

    public SubtitleTaskFinder(SubtitleTaskRepository subtitleTaskRepository,
                              WorkspaceRepository workspaceRepository,
                              WorkspaceDirectoryManager workspaceDirectoryManager) {
        this.subtitleTaskRepository = subtitleTaskRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
    }

    /**
     * 按对外任务号取任务。
     *
     * @param taskNo 对外任务号
     * @return 任务行
     * @throws IllegalArgumentException taskNo 为空白（HTTP 边界映射 400）
     * @throws TaskNotFoundException    任务不存在（HTTP 边界映射 404）
     */
    public SubtitleTask require(String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
        return subtitleTaskRepository.findByTaskNo(new TaskNo(taskNo))
                .orElseThrow(() -> new TaskNotFoundException(taskNo));
    }

    /**
     * 取任务所属的媒体身份。任务与身份同生，缺了属服务端缺陷。
     *
     * @param task 任务行
     * @return 它的媒体身份
     * @throws IllegalStateException 媒体行不存在（数据自相矛盾）
     */
    public Workspace requireWorkspace(SubtitleTask task) {
        return workspaceRepository.findById(task.getWorkspaceId())
                .orElseThrow(() -> new IllegalStateException(
                        "任务所属的媒体身份缺失：workspaceId=" + task.getWorkspaceId()));
    }

    /**
     * 定位该媒体的工作目录（工作区根 / {@code sanitizeName(source_key)}）。
     *
     * <p><b>只定位、不创建</b>：调用方都是先看文件在不在，前置判据不该有建目录的副作用。
     * 目录确实由管线执行体建出来。
     *
     * @param workspace 媒体身份
     * @return 目录路径；目录可能还不存在
     */
    public Path workspaceDir(Workspace workspace) {
        return workspaceDirectoryManager.getWorkspaceRoot()
                .resolve(WorkspaceDir.of(workspace.getSourceKey()).value());
    }
}
