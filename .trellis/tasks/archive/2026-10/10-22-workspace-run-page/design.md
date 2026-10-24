# 设计：工作区运行态（run-scoped SSE、重生成与翻译、收尾清理）

> 子任务 B2。上游：`10-21-workspace-fe-rewrite/prd.md`（源需求 R1–R6）、
> `10-22-workspace-ready-page/prd.md`（B1，本任务在它上面做增量）。
> 本文只写「为什么这样定」；照着做的顺序在 `implement.md`。

---

## 1. 目标与非目标

### 要做

1. 一条 run 一条流地订阅进度，把 204 / 404 / 断网三种情况都收得住。
2. 进度百分比正确：MAIN 用原值、RECLEAN 归一到 0–100、TRANSLATE 用原值，且永不回退。
3. 关键词「重新生成」：把当前完整清单提交，跟新 run 的进度，完成后刷新关键词与字幕。
4. 翻译：选语种 / 互换 / 触发 / 双语显示 / 译文失效提示。
5. 失败与中断：没字幕时出错误卡，有字幕时只出错误条。
6. 收尾：清残留、逐条实测接口、订正过期的前端规范。

### 不做

- **不碰媒体播放**：后端没有媒体端点（父任务 Q4），`MediaPane` 维持 B1 的 16:9 无源占位。
- **不重建 B1 的共享层**：`types/` / `enums/` / `api/` / `utils/` 只在「运行态确实要用」的地方补（见 §2.2）。
- 不改后端一行代码；后端缺口（媒体端点、单条字幕持久化）不在这里补。
- 不做跨标签页同步、不做系统通知、不做离线。

---

## 2. 起点：B1 交付了什么，B2 改哪几处

B1 交付后，工作区能打开任何「已跑完」的任务，能提交新任务并跳过来，但进行中的任务只显示
「执行中，请稍后刷新」。B2 把那句占位换成真实进度，并把翻译 / 重生成接上。

### 2.1 已有、直接复用、不改

| 文件 | B1 给了什么 |
|---|---|
| `frontend/src/types/task.ts` / `subtitle.ts` | `TaskResponse` / `RunResponse` / `TaskRunRef` / 产物 / 字幕 / 语种等类型 |
| `frontend/src/enums/task.ts` | `TASK_RUN_TYPE` / `TASK_RUN_STATUS`（`as const`，比较处用命名常量） |
| `frontend/src/api/request.ts` | 唯一 axios 实例、`ApiError(status / message / runRef)`、`getAbsolute<T>` |
| `frontend/src/api/tasks.ts` | `submitUrl` / `submitUpload` / `getTask` / `getTaskKeywords` / `translateTask` / `regenerateSubtitles` |
| `frontend/src/api/artifacts.ts` | `listArtifacts` / `fetchArtifactJson` |
| `frontend/src/api/languages.ts` | `getLanguages` |
| `frontend/src/utils/artifacts.ts` / `language.ts` / `transcript.ts` | 取产物、取文件 url（判空）、语种码归一、原文译文按下标配对 |
| `frontend/src/stores/workspace.ts` | 基础 state、`phase` / `cues`，`open` / `refresh` / `reset` 等 action |
| `frontend/src/views/workspace/index.vue` 与 `components/` | 就绪态双栏、顶栏与产物菜单、播放器占位、关键词区、主题区、字幕区 |

### 2.2 B2 要改 / 要加

| 文件 | 动作 |
|---|---|
| `frontend/src/types/task.ts` | 不用改：`StageProgress` / `TaskRunProgress`（SSE 事件形状）B1 已建好，B2 直接用 |
| `frontend/src/enums/subtitle.ts` | B1 已建 `ARTIFACT_TYPE`；B2 比较产物类型时用它的命名常量，不写裸字面量 |
| `frontend/src/utils/progress.ts` | **补行为**：`normalizeRunProgress` 与 RECLEAN 区间常量（B1 只落了骨架） |
| `frontend/src/composables/useRunEvents.ts` | **新建**：包住 `EventSource`，自管重试与代际校验 |
| `frontend/src/stores/workspace.ts` | **增补**运行态 state / computed / action，扩展 `open` / `refresh` / `reset` |
| `frontend/src/views/workspace/index.vue` | 接线：打开即订阅、卸载即关流、`phase` 分支改成「运行中也渲染双栏」 |
| `components/WorkspaceHeader.vue` | 加进度胶囊 + 断线重试入口 |
| `components/KeywordPane.vue` | 加「重新生成」按钮，运行中禁用 |
| `components/TranscriptPane.vue` | 加译文行、失效提示、错误条、生成中的骨架与进度头 |
| `components/TranslationBar.vue` | **新建**：源 / 目标语种双 select + 互换 + 翻译按钮 + 翻译进度 |
| `components/ThemePane.vue` | 小改：首次生成中显示「正在生成主题…」，不冒充「未能提取主题」 |
| `components/StatusCard.vue` | 复用承载失败 / 中断卡（必要时扩 props，不改 B1 已有用法） |
| `.trellis/spec/frontend/` | 订正 `api.md` 等 6 份文档里的过期描述（见 §11.3） |

