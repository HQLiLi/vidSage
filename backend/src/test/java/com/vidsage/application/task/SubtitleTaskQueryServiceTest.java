package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.application.task.query.KeywordsSnapshot;
import com.vidsage.application.task.query.TaskSnapshot;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link SubtitleTaskQueryService} 的纯单元测试：仓储与定位件所依赖的端口全部 mock。
 *
 * <p>重点覆盖两条查询、SSE 快照的「RUNNING 给快照 / 已终态回空 / runNo 不存在 404」，
 * 以及「复查要能拿到终态快照」——那是把流收掉而不是静默关的前提。
 */
class SubtitleTaskQueryServiceTest {

    private final WorkspaceRepository workspaceRepository = Mockito.mock(WorkspaceRepository.class);
    private final SubtitleTaskRepository subtitleTaskRepository = Mockito.mock(SubtitleTaskRepository.class);
    private final TaskRunRepository taskRunRepository = Mockito.mock(TaskRunRepository.class);
    private final SubtitleKeywordRepository keywordRepository = Mockito.mock(SubtitleKeywordRepository.class);
    private final WorkspaceDirectoryManager workspaceDirectoryManager = Mockito.mock(WorkspaceDirectoryManager.class);

    private static final long TASK_ID = 1L;
    private static final String TASK_NO = "3f2a9c1e3f2a9c1e3f2a9c1e3f2a9c1e";
    private static final String URL = "https://www.bilibili.com/video/BV11QMd6WEJp";
    private static final String SOURCE_KEY = "BV11QMd6WEJp|1";

    private SubtitleTaskQueryService service() {
        return new SubtitleTaskQueryService(
                new SubtitleTaskFinder(subtitleTaskRepository, workspaceRepository, workspaceDirectoryManager),
                taskRunRepository, keywordRepository);
    }

    @Test
    void findByTaskNoReturnsIdentityAndLastRun() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(taskRunRepository.findLatestByTaskId(TASK_ID))
                .thenReturn(Optional.of(runningRun(TaskRunType.TRANSLATE, "zh-Hans", 3)));

        TaskSnapshot snapshot = service().findByTaskNo(TASK_NO);

        assertEquals(TASK_NO, snapshot.taskNo());
        assertEquals(SourceType.URL, snapshot.sourceType());
        assertEquals(URL, snapshot.sourceUri());
        assertNotNull(snapshot.lastRun());
        assertEquals(3, snapshot.lastRun().runNo());
        assertEquals(TaskRunType.TRANSLATE, snapshot.lastRun().runType());
        assertEquals("zh-Hans", snapshot.lastRun().targetLanguage());
        assertEquals(TaskRunStatus.RUNNING, snapshot.lastRun().status());
    }

    @Test
    void findByTaskNoHasNullLastRunWhenNeverExecuted() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(workspaceRepository.findById(TASK_ID)).thenReturn(Optional.of(urlWorkspace()));
        when(taskRunRepository.findLatestByTaskId(TASK_ID)).thenReturn(Optional.empty());

        TaskSnapshot snapshot = service().findByTaskNo(TASK_NO);

        assertNull(snapshot.lastRun());
    }

    @Test
    void unknownTaskNoThrowsNotFound() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> service().findByTaskNo("ghost"));
    }

    @Test
    void streamSnapshotReturnsRunningSnapshotWhenRunIsRunning() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(taskRunRepository.findByTaskIdAndRunNo(TASK_ID, 2))
                .thenReturn(Optional.of(runningRun(TaskRunType.MAIN, null, 2)));

        Optional<TaskRunProgress> snapshot = service().streamSnapshot(TASK_NO, 2);

        assertTrue(snapshot.isPresent(), "RUNNING 的 run 要开流，给出首条 DB 快照");
        assertEquals(TASK_NO, snapshot.get().taskNo());
        assertEquals(2, snapshot.get().runNo());
        assertEquals(TaskRunStatus.RUNNING, snapshot.get().status());
        assertFalse(snapshot.get().isTerminal());
    }

    @Test
    void streamSnapshotIsEmptyForTerminalRun() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(taskRunRepository.findByTaskIdAndRunNo(TASK_ID, 2))
                .thenReturn(Optional.of(terminalRun(TaskRunType.MAIN, 2)));

        assertTrue(service().streamSnapshot(TASK_NO, 2).isEmpty(), "已终态 → 204，不建流");
    }

    @Test
    void streamSnapshotWithUnknownRunNoThrowsNotFound() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(taskRunRepository.findByTaskIdAndRunNo(TASK_ID, 9)).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> service().streamSnapshot(TASK_NO, 9));
    }

    @Test
    void renderSnapshotRendersTerminalRunSoStreamCanBeClosed() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(taskRunRepository.findByTaskIdAndRunNo(TASK_ID, 2))
                .thenReturn(Optional.of(terminalRun(TaskRunType.MAIN, 2)));

        TaskRunProgress snapshot = service().renderSnapshot(TASK_NO, 2);

        assertTrue(snapshot.isTerminal(), "复查拿到终态要能推终态快照，不被过滤成空");
        assertEquals(TaskRunStatus.SUCCEEDED, snapshot.status());
    }

    @Test
    void findKeywordsReadsByTaskId() {
        when(subtitleTaskRepository.findByTaskNo(any())).thenReturn(Optional.of(task()));
        when(keywordRepository.findByTaskId(TASK_ID)).thenReturn(new SubtitleKeywords(List.of("影视飓风")));

        KeywordsSnapshot snapshot = service().findKeywords(TASK_NO);

        assertEquals(List.of("影视飓风"), snapshot.values());
        assertNotNull(snapshot.fingerprint());
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

    private static TaskRun runningRun(TaskRunType type, String targetLanguage, int runNo) {
        LocalDateTime now = LocalDateTime.now();
        return TaskRun.restore(1L, TASK_ID, runNo, type, targetLanguage, TaskRunStatus.RUNNING, null, null,
                now, null, now);
    }

    private static TaskRun terminalRun(TaskRunType type, int runNo) {
        LocalDateTime now = LocalDateTime.now();
        return TaskRun.restore(1L, TASK_ID, runNo, type, null, TaskRunStatus.SUCCEEDED, "clean-subtitles",
                null, now, now, now);
    }
}
