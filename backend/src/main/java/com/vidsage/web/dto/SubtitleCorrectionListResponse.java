package com.vidsage.web.dto;

import com.vidsage.application.subtitle.query.CorrectionView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 字幕纠错列表响应（GET /api/tasks/{taskNo}/corrections）。
 *
 * <p>外面包一层 {@code items} 而不是直接返回数组：将来加「分页 / 统计」时有地方放，
 * 前端也不用为「空列表」和「空响应体」写两种判断。
 *
 * @param items 纠错列表；一条都没有时为空表
 */
@Schema(description = "字幕纠错列表")
public record SubtitleCorrectionListResponse(
        @Schema(description = "该任务的全部纠错；一条都没有时为空数组") List<SubtitleCorrectionResponse> items) {

    /**
     * 应用层读模型列表 → 对外响应。
     *
     * @param views 应用层读模型
     */
    public static SubtitleCorrectionListResponse from(List<CorrectionView> views) {
        return new SubtitleCorrectionListResponse(views.stream()
                .map(SubtitleCorrectionResponse::from)
                .toList());
    }
}
