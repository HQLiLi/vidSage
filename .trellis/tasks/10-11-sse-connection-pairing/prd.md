# SSE 连接与订阅配对显式化：连接表存 `Connection` 对、写失败即退订

> **状态：planning，未开工。** 本文件记录来源、已确认事实与已定决策；完整 PRD + design + implement 待开工时展开。
> 来源：2026-10-11 `10-11-sse-surface-consolidation` 第二轮审查意见 ③（项目所有者当场拍板「另立任务，这个有点大」）。

## Goal

`TaskSseConnectionManager` 里，一条 SSE 连接与「它在 `TaskSubscriptionRegistry` 里的那条订阅」的**配对是隐式的**——两者只共同出现在 `open()` 的同一个作用域里，订阅句柄被关在一个 lambda 闭包中，别处够不着。后果是**写失败那条路径没法退订**。

本次把这对关系变成**显式数据**：连接表的元素从 `SseEmitter` 换成「配对持有」的类型，让任何一条清理路径都能拿到自己那一对。

## 背景与已确认事实（2026-10-11 勘察，均对照当前代码核实）

| 事实 | 证据 |
|---|---|
| 连接表装的是裸 emitter | `web/sse/TaskSseConnectionManager` 的 `emitters: ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>` |
| 订阅在 `open()` 里建，且**只**挂在一个地方 | `Subscription subscription = subscriptionRegistry.subscribe(taskNo, event -> sendTo(taskNo, emitter, event)); emitter.onCompletion(subscription::close);` |
| 写失败分支够不着订阅句柄 | `sendTo` 的 `catch (IOException \| IllegalStateException)` 里只有 `remove(taskNo, target)` |
| 于是该订阅会留到任务终态 | 任务终态时 `application/task/notify/TaskProgressNotifier:102` 调 `TaskSubscriptionRegistry.complete(taskNo)` 才把它清掉 |
| 期间的表现 | 每次 `broadcast` 都会再写那个坏连接 → 再抛 → 再打一行 debug → `remove` 已成 no-op（emitter 早摘了） |
| **必要条件构造不出来** | 「写失败但异步请求永不完结」找不到具体机制：客户端断连 → 容器完结 async request → `onCompletion` 触发；`onTimeout` → 容器**必**补发 `onCompletion`；写向已完结 emitter → 那时 `onCompletion` 早已跑过、订阅早从注册表移除、不会再被调到。**故这是理论隐患，不是能指到真实场景的缺陷** |
| Spring 7 `ResponseBodyEmitter.send` 的实际抛法 | 读 `spring-webmvc-7.0.9` 字节码：`Assert.state(!complete)` → 已完结抛 **`IllegalStateException`**；`handler.send` 的 **`IOException` 原样抛**、其他 `Throwable` **包成 `IllegalStateException`**。**没有** `sendFailed` 标志位（字段只有 `complete` 与 `failure`）——写失败后 emitter 不会被标记为废 |
| 连接表元素类型无先例可循 | 全仓 `Connection` 从未作类型名；它作为「连接」概念只出现在类名 `TaskSseConnectionManager`、controller 字段 `sseConnectionManager`、若干测试方法名里 |

## 已定决策

### D1（2026-10-11 拍板）连接表存「配对」而不是裸 emitter

元素类型定为一条**私有嵌套 record**，持有 emitter 与它自己那条订阅：

```java
private record Connection(SseEmitter emitter, Subscription subscription) {
}
```

- **名字依据**：「connection / 连接」已经是这个包既有的词（类名、字段名、测试名都在这个意义上用它），不是新造词；私有嵌套 record 也是本仓既有惯例（`LlmSubtitleCleanser.BatchRange` / `AgentOutcome` / `TermMetrics`、`LlmSubtitleTranslator.BatchRange` / `TranslateOutcome`），且**不进** spec 的「组件后缀登记」表（那张表管包级角色构件后缀）。
- **连带**：字段名 `emitters` 要改成 `connections`（私有字段），否则「叫 emitters 的表装 Connection」读着别扭。`emitterCount` / `hasBucket` 两个测试探针名可保留（它们问的就是 emitter 的事）。

### D2（2026-10-11 拍板）`register()` 并进 `open()`

配对必须等 `subscribe(...)` 返回后才能组装，所以「只建连接不建订阅」的那半步在新结构下不存在——**`register` 这个包私有方法应当消失**。收益：`open()` 真正成为唯一入口，连「只建连接」都无从做起（本仓 `10-11-sse-surface-consolidation` 的 R1/AC2 方向再进一步）。代价：测试里直调 `register` 的用例要改走 `open`。

### D3（2026-10-11 拍板）注册表**保留**，不因本次改动删除

`TaskSubscriptionRegistry` 仍是必需品，理由是它切断了一条不能有的依赖：

| 侧 | 谁 | 调什么 |
|---|---|---|
| 生产者 | `application/task/notify/TaskProgressNotifier`（application 层） | `broadcast(...)`、`complete(taskNo)` |
| 消费者 | `web/sse/TaskSseConnectionManager`（web 层） | `subscribe(...)` |

生产者住在 application 层，按 spec 既不许 import `web/`、更不许碰 `SseEmitter`——它想把进度送出去，就必须有一个不认识 emitter 的落点。注册表还承担另外两件被依赖的事：`broadcast` 的「单个订阅者失败只记 WARN、不连累别人」，以及 `complete(taskNo)`（终态时让管线清空订阅者，管线不必知道订阅者是谁）。**「记一对」只是让 hub 这一侧的配对显式化，不替代注册表。**

### D4（2026-10-11 拍板）写失败即退订，但不采用「借 `complete()` 退订」

要解决的是**服务端资源没释放**（注册表里那条订阅没退），直接解就是 `subscription.close()`；`close()` 幂等（内部 CAS），**不需要先判断是否已退**。

**被否的两条**：

1. **在写失败分支调 `target.complete()`，借 `onCompletion` 钩子退订**：不用把句柄递进失败路径，syntax 最省。被否理由——它会**顺带把连接终结掉**，那是 `complete()` 的副作用，不是本问题的一部分；所有者不接受为一个服务端资源泄漏去改连接的终结语义。
2. **`AtomicReference<Subscription>` 持有器把它递进 `sendTo`**：改动最小、不动数据结构，但引入一个「`subscribe` 返回前、持有器尚未赋值时并发广播撞上 → 拿到 null → 退不掉」的窄角，且写法绕眼。有了 D1 的显式配对就不必要。

## 待开工时展开

- 验收标准（AC）：至少覆盖「写失败后该连接的订阅确实被退」「`open()` 成为唯一入口（`register` 消失）」「空桶回收 / 终态收口 / 窗口竞态防御不回归」。
- `sendTo` / `beat` / `remove` / `emitterCount` / `hasBucket` 逐点的类型改动面与测试改动面。
- 是否需要顺带重议「每连接一个订阅者（1:1）」——那是 `10-11-sse-surface-consolidation` 已完成的部分，本次**不重开**，除非新结构让「每 taskNo 共享一个订阅」变得明显更简单。

## Notes

- 任务创建：2026-10-11，`base_branch = dev`，`--no-start`（创建时不激活，避免顶掉当时在跑的 `10-11-sse-surface-consolidation`）。
- 兄弟任务（同日拆出）：`10-11-stage-progress-rename`。
- 前序任务：`10-11-sse-surface-consolidation`（本任务的缺陷即在其第二轮审查中报出；该处已在 `evidence/ac5-ac6-verification.md` 之外单独说明，最终按「登记不改」处理）。
