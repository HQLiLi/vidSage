# SubtitlePipeline 三条 run 流程拆分 — 需求

> 任务 `10-21-subtitle-run-flow-split`｜分支 `feature/subtitle-run-flow-split`｜基座 `dev`
> 技术设计见 [design.md](./design.md)；执行顺序与验证命令见 [implement.md](./implement.md)。

> **订正（2026-10-21，开工当天）**：本任务创建时的描述写着「`SubtitleTaskService` 退化成薄入口」。经当轮设计讨论推翻——同步段全部下沉之后，该类的参数校验 / 语种解析 / 转调**没有任何一样是三条流程共享的**，留下它就是一个零逻辑的空壳，正是 spec 2026-10-06 那次命名对齐要扫掉的形态。**改为删掉**，三个类自己升为用例服务（判据与出处见 design §3）。

---

## 1. 背景：病根是流程被劈成两半

`SubtitlePipeline`（298 行）与 `SubtitleTaskService`（356 行）各自横跨 MAIN / RECLEAN / TRANSLATE 三条 run 流程。

准确的描述不是「三条流程混在一个类」，而是**每条流程被劈成两半，两半分别跟另外两条流程的另一半混在两个类里**：

| run | 同步段（守卫 + 抢互斥 + 落库） | 异步段（闸门 + 阶段 + 落定 + 收口） |
|---|---|---|
| **MAIN** | `SubtitleTaskService.handleSubmit`（`submitUrl` / `submitUpload` 收敛于此）：事务内 `findOrCreateWorkspace` + `findOrCreateTask` + `supervisor.start(MAIN)` + 关键词落库 | `SubtitlePipeline.runPipeline`：指纹探测 → 准备 → 清洗 → 收口 |
| **RECLEAN** | `SubtitleTaskService.regenerate`：`finder.require`(404) → `requireIntactArtifact(ORIGINAL)`(400) → `supervisor.start(RECLEAN)` → 关键词落库 / 读回 | `SubtitlePipeline.regenerate`：占位 → `cleanupStage.regenerate` → 无条件作废下游 → 收口 |
| **TRANSLATE** | `SubtitleTaskService.translate`：语种解析 → `finder.require` → `requireIntactArtifact(CLEANED)` → `supervisor.start(TRANSLATE, target)` | `SubtitlePipeline.translate`：已有译文就 SKIPPED 提前收口 → `translationStage.translate` → 占位 → 落定 → 收口 |

四条具体代价：

1. **同一条流程的规则隔了一个类**。RECLEAN 的前置守卫「ORIGINAL 必须完好」在 `SubtitleTaskService:195`，它的执行规则「无条件作废下游译文」在 `SubtitlePipeline:123`。要搞懂 RECLEAN 是什么，得同时开两个文件。
2. **两条流程的规则住进同一个方法**。`regenerate` 里 `supervisor.start(RECLEAN)` 之后紧跟 `keywordRepository.replace` —— 关键词落库这条规则在 `handleSubmit` 里还有一份。
3. **三条流程的差异被压成散落的字符串**。中断文案（"请重新提交" / "请重新触发" / "请重新触发翻译"）与失败日志前缀（"任务执行失败" / "字幕重新生成失败" / "翻译失败"）平铺在三个方法体里，而不是各流程自己的属性。
4. **类的字段是三条流程依赖的并集**。`mediaIdentityResolver` 只服务 MAIN 的 URL 路线，`workspaceDirectoryManager` 只服务 MAIN 的上传归位，`transactionTemplate` 只服务 MAIN，`artifactStore` 只服务 RECLEAN + TRANSLATE 的守卫。

**这不是新账**：10-20 `subtitle-reuse-versioning` 的 prd「本段不做」第一条原文即为「把 1 个管线拆成 3 个（主任务 / 重清洗 / 翻译）：后续另开任务；『重清洗无条件作废老产物』那条语义由它定稿」。本任务就是那一刀。

## 2. 目标

按 run 类型重组为三个各自完整的类，**每条流程的全部规则（同步段 + 异步段）住进一个类**。

## 3. 范围

### 3.1 动

| | 内容 |
|---|---|
| **删** | `application/subtitle/pipeline/SubtitlePipeline`、`application/task/SubtitleTaskService` |
| **增** | `SubtitleMainService` / `SubtitleRecleanService` / `SubtitleTranslateService`（落 `application/task/`，`@Service`） |
| **改** | `TaskController` 改注入三个用例服务；`SubtitlePipelineTest` / `SubtitleTaskServiceTest` 按 run 类型重分 |

### 3.2 不动

- 端点、请求 / 响应形状、SSE 事件形状、异常 → HTTP 状态码映射
- 四个阶段协调器的公开方法与行为——**唯一例外**是 `SubtitleCleanupCoordinator` 内部的一处代理拆解（`requireContextSummary` 兼职「决定开始事件文案」→ 换成显式 `CleanMode` 枚举），见 design §5 T6。公开签名一字不动
- `SubtitleTaskFinder` / `TaskRunSupervisor` / `SubtitleArtifactStore` / `TaskConcurrencyGate`
- 六张表 DDL、`schema.sql`、产物模型与复用判据
- **行为一行不改** —— 本任务是纯结构搬迁

### 3.3 本任务不做

