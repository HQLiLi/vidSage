/**
 * 任务的进度通知协作件（现行成员 {@code TaskProgressNotifier}）：进度消息的单一出口——
 * 组装、落库、广播、终态清订阅这条序列只在本包写一遍。
 *
 * <p>与 {@code subscribe/} 的分工：一个管「谁能收」（注册与回调，在 {@code subscribe/}），
 * 一个管「什么时候送、送之前先落库」（本包）。
 *
 * <p><b>包内禁止出现 HTTP 类型</b>（{@code SseEmitter} 等只活在 web/sse/），也不装业务编排
 * （阶段顺序归管线，状态机的终态迁移 {@code succeed} / {@code fail} 等归调用点——本包只管到落库为止，
 * 不驱动状态机）；判据是「写得出它不许装什么」：把 Spring 容器拿掉它照样成立。
 */
package com.vidsage.application.task.notify;
