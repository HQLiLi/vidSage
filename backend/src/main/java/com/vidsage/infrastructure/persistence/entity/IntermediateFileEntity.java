package com.vidsage.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vidsage.domain.subtitle.model.IntermediateFileStatus;
import com.vidsage.domain.subtitle.model.IntermediateFileType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code intermediate_file} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisIntermediateFileRepository} 转换为领域对象 {@code IntermediateFile}，转换点只此一处。
 */
@Getter
@Setter
@TableName("intermediate_file")
public class IntermediateFileEntity {

    /** 内部自增行 id，不对外。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属字幕任务（逻辑外键 → subtitle_task.id）。 */
    private Long taskId;

    /** 工作区内文件名（唯一索引 uk_intermediate_file_task_id_file_name 的一半）。 */
    private String fileName;

    /** 中间产物类型（SOURCE_MEDIA / INFO_JSON / AUDIO）。 */
    private IntermediateFileType fileType;

    /** 产物状态（READY / SUPERSEDED / DELETED）。 */
    private IntermediateFileStatus status;

    /** 产出它的那条 run（审计，无承重用途），可空。 */
    private Long derivedFromRunId;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最近变更时间。 */
    private LocalDateTime updatedAt;
}
