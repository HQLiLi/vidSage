# 命名与构件语义整理：Task 前缀对齐、管线方法与阶段类改名

> **本任务的全部决策已于 2026-10-06 定稿**（在 `10-06-port-inversion` 的审查过程中逐轮敲定，见下文「决策存档」）。本 PRD 是那轮讨论的落点，**不需要重新讨论方向**——直接进设计/实现。

## Goal

把 2026-10-06 端口倒置任务审查期暴露的三类命名问题一次性收口：

1. `TaskPipelineService` 内部三个 `run*` / `execute` 名字互不可辨；
2. 任务上下文里 `Task*`（20 个类型）与 `VideoTask*`（聚合根 + 持久化三件套，4 个类型）两套前缀并存，**唯一说清「什么任务」的是聚合根**；
3. `application/subtitle/` 与 `application/media/` 下六个 `XxxService` **名不副实**——它们没有任何一个是用例入口，而是管线步骤。

**零行为变化**：只改名字与文档，不改任何方法体、日志、异常、JSON、SQL、HTTP 契约。

## 背景事实（2026-10-06 实测，是本任务的依据）

### 事实 1：application 服务是一棵只有一个根的单链树

```
web/TaskController
 └─ TaskService                          ← 唯一入口
     ├─ WorkspaceService                 ← 横切，被 6 处注入（唯一例外）
     └─ TaskPipelineService              ← 只被 TaskService 注入
         ├─ SubtitleService              ← 只被 TaskPipelineService 注入
         │   ├─ MediaAcquisitionService  ← 只被 AudioPreparationService 注入
         │   ├─ AudioPreparationService  ← 只被 SubtitleService 注入
         │   └─ TranscriptionService     ← 只被 SubtitleService 注入
         ├─ SubtitleCleanupService       ← 只被 TaskPipelineService 注入
         └─ SubtitleTranslationService   ← 只被 TaskPipelineService 注入
```

**除 `WorkspaceService` 外，每个服务的调用者恰好只有一个**——没有一个是独立用例。

**历史成因**：`MediaController` 的类 Javadoc 自述，原「临时面」的同步端点（`POST /api/media/audio`、ASR / 字幕各端点）已随 MVP-1 异步管线**收编删除**。这些 `XxxService` **当初就是独立用例**，被收编后失去了入口，却保留了名字与分层位置——它们是「**前用例**」，现在只承担管线步骤。这是事实 3 的改名的依据。

### 事实 2：它们**不能**下沉 domain（已被规则排除，不需再论证）

六个阶段类全是 I/O 密集：`WorkspaceService` + `shared/fs/ArtifactFiles` + `shared/subtitle/{SrtRenderer,SubtitleArtifactNames,SubtitleSegmentSplitter}` + `java.nio.file.Path`。两条硬规则直接挡住：

- `directory-structure.md`：**`domain → shared` 禁止**；`domain/` 类型的紧凑构造器里不许做 I/O；
- 它们产出的就是 `segments-*.json` / `subtitles-*.srt` 这些**文件**，检查点判定还依赖「文件存在且字节数 > 0」。

且**纯业务判据已经下沉完了**（`10-06-domain-logic-sink`：`CheckpointPolicy` / `TermVerification` / `Bcp47Language` / `TaskArtifactScan` 等）。剩下在这几个类里的正是「I/O + 调端口 + 编排」——按定义是 application 的活。**所以问题不在分层，在名字。**

## Requirements

- **R1 方法改名**（`application/task/TaskPipelineService`）：
  - `run(TaskNo)` → **`runPipeline(TaskNo)`**
  - `runStages(VideoTask)` → **`prepareAndClean(VideoTask)`**
  - `runTranslation(VideoTask, MediaInput)` → **`runTranslationPipeline(VideoTask, MediaInput)`**
  - `execute(TaskNo)` → **保持不动**（唯一的对外启动口，调用点 `pipelineService.execute(taskNo)` 读得通；「它只是投递、随即返回」这层含义由类 Javadoc 承担）
  - 要动的调用点：`TaskPipelineServiceTest` 里 13 处 `pipeline.run(...)`、2 处 `pipeline.execute(...)`，以及类 Javadoc 与各方法 Javadoc 内的交叉引用。
- **R2 服务前缀对齐聚合根**（范围只到服务层）：
  - `TaskService` → **`VideoTaskService`**；~~`TaskPipelineService` → **`VideoTaskPipelineService`**~~
    - **2026-10-06 追加修订**：`TaskPipelineService` → **`VideoTaskPipeline`**（去掉 `Service` 后缀，stereotype 改 `@Component`）。原结论按「只对齐前缀」定，漏了后缀；施工前复核依赖时确认它的消费者同样只有 `TaskService` 一个（与六个阶段类同档），留 `Service` 后缀会留下「它也是个入口」的误导。判据由「管线的所有者不是用例服务」给出。全量见 `design.md` §3-1a。
  - **不改**对外契约（`TaskResponse` / `TaskSnapshot` / `TaskProgressMessage` / `TaskArtifact*`）与值对象 / 枚举（`TaskNo` / `TaskStatus` / `TaskInputType`）——「任务」是对外概念（路由就是 `/api/tasks`，前端也这么叫），加 `Video` 前缀只是变长、不消除歧义。
  - 已知且可接受的不一致：`VideoTaskService` 返回 `TaskSnapshot`。口径是「**服务与聚合根对齐，读模型与对外契约沿用对外概念**」。
  - 待定（设计阶段定）：`web/controller/TaskController` 是否一并改名——它跨了「服务层」与「对外契约层」两界，倾向于**不改**（HTTP 入口按对外概念命名）。
