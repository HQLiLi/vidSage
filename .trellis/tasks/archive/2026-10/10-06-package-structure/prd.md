# 包结构整理：平铺包按角色拆分子包

## Goal

让每个上下文包里混放的角色分开，新增类时判据机械可执行——「先判该层的角色，角色决定桶路径」，取代现在 22 个类平铺、看不出角色边界的状态。纯结构移动，零行为变化。

用户价值（用户原话）：**「先拆，小但碎没关系，这就是分类，分好比杂乱要好，后面添加可以知道往哪加。」**——因此分类规则本身必须能机械执行，碎包不是代价。

## Background：已确认事实（2026-10-06 盘点）

main 共 170 个类，各包类数与角色构成：

| 包 | 类数 | 构成 |
|---|---|---|
| `domain/subtitle` | **22** | 端口 4 / 无状态政策 3 / 数据载体 14 |
| `domain/task` | 8 | 状态机 `VideoTask` 1 / 枚举 3 / 值对象 `TaskNo` / 政策 `TaskArtifactScan` / 端口 `VideoTaskRepository` |
| `domain/media` | 8 | 数据载体 5 / 端口 2 / 枚举 1 |
| `domain/transcription` | 6 | 端口 1 / 数据载体 5 |
| `domain/shared` | 6 | 值对象 4 / 接口 `ProgressListener` |
| `application/media` | 3 + pkg-info | `WorkspaceService`（`@Service` 横切）+ 2 个阶段类 |
| `application/subtitle` | 3 + pkg-info | 3 个阶段类，无服务 |
| `application/transcription` | 1 + pkg-info | 1 个阶段类 |
| `application/diagnostics` | 2 + pkg-info | 端口 `ToolDiagnostics` + 出参 `ToolDiagnosticReport` |
| `application/task` | 根 3 | 已拆出 `query/`、`exception/`、`subscribe/`；根余 `VideoTaskService`、`VideoTaskPipeline`、`TaskProgressMessage` |
| `shared/*`、`config/`、`infrastructure/process/`、`infrastructure/provider/*` | 各 1–5 | 见 `design.md` 的逐包归属 |

spec 现行口径（`.trellis/spec/backend/directory-structure.md`）：

- `:158`「二级包怎么分」——三条轴各管各的；`domain`/`application` 按限界上下文分
- `:162` 阈值句：「上下文包内的**角色子包随 mass 出现**：**1–3 类的小上下文平铺即可，类多且角色分明再分子包**」；唯一样板实例是 `application/task/`
- `:166` 同一规范**曾被以「再套一层是冗余层级」为由否掉、后有意推翻**——该取舍在本项目反复出现

波及面：`com.vidsage.domain.subtitle` 被 main 35 处 + test 15 处引用；`com.vidsage.domain.task` 被 main 15 处 + test 7 处引用。7 份 backend spec 都引用了受影响的包路径。代码与 resources 里**没有**硬编码包名字符串（`@MapperScan` / `basePackages` 零处；`logging.level.com.vidsage: DEBUG` 按前缀生效不受影响）。

## Requirements

- **R1 轴（2026-10-06 用户选定，方案 A 角色轴）**：拆包轴 = 角色。`domain/<上下文>/` 的桶为 `port/`（接口）、`policy/`（final class + 静态方法的无状态政策）、`model/`（record / enum / sealed 数据词汇）；**根只留该上下文的主类型**（状态机/聚合，如 `VideoTask`），没有主类型就只剩 `package-info`。`domain/subtitle` 的 `model/` 内 14 个数据载体再按子主题分二层（`cleanup/`、`translation/`、`term/`、`common/`）
- **R1.1** 阈值取下限：能拆就拆，拆出的桶允许只有 1 个文件（用户已定）
- **R2 范围（2026-10-06 用户选定：全层扫一遍）**：逐层的桶规则与逐包归属见 `design.md`；**不建空桶**——某角色在该层没有成员就不建该目录
- **R3 零行为变化**：只动 `package` 声明、`import` 与文件位置；类名、方法签名、注解、行为、HTTP 契约、JSON 字段、数据库零变化（R7 那处下沉是唯一例外：删两个包级私有 static、在新政策类里对等重建，行为仍须零变化）
- **R4 测试包镜像跟随**（spec「测试包结构」要求测试包与主包镜像对应）
- **R5 spec 对账**：`directory-structure.md`「二级包怎么分」的阈值句（`:162`）改写成新判据，并让新增类的落位可从规范机械推导；7 份 backend spec 中引用受影响路径处一并对账
- **R6** 受影响包的 `package-info.java` 重写（`domain/subtitle/package-info.java` 现在本身是一份逐类词汇索引，拆包后失效）
- **R7 逻辑下沉（2026-10-06 用户追加）**：`VideoTaskService` 里两个**包级私有 static** 判据 `stageEventOf(VideoTask)` / `translationStageEventOf(VideoTask)` 下沉为 `domain/task/policy/TaskStageEvent`（纯函数领域政策，与 `TaskArtifactScan` 同族），两个调用点（`VideoTaskService.snapshot` 与 `VideoTaskPipeline` 的两处终态广播）改调它。**判据逐字搬**——分支、进度值、面向用户的中文文案一字不差，行为零变化。动因：不这样做，`VideoTaskPipeline` 移出 `application.task` 包后访问不到这两个包级私有 static（Java 包级私有不跨子包），只剩「放宽可见性」或「不拆该包」两个次优解；下沉到 domain 后它是 public API，根与 `pipeline/` 桶都能调


## Acceptance Criteria

- [ ] **AC1** 后端编译通过 + 全量测试通过（命令见 `implement.md`，注意必须 `mvn clean test`）
- [ ] **AC2** 落位机械可核对：每个桶内只含单一角色，用脚本断言（如 `port/` 下全部为接口、`policy/` 下全部为 `public final class`）
- [ ] **AC3** 零行为变化：`git diff -M` 只含重命名 + `package`/`import` 行；无方法签名、注解、类名变更；HTTP 契约（含 SSE payload 字段名与类型）与数据库结构零变化
- [ ] **AC4** 能从新规范机械推导出「新加一个类放哪」，7 份 backend spec 无残留旧路径引用
- [ ] **AC5** 测试包与主包镜像一致

## Out of Scope

- 不改类名、不改方法签名、不改任何行为（改名属于已完成任务 `10-06-naming-alignment`；R7 那处下沉是本任务明确纳入的例外）
- 不建空桶（如 `domain/media/policy/`、`domain/transcription/policy/`）
- `@Schema` 落位口径（内层类型能不能带 `@Schema`）由用户自行推进——`directory-structure.md`「契约类的位置」表格里 `TaskProgressMessage` 那行的订正随之留白；本次只改「二级包怎么分」与路径引用
- 顺手发现的、与本任务无关的结构争议不在范围内（如 `config/` 里两个 LLM 相关 `*Properties` 是否该迁去 `infrastructure/provider/llm/`——spec 有归属条款，但那是另一件事）

## Open Questions

无。轴（Q1）、范围（Q2）已由用户 2026-10-06 定案；`@Schema` 相关的 spec 订正按上述 Out of Scope 处理。技术设计与执行计划见同目录 `design.md` 与 `implement.md`。
