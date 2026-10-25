/**
 * 任务应用层：任务提交 / 查询 / 执行机制的用例入口，子包按角色划分。
 *
 * <p>根包装六类：{@code SubtitleMainService}（MAIN run：提交与上传，跑到原文字幕就绪）、
 * {@code SubtitleRecleanService}（RECLEAN run：按当前关键词重跑清洗）、
 * {@code SubtitleTranslateService}（TRANSLATE run：在清洗产物上产一版译文）、
 * {@code SubtitleTaskQueryService}（读：任务视图、关键词、SSE 快照）、
 * {@code TaskRunSupervisor}（run 的机制类：插 run 抢互斥 / 推进 / 收口 / 启动收口）与
 * {@code SubtitleTaskFinder}（按任务号定位任务、它的媒体与工作目录，写读两侧共用）。
 *
 * <p>三个 run 用例各管一条流程，互不引用：从守卫、抢互斥、落库，到驱动管线阶段、收口，
 * 全在自己类里。
 *
 * <p>子包按角色划分：{@code port/} 装本层出站端口（{@code TaskConcurrencyGate}——执行闸门的契约，
 * 实现在 {@code infrastructure/concurrency}；{@code TaskProgressBroadcaster}——进度广播的契约，
 * 实现在 {@code web/sse}）；{@code query/} 装读模型（{@code TaskSnapshot} / {@code RunSummary} /
 * {@code KeywordsSnapshot}，零 web 注解——对外契约注解集中在 {@code web/dto}）；
 * {@code message/} 装应用消息（{@code TaskRunProgress}——application → web 的进度事件）；
 * {@code exception/} 装应用异常（{@code TaskConflictException} → 409、{@code TaskNotFoundException} → 404）。
 */
package com.vidsage.application.task;
