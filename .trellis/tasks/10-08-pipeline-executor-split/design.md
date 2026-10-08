# SubtitlePipeline 执行模型拆分 — 技术设计

## 一、目标形状：端口 + 实现

```
application/task/port/TaskConcurrencyGate.java           ← 端口（技术词汇 + 影响领域状态）
infrastructure/concurrency/SemaphoreTaskConcurrencyGate.java  ← 实现（@Component）
```

端口只有一个方法：

```java
public interface TaskConcurrencyGate {
    /** 提交这段活；拿到执行许可后才跑 body。等许可被打断时调 onQueueInterrupted，之后 body 不跑。 */
    void submit(Runnable body, Runnable onQueueInterrupted);
}
```

实现拿走四样东西：`Semaphore` 的创建、`ExecutorService` 的创建（线程名 `vidsage-task-` 原样保留）、`maxConcurrent ≥ 1` 校验、`acquire / release` 的配对。

`SubtitlePipeline` 之后只剩：

```java
private final TaskConcurrencyGate gate;                    // 构造注入（替掉 TaskProperties）
public void execute(TaskNo taskNo) {
    gate.submit(() -> runPipeline(taskNo),
                () -> log.warn("任务等待执行配额时被中断，保持 PENDING: taskNo={}", taskNo.value()));
}
```

**中断语义不合并**：两种「排队被打断怎么办」由调用方以回调给出，实现类对它们一无所知——这是端口存在的理由，不是可省的参数。

## 二、为什么是端口（判据链）

1. **端口归属判据**（spec）：技术/运维词汇 → application 端口；兜底问「删掉这个端口，业务规则还成立吗」——并发配额删掉，`PENDING → RUNNING → 终态` 规则完全成立 → `application/task/port/`。
2. **为什么不是裸 `Executor`（甲）**：甲的契约里**没有「排队中被中断」这个概念**——排队中的任务只是队列里的一个对象，没有线程、没有等待点。今天「排队 = 阻塞在 `acquire()` 上的虚拟线程」正是为了让它可被中断；换成甲会**丢语义**。补充两条：配额已决定领域状态机的推进时点（`acquire()` 之后才 `task.start()`，而 PENDING 是对外状态）；中断会直接产出领域状态（翻译 → FAILED）。
3. **这个端口的退化条件**（写下来防误读）：哪天它退化成「就是把 Runnable 丢出去跑」，就该删掉端口、直接依赖 JDK 的 `Executor`。端口存在的前提是「契约里有应用自己的语义」。

## 三、调用链与线程（改造前后对照）

| | 改造前 | 改造后 |
|---|---|---|
| `execute()` | 请求线程 `executor.execute(...)` | 请求线程 `gate.submit(...)` |
| 装载 | 闸门线程，**在 `acquire()` 之前** | 闸门线程，**在 `acquire()` 之后**（body 内） |
| `acquire()` / `release()` | 管线里手写两处 | 实现类里一处 |
| `translateTask()` 同步段 | 请求线程：判定 → `translationStart` → `update` → `executor.execute` | 不变，只有最后一步改 `gate.submit` |

线程账（便于排障）：① HTTP 请求线程（Tomcat 每请求一根虚拟线程）；② 闸门线程（`vidsage-task-` 前缀）；③ 抽子进程流的 / SSE 心跳的（与本任务无关）。

## 四、四条行为口径

1. **装载时点后移**（R3）：幽灵任务（装载返回空）会先照常排队、拿到许可、查出空、立刻放掉——今天它不排队也不占许可。差异**共四格**，逐格枚举见 `prd.md` 的 R3（2026-10-08 检查面补入后两格）：

   1. 许可毫秒级占用（幽灵任务）；
   2. `任务记录缺失` ERROR 日志在有排队时推迟；
   3. 同一 taskNo 被 `execute` 两次时，基线会因陈旧快照把管线**跑两遍**，装载后移后确定放弃——方向是改进；
   4. 幽灵任务**排队时被打断**时，那条 ERROR **一次都不出现**（不只是推迟）。

   **必须写进验证报告的差异栏**（沿用 10-08「AC7 那一格差异显式写明」的做法）。
2. **翻译成本日志保留「含排队时间」**（R4）：`long start = System.currentTimeMillis()` 从 `runTranslationPipeline` 顶部移到 `translateTask` 的「翻译已触发」日志之后、`gate.submit` 之前，作为参数传进 body。数值会差一次线程启动延迟（微秒级）、**语义不变**——也记进差异栏。
3. **终态广播仍在配额内**（R5）：`succeed()` / `repository.update` / `broadcastTerminal` 保持在 `release()` 之前。谁要是「顺手把广播挪到放掉许可之后」就改了并发行为。
4. **日志文案逐字**（AC7）：两条排队中断 warn、以及 `任务记录缺失…` / `任务开始执行…` / `任务执行成功…` / `翻译已触发…` / `任务翻译完成…` 全部原样搬运，一个标点都不改（注意翻译那条用的是**半角冒号**：`翻译等待执行配额时被中断: taskNo={}`）。

