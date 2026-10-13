/**
 * 端口适配器：domain 各端口接口的具体实现与 LLM worker——
 * {@code asr/dashscope}（{@code DashScopeAsrProvider}）、
 * {@code audio/ffmpeg}（{@code FfmpegAudioExtractor}）、
 * {@code download/local} 与 {@code download/ytdlp}
 * （{@code LocalFileMediaAcquirer} / {@code YtDlpMediaAcquirer}）、
 * {@code llm/}（字幕清洗/翻译/上下文摘要 worker：{@code SubtitleCleanser} / {@code SubtitleTranslator}
 * / {@code SubtitleContextSummarizer} / {@code MessageHistory}；术语查证 {@code termlookup/dashscope}）。
 *
 * <p>供应商缝由 Spring AI {@code ChatClient} 与 Spring 按类型的自动装配承担，本层不另建
 * 抽象接口（quality-guidelines：不预先抽接口）。
 */
package com.vidsage.infrastructure.provider;
