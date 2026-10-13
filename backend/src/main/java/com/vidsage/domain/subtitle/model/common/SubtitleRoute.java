package com.vidsage.domain.subtitle.model.common;

/**
 * 字幕来源路线，也是字幕落盘产物中该字段的取值域。
 *
 * <p>当前仅 {@link #ASR_CLOUD} 实际产出；{@link #PLATFORM_MANUAL}、{@link #PLATFORM_AUTO}、
 * {@link #ASR_LOCAL} 为预留值，用于冻结契约。将来接入平台字幕或本地 ASR 时，只新增处理管线
 * 并映射到已有枚举值，不扩展本枚举取值域，从而避免已落盘产物和消费代码被迫调整。
 */
public enum SubtitleRoute {

    /** 预留，当前不产出。 */
    PLATFORM_MANUAL,

    /** 预留，当前不产出。 */
    PLATFORM_AUTO,

    /** 预留，当前不产出。 */
    ASR_LOCAL,

    /** 当前唯一产出的路线。 */
    ASR_CLOUD
}