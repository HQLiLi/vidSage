# 设计：命名与构件语义整理（Task 前缀对齐、管线方法与阶段类改名）

> 需求与决策见 `prd.md`（Q1–Q7 已定稿）。本文只补技术设计：改名总表、先后顺序、验证命令、回滚面。
> 执行清单见 `implement.md`。
>
> **2026-10-06 施工前追加**：复核 `TaskPipelineService` 依赖后，把 R2 的 `VideoTaskPipelineService` 修订为 `VideoTaskPipeline`（去后缀 + 改 `@Component`）。PRD 已同步加删除线注记；本文按新结论写。

## 1. 范围与不变量

### 动

- **8 个主代码类名** + **7 个测试类名**（文件级改名）；
- **3 个方法名**（`VideoTaskPipeline` 内部）；
- **7 个字段名**（六个 Stage 字段 + 管线的注入字段）；
- **7 个 stereotype**：`@Service` → `@Component`（六个 Stage + 管线本身）；
- **spec 对账**：`directory-structure.md`（含新增构件说明）、`error-handling.md`、`logging-guidelines.md`；
- **4 个 package-info**；
- 全部 Javadoc / 注释里的交叉引用；
- **删除** `docs/ddd-refactor-handoff.md`。

### 不动（硬不变量）

- 方法体逻辑、日志文案、异常类型与文案、HTTP 契约、SSE payload、JSON 字段名、SQL —— 一行都不动（R4）；
- `execute(TaskNo)` / `translateTask(TaskNo)` / `runPipeline(TaskNo)` / `prepareAndClean(VideoTask)` / `runTranslationPipeline(...)` 的**方法名按 R1 原样落地，不回调**（追加项已确认：类名从 `...PipelineService` 变成 `VideoTaskPipeline` 后，`pipeline.runPipeline(...)` 有轻微叠字，但「同步跑完」与 `execute`「投递即返」的分工比 `run` / `execute` 更立得住，且 PRD 明写勿重开 Q1）；
- `WorkspaceService` 类名与 stereotype、`VideoTaskService` 类名；
- 对外契约类（`TaskResponse` / `TaskSnapshot` / `TaskProgressMessage` / `TaskArtifact*`）与值对象 / 枚举（`TaskNo` / `TaskStatus` / `TaskInputType`）—— R2 明确排除；
- `web/controller/TaskController` 类名与字段名（见 §3-4）；
- **历史快照一律不改写**：`.trellis/tasks/archive/**`、`.trellis/workspace/**`（journal / traces）、`.zcode/plans/**`、本任务自己的 `prd.md`（它是需求来源，用旧名描述「改什么」是正确的；追加修订除外）。

## 2. 改名总表

### 2.1 类名（主代码）

| 旧 | 新 | 文件位置 |
|---|---|---|
| `TaskPipelineService` | `VideoTaskPipeline` | `application/task/` |
| `TaskService` | `VideoTaskService` | `application/task/` |
| `SubtitleService` | `SubtitlePreparationStage` | `application/subtitle/` |
| `SubtitleCleanupService` | `SubtitleCleanupStage` | `application/subtitle/` |
| `SubtitleTranslationService` | `SubtitleTranslationStage` | `application/subtitle/` |
| `AudioPreparationService` | `AudioPreparationStage` | `application/media/` |
| `MediaAcquisitionService` | `MediaAcquisitionStage` | `application/media/` |
| `TranscriptionService` | `TranscriptionStage` | `application/transcription/` |

**不动**：`WorkspaceService`（六个阶段类里唯一被 6 处共用的横切能力，本就是服务形态）。

### 2.2 类名（测试，镜像对应）

| 旧 | 新 |
|---|---|
| `TaskPipelineServiceTest` | `VideoTaskPipelineTest` |
| `TaskServiceTest` | `VideoTaskServiceTest` |
| `SubtitleServiceTest` | `SubtitlePreparationStageTest` |
| `SubtitleCleanupServiceTest` | `SubtitleCleanupStageTest` |
| `SubtitleTranslationServiceTest` | `SubtitleTranslationStageTest` |
| `AudioPreparationServiceTest` | `AudioPreparationStageTest` |
| `MediaAcquisitionServiceTest` | `MediaAcquisitionStageTest` |

