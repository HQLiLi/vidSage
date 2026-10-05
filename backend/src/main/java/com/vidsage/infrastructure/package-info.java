/**
 * 基础设施层：出站适配——实现 domain 的端口接口、持久化任务状态、驱动外部进程与大模型。
 * 按能力分包：{@code persistence}（MyBatis-Plus 持久化）、{@code process}（外部进程执行）、
 * {@code provider}（asr / audio / download / llm 各适配器实现）。
 *
 * <p>依赖规则：本层依赖 domain（实现端口、使用领域词汇），不依赖 application / web。
 * 豁免（directory-structure.md「依赖规则与豁免清单」正典，刻意不做接口脱壳）：
 * <ul>
 *   <li>a. application 可直接引用本层具体类：{@code VideoTaskRepository}、诊断编排经
 *       {@code ToolDiagnosticsService} 引用 {@code ExternalToolDiagnostics} / {@code ExternalToolReport}、
 *       {@code provider/llm/} 四个 worker（{@code SubtitleCleanser} / {@code SubtitleTranslator}
 *       / {@code SubtitleContextSummarizer} / {@code MessageHistory}）；</li>
 *   <li>b. web 可直接读本层 {@code ExternalToolReport}（{@code MediaController} 诊断端点）。</li>
 * </ul>
 * 正统化升级触发条件：出现第二个存储实现或本地模型部署——届时再抽端口、倒置依赖。
 */
package com.vidsage.infrastructure;
