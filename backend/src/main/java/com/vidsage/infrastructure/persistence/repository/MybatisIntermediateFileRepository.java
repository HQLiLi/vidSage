package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.subtitle.IntermediateFile;
import com.vidsage.domain.subtitle.model.IntermediateFileStatus;
import com.vidsage.domain.subtitle.port.IntermediateFileRepository;
import com.vidsage.infrastructure.persistence.entity.IntermediateFileEntity;
import com.vidsage.infrastructure.persistence.mapper.IntermediateFileMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code intermediate_file} 表的 MyBatis-Plus 实现：包装 {@link IntermediateFileMapper}，做
 * 「实体 ↔ 领域对象」的双向转换（转换点全项目只此一处）。
 *
 * <p>三条定向 UPDATE 的 {@code updated_at} 由调用方传入的时刻决定（{@code at}），仓储不自己取 {@code now()}——
 * 这样单测钉得住落位，编排层也能把同一次收尾的多个时间戳对齐。
 */
@Repository
public class MybatisIntermediateFileRepository implements IntermediateFileRepository {

    private final IntermediateFileMapper mapper;

    public MybatisIntermediateFileRepository(IntermediateFileMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 首次产出一条中间产物（{@code READY} 是唯一入口）；MP 的 insert 回填自增 id。
     *
     * @param file 待存的中间产物（经 {@code IntermediateFile.ofReady} 创建）
     * @return 回填了数据库 id 的中间产物
     */
    public IntermediateFile insertReady(IntermediateFile file) {
        IntermediateFileEntity entity = toEntity(file);
        mapper.insert(entity);
        return toDomain(entity);
    }

    /**
     * 取该 task 的全部中间产物（各种状态都含）。
     *
     * @param taskId 所属字幕任务
     * @return 全部中间产物；没有时是空列表
     */
    public List<IntermediateFile> findByTaskId(long taskId) {
        return mapper.selectList(Wrappers.<IntermediateFileEntity>lambdaQuery()
                        .eq(IntermediateFileEntity::getTaskId, taskId))
                .stream()
                .map(MybatisIntermediateFileRepository::toDomain)
                .toList();
    }

    /**
     * 复用判据前半句：该 task 的该文件名是否停在 {@code READY}；文件在不在由调用方查盘。
     *
     * @param taskId   所属字幕任务
     * @param fileName 工作区内文件名
     * @return {@code READY} 的那一行；没有时为空
     */
    public Optional<IntermediateFile> findReadyByTaskIdAndFileName(long taskId, String fileName) {
        IntermediateFileEntity entity = mapper.selectOne(Wrappers.<IntermediateFileEntity>lambdaQuery()
                .eq(IntermediateFileEntity::getTaskId, taskId)
                .eq(IntermediateFileEntity::getFileName, fileName)
                .eq(IntermediateFileEntity::getStatus, IntermediateFileStatus.READY));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 重新产出：{@code SUPERSEDED} / {@code DELETED} → {@code READY}，复用同一行。
     *
     * @param id 中间产物行 id
     * @param at 本次变更时刻（同句刷 updated_at）
     * @return 受影响行数
     */
    public int markReady(long id, LocalDateTime at) {
        return mapper.markReady(id, at);
    }

    /**
     * 内容变更级联：该 task 当前 {@code READY} 的中间产物全标 {@code SUPERSEDED}。
     *
     * @param taskId 所属字幕任务
     * @param at     本次变更时刻（同句刷 updated_at）
     * @return 受影响行数
     */
    public int markSupersededByTaskId(long taskId, LocalDateTime at) {
        return mapper.markSupersededByTaskId(taskId, at);
    }

    /**
     * 标已回收（两阶段）。
     *
     * @param id 中间产物行 id
     * @param at 本次变更时刻（同句刷 updated_at）
     * @return 受影响行数
     */
    public int markDeleted(long id, LocalDateTime at) {
        return mapper.markDeleted(id, at);
    }

    private static IntermediateFile toDomain(IntermediateFileEntity entity) {
        return IntermediateFile.restore(entity.getId(), entity.getTaskId(), entity.getFileName(),
                entity.getFileType(), entity.getStatus(), entity.getDerivedFromRunId(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static IntermediateFileEntity toEntity(IntermediateFile file) {
        IntermediateFileEntity entity = new IntermediateFileEntity();
        entity.setId(file.getId());
        entity.setTaskId(file.getTaskId());
        entity.setFileName(file.getFileName());
        entity.setFileType(file.getFileType());
        entity.setStatus(file.getStatus());
        entity.setDerivedFromRunId(file.getDerivedFromRunId());
        entity.setCreatedAt(file.getCreatedAt());
        entity.setUpdatedAt(file.getUpdatedAt());
        return entity;
    }
}
