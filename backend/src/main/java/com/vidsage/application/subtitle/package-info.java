/**
 * 字幕应用层：根包留服务门面与产物读写门面，管线构件都住在 {@code pipeline/}，读模型住在 {@code query/}。
 *
 * <p>根包的三个构件：{@code SubtitleLanguageService}（语种清单供给，有 web 调用面）、
 * {@code SubtitleArtifactStore}（产物版本的写路径与「完好的当前版」读路径，被管线、任务服务与
 * 产物查询共用）、{@code SubtitleArtifactQueryService}（产物清单与下载解析，有 web 调用面）。
 *
 * <p>{@code pipeline/} 装字幕管线本体 {@code SubtitlePipeline}（按 run 类型分派三条执行体）
 * 与它编排的三个阶段：{@code SubtitlePreparationCoordinator}（转写并产出原始字幕）、
 * {@code SubtitleCleanupCoordinator}（切分 + LLM 清洗，两段式第一段）、
 * {@code SubtitleTranslationCoordinator}（术语查证 + LLM 翻译，按需触发的第二段）。
 *
 * <p>三者都是<b>管线阶段</b>，不是用例入口：各自只被上级管线按顺序调用一次。
 *
 * <p>{@code query/} 装产物读模型：{@code ArtifactSnapshot}（一版产物）、
 * {@code ArtifactFileSnapshot}（一版里的一个文件位与大小）、{@code ArtifactDownload}（一次下载的路径与文件位）。
 */
package com.vidsage.application.subtitle;