`MediaPane.vue`、`ArtifactMenu.vue`、`routes/`、`api/` 全部不动。

---

## 3. 事实核对（对着后端源码看完后定下的口径）

| 事实 | 依据 | 对本设计的影响 |
|---|---|---|
| SSE 路径是 `/{taskNo}/runs/{runNo}/events`，事件名 `progress` | `backend/src/main/java/com/vidsage/web/controller/TaskController.java:156` | 订阅必须带 `runNo`，`taskNo` 不够 |
| 已终态的 run 返回 **204**，runNo 不存在返回 **404** | 同上 `:163-165`；`ApiExceptionHandler` 的 `TaskNotFoundException` | 前端**不区分**这两者（见 §4.4） |
| 202 与 409 的响应体同形，都是 `{taskNo, runNo}`，409 没有文案 | `TaskRunRefResponse.java:15-19`；`ApiExceptionHandler.java:95-100` | 409 不是纯错误，拿 ref 继续跟 |
| 快照里状态全在 `lastRun`，顶层没有扁平状态 | `TaskResponse.java:25-30` | `phase` / 失败文案都从 `lastRun` 取 |
| `renderStage` 对 RUNNING 一律发 `progress: 0` | `application/task/message/TaskRunProgress.java:72-86` | 重连快照会把进度打回 0，必须单调保护（§5.3） |
| `stage` 可以是 `null`（`currentStage` 还没落） | 同上 `:75-78` | 展示层要有兜底文案 |
| MAIN 的 `stage.progress` 是**任务级** 0–100 | `SubtitleStageName.java:16-22`（权重 5/42/42/49/7/60）与 `CleanupCoordinator.java:221-225` 的注释 | MAIN 直接显示原值 |
| RECLEAN 复用 `clean-subtitles`，经 `ProgressMapper.remap` 压进 `[70,100]` | `shared/progress/ProgressMapper.java:26-32`；区间由 `SubtitleStageName.java:39-54` 累加得出 | RECLEAN 要归一到 0–100 |
| TRANSLATE 是本次 run 自己的 0–100 | `SubtitleTranslateService.java:46`、`LlmSubtitleTranslator.java:198` | 直接显示 |
| RECLEAN 成功后**无条件**作废下游译文 | `SubtitleRecleanService.java:113` 的 `settleCleanedAndSupersedeDownstream` | 刷新产物清单就能看到译文消失，不自己造数据标记 |
| 产物清单只列「文件齐全的当前版本」 | `ArtifactController.java:46-60` | 生成中拿不到半成品字幕，字幕区只能显示骨架 |
| 产物下载地址由后端拼好放在 `files.<位>.url` | `ArtifactResponse.java:56-60` | 前端不自己拼路径，也不拼 `source.mp4` |
| 服务端每 30s 发一条 `:ping` 注释行；emitter 上限 30 分钟 | `web/sse/SseTaskProgressBroadcaster.java:49,54` | 注释行不触发 `onerror`；超长任务会被服务端断一次流，重连即可 |

### 3.1 与任务说明 / 现有文档对不上的三处（已按源码为准）

1. `StageProgress` 的 Javadoc 说 `progress` 是「**阶段内**进度」
   （`backend/src/main/java/com/vidsage/domain/shared/model/StageProgress.java:9`），
   但主管线各阶段实际直接发**任务级**端点值（`SubtitleCleanupCoordinator.java:221-225` 有注释挑明）。
   本设计按 PRD 口径走：MAIN 的 `stage.progress` 当任务级 0–100 用。Javadoc 失实，不在本任务改后端。
2. 原型里「清洗阶段按进度逐条放出字幕」的分支（`prototype/src/components/TranscriptPane.vue:98-108`）
   在真实后端上走不到：产物清单只列完整版本，生成中拿不到半成品。B2 只实现骨架分支，删掉逐条放出的逻辑。
3. PRD AC10 说的「六处错位」如果逐字数只有五个（四个触发点算一条）。`implement.md` 按父任务
   `prd.md` §R3 的六条编号来测，并附 AC10 措辞的对照，避免漏测。

---

## 4. SSE 订阅模型

### 4.1 只有两个入口，最终都收在一个函数上

| 入口 | 来源 | 用途 |
|---|---|---|
| 打开页面 | `GET /{taskNo}` 的 `lastRun.runNo` | 刷新页面、直接开链接、前进后退 |
| 用户触发 | 触发接口返回的 `{taskNo, runNo}` | 提交后跳转、重生成、翻译 |

两个入口都调 store 的 `startRun(ref)`。**不通过路由 query 或全局变量传 `runNo`**：
多一个入口就多一处要维护、多一处会不同步的地方，而快照本来就能给出当前 run。
备选：跳转时带 `?run=3` 直达——不采用，理由同上。

