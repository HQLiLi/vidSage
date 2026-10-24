# 执行计划：工作区运行态（B2）

> 配套 `design.md`（为什么这样定）。本文只写「按什么顺序做、怎么算做完」。
> 前置：`10-22-workspace-ready-page`（B1）已交付并合并；本任务在它的代码上做增量。

---

## 0. 开工前

- [ ] **0.1 分支**：按分支硬规则，从 B1 合并后的当前分支切 `feature/workspace-run-page`，
      合回**它切出来的那个分支**（不一定是 `dev`）。不开 worktree。
- [ ] **0.2 读 B1 的最终形状**：`frontend/src/stores/workspace.ts` 的导出、`index.vue` 的模板分支、
      `components/` 下每个组件的 props/emit。本文假设的形状与 `design.md` §2 一致，
      实际不同就改 B2 的调用点，并把差异记进 §8「偏离与默认取舍」。
- [ ] **0.3 起后端**（Git Bash 必须显式 JDK 21）：

      cd backend && JAVA_HOME=/f/java_soft/java21 mvn -Dmaven.test.skip=true spring-boot:run

      需要 `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 已设（重生成、翻译要真调模型）。
- [ ] **0.4 起前端**：`cd frontend && npm run dev`（`/api` 由 vite 代理到 8080）。
- [ ] **0.5 找一个可用任务**：

      mysql -uroot -pzwl61758157 -D vidsage --default-character-set=utf8mb4 \
        -e "select t.task_no, r.run_no, r.run_type, r.status from task_run r \
            join subtitle_task t on t.id = r.task_id order by r.id desc limit 10;"

      挑一条 `MAIN / SUCCEEDED` 的，记下 `taskNo`（下面叫 `$T`）。

**完成判据**：浏览器打开 `/workspace/$T` 能看到字幕、关键词、主题（B1 的成果）。

---

## 1. 类型与进度换算

**做什么**

1. `frontend/src/types/task.ts` 加两个类型（形状见 `design.md` §4.8）：

   ```ts
   export type StageState = 'RUNNING' | 'SUCCEEDED' | 'SKIPPED' | 'FAILED'
   export interface StageProgress { stage: string; progress: number; message: string; state: StageState }
   export interface TaskRunProgress {
     taskNo: string; runNo: number
     runType: TaskRunType; status: TaskRunStatus
     stage: StageProgress | null
     terminal: boolean
     targetLanguage?: string | null   // 只有 TRANSLATE 变体带，其余是字段缺席（不是 null）
   }
   ```

2. `frontend/src/utils/progress.ts` 把 B1 的骨架补成行为：
   `RECLEAN_STAGE_START = 70` / `RECLEAN_STAGE_END = 100`，
   `normalizeRunProgress(runType, raw): number`。

**涉及文件**：`frontend/src/types/task.ts`、`frontend/src/utils/progress.ts`

**验证**：`npm run type-check`；再临时在浏览器控制台对着 store 打印一次换算（见步骤 3 之后）。

**完成判据**：`normalizeRunProgress('RECLEAN', 70) === 0`、`(…, 85) === 50`、`(…, 100) === 100`；
`('MAIN', 43) === 43`；`(…, null) === 0`。

---

## 2. 建 `useRunEvents`

**做什么**：按 `design.md` §4 新建 `frontend/src/composables/useRunEvents.ts`。

要点清单（逐条对照，缺一条就是没做完）：

- [ ] `start(taskNo, runNo)` 先关旧流、清定时器、`generation += 1`、重试计数归零，再建 `EventSource`
- [ ] URL 写全路径 `/api/tasks/${taskNo}/runs/${runNo}/events`（吃不到 axios 的 baseURL）
- [ ] 只听 `progress` 事件；`JSON.parse` 失败或形状不对就丢这一条，不断流
- [ ] 收到事件先做**代际 + taskNo + runNo** 三重比对，再回调
- [ ] `terminal === true` → 关流、置 `closed`、回调 `onTerminal`，不再重连
- [ ] `onerror` → **立刻 `close()`**（关掉浏览器自动重连）
- [ ] 重连退避 `[1000, 2000, 4000]`，最多 3 次；每次重连前 `await handlers.shouldReconnect()`
- [ ] 三次用尽 → 置 `closed`、回调 `onRetryExhausted`，不再排下一轮
- [ ] `close()` 幂等（清 `setTimeout` + `source.close()` + `generation += 1`）
- [ ] `onScopeDispose(close)` 兜底
- [ ] 导出 `state: Ref<'connecting' | 'live' | 'reconnecting' | 'closed'>`

**涉及文件**：`frontend/src/composables/useRunEvents.ts`（新建）

**验证**：`npm run type-check`、`npm run lint`。运行期验证放到步骤 5 之后（要有 store 才能跑起来）。

**完成判据**：编译与 lint 过；文件里没有第二份 `EventSource` 以外的连接逻辑，没有 `fetch` 轮询。

---

## 3. store 的运行态

**做什么**：改 `frontend/src/stores/workspace.ts`。

- [ ] state：`runProgress` / `activeRunNo` / `streamError` / `translationInvalidated`
- [ ] `const runEvents = useRunEvents({...})` 在 setup 顶层建
- [ ] computed：`streamState`（`activeRunNo === null ? 'idle' : runEvents.state.value`）、
      `runPercent`、`isBusy`
- [ ] action：`startRun(ref)`、`retryStream()`、`regenerate()`、`translate()`、
      `selectTargetLanguage(code)`、`closeStream()`
- [ ] 内部：`applyProgress`（单调闸）、`handleTerminal`、`shouldReconnect`、
      `handleRetryExhausted`、`finishRun`
- [ ] `open()`：开头 `closeStream()` + 清运行态；结尾按 `lastRun.status === RUNNING` 自动 `startRun`
- [ ] `refresh()`：加 `{ reloadKeywords?: boolean }` 选项；有 `translatedLanguage` 时顺带重挂译文
- [ ] `reset()`：一并关流、清运行态
- [ ] `phase` 按 `design.md` §6.1 改写
- [ ] 每个新 action / computed 都写中文注释（quality-guidelines 第二节）

**涉及文件**：`frontend/src/stores/workspace.ts`

**验证**：

- `npm run type-check`、`npm run lint`
- 打开 `/workspace/$T`（已完成任务）→ 不建流（Network 里没有 EventSource），
  控制台里 `runPercent` 为 0、`streamState` 为 `idle`
- 手动在控制台构造 `{runType:'RECLEAN', stage:{progress:85}}` 走一遍 `applyProgress` 的换算
  （或直接核对 `normalizeRunProgress`）

**完成判据**：已完成任务的页面行为与 B1 完全一致（没有任何新胶囊 / 新请求）。

---

## 4. 页面接线

**做什么**：改 `frontend/src/views/workspace/index.vue`。

- [ ] `onMounted` 后 `open(props.taskNo)`（若 B1 没在 open 里订阅，这里不要重复订阅）
- [ ] `watch(() => props.taskNo)` → `reset()` + `open()`（路由复用同一实例）
- [ ] `onUnmounted` → `store.closeStream()`（store 不随路由销毁，必须显式关）
- [ ] `phase` 分支：`running` / `ready` 都渲染工作区壳；`loading` / `not-found` / `error` /
      `run-failed` 走 `StatusCard`
- [ ] 壳的入场动画沿用 B1（`overflow-clip` 保留）

**涉及文件**：`frontend/src/views/workspace/index.vue`

**验证**：打开一个已完成任务，再通过首页提交一个新任务跳过来，观察：

- 提交后进页面：`lastRun.status === RUNNING` → 自动建流，Network 出现一条 `events` 连接；
- 直接刷新该页：仍建流（恢复入口就是快照里的 `lastRun.runNo`）；
- 点到别的页面再回来：旧连接关闭、新连接建立（Network 里同一时刻最多一条）。

**完成判据**：上述三条都成立；离开页面后 EventSource 连接消失。

---

## 5. 顶栏进度胶囊

**做什么**：改 `components/WorkspaceHeader.vue`。

- [ ] 「产物」下拉左侧加胶囊：小圆点 + 文案 + 百分比（`tabular-nums`）
- [ ] 文案按 runType：`MAIN` 处理中 / `RECLEAN` 重新生成中 / `TRANSLATE` 翻译中
- [ ] `reconnecting` → 「连接中断，正在重连…」（百分比保留上一次的值）
- [ ] `streamError` 非空 → 「进度连接中断」+「重试」小按钮，调 `store.retryStream()`
- [ ] 只在 `isBusy` 时显示胶囊；就绪且空闲时不占位

**涉及文件**：`components/WorkspaceHeader.vue`

**验证**（要一条真在跑的任务）：

1. 提交 URL 或上传 `prototype/public/demo/lecture.mp4`（本机已有该文件），跳到工作区：
   胶囊出现，百分比从 0 涨到 100，期间阶段文案在变。
2. 中途把后端进程杀掉（或在 DevTools 里把网络设成 Offline）：胶囊变「连接中断，正在重连…」，
   恢复后继续走。
3. 保持断网直到三次重试用尽：出现「进度连接中断」+「重试」；恢复网络后点「重试」，进度继续。

**完成判据**：AC4 的三段（自动重连 → 提示 → 手动重试）都能看到，且重试不超过 3 次。

---

## 6. 字幕区与主题区的运行态

**做什么**：改 `components/TranscriptPane.vue`、`components/ThemePane.vue`。

- [ ] 生成中（`phase === 'running'`）：头部换成「百分比 + 阶段文案 + 进度条」，列表位置放 5 行骨架
- [ ] 重生成中（`isBusy && cleanedTrack !== null`）：内容保留但不可编辑，头部同样显示进度
- [ ] `busy` 时隐藏行内修改按钮，禁用可聚焦行的编辑入口
- [ ] 骨架用 `animate-pulse` + `motion-reduce:animate-none`（reduced-motion 降级）
- [ ] 原型里「按清洗进度逐条放出字幕」的分支不要抄（真实后端拿不到半成品产物）
- [ ] `ThemePane`：`isBusy && !context` → 「正在生成主题…」；重生成中保留旧主题

**涉及文件**：`components/TranscriptPane.vue`、`components/ThemePane.vue`

**验证**：跑一次新的 MAIN（提交），全程看字幕区：无字幕时是骨架 + 进度，完成后自动换成真实字幕，
中间不出现「没有匹配的字幕」这类空态文案。

**完成判据**：生成过程中页面不显示误导性的空态；完成后无需刷新即有内容。

---

## 7. 关键词「重新生成」

**做什么**：改 `components/KeywordPane.vue`。

- [ ] 标题行右侧加按钮，调 `store.regenerate()`
- [ ] 清单为空 → 文案「清空并重新生成」；有词 → 「重新生成」
- [ ] `isBusy` → 按钮、输入框、增删全部禁用
- [ ] 提交的是当前完整清单（数组），**永不发 `null`**

**涉及文件**：`components/KeywordPane.vue`

**验证**：

1. 打开 `/workspace/$T`，加两个关键词 → 点「重新生成」→ Network 里
   `POST /api/tasks/$T/subtitles/regenerate` 的请求体是 `{"keywords":["a","b"]}` → 202 →
   胶囊显示「重新生成中」→ 完成后关键词与字幕刷新。
2. 删光关键词 → 按钮变「清空并重新生成」→ 请求体是 `{"keywords":[]}`（**不是** `{}`、不是 `null`）。
3. 运行中连点按钮 / 改输入框：全部无效（禁用）。

**完成判据**：AC6 全过，且请求体在两种情况下都正确。

---

## 8. 翻译：控件、加载、失效

**做什么**

- [ ] 新建 `components/TranslationBar.vue`（props / emit 见 `design.md` §10），挂在字幕区头部
- [ ] `TranscriptPane` 接 `update:sourceLanguage` / `update:targetLanguage` / `swap` / `translate`
- [ ] store 的 `translate()` / `selectTargetLanguage()` 按 `design.md` §7.4 / §8.2
- [ ] 译文行：`cue.translatedText` 非空才渲染，样式照原型（小一号、`text-muted`）
- [ ] 时间轴不一致：`translationMismatch` 为真 → 隐藏译文并提示一句
- [ ] 失效提示条（可关闭）+ 「重新生成成功后译文消失」
- [ ] 语种清单 `getLanguages()` 只在 store 里拉一次（B1 已有就复用）

**涉及文件**：`components/TranslationBar.vue`（新建）、`components/TranscriptPane.vue`、
`stores/workspace.ts`

**验证**：

1. 打开 `$T` → 两个 select 有真实语种，源语种 = `context.sourceLanguage`，目标默认英语。
2. 把源和目标选成同一个 → 按钮禁用 + 说明文案。
3. 点「互换」→ 两个 select 互换，**译文不变**（还没翻）。
4. 选一个没翻过的目标 → 翻译 → 胶囊「翻译中」+ 条内百分比 → 完成后双语行出现，
   顶部出现「已翻译为 X」；`GET $T` 的 `lastRun.targetLanguage` 与显示一致。
5. 切到另一个已翻译过的语种 → 直接显示，Network 里没有新的 translate 请求。
6. 重复目标语种再点翻译：后端会跳过并 SUCCEEDED，界面不应卡在「翻译中」。
7. 再对同一任务点「重新生成」→ 成功后：译文行消失、出现失效提示、产物清单里没有 TRANSLATED。

**完成判据**：AC1 / AC7 / AC8 全过。

---

## 9. 失败与中断

**做什么**

- [ ] `index.vue`：`phase === 'run-failed'` → `StatusCard`（标题按 FAILED / INTERRUPTED 分，
      正文 `lastRun.errorMessage`，主按钮按 `inputType` 决定「重新提交」还是「返回首页重新提交」）
- [ ] `TranscriptPane`：有内容时顶部错误条（`lastRun` 失败文案或 `streamError`，可关闭）
- [ ] URL 任务重试：`submitUrl({ url: sourceUri })` → 若返回的 taskNo 变了就 `router.push`，否则 `startRun`

**涉及文件**：`views/workspace/index.vue`、`components/TranscriptPane.vue`、`components/StatusCard.vue`

**验证**：

1. 造一次失败的主任务：提交一个 yt-dlp 取不到的链接（如 `https://example.com/nothing`）→
   等 run 变 FAILED → 页面出错误卡，正文是后端的中文 `errorMessage`；
   点「重新提交」能再次发起（Network 里有新的 `POST /api/tasks`）。
