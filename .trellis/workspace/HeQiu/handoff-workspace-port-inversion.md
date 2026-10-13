# 会话移交（2026-10-09）：WorkspaceService 端口倒置 —— 任务已激活、工件已定稿、**代码一行未动**

> 写这份文档的会话把**规划**做完了（grilling → prd/design/implement → `task.py start` → 开分支），
> 然后在「批次 0 开工」这道门前按用户要求停下、移交新会话。
> **下一句要干的事在下文「下一步」一节，别重新规划。**

## 一句话状态

任务 `10-09-workspace-port-inversion`（标题「WorkspaceService 端口倒置：工作区定位与创建移出应用层」）：
`planning → in_progress`，`base_branch = dev`，**`branch = feature/workspace-port-inversion`**（已从 `dev` 切出并记进 `task.json`）。
工件齐了（`prd.md` / `design.md` / `implement.md` / `implement.jsonl` / `check.jsonl`），
**主源码与测试都还没碰**；工作区里唯一未跟踪的东西就是这个任务目录，**尚未提交**。

开工前按序读：`implement.md`（执行计划，示例代码全在里面）→ `design.md`（契约与订正清单）→ `prd.md`（需求与验收）。

## 下一步

**批次 0 — 判据先行**（纯文档，不碰代码）：按 `design.md` §七 的 #1、#2、#5、#6、#7 五处落地。
`implement.md` 批次 0 一节写了改哪五处、以及「先改判据、再搬代码」的顺序铁律。

**批次 0 走完就停下交审查**——它是本次唯一一处**放宽既有判据**的地方（`infrastructure/fs/` 那一格从「只读 stat 适配」改成「读数与定位/创建」），改错了后面会松一片。

### 用户手里压着两道未答的门（别替他拍板）

1. **批次 0 开工许可**：上一会话已经把 diff 计划摆出来了，用户还没说「动手」。
2. **任务卡入库**：`?? .trellis/tasks/10-09-workspace-port-inversion/` 尚未提交。它是纯过程记录类文档（按分支规则 v5 例外可直接在 dev 上提交），顺手做了能免疫归档时那条 pathspec 坑（历史上踩过 5 次，全在 10-05 前）。独立一笔 `chore(task):` 还是并进批次 0 的提交，用户未定。

## 已定决策（本次会话 grill 出来的，**不重开**）

| # | 决策 | 关键理由 |
|---|---|---|
| Q1 | 端口 `WorkspaceDirectoryManager`（`application/media/port/`），实现 `NioWorkspaceDirectoryManager`（`infrastructure/fs/`） | 照 `FileStateReader` 先例。命名账：全仓 `*Manager` 零占用，但本仓首例——**spec 端口命名表要登记**，否则「Manager」会变成万能后缀 |
| Q2 | stat **必须**从实现里摘出去 | spec 的 `infrastructure → application` 窄口只开给 `implements 出站端口`；让实现注入一个它不实现的 `FileStateReader` 会是全仓第一例 |
| Q3 | 方案 A：调用点**各拿两个依赖**（`manager` + `generator`），不留 application 侧串接壳 | 留壳 = 今天的 `WorkspaceService` 换个名，FS 拆分白做一半 |
| Q4 | `WorkspaceProperties` **留 `config/`** | 3/4 先例（`TaskProperties` / `SubtitleCleanupProperties` / `SubtitleTranslateProperties` 都在 `config/`），`infrastructure → config` 已是既有做法 |
| Q5 | 顺带补依赖表的 `config` | 表漏列但实际已 6 处 import（application 3 + infrastructure 3） |
| Q6 | 协作件名 **`WorkspaceKeyGenerator`**、`@Service`、落 `application/media/` 根 | 用户定名（「它产出的就是这个 key」）；`-Generator` 全仓零占用，本仓首例。**配套必改**：`WorkspaceKey` 类注释那句「生成入口只有本类的两个静态工厂」要改写成「公式在本类、生成器按输入取事实」——不改就是留一句假话（`design.md` §七 #10） |

## 实测事实（2026-10-09 对照源码，**别重新摸**）