`TranscriptionService` 无对应测试类，无需改名。

### 2.3 方法名（`VideoTaskPipeline`）

| 旧 | 新 | 可见性 | 调用点 |
|---|---|---|---|
| `run(TaskNo)` | `runPipeline(TaskNo)` | 包私有 | `execute` 内 1 处 + `VideoTaskPipelineTest` 13 处 |
| `runStages(VideoTask)` | `prepareAndClean(VideoTask)` | private | `runPipeline` 内 1 处 |
| `runTranslation(VideoTask, MediaInput)` | `runTranslationPipeline(VideoTask, MediaInput)` | private | `translateTask` 内 1 处 |

**不动**：`execute(TaskNo)`（唯一对外启动口）、`translateTask(TaskNo)`。

### 2.4 字段名

| 所在类 | 旧字段 | 新字段 | 类型 |
|---|---|---|---|
| `VideoTaskPipeline` | `subtitleService` | `subtitlePreparationStage` | `SubtitlePreparationStage` |
| `VideoTaskPipeline` | `cleanupService` | `cleanupStage` | `SubtitleCleanupStage` |
| `VideoTaskPipeline` | `translationService` | `translationStage` | `SubtitleTranslationStage` |
| `SubtitlePreparationStage` | `audioPreparationService` | `audioPreparationStage` | `AudioPreparationStage` |
| `SubtitlePreparationStage` | `transcriptionService` | `transcriptionStage` | `TranscriptionStage` |
| `AudioPreparationStage` | `acquisitionService` | `acquisitionStage` | `MediaAcquisitionStage` |
| `VideoTaskService` | `pipelineService` | `pipeline` | `VideoTaskPipeline` |

**不动的字段**：`web/controller/TaskController.taskService`（类型换代 `VideoTaskService`，字段名保留 —— 判据见 §3-1）。

**测试侧**：
- `VideoTaskPipelineTest` 的三个 mock 字段 `subtitleService` / `cleanupService` / `translationService` 跟改；
- `VideoTaskServiceTest` 的 mock 字段 `pipelineService` → `pipeline`（含 9 处 `verify(...)`）；
- **不动**：`SubtitlePreparationStageTest` 的局部变量 `audioPrep` / `transcribe`（局部别名，不是字段）、`AudioPreparationStageTest` 里的 `service()` 工厂方法。

### 2.5 stereotype（7 个改 `@Component`）

| 类 | 旧 | 新 |
|---|---|---|
| `VideoTaskPipeline` | `@Service` | `@Component` |
| 六个 `XxxStage` | `@Service` | `@Component` |

**不动**：`VideoTaskService` 与 `WorkspaceService` 保持 `@Service`。

`@Service` 与 `@Component` 行为完全等价（`@Service` 只是 `@Component` 的 `@AliasFor` 特化），零运行时影响。改动的实质是 stereotype 判据的收敛，见 §3-2。

### 2.6 Javadoc / 注释交叉引用

全部 `{@link Xxx}` / `{@code Xxx}` / 正文提及处按上表替换。落点（按文件）：