2. 造一次失败的有字幕任务：对 `$T` 触发翻译，但把语种传成白名单外的值（用 curl 或临时改请求）
   → 400 → 顶部错误条出现，正文是后端的说明，字幕仍可读。
3. 中断：用 `update task_run set status='INTERRUPTED' where ...` 造一条（本地库），刷新页面 →
   卡片标题是「任务已中断」，正文来自后端 `renderStage` 的文案。
   造完记得把数据改回去或换一个任务继续测。

**完成判据**：AC9 过，两种形态（卡 / 条）都出现且文案来自后端。

---

## 10. 收尾：残留与规范

- [ ] 在 `frontend/src` 跑 `design.md` §11.1 的 grep，逐条确认零命中：

      cd frontend && grep -rnE "TaskView|TaskArtifact|TaskConflictResponse|existingTask" src
      grep -rnE "PENDING|translationStatus|taskStatus" src
      grep -rnE "source\.mp4|useTaskEvents|useTranscriptPlayer|stores/task|api/media|types/media|types/transcript" src
      grep -rn "/events" src    # 只允许出现在 /runs/${runNo}/events 这一种写法里

- [ ] 按 `design.md` §11.3 的表逐行订正：
      `api.md`（重写 SSE 与错误两节 + 域文件表 + 类型一节 + 反模式）、
      `directory-structure.md`、`state-management.md`、`component-guidelines.md`、
      `type-safety.md`、`quality-guidelines.md`
