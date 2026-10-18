package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.TaskRun;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 每次执行持久化的出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 */
public interface TaskRunRepository {

    /**
     * 插一条 {@code RUNNING} 的 run——<b>插入即抢互斥</b>。{@code run_no} 按 {@code MAX+1}
     * 生成并回填。
     *
     * <p>撞 {@code uk_task_run_task_id_active_flag} 时返回空（说明该 task 已有 RUNNING 行，抢失败），
     * 由调用方翻成 409。仓储内 catch {@code DuplicateKeyException} 后<b>不外抛</b>——撞键是预期分支不是
     * 异常，且不跨 Spring 代理边界就不会把事务标成 rollback-only。
     *
     * @param run 待插的新 run（经 {@code TaskRun.of} 创建，状态 RUNNING）
     * @return 回填了数据库 id 与 run_no 的 run；撞互斥键时为空
     */
    Optional<TaskRun> insertRunning(TaskRun run);

    /**
     * 查最近一条 run（{@code lastRun}）：<b>含正在跑的那条</b>，按 {@code run_no} 倒序取一条。
     *
     * @param taskId 所属字幕任务
     * @return 最近一条 run；从未执行时为空
     */
    Optional<TaskRun> findLatestByTaskId(long taskId);

    /**
     * 整体回写 run（推进 {@code current_stage}；收口 {@code fail(...)} 之后整体回写）：
     * 按 id 覆盖全部字段。
     *
     * @param run 已变更的 run（变更须经 {@code TaskRun} 的迁移方法产生，不要直接改字段）
     * @return 受影响行数
     */
    int update(TaskRun run);

    /**
     * 启动收口第一条：残留 {@code RUNNING} → {@code INTERRUPTED} + {@code finished_at}。
     *
     * @param at 启动收口的时刻（由调用方传入）
     * @return 被收掉的 {@code task_id}（去重）——第三条 UPDATE 用它批量刷 {@code subtitle_task.updated_at}，
     *         同时供「上个进程有 N 个任务没跑完」的日志
     */
    List<Long> interruptAllRunning(LocalDateTime at);
}
