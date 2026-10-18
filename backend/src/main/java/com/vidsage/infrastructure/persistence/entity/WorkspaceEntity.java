package com.vidsage.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vidsage.domain.workspace.model.SourceType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code workspace} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisWorkspaceRepository} 转换为领域对象 {@code Workspace}，转换点只此一处
 * （directory-structure.md「持久化三层」）。
 *
 * <p>Lombok {@code @Getter/@Setter}，不用 {@code @Data}——可变实体不需要 equals/hashCode/toString 语义。
 */
@Getter
@Setter
@TableName("workspace")
public class WorkspaceEntity {

    /** 内部自增行 id，不对外。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源类型（URL / UPLOAD）。 */
    private SourceType sourceType;

    /** 身份键（URL 路线 bvid|p；上传路线内容 sha256）。 */
    private String sourceKey;

    /**
     * 来源：URL 原文或上传文件原始名。<b>约束：本字段值不得直接写入日志</b>——含用户原始文件名，
     * 属敏感信息。
     */
    private String sourceUri;

    /** 规范化后的 URL（只给人看）。 */
    private String canonicalUrl;

    /** 内容指纹之一：媒体时长（毫秒）。 */
    private Integer durationMs;

    /** 内容指纹之二：发布时间。 */
    private LocalDateTime publishedAt;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最近变更时间。 */
    private LocalDateTime updatedAt;
}