- [ ] 订正后再读一遍 `api.md`，确认没有残留「自动重连不设上限」「TaskView」「existingTask」

**完成判据**：grep 全空；`api.md` 里描述的行为与 B2 的实现一致（照着它写下一个任务不会踩坑）。

---

## 11. 逐条实测接口（AC10）

> 规矩：**不接受「静态看着对」**。每条都要有 curl 输出或浏览器截图/Network 记录。
> SSE 的提交与订阅**必须在同一个 shell 里**（先后台提交、拿 runNo、立刻订阅），否则流已关。

| # | 对应 | 操作 | 看到什么算过 |
|---|---|---|---|
| 1 | 提交读 ref | `curl -i -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' -d '{"url":"<一个可用的视频页>"}'` | `202`；body **只有** `{"taskNo":"…","runNo":1}`，没有 status / artifacts 等字段 |
| 2 | 上传读 ref | `curl -i -X POST localhost:8080/api/tasks/upload -F file=@prototype/public/demo/lecture.mp4` | `202`；body 同形两个键 |
| 3 | 翻译读 ref + body | `curl -i -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' -d '{"sourceLanguage":"zh-Hans","targetLanguage":"en"}'` | `202` + `{taskNo, runNo}`；紧接着再发一次 → `409`，body 仍是 `{taskNo, runNo}`（**没有** `error` 字段） |
| 4 | 重生成读 ref + 空数组语义 | `curl -i -X POST localhost:8080/api/tasks/$T/subtitles/regenerate -H 'Content-Type: application/json' -d '{"keywords":[]}'`；再发一次 `-d '{}'` | 两次都 `202` + ref；前端抓包看到的请求体永远是数组，看不到 `null` |
| 5 | 快照读 lastRun | `curl -s localhost:8080/api/tasks/$T` | 顶层有 `taskNo`/`inputType`/`sourceUri`/`createdAt`/`lastRun`；**没有** `status`/`errorMessage`/`artifacts`；`lastRun` 里有 `runNo`/`runType`/`status`/`targetLanguage`/`currentStage`/`errorMessage`/`startedAt`/`finishedAt` |
| 6 | 产物走清单 + files url | `curl -s localhost:8080/api/tasks/$T/artifacts`，再 `curl -s localhost:8080<files.segments.url>` | 清单项有 `type`/`lang`/`revision`/`keywordsFingerprint`/`files`；`files` 的键是 `segments`/`subtitles`/`context`；直接 GET 那个 url 返回 JSON 200；TRANSLATED 项的 url 带 `?lang=` |
| 7 | SSE 带 runNo、204、404 | 同 shell：`T=$(curl -s -X POST … | …taskNo)`、`R=…runNo`，`curl -N localhost:8080/api/tasks/$T/runs/$R/events`；跑完后再打一次；再打 `…/runs/999/events` | 开流时 `200 text/event-stream`，首条 `event: progress` 的 data 里 `runNo` 等于 `$R`；跑完再打 → `204`；999 → `404` |
| 8 | 无 `source.mp4` 死链 | 浏览器打开工作区，看 Network；同时 `grep -rn "source.mp4" frontend/src` | 没有任何 `.mp4` 请求；播放器是占位，不撒谎 |

