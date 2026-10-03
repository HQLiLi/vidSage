package com.vidsage.model.subtitle;

import java.util.List;

/**
 * 一份翻译产物的全部内容：路线 + 源/目标语种 + 是否跳过 + 逐条双语段落。
 *
 * <p>对照 {@link SubtitleTrack}——它序列化出的 JSON 就是落盘的
 * {@code segments-<route>-translate-<target>.json} 的形状，也是翻译阶段的检查点。
 *
 * @param route          字幕来源路线（与产物文件名里的 token 同源）
 * @param sourceLanguage 判定跳过时实际采用的源语种（BCP-47 短码）：优先取
 *                       {@link SubtitleContext#sourceLanguage()}，为空时回退
 *                       {@link SubtitleTrack#language()}，两者皆空则为<b>空串</b>。
 *                       落盘判定当时的取值，事后能核对「为什么跳过 / 为什么没跳」
 * @param targetLanguage 目标语种（BCP-47 短码），来自配置
 * @param skipped        <b>是否没调用 LLM 就产出</b>（源语种等于目标语种时为 {@code true}）。
 *                       之所以要单独存：跳过与「翻译了但译文恰好等于原文」是两回事，光看两个语种
 *                       字段得自己去比对，存一个布尔让产物自己说明 LLM 有没有跑过，更可靠
 * @param segments       逐条双语段落，时间升序；无段落时为<b>空列表</b>而非 {@code null}
 */
public record TranslatedTrack(SubtitleRoute route, String sourceLanguage, String targetLanguage,
                              boolean skipped, List<TranslatedSegment> segments) {
}
