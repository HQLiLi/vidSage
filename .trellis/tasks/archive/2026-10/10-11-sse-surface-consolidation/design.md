# 设计：SSE 面收口

> 需求与决策见 `prd.md`（D1 / D2 / D3 / D3a）。本文件写技术设计：边界、契约、数据流、取舍。

## 1. 边界

改动横跨 backend / frontend 两侧，共 4 个后端文件 + 3 个前端文件 + 若干 spec 与测试。

| 层 | 文件 | 动作 |
|---|---|---|
| domain | `domain/task/VideoTask.java` | `isEventSettled` → `isEventTerminal`（方法名 + Javadoc 文案） |
| domain | `domain/task/package-info.java` | 登记项随改 |
| application | `application/task/message/TaskProgressMessage.java` | 新增 `terminal` 组件（判据出口）+ 工厂方法 |
| web | `web/sse/TaskSseConnectionManager.java`（原 `TaskSseHub.java`） | 类更名 + 新增 `open()` + 修 `remove()` 空桶 |
| web | `web/controller/TaskController.java` | 依赖 3 → 2；`subscribeEvents` 缩到 3 行 |
| web | `web/sse/` 包说明（若有） | 职责描述补「订阅配对」 |
| frontend | `composables/useTaskEvents.ts` | 删本地判据；加错误处理状态机 |
| frontend | `stores/task.ts` | 读 `terminal` 字段；致命错误确诊；暴露重连态 |
| frontend | `views/workspace/index.vue` | 重连提示条 |
| frontend | `types/task.ts` | 镜像加 `terminal: boolean` |
| spec | `backend/directory-structure.md`、`frontend/api.md`（可能） | 见 §7 |

## 2. 契约变更：`TaskProgressMessage` 增加 `terminal`

