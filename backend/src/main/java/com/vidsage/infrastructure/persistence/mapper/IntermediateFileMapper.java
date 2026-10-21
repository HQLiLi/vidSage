package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.domain.intermediate.model.IntermediateFileType;
import com.vidsage.infrastructure.persistence.entity.IntermediateFileEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code intermediate_file} 表的数据访问接口：单表 CRUD 走 {@link BaseMapper}，
 * 三条定向 UPDATE（重新产出 / 级联作废 / 标回收）额外手写，每条同句刷 {@code updated_at}。
 */
@Mapper
public interface IntermediateFileMapper extends BaseMapper<IntermediateFileEntity> {

    /**
     * 重新产出：{@code SUPERSEDED} / {@code DELETED} → {@code READY}，同句刷 {@code file_type}、
     * {@code derived_from_run_id} 与 {@code updated_at}。
     *
     * @param id               中间产物行 id
     * @param fileType         中间产物类型
     * @param derivedFromRunId 本次产出它的 run；无则传 {@code null}
     * @param at               本次变更时刻
     * @return 受影响行数
     */
    @Update("UPDATE intermediate_file SET status = 'READY', file_type = #{fileType}, "
            + "derived_from_run_id = #{derivedFromRunId}, updated_at = #{at} WHERE id = #{id}")
    int markReady(@Param("id") long id, @Param("fileType") IntermediateFileType fileType,
                  @Param("derivedFromRunId") Long derivedFromRunId, @Param("at") LocalDateTime at);

    /**
     * 内容变更级联：该 task 当前 {@code READY} 的中间产物全标 {@code SUPERSEDED}，
     * 同句刷 {@code updated_at}。已作废 / 已回收的不回退，故按 {@code status = 'READY'} 过滤。
     *
     * @param taskId 所属字幕任务
     * @param at     本次变更时刻
     * @return 受影响行数
     */
    @Update("UPDATE intermediate_file SET status = 'SUPERSEDED', updated_at = #{at} "
            + "WHERE task_id = #{taskId} AND status = 'READY'")
    int markSupersededByTaskId(@Param("taskId") long taskId, @Param("at") LocalDateTime at);

    /**
     * 标已回收（两阶段：删文件之后），同句刷 {@code updated_at}。
     *
     * <p>{@code AND status = 'SUPERSEDED'} 是**状态守卫**：只有「已作废、文件待删」的行才谈得上
     * 「已回收」。少了它，一个还 {@code READY} 的有效缓存行会被标成 {@code DELETED}。
     *
     * @param id 中间产物行 id
     * @param at 本次变更时刻
     * @return 受影响行数
     */
    @Update("UPDATE intermediate_file SET status = 'DELETED', updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'SUPERSEDED'")
    int markDeleted(@Param("id") long id, @Param("at") LocalDateTime at);
}
