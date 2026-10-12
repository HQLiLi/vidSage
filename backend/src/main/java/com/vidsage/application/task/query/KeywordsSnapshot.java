package com.vidsage.application.task.query;

import java.util.List;

/**
 * 关键词读模型（零 web 注解）：按 taskNo 查工作区关键词的返回形状。
 *
 * <p>为什么不直接返回 {@code web/dto/SubtitleKeywordsResponse}：application 不依赖 web
 * （依赖规则）。web 侧由 {@code SubtitleKeywordsResponse.from} 映射——与 {@code TaskSnapshot}
 * → {@code TaskResponse} 同一分工。
 *
 * @param values     关键词清单（用户填写顺序，归一化后的干净一份）；从没填过时为空表
 * @param fingerprint 当前清单的指纹（16 位十六进制，与产物里的 keywordsFingerprint 同算法）
 */
public record KeywordsSnapshot(List<String> values, String fingerprint) {
}
