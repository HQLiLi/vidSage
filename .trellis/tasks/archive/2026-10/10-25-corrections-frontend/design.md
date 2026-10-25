# 前端字幕纠错交互 — 设计

> 技术口径源头是父任务 `design.md` §6。本文只记这个子任务落地时定下的东西：
> 文件落点、状态形状、编辑状态机、恢复入口与刷新时机。需求与验收见 `prd.md`。

## 1. 数据从哪来

- 页面读的 CLEANED / TRANSLATED `segments.json` 已经过后端覆盖层（`10-25-corrections-read-paths`
  已接线），所以「保存后重新加载」天然带纠错。前端不把纠错列表再叠一遍到页面上。
- 只有「保存成功后立刻看到新文本」这一步是本地补丁：把返回值写进内存里的 track。
  下次加载以后端为准。
- 另外拉一份 `GET /corrections`，只为两件事：
  1. 数 `matched=false` 的条数 → 顶部「N 条纠错已失效」；
  2. 判断某条有没有纠错 → 编辑态里要不要显示「恢复原文 / 恢复译文」。
- 纠错列表是附属信息：拉不到就当空清单，不把整页拖进错误卡（与关键词同一口径）。

## 2. 类型与工具

### 2.1 `id` 在旧产物里可能缺席

- `TranscriptionSegment` / `TranslatedSegment` 加 `id?: string`。
- **为什么是可缺省而不是 `| null`**：新产物落盘带 `id`；旧产物磁盘上没有这个键，而后端
  「零纠错」时直发磁盘文件，前端拿到的 JSON 里就是没有 `id`，不是 `null`。
- `buildSegmentId(segment)`（`utils/transcript.ts`）：`id` 有值就用，没有就按
  `seg-<beginTimeMs>-<endTimeMs>` 现算。规则与后端 `TranscriptionSegment#syntheticId` 同一份，
  两处必须一起改；否则纠错会保存到后端找不到的 id 上。

### 2.2 页面视图模型

`SubtitleCue` 增加：

| 字段 | 含义 |
|---|---|
| `id` | 这条字幕的稳定标识，保存纠错时提交的就是它 |
| `sourceText` | 当前语种译文自带的源文本；没挂译文时是 `null` |
| `translationStale` | `translatedText !== null && sourceText !== text`，即「原文已改，译文待更新」 |

`translationStale` 按父任务 §6.1 的口径写：`sourceText` 为 `null` 而译文存在时也算过期——
比不了就当对不上，不猜「应该没变」。

### 2.3 `buildCues` 配对

1. 有译文时先按 `id` 配对：长度相等、每个原文 id 都能在译文里找到、且时间戳逐条一致。
2. 对不上再退回老办法：长度相等且同下标时间戳一致（旧数据兜底）。
3. 两种都失败 → `mismatch = true`，整份译文不挂（保持现状）。

### 2.4 纠错契约类型

`types/subtitle.ts` 加 `SubtitleCorrection`（字段名与后端 `SubtitleCorrectionResponse` 逐字对齐）、
`SubtitleCorrectionListResponse`、`SaveCorrectionRequest`、`ClearStaleCorrectionsResponse`。
`enums/subtitle.ts` 加 `CORRECTION_FIELD = { TEXT: 'text', TRANSLATION: 'translation' }`——
它在保存请求、列表过滤、编辑态三处参与比较，按项目口径放 `enums/`。

## 3. 请求层

- `api/request.ts` 门面补 `put` / `delete`，签名与现有 `get` / `post` 同款（含唯一受控断言）。
- `api/tasks.ts` 加四个端点（tasks 子资源，不另开域文件）：
  - `listCorrections(taskNo)` → `GET /tasks/{taskNo}/corrections`
  - `saveCorrection(taskNo, segmentId, body)` → `PUT /tasks/{taskNo}/corrections/{segmentId}`
  - `deleteCorrection(taskNo, segmentId, field, lang)` → `DELETE ...?field=&lang=`（204，无响应体）
  - `clearStaleCorrections(taskNo)` → `DELETE /tasks/{taskNo}/corrections?staleOnly=true`
- `TranslationRequest` 加 `force?: boolean`。

## 4. store

### 4.1 `workspaceSubtitles`（字幕域）

新增：

- state `corrections: SubtitleCorrection[]`
- computed `staleCorrectionCount`
- `loadCorrections(taskNo)`：拉列表；失败保留旧值（切任务时 `reset()` 已清空）
- `hasCorrection(segmentId, field, lang)`：用 computed 的 Set 查，供行组件判断要不要给恢复入口
- `saveCorrection(taskNo, input): Promise<string | null>`：成功时
  ① 按段 id 改写内存里 `cleanedTrack` / 当前语种的 `translatedTrack`；
  ② 把返回的纠错 upsert 进列表（保持首次保存顺序）。
  失败返回后端文案，不抛异常（与 `load()` 同款错误口径）。