- **R3 阶段类去 `Service` 后缀**：`application/` 下六个名不副实的服务改名为 **`XxxStage`**，理由见事实 3。
  - **2026-10-06 追加**：同一条判据也适用于 `TaskPipelineService`（它同样不是用例入口），但它不是「管线的一步」而是「管线的所有者」，故不带 `Stage` 后缀、直接叫 `VideoTaskPipeline`——见 R2 的追加修订。
  - `SubtitleService` → `SubtitlePreparationStage`（它承担的是 prepare 阶段：内含 acquire / extract-audio / transcribe / write-subtitles 四个子阶段）
  - `SubtitleCleanupService` → `SubtitleCleanupStage`
  - `SubtitleTranslationService` → `SubtitleTranslationStage`
  - `AudioPreparationService` → `AudioPreparationStage`
  - `MediaAcquisitionService` → `MediaAcquisitionStage`
  - `TranscriptionService` → `TranscriptionStage`
  - `WorkspaceService` → **不改**：它是被 6 处共用的横切能力（工作区根解析 + 建目录），本就是服务形态，改名反而不实。
  - 命名依据：「阶段」是本项目**既有的领域词**（`current_stage` / `StageEvent` / design 的 acquire→extract-audio→transcribe→write-subtitles→clean→translate），一个阶段对应一个执行类，名实正好对上。
  - 待定（设计阶段定）：`@Service` 注解保留还是改 `@Component`（`directory-structure.md` 的 stereotype 规则说 `application/` 服务用 `@Service`；阶段类仍是 application 层的 Spring 组件）。
- **R4 零行为变化**：方法体、日志文案、异常类型、HTTP 契约、SSE payload、JSON 字段、SQL 一律不动。JSON 不受 Java 类名影响，前端镜像的是字段名不是类名。
- **R5 spec 对账**：凡写到这些类名 / 方法名的 spec 段落与 package-info 一并改；`directory-structure.md` 需补一条「`application/<上下文>/` 下的 `XxxStage` 是管线步骤、不是用例入口」的构件说明，把事实 1 与事实 3 写进正典。
- **R6 前置依赖**：本任务**应在 `10-06-port-inversion` 归档之后再开**——两者都改 `TaskPipelineService`（端口倒置改了它的 import），基于未合并的分支开局会造成冲突。开工时从最新 `dev` 开 feature 分支。

## Acceptance Criteria

- [ ] **AC1** R1–R3 的名字全部落地，`grep` 复查无旧名残留（含测试、spec、package-info、Javadoc 交叉引用）。
- [ ] **AC2** 全量测试通过（`mvn clean test`），**测试总数与开工前基线一致**；类名会变故只比数量。
- [ ] **AC3** 零行为变化：`git diff` 中不出现方法体、日志文案、异常文案、SQL、JSON 字段的改动。
- [ ] **AC4** `directory-structure.md` 的「构件说明」已写明 `application` 层的 `XxxStage` 与 `XxxService` 的区别，以及「服务前缀对齐聚合根」的命名约定。

## 决策存档（2026-10-06，逐轮敲定，勿重开）

| # | 决策 | 结论 | 理由摘要 |
|---|---|---|---|
| Q1 | `run` 改名 | `runPipeline`（+`runTranslation` → `runTranslationPipeline`） | `run` 是能起的最没信息量的名字；`runToTerminal` 语义过头（配额被打断 / 状态机拒绝时会提前 return 保持 PENDING） |
| Q2 | `runStages` 改名 | `prepareAndClean` | 与方法体下面两行调用（`subtitleService.prepare` / `cleanupService.clean`）逐字对应；备选 `runAutomaticStages` 治不了「第一眼看不出是哪两段」这个病。耦合阶段表是可接受的代价——改阶段时名字会逼你回头看，比笼统名字悄悄过期更安全 |
| Q3 | `execute` 改名 | 保持 | 改名的价值在「少记一条规则」；(b) `startPipeline` 与 `runPipeline` 一样近，换不来 |
| Q4 | Q1–Q3 改动放哪 | 挪进本任务 | 都是「命名」不是「端口倒置」，塞进上个任务会让那个 diff 不可审 |
| Q5 | `Task*` 前缀范围 | 只对齐服务层 | 全量对齐（20 类型）会让 `VideoTaskStatus` / `VideoTaskNo` / `VideoTaskArtifactResponse` 明显变长；按用例命名（`TaskSubmissionService`）不解决「什么任务」且名不副实（该类管四条用例） |
| Q6 | 这活放哪 | 单独立项 | 20 类型 + spec 全文，量级是端口倒置的数倍 |
| Q7 | `XxxService` → `XxxStage` | 改 | 它们没有任何一个是用例入口（事实 1），名字在撒谎；「阶段」是既有领域词 |

## Notes

- 事实 1 的依赖图与事实 2 的 I/O 依赖清单均已实测，勿凭记忆推翻。
- 本 PRD 已含全部方向决策，**planning 阶段只需做技术设计（改名顺序、分批、验证命令），不需要再做需求探讨**。