### 4.2 打开页面时的顺序

```
open(taskNo)
  → closeStream()            旧流先关（路由复用同一组件实例时会走到）
  → 清运行态
  → getTask + listArtifacts + 需要的产物 JSON
  → 落 snapshot（最后一步落）
  → if (lastRun.status === RUNNING) startRun({ taskNo, runNo: lastRun.runNo })
```

`snapshot` 必须**最后**赋值：`phase` 是纯 computed，先落 snapshot 再拉产物会让页面在
`ready` 与 `error`（「完成了却没字幕」）之间闪一下。

### 4.3 事件怎么处理

`useRunEvents` 只在两件事上回调业务层：

- `onProgress(progress)`：非终态事件 → 写进 `runProgress`（过单调闸，见 §5.3）。
- `onTerminal(progress)`：`terminal === true` → **先关流**，再回调；业务层做收口（§7.3）。

`terminal` 只读后端算好的字段（`TaskRunProgress.java:37-40`），前端不自己拼
「状态终态 且 翻译不在跑」这类组合——判据单源在后端。

### 4.4 204 / 404 / 断网：一律「关流 → 拉快照 → 看 lastRun」

`EventSource.onerror` 不带状态码，204（run 已终态）和 404（runNo 不存在）和网络断
在浏览器看来都是 `readyState === CLOSED`。所以前端**不编码 204/404 语义**，统一处理：

```
onerror
  → 立刻 close()            关掉浏览器自带的自动重连，改自管
  → 拉一次 GET /{taskNo} 确诊
      ├ 任务 404            → 走「任务不存在」卡，不再重试
      ├ lastRun 已非 RUNNING → 按终态收口（补上漏掉的终态事件），不再重试
      └ 仍是 RUNNING        → 退避重试
```

这么定有两个好处：

1. **204 不会被当成端点坏了**（AC3）：run 已终态 → 快照里必然不是 RUNNING → 直接收口。
2. **短任务几十毫秒就失败的情况也能收到结果**（翻译可能等不到 SSE 事件）：快照里的
   `lastRun.status` / `lastRun.errorMessage` 是兜底，不依赖流。

备选：不拉快照，直接退避重试三次再看——否掉，204 会被误报成「连接中断」。

### 4.5 自管重试：1s / 2s / 4s，最多 3 次

- 第 1、2、3 次重连分别等 1s、2s、4s；三次之后不再试。
- 用尽后：`streamError = '进度连接中断，请重试'`，并在顶栏胶囊位置给一个「重试」按钮。
- 每次重连前都先 `shouldReconnect()`（由 store 决定要不要重连），所以「run 其实已经结束」
  不会白等 7 秒。
- 任意一条进度事件到达即重置重试计数（说明流活了）。

**为什么不设无限重试**：PRD AC4 明确要「不无限静默重试」。无限重试会让「后端挂了」看起来像
「任务卡住了」，且占着连接。

### 4.6 代际校验：两道闸

1. **composable 内部**：每次 `start()` / `close()` 都 `generation += 1`；回调触发时先比对建立
   连接时抓下的 `generation`，不一致就丢弃。防止旧流最后一条事件打到新 run 上。
2. **store 内**：任何进度事件都要 `progress.runNo === activeRunNo` 才收；换 run 就换
   `activeRunNo`，旧 run 的事件自然被挡掉（AC2）。

两道都要：第一道挡「回调晚到」，第二道挡「后端把别的 run 的事件推过来」。

### 4.7 卸载与路由复用

- `stores/workspace.ts` 是全局单例，**不随路由卸载**（store 的 `onScopeDispose` 不会在离开页面时触发）。
  所以 `views/workspace/index.vue` 的 `onUnmounted` 必须显式调 `store.closeStream()`。
- 同一个页面组件在 `/workspace/A → /workspace/B` 之间会被复用：`index.vue` 里
  `watch(() => props.taskNo)`，变化时 `reset()` 再 `open()`。

### 4.8 代码形状

```ts
// frontend/src/composables/useRunEvents.ts
export type RunStreamState = 'connecting' | 'live' | 'reconnecting' | 'closed'

interface RunEventHandlers {
  /** 收到一条非终态进度。 */
  onProgress(progress: TaskRunProgress): void
  /** 这条 run 已结束；内部已关流。 */
  onTerminal(progress: TaskRunProgress): void
  /** 断线已关流，问业务层要不要退避重连（业务层在这里拉快照确诊）。 */
  shouldReconnect(): Promise<boolean>
  /** 重试次数用尽。 */
  onRetryExhausted(): void
}

export function useRunEvents(handlers: RunEventHandlers): {
  state: Ref<RunStreamState>
  start(taskNo: string, runNo: number): void
  close(): void
}
```

