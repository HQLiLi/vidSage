package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.infrastructure.persistence.entity.SubtitleTaskEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code subtitle_task} 表的数据访问接口：单表 CRUD 走 {@link BaseMapper}，
 * 批量刷 {@code updated_at} 额外给一条定向 {@code @Update}。
 *
 * <p>锁定读（{@code FOR SHARE}）由 {@code MybatisSubtitleTaskRepository} 在 Wrapper 上追加 {@code .last()}。
 */
@Mapper
public interface SubtitleTaskMapper extends BaseMapper<SubtitleTaskEntity> {

    /**
     * 批量刷 {@code updated_at}：一条 {@code IN (...)} 覆盖单条与批量两种调用，
     * 不在仓储里写循环。
     *
     * @param taskIds 要刷的任务 id 集合（非空）
     * @param at      本次收尾的时刻
     * @return 受影响行数
     */
    @Update("""
            <script>
            UPDATE subtitle_task
            SET updated_at = #{at}
            WHERE id IN
            <foreach collection="taskIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int touchUpdatedAt(@Param("taskIds") Collection<Long> taskIds, @Param("at") LocalDateTime at);
}
