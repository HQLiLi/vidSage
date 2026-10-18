package com.vidsage.domain.subtitle;

import com.vidsage.domain.subtitle.model.TaskRunStatus;
import com.vidsage.domain.subtitle.model.TaskRunType;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 一次执行：每次提交 / 重新生成 / 翻译都新插一条 run。
 *
 * <p><b>插入即抢锁</b>：一行 {@code RUNNING} 的插入由 {@code uk(task_id, active_flag)} 约束，
 * 一个 task 同一时刻至多一条；插不进去就是有人正在跑（→ 409）。
 *
 * <p>状态机每个值恰好一条路径：{@code RUNNING}（工厂出生）→ {@code SUCCEEDED}（{@link #succeed}）
 * / {@code FAILED}（{@link #fail}）；{@code INTERRUPTED} 由启动收口的批量 UPDATE 补记，
 * <b>实体不给 interrupt()</b>（一个值只给一条写入路径）。
 *
 * <p>{@code run_no} 由数据库按 {@code MAX+1} 生成（persistence 层回填），落库前为 {@code null}；
 * 重建已落库的 run 只能走 {@link #restore}。
 */
@Getter
public class TaskRun {

    /** 数据库内部行 id；insert 回填，新建时在落库前为 {@code null}。 */
    private Long id;

    /** 所属字幕任务（逻辑外键 → subtitle_task.id）。 */
    private final long taskId;

    /** 任务内递增序号（MAX+1 生成，persistence 层回填）；落库前为 {@code null}。 */
    private Integer runNo;

    /** 执行类型（MAIN / RECLEAN / TRANSLATE）。 */
    private final TaskRunType runType;

    /** 目标语种；只有 TRANSLATE 有值，其余为 {@code null}。 */
    private final String targetLanguage;

    /** 执行状态。 */
    private TaskRunStatus status;

    /** 当前阶段名（前端「卡在哪一步」）。 */
    private String currentStage;

    /** 失败原因（中文，面向用户）；未失败时为 {@code null}。 */
    private String errorMessage;

    /** 开始执行时间（插 RUNNING 行时填）。 */
    private final LocalDateTime startedAt;

    /** 终态时间；INTERRUPTED 填启动收口的时刻。 */
    private LocalDateTime finishedAt;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /** 私有构造：创建入口只有 {@link #of} 与 {@link #restore}。 */
    private TaskRun(Long id, long taskId, Integer runNo, TaskRunType runType, String targetLanguage,
                    TaskRunStatus status, String currentStage, String errorMessage,
                    LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.runNo = runNo;
        this.runType = runType;
        this.targetLanguage = targetLanguage;
        this.status = status;
        this.currentStage = currentStage;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.createdAt = createdAt;
    }

    /**
     * 开一条正在跑的 run：状态 RUNNING、落 startedAt；{@code run_no} 留空由数据库生成后回填。
     *
     * @param taskId         所属字幕任务
     * @param runType        执行类型
     * @param targetLanguage 目标语种；只有 TRANSLATE 传值，其余传 {@code null}
     * @return 状态为 RUNNING 的新 run（id 与 runNo 未落库，为 {@code null}）
     */
    public static TaskRun of(long taskId, TaskRunType runType, String targetLanguage) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskRun(null, taskId, null, runType, targetLanguage, TaskRunStatus.RUNNING,
                null, null, now, null, now);
    }

    /**
     * 重建已落库的 run：仅供 persistence 层把数据库行还原成领域对象使用。接受任何状态
     * （含 INTERRUPTED），不做迁移校验——读的是已落库的事实，不是「迁过去」。
     */
    public static TaskRun restore(Long id, long taskId, Integer runNo, TaskRunType runType, String targetLanguage,
                                  TaskRunStatus status, String currentStage, String errorMessage,
                                  LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime createdAt) {
        return new TaskRun(id, taskId, runNo, runType, targetLanguage, status, currentStage, errorMessage,
                startedAt, finishedAt, createdAt);
    }

    /**
     * RUNNING → SUCCEEDED：跑到底、产物齐了，落 finishedAt。
     *
     * @throws IllegalStateException 当前不是 RUNNING
     */
    public void succeed() {
        validateRunning("SUCCEEDED（只有 RUNNING 的 run 可以标记成功）");
        status = TaskRunStatus.SUCCEEDED;
        finishedAt = LocalDateTime.now();
    }

    /**
     * RUNNING → FAILED：执行体退出时还没落终态，无条件收口置位，落失败原因与 finishedAt。
     *
     * @param errorMessage 失败原因（中文，面向用户）
     * @throws IllegalStateException 当前不是 RUNNING
     */
    public void fail(String errorMessage) {
        validateRunning("FAILED（只有 RUNNING 的 run 可以标记失败）");
        status = TaskRunStatus.FAILED;
        this.errorMessage = errorMessage;
        finishedAt = LocalDateTime.now();
    }

    /**
     * 推进当前阶段名（限 RUNNING 期间，由进度回调或阶段边界驱动）。
     *
     * @param stage 阶段名
     * @throws IllegalStateException 当前不是 RUNNING
     */
    public void updateStage(String stage) {
        validateRunning("推进阶段（run 不在运行中）");
        currentStage = stage;
    }

    /** 状态守卫：当前状态与迁移目标不符即拒绝，消息给出「当前状态 → 目标状态」与合法前提。 */
    private void validateRunning(String targetDescription) {
        if (status != TaskRunStatus.RUNNING) {
            throw new IllegalStateException("非法状态迁移：" + status + " → " + targetDescription
                    + "，当前状态为 " + status);
        }
    }
}
