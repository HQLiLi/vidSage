# 设计：状态迁移 × 广播收口

> 需求与验收见 [prd.md](./prd.md)，执行计划见 [implement.md](./implement.md)。
> 全部坐标以 2026-10-10 的 `dev`（`dd015fd`）为准。

## 一、拆之前 / 拆之后

**之前**——「组装 → 落库 → 广播 → 清订阅」这条序列散布在两个类的 5 处，每处各写一遍：

```
SubtitlePipeline
├── progressListener          :234   log.debug → updateStage → repository.update → broadcast(event)
├── translationProgressListener :251 log.debug → broadcast(event)            ← 刻意不落库
├── broadcastTerminal         :266   try{ broadcast(from(task)) } finally{ complete }
├── broadcastTranslationTerminal :281 try{ broadcast(fromTranslation(task)) } finally{ complete }
├── runPipeline               :187   succeed → update → broadcastTerminal → log
├── runTranslationPipeline    :208   translationSucceed → update → broadcastTranslationTerminal → log
├── failTask                  :295   try{ fail → update → broadcastTerminal → log } catch{ log ERROR }
└── failTranslation           :319   try{ translationFail → update → broadcastTranslationTerminal → 日志 } catch{ log ERROR }

VideoTaskService
└── snapshot                  :212   new TaskProgressMessage(taskNo, status, from(task), translationStatus)
```

**之后**——序列只有一份，在 `TaskProgressNotifier` 里；调用点只做**决定**（迁到哪个状态、什么文案、
记什么日志）与**兜底**：

```
application/task/
├── message/
│   └── TaskProgressMessage.java       （从根迁来，内容一字不改）
├── notify/
│   └── TaskProgressNotifier.java      @Component ← 新增
├── VideoTaskService.java              （根只剩它）
├── pipeline/SubtitlePipeline.java     （少一个依赖：不再认识 TaskSubscriptionRegistry）
└── subscribe/TaskSubscriptionRegistry.java  （这个类本身不动，只换它 import 消息的路径；管线的那个字段批 3 移除）
```

## 二、构件契约

### 2.1 `application/task/notify/TaskProgressNotifier`（新增协作件）

**职责一句话**：任务进度消息的**单一出口**——组装、落库、广播、终态清订阅只在本类里各写一遍；
**不驱动状态机**（`succeed` / `fail` / `translationSucceed` / `translationFail` 留在调用点）。

**档位**：`@Component`。判据（spec 三档表）：没有 web 调用面（不是服务）、不认领阶段、不干编排的机械件
（机制/协作件档，同档先例 `TaskSubscriptionRegistry` / `WorkspaceKeyGenerator`）。

**依赖**：`VideoTaskRepository`（domain 端口）+ `TaskSubscriptionRegistry`（application/task/subscribe）。

**方法集**（5 个，全部 public）：

| 方法 | 语义 | 对应今天的 |
|---|---|---|
| `void saveCurrentStageAndBroadcast(VideoTask task, StageEvent event)` | 主管线阶段推进：`updateStage` → 落库 → 广播 | `progressListener` 体内三行 |
| `void broadcastTranslationStage(VideoTask task, StageEvent event)` | 翻译阶段推进：**只广播、不落库**（差异显式成两个方法，不靠注释） | `translationProgressListener` 体内一行 |
| `void completeTask(VideoTask task)` | 主管线终态：落库 → 广播 `TaskStageEvent.from(task)` → 清订阅 | `runPipeline` / `failTask` 里的落库+广播 |
| `void completeTranslation(VideoTask task)` | 翻译终态：落库 → 广播 `TaskStageEvent.fromTranslation(task)` → 清订阅 | `runTranslationPipeline` / `failTranslation` 里的落库+广播 |
| `TaskProgressMessage createSnapshot(VideoTask task)` | 只组装不发送（SSE 连接建立时的快照） | `VideoTaskService.snapshot` 的组装 |

两个私有助手：`broadcast(task, event)`（组一条 + 广播）、`broadcastTerminal(task, stageEvent)`
（`try{广播}finally{complete}`——终态广播抛异常也必须清订阅，既有语义）。

**为什么成功终态与失败终态共用一个方法**：今天它们各写一遍是**重复**，不是差异——`completed` 被
`runPipeline`（成功）与 `failTask`（失败）都调用，两者的差别（`task.succeed()` vs `task.fail(msg)`、
各自的日志与兜底）全在调用点。同理 `completeTranslation`。**真正需要分开的是「主管线终态」与
「翻译终态」**（渲染器不同），所以是这两个方法而不是四个。

