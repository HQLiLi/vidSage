package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.infrastructure.persistence.entity.TaskRunEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code task_run} 表的数据访问接口：单表 CRUD 走 {@link BaseMapper}，
 * {@code run_no} 取最大、启动收口两条额外手写。
 *
 * <p>撞 {@code uk_task_run_task_id_active_flag} 时抛的 {@code DuplicateKeyException} 由
 * {@code MybatisTaskRunRepository#insertRunning} 在本包内 catch（不外抛）。
 */
@Mapper
public interface TaskRunMapper extends BaseMapper<TaskRunEntity> {

    /**
     * 取该 task 当前最大 {@code run_no}；没有行时为 {@code null}。{@code run_no = MAX + 1} 在仓储里算。
     *
     * @param taskId 所属字幕任务
     * @return 最大 run_no；从未执行时为空
     */
    @Select("SELECT MAX(run_no) FROM task_run WHERE task_id = #{taskId}")
    Integer selectMaxRunNo(@Param("taskId") long taskId);

    /**
     * 取当前所有残留 {@code RUNNING} 行的 {@code task_id}（去重）——启动收口第一条要先知道收掉谁。
     *
     * @return 残留 RUNNING 的 task_id 列表；没有时是空列表
     */
    @Select("SELECT DISTINCT task_id FROM task_run WHERE status = 'RUNNING'")
    List<Long> selectRunningTaskIds();

    /**
     * 启动收口第一条：残留 {@code RUNNING} → {@code INTERRUPTED} + {@code finished_at}。
     *
     * @param at 启动收口的时刻
     * @return 受影响行数
     */
    @Update("UPDATE task_run SET status = 'INTERRUPTED', finished_at = #{at} WHERE status = 'RUNNING'")
    int interruptRunning(@Param("at") LocalDateTime at);
}