**完成判据**：8 条全部有输出留档（贴进任务目录的 `research/` 或 `implement.md` 末尾的勾选表）。

---

## 12. 全量验证

```bash
cd frontend
npm run type-check     # 假绿，不能单独当门
npm run lint
npm run build          # 并行跑 type-check + build-only
```

再按 `design.md` §11.2 在浏览器里把 AC1–AC9 走一遍。**type-check 绿不等于过**。

---

## 13. 评审门

| 门 | 位置 | 看什么 |
|---|---|---|
| G1 | 步骤 3 结束 | store 里运行态与 B1 的边界是否清楚；有没有第二份状态源；单调闸是否只在一处 |
| G2 | 步骤 8 结束 | 翻译链路是否只从 `lastRun.targetLanguage` 取语种；失效提示是否只当提示用 |
| G3 | 步骤 11 结束 | 8 条实测是否每条都有输出；有没有「看着对」充数 |
| G4 | 步骤 10 结束 | `api.md` 重写后是否自洽；失实描述是否只删不粉饰 |

每个门之后停下等审查，不自行提交/合并。

---

## 14. AC 对照表

| AC | 怎么验 | 判据 |
|---|---|---|
| AC1 端到端 | 步骤 5 + 8 | 提交 → 0→100 → 双栏 → 翻译 → 双语 |
| AC2 换 run 重订阅 | 步骤 5 的 Network：重生成 / 翻译后出现**新**的 `events` 连接，旧连接关闭 | 同一时刻最多一条流；新事件 `runNo` 变大 |
| AC3 204 不当错误 | 对已终态 run 手动开流（快照 → 订阅）或跑一个秒完的翻译 | 不出现「连接中断」；页面按已完成处理 |
| AC4 断线重连 | 步骤 5 的 2/3 | 自动重连有提示；三次后给手动入口 |
| AC5 进度单调 | 步骤 5 观察 + 重连时看百分比 | 从不回退；RECLEAN 从 0 起；重连快照的 0 不把进度打回起点 |
| AC6 重生成 | 步骤 7 | 请求体是完整数组；空清单发 `[]`；进度走 SSE；完成后刷新 |
| AC7 翻译控件 | 步骤 8 的 1–4 | 语种真实、可互换、同语种禁用、双语显示 |
| AC8 译文失效 | 步骤 8 的 7 | 译文消失 + 失效提示 |
| AC9 失败 / 中断 | 步骤 9 | 卡或条，文案来自后端 `errorMessage` |
| AC10 接口实测 | 步骤 11 | 8 条全过 |
| AC11 无残留 + 三命令 | 步骤 10 + 12 | grep 空；type-check / lint / build 通过 |
| AC12 规范订正 | 步骤 10 | 表里每一行都改到，`api.md` 与实现一致 |

