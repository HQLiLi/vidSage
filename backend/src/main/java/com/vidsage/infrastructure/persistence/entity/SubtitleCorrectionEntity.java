package com.vidsage.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code subtitle_correction} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisSubtitleCorrectionRepository} 转换为领域对象 {@code SubtitleCorrection}，
 * 转换点只此一处。
 *
 * <p>Lombok {@code @Getter/@Setter}，不用 {@code @Data}——可变实体不需要 equals/hashCode/toString 语义。
 */
@Getter
@Setter
@TableName("subtitle_correction")
public class SubtitleCorrectionEntity {

    /** 内部自增行 id，不对外。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属字幕任务（逻辑外键 → subtitle_task.id）。 */
    private Long taskId;

    /** 字幕段 id，形如 {@code seg-0-3520}。 */
    private String segmentId;

    /** 改的是原文还是译文；列名 field_type，落库为枚举名 TEXT / TRANSLATION。 */
    private SubtitleCorrectionField fieldType;

    /** 译文纠错的目标语种短码；原文纠错为空串（列上 NOT NULL DEFAULT ''）。 */
    private String lang;

    /** 保存这条纠错时机器给出的源文本。 */
    private String sourceText;

    /** 用户改后的文本。 */
    private String correctedValue;

    /** 首次保存时间（覆盖写不刷新它）。 */
    private LocalDateTime createdAt;

    /** 最近一次保存的时刻（与 corrected_value 同句刷；首次插入时与 createdAt 同值）。 */
    private LocalDateTime updatedAt;
}
