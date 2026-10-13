/**
 * 应用服务层：用例编排、读模型、应用消息的家，按 task / media / transcription / subtitle /
 * diagnostics 分包。阶段顺序、断点复用等流程知识住在这里；领域规则本身仍归 {@code domain/}。
 *
 * <p>依赖规则：本层依赖 {@code domain} 与 {@code shared}，不依赖 {@code infrastructure} 与
 * {@code web}——对外部能力的调用一律经端口。
 */
package com.vidsage.application;
