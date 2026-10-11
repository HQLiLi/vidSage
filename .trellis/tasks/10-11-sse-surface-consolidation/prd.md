# SSE 面收口：订阅配对内聚、终态判据单一化、断连可见

## Goal

SSE 这条链从后端连接管理到前端订阅消费，中间散着几处「同一件事写两遍」和「出错时没人管」：

- **后端**：`TaskSseHub` 与 `TaskSubscriptionRegistry` 两张表各存一份在线状态，两者的**配对由
  `TaskController` 手工完成**——历史已因配错踩过两次泄漏，配对责任不该在调用方。另有 `TaskSseHub.remove`
  的 Javadoc 承诺「清空桶」但实现从不删，每个跑完的任务在 hub 里永久留一个空 list。
- **前端**：终态判据（主状态终态 且 翻译非 RUNNING）在项目里编码了**三处**——前端两份、后端一份；
  SSE 连接出错（EventSource `error`）**没有任何处理**，任务不存在或后端不可达时页面无限静默。

本次把这条链的「判据」和「配对」各自收敛到单一来源，并让断连对用户可见。

顺带统一判据的英文词汇：该概念现跨栈有两个词——后端 `settled`、前端 `terminal`。**定案：统一到
`terminal`**（见「已定决策」D1）。

## 背景与已确认事实（2026-10-11 勘察）

| 事实 | 证据 |
|---|---|
| hub 自持 `taskNo → List<SseEmitter>`，注册表自持 `taskNo → List<Consumer>`；两张表各有用途（前者供心跳 / complete 遍历，后者供管线广播），**不是冗余** | `web/sse/TaskSseHub.java:48`、`application/task/subscribe/TaskSubscriptionRegistry.java:57` |
| 配对由 controller 手工完成，共 7 步编排 | `web/controller/TaskController.java:165-191`（读快照 → register → subscribe → 重读快照 → send → 挂 onCompletion → settled 判断 close） |
| 配对错误已有两次真实事故：SSE 重复推送、注册表泄漏 | MVP-1 `research/acceptance-evidence.md`「事件插曲：SSE 重复推送」；`TaskController.java:180-183` 注释 |
| `TaskSseHub.remove` 从不 `emitters.remove(taskNo)`，空桶永留；Javadoc 却写「不为死任务留空桶」 | `web/sse/TaskSseHub.java:158-166` 注释 vs 实现；`TaskSseHubTest` 无相关断言（grep 零命中） |
| 注册表侧 `complete()` 会删桶，hub 侧不会——两侧生命周期不对称 | `TaskSubscriptionRegistry.java:96-101` |
| 终态判据在项目里共三处 | 后端 `application/task/message/TaskProgressMessage.java:36-38`（→ `VideoTask.isEventSettled`）；前端 `composables/useTaskEvents.ts:11-15`、`stores/task.ts:178-181` |
| 后端侧已做过一次单源（2026-10-06）：原 `TaskController.isTerminal` 与 `TaskSseHub.send` 各写一遍，现只剩调用 | `domain/task/VideoTask.java:250-256` Javadoc 自述；本次是把单源延伸过网线到前端 |
| **命名陷阱**：`VideoTaskService.java:143/148/158` 也有 `boolean settled`，但指「上传临时文件已归位」，与终态判据无关，改名波及范围内**必须排除** | `application/task/VideoTaskService.java:143`（`submitUpload` 的 finally 清理判据） |
| 前端 SSE 无任何错误处理 | `frontend/src` 全仓 grep `onerror` / `addEventListener('error'` **零命中**；`useTaskEvents.ts:45-62` 只注册了 `progress` 监听 |
| 前端已有「任务不存在卡」的错误态通道 | `stores/task.ts:22`（`TaskLoadError = {kind:'not-found'} \| {kind:'error'}`）、`stores/task.ts:90-98`（404 → not-found） |
| 前端订阅点有两处：工作区页加载后、翻译触发后 | `stores/task.ts:106-110`（`subscribe()`）、`:120-127`（`translate()` 内重开） |
| `TaskProgressMessage` 不带 `@Schema`（SSE 端点返回 `SseEmitter`，注解进不了 `/v3/api-docs`） | `spec/backend/directory-structure.md:304` |

