# 执行计划：SSE 面收口

> 需求见 [prd.md](./prd.md)，设计与被否方案见 [design.md](./design.md)。**开工前先把这两份读完。**

## 执行前须知

- **分工（沿用既有口径）**：批次 1–2 是**主源码，项目所有者写**（每步先问再动，示例代码就在本文，
  不要贴会话）；批次 0 / 3 / 4 / 5 是**文档、测试与验证，AI 写**。
- **门**：每批做完立刻停，等审查。提交 / 推送 / 合并 / 归档各是一道独立的门，沉默 ≠ 批准。
  未归档期间每轮回复结尾提醒。
- **环境坑**：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn ...`（Git Bash 下必须显式给 JDK 21）；
  量测试前先 `rm -rf backend/target/surefire-reports`（陈旧报告会冒充本次结果）；
  **改名 / 动结构后必须 `mvn clean`**；SSE 抓取要**同一条 shell 里提交 + 订阅**（分两个命令会因
  模型间隔太久只拿到终态快照）；对照前先剥 CRLF；起冒烟实例后按端口 PID `taskkill //PID <pid> //F`
  （`TaskStop` 杀不掉子 JVM）；证据落本任务 `evidence/`，不要用 `/tmp`。
- **中间态**：批次 1 结束时**主源码可编译、测试编译不过**（`TaskSseHubTest` 还按旧类名 `new`，
  `TaskControllerTest` 还按 4 参构造消息）——**属预期，别顺手改**，批次 3 统一处理。

## 步骤 0 — 记录基线（执行前必做）

1. `git rev-parse HEAD` 记进本文「实测记录」。
2. `rm -rf backend/target/surefire-reports && cd backend && JAVA_HOME=/f/java_soft/java21 mvn clean test`
   → 记用例总数与失败数（**AC7 的后端基线**；上一任务记为 488）。
3. **SSE 参照侧抓取**（本任务改动前，在 dev 当前 HEAD 上抓）：
   - `JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`
   - 同一条 shell 内：`POST /api/tasks {"url": …}` → 立刻 `curl -sN /api/tasks/{taskNo}/events`
     → 归一化（剥 taskNo、剥时间戳、剥 CRLF）落 `evidence/ref-sse.txt`。
   - 同时抓 `GET /api/tasks/{taskNo}` 响应体落 `evidence/ref-snapshot.json`（AC7 要逐字节对照）。
   - 收工按 PID `taskkill`。

## 批次 0 — 判据先行（纯文档，不碰代码）

先定判据再改代码，否则代码会被旧判据评判。`spec/backend/directory-structure.md` 四处：

1. `:56` `web/sse/` 职责由「emitter 持有 / 心跳 / 清理等 web 层构件」补为
   「emitter 持有 / 心跳 / 清理 / **订阅配对**等 web 层构件」。
2. `:68` 业务逻辑下沉清单里的 `isEventSettled` → **`isEventTerminal`**（该行同时写着「终态判定，
   web 收口引用」，保留）。
3. `:273` 「组件后缀登记」行补 `-Manager` 第二例 `TaskSseConnectionManager`（角色词「管理一组 SSE
   连接」，落 `web/sse/`）。**订正**：原写「该行既有『防 Manager 变万能后缀』警告」与事实不符——
   那条警告与 `-Manager` 首例（`WorkspaceDirectoryManager`）都在 `:266`「端口接口」行；`:273` 是
   「**端口/适配器之外**的新角色后缀用前先登记」的登记行，本类正是端口之外的角色故落此，条目里
   注明首例与警告在 `:266`、第二例不解除该警告（2026-10-11 拍板）。
4. `:304` `TaskProgressMessage` 行两件事一起做：① 类名 `TaskSseHub.send(...)` →
   **`TaskSseConnectionManager.send(...)`**（AC4 要求 spec 内 `TaskSseHub` 零残留，原清单漏列）；
   ② 补一句：`terminal` 是派生字段，判据单源在 `VideoTask.isEventTerminal`，**不带 `@Schema` 的
   裁决不变**。

**做完停下交审查**——这批改的是既有判据（`web/sse/` 职责扩边），改错了后面会松一片。

## 批次 1 — 后端主源码（项目所有者写）

### 1.1 谓词更名（domain）

`domain/task/VideoTask.java:262`：`isEventSettled` → `isEventTerminal`，Javadoc 首句同步为
「事件流是否已收敛（终态谓词）」——**正文那句解释两条状态轴的说明保留**（它是这个判据的存在理由）。
`domain/task/package-info.java:5` 的登记项随改。

### 1.2 `TaskProgressMessage` 增加 `terminal`

