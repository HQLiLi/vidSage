/**
 * 基础设施层：出站适配——实现 domain 与 application 定义的端口接口、持久化任务状态、
 * 驱动外部进程与大模型。按能力分包：{@code persistence}（MyBatis-Plus 持久化）、
 * {@code process}（外部进程执行）、{@code provider}（asr / audio / download / llm 各适配器实现）。
 *
 * <p>依赖规则：本层依赖 {@code domain} 与 {@code shared}。对 {@code application} 只开一个
 * <b>窄口</b>——仅为实现 application 定义的出站端口而 import 端口接口；<b>禁止</b> import
 * application 的用例服务（{@code application/&lt;上下文&gt;/*Service}）与 {@code web/} 的任何类型。
 * 反向调用用例服务会导致循环依赖与事务边界混乱，这条是硬线。
 *
 * <p>本层是<b>唯一实现端口的地方</b>：两类端口（domain 定义 / application 定义）都落在这里，
 * 命名 = 端口名加技术前缀（{@code MybatisVideoTaskRepository} / {@code LlmSubtitleCleanser} /
 * {@code ProcessToolDiagnostics}）。
 */
package com.vidsage.infrastructure;
