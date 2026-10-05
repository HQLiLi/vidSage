/**
 * 任务应用服务：任务提交 / 查询 / 管线编排（2026-10-05 起按角色分角色子包，根只留服务门面与跨角色消息）。
 * 根包：{@code TaskService}（提交与查询唯一入口）、{@code TaskPipelineService}（异步阶段编排）、
 * 应用→web 的进度消息 {@code TaskProgressMessage}。
 *
 * <p>子包按角色划分：{@code query/} 装读模型（{@code TaskView} / {@code TaskArtifact} /
 * {@code TaskArtifactFile}，面向查询与 HTTP 的应用层投影）；{@code exception/} 装应用异常
 * （{@code TaskConflictException} → 409、{@code TaskNotFoundException} → 404）；{@code subscribe/}
 * 装中立发布-订阅（{@code TaskSubscriptionRegistry} / {@code Subscription}，由 web 层注册
 * 「写 SSE emitter」等回调；包内禁 HTTP 类型的承诺见该子包包说明）。
 */
package com.vidsage.application.task;