| 不做 | 原因 |
|---|---|
| **抽共用骨架**（闸门提交 + 中断回调 + try/catch 收口） | 用户口径：先让三个类各自完整地存在，跑起来看清真实的缝在哪，再决定抽不抽。代价与后续候选登记在 design §5 T1 |
| 改任何对外契约 | 端点 / DTO / SSE / 状态码一律不动，AC4 要求逐项与开工前相同 |
| 动并发与状态机 | `TaskConcurrencyGate` 的中断语义、`task_run` 状态机都不归本任务 |
| 修 10-20 记账的「已知窗口」（ORIGINAL 真重跑 + CLEANED 复用命中） | 触发路径只有「人工改行」或「检查点损坏」，与本任务无关 |

## 4. 验收标准

> 每条 AC 都要留下**机器可判的证据**（命令 + 原始输出），落在任务目录 `evidence/` 下。

### AC1 — 两个旧类消失，三个新类到位

- `SubtitlePipeline` 与 `SubtitleTaskService` 在 `backend/src/main/` 下 **grep 零命中**（含 import、注释、Javadoc）。
- 三个新类存在于 `backend/src/main/java/com/vidsage/application/task/`，各自标 `@Service`。
- **证据**：grep 命令与输出；三个文件的 stereotype 行。

### AC2 — 无跨流程引用

每个用例服务的 import 里**不出现**只有另外两条 run 才需要的协作者：

| 类 | 不得出现 |
|---|---|
| `SubtitleMainService` | `SubtitleTranslationCoordinator` |
| `SubtitleRecleanService` | `SubtitlePreparationCoordinator`、`SubtitleFingerprintCoordinator`、`SubtitleTranslationCoordinator`、`MediaIdentityResolver` |
| `SubtitleTranslateService` | `SubtitlePreparationCoordinator`、`SubtitleCleanupCoordinator`、`SubtitleFingerprintCoordinator`、`SubtitleKeywordRepository`、`MediaIdentityResolver` |

- **证据**：对三个文件逐个 grep 上表名单，输出全空。

### AC3 — 三条 run 的行为逐项不变

- 三种 run（MAIN / RECLEAN / TRANSLATE）各走一次真链路（真 yt-dlp + 真 ASR + 真 LLM），各得 `SUCCEEDED`，失败原因互不串台。
- 复用命中那一趟照旧广播 `SKIPPED` 阶段事件、产物表零写入。
- **证据**：三份 run 的 `task_run` 行 + 日志摘录。

### AC4 — HTTP 面逐字节不变

四个写端点（`POST /api/tasks`、`/upload`、`/{taskNo}/translate`、`/{taskNo}/subtitles/regenerate`）的成功与失败分支（400 / 404 / 409）状态码与响应体形状与开工前逐项相同。

- **证据**：开工前 / 完工后各跑一遍同一组请求，响应逐项对照（含 `{taskNo, runNo}` 形状与 409 的 `{taskNo, runNo}` 体）。

### AC5 — 测试用例只搬不丢

- `mvn clean test` 的 `tests run / failures / errors / skipped` 四个数与开工前基线**逐项相同**。开工前实测基线（2026-10-21，本树，补入 `application-local.yml` 后）：**700 / 0 / 0 / 1**；完工后 **702 / 0 / 0 / 1**——`+2` 是审查轮给 `TaskRunSupervisor.listener` 与 `buildTaskConflictException` 两个新公开方法各补的一条覆盖，旧用例一条不丢（其中一条从 `SubtitleMainServiceTest` 搬到 `TaskRunSupervisorTest`，净变化 0）。
- `SubtitlePipelineTest` + `SubtitleTaskServiceTest` 的用例按 run 类型重分到三个测试类后，**逐条保留**，不许静默删用例。
- **证据**：开工前 / 完工后的 surefire 汇总；旧测试方法名 → 新测试方法名的对照表。

### AC6 — 重清洗语义定稿

- design.md 写明「RECLEAN 恒真重跑 + 无条件作废下游译文」为**终稿语义**及其理由（「内容没变就不作废」那条判据为何退场）。
- 单测钉住：旧 CLEANED 行哈希与新版**完全相同**时，下游译文**仍被**作废。
- **证据**：design.md 对应小节 + 该单测的用例名与运行输出。

### AC7 — spec 同步

- `directory-structure.md` 里因本任务失实的表述全部订正，至少包括：
  - 第 76 行「`SubtitleTaskService`——`TaskController` 唯一注入的应用服务」（`TaskController` 现在注入三个）
  - 第 91 行「管线构件」成员表里的 `SubtitlePipeline`（管线的所有者）
  - 第 213 行 `application/<上下文>/` 的目录说明——「`pipeline/`（管线构件：**管线的所有者**与各阶段类）」里的「管线的所有者」拆分后没了
  - 第 300 行 stereotype 表里的 `SubtitlePipeline` 举例
  - 第 468 行「`application/subtitle/pipeline/`（**编排件与被编排件同包**）」——「编排件」拆分后没了
  - 第 491 行「`SubtitleTaskService` 用 `@Service`」
- **包名 `pipeline/` 不动**（2026-10-21 裁定，见 design §5 T5）：只修描述，不改名。
- **证据**：spec diff。

## 5. 风险

| 风险 | 处置 |
|---|---|
| `SubtitleMainService` 变成 12 依赖的类（见 design §2） | 本任务接受，记账；这是「不抽共用」的直接代价，后续抽离的第一顺位候选 |
| 三个类各写一份 `conflictFor` / `requireIntactArtifact` / 五个 helper，重复显性化 | 有意为之（用户口径），design §5 T1 登记为后续抽离候选 |
| 搬迁过程中静默丢测试用例 | AC5 要求方法名对照表，逐条核对 |
| `TaskController` 注入从 1 个变 3 个 | 属预期；spec 第 491 行「Controller 只做接收 → 卫语句 → 委托 → 返回」不受影响 |
