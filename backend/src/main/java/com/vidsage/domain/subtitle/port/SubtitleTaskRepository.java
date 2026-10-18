package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.SubtitleTask;
import com.vidsage.domain.task.model.TaskNo;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * 字幕任务持久化的出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 */
public interface SubtitleTaskRepository {

    /**
     * 存一条新任务（与 workspace 两表同插的另一半），返回带着数据库自增 id 的那份。
     *
     * @param task 待存的新任务（经 {@code SubtitleTask.of} 创建）
     * @return 回填了数据库 id 的任务
     */
    SubtitleTask insert(SubtitleTask task);

    /**
     * 按对外任务号查（{@code task_no} 唯一索引），对外 {@code GET /api/tasks/{taskNo}} 的入口。
     *
     * @param taskNo 对外任务号
     * @return 任务；不存在时为空
     */
    Optional<SubtitleTask> findByTaskNo(TaskNo taskNo);

    /**
     * 按所属媒体查（{@code workspace_id} 唯一索引）：提交路径命中 workspace 后取它。
     *
     * @param workspaceId 所属媒体的内部行 id
     * @return 任务；不存在时为空
     */
    Optional<SubtitleTask> findByWorkspaceId(long workspaceId);

    /**
     * 按所属媒体<b>锁定读</b>（提交路径并发分支）：两行都要锁定读，否则 {@code subtitle_task}
     * 读空只能去 INSERT、再撞一次 {@code uk_subtitle_task_workspace_id}。用 {@code FOR SHARE} 的理由同
     * {@code WorkspaceRepository#findForShareBySourceTypeAndSourceKey}。
     *
     * @param workspaceId 所属媒体的内部行 id
     * @return 任务；不存在时为空
     */
    Optional<SubtitleTask> findForShareByWorkspaceId(long workspaceId);

    /**
     * 刷最近变更时刻（每次 run 收尾；启动收口对收掉的 task 批量刷）。一条 UPDATE 覆盖
     * 单条与批量两种调用——批量走 {@code IN (...)}，不在仓储里写循环。
     *
     * @param taskIds 要刷的任务 id 集合；空集合是空操作
     * @param at      本次收尾的时刻（由调用方传入以便对齐同一次收尾的时间戳）
     * @return 受影响行数
     */
    int touchUpdatedAt(Collection<Long> taskIds, LocalDateTime at);
}
