package com.vidsage.infrastructure.persistence.entity;

import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code task} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisVideoTaskRepository} 转换为领域对象 {@code VideoTask}，转换点只此一处
 * （directory-structure.md「持久化三层」）。
 */
@Getter
@Setter
@TableName("task")
public class VideoTaskEntity {

    /** 内部自增行 id，不对外（对外只暴露 taskNo）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外任务号：32 位短 UUID（唯一索引 uq_task_task_no）。 */
    private String taskNo;

    /** 输入类型：URL 提交 / 文件上传。 */
    private TaskInputType inputType;

    /**
     * 来源：URL 原文或上传文件原始名。<b>约束：本字段值不得直接写入日志</b>——含用户原始文件名，
     * 属敏感信息。
     */
    private String sourceUri;

    /** 工作区短哈希（索引 idx_task_workspace_key，提交查重用）。 */
    private String workspaceKey;

    /** 工作区目录相对路径（相对 vidsage.workspace.root）。 */
    private String workspaceDir;

    /** 任务状态（状态机权威存储）。 */
    private TaskStatus status;

    /** 最近阶段名（与 StageProgress.stage 同词表）。 */
    private String currentStage;


    /** 终态错误信息（中文，面向用户；主任务失败或翻译子状态失败）。 */
    private String errorMessage;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最近变更时间。 */
    private LocalDateTime updatedAt;

    /** 开始执行时间。 */
    private LocalDateTime startedAt;

    /** 终态时间。 */
    private LocalDateTime finishedAt;
}
