# 后端开发规范

> VidSage 后端（Java 21 + Spring Boot 4.1.1 + Spring AI 2.0.1）的编码约定。
> 所有规则都以 `backend/` 下的真实代码为依据；写代码前先读对应文档。

---

## 规范索引

| 文档 | 内容 | 何时读 |
|------|------|--------|
| [directory-structure.md](./directory-structure.md) | 包结构、按功能分包、类与文件命名 | 新增模块或类时 |
| [provider-pattern.md](./provider-pattern.md) | Provider 抽象 + 条件装配 + record 配置属性 | 新增或切换本地/云端实现时 |
| [error-handling.md](./error-handling.md) | 卫语句、异常类型选择、错误信息写法 | 写任何校验与失败路径时 |
| [quality-guidelines.md](./quality-guidelines.md) | 契约设计、注释要求、测试写法、依赖原则 | 写任何代码前 |

---

## 尚未落地的规范

以下模板文件**已删除**，因为代码库中还没有对应实现。相应功能落地后再重建：

- **`database-guidelines.md`** —— `backend/pom.xml` 目前没有任何持久化依赖（无 MyBatis-Plus、无 JPA、无 Redis starter），也不存在实体或 Repository 代码。主 PRD 规划的 MyBatis-Plus + Redis/pgvector 尚未动工。
- **`logging-guidelines.md`** —— 现有全部源码中没有一条日志语句，尚未形成可依据的约定。

> 不要在功能落地前凭空补这些规范。子 agent 会照着规范写代码，写了不存在的模式比不写更糟。

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
- `spring-ai-starter-model-deepseek`
- `dashscope-sdk-java:2.20.6` —— 已排除 `slf4j-simple`，理由见 pom 内注释（与 Spring Boot 的 Logback 冲突）
- `lombok`（`optional`，且已从 boot jar 打包中排除）
- `spring-boot-starter-test`（test scope）

---

## 常用命令

在 `backend/` 目录下执行：

```bash
mvn test              # 跑测试
mvn spring-boot:run   # 本地启动
mvn -q compile        # 仅编译
```

前端命令见 [../frontend/index.md](../frontend/index.md)。


