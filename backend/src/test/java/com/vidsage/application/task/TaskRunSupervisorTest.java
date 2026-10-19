package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.application.task.port.TaskProgressBroadcaster;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskNo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * {@link TaskRunSupervisor} 的纯单元测试：四个依赖全用 Mockito mock，不起 Spring 容器。
 *
 * <p>重点钉两条：{@code start} 撞互斥键返回空且不清旧行、不重试；{@code finish} 落库抛异常时
 * 广播终态与清流仍然发生。
 */
class TaskRunSupervisorTest {

    private static final long TASK_ID = 7L;

    private static final TaskNo TASK_NO = new TaskNo("t7");

    private final TaskRunRepository taskRunRepository = Mockito.mock(TaskRunRepository.class);

    private final SubtitleTaskRepository subtitleTaskRepository = Mockito.mock(SubtitleTaskRepository.class);

    private final SubtitleArtifactRepository subtitleArtifactRepository =
            Mockito.mock(SubtitleArtifactRepository.class);

    private final TaskProgressBroadcaster broadcaster = Mockito.mock(TaskProgressBroadcaster.class);

    private final TaskRunSupervisor supervisor = new TaskRunSupervisor(taskRunRepository, subtitleTaskRepository,
            subtitleArtifactRepository, broadcaster);

    // ---------- start ----------

    @Test
    void startInsertsRunningRunForTask() {
        when(taskRunRepository.insertRunning(any())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        Optional<TaskRun> started = supervisor.start(task(TASK_ID), TaskRunType.MAIN, null);

        assertTrue(started.isPresent());
        ArgumentCaptor<TaskRun> captor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepository).insertRunning(captor.capture());
        TaskRun inserted = captor.getValue();
        assertEquals(TASK_ID, inserted.getTaskId());
        assertEquals(TaskRunType.MAIN, inserted.getRunType());
        assertNull(inserted.getTargetLanguage());
        assertEquals(TaskRunStatus.RUNNING, inserted.getStatus(), "插进去的就是 RUNNING——插入即抢锁");
    }

    @Test
    void startReturnsEmptyAndDoesNotTouchExistingRunWhenActiveRunConstraintIsHit() {
        when(taskRunRepository.insertRunning(any())).thenReturn(Optional.empty());

        Optional<TaskRun> started = supervisor.start(task(TASK_ID), TaskRunType.MAIN, null);

        assertTrue(started.isEmpty(), "撞互斥键时返回空，由调用方翻 409");
        verify(taskRunRepository, times(1)).insertRunning(any());
        verify(taskRunRepository, never()).update(any());
    }

    // ---------- advance ----------

    @Test
    void advancePersistsStageThenBroadcasts() {
        TaskRun run = runningRun();
        RunRef ref = RunRef.of(TASK_NO, run);
        StageProgress stage = new StageProgress("clean-subtitles", 40, "清洗中", StageProgress.State.RUNNING);

        supervisor.advance(ref, run, stage);

        assertEquals("clean-subtitles", run.getCurrentStage(), "阶段名推进到 current_stage");
        InOrder order = inOrder(taskRunRepository, broadcaster);
        order.verify(taskRunRepository).update(run);
        ArgumentCaptor<TaskRunProgress> captor = ArgumentCaptor.forClass(TaskRunProgress.class);
        order.verify(broadcaster).publish(eq(ref), captor.capture());
        TaskRunProgress event = captor.getValue();
        assertEquals(TASK_NO.value(), event.taskNo());
        assertEquals(3, event.runNo());
        assertEquals(stage, event.stage());
        assertEquals(TaskRunStatus.RUNNING, event.status());
    }

    // ---------- finish ----------

    @Test
    void finishSucceedPersistsTouchesUpdatedAtBroadcastsTerminalAndCompletes() {
        TaskRun run = runningRun();
        RunRef ref = RunRef.of(TASK_NO, run);

        supervisor.finish(ref, run, null);

        assertEquals(TaskRunStatus.SUCCEEDED, run.getStatus());
        verify(subtitleArtifactRepository).markCreatingAsFailedByRunId(1L);
        verify(taskRunRepository).update(run);
        verify(subtitleTaskRepository).touchUpdatedAt(eq(List.of(TASK_ID)), any());
        ArgumentCaptor<TaskRunProgress> captor = ArgumentCaptor.forClass(TaskRunProgress.class);
        verify(broadcaster).publish(eq(ref), captor.capture());
        assertTrue(captor.getValue().isTerminal(), "终态事件 terminal 为真");
        verify(broadcaster).complete(ref);
    }

