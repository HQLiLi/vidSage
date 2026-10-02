package com.vidsage.persistence.entity;

import com.vidsage.model.task.TaskInputType;
import com.vidsage.model.task.TaskStatus;
import com.vidsage.model.task.TranslationStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code task} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code VideoTaskRepository} 转换为领域对象 {@code VideoTask}，转换点只此一处
 * （directory-structure.md「持久化三层」）。
 *
 * <p>普通类 + Lombok {@code @Getter/@Setter}（主 PRD 对实体类的既定形态，不用 {@code @Data}——
 * 可变实体不需要 equals/hashCode/toString 语义）。枚举列按枚举名映射：{@link TaskInputType} /
 * {@link TaskStatus} 的常量名与数据库 ENUM 值逐字相同。
 *
 * <p>时间戳由领域对象在状态迁移时产生，经 Repository 写入，本类不做 fill 策略。
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

    /** URL 原文或上传文件原始名（日志不落原值）。 */
    private String sourceUri;

    /** 工作区短哈希（索引 idx_task_workspace_key，提交查重用）。 */
    private String workspaceKey;

    /** 工作区目录相对路径（相对 vidsage.workspace.root）。 */
    private String workspaceDir;

    /** 任务状态（状态机权威存储）。 */
    private TaskStatus status;

    /** 最近阶段名（与 StageEvent.stage 同词表）。 */
    private String currentStage;

    /**
     * 翻译子状态（2026-10-02 两段式）：主任务 SUCCEEDED 后按需触发的第二生命周期；
     * 数据库 NULL = 从未触发，映射为 {@code null}。枚举名与 {@code task.translation_status}
     * 的 ENUM 值逐字相同。
     */
    private TranslationStatus translationStatus;

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