**为什么 `snapshot` 挂在这里**：快照就是「一条不送出去的事件」，它和终态广播挑的是同一个渲染器
（`TaskStageEvent.from(task)`）。今天这个选择写在两个类里（`SubtitlePipeline:269` 与
`VideoTaskService:214`）——挂进 notifier 后它只剩一处。代价：`VideoTaskService` 多一个依赖
（见 §四）。

### 2.2 `application/task/message/TaskProgressMessage`（搬家，内容不动）

从 `application/task/` 迁到 `application/task/message/`。**record 字段、`settled()`、Javadoc 一字不改**，
只改 `package` 声明与全部 import。

落位判据（spec「二级包怎么分」）：spec 把「应用消息 / 应用异常」列为**同一类目**的一等构件，
而应用异常早已立桶（`application/task/exception/`）。消息类留在根与门面平级是那条不对称感的来源；
本次把它补齐成 `message/`，根回归 spec 的通用规则「根只留门面」。

## 三、调用点改动表

| # | 位置 | 今天 | 之后 |
|---|---|---|---|
| 1 | `SubtitlePipeline.progressListener` :234 | `log.debug` → `updateStage` → `repository.update` → `broadcast` | `log.debug` → `taskProgressNotifier.saveCurrentStageAndBroadcast(task, event)` |
| 2 | `SubtitlePipeline.translationProgressListener` :251 | `log.debug` → `broadcast` | `log.debug` → `taskProgressNotifier.broadcastTranslationStage(task, event)` |
| 3 | `SubtitlePipeline.runPipeline` :187-189 | `succeed` → `update` → `broadcastTerminal` → `log.info` | `succeed` → `taskProgressNotifier.completeTask(task)` → `log.info` |
| 4 | `SubtitlePipeline.runTranslationPipeline` :208-210 | `translationSucceed` → `update` → `broadcastTranslationTerminal` → `log.info` | `translationSucceed` → `taskProgressNotifier.completeTranslation(task)` → `log.info` |
| 5 | `SubtitlePipeline.failTask` :299-308 | `try{ fail → update → broadcastTerminal → log.error } catch(RuntimeException){ log.error }` | `try{ fail → taskProgressNotifier.completeTask(task) → log.error } catch(RuntimeException){ log.error（原文案） }` |
| 6 | `SubtitlePipeline.failTranslation` :321-334 | 同上，翻译版 | `try{ translationFail → taskProgressNotifier.completeTranslation(task) → warn/error } catch(RuntimeException){ log.error（原文案） }` |
| 7 | `VideoTaskService.snapshot` :212-216 | `new TaskProgressMessage(taskNo, status, TaskStageEvent.from(task), translationStatus)` | `taskProgressNotifier.createSnapshot(task)` |
| 8 | `TaskSubscriptionRegistry`（subscribe/） | import `application.task.TaskProgressMessage` | import `application.task.message.TaskProgressMessage`（其余不动） |
| 9 | `web/controller/TaskController`、`web/sse/TaskSseHub` | 同上 | 同上（**web 层逻辑零改动，只换 import 路径**） |

**保留不动的**：`SubtitlePipeline` 的 `repository` 字段（`findByTaskNo` 与三处无广播的落库
— `translateTask` 的幂等收敛 :135 / `translationStart` :148 / `start` :182 — 都还在用）；
两个 listener 方法的 Javadoc（"翻译进度刻意不落 current_stage" 那段解释原样保留，它现在同时
解释了 `saveCurrentStageAndBroadcast` / `broadcastTranslationStage` 的差别）。

**删掉的**：`SubtitlePipeline` 的 `subscriptionRegistry` 字段与构造参数（grep 核实：它只服务
那 5 处广播/清订阅，没有别的用途）；`SubtitlePipeline` / `VideoTaskService` 的一批 import。

## 四、依赖方向

新增/变更的边（全部在 application 内 + 指向 domain，无跨层新边）：

```
pipeline/SubtitlePipeline ──┐
                            ├──→ notify/TaskProgressNotifier ──┬──→ domain/task/port/VideoTaskRepository
VideoTaskService ───────────┘                                 ├──→ subscribe/TaskSubscriptionRegistry
                                                              └──→ message/TaskProgressMessage
subscribe/TaskSubscriptionRegistry ──→ message/TaskProgressMessage
```

