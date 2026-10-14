package com.vidsage.web.dto;

import com.vidsage.application.subtitle.query.LanguageOptionSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一个可选语种的对外形状（{@code GET /api/languages} 数组的元素）。
 *
 * @param code  回传给 {@code POST /api/tasks/{taskNo}/translate} 的短码
 * @param label 中文名，给前端下拉直接显示
 */
@Schema(description = "一个可选的翻译语种")
public record LanguageResponse(
        @Schema(description = "短码（标准大小写，如 zh-Hant），回传给翻译端点使用", example = "zh-Hant") String code,
        @Schema(description = "中文名，前端下拉直接显示", example = "繁体中文") String label) {

    /**
     * 应用层读模型 → 对外响应：注解集中在 web/dto，application 只给零注解的读模型。
     *
     * @param snapshot 应用层读模型
     */
    public static LanguageResponse from(LanguageOptionSnapshot snapshot) {
        return new LanguageResponse(snapshot.code(), snapshot.label());
    }
}
