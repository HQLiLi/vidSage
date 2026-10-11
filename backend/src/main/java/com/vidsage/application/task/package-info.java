/**
 * 任务应用层：任务提交 / 查询 / 管线编排（2026-10-05 起按角色分角色子包）。
 * 根包只留 {@code VideoTaskService}（提交与查询的唯一入口，也是本上下文唯一有 web 调用面的服务）
 * 与 {@code package-info}。
 *
 * <p>子包按角色划分：{@code pipeline/} 装管线构件（{@code SubtitlePipeline}——异步管线的本体，
 * 没有 web 调用面，只被上级按顺序调用）；{@code port/} 装本层出站端口
 * （{@code TaskConcurrencyGate}——执行闸门的契约，实现在 {@code infrastructure/concurrency}）；
 * {@code query/} 装读模型（{@code TaskSnapshot} /
 * {@code TaskArtifact} / {@code TaskArtifactFile}，面向查询的应用层投影，零 web 注解——对外契约注解
 * 集中在 {@code web/dto/TaskResponse}）；{@code message/} 装应用消息
 * （{@code TaskProgressMessage}——application → web 的进度消息，2026-10-10 从根迁入，
 * 与 {@code exception/} 对称）；{@code exception/} 装应用异常
 * （{@code TaskConflictException} → 409、{@code TaskNotFoundException} → 404）；{@code subscribe/}
 * 装中立发布-订阅（{@code TaskSubscriptionRegistry} / {@code Subscription}，由 web 层注册
 * 「写 SSE emitter」等回调；包内禁 HTTP 类型的承诺见该子包包说明）；{@code notify/} 装进度通知协作件
 * （{@code TaskProgressNotifier}——进度消息的单一出口：组装、落库、广播、终态清订阅只在它那里
 * 写一遍；与 {@code subscribe/} 的分工见该子包包说明）。
 */
package com.vidsage.application.task;
