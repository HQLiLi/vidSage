# AsrController 与 DTO 补 Swagger 注解文档

## Goal

用 io.swagger 注解（`@Tag` / `@Operation` / `@Schema` / `@ApiResponse`，方案 A）为现有 ASR 接口与请求/响应模型补中文文档描述，使 Swagger UI 从"裸接口"变为可读、可试的文档。

## Requirements

- 注解描述与现有 Javadoc 语义一致（Javadoc 是描述来源，注解是其文档化），全部中文
- 覆盖范围（共 4 个类 + 1 个错误响应）：
  - `AsrController`：类上 `@Tag`；`transcribe` 方法 `@Operation` + `@ApiResponse`（400 参数不合法，含 error 字段结构说明）
  - 内部 record `TranscriptionRequest`：record 与三个字段（audioUrl / audioFilePath / language）`@Schema`，写清"恰好传一个"的约束与示例值
  - `TranscriptionResult`：record 与字段 `@Schema`（language 可能为 null、segments 空时为空列表）
  - `TranscriptionSegment`：record 与三个时间/文本字段 `@Schema`（说明时间戳用于前端点击引用跳转）
- 约束：
  - 只加注解，不改任何业务逻辑、方法签名、既有多行 Javadoc
  - 不新增依赖（注解包随 springdoc 传递依赖已可用）
  - 描述需包含业务约束（如 audioUrl 与 audioFilePath 恰好传一个），而不是复述字段名

## Scope Boundary

- 不做方案 B/C 的 Therapi/Javadoc 自动导入（用户已明确选方案 A）
- 不动 AudioSource / TranscriptionCommand 等非 HTTP 契约模型（不出现在文档中）
- 不做全局 OpenAPI 信息配置（标题/版本等 springdoc 默认值可接受）

## Acceptance Criteria

- [x] Swagger UI 中接口分组、方法 summary、请求/响应 schema 均显示中文描述
- [x] `GET /v3/api-docs` 的 JSON 中能看到上述描述（info/paths/schemas 抽查）
- [x] `mvn test` 全绿（基线 16 个测试）
- [x] git diff 确认业务逻辑零改动（仅注解与 import 新增）