## Requirements

- **R1（后端）订阅配对内聚**：`register` 与 `subscribe` 的配对不再由 web 调用方负责，
  收进 `TaskSseConnectionManager.open()` 单一入口（见 D3）。
- **R2（后端）修空桶泄漏**：`TaskSseConnectionManager`（原 `TaskSseHub`，见 D3a）在任务最后一个
  emitter 摘除时删掉该 taskNo 的空桶；同步订正 `remove` 的 Javadoc（现值与实现不符），并补测试覆盖。
- **R3（判据单一来源 + 词汇统一）**：后端在 `TaskProgressMessage` 上把终态判据作为**字段**（`terminal`）
  一并送出；前端两处重复编码**删除**，改读该字段。判据唯一来源 = domain 的 `VideoTask.isEventTerminal`。
  同时把判据的英文词汇从 `settled` 统一为 `terminal`（见 D1 的波及面清单）。
- **R4（前端）断连可见**：SSE 连接出错时不再静默，按致命 / 瞬时两类分别表现（见 D2）。
- **R5（契约一致）**：`terminal` 契约的三处同步——后端 record、`frontend/src/types/task.ts` 的镜像、
  `spec/backend/directory-structure.md:304` 那行；三者必须同批落地，不留半截。

## Acceptance Criteria

**AC1（后端·空桶回收）** `web/sse/TaskSseConnectionManager` 在某任务最后一个 emitter 被摘除后，
`emitters` 不再保留该 taskNo 的键（不再积累空 `CopyOnWriteArrayList`）；`remove` 的 Javadoc
与实际行为一致。单测覆盖「最后一个连接摘除即删桶」与「同任务仍有其他连接时不删桶」两条。

**AC2（后端·配对单点）** SSE 订阅的「登记连接 → 挂订阅 → 重读快照 → 推送 → 终态收口」只在
`TaskSseConnectionManager.open()` 写一遍：`TaskController` 源码中不出现 `subscriptionRegistry`
字样，其构造依赖从 3 个降为 2 个；`subscribeEvents` 方法体 ≤ 3 行。顺序正确性有单测覆盖
（含「窗口内任务已终态 → 推完快照即退订」这条历史缺陷路径）。

**AC3（契约·`terminal` 字段）** SSE 事件的 data JSON 含布尔字段 `terminal`：终态事件为 `true`，
非终态为 `false`；取值单源于 `VideoTask.isEventTerminal`（构造时算一次，调用方不得自行推导）。

**AC4（命名单一，可 grep 判）** 检查范围限定 `backend/`、`frontend/`、`.trellis/spec/` 三处
（**不含本任务卡自身**——它的「现状事实」表按惯例要保留旧名做历史陈述）：

- `TaskSseHub` 零残留（含 Javadoc / 注释 / spec）；
- `isEventSettled` 零残留；`settled` 仅剩 `VideoTaskService.java:143/148/158` 那处异义用法
  （「上传临时文件已归位」，本任务不动）；
- 前端 `isTerminalEvent` 零残留。

**AC5（前端·瞬时断连可见）** SSE 传输中断（后端停掉）时，工作区页在数秒内出现不遮挡进度的
「连接中断，正在重连…」提示；后端恢复后提示消失、事件流续上，进度无感恢复。
可复现：Playwright 拦截 `/api/tasks/*/events` 先 `abort` 再放行，或代码外停/启后端。

**AC6（前端·致命断连可见）** `/events` 返回非 200 时页面**不空白、不无限加载**：重拉一次快照确诊，
任务确实不存在则显示现有「任务不存在」卡，其余情况显示现有通用错误卡。
可复现：Playwright 对该路径返回 404 后观测页面。

