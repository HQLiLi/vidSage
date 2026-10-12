/**
 * 任务的应用消息：application 层组装、web 层读取的进度消息（现行成员 {@code TaskProgressMessage}），
 * 与 {@code exception/} 同属「应用消息 / 应用异常」类目。
 *
 * <p><b>包内禁止出现 HTTP 类型</b>（{@code SseEmitter} 等只活在 web/sse/），也禁止业务编排
 * （阶段顺序、状态迁移都归上层的管线与调用点）——本包只装「一条消息长什么样」，
 * 判据是「写得出它不许装什么」：把 Spring 容器拿掉它照样成立。
 */
package com.vidsage.application.task.message;
