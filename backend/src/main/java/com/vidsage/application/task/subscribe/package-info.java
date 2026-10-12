/**
 * 任务进度的中立发布-订阅：application 层管线广播事件、web 层挂「写 emitter」回调，两边都依赖它。
 *
 * <p><b>包内禁止出现 HTTP 类型</b>（{@code SseEmitter} 等只活在 web/sse/），也禁止业务编排
 * （阶段顺序、状态迁移都归 application/）——本包只认识「按 taskNo 把事件交给 Consumer」的中立契约，
 * 判据是「写得出它不许装什么」：把 Spring 容器拿掉它照样成立。
 */
package com.vidsage.application.task.subscribe;
