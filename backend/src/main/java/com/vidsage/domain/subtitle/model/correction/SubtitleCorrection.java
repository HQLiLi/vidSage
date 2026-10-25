package com.vidsage.domain.subtitle.model.correction;

import java.time.LocalDateTime;

/**
 * 用户对某一条字幕（原文，或某个语种的译文）的手动改动。
 *
 * <p><b>它是一层「覆盖」，不是新一版产物</b>：磁盘上的字幕文件永远保持机器输出，读取时才把纠错
 * 叠上去。所以这里记着 {@code sourceText}——保存那一刻机器给出的文本长什么样；
 * 事后拿它跟当前产物比，就知道这条改动还对不对得上（前端据此提示「已失效」）。
 *
 * <p><b>lang 一律收敛成空串</b>：原文纠错没有语种维度，而唯一键里有 {@code lang}，
 * MySQL 的唯一约束又不比较 {@code NULL}——放 {@code null} 进去，同一段就能插两条原文纠错。
 *
 * @param id             内部自增行 id；还没落库时为 {@code null}
 * @param taskId         所属字幕任务
 * @param segmentId      字幕段 id，形如 {@code seg-0-3520}
 * @param field          改的是原文还是译文
 * @param lang           译文纠错的目标语种短码；原文纠错固定空串
 * @param sourceText     保存这条纠错时机器给出的源文本；{@code null} 收敛成空串（列上是 NOT NULL）
 * @param correctedValue 用户改后的文本
 * @param createdAt      首次保存时间
 * @param updatedAt      最近一次保存时间
 */
public record SubtitleCorrection(Long id, long taskId, String segmentId,
                                 SubtitleCorrectionField field, String lang,
                                 String sourceText, String correctedValue,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {

    /**
     * 紧凑构造器只管住唯一键的形状：{@code field_type} 不能缺、{@code lang} 不能是 {@code null}、
     * 原文纠错不该带语种。值本身（空串、超长）由应用服务先拦，那属于「调用方传错」。
     */
    public SubtitleCorrection {
        if (field == null) {
            throw new IllegalStateException("纠错的 field 不能为空：只能是 TEXT 或 TRANSLATION");
        }
        lang = (lang == null || field == SubtitleCorrectionField.TEXT) ? "" : lang;
        if (field == SubtitleCorrectionField.TRANSLATION && lang.isBlank()) {
            throw new IllegalStateException("译文纠错必须带目标语种 lang：请传 BCP-47 短码，如 en");
        }
        sourceText = sourceText == null ? "" : sourceText;
    }
}
