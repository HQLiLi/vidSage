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
 * 唯一的查询是「按任务取整份清单」，从来没有「按词反查哪些任务用了它」；
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

    /** 字幕任务的内部行 id（唯一索引 uk_subtitle_keyword_task_id；关键词挂任务不挂媒体）。 */
    private Long taskId;

    /** 关键词原文，以 U+0001 连接，顺序即用户填写顺序；空清单存空串（不是 null）。 */
    private String keywords;

    /** 首次写入时间（覆盖写不刷新它——它记的是「这个任务什么时候第一次填了关键词」）。 */
    private LocalDateTime createdAt;

    /** 最近一次覆盖写的时刻（与 keywords 同句刷；首次插入时与 createdAt 同值）。 */
    private LocalDateTime updatedAt;
}