- `application/task/VideoTaskPipeline.java`：类 Javadoc 的 `{@code SubtitleService.prepare}`、`{@code SubtitleCleanupService.clean}`、`{@link TaskService}` 等；构造器 `@param` 的说明文字。
- `application/task/VideoTaskService.java`：`{@link TaskPipelineService#execute}`、`{@link TaskPipelineService#translateTask}`、正文两处 `{@code TaskPipelineService}`。
- `application/media/AudioPreparationStage.java`：`{@link MediaAcquisitionService#reuseExisting}`。
- `application/media/WorkspaceService.java`：正文 `TaskService（application/task）`。
- `application/subtitle/*.java`：`{@link SubtitleService}` / `{@link SubtitleCleanupService}` 互引、正文提及（含 `SubtitleCleanupService.java` 的自指注释）。
- `application/task/query/TaskArtifactFile.java`、`application/task/subscribe/TaskSubscriptionRegistry.java`：`{@code TaskService.openArtifact}` / `{@code TaskService.snapshot}`。
- `config/TaskProperties.java`：正文 `{@code TaskPipelineService}`。
- `domain/media/AudioArtifact.java`、`domain/media/AudioSpec.java`、`domain/media/MediaInput.java`：正文提及。
- `domain/shared/WorkspaceDir.java`、`domain/shared/WorkspaceKey.java`：正文提及。
- `domain/subtitle/CheckpointPolicy.java`（4 处类名合流说明 + `SubtitleServiceTest` 回归护栏名）、`domain/subtitle/TermLookupCommand.java`、`domain/task/TaskArtifactScan.java`。
- `shared/fs/ArtifactFiles.java`、`shared/progress/ProgressMapper.java`、`shared/subtitle/SubtitleArtifactNames.java`。
- 测试类 Javadoc：`AudioArtifactTest`（提 `AudioPreparationServiceTest`）、`Bcp47LanguageTest`、`CheckpointPolicyTest`（提 `SubtitleServiceTest` / `SubtitleCleanupServiceTest` / `SubtitleTranslationServiceTest`）、`TermVerificationTest`、`ProgressMapperTest`（提 `SubtitleService`）。

### 2.7 package-info（4 个）

`application/task/`、`application/subtitle/`、`application/media/`、`application/transcription/` —— 类名替换。`application/task/` 的「根包：`TaskService`（提交与查询唯一入口）、`TaskPipelineService`（异步阶段编排）」改写为「`VideoTaskService`（提交与查询唯一入口）、`VideoTaskPipeline`（异步管线本体）」。

### 2.8 删除

`git rm docs/ddd-refactor-handoff.md` —— 2026-10-05 的规划快照，已 100% 执行完毕，头部「状态：待执行」失实，且 §4-2（豁免清单）/ §4-3（LLM worker 不抽端口）/ §7-6（保持具体类仓库）三条已被 `10-06-port-inversion` 推翻，与现行 spec 矛盾。

## 3. 关键判断与取舍

### 3-1 字段名为什么只跟改这些

字段名回答的是「**这个字段在本类里扮演什么角色**」，类名回答的是「**这个类型是什么**」。判据是这句话还成不成立：

- `TaskController.taskService`：这个字段的角色就是「任务服务」，它握着的是任务服务 —— **成立**，保留。
- `VideoTaskService.pipeline`：角色是「管线」，它握着的就是那条管线 —— `pipelineService` 会继续暗示「它是个服务」，**不成立**，跟改成 `pipeline`。
- `VideoTaskPipeline.subtitleService`：角色被说成「字幕服务」，可它握着的是**一个阶段**，不是服务 —— **不成立**（这正是本任务要消灭的那个谎言，只是低了一层）。跟改成 `subtitlePreparationStage`。

备选「一律不动」会留下 `private final SubtitlePreparationStage subtitleService;` 这种类型说 stage、变量名说 service 的自相矛盾。

### 3-1a 为什么管线本身也从 `Service` 里拿出来（且不叫 `Stage`）

**入口判据收紧成一句硬话：web 层调谁，谁才是用例入口。** 实测 web 层的完整注入面：

| web 注入 | 类型 | 是不是用例服务 |
|---|---|---|
| `TaskController.taskService` | `TaskService` | **是** —— 全项目唯一的应用服务入口 |
| `TaskController.sseHub` / `subscriptionRegistry` | `TaskSseHub` / `TaskSubscriptionRegistry` | 否，机制类 |
| `MediaController.toolDiagnostics` | `ToolDiagnostics`（application 端口） | 否，端口 |

故 `application/` 里 web 真正调的只有 `TaskService` 一个。在这条判据下：