**AC7（不回归，双侧对照）** 单实例冒烟跑一条真实任务，SSE 事件流与 dev 基线对照——事件条数、
顺序、各字段值与基线一致，**唯一差异是新增的 `terminal` 字段**；`GET /api/tasks/{taskNo}` 快照
响应体与基线逐字节一致（该响应不含 `terminal`）。后端 `mvn test` 全绿（基线 488 条以上）；
前端 `type-check` / `lint` / `build` 三命令全绿。

## 不做（Out of scope）

- **重议 `web/sse/` 与 `subscribe/` 的落位判据**：spec 已写死（`spec/backend/directory-structure.md:56,76`），
  判据自洽，本次不动。
- **SSE 契约的其余变更**（多任务订阅、按用户隔离、事件形状大改）：无产品诉求，另议。
- **两张表合并**：受「application 层禁 HTTP 类型」约束，合并会破坏既有分层判据；两张表各有用途，
  本次只收口配对责任，不合并存储。
- **AC6 的「致命断连」渲染出口**（2026-10-11 执行期发现，项目所有者拍板「先登记，不改」）：
  `diagnoseFatal()` 把确诊结果写进 `loadError`，但工作区视图的优先级是「就绪态分支
  （`isSucceeded && taskView`）→ 进度卡 `isRunning` → 失败卡 `isFailed` → 错误卡 `loadError`
  → loading」——`loadError` 由 `diagnoseFatal` 写入时，**四种状态下都渲染不出来**
  （RUNNING/PENDING 走进度卡、FAILED 走失败卡、SUCCEEDED 走就绪分支、taskView 为空时
  `diagnoseFatal` 提前 return）。即 **R4 的「致命类不再静默」实际未达成**，AC6 只由旧的
  `load()` 路径（任务真不存在 → 404 → 任务不存在卡）满足。证据见
  `evidence/ac5-ac6-verification.md`。
  **不改的理由**：本任务 AC7 的验收是「唯一差异是新增 `terminal` 字段」的干净对照，动视图会让它失真。
  **待另开任务**：把 `loadError` 提到 `isRunning` / `isFailed` 之前，或给致命断连单独做一条
  不遮挡的提示（形态与 `reconnecting` 对齐）。
- **写失败路径不退订（第二轮审查意见 ③）**（2026-10-11 发现，项目所有者拍板「另立任务，这个有点大」）：
  `TaskSseConnectionManager.sendTo` 的写失败分支只 `remove(taskNo, target)`，**够不着该连接的订阅句柄**
  （句柄只活在 `open()` 的 lambda 闭包里），于是注册表里那条订阅会留到任务终态才被
  `TaskProgressNotifier` 的 `complete(taskNo)` 清掉；期间每次广播都白送给一个已经出不去的连接。
  **另立 `10-11-sse-connection-pairing`**（planning，未开工）：把连接表的元素从 `SseEmitter` 换成
  配对持有的类型，让清理路径拿得到自己那一对；连带 `register()` 并入 `open()`。诊断与已定决策
  全部记在该任务 `prd.md` 里。
  **不在本任务做的理由**：要改 `emitters` 的值类型，连带 `sendTo` / `beat` / `remove` / `emitterCount` /
  `hasBucket` 与三条测试——是本仓核心数据结构的结构性改动，且缺陷本身是**理论隐患**
  （「写失败但异步请求永不完结」的必要条件构造不出来）。

## 已定决策

### D1（2026-10-11，项目所有者拍板）终态判据：后端出字段，词汇统一到 `terminal`

判据的权威在 domain（`VideoTask.isEventSettled`），前端重新编码它是重复劳动，且
`useTaskEvents.ts:7-9` 的注释恰在解释「为什么还要看翻译子状态」——那正是重复编码最容易写错的位置。
故：后端把判据结果作为字段送出，前端两处删除、改读字段。

词汇随之从 `settled` 统一到 `terminal`（理由：前端 `isTerminalEvent` 与 `TaskSseHub:116` 局部变量
已在用该词，且它是流式 API 的标准词「terminal event」）。

