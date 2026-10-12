package com.vidsage.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 重新生成字幕请求体（POST /api/tasks/{taskNo}/subtitles/regenerate）。
 *
 * <p>是 HTTP 专属形状（只有 keywords 一个字段），不直接复用领域类型 {@code SubtitleKeywords}——
 * domain 词汇不进 web 契约，归一化与上限校验在应用层按需做。
 *
 * @param keywords 要替换的关键词清单。<b>{@code null}（字段缺席）= 不改变已有列表，按现有关键词重跑；
 *                 {@code []}（显式空数组）= 清空</b>。两者语义不同，写在 @Operation 里给前端看
 */
public record RegenerateSubtitlesRequest(
        @Schema(description = "要替换的关键词清单；null（字段缺席）= 不改变已有列表，[] = 清空",
                example = "[\"影视飓风\"]") List<String> keywords) {
}
