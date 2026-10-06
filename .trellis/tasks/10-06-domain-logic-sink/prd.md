# 业务逻辑下沉 domain（application service 瘦身为纯编排）

## Goal

把 application service 中错住的业务规则下沉到 domain 层，service 瘦身为纯用例编排：application 管步骤编排、业务实现在 domain、技术实现在 infrastructure。**行为等价**是硬约束——这是语义重构（搬逻辑不改行为），不是 Phase 1–4 那类机械迁移。

需求来源：项目所有者 2026-10-06 提出「application 中的 service 还是传统 CRUD service，没做拆分」；证据基线 = [research/logic-distribution.md](./research/logic-distribution.md)（逐方法归类的调研报告，行号锚点均在其中）。

## 已确认事实（调研，2026-10-06）

- application 层 2506 行中可下沉纯域规则约 400–600 行（不足 1/5）；`MediaAcquisitionService` / `TranscriptionService` / `ToolDiagnosticsService`（共 184 行）为纯编排/门面，零改动；`TaskPipelineService` 近乎达标。
- 四大下沉块：① Workspace 命名/寻址（`normalize`/`dirName`/`sanitizeName`/`shortHash`，即 Phase 2 延后的第二批值对象 `SourceUri`/`WorkspaceDir`）；② 字幕语种政策 + 术语查证政策（`SubtitleTranslationService`，规则密度最高）；③ 检查点语义（「JSON 非空=完成、0 字节=未完成、SRT 是派生视图」在 4 个服务各写一遍）；④ 零散谓词与常量（音频指纹命名、产物可见性过滤、active 谓词在 repository SQL 里、`isTerminal` 在 web 两处重复、`toMediaInput` 工厂、`STAGE_TRANSLATE` 常量住在 infrastructure）。
- 重复实锤：key 宽度常量 12 两处各一条公式；上下文降级政策近逐字重复；`isTerminal`（TaskController L258-262 与 TaskSseHub L115-119 注释互指）。
- domain 已就位勿重复搬运：`VideoTask` 双状态机、`TaskNo`/`WorkspaceKey`、`AudioSpec` 不变量、subtitle 契约群、四端口接口。
- Controller 的双份卫语句（url/taskNo 空白）**不是**待修重复——error-handling.md「同一个值被检查两次不是冗余」判据（边界 IAE vs 服务端 ISE）支持保留。

## Requirements

