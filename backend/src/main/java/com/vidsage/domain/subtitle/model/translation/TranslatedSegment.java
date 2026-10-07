package com.vidsage.domain.subtitle.model.translation;

import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;

/**
 * 一条双语段落：原文与译文配对存放，时间戳与输入段落逐条一致。
 *
 * <p><b>为什么原文与译文放同一条 record</b>：双语 SRT 渲染时逐条同时用到两者，若拆成两条平行的
 * {@code List}，一旦长度不等就是<b>静默错位</b>——A 句的译文会被配到 B 句上，且不报错。配对存放
 * 让错位在类型上不可能发生。
 *
 * <p><b>不兜底、不加 {@code @Schema}</b>：字段形状与 {@link SubtitleTrack} 的段落同源，序列化出来
 * 的 JSON 就是落盘 {@code segments-<route>-translate-<target>.json} 里数组元素的形状；{@code null}
 * 文本按空串渲染（同 {@code SrtRenderer.toSrt} 的处理），契约本身不收敛。
 *
 * @param beginTimeMs    本句开始时间（毫秒），与输入段落逐条一致
 * @param endTimeMs      本句结束时间（毫秒），与输入段落逐条一致
 * @param sourceText     清洗后的原文；{@code null} 在渲染时视为空串
 * @param translatedText 译文；等于 {@code sourceText} 表示无需翻译或整趟跳过（源语种等于目标语种）
 */
public record TranslatedSegment(long beginTimeMs, long endTimeMs,
                                String sourceText, String translatedText) {
}