- `deleteCorrection(taskNo, input): Promise<string | null>`：只发请求，不动内存——恢复要重新拉
  产物才能拿到机器原文/机器译文。
- `clearStaleCorrections(taskNo): Promise<string | null>`：成功后本地剔掉 `matched=false` 的条目。

### 4.2 `workspace`（编排）

- `loadAll` 里字幕加载成功后调 `subtitles.loadCorrections(current)`；任何 run 收口都会走
  `refresh()` → `loadAll`，所以重新生成后失效标记自动刷新。
- `reloadSubtitles()`：按现有产物清单重拉 CLEANED、当前语种译文与纠错列表，**不碰关键词**
  （恢复纠错后调它，避免把用户还没提交的关键词编辑冲掉）。
- `revertCorrection(segmentId, field, lang)`：先 `deleteCorrection`，成功再 `reloadSubtitles()`。
- `translate()`：`force = cues.some(c => c.translationStale)`，随请求发给后端。

## 5. 组件

### 5.1 `SubtitleCueRow.vue`（新增）

一条字幕行自己管编辑态，`TranscriptPane` 只负责搜索、选中、进度与顶部提示。

- props：`cue`、`selected`、`busy`、`language`（当前译文语种，没有译文时 `null`）
- emit：`select`
- 铅笔：原文行、译文行各一个；`busy` 时不渲染；译文铅笔只在 `translatedText !== null` 时渲染；
  默认 `opacity-0`，行 hover 或键盘聚焦时显示。
- 编辑态：textarea 替换 `<p>`，自动聚焦、光标放到末尾；下方一排「保存 / 取消 / 恢复原文（译文）」，
  以及 `Esc 取消 · Ctrl/⌘+Enter 保存` 的提示。
- 保存时机：失焦、点保存、Ctrl/⌘+Enter。保存按钮用 `@mousedown.prevent`，避免先失焦再点击写两次。
- 幂等：trim 后与当前文本相同 → 直接退出，不发请求；`saving` 期间忽略再次提交。
- 失败：把草稿还原成编辑前文本，在行内显示后端文案，编辑态保留（改完可直接重试）。
- 空内容：trim 为空视为非法输入，还原并提示「内容不能为空」，不发请求。
- Esc / 回车处理先看 `event.isComposing`（中文输入法选词时不误触发）。
- `busy` 变成 true 时（另一个 run 起来了）自动放弃编辑并还原。
- 恢复入口：仅当 `hasCorrection` 为真时出现；点了先退出编辑态，调 `revertCorrection`，
  失败把文案显示在行内。

### 5.2 `TranscriptPane.vue`（改造）

- 列表改渲染 `SubtitleCueRow`（`:key` 仍用后端段落下标，它本来就是稳定的业务键）。
- 顶部提示条增加「N 条纠错已失效」+「清除」；清除失败在条内显示错误，不清掉条数。
- 把 `translationStale` 汇总成 `retranslate` 传给 `TranslationBar`。

### 5.3 `TranslationBar.vue`（小改）

- 新增 `retranslate?: boolean`：为真时按钮文案与 title 用「重新翻译」，否则仍是「翻译」。
- 不新增按钮，force 由 store 的 `translate()` 统一决定。

## 6. 失败与边界

| 情形 | 表现 |
|---|---|
| 保存时 run 已启动（后端 409 `{taskNo, runNo}`） | 行内显示「该任务已有一次执行正在进行」；铅笔平时已隐藏，属兜底 |
| segment 已过期（后端 409 `CorrectionSegmentOutdatedException`） | 行内显示后端文案「字幕条目已过期，请刷新后重试」 |
| 译文版本比原文旧 | 行内显示后端文案「重新翻译后再改」一类的提示 |
| 纠错列表拉取失败 | 当空清单，不提示；下次加载再试 |
| 恢复后重新加载失败 | 行内显示错误，页面保持原样 |
| 旧产物没有 id | `buildSegmentId` 现算，保存、配对都正常 |

## 7. 不做什么

- 不做纠错历史 / 逐次撤销。
- 不做「这一行被改过」角标（只做过期与失效两种显式状态）。
- 不做单条重翻、不做删除字幕条目。
- 不改搜索逻辑：搜索仍然同时在原文与译文里找。
- 不在前端重新叠一遍纠错列表。
