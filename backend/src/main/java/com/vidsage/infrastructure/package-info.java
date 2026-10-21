/**
 * 基础设施层：两类居民。① <b>出站适配</b>——实现 domain 与 application 定义的端口接口、持久化
 * 任务状态、驱动外部进程与大模型；② <b>通用技术机制</b>——不面向业务契约、但有状态或需要
 * Spring 容器的机制类（{@code process} 的外部进程执行、{@code concurrency} 的任务并发闸）。
 * 按能力分包：{@code persistence}（MyBatis-Plus 持久化）、{@code process}（外部进程执行）、
 * {@code fs}（本机文件系统的读数与目录定位/创建）、{@code concurrency}（进程内执行机制）、
 * {@code provider}（asr / audio / download / llm 各适配器实现）。
 *
 * <p>与 {@code shared} 的分界看<b>有没有状态</b>：无状态纯函数工具进 {@code shared}；有状态、或
 * 需要容器的，即使不面向业务契约也进本层。反过来，有状态但只为某一层服务、没有复用意图的
 * （{@code web/sse/SseTaskProgressBroadcaster} 这类）留在服务它的层，不上提到这里。
 *
 * <p>依赖规则：本层依赖 {@code domain} 与 {@code shared}。对 {@code application} 只开一个
 * <b>窄口</b>——仅为实现 application 定义的出站端口而 import 端口接口；<b>禁止</b> import
 * application 的用例服务（{@code application/&lt;上下文&gt;/*Service}）与 {@code web/} 的任何类型。
 * 反向调用用例服务会导致循环依赖与事务边界混乱，这条是硬线。
 */
package com.vidsage.infrastructure;