**改名波及面**（`settled` → `terminal`，仅限终态谓词这层含义）：

| 文件 | 位置 | 动作 |
|---|---|---|
| `domain/task/VideoTask.java` | `:262` `isEventSettled` | 改名 `isEventTerminal`（Javadoc 文案同步） |
| `domain/task/package-info.java` | `:5` 登记项 | 随改 |
| `application/task/message/TaskProgressMessage.java` | `:31-38` `settled()` 方法 | 改为 record 组件 `terminal`（紧凑构造器算一次，落点唯一） |
| `web/sse/TaskSseHub.java` | `:112-116` 注释 + 调用 | 随改 |
| `web/controller/TaskController.java` | `:185-186` 注释 + 调用 | 随改 |
| `test/domain/task/VideoTaskTest.java` | `:25`、`:278-293` 方法名与断言 | 随改 |
| `spec/backend/directory-structure.md` | `:68` 登记项 | 随改 |
| `frontend/src/composables/useTaskEvents.ts` | `:7-15` `isTerminalEvent` | **删除**，改读 `event.terminal` |
| `frontend/src/stores/task.ts` | `:178-181` 终态判定 | **删除**，改读 `message.terminal` |
| `frontend/src/types/task.ts` | `:89-98` 镜像 | 加 `terminal: boolean` 字段 |
| **不动** | `application/task/VideoTaskService.java:143/148/158` | 该处 `settled` 指「上传临时文件已归位」，异义，排除 |

**代价**：SSE payload 多一个派生字段（可从两个状态算出），属刻意反范式——理由是让权威方算、
消费方不重算。契约变更需前端同步上线（单体仓库内同批交付，无灰度问题）。

### D2（2026-10-11，项目所有者拍板）断连行为：分致命 / 瞬时两类

EventSource 的两种失败要分开对待，依据是它的实际语义：

| 情形 | 浏览器行为 | `readyState` | 前端表现 |
|---|---|---|---|
| HTTP 非 200（如 404）/ content-type 不符 | fail，**不再重连** | `CLOSED` | 重拉一次 `GET /api/tasks/{taskNo}` 确诊：404 走现有「任务不存在卡」，其余走现有通用错误卡（复用 `stores/task.ts:22` 的 `TaskLoadError`） |
| 网络断 / 后端重启 | **自动重连**（`retry:` 默认约 3s） | `CONNECTING` | 不遮挡进度的轻提示「连接中断，正在重连…」，连上即消失 |

- `onerror` 两种都会触发且**不带状态码**，故靠 `readyState` 区分；具体错误类型靠重拉快照确诊。
- **不设重连上限**：3 秒一次开销可忽略，恢复即自动续（重连会重新拿快照，进度无感恢复）。
- 防闪阈值、提示文案落位等实现细节在 `design.md` 定。

### D3（2026-10-11，项目所有者拍板）配对收口落在 `TaskSseHub` 自身

`TaskSseHub` 新增 `open(taskNo, snapshotSupplier)`，把「登记连接 → 挂订阅 → 重读快照 → 推送 →
终态收口」这条有先后的流程收进类里；`TaskController` 缩到两行（404 预检仍留在 controller——
必须在任何 emitter 登记之前抛出，否则会给不存在的任务留悬挂连接）。

选 A（扩 hub）而非 B（新增 web/sse 协作件）的理由：B 换来的实质好处只有「接线顺序有独立单测」，
而 A 下扩 `TaskSseHubTest` 同样能覆盖该顺序；B 的代价是多一个类 + 一个本仓库尚不存在的角色词
（`Subscription` 已被注册表的退订句柄占用，新类不能叫 `TaskSseSubscription`，否则撞词）。

