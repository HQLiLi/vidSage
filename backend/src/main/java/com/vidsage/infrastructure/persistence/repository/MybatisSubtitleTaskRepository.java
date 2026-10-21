package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.port.SubtitleTaskRepository;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.infrastructure.persistence.entity.SubtitleTaskEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleTaskMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code subtitle_task} 表的 MyBatis-Plus 实现：包装 {@link SubtitleTaskMapper}，做「实体 ↔ 领域对象」
 * 的双向转换（转换点全项目只此一处）。值对象 {@code TaskNo} ↔ String 列的转换也收敛在此。
 */
@Repository
public class MybatisSubtitleTaskRepository implements SubtitleTaskRepository {

    private final SubtitleTaskMapper mapper;

    public MybatisSubtitleTaskRepository(SubtitleTaskMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 落一条新任务；MP 的 insert 会把数据库生成的自增 id 回填进实体，返回值携带该 id。
     *
     * @param task 待落库的新任务（经 {@code SubtitleTask.of} 创建）
     * @return 回填了数据库 id 的任务
     */
    public SubtitleTask insert(SubtitleTask task) {
        SubtitleTaskEntity entity = toEntity(task);
        mapper.insert(entity);
        return toDomain(entity);
    }

    /**
     * 按对外任务号查（{@code task_no} 唯一索引）。
     *
     * @param taskNo 对外任务号（值对象；查询条件取其 {@code value} 落到 DB 列）
     * @return 任务；不存在时为空
     */
    public Optional<SubtitleTask> findByTaskNo(TaskNo taskNo) {
        SubtitleTaskEntity entity = mapper.selectOne(Wrappers.<SubtitleTaskEntity>lambdaQuery()
                .eq(SubtitleTaskEntity::getTaskNo, taskNo.value()));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 按所属媒体查（{@code workspace_id} 唯一索引）。
     *
     * @param workspaceId 所属媒体的内部行 id
     * @return 任务；不存在时为空
     */
    public Optional<SubtitleTask> findByWorkspaceId(long workspaceId) {
        SubtitleTaskEntity entity = mapper.selectOne(Wrappers.<SubtitleTaskEntity>lambdaQuery()
                .eq(SubtitleTaskEntity::getWorkspaceId, workspaceId));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 按所属媒体<b>锁定读</b>（提交路径并发分支）：{@code FOR SHARE} 绕过一致性快照。
     *
     * @param workspaceId 所属媒体的内部行 id
     * @return 任务；不存在时为空
     */
    public Optional<SubtitleTask> findForShareByWorkspaceId(long workspaceId) {
        SubtitleTaskEntity entity = mapper.selectOne(Wrappers.<SubtitleTaskEntity>lambdaQuery()
                .eq(SubtitleTaskEntity::getWorkspaceId, workspaceId)
                .last("FOR SHARE"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 刷最近变更时刻。空集合是空操作，不发语句。
     *
     * @param taskIds 要刷的任务 id 集合；空集合是空操作
     * @param at      本次收尾的时刻
     * @return 受影响行数
     */
    public int touchUpdatedAt(Collection<Long> taskIds, LocalDateTime at) {
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        return mapper.touchUpdatedAt(taskIds, at);
    }

    private static SubtitleTask toDomain(SubtitleTaskEntity entity) {
        return SubtitleTask.restore(entity.getId(), new TaskNo(entity.getTaskNo()), entity.getWorkspaceId(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static SubtitleTaskEntity toEntity(SubtitleTask task) {
        SubtitleTaskEntity entity = new SubtitleTaskEntity();
        entity.setId(task.getId());
        entity.setTaskNo(task.getTaskNo().value());
        entity.setWorkspaceId(task.getWorkspaceId());
        entity.setCreatedAt(task.getCreatedAt());
        entity.setUpdatedAt(task.getUpdatedAt());
        return entity;
    }
}
