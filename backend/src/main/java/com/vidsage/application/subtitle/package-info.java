/**
 * 字幕应用服务：字幕产物的三段编排——{@code SubtitleService}（转写并产出原始字幕）、
 * {@code SubtitleCleanupService}（切分 + LLM 清洗，两段式第一段）、
 * {@code SubtitleTranslationService}（术语查证 + LLM 翻译，按需触发的第二段）。
 */
package com.vidsage.application.subtitle;
