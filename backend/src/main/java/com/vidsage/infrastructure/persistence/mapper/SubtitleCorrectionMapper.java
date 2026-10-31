package com.vidsage.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.infrastructure.persistence.entity.SubtitleCorrectionEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code subtitle_correction} 表的数据访问接口：单表 CRUD 走 {@link BaseMapper}，
 * 保存额外给一条 {@code ON DUPLICATE KEY UPDATE}。
 *
 * <p><b>为什么保存要手写这条 SQL</b>：一个段落的一项改动只有一行（唯一键
 * {@code (task_id, segment_id, field_type, lang)}），用户改第二次是覆盖不是追加。
 * 「先查再插/改」凑出来的两语句在并发下会撞唯一索引；MySQL 原生的
 * {@code ON DUPLICATE KEY UPDATE} 就是一条语句、原子，与 {@code subtitle_keyword} 同款。
 */
@Mapper
public interface SubtitleCorrectionMapper extends BaseMapper<SubtitleCorrectionEntity> {

    /**
     * 按唯一键插入或更新：有就只更 {@code source_text}、{@code corrected_value} 与 {@code updated_at}，
     * 没有就插一行。
     *
     * <p>{@code created_at} 不参与更新——覆盖是「改这句话」，不是「重新记一次」；
     * 首次插入时它与 {@code updated_at} 取同一个 {@code now}，两列同值。
     * {@code source_text} 跟着更新，因为用户第二次保存时依据的源文本可能已经换了。
     *
     * @param taskId         字幕任务的内部行 id
     * @param segmentId      字幕段 id
     * @param fieldType      原文还是译文（按枚举名落库）
     * @param lang           目标语种；原文纠错为空串
     * @param sourceText     保存时依据的源文本（原文纠错是机器原文，译文纠错是当时用户看到的原文）
     * @param correctedValue 用户改后的文本
     * @param now            本次保存的时刻（首插时同时落 created_at 与 updated_at；覆盖时只刷 updated_at）
     * @return 受影响行数（MySQL 对 upsert 恒返回 1 或 2，具体语义与本项目无关）
     */
    @Insert("""
            INSERT INTO subtitle_correction
                (task_id, segment_id, field_type, lang, source_text, corrected_value, created_at, updated_at)
            VALUES
                (#{taskId}, #{segmentId}, #{fieldType}, #{lang}, #{sourceText}, #{correctedValue}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE source_text = #{sourceText},
                                    corrected_value = #{correctedValue},
                                    updated_at = #{now}
            """)
    int upsertByUniqueKey(@Param("taskId") long taskId,
                          @Param("segmentId") String segmentId,
                          @Param("fieldType") SubtitleCorrectionField fieldType,
                          @Param("lang") String lang,
                          @Param("sourceText") String sourceText,
                          @Param("correctedValue") String correctedValue,
                          @Param("now") LocalDateTime now);
}