```java
public record TaskProgressMessage(
        String taskNo,
        TaskStatus taskStatus,
        StageEvent stage,
        TranslationStatus translationStatus,
        boolean terminal) {

    /**
     * 紧凑构造器：判据在此算一次，构造方传进来的值一律被覆盖。
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

**为什么是「组件 + 紧凑构造器覆盖 + 工厂」这个形状**：

- 组件才进 JSON。Jackson 按 record 组件序列化，派生属性想出现在 payload 里，只能是组件。
- 紧凑构造器覆盖参数值 → 判据**只有一处**（`VideoTask.isEventTerminal`），即便有人绕过工厂直接调
  5 参构造器，也写不进错误值。这是「即便用错也不会错」的形状，而不是靠约定。
- 工厂 `of(...)` 让调用点维持「只给事实」的书写：`TaskProgressNotifier.broadcast` /
  `createSnapshot` 两处改用它，不出现第 5 个 `false` 实参。
- 代价：5 参构造器仍公开可调，且参数 `terminal` 恒被忽略——这是 record + 派生字段的固有别扭，
  用工厂 + Javadoc 说明消解。**被否方案**见 §6。
- **为什么不能让 5 参构造器私有**：record 的规范构造器可见性不得低于 record 自身（本题是 public），
  所以「只留工厂一条路」在 record 上做不到——这正是必须用紧凑构造器无条件纠偏、而不能靠约定
  的原因。

**JSON 形状**（字段顺序即组件顺序，`terminal` 落在末位）：

```json
{"taskNo":"...","taskStatus":"SUCCEEDED","stage":{...},"translationStatus":null,"terminal":true}
```

`TaskProgressMessage` 仍**不带 `@Schema`**（`spec/backend/directory-structure.md:304` 的既有裁决不变：
SSE 端点返回 `SseEmitter`，注解进不了 `/v3/api-docs`）。

## 3. 后端：`TaskSseConnectionManager`

### 3.1 新增 `open()`——配对与收口的单点

```java
public SseEmitter open(String taskNo, Supplier<TaskProgressMessage> snapshotSupplier) {
    requireTaskNo(taskNo);
    SseEmitter emitter = register(taskNo);
    Subscription subscription = subscriptionRegistry.subscribe(taskNo, event -> send(taskNo, event));
    emitter.onCompletion(subscription::close);
    // 重读快照：任务可能恰在「读快照 → 挂订阅」的窗口里到达终态——终态广播与 complete
    // 都发生在订阅挂上之前，只推窗口前读到的旧快照，这条订阅既等不到终态事件也永不退订
    TaskProgressMessage snapshot = snapshotSupplier.get();
    send(taskNo, snapshot);
    if (snapshot.terminal()) {
        subscription.close();   // 订阅时任务已终态：注册表不会再有 complete 调用，主动退订防泄漏
    }
    return emitter;
}
```

这段顺序照搬 `TaskController.subscribeEvents` 现存的 7 步，**逐字保留两条历史缺陷的防御**
（陈旧快照 / 注册表泄漏，见 MVP-1 `research/acceptance-evidence.md`「事件插曲：SSE 重复推送」），
只换落点，不改语义。

`TaskController.subscribeEvents` 收窄为：

```java
public SseEmitter subscribeEvents(@PathVariable String taskNo) {
    if (!StringUtils.hasText(taskNo)) {
        throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
    }
    log.info("订阅任务进度: taskNo={}", taskNo);
    taskService.snapshot(taskNo);   // 404 预检：必须在任何 emitter 登记之前抛出，不留悬挂连接
    return sseConnectionManager.open(taskNo, () -> taskService.snapshot(taskNo));
}
```

**注意**：`taskService.snapshot` 会调两次（预检一次、`open` 内重读一次），与现状一致
（现代码 `:171` 与 `:178` 各一次）——预检结果刻意丢弃，重读结果才是要推的快照。不合并，
因为「预检必须先于 emitter 登记」与「重读必须晚于订阅挂载」是两个不同的时序约束。

### 3.2 修 `remove()` 的空桶泄漏

```java
private void remove(String taskNo, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskNo);
    if (list == null) {
        return;
    }
    list.remove(emitter);
    if (list.isEmpty()) {
        // 两参 remove：只在映射仍指向这个桶实例时删，防与并发的 register 竞态（后者刚把新
        // emitter 加进同一个桶，一参 remove 会把新连接一起丢掉）
        emitters.remove(taskNo, list);
    }
}
```

配套：加包私有的 `boolean hasBucket(String taskNo)` 供测试断言桶是否真被删（现有
`emitterCount` 对「桶已删」与「桶空着」返回同一结果，测不出这条）。

### 3.3 `send()` 的终态判定改读字段

```java
boolean terminal = event.terminal();   // 原为 event.settled()
```

## 4. 前端：`useTaskEvents` 的错误处理状态机

### 4.1 判据删除

`isTerminalEvent` 整个函数删除，改读 `event.terminal`。`stores/task.ts:178-181` 的重复判定同样删除。

### 4.2 错误处理

EventSource 的两种失败用 `readyState` 区分（依据见 `prd.md` D2 表）：

```ts
let consecutiveErrors = 0
const reconnecting = ref(false)
const fatal = ref(false)