- `WorkspaceService`（`application/media/WorkspaceService.java`，`@Service`）：3 个 public —— `workspaceFor(MediaInput)` / `workspaceFor(String, WorkspaceKey)` / `workspaceRoot()`；2 个 private —— `rootDir()`（配置根 + `WorkspaceDir.FALLBACK_NAME` 回退）/ `ensureCreated()`（`Files.createDirectories` + ISE + debug 日志）。**零状态、零业务规则**。目录名公式与身份 key **早已在 domain**（`WorkspaceDir.of` / `WorkspaceKey` / `MediaInput.getContentKey`），它只是调用方。
- **6 个注入点**：`VideoTaskService`（:76/:86）、`SubtitlePipeline`（:55/:72）、`AudioPreparationCoordinator`（:48/:59）、`SubtitlePreparationCoordinator`（:38/:53）、`SubtitleCleanupCoordinator`（:51/:70）、`SubtitleTranslationCoordinator`（:72/:96）。
- **调用点坐标**：`workspaceFor(MediaInput)` 在 `AudioPreparationCoordinator:107` / `SubtitlePreparationCoordinator:95` / `SubtitleCleanupCoordinator:137` / `SubtitleTranslationCoordinator:138` 与 `:187`；`workspaceFor(label, key)` 在 `VideoTaskService:313`；`workspaceRoot()` 在 `VideoTaskService:134/:239/:344` + `SubtitlePipeline:343`。
- **`MediaInput.LocalPath` 在生产代码里零构造点**——全仓 `new MediaInput.LocalPath(...)` 只在测试里；`MediaController` 只做 `/tools` 诊断。故 `getMediaFileInfo` 今天**恒返回 `NOT_APPLICABLE`**。该变体是**刻意留的未接线路线**（域注释 + `LocalFileMediaAcquirer` 整类 + 三个单测），**不删、只搬**。
- **`infrastructure → application` 现有 4 处 import**，全部是「自己实现的那个端口」或「该端口的出参类型」（`NioFileStateReader` / `SemaphoreTaskConcurrencyGate` / `ProcessToolDiagnostics` ×2）。**注**：`SemaphoreTaskConcurrencyGate` 还 import 了 `config/TaskProperties` —— 这是 `infrastructure → config` 的既有先例（共 3 处）。
- **受影响测试类 7 个**：`WorkspaceServiceTest`（9 例，要**一分为二**，见 `design.md` §六）+ `SubtitlePipelineTest` / `VideoTaskServiceTest` / `SubtitleCleanupCoordinatorTest` / `SubtitlePreparationCoordinatorTest` / `AudioPreparationCoordinatorTest` / `SubtitleTranslationCoordinatorTest`（只换构造夹具，**断言一个字不放宽**）。
- **两处写死 `vidsage.workspace.root` 的文案指向配置键而非类名 → 不改**：`VideoTaskService:159` 的上传失败提示、`WorkspaceService:111` 的 ISE（后者随代码搬进实现）。

## 执行期分工（沿用既有口径）

- **批次 0**（spec 四格 + 两个 `package-info` 的 Javadoc 文本）——**文档，AI 写**；
- **批次 1–3**（端口、实现、`WorkspaceKeyGenerator`、6 个调用点、删 `WorkspaceService`）——**主源码，用户写**，示例代码在 `implement.md` 里、**不贴会话**，每步先问再动；
- **批次 4**（测试重分 + 6 个测试类换夹具）——**AI 写**，按用户写出来的代码改；
- **批次 5**（收尾文档 + `evidence/`）——AI 写。

**门**：每批做完立刻停，等审查；提交 / 推送 / 合并 / 归档各是一道独立的门，沉默 ≠ 批准。
**注意中间态**：批次 3 删掉 `WorkspaceService` 后测试会编译不过——**属预期，别顺手改**。

## 一个语义陷阱（拆分后唯一一处）

Java 实参从左到右求值，写成 `workspaceDirs.workspaceFor(input.dirLabel(), keyGenerator.resolve(input))`
会让 `input.dirLabel()` 先跑，`input == null` 时抛 **NPE 而不是原来的 IAE**「媒体输入不能为空」。
生产路径上 4 个 Coordinator 各自已有前置卫语句，此分支不可达，但单测断言它在——
**写成两行**（先 `resolve` 后 `workspaceFor`），语义与今天逐字相同。

## 环境坑（memory 里有，别重踩）

- `cd backend && JAVA_HOME=/f/java_soft/java21 mvn ...`（Git Bash 下必须显式给 JDK 21）。
- 量测试前先 `rm -rf backend/target/surefire-reports`（陈旧报告会冒充本次结果）；**改结构 / 改名后必须 `mvn clean`**。
- 证据目录用任务下的 `evidence/`，不要用 Git Bash 的 `/tmp`。

## 另一条挂着的任务（别混进来）

`10-08-pipeline-state-broadcast`（状态迁移 × 广播收口）仍在 `planning`，工件未写、`base_branch = dev`、无分支。
用户 2026-10-09 明确：**先做本任务，那条搁置**。它的靶子与四个待决问题见
`.trellis/workspace/HeQiu/handoff-pipeline-state-broadcast.md`。
