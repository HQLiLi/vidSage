package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.infrastructure.persistence.entity.SubtitleArtifactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code subtitle_artifact} 表的数据访问接口：单表 CRUD 走 {@link BaseMapper}，
 * {@code revision} 取最大与全部定向 UPDATE（换版落定 / 取代 / 回收 / 收口置位）额外手写。
 */
@Mapper
public interface SubtitleArtifactMapper extends BaseMapper<SubtitleArtifactEntity> {

    /**
     * 取该 {@code (task, artifact_type, lang)} 当前最大 {@code revision}；没有行时为 {@code null}。
     * {@code revision = MAX + 1} 在仓储里算。
     *
     * @param taskId       所属字幕任务
     * @param artifactType 产物类型
     * @param lang         语种（空串 = 无语言维度）
     * @return 最大 revision；该组合从未产出时为空
     */
    @Select("SELECT MAX(revision) FROM subtitle_artifact "
            + "WHERE task_id = #{taskId} AND artifact_type = #{artifactType} AND lang = #{lang}")
    Integer selectMaxRevision(@Param("taskId") long taskId,
                              @Param("artifactType") ArtifactType artifactType,
                              @Param("lang") String lang);

    /**
     * 换版落定：写四列文件列 + 四列哈希列 + 置 {@code READY}，同一条 UPDATE
     * （写入点 = 换版落定那条 UPDATE）。字段取自实体属性。
     *
     * @param entity 已 completeCreation 的实体（id 与八个文件/哈希值齐全）
     * @return 受影响行数
     */
    @Update("""
            UPDATE subtitle_artifact
            SET segments_file_name = #{segmentsFileName},
                subtitles_file_name = #{subtitlesFileName},
                context_file_name = #{contextFileName},
                terms_file_name = #{termsFileName},
                segments_sha256 = #{segmentsSha256},
                subtitles_sha256 = #{subtitlesSha256},
                context_sha256 = #{contextSha256},
                terms_sha256 = #{termsSha256},
                status = 'READY'
            WHERE id = #{id}
            """)
    int updateReady(SubtitleArtifactEntity entity);

    /**
     * 覆盖型取代：{@code SUPERSEDED} + 清空四列文件列与四列哈希列。
     *
     * <p>{@code AND status = 'READY'} 是**状态守卫**，不是冗余条件：只有当前版会被取代。
     * 少了它，一个传错的 id（比如已经作废过的行）会被再清一次列，而清列是不可逆的
     * ——下游译文是孤儿型，文件列是回收时定位文件的唯一线索。
     *
     * @param artifactId 产物行 id
     * @return 受影响行数
     */
    @Update("""
            UPDATE subtitle_artifact
            SET status = 'SUPERSEDED',
                segments_file_name = NULL, subtitles_file_name = NULL,
                context_file_name = NULL, terms_file_name = NULL,
                segments_sha256 = NULL, subtitles_sha256 = NULL,
                context_sha256 = NULL, terms_sha256 = NULL
            WHERE id = #{artifactId} AND status = 'READY'
            """)
    int markSupersededAndClearFiles(@Param("artifactId") long artifactId);

    /**
     * 孤儿型取代（级联）：某任务某类型当前有效的产物一次全标 {@code SUPERSEDED}，文件列留着。
     *
     * @param taskId 所属字幕任务
     * @param type   产物类型
     * @return 受影响行数
     */
    @Update("""
            UPDATE subtitle_artifact
            SET status = 'SUPERSEDED'
            WHERE task_id = #{taskId} AND artifact_type = #{type} AND status = 'READY'
            """)
    int markSupersededByTaskIdAndType(@Param("taskId") long taskId, @Param("type") ArtifactType type);

    /**
     * 标已回收（两阶段第二步）。
     *
     * <p>{@code AND status = 'SUPERSEDED'} 是**状态守卫**：只有「已作废、文件待删」的行才谈得上
     * 「已回收」。少了它，一个还 {@code READY} 的行会被标成 {@code DELETED}，而它登记的文件
     * 可能刚被当成当前版发出去。
     *
     * @param artifactId 产物行 id
     * @return 受影响行数
     */
    @Update("UPDATE subtitle_artifact SET status = 'DELETED' WHERE id = #{artifactId} AND status = 'SUPERSEDED'")
    int markDeleted(@Param("artifactId") long artifactId);

    /**
     * 收口置位：本次 run 插的、仍停在 {@code CREATING} 的行 → {@code FAILED}。
     *
     * @param runId 产出这些占位行的 run
     * @return 受影响行数
     */
    @Update("UPDATE subtitle_artifact SET status = 'FAILED' "
            + "WHERE derived_from_run_id = #{runId} AND status = 'CREATING'")
    int markCreatingAsFailedByRunId(@Param("runId") long runId);

    /**
     * 启动收口第二条：残留 {@code CREATING} → {@code FAILED}（无差别批量）。
     *
     * @return 受影响行数
     */
    @Update("UPDATE subtitle_artifact SET status = 'FAILED' WHERE status = 'CREATING'")
    int markCreatingAsFailed();
}