## 五、测试策略

| 层 | 测什么 | 怎么得到可测性 |
|---|---|---|
| 管线侧（`SubtitlePipelineTest`） | 两条中断分支：主管线 → 保持 PENDING、零落库、零编排；翻译 → `failTranslation` 落库 + 终态广播 | 注入**假 gate**：一个立即同步跑 body 的实现，和一个立即回调 `onQueueInterrupted` 的实现 |
| 实现侧（`SemaphoreTaskConcurrencyGateTest`） | **命门①** 排队被打断时**不释放**许可（错放 = 并发上限悄悄变大）；**命门②** body 抛异常时仍释放（错了 = 队列永久堵死） | 包内可见构造 `(int maxConcurrent, ExecutorService)`，测试塞一个带记录 `ThreadFactory` 的执行器，抓住排队线程去 `interrupt()` |
| 既有用例 | 8 个同步用例零改动（`runPipeline(TaskNo)` 签名不变） | 唯一改动：`service(int)` 里把 `new TaskProperties(n)` 换成 `new SemaphoreTaskConcurrencyGate(...)` + 假 gate |

> 实现侧测试用「自控 executor」是**测试替身**，不是生产装配的一部分：生产路径仍由 `TaskProperties` 构造出 `vidsage-task-` 虚拟线程执行器。

## 六、分层判例：infrastructure 范围放宽

**判据改写**：`shared` 与 `infrastructure` 的分界，从「跟不跟外部世界打交道」改为 **「有没有状态 / 要不要容器」**：

- 无状态纯函数工具（`ArtifactFiles` / `ProgressMapper` / `TextSimilarity`）→ `shared/`；
- 有状态、或需要 Spring 容器的通用机制（`ExternalProcessRunner`、本任务的 `SemaphoreTaskConcurrencyGate`）→ `infrastructure/`；
- 有状态但只为某一层服务、没有复用意图的（`TaskSubscriptionRegistry`、`TaskSseHub`）→ 留在服务它的层。

`infrastructure/package-info` 现在只写出站适配，而 spec 二级包表里 `process/` 早已是「不面向业务契约的机制类」——本任务把散文补成两类，消除这处自相矛盾。**依赖表、`shared`、`config`、`web` 都不动。**

## 七、讨论中否掉的方案（别再重开）

| 方案 | 否掉的理由 |
|---|---|
| **B：执行类吃下状态迁移** | 新类被迫依赖 repository + registry，把 10-08 刚理干净的进度职责塞回执行路径；翻译路径要么吃下两套状态机、要么拆不彻底 |
| **C：只抽 Semaphore** | 异步提交与排队语义仍在编排类，「执行模型拆出来」名不副实 |
| **D 放宽：实现类直接放 infrastructure、管线 import** | 要重开 2026-10-06 刚清空的「豁免 a」；且管线侧无法注入替身，两条中断分支继续零覆盖 |
| **装载提到调用线程** | 请求线程从「落库 + 提交 + 返回」变成四件事；换来的是让一条**实践不可达**的路径逐格不变 |
| **给 executor 加 `@PreDestroy` shutdown** | 行为变更：容器关闭时打断进行中的任务 → FAILED。本次不做，另行讨论 |
| **在 `config/` 声明 `@Bean` 装配 gate** | spec 依赖表写着 `config` 谁的都不依赖，且 config 现在确实零处 import 项目类型；实现类直接收 `config/TaskProperties` 有先例（`LlmSubtitleCleanser`） |
| **把 `shared` / `config` 一并并进 infrastructure** | 会丢 `shared` 那条「无状态纯函数」闸门；用户 2026-10-08 定：不搬，只把界线写明 |

## 八、诚实边界与代价

1. **净 LOC 是变多的**：抽走的约 25 行摊到「接口 + 实现 + 测试 + 文档订正」上。本次买的是归属、配对单点化与可测性，不是"代码变少"。
2. **端口只有一个实现**。先例是 `FileStateReader` → `NioFileStateReader`，所以不算破例；但它也确实是"为将来留位"——若一直不换实现，这个接口就是纯成本。**判据看的是它表达不表达应用语义，不是实现个数。**
3. **中断路径的测试是新增能力，不是"覆盖已有行为"**：今天它零覆盖，所以「逐格不变」不要求测它；测它是本次拆分顺带挣来的。
4. **本任务不碰「状态 × 广播」三角**，也不碰任何排队政策。`SubtitlePipeline` 拆完仍是 300+ 行的类——这是已知且刻意的边界。
5. **术语「配额」**：本项目内部 = 并发许可（`max-concurrent`）；外部服务的额度一律叫外部配额。新类注释沿用前者，别把 DashScope 的账号额度混进来。
6. **端口形状已把「进程内」编进契约**：`submit(Runnable)` 无法跨进程。将来若要做分布式配额，契约要重写（占名额 → 跑 → 释放，或入队 taskNo + worker 取），不是换实现就行。
