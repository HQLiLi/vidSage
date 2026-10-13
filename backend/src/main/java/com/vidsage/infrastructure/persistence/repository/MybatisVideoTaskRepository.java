package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.port.VideoTaskRepository;
import com.vidsage.infrastructure.persistence.entity.VideoTaskEntity;
import com.vidsage.infrastructure.persistence.mapper.VideoTaskMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code task} 表的 MyBatis-Plus 实现：包装 {@link VideoTaskMapper}，做「实体 ↔ 领域对象」
 * 的双向转换（转换点全项目只此一处）。
 */
@Repository
public class MybatisVideoTaskRepository implements VideoTaskRepository {

    private final VideoTaskMapper mapper;

    public MybatisVideoTaskRepository(VideoTaskMapper mapper) {
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
     * @param taskNo 对外任务号（值对象；查询条件取其 {@code value} 落到 DB 列）
     * @return 任务；不存在时为空
     */
    public Optional<VideoTask> findByTaskNo(TaskNo taskNo) {
        VideoTaskEntity entity = mapper.selectOne(
                Wrappers.<VideoTaskEntity>lambdaQuery().eq(VideoTaskEntity::getTaskNo, taskNo.value()));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 查同工作区且<b>仍在进行中</b>的任务：提交查重用——命中即说明同一输入已有任务在排队或执行，
     * 重复提交应被 409 拒绝。「什么算 active」是业务谓词，单源在
     * {@link VideoTask#ACTIVE_STATUSES}（PENDING / RUNNING），本方法只把它落成 SQL 的 status IN 条件。
     *
     * @param workspaceKey 工作区短哈希（值对象；查询条件取其 {@code value} 落到 DB 列）
     * @return 进行中的既有任务；没有时为空
     */
    public Optional<VideoTask> findActiveByWorkspaceKey(WorkspaceKey workspaceKey) {
        VideoTaskEntity entity = mapper.selectOne(Wrappers.<VideoTaskEntity>lambdaQuery()
                .eq(VideoTaskEntity::getWorkspaceKey, workspaceKey.value())
                .in(VideoTaskEntity::getStatus, VideoTask.ACTIVE_STATUSES)
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

    private static VideoTask toDomain(VideoTaskEntity entity) {
        return VideoTask.restore(entity.getId(), new TaskNo(entity.getTaskNo()), entity.getInputType(),
                entity.getSourceUri(), new WorkspaceKey(entity.getWorkspaceKey()), entity.getWorkspaceDir(),
                entity.getStatus(), entity.getCurrentStage(), entity.getTranslationStatus(),
                entity.getErrorMessage(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getStartedAt(), entity.getFinishedAt());
    }

    private static VideoTaskEntity toEntity(VideoTask task) {
        VideoTaskEntity entity = new VideoTaskEntity();
        entity.setId(task.getId());
        entity.setTaskNo(task.getTaskNo().value());
        entity.setInputType(task.getInputType());
        entity.setSourceUri(task.getSourceUri());
        entity.setWorkspaceKey(task.getWorkspaceKey().value());
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
