/**
 * 任务应用层：任务提交 / 查询 / 执行机制的用例入口，子包按角色划分。
 *
 * <p>根包装四类：{@code SubtitleTaskService}（写用例：提交、上传、重生成、翻译）、
 * {@code SubtitleTaskQueryService}（读：任务视图、关键词、SSE 快照）、
 * {@code TaskRunSupervisor}（run 的机制类：插 run 抢互斥 / 推进 / 收口 / 启动收口）与
 * {@code SubtitleTaskFinder}（按任务号定位任务、它的媒体与工作目录，写读两侧共用）。
 *
 * <p>子包按角色划分：{@code port/} 装本层出站端口（{@code TaskConcurrencyGate}——执行闸门的契约，
 * 实现在 {@code infrastructure/concurrency}；{@code TaskProgressBroadcaster}——进度广播的契约，
 * 实现在 {@code web/sse}）；{@code query/} 装读模型（{@code TaskSnapshot} / {@code RunSummary} /
 * {@code KeywordsSnapshot}，零 web 注解——对外契约注解集中在 {@code web/dto}）；
 * {@code message/} 装应用消息（{@code TaskRunProgress}——application → web 的进度事件）；
 * {@code exception/} 装应用异常（{@code TaskConflictException} → 409、{@code TaskNotFoundException} → 404）。
 *
 * <p>管线本体 {@code SubtitlePipeline} 住在 {@code application/subtitle/pipeline/}，
 * 与它编排的三个阶段同包。
 */
package com.vidsage.application.task;