内部常量：`const RECONNECT_DELAYS_MS = [1000, 2000, 4000]`。
`start()` 会 `close()` 现有连接、清定时器、`generation += 1`、重置重试计数，然后建新连接。
`close()` 幂等：清 `setTimeout`、关 `EventSource`、置句柄为空、`generation += 1`。
`onScopeDispose(close)` 兜底（store 被销毁时）。

`parseProgress` 只在解析失败或形状不对时丢弃该条事件，**不断开连接**。

---

## 5. 进度口径与归一

### 5.1 三种 run 的原始含义

| runType | 后端 `stage.progress` 含义 | 直接显示？ |
|---|---|---|
| `MAIN` | 任务级 0–100（阶段区间按权重累加：0–2 / 2–22 / 22–43 / 43–67 / 67–70 / 70–100） | 是 |
| `RECLEAN` | 清洗阶段的**任务级** 70→100 | 否，要归一 |
| `TRANSLATE` | 本次 run 自己的 0–100 | 是 |

### 5.2 RECLEAN 归一

```ts
// frontend/src/utils/progress.ts
/** 镜像 backend SubtitleStageName 累加出来的 CLEAN_SUBTITLES.start()；改后端权重表时这里要跟着改。 */
const RECLEAN_STAGE_START = 70
const RECLEAN_STAGE_END = 100

/** 把一次 run 的原始阶段进度换成 0–100 的显示值；null / 越界都收敛到合法区间。 */
export function normalizeRunProgress(runType: TaskRunType, raw: number | null): number
```

- `MAIN` / `TRANSLATE`：原值，`clamp(0,100)`。
- `RECLEAN`：`(raw - 70) * 100 / 30`，四舍五入后 `clamp(0,100)`。
- `raw === null`（stage 还没落）或非有限数 → `0`。

**为什么只镜像一个常量**：完整权重表镜像进来会多六个永远用不上的数，改后端还会悄悄漂。
只记一个起点，漂移的后果是「RECLEAN 的进度条起点不是 0」或「到不了 100」，纯观感问题，
不会让任何逻辑判断出错。备选：镜像整张权重表——收益不足以抵消维护面。

### 5.3 单调保护

落在 store 的 `applyProgress` 里，规则只有一条：

```
新值 = normalizeRunProgress(progress.runType, progress.stage?.progress ?? null)
旧值 = normalizeRunProgress(当前 runProgress.runType, 当前 runProgress.stage?.progress ?? null)
if (runProgress !== null && 新值 < 旧值) 整条丢弃
else runProgress = progress
```

- 整条丢弃而不是只丢百分比：重连快照的 `message` 是「任务执行中」这种粗话，不能覆盖掉
  更具体的阶段文案。
- 相同百分比仍收下：后端同一阶段多发一条是正常的，消息可能变了。
- 换 run 时 `runProgress = null`，比较自然重置，不需要额外的 `maxPercent` 变量。
- 终态事件不走这条闸（先收口关流，不进 `applyProgress`）。

---

## 6. 状态机增补

### 6.1 `phase` 的判定（B2 改写）

```
loadError 是 404                     → 'not-found'
loadError 是别的错误                  → 'error'
snapshot 还没有                       → 'loading'
cleanedTrack !== null                → 'ready'
cleanedTrack === null:
    lastRun === null                 → 'error'（这个任务还没有任何执行记录，请重新提交）
    lastRun RUNNING                  → 'running'
    FAILED / INTERRUPTED             → 'run-failed'
    SUCCEEDED                        → 'error'（显示「任务已完成，但找不到可用字幕产物」）
```

关键取舍：**判据是「有没有字幕可看」，不是「lastRun 是什么状态」**。
所以翻译中、重新生成中（已有字幕）都是 `ready`，只是内容换成不可编辑 + 顶部进度；
只有首次生成（还没有字幕）才是 `running`。

这张表与 B1 `design.md` §6.2 是同一份：B1 先把它建出来，B2 不再改判据，只加运行态的表现。

### 6.2 `streamState`

| 值 | 含义 | 界面 |
|---|---|---|
| `idle` | 没有要跟的 run | 无胶囊 |
| `connecting` | 刚发起订阅，或触发请求还在路上 | 胶囊显示百分比（0%） |
| `live` | 收到过至少一条事件 | 胶囊正常走 |
| `reconnecting` | 断线退避中 | 胶囊文案换「连接中断，正在重连…」，百分比保留 |
| `closed` | 流已关（终态收口 / 手动关 / 重试用尽） | 正常时无胶囊；有 `streamError` 时显示重试入口 |

**实现方式偏离任务说明一处**：任务说明把 `streamState` 列在「state」里，本设计落成
**computed**：`activeRunNo === null ? 'idle' : runEvents.state.value`。
理由：流的真实状态只有 composable 知道，store 再存一份就是两个状态源，必然有一天不同步。
对外行为完全一样（组件只读）。备选：store 存一份、composable 用回调同步——多一条易漏的通道。

### 6.3 `isBusy`

```
isBusy = (lastRun?.status === RUNNING) || streamState ∈ {connecting, live, reconnecting}
```

