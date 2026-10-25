package com.vidsage.application.task;

import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.application.task.port.TaskProgressBroadcaster;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
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
     * 撞互斥键时的那条冲突：插 run 只告诉「有人正在跑」，既有 run 的序号要再取一次
     * （取不到说明状态自相矛盾，属服务端缺陷）。
     *
     * <p>取既有 run 用<b>锁定读</b>：并发分支的调用方可能正跑在一个快照已经陈旧的事务里，
     * 普通 SELECT 看不到对方刚提交的 RUNNING 行，会误判成「撞键却无既有 run」而 500。
     *
     * @param task 撞上互斥的任务
     * @return 带既有 run 序号的那条冲突（HTTP 边界映射 409）
     */
    public TaskConflictException buildTaskConflictException(SubtitleTask task) {
        int runNo = taskRunRepository.findLatestForShareByTaskId(task.getId())
                .map(TaskRun::getRunNo)
                .orElseThrow(() -> new IllegalStateException(
                        "互斥撞键但取不到既有 run：taskId=" + task.getId()));
        return new TaskConflictException(task.getTaskNo().value(), runNo);
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
     * 这条 run 的阶段回调：先打一条 debug，再交给 {@link #advance} 推进。
     *
     * <p>各条流程都从这里取，免得各自抄一份「记日志 + 推进」的胶水。
     *
     * @param ref 这条 run 的对外引用
     * @param run 正在跑的 run
     * @return 给各阶段用的进度监听
     */
    public ProgressListener listener(RunRef ref, TaskRun run) {
        return stageProgress -> {
            log.debug("任务阶段事件: taskNo={}, runNo={}, stage={}, progress={}, state={}, message={}",
                    ref.taskNo().value(), ref.runNo(), stageProgress.stage(), stageProgress.progress(),
                    stageProgress.state(), stageProgress.message());
            advance(ref, run, stageProgress);
        };
    }

    /**
     * 按异常收口一条 run：失败原因取异常消息，取不到时退回异常类名
     * （消息为 {@code null} 时不传播字面量 "null"）。
     *
     * @param ref   这条 run 的对外引用
     * @param run   待收口的 run
     * @param cause 让本次执行失败的那个异常
     */
    public void fail(RunRef ref, TaskRun run, Throwable cause) {
        finish(ref, run, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
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
