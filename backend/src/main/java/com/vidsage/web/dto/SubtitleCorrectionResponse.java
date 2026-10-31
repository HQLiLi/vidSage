package com.vidsage.web.dto;

import com.vidsage.application.subtitle.query.CorrectionView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 一条字幕纠错（保存响应、列表里的一项）。
 *
 * <p>两个布尔是给前端「该不该提示用户」用的：{@code matched=false} 表示字幕重新生成过、
 * 这条改动已失效；{@code sourceChanged=true} 表示保存时依据的源文本后来变了、这条改动可能需要复核
 * （原文纠错比机器文本，译文纠错比叠过原文纠错的生效原文）。
 *
 * @param segmentId     字幕段 id，形如 {@code seg-0-3520}
 * @param field         改哪一项：{@code text} / {@code translation}
 * @param lang          目标语种短码；原文纠错为空串
 * @param sourceText    保存这条纠错时用户看到的源文本：原文纠错是改前的机器原文，
 *                      译文纠错是当时叠过原文纠错的原文
 * @param value         用户改后的文本
 * @param matched       当前字幕里还有没有这一条（{@code false} = 已失效）
 * @param sourceChanged 依据的源文本是否已与当前文本不同（{@code true} = 需要复核）
 * @param updatedAt     最近一次保存时间
 */
@Schema(description = "一条字幕纠错")
public record SubtitleCorrectionResponse(
        @Schema(description = "字幕段 id", example = "seg-0-3520") String segmentId,
        @Schema(description = "改哪一项：text = 原文，translation = 译文", example = "text") String field,
        @Schema(description = "译文纠错的目标语种短码；原文纠错为空串", example = "en") String lang,
        @Schema(description = "保存这条纠错时用户看到的源文本：原文纠错是改前的机器原文，译文纠错是当时叠过原文纠错的原文")
        String sourceText,
        @Schema(description = "用户改后的文本", example = "今天天气不错") String value,
        @Schema(description = "当前字幕里是否还有这一条；false = 字幕已重新生成，该纠错已失效") boolean matched,
        @Schema(description = "依据的源文本是否已与当前文本不同；true = 该条纠错需要复核（译文纠错据此提示「译文待复核」）")
        boolean sourceChanged,
        @Schema(description = "最近一次保存时间") LocalDateTime updatedAt) {

    /**
     * 应用层读模型 → 对外响应。
     *
     * @param view 应用层读模型
     */
    public static SubtitleCorrectionResponse from(CorrectionView view) {
        return new SubtitleCorrectionResponse(view.segmentId(), view.field(),
                view.lang(), view.sourceText(), view.value(), view.matched(), view.sourceChanged(),
                view.updatedAt());
    }
}