- **无环**：`message/` 只依赖 domain（是本上下文里最底的一格）；`notify/` → `subscribe/` → `message/`。
- **application 零 `infrastructure` import** 的既有事实不变（本任务不新增出站端口）。
- **`web/` main 只依赖 application** 不变：`TaskController` / `TaskSseHub` 只是 import 路径变长。
- **`VideoTaskService` 多一个依赖**（`TaskProgressNotifier`）——这是本次唯一一处依赖数上升的地方，
  换来的是"快照与在线事件同源"由结构保证。若评审认为不值，替代方案见 §八。

## 五、行为保真清单（逐字保留，AC4 的对照依据）

1. **两条 debug 日志**：`任务阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}`（:236）
   与 `翻译阶段事件: ...`（:253）——文案、级别、字段顺序不变，仍在**调用点**（listener 内）。
2. **四条终态日志**：`任务执行成功: taskNo={}, cost={}ms`、`任务翻译完成: ...`（info）、
   `任务执行失败: taskNo={}, status={}, message={}`（error）、`任务翻译失败: ...`（`cause == null`
   为 warn、否则 error）——级别与文案不变，仍在**调用点**。
3. **两条兜底 ERROR**：`任务失败状态落库失败，任务可能停留在 RUNNING: ...`（:306）与
   `翻译失败状态落库失败，任务可能停留在翻译 RUNNING: ...`（:332）——文案不变，仍在**调用点**的
   `catch (RuntimeException)` 里。
4. **异常语义不变**（最要紧的一条）：`task.fail(message)` / `task.translationFail(message)` **必须
   留在调用点的 `try` 内**——今天它们抛 ISE 会被同一条 catch 当成"落库失败"吞掉并记 ERROR
   （既有怪癖：`task.fail()` 抛的不是落库异常）。notifier 只被调用，不接管这两行。
5. **落库必须先于广播**：`saveCurrentStageAndBroadcast` / `completeTask` / `completeTranslation`
   内部顺序固定为 `repository.update` → `broadcast`。
6. **终态清订阅**：`try{广播}finally{complete}` 骨架原样保留在 `broadcastTerminal` 私有助手里；
   `TaskSubscriptionRegistry.complete` 由 notifier 调用，不在调用点。
7. **两条终态线的渲染器不合并**：主管线终态用 `TaskStageEvent.from(task)`、翻译终态用
   `TaskStageEvent.fromTranslation(task)`——即使 `from` 在翻译子状态非空时会转派，也**不**顺手并成一条
   （并了就得论证两处等价，是额外风险，且违反"逐格不变"）。
8. **翻译进度不落库**：`broadcastTranslationStage` 里没有 `repository.update`，也不碰 `current_stage`。
9. **未参与收口的落库原样留在管线**：`translateTask` 幂等收敛 :135、`translationStart` :148、
   `start` :182（这三处今天就没有广播）。
10. **SSE 面的行为零改动**：`TaskSseHub.send` 的 `settled()` 终态判定、`TaskController` 的双次快照读
    与退订逻辑全部不动。

## 六、测试改动

**既有测试（只换夹具与 import，断言一个字不放宽）**：

| 测试类 | 改动 |
|---|---|
| `application/task/pipeline/SubtitlePipelineTest` | fixture 加一个**真** notifier（喂**同一个** `repository` mock 与**同一个** `TaskSubscriptionRegistry` 实例）并换构造参数；import 改包。`inOrder` / `verify` 的落库与广播断言天然继续成立 |
| `application/task/VideoTaskServiceTest` | fixture 的 `new VideoTaskService(...)` 补第 4 个参数（真 notifier：repository mock + 新 registry）；导入改包 |
| `application/task/subscribe/TaskSubscriptionRegistryTest` | import 改包 |
| `web/controller/TaskControllerTest` | import 改包；:183 那处 `new TaskProgressMessage(...)` 是**测试夹具构造，豁免 AC2** |
| `web/sse/TaskSseHubTest` | import 改包 |

**新增测试（AI 写）**：`application/task/notify/TaskProgressNotifierTest`
- `saveCurrentStageAndBroadcast`：`inOrder` 断言 `updateStage` → `repository.update` → `broadcast`（顺序即保真清单第 5 条）。
- `broadcastTranslationStage`：广播一次且 `repository.update` **零次**、`current_stage` 不变。
- `completeTask` / `completeTranslation`：广播的 stage 分别取 `from` / `fromTranslation`；广播后订阅被清
  （`complete` 生效：再广播收不到）。
- `completeTask` 的 `try/finally`：让 registry 抛异常，断言订阅仍被清（保真清单第 6 条）。
- `createSnapshot`：只组装、不广播、不落库。

## 七、规范与文档订正清单

