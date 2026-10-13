# 会话移交（2026-10-10）：SubtitlePipeline 状态迁移 × 广播收口 —— **规划已定稿、代码一行未动**

> 写这份文档的会话把**规划做完了**（四轮 grilling → `prd/design/implement` + 两个 jsonl 全部定稿），
> 然后在「`task.py start` + 批次 0 开工」这道门前按项目所有者要求停下、移交新会话。
> **下一句要干的事在下文「下一步」一节，别重新规划。**

## 一句话状态

任务 `10-08-pipeline-state-broadcast`（标题「SubtitlePipeline 状态迁移 × 广播收口拆分」）：

- 状态仍是 `planning`、`base_branch = dev`（已核对）、**无分支**、**未 `task.py start`**；
- 工件齐了：`prd.md`（已重写，需求 + AC1–AC7 + Q1–Q5）、`design.md`（新）、`implement.md`（新）、
  `implement.jsonl` / `check.jsonl`（已填）；
- **主源码与测试一行未动**；
- 工作区：`M` 三个（prd / 两个 jsonl）、`??` 两个（design.md / implement.md）。任务目录**本身早在
  2026-10-08 就入库了**（`9e9ecba`），所以归档时那条 pathspec 坑在这条任务上不存在。

开工前按序读：`implement.md`（执行计划，**示例代码全在里面**）→ `design.md`（构件契约、行为保真清单
10 条、被否方案）→ `prd.md`（需求与验收）。

## 下一步（按序）

1. **`task.py start`**（`planning → in_progress`）+ 从 `dev` 开 `feature/pipeline-state-broadcast`
   + `task.py set-branch` 把分支写进任务卡。
   （`create` 会把 `base_branch` 戳错的坑这条任务上已核过 = `dev`，不用再修。）
2. 顺手订正任务卡描述：`task.json` 里「…**另附两处已核实并发竞态的处置**」这半句已过时——
   那两处竞态按 Q5 另开了（用 `task.py set-meta` 改描述，或 `rename`）。
3. **批次 0 — 判据先行**（纯文档，**AI 写**）：落 `design.md` §七 的 #1–#4（spec 二级包表两份口径 +
   `-Notifier` / `notify/` 命名登记 + 契约位置表）。
   **做完立刻停下交审查**——这是本次唯一一处**改动既有判据**的地方（根包构成规则收窄），改错了后面会松一片。
4. 之后按 `implement.md` 批次 1 → 5 走。

### 用户手里压着两道门（别替他拍板）

1. **批次 0 开工许可**：工件已交他审，他还没说「动手」。
2. **任务目录入库**：`M` 三个 + `??` 两个尚未提交。独立一笔 `chore(task):` 还是并进批次 0 的提交，未定。

## 已定决策（本次会话 grill 出来的，**不重开**）

| # | 决策 | 关键理由 |
|---|---|---|
| Q1 | 范围取 **C1「协作件收口」** | 新增协作件管住「落库 → 广播 → 清订阅」；被否：(a)/(b) 只收部分面（到头来是搬家）、C2 消息自持静态工厂（治不到最疼的②）。代价：`VideoTaskService` 进验收范围 |
| Q2 | 落位取 **A「两个桶」** | 消息进新桶 `message/`（与已有 `exception/` 对称——spec 把「应用消息 / 应用异常」列为同一类目，异常早已立桶，消息留根与门面平级就是那个不对称感）；协作件另开一桶。被否：同桶装两件、不新建类（收口留管线、快照委托管线） |
| Q3 | 命名 **`notify/` + `TaskProgressNotifier`** | `Notif*` 全仓零命中；`Broadcast*` 3 文件命中（`TaskSubscriptionRegistry.broadcast(...)` 是既有动词）；`Publish*` 零命中但与 `subscribe/` 自述的「中立发布-订阅」叠。桶名与 `subscribe/` 成对：一个管「谁能收」，一个管「什么时候送、送之前先落库」。**新角色词要登记进 spec 命名表** |
| Q4 | notifier 边界取 **β「管到落库」** | notifier 收「落库 → 广播 → 终态清订阅」；**域状态迁移（`succeed`/`fail`/`translationSucceed`/`translationFail`）与全部日志、失败兜底留在调用点**。被否：α 只送不管库（治不到②）、γ 连状态迁移一起管（协作件驱动状态机，与 spec 三档表相冲） |
| Q5 | **两处并发竞态**（并发 `POST /translate` 双跑、提交查重 check-then-insert）与 **SSE / 通知面搬家** 均**另开**，不进本任务 | 前者要动 DB 约束改行为，与本任务「逐格不变」的验收口径相反；后者会让一个任务有两种验收口径 |

**§九「风险与开放项」里还有两处是提案、可改**：① `snapshot` 挂 notifier 导致 `VideoTaskService`
依赖数 3 → 4（有退路，退回去就是丢掉一半收益）；② 方法名 `advanceStage` / `relayStage` 未必一眼看得出
「落不落库」的差别。