---

## 15. 回滚点

| 回滚点 | 位置 | 回退方式 | 回退后的状态 |
|---|---|---|---|
| R1 | 步骤 1–2 之后 | 删 `useRunEvents.ts`、还原 `types/task.ts` / `utils/progress.ts` | 完全等于 B1 |
| R2 | 步骤 3–4 之后 | revert 这两个提交 | 页面能用，但进行中的任务回到 B1 的占位 |
| R3 | 步骤 5–9 之后 | 按组件逐个 revert | 功能递减，不会互相牵连 |
| R4 | 步骤 10 之后 | 规范订正单独一个提交，可单独 revert | 代码不受影响 |

---

## 16. 偏离与默认取舍（PRD 没写死的地方）

| # | 取舍 | 理由 | 备选（未采用） |
|---|---|---|---|
| D1 | 运行中直接渲染双栏 + 骨架，不用居中进度卡 | 原型就是这个形态；完成时不用整体换布局 | 居中卡片 → 完成后切双栏（更简单但跳一下） |
| D2 | 删掉原型里「按清洗进度逐条放出字幕」 | 产物清单只列完整版本，真实后端走不到 | 保留但永不触发（死代码） |
| D3 | 重试 1s/2s/4s，最多 3 次 | PRD 写死；再长会让「后端挂了」看起来像卡住 | 无限重试（PRD 明确不要） |
| D4 | 204/404 都不编码语义，统一快照确诊 | EventSource 拿不到状态码；204 与 404 不可区分 | 靠 `readyState` 分两类（旧规范的做法，会误报） |
| D5 | `streamState` 落成 computed（任务说明里写的是 state） | 避免两个状态源 | 回调同步一份到 store |
| D6 | 额外加一个 `retryStream()` action | 组件不该知道怎么拼 `TaskRunRef` | 组件直接调 `startRun` |
| D7 | 翻译 / 重生成也算进 `isBusy`，期间关键词不可改 | 后端对同一任务只允许一条 run，放行只会换来 409 | 按 run 类型分别禁用 |
| D8 | 翻译成功后一律用 `lastRun.targetLanguage`，不看当前 select | 用户可能在等待期间又换了选项 | 用触发时记下的目标语种 |
| D9 | 关键词只在 MAIN / RECLEAN 收口后重读 | 翻译收口时重读会冲掉用户本地未提交的编辑；重生成 409 跟随时覆盖是有意的——那批编辑从未提交 | 每次都重读 |
| D10 | 失效提示关闭状态放组件本地 | 只是提示，不是数据；刷新后消失合理 | 放 store |
| D11 | URL 任务失败卡给「重新提交」，UPLOAD 只给回首页 | 浏览器拿不到用户选过的原文件，假称能重试是撒谎 | 两种都只给回首页 |
| D12 | 翻译成功后不自动切换源 / 目标 select | 用户手动选的要留着；显示的语种另有「已翻译为 X」标注 | 自动同步到 `lastRun.targetLanguage` |

