package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.infrastructure.persistence.entity.WorkspaceEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code workspace} 表的数据访问接口：单表 CRUD 走 {@link BaseMapper}，
 * 指纹写入额外给一条定向 {@code @Update}（照 {@code SubtitleKeywordMapper.replaceByWorkspaceKey} 的样板）。
 *
 * <p>两个锁定读（{@code FOR SHARE}）由 {@code MybatisWorkspaceRepository} 在 Wrapper 上追加 {@code .last()}，
 * 不在这里另写 {@code @Select}。
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {

    /**
     * 写入内容指纹：一条 UPDATE 同时写 {@code duration_ms} / {@code published_at} 并同句刷 {@code updated_at}。
     *
     * @param workspaceId 内部行 id
     * @param durationMs  媒体时长（毫秒），可为 {@code null}
     * @param publishedAt 发布时间，可为 {@code null}
     * @param now         本次写入的时刻（同句刷 updated_at）
     * @return 受影响行数
     */
    @Update("""
            UPDATE workspace
            SET duration_ms = #{durationMs}, published_at = #{publishedAt}, updated_at = #{now}
            WHERE id = #{workspaceId}
            """)
    int updateFingerprint(@Param("workspaceId") long workspaceId,
                          @Param("durationMs") Integer durationMs,
                          @Param("publishedAt") LocalDateTime publishedAt,
                          @Param("now") LocalDateTime now);
}
