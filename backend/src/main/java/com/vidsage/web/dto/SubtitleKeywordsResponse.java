package com.vidsage.web.dto;

import com.vidsage.application.task.query.KeywordsSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 关键词查询响应（GET /api/tasks/{taskNo}/keywords）。
 *
 * <p><b>为什么返回 {@code fingerprint} 而不是让前端自己算</b>：指纹算法是后端的单源
 * （{@code SubtitleKeywords#computeFingerprint}），前端复制一份迟早漂移。前端拿它与
 * {@code segments-*-clean.json} 里的 {@code keywordsFingerprint} 比对，就知道
 * 「这份字幕对不对得上当前关键词」。
 *
 * @param keywords    该工作区的关键词清单（用户填写顺序原样返回）；从没填过时为空表
 * @param fingerprint 当前清单的指纹（16 位十六进制，与产物里那一栏同算法）
 */
@Schema(description = "关键词清单与指纹")
public record SubtitleKeywordsResponse(
        @Schema(description = "关键词清单（用户填写顺序）；从没填过时为空表",
                example = "[\"影视飓风\", \"Tim\"]") List<String> keywords,
        @Schema(description = "当前清单的指纹，与 segments-*-clean.json 的 keywordsFingerprint 同算法",
                example = "a1b2c3d4e5f60708") String fingerprint) {

    /**
     * 应用层读模型 → 对外响应：对外契约注解集中在 web/dto，application 只给零注解的读模型。
     *
     * @param snapshot 应用层读模型
     */
    public static SubtitleKeywordsResponse from(KeywordsSnapshot snapshot) {
        return new SubtitleKeywordsResponse(snapshot.values(), snapshot.fingerprint());
    }
}