**已知缺口（不修，登记即可）**

- 媒体不能播（后端无端点），播放器保持占位。
- 单条字幕修改不持久化（后端无端点），B1 已明示。
- 刷新页面后不知道「上次重生成导致译文失效」，提示不重现（只在当前会话内提示）。
- 关键词指纹不展示（B1 已定），因此「字幕对不对得上当前关键词」只能靠产物清单间接判断。

---

## 17. 交付物清单

**代码**

- `frontend/src/composables/useRunEvents.ts`（新建）
- `frontend/src/stores/workspace.ts`（运行态部分）
- `frontend/src/utils/progress.ts`
- `frontend/src/types/task.ts`（加两个 SSE 类型）
- `frontend/src/views/workspace/index.vue`
- `frontend/src/views/workspace/components/`：`WorkspaceHeader.vue`、`KeywordPane.vue`、
  `TranscriptPane.vue`、`ThemePane.vue`、`StatusCard.vue`、`TranslationBar.vue`（新建）

**规范**

- `.trellis/spec/frontend/api.md`（重写 SSE 与错误两节、域文件表、类型一节、反模式）
- `.trellis/spec/frontend/directory-structure.md`、`state-management.md`、
  `component-guidelines.md`、`type-safety.md`、`quality-guidelines.md`（逐行订正）