    @Test
    void finishFailureRecordsReasonAndStillBroadcastsTerminal() {
        TaskRun run = runningRun();
        RunRef ref = RunRef.of(TASK_NO, run);

        supervisor.finish(ref, run, "转写失败：请检查 DASHSCOPE_API_KEY");

        assertEquals(TaskRunStatus.FAILED, run.getStatus());
        assertEquals("转写失败：请检查 DASHSCOPE_API_KEY", run.getErrorMessage());
        ArgumentCaptor<TaskRunProgress> captor = ArgumentCaptor.forClass(TaskRunProgress.class);
        verify(broadcaster).publish(eq(ref), captor.capture());
        assertTrue(captor.getValue().isTerminal());
        assertEquals("转写失败：请检查 DASHSCOPE_API_KEY", captor.getValue().stage().message());
        verify(broadcaster).complete(ref);
    }

    @Test
    void finishStillBroadcastsAndCompletesWhenPersistingTerminalStateThrows() {
        TaskRun run = runningRun();
        RunRef ref = RunRef.of(TASK_NO, run);
        doThrow(new IllegalStateException("模拟落库失败")).when(taskRunRepository).update(any());

        supervisor.finish(ref, run, null);

        // 落库失败只记日志，广播终态与清流仍发生——订阅者不会挂到空闲超时
        verify(subtitleArtifactRepository).markCreatingAsFailedByRunId(1L);
        verify(broadcaster).publish(eq(ref), any(TaskRunProgress.class));
        verify(broadcaster).complete(ref);
        verify(subtitleTaskRepository, never()).touchUpdatedAt(any(), any());
    }

    @Test
    void finishStillPersistsTerminalStateWhenArtifactClosureThrows() {
        TaskRun run = runningRun();
        RunRef ref = RunRef.of(TASK_NO, run);
        doThrow(new IllegalStateException("模拟占位行收口失败"))
                .when(subtitleArtifactRepository).markCreatingAsFailedByRunId(anyLong());

        supervisor.finish(ref, run, null);

        assertEquals(TaskRunStatus.SUCCEEDED, run.getStatus(),
                "占位行收口失败不能挡住 run 终态落库");
        verify(taskRunRepository).update(run);
        verify(subtitleTaskRepository).touchUpdatedAt(eq(List.of(TASK_ID)), any());
        verify(broadcaster).complete(ref);
    }

    @Test
    void finishStillCompletesWhenBroadcastThrows() {
        TaskRun run = runningRun();
        RunRef ref = RunRef.of(TASK_NO, run);
        doThrow(new IllegalStateException("模拟广播失败")).when(broadcaster).publish(eq(ref), any());

        supervisor.finish(ref, run, null);

        verify(broadcaster).complete(ref);
    }

    // ---------- recoverInterruptedRuns ----------

    @Test
    void recoverInterruptedRunsInterruptsRunsFailsCreatingArtifactsAndTouchesTasks() {
        when(taskRunRepository.interruptAllRunning(any())).thenReturn(List.of(7L, 8L));

        supervisor.recoverInterruptedRuns();

        verify(subtitleArtifactRepository).markCreatingAsFailed();
        verify(subtitleTaskRepository).touchUpdatedAt(eq(List.of(7L, 8L)), any());
    }

    @Test
    void recoverInterruptedRunsIsSafeWhenNoResidualRun() {
        when(taskRunRepository.interruptAllRunning(any())).thenReturn(List.of());

        supervisor.recoverInterruptedRuns();

        verify(subtitleArtifactRepository).markCreatingAsFailed();
        verify(subtitleTaskRepository).touchUpdatedAt(eq(List.of()), any());
    }

    // ---------- 夹具 ----------

    private static SubtitleTask task(long id) {
        LocalDateTime now = LocalDateTime.now();
        return SubtitleTask.restore(id, TASK_NO, 1L, now, now);
    }

    /** 造一条已落库、正在跑的 run：{@code RunRef.of} 要从它身上取 runNo。 */
    private static TaskRun runningRun() {
        LocalDateTime now = LocalDateTime.now();
        return TaskRun.restore(1L, TASK_ID, 3, TaskRunType.MAIN, null, TaskRunStatus.RUNNING,
                "transcribe-audio", null, now, null, now);
    }
}