**副作用**：controller 的构造依赖从 3 个（`taskService` / `sseHub` / `subscriptionRegistry`）降到 2 个。
**连带改动**：`TaskSseHub` 的 Javadoc 与 `spec/backend/directory-structure.md:56` 的 `web/sse/` 职责
描述（现为「emitter 持有 / 心跳 / 清理」）都要加上「订阅配对」一项。

### D3a（2026-10-11，项目所有者拍板）`TaskSseHub` 更名为 `TaskSseConnectionManager`

起因：`spec/backend/directory-structure.md:273` 规定「端口/适配器之外的新角色后缀**用前先登记**」，
已登记的只有 `-Notifier` / `-Manager` / `-Generator`；`TaskSseHub` 建于 2026-09-30，早于该规矩，
其 `-Hub` 后缀从未登记。

定案改为 `TaskSseConnectionManager`（复用已登记的 `-Manager`，角色词答「管理一组 SSE 连接」），
而不是登记 `-Hub`。**连带改动**：类名出现处共 5 个文件（`TaskController`、`TaskControllerTest`、
`VideoTaskService` 注释、`VideoTask` 注释、`infrastructure/package-info`）与文件名本身；
`-Manager` 的登记条目补记本仓第二例，并保留其「防万能后缀」的既有警告。

### D4（2026-10-11，项目所有者拍板）SSE 载荷保持「内部形状投影」，不按消费方裁剪

查证发现：`TaskProgressMessage.stage` 的四个字段里，前端**只读两个**（`message` → 文案、
`progress` → 进度条），`stage`（阶段名）与 `state`（含 SKIPPED）前端一行都没读；而 `state` 在后端
也**只有两处 debug 日志**在读（`SubtitlePipeline:216-217`、`:230-231`），零功能消费方。

但**决定不裁**：`StageProgress` 是**管线契约**（被 infrastructure / application / domain 三层共用），
不是 SSE 自己的东西；SSE 只是链尾消费者。要裁只能在"组装 `TaskProgressMessage` 那一步"裁，收益是
少两个字段的镜像成本，代价是动一次契约 + 前端类型改 + **AC7 对照基线重取**。不划算，本次不动。
（"跳过"信息用户**已经能看到**——走的是 `message` 文案「已有 segments 产物，跳过转写」这类；
`SKIPPED` 枚举值是给日志的可观测性用的，见 `AudioPreparationCoordinator.java:38` 的自述。）

### D5（2026-10-11，项目所有者拍板）`StageEvent` 命名收口**另立任务**，本任务不做

讨论中牵出另一个命名问题：`StageEvent` 一眼看不出是什么。定案改为 `StageProgress`，连带
`StageState` → `State`、`ProgressListener.onEvent` → `onProgress`，约 285 处 / 31 文件。

**不在本任务做**，已另建 `10-11-stage-progress-rename`（planning，未开工）。理由：
① 本任务 AC7 是"唯一差异是新增 `terminal` 字段"的干净对照，塞进 285 处重命名会让它失真；
② 一次改动同时动语义与名字，出问题不好定位；③ 该改名的爆炸半径与本次只重合 3 个文件。
仓库先例：`10-06-package-structure` / `10-06-naming-alignment` / `10-07-pipeline-stage-rename`
三次命名收口都是独立任务。

**分界线**：本任务内的改名只限"与本次改动同一契约、同一批文件"的（`settled` → `terminal` 是新字段
的名字；`TaskSseHub` → `TaskSseConnectionManager` 是正在改的那个类）。`StageEvent` 跨全仓、含
infrastructure，另立。

**互不阻塞**：类型名不进 JSON，故两任务先后皆可。

## Notes

- 任务创建：2026-10-11，`base_branch = dev`。
- 兄弟任务（同日拆出）：`10-11-stage-progress-rename`（见 D5）。
- 相关任务：`archive/2026-10/10-08-pipeline-state-broadcast`（其 Out of scope 首次登记「SSE / 通知面搬家」）、
  `archive/2026-10/10-03-vidsage-fe-v0`（P2-2 终态谓词、P2-6 重连 404 静默的原始登记处）。
