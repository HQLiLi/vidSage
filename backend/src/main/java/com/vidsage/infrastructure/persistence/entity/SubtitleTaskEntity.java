package com.vidsage.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code subtitle_task} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisSubtitleTaskRepository} 转换为领域对象 {@code SubtitleTask}，转换点只此一处。
 *
 * <p>{@code task_no} 在实体里是 {@code String} 列，值对象 {@code TaskNo} ↔ String 的转换收敛在 Repository。
 */
@Getter
@Setter
@TableName("subtitle_task")
public class SubtitleTaskEntity {

    /** 内部自增行 id，不对外。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外稳定号（32 位短 UUID，唯一索引 uk_subtitle_task_task_no）。 */
    private String taskNo;

    /** 所属媒体身份（逻辑外键 → workspace.id，唯一索引 uk_subtitle_task_workspace_id）。 */
    private Long workspaceId;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最近一次 run 收尾的时刻。 */
    private LocalDateTime updatedAt;
}