```java
public record TaskProgressMessage(
        String taskNo,
        TaskStatus taskStatus,
        StageEvent stage,
        TranslationStatus translationStatus,
        boolean terminal) {

    /**
     * 紧凑构造器：终态判据在这里算一次，构造方传进来的值一律被覆盖——
     * 判据单源在 VideoTask.isEventTerminal，谁都写不进别的值。
     */
    public TaskProgressMessage {
        terminal = VideoTask.isEventTerminal(taskStatus, translationStatus);
    }

    /**
     * 常规构造入口：调用方只给 4 个事实字段，判据由本类自己算。
     */
    public static TaskProgressMessage of(String taskNo, TaskStatus taskStatus,
                                         StageEvent stage, TranslationStatus translationStatus) {
        return new TaskProgressMessage(taskNo, taskStatus, stage, translationStatus, false);
    }
}
```

原有 `settled()` 方法删除（判据改为字段）。类 Javadoc 里「终态（SUCCEEDED / FAILED）事件是最后一条」
那段补一句 `terminal` 的含义。

### 1.3 类更名 + `open()` + 空桶修复（web）

```bash
git mv backend/src/main/java/com/vidsage/web/sse/TaskSseHub.java \
       backend/src/main/java/com/vidsage/web/sse/TaskSseConnectionManager.java
```

类名、构造器、Javadoc 里的自称一并改。构造器新增依赖：

```java
private final TaskSubscriptionRegistry subscriptionRegistry;

public TaskSseConnectionManager(TaskSubscriptionRegistry subscriptionRegistry) {
    this.subscriptionRegistry = subscriptionRegistry;
    this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("vidsage-sse-heartbeat-", 0).factory());
    this.heartbeatExecutor.scheduleWithFixedDelay(
            this::beat, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
}
```

新增 `open()`（顺序照搬 `TaskController.subscribeEvents` 现存 7 步，**逐字保留两条历史缺陷的防御**）：

```java
/**
 * 建立一条任务进度订阅：登记连接 → 挂订阅 → 重读快照推送 → 终态收口，全在这一个入口里走完。
 * 调用方（web 控制器）只负责 404 预检，不再自己拼配对这些步。
 *
 * @param taskNo            对外任务号；不可为空白
 * @param snapshotSupplier  快照读取；在订阅挂上之后调用（重读，防窗口内任务已终态）
 * @return 未初始化的 emitter（由 Spring MVC 在请求挂起时接管写出）
 */
public SseEmitter open(String taskNo, Supplier<TaskProgressMessage> snapshotSupplier) {
    requireTaskNo(taskNo);
    SseEmitter emitter = register(taskNo);
    Subscription subscription = subscriptionRegistry.subscribe(taskNo, event -> send(taskNo, event));
    // 连接没了就必须退订：否则泄漏的订阅者继续留在注册表里，同任务的后续广播会经它再写一遍
    // 给幸存连接（2026-10-03 真实运行抓到的重复推送缺陷）。close 幂等，与下方终态分支并发安全
    emitter.onCompletion(subscription::close);
    TaskProgressMessage snapshot = snapshotSupplier.get();
    send(taskNo, snapshot);
    if (snapshot.terminal()) {
        // 订阅时任务已终态：注册表不会再有 complete 调用，主动退订防泄漏
        subscription.close();
    }
    return emitter;
}
```

`remove()` 补空桶回收（另加 `hasBucket` 测试探针，理由见 design §3.2）：

```java
private void remove(String taskNo, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskNo);
    if (list == null) {
        return;
    }
    list.remove(emitter);
    if (list.isEmpty()) {
        // 两参 remove：只在映射仍指向这个桶实例时删，防与并发的 register 竞态
        emitters.remove(taskNo, list);
    }
}

/** 该 taskNo 是否还挂着桶。给测试用，业务代码不要依赖它做判断。 */
boolean hasBucket(String taskNo) {
    return emitters.containsKey(taskNo);
}
```

`send()` 的终态判定改读字段：`boolean terminal = event.terminal();`（原 `event.settled()`），
上方那段解释两条状态轴的注释保留。

### 1.4 `TaskController` 收窄

构造依赖从 3 个降为 2 个（去掉 `TaskSubscriptionRegistry`），字段 `sseHub` 更名
`sseConnectionManager`。`subscribeEvents` 缩为：

```java
public SseEmitter subscribeEvents(@PathVariable String taskNo) {
    if (!StringUtils.hasText(taskNo)) {
        throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
    }
    log.info("订阅任务进度: taskNo={}", taskNo);
    // 先读快照再建 emitter：未知 taskNo 在此 404，不给不存在的任务留悬挂连接
    taskService.snapshot(taskNo);
    return sseConnectionManager.open(taskNo, () -> taskService.snapshot(taskNo));
}
```

类 Javadoc 里描述 7 步接线的那段（`:41-44`）改写为「连接建立与收口全在 `web/sse/`，
本类只做 404 预检与委托」。

