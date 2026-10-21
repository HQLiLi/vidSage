package com.vidsage.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vidsage.domain.task.model.TaskRunStatus;
import com.vidsage.domain.task.model.TaskRunType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code task_run} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisTaskRunRepository} 转换为领域对象 {@code TaskRun}，转换点只此一处。
 *
 * <p><b>生成列 {@code active_flag} 不映射</b>——它是 DB 算出来的（只有 RUNNING 的行有值），
 * 插入与更新都不带它。枚举列按名映射（实体字段直接用 Java enum）。
 */
@Getter
@Setter
@TableName("task_run")
public class TaskRunEntity {

    /** 内部自增行 id，不对外。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属字幕任务（逻辑外键 → subtitle_task.id）。 */
    private Long taskId;

    /** 任务内递增序号（MAX+1 生成）。 */
    private Integer runNo;

    /** 执行类型（MAIN / RECLEAN / TRANSLATE）。 */
    private TaskRunType runType;

    /** 目标语种；只有 TRANSLATE 有值。 */
    private String targetLanguage;

    /** 执行状态。 */
    private TaskRunStatus status;

    /** 当前阶段名。 */
    private String currentStage;

    /** 失败原因（中文，面向用户）。 */
    private String errorMessage;

    /** 开始执行时间。 */
    private LocalDateTime startedAt;

    /** 终态时间。 */
    private LocalDateTime finishedAt;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
