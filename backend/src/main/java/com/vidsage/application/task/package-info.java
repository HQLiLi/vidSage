/**
 * 任务应用服务：任务提交/查询/管线编排与读模型——{@code TaskService}（提交与查询唯一入口）、
 * {@code TaskPipelineService}（异步阶段编排）、读模型 {@code TaskView} / {@code TaskArtifact}
 * / {@code TaskArtifactFile}、应用→web 的进度消息 {@code TaskProgressMessage}、
 * 异常 {@code TaskConflictException} / {@code TaskNotFoundException}、
 * 中立发布-订阅 {@code TaskSubscriptionRegistry} / {@code Subscription}。
 *
 * <p>订阅注册表由 web 层注册消费者（如「写 SSE emitter」的回调），但本包禁止出现任何 HTTP 类型
 * （{@code SseEmitter} 等只活在 web/sse/）——包内只认识「按 taskNo 把事件交给 Consumer」的中立契约。
 */
package com.vidsage.application.task;