- R1 **Workspace 寻址家族**（→ `domain/shared/`，与 `WorkspaceKey` 同判据：跨上下文共用）：新建 `SourceUri`（输入身份规范化 `normalize`）、`WorkspaceDir`（目录名公式 `dirName` + `sanitizeName`/保留名，2026-10-06 所有者确认随行）；`WorkspaceKey` 增静态工厂（`shortHash` 吸收，key 宽度 12 单源化）；`localPathKey` 切两层（身份公式进域、文件 stat 由 application 提供三元组）。`WorkspaceService` 瘦身为文件系统操作（`ensureCreated`/`rootDir`/`workspaceFor` 编排）。
- R2 **字幕语种与术语政策**（→ `domain/subtitle/`）：`Bcp47Language`（或 LanguagePolicy 领域服务）承载 `resolveTargetLanguage`/`resolveSourceLanguage`/`isSameMainLanguage`/`mainLanguageSubtag`；术语查证触发三条件与结论记账政策（official/common/none、失败词不进产物、全失败不落盘）下沉；`skipAll` 同语种照抄规则随行；`STAGE_TRANSLATE` 等阶段常量归位 domain/shared/StageEvent 一族（消除 TaskService 为取常量 import infrastructure 类）。
- R3 **检查点语义统一**（→ `domain/subtitle/`）：一个检查点政策对象承载「JSON 非空=完成、0 字节=未完成、SRT 是派生视图不参与幂等」，SubtitleService / SubtitleCleanupService / SubtitleTranslationService / AudioPreparationService 四处合流；上下文「缓存-or-摘要 + 空摘要绝不落盘」政策抽共享对象。
- R4 **零散谓词与工厂**：产物可见性过滤（`*.srt` + `segments-*.json`、排除 terms-/context-）→ domain/task 政策；active 任务谓词（现 `VideoTaskRepository` SQL `in(PENDING,RUNNING)`）→ `VideoTask` 领域谓词单源，repository 引用；`isTerminal`（主状态终态且翻译不在 RUNNING）→ `VideoTask.isEventSettled()` 单源，TaskController/TaskSseHub 改调用；音频指纹命名 `audioFileName`/`sampleRateKilo` → `domain/media/`；`toMediaInput` 重建规则 → `MediaInput` 静态工厂。
- R5 **行为等价硬约束**：`mvn test` 全绿且断言随逻辑搬家（WorkspaceServiceTest 约 18 条、SubtitleTranslationServiceTest 约半数迁 domain 测试），断言值不变不许弱化；对外 JSON/SSE 形状零变化；Controller 边界卫语句保留。
- R6 每批独立 commit（延续 `feature/ddd-four-layer` 分支或其后续），可独立 revert。
- R7（2026-10-06 追加，项目所有者选定方案）：**读模型拆分**——`application/task/query/TaskView` 改造为无注解读模型 `TaskSnapshot`（去掉全部 @Schema，应用层读模型不携带 web 注解）；`web/dto/` 新增 `TaskResponse`（@Schema 齐全，静态工厂 `from(TaskSnapshot)` 映射，controller 四端点返回它）；`TaskConflictResponse` 内嵌改 `TaskResponse`；`TaskConflictException` 载荷随 application 侧（TaskSnapshot）；「对外响应类命名 XXResponse、落 web/dto/」写进 spec 作为约定（对未来新增端点生效）。JSON 字段名/类型逐一不变，前端零感知。

## Acceptance Criteria

- [ ] AC1 `cd backend && mvn clean test` 全绿（每批末 + 任务末）。
- [ ] AC2 service 瘦身达标（对照 design.md §2 下沉映射表逐项核）：三个零改动服务 git diff 为空；`TaskService` / `WorkspaceService` / `SubtitleTranslationService` 等的报告所列业务规则块全部迁出，方法级核对无残留。
- [ ] AC3 单源验证：key 宽度常量、`isTerminal`、active 谓词、检查点判据、上下文降级政策各只有一处定义（grep 验证）。
- [ ] AC4 测试随迁不弱化：迁入 domain 的测试断言值与原断言一致；`git diff` 审查无断言语义弱化。
- [ ] AC5 对外契约零变化：JSON/SSE 形状不变；web 层仅 isTerminal 单源化相关的调用改动与 import。
- [ ] AC6 spec 同步：directory-structure.md / quality-guidelines.md 如有受影响的归属判据表述，同步更新（如「第二批值对象落地」事实）。
- [ ] AC7 读模型拆分后：`TaskSnapshot`（application）零 web 注解；`TaskResponse`（web/dto）@Schema 齐全；JSON 字段名/类型与拆分前逐一一致（前端 `types/task.ts` 零改动）；`grep -rn "TaskView" backend/src/` 零残留（rename 完结标志）。

## Out of Scope

- 依赖倒置正统化：LLM worker 不建 domain 端口、`VideoTaskRepository` 保持具体类（豁免清单不变，升级触发条件不变）。
- `shared/subtitle/` 五个纯域规则件不动（2026-10-06 所有者选定：无状态纯函数、shared 准入判据覆盖、搬家波及面大）。
- Controller 边界卫语句去重（error-handling.md「两问两答」判据：双份保留）。
- `stageEventOf`/`translationStageEventOf` 升格域服务（留 application/task，读模型组装是应用层职责）。
- 失败文案组装迁域（留编排层：面向用户文案是展示关切）。
- `translateTask` 触发判定序列迁域（留编排：状态守卫已在 `VideoTask`，判定顺序与幂等收敛是用例步骤）。
- raw/clean 缺失抛 ISE 的错误分类调整（保持现状，error-handling.md 判据已覆盖）。
- 领域事件、聚合边界重划等更大的 DDD 升级；任何对外行为变更。
