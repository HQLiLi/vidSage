/**
 * 应用服务层：用例编排、读模型、应用消息的家，按 task / media / transcription / subtitle /
 * diagnostics 分包。阶段顺序、断点复用等流程知识住在这里；领域规则本身仍归 {@code domain/}。
 *
 * <p>依赖规则：正向 {@code web → application → domain}；infrastructure 依赖 domain，
 * 与本层无正向关系，仅按豁免清单反向引用其具体类——豁免清单 a（刻意不做接口脱壳）：
 * {@code VideoTaskRepository}（保持具体类，不预先抽接口）、诊断编排经 {@code ToolDiagnosticsService}
 * 引用 {@code ExternalToolDiagnostics} / {@code ExternalToolReport}、
 * {@code infrastructure/provider/llm/} 四个 worker（{@code SubtitleCleanser} /
 * {@code SubtitleTranslator} / {@code SubtitleContextSummarizer} / {@code MessageHistory}）。
 * 正统化升级触发条件：出现第二个存储实现或本地模型部署。
 */
package com.vidsage.application;
