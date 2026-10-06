package com.vidsage.domain.task;

import com.vidsage.domain.shared.WorkspaceKey;
import java.util.Optional;

/**
 * 任务持久化的出口：谁要存任务、读任务，都走这个接口，不直接碰数据库那一套。
 *
 * <p>实现在 {@code infrastructure/persistence/repository/}（当前是 MyBatis-Plus 那版
 * {@code MybatisVideoTaskRepository}），由 Spring 注进来。本接口只说「能做什么」，
 * 底下是 MySQL 还是别的存储，领域层不关心。
 *
 * <p>「什么算进行中」是业务判据，单源在 {@link VideoTask#ACTIVE_STATUSES}；实现类只负责
 * 把它落成查询条件，不自己另定一套。
 */
public interface VideoTaskRepository {

    /**
     * 存一条新任务（PENDING），返回带着数据库自增 id 的那份——id 由数据库生成，调用方以返回值为准。
     *
     * @param task 待存的新任务（经 {@code VideoTask.newPending} 创建）
     * @return 回填了数据库 id 的任务
     */
    VideoTask insert(VideoTask task);

    /**
     * 按对外任务号查（task_no 是唯一索引）。
     *
     * @param taskNo 对外任务号
     * @return 任务；不存在时为空
     */
    Optional<VideoTask> findByTaskNo(TaskNo taskNo);

    /**
     * 查同工作区、且还在排队或执行中的任务——提交查重用。命中就说明同一份输入已经有任务在跑，
     * 重复提交该拒掉。
     *
     * @param workspaceKey 工作区短哈希
     * @return 进行中的既有任务；没有时为空
     */
    Optional<VideoTask> findActiveByWorkspaceKey(WorkspaceKey workspaceKey);

    /**
     * 整体回写任务（状态迁移 / 阶段推进之后调用）：按 id 覆盖全部非空字段。
     *
     * @param task 已变更的任务（变更须经 {@code VideoTask} 的迁移方法产生，不要直接改字段）
     */
    void update(VideoTask task);
}
