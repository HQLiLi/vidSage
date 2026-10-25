/**
 * 字幕应用层：根包留服务门面与产物读写门面，管线构件都住在 {@code pipeline/}，读模型住在 {@code query/}。
 *
 * <p>根包的六个构件：{@code SubtitleLanguageService}（语种清单供给，有 web 调用面）、
 * {@code SubtitleArtifactStore}（产物版本的写路径与「完好的当前版」读路径，被三个 run 用例与
 * 产物查询共用）、{@code SubtitleArtifactQueryService}（产物清单与下载解析，有 web 调用面）、
 * {@code SubtitleCorrectionService}（手动纠错的用例入口，有 web 调用面）、
 * {@code SubtitleCorrectionOverlay}（纠错合并入口，页面 / 下载 / 翻译取源文本三处共用）、
 * {@code SubtitleTrackReader}（按任务读磁盘上的 CLEANED / TRANSLATED 机器产物）。
 *
 * <p>{@code pipeline/} 装管线的四个阶段构件：{@code SubtitleFingerprintCoordinator}（内容指纹探测，
 * 在媒体获取之前）、{@code SubtitlePreparationCoordinator}（转写并产出原始字幕）、
 * {@code SubtitleCleanupCoordinator}（切分 + LLM 清洗，两段式第一段）、
 * {@code SubtitleTranslationCoordinator}（术语查证 + LLM 翻译，按需触发的第二段）。
 *
 * <p>四个都是<b>管线阶段</b>，不是用例入口：各自只被上级用例按顺序调用，自己没有 web 调用面。
 * 驱动它们的用例住在 {@code application/task/}，按 run 类型分成三个服务。
 *
 * <p>{@code query/} 装产物读模型：{@code ArtifactSnapshot}（一版产物）、
 * {@code ArtifactFileSnapshot}（一版里的一个文件位与大小）、{@code ArtifactDownload}（一次下载的路径与文件位）、
 * {@code CorrectionView}（一条纠错 + 「还对不对得上当前字幕」两条判据）。
 */
package com.vidsage.application.subtitle;
