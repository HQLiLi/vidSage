# service 层注解回归 @Service — PRD

> **Lightweight 任务，PRD-only。** 改动为 1 个 Java 文件（2 行）+ 5 处 spec 文本，写 design.md / implement.md 是纯开销。

## 背景

`directory-structure.md` 规定「Spring 组件统一用 `@Component`，**不用** `@Service`」，但**没有记录任何理由**：`provider-pattern.md` 写「`@Component` 而非 `@Service`（见 directory-structure.md）」，而 directory-structure.md 只有规则、没有解释 —— 两处文档互相指向一个不存在的理由。

经核对 Spring 实际行为（`spring-context:7.0.9`，`javap` 验证）：

| 注解 | 额外行为 |
|---|---|
| `@Component` | 无（仅注册 bean） |
| `@Service` | **无。纯标签** |
| `@Repository` | 有（异常转换） |
| `@Controller` | 有（handler 识别） |

即 `@Component` 与 `@Service` 完全等价，当初的规则是纯风格选择。用户决定**回归 Spring 社区惯例**：`service/` 下的应用服务标 `@Service`，读者一眼能认出层次，不必停下来想「为什么是 `@Component`」。

**注意这条规则从「统一」变成「按包选」**：Provider 实现类不在 `service/` 包、也不是应用服务，继续用 `@Component`。这不是遗漏，是新规则的正确结果。

## 需求

### R1 `service/` 用 `@Service`

`backend/src/main/java/com/vidsage/service/TranscriptionService.java`：`@Component` → `@Service`，import 同步换为 `org.springframework.stereotype.Service`。

### R2 规则改为「按包选 stereotype」

`directory-structure.md` 命名约定表（当前第 147 行）由「实现类用 `@Component`，不用 `@Service`」改为按包对应：

| 包 | stereotype |
|---|---|
| `controller/` | `@RestController` |
| `service/` | `@Service` |
| `provider/` | `@Component`（它不是应用服务，`@Service` 会误导） |
| `persistence/` | `@Repository` |

### R3 `persistence/` 的未来注解对齐为 `@Repository`

`directory-structure.md` 第 91 行现写「`XxxRepository` 具体类（**`@Component`**）」，改为 `@Repository`，与新规则自洽。

> **不声称异常转换收益。** 常见说法是 `@Repository` 白拿 `PersistenceExceptionTranslationPostProcessor` 的异常转换，但那主要针对 JPA/Hibernate 原生异常，**MyBatis-Plus 下是否真触发未经核实**。写入 spec 时只作为「跟随社区惯例」的理由，不得写成「有行为差异」。

### R4 修悬空引用

`provider-pattern.md` 第 51 行「`@Component` 而非 `@Service`（见 [directory-structure.md](./directory-structure.md)）」：Provider 仍用 `@Component`，但理由要改成自身成立的表述（**它不是应用服务、也不在 `service/` 包**），不再指向别处。

### R5 同步其余提到注解的 spec 行

- `directory-structure.md` 第 52 行：`service/` 的「允许出现」列中含 `@Component` → `@Service`
- `directory-structure.md` 第 164 行：「`TranscriptionService` 用 `@Component`」→ `@Service`

### R6（可选，请审时裁决）切点用包定位，不用注解定位

spec 中补一句提示：写切面时用 `within(com.vidsage.service..*)` 这类**包定位**，不要用 `@within(org.springframework.stereotype.Service)` 这类**注解定位** —— 后者在有人漏标注解时会静默失效。

**权衡**：项目目前没有任何切面，按 `index.md`「不要在功能落地前凭空补这些规范」，这条属于推测性规则。但它是本次注解讨论的直接产物、且失效时无任何报错，作为一行提示可能值得。**倾向：加，但只作为新规则下的一句附注，不单开章节。** 若你认为多余，划掉即可。

## 不做

- **不动 `provider/` 与 `controller/` 下的注解**（`DashScopeAsrProvider` 保持 `@Component`、`AsrController` 保持 `@RestController`）
- **零行为改动**：`@Component` ↔ `@Service` 等价，不改任何逻辑、构造、字段
- **不加切面**（R6 只是文档提示）
- **不引入新依赖、不改 `pom.xml`、不改配置键与 REST 路径**
- **不改 `index.md`、`quality-guidelines.md`、`error-handling.md`**（经 grep 确认其中无 stereotype 规则）

## 约束

- 基线：本分支从已合并技术分层的 `master`（`48ae51c`）开出，`TranscriptionService` 位于 `com/vidsage/service/`
- 测试基线 16/16 绿（`VidSageApplicationTests` 1 + `AsrControllerTest` 4 + `DashScopeAsrProviderTest` 11）
- 验证命令：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test`

## 验收标准

- [ ] `service/TranscriptionService.java` 用 `@Service`，import 为 `org.springframework.stereotype.Service`
- [ ] `git diff` 对该 `.java` 文件**只有 2 行变更**（注解 + import），无其他改动
- [ ] `grep -rn "@Component" backend/src` 只剩 `DashScopeAsrProvider.java` 一处
- [ ] `grep -rn "RestController" backend/src` 命中 `AsrController` 且未变
- [ ] `mvn test` 16/16 绿 —— 本次为零行为改动，若不绿说明改错了（不是「测试恰好没过」）
- [ ] `grep -rn "不用 .*@Service\|@Component 而非 @Service" .trellis/spec/` 无命中
- [ ] `directory-structure.md` 不再存在「统一用 `@Component`」的表述，新规则能查到 `@Service` / `@Repository`
- [ ] `provider-pattern.md` 中 Provider 用 `@Component` 的理由自身成立，不再指向 directory-structure.md

## 备注

- 上游讨论：本任务起因是用户问「为啥这里是 `Component` 不是 `@Service`」。当时的选项是「补理由 / 改用 `@Service` / 不动」，用户选**改用 `@Service`**。
- 本任务**依赖** `refactor/tech-layering` 已合入 `master`（已完成，`48ae51c`）—— 否则 `service/` 包与相应 spec 章节都不存在。
