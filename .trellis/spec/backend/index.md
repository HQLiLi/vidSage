# 后端开发规范

> VidSage 后端（Java 21 + Spring Boot 4.1.1 + Spring AI 2.0.1）的编码约定。
> 所有规则都以 `backend/` 下的真实代码为依据；写代码前先读对应文档。

---

## 规范索引

| 文档 | 内容 | 何时读 |
|------|------|--------|
| [directory-structure.md](./directory-structure.md) | 四层结构（domain / application / infrastructure / web）、依赖规则与端口归属判据、类与文件命名、方法名动词开头 | 新增模块、类或方法时 |
| [provider-pattern.md](./provider-pattern.md) | Provider 抽象 + 条件装配 + record 配置属性 | 新增或切换本地/云端实现时 |
| [error-handling.md](./error-handling.md) | 卫语句、异常类型选择、错误信息写法与语言 | 写任何校验与失败路径时 |
| [quality-guidelines.md](./quality-guidelines.md) | 契约设计、注释要求、测试写法、依赖原则、主流选型 | 写任何代码前 |
| [logging-guidelines.md](./logging-guidelines.md) | 日志选型、级别分层、脱敏红线 | 写任何日志语句前 |
| [database-guidelines.md](./database-guidelines.md) | 建表契约（六张表、索引与约束命名、枚举列类型）、MyBatis-Plus 用法、事务与并发读 | 改表、写 SQL / 仓储、碰事务时 |
| [state-machine-guidelines.md](./state-machine-guidelines.md) | 三张状态列（`task_run` / `subtitle_artifact` / `intermediate_file`）的值语义、**全部**写入路径、守卫落在哪 | 加/改状态值、加一条写状态的新路径时 |

---

## 已删除规范的重建记录

模板文件会因代码库中还没有对应实现而先删除，相应功能落地后再重建：

- `logging-guidelines.md` —— 2026-09-24 随首个日志实现落地重建。

> 不要在功能落地前凭空补这些规范。子 agent 会照着规范写代码，写了不存在的模式比不写更糟。
> **重建完成的条目从本节删掉、并进上面的「规范索引」** —— 留在"待重建"列表里会读成还没做。

---

## 技术栈基线

**以 `backend/pom.xml` 为准，不要凭记忆写版本号。**

| 项 | 值 | 出处 |
|---|---|---|
| Spring Boot | `4.1.1` | `pom.xml` parent |
| Java | `21` | `pom.xml` `<java.version>` |
| Spring AI | `2.0.1` | `pom.xml` BOM |
| 构建 | Maven（**无 wrapper**，直接用 `mvn`） | 目录下无 `mvnw` |

现有依赖（`pom.xml`）：

- `spring-boot-starter-web`
- `springdoc-openapi-starter-webmvc-ui:3.1.1` —— 接口文档（OpenAPI 3 + Swagger UI，零配置）。Boot 4（Framework 7）必须用 springdoc **3.x**，2.x 启动报错；knife4j 尚未适配 Boot 4，故未引入。接口描述用 Swagger 注解（`@Tag`/`@Operation`/`@Schema`，方案 A），中文且与 Javadoc 语义一致；**直接充当对外契约的模型**（JSON 形状可直接暴露者，如 `application/diagnostics/` 的 `ToolDiagnosticReport`、`domain/transcription/model/` 的 `TranscriptionResult`）字段都要有 `@Schema` 描述，**需展平而自持于 `web/dto/` 的 HTTP 契约 record 同理**——归属判据见 [directory-structure.md](./directory-structure.md)
- `spring-ai-starter-model-deepseek`
- `dashscope-sdk-java:2.20.6` —— 已排除 `slf4j-simple`，理由见 pom 内注释（与 Spring Boot 的 Logback 冲突）
- `lombok`（`optional`，且已从 boot jar 打包中排除）
- `spring-boot-starter-test`（test scope）
- `spring-boot-starter-webmvc-test`（test scope）—— Boot 4 把 `@WebMvcTest` 从 starter-test 拆分到该独立模块，写切片测试必须单独引入

---

## 常用命令

在 `backend/` 目录下执行：

```bash
mvn test              # 跑测试
mvn spring-boot:run   # 本地启动
mvn -q compile        # 仅编译
```

本地启动后接口文档：Swagger UI 在 `http://localhost:8080/swagger-ui/index.html`（JSON 在 `/v3/api-docs`）。生产环境若不对外开放文档，需显式关闭 `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled`（启动时有 WARN 提示）。

前端命令见 [../frontend/index.md](../frontend/index.md)。


