/**
 * 字幕管线构件：字幕产物的三段编排——{@code SubtitlePreparationStage}（转写并产出原始字幕）、
 * {@code SubtitleCleanupStage}（切分 + LLM 清洗，两段式第一段）、
 * {@code SubtitleTranslationStage}（术语查证 + LLM 翻译，按需触发的第二段）。
 *
 * <p>三者都是<b>管线阶段</b>，不是用例入口：各自只被上级管线按顺序调用一次，后缀统一为 {@code Stage}。
 */
package com.vidsage.application.subtitle;