**验证**：`cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean compile`
（**只编译主源码**——测试此刻编译不过属预期）。

## 批次 2 — 前端主源码（项目所有者写）

### 2.1 契约镜像

`frontend/src/types/task.ts` 的 `TaskProgressMessage` 加一个字段：

```ts
  /** 是否这条流的最后一条事件（后端算好送出，前端不再自己判——判据单源在后端状态机） */
  terminal: boolean
```

### 2.2 `useTaskEvents.ts`

- 删掉 `isTerminalEvent` 函数，改读 `event.terminal`。
- 加错误处理状态机（完整形状见 design §4.2）：`reconnecting` / `fatal` 两个 ref，
  `onerror` 按 `es.readyState === EventSource.CLOSED` 分流，`onopen` 复位，连续失败 2 次才置
  `reconnecting`（防闪）。返回值增加 `reconnecting` / `fatal`。
- `subscribe()` 里复位三个状态（重订阅是全新连接周期）。

### 2.3 `stores/task.ts`

- 终态判据改读 `message.terminal`（删本地两份编码）。
- 暴露 `reconnecting`；`watch(events.fatal)` → 触发 `diagnoseFatal()`：重拉一次快照，
  404 记 `not-found`、其他失败记 `error`、**成功（任务还在）记
  `{kind:'error', message:'进度连接已断开，请刷新页面重试'}`**（design §4.3 三分支）。

### 2.4 `views/workspace/index.vue`

`<main>` 顶部加一条 `role="status"` 提示条，`reconnecting` 为真时显示「连接中断，正在重连…」，
用现有令牌（`border-border` / `bg-background` / `text-muted`），**不遮挡**下方进度卡与字幕稿。

**验证**：`cd frontend && npm run type-check && npm run lint && npm run build` 三命令全绿。

## 批次 3 — 测试与镜像（AI 写，按批次 1/2 的实际代码改）

- `web/sse/TaskSseConnectionManagerTest.java`（文件随类更名）：构造改
  `new TaskSseConnectionManager(new TaskSubscriptionRegistry())`；**新增两条**
  ——「最后一个连接摘除即删桶」（断言 `hasBucket` 转 false）、「同任务仍有其他连接时不删桶」；
  补 `open()` 顺序用例，含「窗口内任务已终态 → 推完快照即退订」（历史缺陷路径）。
- `web/controller/TaskControllerTest.java`：构造依赖与 import 随改；消息改经 `TaskProgressMessage.of(...)`
  构造；补一条断言 `subscribeEvents` 路径不再直接触碰注册表。
- `domain/task/VideoTaskTest.java:25`、`:278-293`：方法名与断言调用随 `isEventTerminal` 更名。
- `TaskProgressMessage` 单测（若无则新建）：`terminal` 在
  `(SUCCEEDED,null)` / `(SUCCEEDED,SUCCEEDED)` / `(FAILED,null)` 为 true，
  `(SUCCEEDED,RUNNING)` / `(PENDING,null)` / `(RUNNING,null)` 为 false；且**直接传 5 参构造器
  传错值也会被紧凑构造器纠正**（这是 §1.2 那个形状的意义所在）。

**验证**：`rm -rf target/surefire-reports && mvn clean test` 全绿，用例数 ≥ 步骤 0 基线 + 新增。

## 批次 4 — 验证（AC5 / AC6 / AC7）

1. **AC7 双侧对照**：起冒烟实例，同 shell 提交 + 订阅抓 SSE 流 → 与 `evidence/ref-sse.txt` 对照，
   断言：事件条数、顺序、各字段值一致，**唯一差异是每行多出的 `terminal` 字段**；
   `GET /api/tasks/{taskNo}` 响应体与 `evidence/ref-snapshot.json` **逐字节一致**。
   证据落 `evidence/ac7-comparison.md`。
2. **AC5 瞬时断连**：前端 dev + 后端跑一个任务的工作区页，**停掉后端** → 观测「连接中断，正在重连…」
   在数秒内出现；**重启后端** → 提示消失、事件续上。Playwright 截图 + 文字观测落证据。
3. **AC6 致命断连**：Playwright 对 `/api/tasks/*/events` 返回 404 → 断言页面出现「任务不存在」卡
   （不是空白、不是无限 loading）。
4. **AC4 grep 证据**：`TaskSseHub`、`isEventSettled`、`isTerminalEvent` 全仓零残留；
   `settled` 仅剩 `VideoTaskService.java` 三处异义用法（逐条列出）。
5. 前端三命令复跑。

## 批次 5 — 收尾

1. `spec/frontend/api.md` 补 SSE 订阅的错误处理约定（`readyState` 分致命 / 瞬时；致命时重拉快照
   确诊；判据不在前端重编码）——这是本次**新增**的一条前端规范，写进 `:11` 附近。
