package com.vidsage.domain.task;

import com.vidsage.domain.task.model.TaskNo;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 字幕任务身份实体：一个媒体（workspace）对应一个对外稳定的 task_no。
 *
 * <p><b>没有状态列</b>：主状态、失败原因、阶段全部从 {@code task_run} 派生，
 * 所以本实体没有状态迁移，只有一个变更入口 {@link #recordRunFinished}（每次 run 收尾刷一下）。
 *
 * <p>{@code task_no} 复用 {@code domain/task/model/TaskNo}。
 */
@Getter
public class SubtitleTask {

    /** 数据库内部行 id；insert 回填，新建时在落库前为 {@code null}。 */
    private Long id;

    /** 对外稳定号值对象（32 位短 UUID 语义），生成后全生命周期不变。 */
    private final TaskNo taskNo;

    /** 所属媒体身份（逻辑外键 → workspace.id），唯一。 */
    private final long workspaceId;

    /** 创建时间。 */
    private final LocalDateTime createdAt;

    /** 最近一次 run 收尾的时刻（本表唯一的变更）。 */
    private LocalDateTime updatedAt;

    /** 私有构造：创建入口只有 {@link #of} 与 {@link #restore}。 */
    private SubtitleTask(Long id, TaskNo taskNo, long workspaceId, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.taskNo = taskNo;
        this.workspaceId = workspaceId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 为一个媒体新建字幕任务：生成对外 task_no、落创建时间戳。
     *
     * @param workspaceId 所属媒体身份的内部行 id
     * @return 新任务（id 未落库，为 {@code null}）
     */
    public static SubtitleTask of(long workspaceId) {
        LocalDateTime now = LocalDateTime.now();
        return new SubtitleTask(null, TaskNo.random(), workspaceId, now, now);
    }

    /**
     * 重建已落库的任务：仅供 persistence 层把数据库行还原成领域对象使用，业务代码不得用它凭空造任务。
     */
    public static SubtitleTask restore(Long id, TaskNo taskNo, long workspaceId,
                                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new SubtitleTask(id, taskNo, workspaceId, createdAt, updatedAt);
    }

    /**
     * 刷最近变更时刻（每次 run 收尾调用）。
     *
     * @param at 本次收尾的时刻（由调用方传入，便于把「同一次收尾」的多个时间戳对齐）
     */
    public void recordRunFinished(LocalDateTime at) {
        this.updatedAt = at;
    }
}
