package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.config.WorkspaceProperties;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import com.vidsage.infrastructure.fs.NioWorkspaceDirectoryManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * {@link SubtitleTaskFinder} 的纯单元测试：两个仓储 mock，工作区管理器用真实实例 + 临时目录。
 *
 * <p>重点钉住两件事：空白 / 未知任务号分别映射 400 与 404；{@link SubtitleTaskFinder#workspaceDir}
 * 只拼路径、不建目录。
 */
class SubtitleTaskFinderTest {

    @TempDir
    Path tempDir;

    private final SubtitleTaskRepository subtitleTaskRepository = Mockito.mock(SubtitleTaskRepository.class);
    private final WorkspaceRepository workspaceRepository = Mockito.mock(WorkspaceRepository.class);

    private static final long TASK_ID = 1L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";
    private static final String URL = "https://www.bilibili.com/video/BV11QMd6WEJp";
    private static final String SOURCE_KEY = "BV11QMd6WEJp|1";

    private SubtitleTaskFinder finder() {
        return new SubtitleTaskFinder(subtitleTaskRepository, workspaceRepository, directoryManager());
    }

    private NioWorkspaceDirectoryManager directoryManager() {
        return new NioWorkspaceDirectoryManager(new WorkspaceProperties(tempDir.resolve("works").toString()));
    }

    @Test
    void requireReturnsTaskByTaskNo() {
        SubtitleTask task = task();
        when(subtitleTaskRepository.findByTaskNo(new TaskNo(TASK_NO))).thenReturn(Optional.of(task));

        assertSame(task, finder().require(TASK_NO));
    }

    @Test
    void requireRejectsBlankTaskNoBeforeQuerying() {
        assertThrows(IllegalArgumentException.class, () -> finder().require("  "));
        verify(subtitleTaskRepository, never()).findByTaskNo(any());
    }

    @Test
    void requireThrowsNotFoundForUnknownTaskNo() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> finder().require("ghost"));
    }

    @Test
    void requireWorkspaceLoadsMediaOfTask() {
        Workspace workspace = urlWorkspace();
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(workspace));

        assertSame(workspace, finder().requireWorkspace(task()));
    }

    @Test
    void requireWorkspaceThrowsWhenMediaIsMissing() {
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> finder().requireWorkspace(task()));
    }

    @Test
    void workspaceDirLocatesDirectoryWithoutCreatingIt() {
        Path expected = tempDir.resolve("works").resolve("BV11QMd6WEJp_1");

        assertEquals(expected, finder().workspaceDir(urlWorkspace()));
        assertFalse(Files.exists(expected), "定位不该把目录建出来——建目录是管线执行体的事");
    }

    // ---------- 夹具 ----------

    private static Workspace urlWorkspace() {
        LocalDateTime now = LocalDateTime.now();
        return Workspace.restore(TASK_ID, SourceType.URL, new SourceKey(SOURCE_KEY), URL, URL, null, null, now, now);
    }

    private static SubtitleTask task() {
        LocalDateTime now = LocalDateTime.now();
        return SubtitleTask.restore(TASK_ID, new TaskNo(TASK_NO), TASK_ID, now, now);
    }
}
