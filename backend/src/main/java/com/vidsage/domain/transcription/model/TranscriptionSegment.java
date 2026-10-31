package com.vidsage.domain.transcription.model;

/**
 * 一句转写结果，时间戳用于前端点击引用跳转。
 *
 * <p><b>id 为什么由时间戳算出来</b>：切分是确定性的（同一份原文、同一个时长预算，边界不变），
 * 而清洗与翻译都不动时间戳，所以同一句在重跑后会拿到同一个 id——手动纠错靠它认句。
 * 切分真变了（换了时长预算、重新转写），id 自然对不上，旧纠错就显式失效，
 * 不会悄悄套到别的句子上。
 *
 * @param beginTimeMs 句子开始时间（毫秒） 如：0
 * @param endTimeMs   句子结束时间（毫秒） 如：3520
 * @param text        句子识别文本 如：今天天气不错
 * @param id          这句字幕的稳定标识，形如 {@code seg-0-3520}；
 *                    旧产物里没存这个字段时按时间戳现算，不是 {@code null}
 */
public record TranscriptionSegment(long beginTimeMs, long endTimeMs, String text, String id) {

    /**
     * 紧凑构造器只做一件事：id 缺失（旧 JSON 没这个字段）或空白时按时间轴补上。
     * 这样读旧产物的代码拿到的每条字幕都带 id，不必到处判空。
     */
    public TranscriptionSegment {
        if (id == null || id.isBlank()) {
            id = syntheticId(beginTimeMs, endTimeMs);
        }
    }

    /**
     * 不带 id 的写法（绝大多数构造点用这个）：id 按时间戳现算。
     *
     * @param beginTimeMs 句子开始时间（毫秒）
     * @param endTimeMs   句子结束时间（毫秒）
     * @param text        句子识别文本
     */
    public TranscriptionSegment(long beginTimeMs, long endTimeMs, String text) {
        this(beginTimeMs, endTimeMs, text, null);
    }

    /**
     * 按时间轴拼 id：{@code seg-<beginTimeMs>-<endTimeMs>}。
     *
     * <p>译文段也用同一套规则（{@code TranslatedSegment} 直接调这里），所以规则只写这一处，
     * 别在别的地方再拼一遍——两处拼法一旦漂移，纠错就永远对不上。
     *
     * @param beginTimeMs 句子开始时间（毫秒）
     * @param endTimeMs   句子结束时间（毫秒）
     * @return 形如 {@code seg-0-3520} 的 id
     */
    public static String syntheticId(long beginTimeMs, long endTimeMs) {
        return "seg-" + beginTimeMs + "-" + endTimeMs;
    }
}
