# application 出站依赖倒置：端口归 domain，infrastructure 实现

## Goal

消除后端各层对 `infrastructure/` 的**反向依赖**，并把 `web → domain` 清零：把现在被上层直接引用的 5 个 infrastructure 具体类改造成「端口接口 + infrastructure 实现」，上层只依赖 `domain` 与 `application`。

本轮**只做依赖方向的整理，不做业务逻辑搬迁**——行为、日志、HTTP 契约、SSE 事件、持久化格式全部保持等价。

## 现状（改造前）

**`application/` → `infrastructure/`（5 处）：**

| 消费方 | 依赖的 infrastructure 类型 | 性质 |
|---|---|---|
| `application/task/TaskService` | `VideoTaskRepository` | 持久化 |
| `application/task/TaskPipelineService` | `VideoTaskRepository` | 持久化 |
| `application/subtitle/SubtitleCleanupService` | `SubtitleCleanser`、`SubtitleContextSummarizer` | LLM worker |
| `application/subtitle/SubtitleTranslationService` | `SubtitleContextSummarizer`、`SubtitleTranslator` | LLM worker |
| `application/diagnostics/ToolDiagnosticsService` | `ExternalToolDiagnostics`、`ExternalToolReport` | 进程诊断 |

**`web/` → `infrastructure/`（豁免 b）：** `MediaController` 直接读 `ExternalToolReport`。

**`web/` → `domain/`（4 处 import / 3 个文件）：**

| 文件 | 用到的 domain 类型 | 用法 |
|---|---|---|
| `web/controller/TaskController:5,191` | `VideoTask` | 调用领域规则 `VideoTask.isEventSettled(...)` 判终态 |
| `web/sse/TaskSseHub:4,116` | `VideoTask` | 同上 |
| `web/dto/TaskResponse:4-6,39-43` | `TaskInputType` / `TaskStatus` / `TranslationStatus` | 三个响应字段的类型声明 |

这些依赖目前是 spec **明文允许的豁免**（`directory-structure.md` 豁免清单 a / b，且正典只写了 `web → application`）。本轮决定推翻豁免并把 `web → domain` 一并清零。

## Requirements

- **R1 依赖方向**：`application/` 与 `web/` 的 **main 源码**中，不再出现 `com.vidsage.infrastructure.*` 或 `com.vidsage.domain.*` 的 import。
- **R2 端口归属**：端口接口按契约的**语义职责**分流——领域词汇且业务规则需要它 → 落 `domain/<上下文>/`；技术/运维词汇、或仅编排/集成需要 → 落 `application/<上下文>/`。兜底判据：删掉这个端口，业务规则还成立吗？
- **R3 端口命名**：接口与实现**同名**，实现只加技术前缀——`VideoTaskRepository` → `MybatisVideoTaskRepository`；`SubtitleCleanser` / `SubtitleTranslator` / `SubtitleContextSummarizer` → `Llm*`；`ExternalToolDiagnostics` → `ProcessToolDiagnostics`。
- **R4 端口表面**：端口只声明上层真正调用的方法，不搬实现类的便捷重载与包私有测试钩子。
- **R5 端口实现**：两类端口均由 infrastructure 实现；infrastructure 是唯一实现端口的地方。`infrastructure → application` 是**窄口 + 白名单**（只允许 import 端口接口，禁止 import 用例服务 `*Service`、`web/`、`web/dto/`）。
- **R6 行为等价**：不改变任何运行时行为。端口化只是「谁声明签名」的搬家，方法体一行不动。
- **R7 不做的事**：不引入 domain service（架构上允许，本轮无真实场景）；不引入事务 / 权限边界；不做二次业务下沉；不重组 application 的二级包结构。
- **R8 层间规则的 test 豁免**：依赖规则约束**生产代码**；`web/` 的 **test 源码**为构造夹具允许 import `domain`，该豁免明文写进 spec。
- **R9 spec 对账**：凡记载本轮事实的 spec 段落与 package-info 一并改写为现状，不留失实表述。
- **R10 提交切分**：代码改动与文档改动分两次提交。

## Acceptance Criteria

- [ ] **AC1a** 依赖归零（main）：
      `grep -rn "com\.vidsage\.infrastructure" backend/src/main/java/com/vidsage/application backend/src/main/java/com/vidsage/web` **零命中**；
      `grep -rn "com\.vidsage\.domain" backend/src/main/java/com/vidsage/web` **零命中**。
- [ ] **AC1b** test 侧：`application` 与 `web` 的 test 源码中 `com.vidsage.infrastructure` **零命中**；`web` 的 test 源码允许 `com.vidsage.domain`（豁免，见 R8）。
- [ ] **AC2** 全量测试通过（`mvn clean test`），且**测试总数与改造前基线（465）一致**。类名会随实现改名而变，故比对**数量**而非类名。
- [ ] **AC3** 五个端口的签名与 `design.md`「端口清单」逐一相符；端口上看不到便捷重载、`validate` 等包私有方法。
- [ ] **AC4** 每个端口恰有一个实现类，且实现类带原 stereotype（`@Repository` / `@Component`），Spring 上下文加载测试通过。
- [ ] **AC5** HTTP 契约的 **JSON 字段名与类型零变化**，含 SSE payload：`TaskProgressMessage` 新增的派生方法**不得**出现在 SSE 的 data JSON 里（实际比对，不靠推断）。
- [ ] **AC6** OpenAPI 文档的两处有意变化已落实且仅此两处：`ToolDiagnosticReport` 的 schema 组件名与描述文案；`TaskResponse` 三个字段由 `enum` 降级为 `string`（取值写进描述）。
- [ ] **AC7** `directory-structure.md` 的豁免清单 a / b 已删除、依赖规则段已按「窄口 + 白名单 + 归属判据」重写；`quality-guidelines.md` 的「不预先抽接口」条目已按新判据重写；全文不再出现「LLM worker 保持具体类」「本包就是终态形态」等失实表述。
- [ ] **AC8** 两次提交：先 `refactor(backend): …`（代码），后 `docs(spec): …`（文档）。

## 已知副作用（可接受）

- 5 个实现类 + 5 个测试类改名；`ToolDiagnosticsService` 删除。
- OpenAPI 文档的两处变化（见 AC6）。**JSON 本体全程不变。**
- 新增文件集中在 `domain/{task,subtitle}/`、`application/diagnostics/`；**不新增 `domain/diagnostics/`**（诊断端口归 application 定义）。

## Notes

- 本项目尚有若干**非 DDD 视角**的既有规范；本轮遇到与之冲突时，**停下来问项目所有者**，不自行选边。
- 引用正典：`directory-structure.md`（分层判据、依赖规则、命名）、`quality-guidelines.md`、`provider-pattern.md`。
- 一条**已知待复核**：域名端口的兜底判据（「删掉这个端口，业务规则还成立吗？」）对 LLM 组有歧义，本轮按「该能力自身的业务语义」读法判为 domain，详见 `design.md` §1.2。