| # | 文件 | 订正 |
|---|---|---|
| 1 | `spec/backend/directory-structure.md` 二级包表 | `application/<上下文>/` 的桶列加 `message/`（应用消息）与 `notify/`（进度通知协作件）；「根留什么」改成**只留 `@Service` 服务门面**（删掉"＋应用消息"） |
| 2 | 同上·构件说明表 | 机制/协作件档的现行成员加 `TaskProgressNotifier` |
| 3 | 同上·命名约定表 | 端口/适配器之外补一条**组件后缀登记**：`-Notifier`（本仓首例，角色词答"它把状态变化送出去"）；桶名 `notify/` 登记 |
| 4 | 同上·契约类的位置表 | `TaskProgressMessage` 的位置列改为 `application/task/message/`；理由列补"2026-10-10 迁入 message/ 桶，与 exception/ 对称" |
| 5 | `application/task/package-info.java` | 根只留门面；`TaskProgressMessage` 从根说明里移走，加两个新桶的指向 |
| 6 | `application/task/message/package-info.java`（新） | 说明该桶装什么、不许装什么（禁 HTTP 类型、禁编排） |
| 7 | `application/task/notify/package-info.java`（新） | 同上；并写明与 `subscribe/` 的分工（一个管"谁能收"，一个管"什么时候送、送之前先落库"） |
| 8 | `domain/task/policy/TaskStageEvent` 类注释 | 那句「{@code VideoTaskService}（快照）与 {@code SubtitlePipeline}（两处终态广播）跨包共用同一份形状」里的调用者**改成 `TaskProgressNotifier`** ——不改就是留一句假话 |
| 9 | `web/dto/TaskResponse.java:12` 注释 | 提到 `application/task/TaskProgressMessage` 的路径改为 `application/task/message/TaskProgressMessage` |

## 八、被否的方案（记录取舍）

| 方案 | 否决理由 |
|---|---|
| **α「notifier 只送不管库」**（`repository.update` 留调用点） | 治不到②——「落库在先、失败就闭嘴」这条策略仍在 3 处各写一遍，而那正是将来最容易改错的地方 |
| **γ「连状态迁移一起管」**（`succeed` / `fail` 也进 notifier） | 协作件开始驱动状态机，与 spec「机制/协作件＝不干编排的机械件」档位相冲；且失败文案由调用点决定，搬进去只是把参数传得更远 |
| **C2「消息自持静态工厂」**（`TaskProgressMessage.of(task, event)`，不建协作件） | ①的价值本就被 `TaskStageEvent.from` 吃掉大半，只做 C2 等于花力气治最不疼的那段；且"快照挑 `from` / 终态挑 `from` / 翻译终态挑 `fromTranslation`"的渲染器选择会留在两个类里 |
| **消息与协作件同桶（`notify/` 装两件）** | 省的是一个 spec 明说可接受的 1 文件桶；代价是「应用消息」这个 spec 类目在包名上被淡化 |
| **不新建类：收口留管线，快照委托管线** | 读侧的组装挂到写侧执行器上，方向别扭；且将来第二条产生进度事件的链路会想要同一份收口而没有家 |
| **消息下沉 `domain/` 或提到 `web/dto/`** | 硬约束否决：`web` main 只许依赖 `application`（下沉 domain 会让 web 直连 domain）；application 要构造它（提到 web 会让 application 反向依赖 web） |

## 九、风险与开放项

1. **`VideoTaskService` 的依赖数上升**（3 → 4）。理由是"快照与在线事件同源由结构保证"；
   若评审认为不值，退路是让 `snapshot()` 留在原地自建消息（回到 5 处构造里的 1 处），
   AC2/AC4 随之放宽——**但那就退回 C1 的一半**。
2. **方法名已定**（2026-10-11 批 3 审查拍板）：`saveCurrentStageAndBroadcast` / `broadcastTranslationStage` /
   `completeTask` / `completeTranslation` / `createSnapshot`——以代码为准，原提案名作废。
3. **新角色词 `-Notifier` 与桶名 `notify/` 要登记**，否则会像 spec 里那条注记说的——不登记的后缀会变万能。
4. **中间态**：执行计划按「先建后用、最后删」排序——批次 1（纯搬运）与批次 2（只建不接）各自结束时
   **测试全绿**；批次 3 换掉管线的构造参数后，**主源码可编译但测试编译不过**（夹具还按旧参数构造），
   批次 4 统一处理。这与 10-09 的处置一致，**属预期，不要顺手改测试**。
5. **`TaskControllerTest` 的夹具构造豁免 AC2**，须在 evidence 里显式列出，避免 check 阶段误判。