## 实测事实（2026-10-10 对照源码，**别重新摸**）

- **`new TaskProgressMessage(...)` 5 处**：`SubtitlePipeline` :240（主管线进度）/ :255（翻译进度）/
  :268（主管线终态）/ :283（翻译终态）+ `VideoTaskService` :214（重连快照）。
- **「落库 → 广播」5 对**：`runPipeline` :188-189、`runTranslationPipeline` :209-210、
  `progressListener` :239-240、`failTask` :301-302、`failTranslation` :323-324。
- **终态 `try{广播}finally{complete}` 2 处**：`broadcastTerminal` :266 / `broadcastTranslationTerminal` :281。
- **管线里 `subscriptionRegistry` 只服务那 5 处**（grep 核过，没有别的用途）→ 收口后字段连同
  构造参数一起删。**别读成「保留」**（这句措辞已在工件里改明白：批 1 的 import 清单里出现
  `SubtitlePipeline.java` 是因为**那时它仍持有该字段**——批 1 是纯搬运、必须全绿；批 3 才换线）。
- **`repository` 字段要保留**：`findByTaskNo` 与三处**无广播**的落库（`translateTask` 幂等收敛 :135、
  `translationStart` :148、`start` :182）仍在用。
- **受影响测试 5 个既有 + 1 个新增**：`SubtitlePipelineTest`（fixture 加真 notifier，喂**同一个**
  repository mock 与**同一个** registry 实例）、`VideoTaskServiceTest`（构造补第 4 参数）、
  `TaskSubscriptionRegistryTest` / `TaskControllerTest` / `TaskSseHubTest`（只换 import）；
  新增 `application/task/notify/TaskProgressNotifierTest`。**断言一个字不放宽**。
- **AC2 的测试夹具豁免**：`TaskControllerTest:183` 自己 `new TaskProgressMessage(...)`（service 是 mock），
  这是豁免项，要在 evidence 里显式列出，免得 check 阶段误判。
- **消息类的落位是硬约束不是偏好**：`web` main 只许依赖 `application`（下沉 domain 会让 web 直连 domain）；
  application 要构造它（提到 `web/dto/` 会让 application 反向依赖 web）。**只能在 application 层内搬**。
- **异常语义是保真的关键**：`task.fail()` / `task.translationFail()` **必须留在调用点的 `try` 内**——
  今天它们抛 ISE 会被同一条 catch 当成「落库失败」吞掉记 ERROR（既有怪癖），搬进 notifier 就变了
  （design §五 第 4 条）。
- **基线可复用**：`.trellis/tasks/archive/2026-10/10-08-progress-weight-flattening/evidence/branch-sse.txt`
  （14 格）+ `10-08-pipeline-executor-split/evidence/{ac4-comparison.md,ref-sse-normalized.txt}`。
  **但本次仍要自己抓一次参照侧**（同机同轮的两个文件才排得掉环境差异）。

## 执行期分工与门（沿用既有口径）

- **批次 0 / 4 / 5 = AI 写**（判据、测试、收尾文档与证据）；**批次 1–3 = 项目所有者写**主源码，
  示例代码在 `implement.md` 里、**不贴会话**，每步先问再动；AI 按他写出来的代码改测试。
- 批次划分：**批 1** 消息纯搬运（结束**全绿**）→ **批 2** notifier 只建不接（结束**全绿**）→
  **批 3** 换线 + 删私有方法（结束**主源码可编译、测试编译不过**——夹具还按旧构造传参，
  **属预期，别顺手改**，批 4 统一处理）。
- **门**：每批做完立刻停等审查；提交 / 推送 / 合并 / 归档各是一道独立的门，沉默 ≠ 批准。

## 环境坑（memory 里有，别重踩）

- `cd backend && JAVA_HOME=/f/java_soft/java21 mvn ...`（Git Bash 下必须显式给 JDK 21）。
- 量测试前先 `rm -rf backend/target/surefire-reports`（陈旧报告会冒充本次结果）；**改结构 / 改名后必须 `mvn clean`**。
- 冒烟起 `spring-boot:run --server.port=18080`；收工**按端口 PID `taskkill //PID <pid> //F`**
  （`TaskStop` 杀不掉子 JVM）。
- 证据目录用任务下的 `evidence/`，不要用 Git Bash 的 `/tmp`。

## 另一条挂着的任务（别混进来）

- `09-24-vidsage-mvp4-summary`（MVP-4 结构化总结）：`planning` 至今**从未开工**，是路线图里唯一还没启动的
  MVP 子任务。项目所有者若想从重构切回功能，就是它。
- `09-29-subtitle-translate-qwen-mt`：2026-09-30 已拍板**暂缓**（未来可选测试任务），不用管。
- `10-09-workspace-port-inversion` 的 Out of scope 里挂了两笔小账：`MediaInput.LocalPath` 接线、
  `WorkspaceService` 两个重载合并——都没排期。