**证据**

- 步骤 11 的 8 条实测输出
- AC1–AC9 的浏览器验证记录（截图或录屏说明）
- `npm run type-check` / `lint` / `build` 的输出

---

## 18. 实测与收口记录（2026-10-24）

### 已完成

- 步骤 1–8 实现并实跑：run-scoped SSE 订阅、自管重连（1s/2s/4s 三次）、终态 204 回查快照收口、
  顶栏进度胶囊、字幕骨架、关键词重生成（空数组请求体已抓包）、翻译语种选择 / 互换 / 双语显示 / 失效提示。
- 实测证据：`evidence/g2-bilingual-ready.png`、`evidence/g2-regenerate-disabled.png`；
  断线 / 终态 / 进度 / 骨架的数据记录见任务评审回复。
- `npm run type-check` / `npm run lint` / `npm run build` 全绿。
- 残留 grep：只命中允许的 `/runs/{runNo}/events` 写法与一句「后端没有 PENDING」的说明性注释。

### 偏离与修正（评审期间新增）

| # | 取舍 / 修正 | 理由 |
|---|---|---|
| D13 | `loadAll` 改为**快照最后赋值** | 原顺序会让收口瞬间出现「快照已 SUCCEEDED、字幕还没到」的窗口，`phase` 判成 error 闪出加载失败卡 |
| D14 | 收口刷新失败时，已有内容只出错误条；没内容才保留错误卡 | 终态已确认，不该把能看的内容藏掉；没内容时需要重试入口 |
| D15 | 打开任务时，默认目标语种已有现成译文就直接显示 | R4「已存在的 TRANSLATED 产物直接显示」 |
| D16 | 搜索同时匹配原文与译文 | 双语都在屏上，只搜原文会让人以为搜不到 |
| D17 | `TranslationBar` 增加 `busy` prop | 任何 run 在跑都禁用语种控件，避免点了被 store 静默挡住 |

### 本次收口时明确留下的未完成项

- 步骤 9 的 URL 任务「重新提交」未接线：失败卡按钮仍是「返回上传」。
- 步骤 10 的规范订正（AC12）未做——所有者指示本次只记录 journal。
- 步骤 11 的 8 条 curl 留档未逐条保存；其中翻译 202、SSE 终态 204 → 快照收口、产物清单与下载、
  无 `source.mp4` 死链已实跑，提交 / 上传 202 的形状与 409 同形未单独留档。
- 媒体播放（父任务 Q4 的后端缺口）未做，播放器维持无源占位。