含义是「这个任务现在有一次执行在跑」，不只是「本页在跟流」。

**为什么把翻译也算进去**：后端对同一任务只允许一条 run（互斥闸门，冲突即 409）。若翻译期间
还允许改关键词、点重生成，用户只会换来一个 409。统一禁用最不容易误导。
代价是翻译那几十秒里关键词不能改。备选：按 run 类型分别禁用（更细，但要专门处理 409 的解释）。

---

## 7. 触发流程

### 7.1 通用骨架

两个动作共用同一条流水线，差别只在请求体与收口后的界面动作：

```
guard（isBusy 就返回）
  → streamState 立刻进 'connecting'（同步设置，挡住连点）
  → streamError = null
  → POST 触发接口
      ├ 202 {taskNo, runNo} → startRun(ref)
      ├ 409 {taskNo, runNo} → startRun(ref)   // 冲突不是错误，跟那条正在跑的 run
      └ 其它（400/404/500） → streamState = 'closed'；streamError = ApiError.message
```

`streamState = 'connecting'` 放在请求之前，是因为「点下去到拿到 ref」之间没有任何状态能
阻止第二次点击；借用已有的流状态最省事，也不用新增 `triggering` 标志。

### 7.2 重新生成

```ts
async function regenerate(): Promise<void> {
  const taskNo = ...
  await regenerateSubtitles(taskNo, { keywords: [...keywords.value] })  // 总是数组，绝不发 null
}
```

- **总是提交数组**：`[]` 表示清空，`null`（字段缺席）表示不动清单。前端不存在「不改清单」
  的诉求，所以永远不发 `null`（`RegenerateSubtitlesRequest.java:12-17`）。
- 关键词的上限（单条 ≤ 50 字、≤ 50 条）由 B1 的输入框挡，B2 不改。
- 触发前不预置 `translationInvalidated`：要等这条 run **成功**才知道译文真的被作废。
- 409 的含义是「已经有一次执行在跑」，此时本地关键词编辑没有入库（`SubtitleRecleanService.java:84-88`
  先抢 run 再落关键词）。所以跟流即可，不提示失败；跑完后用户仍可再点一次。
- 跟 409 那条 run 收口时会重读关键词，本地未提交的编辑会被服务端清单覆盖。这是有意的：
  那批编辑从未生效，留在界面上会让人以为已经应用了。不额外弹提示（进度胶囊已经表明有 run 在跑）。

### 7.3 收口：`finishRun`

一条 run 结束后统一走这里，**不管结束事件是从 SSE 收到的，还是断线后从快照补判的**：

```ts
async function finishRun(finished: {
  runType: TaskRunType
  status: TaskRunStatus
  targetLanguage: string | null
}): Promise<void>
```

步骤：

1. `activeRunNo = null`、`runProgress = null`（同步做，作为重入闸：第二条收口路径进来时
   `runNo` 已经对不上，直接返回）。
2. 记下 `hadTranslation = artifacts.some(a => a.type === ARTIFACT_TYPE.TRANSLATED)`（刷新前）。
3. `if (finished.runType === TRANSLATE && finished.status === SUCCEEDED)` →
   `translatedLanguage = finished.targetLanguage`（先设，`refresh` 要靠它挑译文）。
4. `await refresh({ reloadKeywords: finished.runType !== TRANSLATE })`。
5. 成功后按类型补界面状态：
   - `RECLEAN` + `SUCCEEDED`：`translationInvalidated = hadTranslation`；
     清空 `localSegmentEdits`（原文变了，B1 的行内改写不再对应任何一行）。
   - `TRANSLATE` + `SUCCEEDED`：`translationInvalidated = false`。
   - 失败 / 中断：什么都不做，文案由 `lastRun.errorMessage` 供（§9）。
6. `refresh` 抛错：`streamError = '进度已结束，但刷新任务内容失败，请刷新页面'`。
   不把整页打成错误卡——终态已经确认，只是内容没跟上。

**关键词只在 MAIN / RECLEAN 收口后重读**：翻译期间用户手改过关键词（本地、未提交），
重读会把它们冲掉。这是本节最容易被忽略的一条。

### 7.4 翻译

```ts
async function translate(): Promise<void> {
  if (isBusy || selectedSourceLanguage === selectedTargetLanguage) return
  await translateTask(taskNo, {
    sourceLanguage: selectedSourceLanguage,
    targetLanguage: selectedTargetLanguage,
  })
}
```

触发前不切换显示的语言：译文只在**成功后**切（失败时旧译文仍在，属于诚实行为）。

---

## 8. 译文加载、配对与失效

### 8.1 从哪取

- 语种：成功后一律以 `lastRun.targetLanguage` 为准（PRD R4），不看用户此刻 select 选了什么
  ——用户可能在等待期间又换了选项。
