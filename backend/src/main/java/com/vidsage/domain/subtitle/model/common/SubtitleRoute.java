package com.vidsage.domain.subtitle.model.common;

/**
 * 这份字幕是从哪条路线来的，落盘到产物的取值域。
 *
 * <p><b>四值一次定死、含两个本轮产不出的值</b>：将来接平台字幕时只加产线、不改契约取值域，
 * 避免已落盘的产物与 MVP-3 的消费代码跟着改（见设计决策 3）。
 *
 * <p><b>本枚举无字段无方法</b>：文件名里的 kebab token（如 {@code ASR_CLOUD} → {@code asr-cloud}）
 * 由服务层从 {@code name()} 派生，与此处 {@code AudioContainer} 派生 {@code .m4a} 同构——派生规则
 * 只有定文件名的人知道，不写进契约。
 *
 * <p>命名是「路线」不是「实现」：{@code ASR_CLOUD} / {@code ASR_LOCAL} 命名的是「这份字幕的来源」，
 * 同一概念拆成两个枚举会制造两处真相源，故不另设「字幕来源」类型。
 */
public enum SubtitleRoute {

    /** 平台人工字幕。本轮不产出，保留取值以冻结契约域。 */
    PLATFORM_MANUAL,

    /** 平台自动字幕。本轮不产出，保留取值以冻结契约域。 */
    PLATFORM_AUTO,

    /** 本地 FunASR 转写。本轮不产出，保留取值以冻结契约域。 */
    ASR_LOCAL,

    /** 云端 DashScope 转写。本轮唯一产出的路线。 */
    ASR_CLOUD
}