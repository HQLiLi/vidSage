package com.vidsage.domain.subtitle.model.correction;

/**
 * 纠错改的是哪一项：原文还是某个语种的译文。
 *
 * <p>取值与 {@code subtitle_correction.field_type} 列一一对应（按枚举名落库）。
 * 对外 HTTP JSON 里叫 {@code text} / {@code translation}——<b>这组词形的两个方向都写在
 * {@link #parse} 与 {@link #token} 这里</b>：web 层只把客户端传来的词形原样转给应用层，
 * 应用层再调 {@code parse}。词形若在别处再抄一份，改一处就会漏另一处。
 */
public enum SubtitleCorrectionField {

    /** 原文纠错：改 CLEANED 这一段的 text，语种维度为空串。 */
    TEXT,

    /** 译文纠错：改某个语种 TRANSLATED 这一段的译文，必须带目标语种。 */
    TRANSLATION;

    /**
     * 对外词形 → 枚举：大小写不敏感，两侧空白先去掉。
     *
     * @param token 客户端传来的 {@code field}，取值 {@code text} 或 {@code translation}
     * @return 对应的枚举
     * @throws IllegalArgumentException 为空、或不是 {@code text} / {@code translation}
     */
    public static SubtitleCorrectionField parse(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("field 不能为空：只能是 text（原文）或 translation（译文）");
        }
        String trimmed = token.trim();
        if ("text".equalsIgnoreCase(trimmed)) {
            return TEXT;
        }
        if ("translation".equalsIgnoreCase(trimmed)) {
            return TRANSLATION;
        }
        throw new IllegalArgumentException("未知的 field：" + token
                + "；只能是 text（原文）或 translation（译文）");
    }

    /**
     * 枚举 → 对外词形。
     *
     * @return {@code text} 或 {@code translation}
     */
    public String token() {
        return switch (this) {
            case TEXT -> "text";
            case TRANSLATION -> "translation";
        };
    }
}
