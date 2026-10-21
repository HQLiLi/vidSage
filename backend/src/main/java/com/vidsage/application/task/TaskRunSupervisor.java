package com.vidsage.application.task;

import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.application.task.port.TaskProgressBroadcaster;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.task.port.TaskRunRepository;
import com.vidsage.domain.task.model.RunRef;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * run 的生命周期机制：插 run 抢互斥、推进阶段、收口、启动收口。
 *
 * <p>机制收在一处：互斥键让「插 run」同时是抢互斥与状态写入，谁都不能绕过它单独改 run 状态。
 *
 * <p>收口是无条件的：落终态、广播终态、清这条 run 的流三件事顺序写死，后两件不因落库失败而跳过——
 * 落库一抛就既没终态也不清流，订阅者会一直挂到空闲超时。
 */
@Slf4j
@Component
public class TaskRunSupervisor {

    private final TaskRunRepository taskRunRepository;

    private final SubtitleTaskRepository subtitleTaskRepository;

    private final SubtitleArtifactRepository subtitleArtifactRepository;

    private final TaskProgressBroadcaster broadcaster;

    public TaskRunSupervisor(TaskRunRepository taskRunRepository,
                             SubtitleTaskRepository subtitleTaskRepository,
                             SubtitleArtifactRepository subtitleArtifactRepository,
                             TaskProgressBroadcaster broadcaster) {
        this.taskRunRepository = taskRunRepository;
        this.subtitleTaskRepository = subtitleTaskRepository;
        this.subtitleArtifactRepository = subtitleArtifactRepository;
        this.broadcaster = broadcaster;
    }

    /**
     * 开一条 RUNNING 的 run，插入即抢互斥：同一 task 已有 RUNNING 行时仓储返回空，本方法原样返回空，
     * 不外抛、不重试、不动旧行——由调用方把空翻成 409。
     *
     * @param task           所属字幕任务（须已落库，带 id）
     * @param type           执行类型
     * @param targetLanguage 目标语种；只有 TRANSLATE 传值，其余传 {@code null}
     * @return 回填了 id 与 runNo 的新 run；该 task 已有 run 在跑时为空
     */
    public Optional<TaskRun> start(SubtitleTask task, TaskRunType type, String targetLanguage) {
        return taskRunRepository.insertRunning(TaskRun.of(task.getId(), type, targetLanguage));
    }

    /**
     * 推进当前阶段：先改 run、再落库、最后广播。
     *
     * @param ref   这条 run 的对外引用（taskNo 与 runNo 的来源）
     * @param run   正在跑的 run
     * @param stage 本次阶段事件
     */
    public void advance(RunRef ref, TaskRun run, StageProgress stage) {
        run.updateStage(stage.stage());
        taskRunRepository.update(run);
        broadcaster.publish(ref, TaskRunProgress.of(ref, run, stage));
    }

    /**
     * 收口一条 run：落终态 → 广播终态 → 清这条 run 的流，顺序写死，后两步不因落库失败而跳过。
     *
     * <p>落库失败只记 ERROR；广播与清流仍照做——把三步包进同一个 try 会让落库一抛就既没终态也不清流，
     * 订阅者会一直挂到空闲超时。
     *
     * @param ref                这条 run 的对外引用
     * @param run                待收口的 run
     * @param errorMessageOrNull 失败原因；传 {@code null} 表示成功
     */
    public void finish(RunRef ref, TaskRun run, String errorMessageOrNull) {
        try {
            // 本次run如果有创建产物的操作，判断产物状态是否还处于创建中，如果还处于就给他设为失败，防止影响相同任务的其他run
            subtitleArtifactRepository.markCreatingAsFailedByRunId(run.getId());
        } catch (RuntimeException e) {
            log.error("run 占位产物收口失败（仍会落 run 终态）: runNo={}", run.getRunNo(), e);
        }
        try {
            if (errorMessageOrNull == null) {
                run.succeed();
            } else {
                run.fail(errorMessageOrNull);
            }
            taskRunRepository.update(run);
            subtitleTaskRepository.touchUpdatedAt(List.of(run.getTaskId()), LocalDateTime.now());
        } catch (RuntimeException e) {
            log.error("run 终态落库失败，仍会广播终态并清流: runNo={}", run.getRunNo(), e);
        }
        try {
            broadcaster.publish(ref, TaskRunProgress.of(ref, run, terminalStage(run)));
        } catch (RuntimeException e) {
            log.error("run 终态广播失败: runNo={}", run.getRunNo(), e);
        }
        broadcaster.complete(ref);
    }

    /**
     * 启动收口：把上个进程留下的残留 RUNNING run 收成 INTERRUPTED、残留 CREATING 产物收成 FAILED，
     * 并刷新被收掉任务的 updated_at。
     */
    public void recoverInterruptedRuns() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> taskIds = taskRunRepository.interruptAllRunning(now);
        subtitleArtifactRepository.markCreatingAsFailed();
        subtitleTaskRepository.touchUpdatedAt(taskIds, now);
        // 一个 task 至多一条 RUNNING run（互斥键保证），所以「收掉几条 run」就是「几个任务没跑完」，一个数就够
        log.warn("启动收口：上个进程有 {} 个任务没跑完，残留 run 已收成 INTERRUPTED", taskIds.size());
    }

    /**
     * 终态事件里的阶段渲染：复用 {@link TaskRunProgress#renderStage}，消息形状只在一处定义。
     */
    private static StageProgress terminalStage(TaskRun run) {
        return TaskRunProgress.renderStage(run);
    }
}
