package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.subtitle.TaskRun;
import com.vidsage.domain.subtitle.port.TaskRunRepository;
import com.vidsage.infrastructure.persistence.entity.TaskRunEntity;
import com.vidsage.infrastructure.persistence.mapper.TaskRunMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * {@code task_run} 表的 MyBatis-Plus 实现：包装 {@link TaskRunMapper}，做「实体 ↔ 领域对象」
 * 的双向转换（转换点全项目只此一处）。
 */
@Repository
public class MybatisTaskRunRepository implements TaskRunRepository {

    private final TaskRunMapper mapper;

    public MybatisTaskRunRepository(TaskRunMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插一条 {@code RUNNING} 的 run——插入即抢互斥：先按 {@code MAX+1} 生成 {@code run_no}
     * 并回填，再 INSERT；撞 {@code uk_task_run_task_id_active_flag} 时返回空（抢失败，调用方翻 409）。
     *
     * <p>catch 必须在本方法内、不外抛：不跨 Spring 事务代理边界就不会把事务标成 rollback-only。
     *
     * @param run 待插的新 run（经 {@code TaskRun.of} 创建）
     * @return 回填了数据库 id 与 run_no 的 run；撞互斥键时为空
     */
    public Optional<TaskRun> insertRunning(TaskRun run) {
        Integer maxRunNo = mapper.selectMaxRunNo(run.getTaskId());
        TaskRunEntity entity = toEntity(run);
        entity.setRunNo((maxRunNo == null ? 0 : maxRunNo) + 1);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 撞 uk_active_run：该 task 已有 RUNNING 行，抢互斥失败。撞键是预期分支不是异常，故不外抛。
            return Optional.empty();
        }
        return Optional.of(toDomain(entity));
    }

    /**
     * 查最近一条 run（含正在跑的那条），按 {@code run_no} 倒序取一条。
     *
     * @param taskId 所属字幕任务
     * @return 最近一条 run；从未执行时为空
     */
    public Optional<TaskRun> findLatestByTaskId(long taskId) {
        TaskRunEntity entity = mapper.selectOne(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getTaskId, taskId)
                .orderByDesc(TaskRunEntity::getRunNo)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 全量回写 run（阶段推进 / 收口之后调用）：按 id 覆盖全部字段。
     *
     * @param run 已变更的 run（须经 {@code TaskRun} 的迁移方法产生变更）
     * @return 受影响行数
     */
    public int update(TaskRun run) {
        return mapper.updateById(toEntity(run));
    }

    /**
     * 启动收口第一条：先取残留 {@code RUNNING} 的 task_id，再一条 UPDATE 全部收成
     * {@code INTERRUPTED}。两条语句、无循环——启动收口在单线程阶段跑，读与写之间没有并发。
     *
     * @param at 启动收口的时刻
     * @return 被收掉的 {@code task_id}（去重）
     */
    public List<Long> interruptAllRunning(LocalDateTime at) {
        List<Long> taskIds = mapper.selectRunningTaskIds();
        mapper.interruptRunning(at);
        return taskIds;
    }

    private static TaskRun toDomain(TaskRunEntity entity) {
        return TaskRun.restore(entity.getId(), entity.getTaskId(), entity.getRunNo(), entity.getRunType(),
                entity.getTargetLanguage(), entity.getStatus(), entity.getCurrentStage(),
                entity.getErrorMessage(), entity.getStartedAt(), entity.getFinishedAt(), entity.getCreatedAt());
    }

    private static TaskRunEntity toEntity(TaskRun run) {
        TaskRunEntity entity = new TaskRunEntity();
        entity.setId(run.getId());
        entity.setTaskId(run.getTaskId());
        entity.setRunNo(run.getRunNo());
        entity.setRunType(run.getRunType());
        entity.setTargetLanguage(run.getTargetLanguage());
        entity.setStatus(run.getStatus());
        entity.setCurrentStage(run.getCurrentStage());
        entity.setErrorMessage(run.getErrorMessage());
        entity.setStartedAt(run.getStartedAt());
        entity.setFinishedAt(run.getFinishedAt());
        entity.setCreatedAt(run.getCreatedAt());
        return entity;
    }
}