- 文件：`pickArtifact(artifacts, ARTIFACT_TYPE.TRANSLATED, lang)` → `artifactFileUrl(artifact, 'segments')`
  → `fetchArtifactJson<TranslatedTrack>`。`files` 是 `Record`，取值必须判空
  （`noUncheckedIndexedAccess`），拿不到就当作没有译文。
- 配对：B1 的 `buildCues(cleaned, translated)` 按下标配，并校验时间戳一致；不一致时
  `mismatch` 为真。B2 只在字幕区显示一句「译文与原文时间轴不一致，已隐藏译文」，不自己重新配对。

### 8.2 切语种不重跑

`selectTargetLanguage(code)`：

```
selectedTargetLanguage = code
有 code 的 TRANSLATED 产物 → translatedLanguage = code；拉 segments.json 显示
没有                        → translatedLanguage = null；translatedTrack = null
```

即：已经翻过的语种，切回去直接显示（AC 里「切语种不必重跑」）；没翻过的，先清空显示，
让「翻译」按钮可点。翻译按钮此时可用是因为 `isBusy` 为假。

### 8.3 失效

- `translationInvalidated` **只是提示语开关**，不是「译文能不能用」的判据。
  译文能不能用一律看产物清单：RECLEAN 成功后后端已经把下游译文的版本作废
  （`SubtitleRecleanService.java:113`），清单里自然没有 TRANSLATED，`refresh` 后
  `translatedTrack` 变 null，双语行消失（AC8）。
- 提示文案：「字幕已重新生成，原译文已失效，需要重新翻译。」
- 只在「重生成成功 **且** 之前确实有译文」时置真：没有译文却说「原译文已失效」是假话。
- 关闭按钮是组件内局部状态（`TranscriptPane`），不进 store——刷新页面后提示消失是合理的。
- 已知局限：刷新页面后无法知道「上次是重生成导致的失效」，提示不会重现。备选：比对
  `keywordsFingerprint`——它只能说明字幕对应哪套关键词，推不出译文是否失效，不采用。

---

## 9. 失败与中断

| 情形 | 判据 | 界面 |
|---|---|---|
| 首次生成失败 / 被中断，且没有字幕 | `phase === 'run-failed'` | `StatusCard`：标题「任务处理失败」/「任务已中断」，正文 `lastRun.errorMessage`，主按钮「重新提交」 |
| 有字幕，但最近一条 run 失败（翻译 / 重生成） | `cleanedTrack !== null` 且 `lastRun.status ∈ {FAILED, INTERRUPTED}` 且 `lastRun.runType !== MAIN` | 字幕区顶部错误条，带 `lastRun.errorMessage` + 关闭 |
| 触发请求直接被拒（400/500） | `streamError` 非空 | 同一条错误条（互斥显示） |
| 流断了且重试用尽 | `streamError` 非空 | 顶栏胶囊位置显示「进度连接中断」+「重试」 |

「重新提交」的含义：

- URL 任务：用 `sourceUri` 调一次 `submitUrl({ url: sourceUri })`。
  返回的 `taskNo` 与当前不同（后端按内容寻址，可能落新工作区）→ `router.push` 到新页；
  相同 → `startRun(ref)` 原地继续。
- UPLOAD 任务：浏览器拿不到原文件，只能回首页重新选择 → `router.push('/')`。
  按钮文案写「返回首页重新提交」，不假称能重试。

`INTERRUPTED` 的文案以后端为准（`renderStage` 给的「上次被中断了，重新提交即可」），
前端不自己写第二套说法。

---

## 10. 组件增补清单

| 组件 | 改什么 | 数据从哪来 |
|---|---|---|
| `WorkspaceHeader.vue` | 现有「产物」下拉左侧加进度胶囊：小圆点 + 文案（处理中 / 重新生成中 / 翻译中）+ 百分比；`reconnecting` 时文案换「连接中断，正在重连…」；`streamError` 时换成「进度连接中断」+「重试」小按钮 | store：`runPercent` / `streamState` / `streamError` / `lastRun` / `runProgress`；重试调 `store.retryStream()` |
| `KeywordPane.vue` | 标题行右侧加「重新生成」；清单为空时文案「清空并重新生成」；`isBusy` 时按钮与输入框、增删全禁用 | store：`keywords` / `isBusy` / `regenerate()` |
| `TranslationBar.vue`（新建） | 源 / 目标双 `select` + 互换 + 翻译按钮 + 翻译进度条 + 一行状态文案 | props：`languages` / `source` / `target` / `translating` / `progress` / `translatedLanguage`；emit 四个事件，由 `TranscriptPane` 转给 store |
| `TranscriptPane.vue` | ① 每条字幕下方显示译文行（`cue.translatedText` 非空才显示）；② 头部换成「生成中」形态：百分比 + 阶段文案 + 进度条 + 骨架行；③ 失效提示条；④ 顶部错误条；⑤ `busy` 时隐藏修改按钮 | props 沿用 B1（`cues` / `player` 等）+ 直接读 store（`isBusy` / `runPercent` / `runProgress` / `translationInvalidated` / `streamError` / `lastRun` / `languages` / 语种选择） |
| `ThemePane.vue` | 加一个「首次生成中」分支：`busy && !context` → 「正在生成主题…」；重生成中保留旧内容 | store：`isBusy` / `context` |
| `StatusCard.vue` | 失败 / 中断卡复用；若 B1 的 props 不够，扩 `title` / `message` / `actionLabel` / `action-kind` | `index.vue` 传参 |

