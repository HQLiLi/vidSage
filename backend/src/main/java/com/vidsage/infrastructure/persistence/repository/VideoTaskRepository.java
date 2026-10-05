package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.TaskStatus;
import com.vidsage.infrastructure.persistence.entity.VideoTaskEntity;
import com.vidsage.infrastructure.persistence.mapper.VideoTaskMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code task} 表对上（application/）的<b>唯一</b>持久化入口：包装 {@link VideoTaskMapper}，
 * 做「实体 ↔ 领域对象」的双向转换（转换点全项目只此一处）。
 *
 * <p>{@code @Repository} 具体类、不抽接口——单一存储实现下抽接口属于为以后可能用到的过度设计
 * （directory-structure.md「持久化三层」）。本类只做存取与转换，不写业务规则；
 * Wrapper 等 MP 类型不出本包。
 */
@Repository
public class VideoTaskRepository {

    private final VideoTaskMapper mapper;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param mapper task 表数据访问接口（MP BaseMapper）
     */
    public VideoTaskRepository(VideoTaskMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 落一条新任务（PENDING）；MP 的 insert 会把数据库生成的自增 id 回填进实体，
     * 返回值携带该 id，调用方以返回值为准。
     *
     * @param task 待落库的新任务（经 {@code VideoTask.newPending} 创建）
     * @return 回填了数据库 id 的任务
     */
    public VideoTask insert(VideoTask task) {
        VideoTaskEntity entity = toEntity(task);
        mapper.insert(entity);
        return toDomain(entity);
    }

    /**
     * 按对外任务号查询（task_no 唯一索引）。
     *
     * @param taskNo 对外任务号
     * @return 任务；不存在时为空
     */
    public Optional<VideoTask> findByTaskNo(String taskNo) {
        VideoTaskEntity entity = mapper.selectOne(
                Wrappers.<VideoTaskEntity>lambdaQuery().eq(VideoTaskEntity::getTaskNo, taskNo));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 查同工作区且<b>仍在进行中</b>（PENDING / RUNNING）的任务：提交查重用——
     * 命中即说明同一输入已有任务在排队或执行，重复提交应被 409 拒绝（design.md 数据流节）。
     *
     * @param workspaceKey 工作区短哈希
     * @return 进行中的既有任务；没有时为空
     */
    public Optional<VideoTask> findActiveByWorkspaceKey(String workspaceKey) {
        VideoTaskEntity entity = mapper.selectOne(Wrappers.<VideoTaskEntity>lambdaQuery()
                .eq(VideoTaskEntity::getWorkspaceKey, workspaceKey)
                .in(VideoTaskEntity::getStatus, List.of(TaskStatus.PENDING, TaskStatus.RUNNING))
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 全量回写任务（状态机迁移 / 阶段推进后调用）：按 id 覆盖全部非空字段。
     *
     * @param task 已变更的领域对象（须经 {@code VideoTask} 的迁移方法产生变更）
     */
    public void update(VideoTask task) {
        mapper.updateById(toEntity(task));
    }

    /**
     * 实体 → 领域对象。
     */
    private static VideoTask toDomain(VideoTaskEntity entity) {
        return VideoTask.restore(entity.getId(), entity.getTaskNo(), entity.getInputType(),
                entity.getSourceUri(), entity.getWorkspaceKey(), entity.getWorkspaceDir(),
                entity.getStatus(), entity.getCurrentStage(), entity.getTranslationStatus(),
                entity.getErrorMessage(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getStartedAt(), entity.getFinishedAt());
    }

    /**
     * 领域对象 → 实体。
     */
    private static VideoTaskEntity toEntity(VideoTask task) {
        VideoTaskEntity entity = new VideoTaskEntity();
        entity.setId(task.getId());
        entity.setTaskNo(task.getTaskNo());
        entity.setInputType(task.getInputType());
        entity.setSourceUri(task.getSourceUri());
        entity.setWorkspaceKey(task.getWorkspaceKey());
        entity.setWorkspaceDir(task.getWorkspaceDir());
        entity.setStatus(task.getStatus());
        entity.setCurrentStage(task.getCurrentStage());
        entity.setTranslationStatus(task.getTranslationStatus());
        entity.setErrorMessage(task.getErrorMessage());
        entity.setCreatedAt(task.getCreatedAt());
        entity.setUpdatedAt(task.getUpdatedAt());
        entity.setStartedAt(task.getStartedAt());
        entity.setFinishedAt(task.getFinishedAt());
        return entity;
    }
}
