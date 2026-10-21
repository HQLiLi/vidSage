package com.vidsage.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code subtitle_artifact} 表的行结构（建表语句见 resources/schema.sql）。仅 persistence/ 内部可见，
 * 对上由 {@code MybatisSubtitleArtifactRepository} 转换为领域对象 {@code SubtitleArtifact}，转换点只此一处。
 *
 * <p><b>生成列 {@code active_flag} 不映射</b>——它是 DB 算出来的（只有 READY 的行有值），
 * 插入与更新都不带它。
 */
@Getter
@Setter
@TableName("subtitle_artifact")
public class SubtitleArtifactEntity {

    /** 内部自增行 id，不对外。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属字幕任务（逻辑外键 → subtitle_task.id）。 */
    private Long taskId;

    /** 产出它的那条 run（审计；收口靠它定位）。 */
    private Long derivedFromRunId;

    /** 产物类型（ORIGINAL / CLEANED / TRANSLATED）。 */
    private ArtifactType artifactType;

    /** 语种；空串 = 无语言维度（NOT NULL DEFAULT ''）。 */
    private String lang;

    /** 同 (task,type,lang) 递增的版本号（MAX+1 生成）。 */
    private Integer revision;

    /** 产物状态。 */
    private SubtitleArtifactStatus status;

    /** 审计 / 溯源：内容继承自哪一版上游，可空。 */
    private Long derivedFromArtifactId;

    /** 关键词指纹；只有 CLEANED 有值，可空。 */
    private String keywordsFingerprint;

    /** segments 文件名（检查点 JSON）。 */
    private String segmentsFileName;

    /** subtitles 文件名（SRT 派生视图）。 */
    private String subtitlesFileName;

    /** context 文件名（只有 CLEANED 有）。 */
    private String contextFileName;

    /** terms 文件名（只有 TRANSLATED 有）。 */
    private String termsFileName;

    /** segments 成品文件的 sha256。 */
    private String segmentsSha256;

    /** subtitles 成品文件的 sha256。 */
    private String subtitlesSha256;

    /** context 成品文件的 sha256。 */
    private String contextSha256;

    /** terms 成品文件的 sha256。 */
    private String termsSha256;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