**组件读 store 还是走 props**：B1 已经把关键词、语种、运行态都放在 store 里，再逐个透传
会让 `index.vue` 变成一根十几行的管子。定：**运行态与翻译态直接读 store**；字幕内容
（`cues`）继续走 props，保持 B1 的边界。若 B1 实际落成了纯 props，B2 只改接线，不动 B1 定义。

`TranslationBar` 用 props 而不是读 store：它是纯展示组件，语种、进度、完成态都由父组件
算好传进来，方便单独核对。备选：直接读 store——会让「切换语种」这条链藏在两个组件里。

文案与状态：

- 源目标相同 → 按钮禁用 + 「源语言与目标语言相同，请选择其他目标语种。」
- 翻译中 → 按钮显示百分比，两个 `select` 与互换禁用。
- 已有译文 → 「已翻译为<语种名>」。

样式全部用 Tailwind 工具类，形态照原型（`prototype/src/components/TranslationBar.vue`），
但删掉所有「本地演示」字样——B2 是真调接口。

---

## 11. 收尾

### 11.1 残留 grep（`frontend/src`）

```
TaskView | TaskArtifact | TaskConflictResponse | existingTask
/events（不带 /runs/）
PENDING | translationStatus | taskStatus
source.mp4 | artifacts/\$\{...\}\.mp4
useTaskEvents | useTranscriptPlayer | stores/task | api/media | types/media | types/transcript
```

预期：全部零命中（`/events` 只应命中 `/runs/{runNo}/events` 这一种写法，用正则排除）。

### 11.2 必须实跑的验收项

`type-check` 在本项目是假绿（旧类型本地自洽也能过），下面这些一律以浏览器里看到为准：

- 提交 → 进度 → 就绪 → 翻译 → 双语（AC1）
- 重生成 / 翻译后换新 `runNo`，旧流不再推（AC2，看 Network 面板的 EventSource 连接数）
- 终态 run 订阅 204 不报错（AC3）
- 断线重连与重试用尽（AC4）
- 进度单调、RECLEAN 从 0 起、重连不回 0（AC5）
- 关键词「重新生成」，空清单发 `[]`（AC6，看请求体）
- 语种选择 / 互换 / 同语种禁用 / 双语显示（AC7）
- 重生成后译文消失 + 提示（AC8）
- 失败与中断（AC9）
- 六处错位逐条实测（AC10，步骤在 `implement.md`）

### 11.3 规范订正清单

`.trellis/spec/frontend/api.md` 按当前后端重写，具体失实处：

| 行 | 现在写的 | 改成 |
|---|---|---|
| `api.md:11` | SSE `GET /api/tasks/{taskNo}/events` | `GET /api/tasks/{taskNo}/runs/{runNo}/events`，一条 run 一条流 |
| `api.md:13-24` | 整节「SSE 订阅的错误处理」：404 走致命、断线靠浏览器自动重连、不设上限 | 改成：`onerror` 一律先关流（关掉浏览器自动重连），拉快照按 `lastRun` 确诊；204/404 不可区分；自管重试 1s/2s/4s 三次；代际校验；页面卸载关流 |
| `api.md:34-38` | 域文件表有 `media.ts` | 改成 `tasks.ts` / `artifacts.ts` / `languages.ts`，删 `media.ts` |
| `api.md:45` | 类型按域：`task.ts` / `media.ts` | `task.ts` / `subtitle.ts` |
| `api.md:48` | `request.get<TaskView>` | `request.get<TaskResponse>`，状态在 `lastRun` |
| `api.md:54-63` | 202 返回 `TaskView`；409 返回 `TaskConflictResponse`（含 `existingTask`）；翻译失败读顶层 `errorMessage` | 202/409 都是 `{taskNo, runNo}`；`ApiError.runRef`；400/404/500 是 `{error}`；失败读 `lastRun.errorMessage` |
| `api.md:73` | 反模式「丢掉 existingTask」 | 换成「把 409 当纯错误」「把 204 当端点坏了」「自己拼终态组合」 |

其余文档里的过期描述：