- `TaskService`（唯一入口）与 `WorkspaceService`（被 6 处共用的横切能力）→ `Service` 站得住；
- **其余 7 个全是内部件** —— 六个阶段类与管线本身。PRD 的事实 1 已写明这一点（「除 `WorkspaceService` 外，每个服务的调用者恰好只有一个」），但 R2 / Q5 只处理了前缀、漏了后缀。施工前复核依赖确认：`TaskPipelineService` 的消费者同样只有 `TaskService` 一处（构造器注入），全仓无第二处声明。

**为什么叫 `VideoTaskPipeline` 而不是 `VideoTaskPipelineStage`**：`Stage` 在本项目是「管线的一步」（`current_stage` / `StageEvent` / acquire→extract-audio→transcribe→write-subtitles→clean→translate）。`TaskPipelineService` 不是任何管线的一步，**它是那条管线的所有者与编排者**。硬套 `Stage` 会造出新歧义（下一个人会问「它属于哪条上级管线的哪个阶段」）。去掉后缀、名字直接是那个东西，是本判据下最诚实的选择。前缀仍对齐聚合根 `VideoTask`。

**命名为 `...Pipeline` 不与既有命名冲突**：全仓现有 `*Pipeline*` 仅此一处；`ExternalProcessRunner` / `YtDlpRunner` 在 `infrastructure/`，是「执行外部程序」的机制类，与 application 层的管线无关。

### 3-1b 为什么 R1 的方法名不因为类名变了而回调

Q1 否掉 `run` 的原文理由是「`run` 是能起的最没信息量的名字」——当时类名是 `...Service`，`service.run()` 确实毫无信息。类名变成 `VideoTaskPipeline` 后信息由类名供给，`pipeline.runPipeline(...)` 读起来会有轻微叠字，所以这条前提**确实变了**。

仍然保持已定名，理由：

1. PRD 明确「勿重开」Q1；
2. 公开面三个方法并列时，`execute`（投递即返，异步）/ `runPipeline`（同步跑完整条）/ `translateTask`（按需翻译）的分工比 `execute` / `run` 更立得住 —— 后者谁是异步谁是同步得靠读注释；
3. 改动面（14 处调用点）换来的是审美，不值。

叠字是**重复**，不是**歧义**——这笔账认了。

### 3-2 stereotype 判据收敛

改完之后 `application/` 的 stereotype 规则可以写成一句可执行的判据：

```
web 调用面（用例服务）   → @Service   （VideoTaskService）
横切能力（多处方共用）   → @Service   （WorkspaceService）
管线构件（管线本体与阶段）→ @Component （VideoTaskPipeline + 六个 XxxStage）
中立机制类               → @Component （TaskSubscriptionRegistry / Subscription）
```

这条判据把「什么叫服务」从「名字里有没有 Service」变成了**「有没有 web 调用面，或是不是多处方共用的横切能力」**——本任务要修的核心就在这。spec 的 stereotype 表要按此重写（§4.1）。

### 3-3 为什么 spec 对账必须同任务内做完

AC1 要求 grep 无旧名残留。spec 是**约束后续代码行为**的正典：`directory-structure.md` 的 stereotype 表若还写「`application/` 服务→`@Service`」而不认管线构件，下一个人加阶段类就会重新用 `@Service` + `XxxService` 命名，本任务的成果当场回退。故 spec 对账不是「顺手」，是任务的一部分（R5）。

### 3-4 为什么 `TaskController` 不改名

它跨了「服务层」与「对外契约层」两界。对外概念就是「任务」（路由 `/api/tasks`，前端也叫 task），HTTP 入口按对外概念命名是自洽的；改 `VideoTaskController` 只会让路由与类名对不上。PRD 倾向不改，据此落地。

同理 `VideoTaskService` 返回 `TaskSnapshot` 是**已知且可接受**的不一致：口径是「服务与聚合根对齐，读模型与对外契约沿用对外概念」。

### 3-5 为什么改名必须一个 commit 落地

Java 是编译期解析符号：改了类名而调用点没改，`mvn test` 直接不编译。8 个类互相引用（`SubtitlePreparationStage` 引用 `AudioPreparationStage`；`VideoTaskPipeline` 引用三个 Stage；`VideoTaskService` 引用 `VideoTaskPipeline`），任何中途切分都要在同一批文件上重改一遍，分批只增加 commit 数、不增加可审性。

