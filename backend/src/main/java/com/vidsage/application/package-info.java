/**
 * 应用服务层：用例编排、读模型、应用消息的家，按 task / media / transcription / subtitle /
 * diagnostics 分包。阶段顺序、断点复用等流程知识住在这里；领域规则本身仍归 {@code domain/}。
 *
 * <p>依赖规则：本层依赖 {@code domain} 与 {@code shared}，不依赖 {@code infrastructure} 与
 * {@code web}——对外部能力的调用一律经端口。本层也**定义业务无关的出站端口**
 * （如 {@code diagnostics/ToolDiagnostics}）：端口归谁定义看契约的语义职责，判据见
 * directory-structure.md「端口归属判据」。
 *
 * <p>2026-10-06 端口倒置前，本层曾按豁免清单 a 直接引用 5 处 infrastructure 具体类
 * （{@code VideoTaskRepository}、诊断的 {@code ExternalToolDiagnostics} / {@code ExternalToolReport}、
 * {@code provider/llm/} 的三个 LLM worker）。豁免清单已清空，本层现在只认端口。
 */
package com.vidsage.application;