2. 回勾 `prd.md` 的 AC 勾选框，补 `evidence/`。
3. 提交 / 推送 / 合并回 **dev**（本任务 base_branch）/ 归档，逐道门批准。

## 风险点

| 风险 | 处置 |
|---|---|
| 忘记 `mvn clean` → 陈旧 class 让改名"看起来"通过了 | 步骤 0 起每次编译都 `clean` |
| SSE 抓取分两条命令 → 只拿到终态快照 | 提交与订阅写在同一条 shell |
| CRLF 让逐字节对照假失败 | 对照前先剥 CRLF（两侧都剥） |
| 冒烟实例没杀干净 → 端口占用 | 按 PID `taskkill //PID <pid> //F` |
| 前端 `lint` 带 `--fix` 会改文件 | 跑完 `git status` 复核改动面 |

## 实测记录

**步骤 0（2026-10-11，AI 执行）** — 详见 `evidence/ref-capture-2026-10-11.md`

| 项 | 实测值 |
|---|---|
| `git rev-parse HEAD` | `0307f867c281fa050c61e91a5b9452163e1553bd`（分支 `feature/sse-surface-consolidation`，自 dev 切出） |
| 后端测试基线 | **488 / 0 失败 / 0 错误 / 1 跳过**，BUILD SUCCESS（与上一任务记的 488 一致） |
| 场景 A SSE | taskNo `09601a66e6f5419b843ecc3af712be82`，**14 个 `event:progress`**，进度序列 `0/21/21/42/42/66/66/70/70/79/89/100/100/100`；另 2 个 `:ping` |
| 与 10-08 归档基线 | 14 格**逐格相同**，唯一差异是 `:ping` 落点（时序抖动） |
| 场景 B 快照 | `ref-snapshot.json`，`status=SUCCEEDED`、`translationStatus=null`、`artifacts=[]`；**不含 `terminal`** |
| 冒烟实例收尾 | PID 34576 `taskkill //F`，18080 已释放 |

**批次 0（2026-10-11，AI 执行）** — `spec/backend/directory-structure.md` 四处改完（`:56` / `:68` / `:273` / `:304`），
spec 内 `TaskSseHub`、`isEventSettled` 均零命中。两处订正见批次 0 正文。

**批次 1（后端主源码，项目所有者写）**：已落地——`open()` 建在 `TaskSseConnectionManager:89`、`remove()` 空桶修复、
controller 收窄到 3 行且不再碰注册表。两处收尾由 AI 补：`register` 收成包私有、`hasBucket` 测试探针
（2026-10-11 所有者指派）；另补 `domain/task/package-info.java:5` 的登记项改名（批次 1.1 漏项）。

**批次 2（前端主源码）**：AI 执行（所有者指派）。4 个文件；`type-check` / `lint` / `build` 三绿；
`isTerminalEvent` / `TaskSseHub` 前端零残留。

**批次 3（测试与镜像）**：AI 执行。
- `mvn clean test` → **497 / 0 失败 / 0 错误 / 1 跳过**（基线 488，+9）。
- 新增：`TaskProgressMessageTest`（3）、`TaskSseConnectionManagerTest` 的 `open()` 顺序与空桶回收（+6，11→17）。
- 修正清单外漏项：`TaskProgressNotifierTest` / `SubtitlePipelineTest` / `TaskSubscriptionRegistryTest`
  共 7 处 4 参构造点改 `of(...)`（原清单只列了 3 个文件，那些不改同样编译不过）。
- **未做**：implement.md 原列的「补一条断言 `subscribeEvents` 路径不再直接触碰注册表」——全仓无结构型
  断言先例，改由批次 4 的 grep 证据承担（源码里 `subscriptionRegistry` 零命中即证）。

**批次 4（验证）**：AI 执行。AC4 / AC5 / AC6 / AC7 证据见 `evidence/`。
- **AC7 通过**：SSE 14 条、进度序列与参照侧逐格相同，唯一差异是新增 `terminal` 字段（13×false + 1×true）；
  快照归一化后与参照侧**逐字节一致**。见 `evidence/ac7-comparison.md`。
- **AC5 通过**：拦截 `/events` abort 复现，提示条 3.4s 出现、不遮挡、放行后消失且流续上。
  见 `evidence/ac5-ac6-verification.md`。
- **AC6 部分通过**：任务不存在卡 ✓；但 `/events` 单独 404 那条路无渲染出口 → **登记不改**，见 `prd.md`。
- **AC4 通过**：`TaskSseHub` / `isTerminalEvent` 全仓零命中；`settled` 仅剩 `VideoTaskService:135/140/150` 异义用法。
- 前端三命令复跑全绿。

**批次 5（收尾）**：待执行。
