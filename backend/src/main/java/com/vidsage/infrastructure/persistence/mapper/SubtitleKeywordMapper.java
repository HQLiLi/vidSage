package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.infrastructure.persistence.entity.SubtitleKeywordEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code subtitle_keyword} 表的数据访问接口：单表 CRUD 走 {@link BaseMapper}，
 * 覆盖写额外给一条 {@code ON DUPLICATE KEY UPDATE}。
 *
 * <p><b>为什么覆盖写要手写这条 SQL</b>：MP 的 BaseMapper 没有 upsert，
 * 用「先 select 再 insert / updateById」凑出来的两语句在并发下会撞唯一索引；
 * MySQL 原生的 {@code ON DUPLICATE KEY UPDATE} 就是一条语句、原子、也是当前主流做法。
 * 只有这一条覆盖写偏离了纯 BaseMapper，读仍然走 Wrapper。
 */
@Mapper
public interface SubtitleKeywordMapper extends BaseMapper<SubtitleKeywordEntity> {

    /**
     * 按工作区覆盖式写入：有就只更 {@code keywords}，没有就插一行。
     * {@code created_at} 不参与更新——覆盖是「改关键词」，不是「重新填一次」。
     *
     * @param workspaceKey 工作区短哈希
     * @param keywords     整份清单（分隔符已由 {@code MybatisSubtitleKeywordRepository} 拼好，本接口只见字符串）
     * @param createdAt    首次写入时间
     * @return 受影响行数（MySQL 对 upsert 恒返回 1 或 2，具体语义与本项目无关）
     */
    @Insert("""
            INSERT INTO subtitle_keyword (workspace_key, keywords, created_at)
            VALUES (#{workspaceKey}, #{keywords}, #{createdAt})
            ON DUPLICATE KEY UPDATE keywords = #{keywords}
            """)
    int replaceByWorkspaceKey(@Param("workspaceKey") String workspaceKey,
                              @Param("keywords") String keywords,
                              @Param("createdAt") LocalDateTime createdAt);
}
