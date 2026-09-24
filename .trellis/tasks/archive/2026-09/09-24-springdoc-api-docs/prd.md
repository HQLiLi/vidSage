# 接入 springdoc-openapi 接口文档

## Goal

为后端引入 springdoc-openapi 3.x，自动生成 OpenAPI 3 文档并提供 Swagger UI，零侵入既有 Controller

## Requirements

- 后端所有 REST 接口自动生成 OpenAPI 3 文档，无需为现有 Controller 逐个补注解
- 提供 JSON 文档端点与可交互的 Swagger UI 页面
- 版本约束（关键）：
  - 必须使用 springdoc-openapi **3.x** 线 —— 项目是 Spring Boot 4.1.1（Spring Framework 7），springdoc 2.x 与其不兼容，会启动报错
  - 具体补丁版本以 Maven Central 当时最新 3.x 为准，不凭记忆写死
  - **不引入 knife4j**：其最新版（4.5.0/4.6.0）基于 springdoc 2.x / Boot 3.x，官方尚未适配 Boot 4，引入会冲突

## Scope Boundary

- 不做 knife4j 式 UI 美化（等其适配 Boot 4 后另行任务）
- 不要求为现有 Controller 补 `@Operation` 等文档注解（可作为后续增强，不属于本任务验收项）
- 不改动任何既有接口的行为

## Acceptance Criteria

- [x] `backend/pom.xml` 引入 `springdoc-openapi-starter-webmvc-ui` 3.x（test scope 之外的默认 scope）
- [x] 应用启动后 `GET /v3/api-docs` 返回 OpenAPI 3 JSON，且包含现有 Controller 的接口定义
- [x] Swagger UI 页面可访问并正常渲染（默认路径 `/swagger-ui/index.html`）
- [x] `mvn test` 全绿（基线 16 个测试），证明既有接口行为未受影响

## Notes

- 轻量任务，PRD-only；接入本身零配置即可用，如实施中发现 Boot 4.1 兼容问题再回到规划阶段补充设计
- 后端规范见 `.trellis/spec/backend/index.md`（新增依赖需同步更新该文档的"技术栈基线"小节）
