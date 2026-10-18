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
            WHERE id = #{artifactId}
            """)
    int markSupersededAndClearFiles(@Param("artifactId") long artifactId);

    /**
     * 孤儿型取代（级联）：只标 {@code SUPERSEDED}，文件列留着。
     *
     * @param artifactId 产物行 id
     * @return 受影响行数
     */
    @Update("UPDATE subtitle_artifact SET status = 'SUPERSEDED' WHERE id = #{artifactId}")
    int markSuperseded(@Param("artifactId") long artifactId);

    /**
     * 标已回收（两阶段第二步）。
     *
     * @param artifactId 产物行 id
     * @return 受影响行数
     */
    @Update("UPDATE subtitle_artifact SET status = 'DELETED' WHERE id = #{artifactId}")
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