es.onerror = () => {
  if (es.readyState === EventSource.CLOSED) {
    // 非 200 / content-type 不符：浏览器 fail 且不再重连 —— 这条流已经死了
    fatal.value = true
    close()
    return
  }
  // readyState === CONNECTING：断线自动重连中
  consecutiveErrors += 1
  if (consecutiveErrors >= RECONNECT_HINT_AFTER) reconnecting.value = true
}
es.onopen = () => {          // 每次成功（重）连接都会触发
  consecutiveErrors = 0
  reconnecting.value = false
}
```

- **防闪阈值 `RECONNECT_HINT_AFTER = 2`**：EventSource 默认重连间隔约 3s，一次抖动约 3s 内自愈；
  连续失败 2 次（约 6s）才提示，避免正常网络抖动闪一下提示条。
- 收到任何 `progress` 事件也复位计数（流已经活了，不必等 `onopen`）。
- `subscribe()` 里复位 `fatal` / `reconnecting` / 计数（重订阅是一次全新的连接周期）。
- **不设重连上限**：3s 一次的自动重试开销可忽略，恢复即续；重连会重新拿 DB 快照，进度无感恢复。

### 4.3 致命错误的确诊与呈现

`onerror` 拿不到状态码，故 `fatal` 置位后由 **store** 重拉一次快照确诊（复用现成的 `getTask`
与 `TaskLoadError` 机制，见 `stores/task.ts:22`、`:90-98`）：

| 重拉结果 | `loadError` | 页面 |
|---|---|---|
| 404 | `{kind:'not-found'}` | 现有「任务不存在」卡 |
| 其他失败 | `{kind:'error', message}` | 现有通用错误卡 |
| 成功（任务还在） | `{kind:'error', message:'进度连接已断开，请刷新页面重试'}` | 现有通用错误卡 |

第三种情况是「SSE 端点坏了但快照接口还活着」，必须给用户一句话，否则页面看着正常却永不更新。

### 4.4 composable 与 store 的接口

`useTaskEvents()` 返回值增加 `reconnecting`、`fatal` 两个 ref（`latest`、`subscribe`、`close`
不变）。store 侧：

```ts
const reconnecting = computed(() => events.reconnecting.value)
watch(events.fatal, (isFatal) => { if (isFatal) void diagnoseFatal() })
```

`diagnoseFatal()` 即 §4.3 的三分支，写 `loadError`。

## 5. 前端：视图

`views/workspace/index.vue` 在 `<main>` 顶部加一条 `role="status"` 的提示条，`reconnecting` 为真时
显示「连接中断，正在重连…」，样式取现有令牌（`border-border` / `bg-background` / `text-muted`），
**不遮挡**下方的进度卡与字幕稿。`reconnecting` 为假即消失。

## 6. 被否方案

1. **前端本地合并判据（PRD Q1 的选项 a）**：零契约风险，但判据仍跨栈两份——P2-2 要治的病没治好。
2. **新增 web/sse 协作件承担配对（PRD Q3 的选项 B）**：换来的实质好处只有「接线顺序有独立单测」，
   而扩 `TaskSseConnectionManager` 的测试同样能覆盖；代价是多一个类 + 一个本仓库尚不存在的角色词
   （`Subscription` 已被注册表的退订句柄占用）。
3. **`terminal` 用 `@JsonProperty` 标注在非组件方法上**：record 保持 4 组件、无需工厂。否决理由：
   依赖 Jackson 对 record 非组件属性的识别（在命名策略 / 包含规则下有边角行为），而组件形式是
   显式且可单测的；多一个工厂方法的成本低于这份不确定性。
4. **给重连设上限或加手动重试按钮**：EventSource 自动重试本身很轻，且恢复后靠 DB 快照无感续上；
   加上限需要额外状态与「放弃后怎么办」的交互设计，本次不做。
5. **重议 `web/sse/` 与 `subscribe/` 的落位判据**：spec 已写死且自洽，本次不动（PRD Out of scope）。

## 7. spec 更新清单（Phase 3.3 落地）

| 文件 | 位置 | 更新 |
|---|---|---|
| `spec/backend/directory-structure.md` | `:56` | `web/sse/` 职责由「emitter 持有 / 心跳 / 清理」补为「…/ 订阅配对」 |
| 同上 | `:68` | 登记项 `isEventSettled` → `isEventTerminal` |
| 同上 | `:273` | `-Manager` 登记条目补第二例 `TaskSseConnectionManager`（保留「防万能后缀」警告） |
| 同上 | `:304` | `TaskProgressMessage` 行补「`terminal` 为派生字段，判据单源在 `VideoTask.isEventTerminal`」 |
| `spec/frontend/api.md` | `:11` 附近 | 增补 SSE 订阅的错误处理约定：`readyState` 分致命 / 瞬时，致命时重拉快照确诊；判据不在前端重编码 |

## 8. 兼容与回滚

- **契约变更的兼容性**：payload 新增字段，前端旧版会忽略它（多一个未知键）——但旧前端仍自己判终态，
  行为与今天一致，故**不构成破坏**；新版前端读新字段。单体仓库同批交付，无灰度问题。
- **回滚点**：后端契约字段与前端消费可分两次提交，各自独立可回滚；`open()` 的收口是纯搬移，
  回滚等于把方法体搬回 controller。
- **命名改动的回滚**：`TaskSseHub` → `TaskSseConnectionManager` 是纯重命名，回滚无残留风险。