| 文件:行 | 现在写的 | 改成 |
|---|---|---|
| `directory-structure.md:54` | `tasks.ts` / `media.ts` | `tasks.ts` / `artifacts.ts` / `languages.ts` |
| `directory-structure.md:55` | `task.ts` / `media.ts` / `transcript.ts` | `task.ts` / `subtitle.ts` |
| `directory-structure.md:57` | `lib/` 当前只有 `gsap.ts` | 补 `day.ts` / `progress.ts` |
| `directory-structure.md:58` | `utils/` 现有 `format.ts` / `transcript.ts` | 补 `artifacts.ts` / `language.ts` |
| `directory-structure.md:63` | stores「当前为空」 | 有 `workspace.ts` |
| `directory-structure.md:64` | composables「按需再建」 | 有 `useRunEvents.ts` |
| `state-management.md:4` | 「当前目录为空」，示例 `@/stores/task` | 指向 `@/stores/workspace` |
| `component-guidelines.md:14,38` | 示例 import `@/stores/task` | `@/stores/workspace` |
| `component-guidelines.md:73` | 「项目暂无列表组件」 | 已有（关键词列表、字幕列表） |
| `component-guidelines.md:85` | composables「尚无文件」 | 已有 `useRunEvents.ts` |
| `type-safety.md:81,84` | `media.ts` | `subtitle.ts` |
| `type-safety.md:92` | 例 `TASK_STATUS` / `TaskStatus` | `TASK_RUN_STATUS` / `TaskRunStatus` |
| `type-safety.md:93` | 例 `TaskView` / `TaskStatus` | 换成现存的 `RunResponse` |
| `quality-guidelines.md:197` | 例 `isTaskConflictResponse` | 换成现存函数名 |
| `quality-guidelines.md:203` | 待改清单里的 `artifactHref`（`WorkspaceHeader.vue:39`） | B1 重写后该函数已不存在，删掉这一行 |

**订正原则**：只改与事实不符的句子，不借机重写风格；废弃内容直接删，不留下「（已废弃）」的注。

---

## 12. 风险与回滚

| 风险 | 应对 |
|---|---|
| `type-check` 假绿，模板里的错误过不了 | 每条验收都在浏览器里走一遍；`npm run build` 也跑（它含 `type-check`） |
| 重试把「后端挂了」伪装成「任务卡住」 | 最多三次；用尽后明确报错并给手动入口 |
| 两条收口路径同时跑，刷新两次 | `finishRun` 进门前同步把 `activeRunNo` 置空，第二条进来 `runNo` 对不上直接返回 |
| 离开页面后流还在跑 | `index.vue` 的 `onUnmounted` 显式 `closeStream()`，不能只靠 store 作用域 |
| 翻译期间重读关键词冲掉本地编辑 | `refresh({ reloadKeywords: runType !== TRANSLATE })` |
| 后端权重表变了，RECLEAN 起点漂 | 只影响观感；常量处写清「镜像自 `SubtitleStageName`」 |
| 服务端 30 分钟断流 | 走同一条重连路径，用户看到一次「正在重连」后恢复 |
| B1 落地形状与本文假设不同（props / action 名） | 只改 B2 的调用点，不回改 B1 的边界；差异记进 `implement.md` |

**回滚**：B2 的改动集中在 `useRunEvents.ts` + store 的运行态段落 + 四个组件的分支上，
按 `implement.md` 的步骤逐条提交。要回退到 B1 基线，把这几个提交一次性 revert 即可——
B1 基线本身是完整可验收的状态（能打开已完成任务、能提交跳转）。

---

## 13. 事实来源

- `.trellis/tasks/10-22-workspace-run-page/prd.md`
- `.trellis/tasks/10-22-workspace-ready-page/prd.md`
- `.trellis/tasks/10-21-workspace-fe-rewrite/prd.md`、`handoff.md`
- `.trellis/tasks/10-21-workspace-prototype/design.md`
- `.trellis/spec/frontend/*.md`
- `backend/src/main/java/com/vidsage/web/controller/TaskController.java:156-169`
- `backend/src/main/java/com/vidsage/web/controller/ArtifactController.java:46-108`
- `backend/src/main/java/com/vidsage/web/controller/ApiExceptionHandler.java:95-100`
- `backend/src/main/java/com/vidsage/web/dto/{TaskResponse,RunResponse,TaskRunRefResponse,ArtifactResponse,RegenerateSubtitlesRequest}.java`
- `backend/src/main/java/com/vidsage/application/task/message/TaskRunProgress.java:37-86`
- `backend/src/main/java/com/vidsage/domain/subtitle/model/common/SubtitleStageName.java:16-54`
- `backend/src/main/java/com/vidsage/shared/progress/ProgressMapper.java:26-32`
- `backend/src/main/java/com/vidsage/application/task/SubtitleRecleanService.java:84-113`
- `backend/src/main/java/com/vidsage/application/task/SubtitleTranslateService.java:81-120`
- `backend/src/main/java/com/vidsage/web/sse/SseTaskProgressBroadcaster.java:49-128`
- `frontend/src/composables/useTaskEvents.ts`（旧实现，只作对照）
- `frontend/src/stores/task.ts`（旧实现，只作对照）
- `prototype/src/views/WorkspaceView.vue`、`components/{TranscriptPane,KeywordPane,TranslationBar}.vue`
