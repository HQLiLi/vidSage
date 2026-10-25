package com.vidsage.domain.subtitle.model.translation;

import com.vidsage.domain.subtitle.model.cleanup.SubtitleTrack;
import com.vidsage.domain.transcription.model.TranscriptionSegment;

/**
 * 一条双语段落：原文与译文配对存放，时间戳与输入段落逐条一致。
 *
 * <p><b>为什么原文与译文放同一条 record</b>：双语 SRT 渲染时同时用到两者，若拆成两条平行的
 * {@code List}，一旦长度不等就是<b>静默错位</b>——A 句的译文配到 B 句上，且不报错。
 *
 * <p><b>文本不兜底、不加 {@code @Schema}</b>：字段形状与 {@link SubtitleTrack} 的段落同源，序列化出的
 * JSON 就是落盘 {@code translated-segments-<lang>.json} 里数组元素的形状；{@code null} 文本
 * 按空串渲染，契约本身不收敛。
 *
 * <p><b>id 与原文一致</b>：译文是照着 CLEANED 的某一版逐条翻的，时间戳不变，id 也就该是同一条。
 * 翻译时从源段落原样带过来；旧产物没存 id 时按时间戳现算。
 *
 * @param beginTimeMs    本句开始时间（毫秒），与输入段落逐条一致
 * @param endTimeMs      本句结束时间（毫秒），与输入段落逐条一致
 * @param sourceText     清洗后的原文；{@code null} 在渲染时视为空串
 * @param translatedText 译文；等于 {@code sourceText} 表示无需翻译或整趟跳过（源语种等于目标语种）
 * @param id             这句字幕的稳定标识，形如 {@code seg-0-3520}；
 *                       旧产物里没存这个字段时按时间戳现算，不是 {@code null}
 */
public record TranslatedSegment(long beginTimeMs, long endTimeMs,
                                String sourceText, String translatedText, String id) {

    /**
     * 紧凑构造器只补 id（旧 JSON 没这个字段）：规则与原文段同一处，见
     * {@link TranscriptionSegment#syntheticId(long, long)}。
     */
    public TranslatedSegment {
        if (id == null || id.isBlank()) {
            id = TranscriptionSegment.syntheticId(beginTimeMs, endTimeMs);
        }
    }

    /**
     * 不带 id 的写法：id 按时间戳现算。
     *
     * @param beginTimeMs    本句开始时间（毫秒）
     * @param endTimeMs      本句结束时间（毫秒）
     * @param sourceText     清洗后的原文
     * @param translatedText 译文
     */
    public TranslatedSegment(long beginTimeMs, long endTimeMs, String sourceText, String translatedText) {
        this(beginTimeMs, endTimeMs, sourceText, translatedText, null);
    }
}
