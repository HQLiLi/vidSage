# 会话移交（2026-10-08 第三轮）：SubtitlePipeline 状态迁移 × 广播收口 —— 任务已建、待规划

> 写这份文档的会话，上下文几乎全是**上一个任务**的（`10-08-pipeline-executor-split`，已完结归档：
> merge `0d388d4` / archive `142253d` / journal `e1c2e62`，均已推）。用户明确要求：
> **新任务在新会话里讨论**——所以这里只交代「从哪起、靶子在哪、哪四问待回答」，不谈方案。
>
> 开工前先读：归档任务 `.trellis/tasks/archive/2026-10/10-08-pipeline-executor-split/prd.md` 的 Out of scope
> （这个任务的来源就在那一节）。

## 一句话状态

任务 `10-08-pipeline-state-broadcast`（标题「SubtitlePipeline 状态迁移 × 广播收口拆分」）
**已创建、仍是 `planning`**：目录里只有 `task.json`，**prd / design / implement 都还没写**，
未 `task.py start`，未开分支，当前在 `dev` 上、工作区干净（除本文档）。

`base_branch = dev` 已核对无误。**任务目录尚未入库**（`git status` 里是 `??`）——
第一次提交必须把它带上，否则归档时自动提交会报 pathspec 错（上个任务踩过，见 memory）。

## 下一步怎么起

1. 读本文档 → 读「四个待决问题」→ **一轮轮 grill 用户**（口径：编号问题 + 我的推荐；
   能从代码/日志推断的问题不要问；等用户说动手再动）。
2. 收敛后写 `prd.md`；本任务属复杂任务，还要 `design.md` + `implement.md`，再 `task.py start`、开分支。
3. 执行期沿用既有分工：主源码用户写（每步先问，示例代码写进 `implement.md` 不贴会话），测试我按他写出来的代码改；
   改完即停等审查，提交 / 推送 / 合并 / 归档各是一道门。

## 靶子：这三角今天的重复面（2026-10-08 实测计数，别重新摸）

| 面 | 计数 | 坐标 |
|---|---|---|
| `TaskProgressMessage` 组装 | **5 处** | `SubtitlePipeline.java:239`（主管线进度回调）/ `:254`（翻译进度回调）/ `:267`（主管线终态）/ `:282`（翻译终态）+ `VideoTaskService.java:214`（重连快照） |
| `TaskStageEvent.from` / `fromTranslation` 形状 | **3 处** | `SubtitlePipeline.java:268` / `:283` + `VideoTaskService.java:214` |
| 「落库 + 广播」成对出现 | **4 处** | `progressListener`（先 `updateStage` + `repository.update`，后 broadcast）、`translationProgressListener`（**刻意只广播**，不落 `current_stage`）、`failTask`、`failTranslation`（各自 fail → update → 广播，且各自 catch 落库失败记 ERROR「任务可能停留在 RUNNING」） |

要害：「**在线事件**」与「**重连快照**」今天完全靠约定保持同源——同一个 `TaskStageEvent.from` 形状，
管线和 `VideoTaskService` 各写一遍。这是用户说的「真正的疼」。

## 四个待决问题（选项与我的推荐已备好，等你问用户）

1. **范围拆到哪一层**：`(a)` 只收终态那条线（`failTask` / `failTranslation` / 两个 `broadcast*Terminal`）／
   `(b)` 连两条进度回调一起收（「翻译进度刻意不落 `current_stage`」这条差异要显式建模）／
   `(c)` 连 `VideoTaskService` 的快照组装一起单点化，让事件与快照永不脱节。
   **我的推荐 (c)**——`(a)(b)` 更像搬家，重复面还在；但 (c) 面最大，要在设计期确认允许改 `VideoTaskService`。
2. **行为口径**：逐格不变 / 允许改行为并列差异栏。**我推荐逐格不变**，沿用上个任务的双侧对照套路
   （SSE 事件流 + 快照响应体）。
3. **两处并发竞态**（并发 `POST /translate` 会双跑同一任务；提交查重是 check-then-insert 且 schema 无唯一约束）
   要不要划进本任务。**我推荐分开另开**——它们要动 DB 约束与幂等语义（改行为），与本任务「不变」的验收口径相反；
   若用户想一次摸底两次改，就按同任务下的独立子任务做。
4. **SSE / 通知面搬家**（用户提过重构意向，口径「只允许搬家不动规则」）含不含。**我推荐不含**，另开，
   避免一个任务两种验收口径。

## 上一个任务留下的可复用资产

- **对照套路**：归档任务 `evidence/ac4-comparison.md`——基线复用 + 单实例 + 双通道抓取（SSE + 应用日志「任务阶段事件」）
  逐字节 diff；差异栏格式见同目录 `completion-report.md`。
- **可直接复用的基线**：`.trellis/tasks/archive/2026-10/10-08-progress-weight-flattening/evidence/branch-sse.txt`
  （14 格，`0/21/21/42/42/66/66/70/70/79/89/100/100/100`）——它与当前 dev 行为逐字节相同
  （`git diff 7cdcb2c^2 15e6112 -- backend/src/main` 为空，已核）。
- **冒烟套路**：`spring-boot:run --server.port=18080` → `POST /api/tasks {"url": …}` → 立刻
  `curl -sN /api/tasks/{taskNo}/events` 抓 SSE，日志通道用「任务阶段事件」行（无竞态）；
  收工按端口 PID `taskkill //PID <pid> //F`。
- **同期新增的分层判例**：`infrastructure` 的准入判据已改成「有没有状态」（见 `.trellis/spec/backend/directory-structure.md`
  二级包表与新端口判据例）——本任务若新增构件，落位按这条判。

## 环境坑（memory 里有，别重踩）

`mvn` 在 Git Bash 下要显式 `JAVA_HOME=/f/java_soft/java21`；量测试前删 `target/surefire-reports`，
改名 / 改结构后必须 `mvn clean`；起冒烟实例后 `TaskStop` 杀不掉子 JVM，按端口 PID `taskkill`；
证据目录用任务下的 `evidence/`，不要用 Git Bash 的 `/tmp`。