**所以提交切分按「产物种类」切，不按「类」切**（见 §4）：

1. `refactor(backend): …` —— 代码改名（主代码 + 测试 + package-info）；
2. `docs(spec): …` —— spec 对账 + 删除 `docs/ddd-refactor-handoff.md`。

两个 commit 各自可编译可测（spec 不影响构建）。

### 3-6 实现方式：inline 机械替换，不派 subagent

本次是**全局符号改名**，主要风险是「漏改一处」与「多改一处（误伤同名不同物）」，不是设计判断。sed / 定向替换 + 编译器 + grep 三重门禁比自由编辑可靠，且 diff 全为名字行，审计成本低。质量检查阶段按工作流走 `trellis-check`。

**已核对的子串关系（决定替换顺序）**：

- `SubtitleService` **不是** `SubtitleCleanupService` 的子串（类名是 `Subtitle` + `Cleanup` + `Service`，不是 `SubtitleService` + `Cleanup`）；
- `TaskService` **不是** `TaskPipelineService` 的子串（同理，`Task` + `Pipeline` + `Service`）；
- 故 `TaskPipelineService` → `VideoTaskPipeline` 与 `TaskService` → `VideoTaskService` **互不干扰，顺序无关**；
- 测试类名**天然被同一条替换覆盖**：`TaskPipelineServiceTest` → `VideoTaskPipelineTest`、`TaskServiceTest` → `VideoTaskServiceTest`（因为后缀 `Test` 不在替换模式内）；
- 字段名替换一律**大小写敏感**：`pipelineService`（小写 p）不会命中 `TaskPipelineService` 里的 `PipelineService`；
- 六个 `XxxStage` 类名按「长名先于短名」排（`SubtitleTranslationService` → `SubtitleCleanupService` → `SubtitleService`），保险起见。

## 4. spec 对账清单

### 4.1 `directory-structure.md`

| 位置（按引文定位，行号会漂） | 改动 |
|---|---|
| §分层归属判据 · 值对象段 | `TaskService`（application/task）计算 → `VideoTaskService`（application/task）计算 |
| §`application/` 各层装什么 · 「用例编排」条 | 例子列表改名，并**拆成两条**：用例服务与横切服务（`VideoTaskService` / `WorkspaceService`）一条、**管线构件**（`VideoTaskPipeline` + 六个 `XxxStage`）一条，各配一句职责 |
| **新增**「构件说明」 | 见下 |
| §二级包怎么分 · 命名约定表 · Spring 组件行 | stereotype 按 §3-2 的四档判据重写；例子 `TaskService` → `VideoTaskService` |
| §应用服务层与 HTTP 入口 · 最后一行 | `TaskService` 用 `@Service` → `VideoTaskService` 用 `@Service` |
| §契约类的位置 · 参照行 | `application/task/TaskService.java` → `application/task/VideoTaskService.java` |

**新增的构件说明**（R5 + AC4 的落点）——写进 §分层归属判据 的 `application/` 段，内容要点：

1. `application/<上下文>/` 的编排类分两档，**判据是「有没有 web 调用面 / 是不是多处方共用的横切能力」**：
   - **服务**（`@Service`）：`VideoTaskService`（web 唯一入口）、`WorkspaceService`（被 6 处共用的横切能力）；
   - **管线构件**（`@Component`）：`VideoTaskPipeline`（**管线的所有者**，编排整条管线的执行：配额、虚拟线程、阶段顺序、进度广播、终态收口）与六个 `XxxStage`（**管线的一步**，只被上级管线按顺序调用一次）。
