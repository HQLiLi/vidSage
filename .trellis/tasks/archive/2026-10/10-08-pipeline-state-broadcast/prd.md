# SubtitlePipeline 状态迁移 × 广播收口

> 任务卡 `10-08-pipeline-state-broadcast`（2026-10-08 建，同日搁置；2026-10-09 项目所有者定「先做
> WorkspaceService 端口倒置」，本任务于 2026-10-10 复工规划）。
> 技术设计见 [design.md](./design.md)，执行计划见 [implement.md](./implement.md)。

## Goal

把「任务状态变化 → 落库 → 广播进度事件」这条序列收进**一个**协作件，让主管线、翻译线、终态线与
`VideoTaskService` 的重连快照**共用同一处组装与送出**；`TaskProgressMessage` 从上下文根迁入独立桶。
**行为逐格不变**。

## 背景：三个重复面的实测计数（2026-10-10 对照源码）

| 面 | 计数 | 坐标 |
|---|---|---|
| `new TaskProgressMessage(...)` 四参构造 | **5 处** | `SubtitlePipeline` :240（主管线进度）/ :255（翻译进度）/ :268（主管线终态）/ :283（翻译终态）+ `VideoTaskService` :214（重连快照） |
| 「落库 → 广播」成对 | **5 对** | `runPipeline` :188-189、`runTranslationPipeline` :209-210、`progressListener` :239-240、`failTask` :301-302、`failTranslation` :323-324 |
| 终态 `try{广播}finally{清订阅}` | **2 处** | `broadcastTerminal` :266 / `broadcastTranslationTerminal` :281 |

**要害**：这 5 对里有 3 对各自带一份「落库失败就不广播、只记 ERROR」的兜底，文案还不一样
（`任务失败状态落库失败…` / `翻译失败状态落库失败…`）；而 `VideoTaskService` 的**重连快照**与管线的
**在线事件**是两个类各写一遍同一个形状。将来要加"广播前打点"、换事务性 outbox、给终态事件加字段，
都得记得改 5 处——漏一处产出的正是「在线看得到、重连看不到」这类最难查的不一致。

**要说清一句**：事件形状的真源 `TaskStageEvent.from` / `fromTranslation` 在 2026-10-06 下沉 domain 时
**已经单点**。本任务治的不是"形状同源"（那已经成立），是上面那三段**序列**的重复。

## Requirements

- **R1 新增协作件**：`application/task/notify/TaskProgressNotifier`（`@Component`），独占
  `TaskProgressMessage` 的构造，并拥有「落库 → 广播 → 终态清订阅」这条序列。
- **R2 消息迁桶**：`TaskProgressMessage` 迁到 `application/task/message/`；`application/task/` 根
  只剩服务门面 `VideoTaskService`（+ `package-info`）。
- **R3 四条线全走 notifier**：主管线进度、翻译进度、主管线终态、翻译终态。其中
  **「翻译进度刻意不落 `current_stage`」这条差异必须显式保留**——它是既有语义，不是待清理的重复。
- **R4 快照同源**：`VideoTaskService.snapshot()` 改为经 notifier 组装，SSE 快照与在线事件同源
  由**结构**保证，不再靠注释约定。
- **R5 边界**：notifier 管到落库为止；**域状态迁移（`succeed` / `fail` / `translationSucceed` /
  `translationFail`）留在调用点**，notifier 不驱动状态机。
- **R6 行为逐格不变**：SSE 事件流与快照响应体与 dev 基线逐字节一致；日志文案、异常语义一概不动。

## Acceptance Criteria

- [ ] **AC1 结构**：`application/task/` 根只剩 `VideoTaskService` + `package-info.java`；
      `message/` 与 `notify/` 两桶的落位、命名、`@Component` 档位符合 spec（含 spec 的登记）。
- [ ] **AC2 构造单点**：全仓 `new TaskProgressMessage(` 只在 `TaskProgressNotifier` 内命中
      （main + test；测试若需构造断言夹具另计，须在 evidence 里列出豁免清单）。
- [ ] **AC3 送出单点**：`SubtitlePipeline` 不再依赖 `TaskSubscriptionRegistry`；
      全仓 `broadcast(` / `complete(` 的调用只在 notifier 内命中。
- [ ] **AC4 行为保真**：design.md §五清单逐条落地——两条 debug 阶段日志、四条终态日志、
      两条「落库失败」ERROR 文案逐字不变；`task.fail()` 抛 ISE 被吞的既有语义不变；
      终态广播抛异常也必须清订阅（`try/finally`）不变。
- [ ] **AC5 双侧对照**：单实例冒烟，SSE 事件流与快照响应体对照 dev 基线**逐字节一致**；
      证据落任务 `evidence/`。
- [ ] **AC6 测试**：受影响的既有测试只换夹具、**断言一个字不放宽**；`mvn clean test` 全绿
      （改名 / 改结构后必须 `clean`）。
- [ ] **AC7 规范**：design.md §七 订正清单逐条落地，且不自相矛盾。

## Out of scope

- **两处并发竞态**：并发 `POST /translate` 双跑同一任务；提交查重的 check-then-insert 且 schema
  无唯一约束。二者要动 DB 约束与幂等语义（**改行为**），与本任务「逐格不变」的验收口径相反 → **另开**。
- **SSE / 通知面搬家**：`web/sse/TaskSseHub`、`subscribe/` 的落位重议 → **另开**（一个任务两种验收口径会打架）。
- **`TaskProgressMessage` 的方法面**：不动 `settled()`，不新增字段或静态工厂。
- **`MediaInput.LocalPath` 接线**、**SSE 契约变更**、**Javadoc 全局重构**。

## Key decisions

- **Q1（2026-10-10）范围取「C1 协作件收口」**：新增一个协作件管住「落库 → 广播 → 清订阅」，
  而不是只做「消息自持静态工厂」（C2）或只收终态线（(a)/(b)）。理由：(a)(b) 到头来是搬家，
  那个"靠约定同源"的疼点没治好；C2 治不到最疼的②（落库在先的顺序与失败兜底）。代价：
  `VideoTaskService` 进验收范围。
- **Q2（2026-10-10）落位取「两个桶」**：消息进新桶 `message/`（与已有的 `exception/` 对称——
  spec 把「应用消息 / 应用异常」列为**同一类目**一等构件，异常早已立桶，消息留在根与门面平级是那个
  不对称感的来源）；协作件另开一桶。被否：与消息同桶（`notify/` 装两件）／不新建类（收口留管线、
  快照委托管线——读侧组装挂在写侧执行器上，方向别扭）。
- **Q3（2026-10-10）命名取 `notify/` + `TaskProgressNotifier`**。命名账：`Notif*` 全仓零命中；
  `Broadcast*` 3 个文件命中（`TaskSubscriptionRegistry.broadcast(...)` 是既有动词，类名再用会与之叠）；
  `Publish*` 零命中但与 `subscribe/` 自述的「中立发布-订阅」语义叠。桶名 `notify/` 与 `subscribe/`
  成对：一个管「谁能收」，一个管「什么时候送、送之前先落库」。**新角色词登记进 spec 命名表**。
- **Q4（2026-10-10）notifier 边界取 β「管到落库」**：调用点保留域状态迁移与日志/兜底，
  notifier 收「落库 → 广播 → 清订阅」。被否：α「只送不管库」（治不到②）／γ「连状态迁移一起管」
  （协作件开始驱动状态机，与 spec「机制/协作件＝不干编排的机械件」档位相冲）。
- **Q5（2026-10-10）两处并发竞态与 SSE 面搬家均**另开**，不进本任务（见 Out of scope）。
