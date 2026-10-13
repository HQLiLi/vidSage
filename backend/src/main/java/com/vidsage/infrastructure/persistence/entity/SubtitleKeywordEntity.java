package com.vidsage.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code subtitle_keyword} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisSubtitleKeywordRepository} 转换为领域对象 {@code SubtitleKeywords}，
 * 转换点只此一处（directory-structure.md「持久化三层」）。
 *
 * <p><b>整份清单挤在一列 {@code keywords} 里</b>，不是一个词一行：
 * 唯一的查询是「按工作区取整份清单」，从来没有「按词反查哪些工作区用了它」；
 *
 * <p>Lombok {@code @Getter/@Setter}，不用 {@code @Data}——可变实体不需要 equals/hashCode/toString
 * 语义。
 */
@Getter
@Setter
@TableName("subtitle_keyword")
public class SubtitleKeywordEntity {

    /** 内部自增行 id，不对外。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工作区短哈希（唯一索引 uk_subtitle_keyword_workspace_key；关键词挂工作区不挂任务）。 */
    private String workspaceKey;

    /** 关键词原文，以 U+0001 连接，顺序即用户填写顺序；空清单存空串（不是 null）。 */
    private String keywords;

    /** 写入时间（覆盖写不刷新它——它记的是「这个工作区什么时候第一次填了关键词」）。 */
    private LocalDateTime createdAt;
}