2. 管线构件为什么不能下沉 `domain/`：它们全是 I/O 密集（读工作区、调端口、写产物文件），而 `domain → shared` 禁止、`domain` 紧凑构造器不许做 I/O；纯业务判据已下沉完（`CheckpointPolicy` / `TermVerification` / `Bcp47Language` / `TaskArtifactScan` 等）。**问题不在分层，在名字。**
3. 命名约定两条：
   - 服务类前缀与聚合根对齐（聚合根 `VideoTask` → `VideoTaskService`）——但**对外契约与值对象不跟**（「任务」是对外概念，路由 `/api/tasks`），故 `VideoTaskService` 返回 `TaskSnapshot` 是可接受的不一致；
   - 管线构件的后缀按其角色取：**管线的所有者不带后缀**（`VideoTaskPipeline`），**管线的一步以既有领域词 `Stage` 结尾**（`current_stage` / `StageEvent` 是本项目既有词汇），一个阶段对应一个执行类。

### 4.2 `error-handling.md`

§「同一个值被检查两次不是冗余」段：`TaskService.loadTask` → `VideoTaskService.loadTask`。

### 4.3 `logging-guidelines.md`

- 首行「以 … 为依据」列表：`application/transcription/TranscriptionService.java` → `application/transcription/TranscriptionStage.java`；
- §日志级别表 · Service 行「例子」列：`TranscriptionService` → `TranscriptionStage`。

### 4.4 复查口径（AC1 的 grep 范围）

```bash
# 应为空
grep -rn "TaskPipelineService\|\bTaskService\b\|SubtitleCleanupService\|SubtitleTranslationService\|\bSubtitleService\b\|AudioPreparationService\|MediaAcquisitionService\|TranscriptionService" \
  backend/src .trellis/spec
# 应为空（方法名）—— 不带点前缀！
grep -rnE "\brunStages\b|\brunTranslation\b" backend/src .trellis/spec
# 且必须做正向断言：新名命中数 > 0（对「漏改」免疫，反向 grep 不免疫）
grep -rn "runPipeline\|prepareAndClean\|runTranslationPipeline" backend/src
```

> **方法名的 AC1 判据原本写作 `\.runStages(`（带点），是恒真的**：三个方法的调用点除测试外全是类内非限定调用（`runStages(task);`，名字前没有点），`run` → `runPipeline` 更是不在模式内。这个盲区让 R1 整个漏做、门禁却报通过（2026-10-06 实测，`trellis-check` 复核抓出）。**改名门禁必须同时做正向断言。**

排除项（历史快照，**预期命中**）：`.trellis/tasks/archive/**`、`.trellis/workspace/**`、`.zcode/plans/**`、本任务 `prd.md` / `design.md` / `implement.md`（它们本身就在描述改名）。

## 5. 验证与回滚

### 验证

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn clean test -q
```

- **必须 `clean`**：改名 = 删旧文件 + 建新文件，`target/classes/` 会留旧 class（2026-09-27 实测过这个坑）；`surefire-reports/` 会留旧的 `TEST-*.xml`（2026-09-26 实测过，包改名后凭空多出幽灵类）。
- AC2 判据：**用例总数与开工基线一致**（类名会变，只比数量）。基线值在 `implement.md` 里记。
- AC3 判据：`git diff` 里逐行看，只允许出现名字与 stereotype 注解的改动，方法体 / 日志 / 异常文案 / JSON 字段不得出现。
- AC1 判据：§4.4 两条 grep 为空。

### 回滚

两个 commit 各自独立 revert 即可：代码 commit 回滚 = 名字复原（无副作用）；spec commit 回滚 = 文档复原 + `docs/ddd-refactor-handoff.md` 复活。无数据迁移、无配置变更、无对外契约变更，回滚面不涉及运行时。

### 兼容性

- 前端零影响：前端只镜像 JSON 字段名与 `StageEvent` 这两种领域词汇，均不改（侦察已确认 `frontend/src` 无类名引用）。
- 装配零影响：无 `@Qualifier` / bean 名引用这些类（已 grep 确认，现有 `@Qualifier` 全部指向 `ChatClient` bean）。`@Service` → `@Component` 不改 bean 名（bean 名来自类名，与注解无关）。
- Spring bean 名会随类名变（`taskService` → `videoTaskService`、`taskPipelineService` → `videoTaskPipeline`），但无任何配置或代码按 bean 名取用。
