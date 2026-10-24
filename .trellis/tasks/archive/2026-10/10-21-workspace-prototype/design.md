# 设计：工作区页原型

## 1. 起点

`prototype/` 是一个**独立可跑的原型包**（Vue 3.5 + TS + Vite 8 + Tailwind 4，与 `frontend/` 同栈），
已被 `/prototype/` gitignore。现有内容：

> 技术栈不是约束：这是现有可复用脚手架。原型可以选择任何更快的实现方式，不要求遵循正式前端规范；后续若换成静态 HTML/CSS/JS 等方案，只需保持信息架构、交互与 mock 契约形状。

| 文件 | 行数 | 现状 |
|---|---|---|
| `src/App.vue` | 14 | 上传页 ↔ 工作区页二选一，`source` 为 `null` 时是上传页 |
| `src/views/UploadView.vue` | 261 | 上传态（**本任务不动**） |
| `src/views/WorkspaceView.vue` | 84 | **旧设计**：顶栏 + `MediaPane` + `TranscriptPane` 左右分栏 |
| `src/components/MediaPane.vue` | 136 | 视频黑场 / 音频卡双形态 + 波形 |
| `src/components/TranscriptPane.vue` | 128 | 标题 + 计数、搜索、时间码 + 文本列表、seek、自动滚动 |
| `src/composables/useTranscriptPlayer.ts` | 100 | 单例播放器（秒口径），媒体元素与字幕列表共享 |
| `src/data/transcript.ts` | 60 | `DEMO_CUES`（**秒口径**，`{id, start, end, text}`）+ `DEMO_SOURCES` |
| `public/demo/` | — | `lecture.mp4` / `podcast.mp3`（ffmpeg 本地生成，离线可跑） |

**新布局要改的是 `WorkspaceView.vue` 与两个 pane，并新增关键词 / 主题两个 pane。**

## 2. 新布局

```
┌──────────────────────────────────────────────────────────────────────┐
│ WorkspaceHeader（不动：返回 / 标题 / 翻译入口 / 产物面板）              │
├──────────────────────────────────┬───────────────────────────────────┤
│ 左：视频区（flex-col，竖分三块）    │ 右：字幕区（维持原样）              │
│ ┌──────────────────────────────┐ │ ┌───────────────────────────────┐ │
│ │ ① 播放器  固定 16:9           │ │ │ 标题 + 计数                     │ │
│ │    视频：黑场 + object-contain │ │ │ 搜索框                        │ │
│ │    音频：封面卡 + 波形 + 播控   │ │ ├───────────────────────────────┤ │
│ ├──────────────────────────────┤ │ │ 时间码 + 原文 + 译文 列表       │ │
│ │ ② 关键词区  自然高度           │ │ │ 行 hover：右上镂空修改图标 /     │ │
│ │    垂直列表   [重新生成]        │ │ │ 自动滚动跟随                   │ │
│ ├──────────────────────────────┤ │ │                               │ │
│ │ ③ 主题区  shrink-0，max-h     │ │ │                               │ │
│ │    一两句话主题（可含术语表）    │ │ │                               │ │
│ └──────────────────────────────┘ │ └───────────────────────────────┘ │
└──────────────────────────────────┴───────────────────────────────────┘
```

### 2.1 高度分配（本设计的核心取舍）

左列是"一个主块 + 两个信息块"，**不能让三块等分**——视频被压到 1/3 高度就没法看了。首版允许内容超出视口，取：

- **① 播放器**：使用固定 16:9 比例（`aspect-video shrink-0`），不再用 `flex-1` 吃剩余高度；新增关键词不能挤压播放器。视频 `object-contain` 不裁切；音频卡在其内居中。
- **② 关键词区**：区块按内容自然排布，但列表本体 `max-h-60 overflow-y-auto`；关键词多时只滚动列表，不把主题区无限往下推。
- **③ 主题区**：`shrink-0`，按内容自然撑高。
- **滚动策略**：左列仍保留整体滚动兜底；关键词列表是唯一允许的内部滚动区，并统一使用悬浮自动隐藏滚动条。
- **滚动条外观**：桌面左右两个滚动区域使用同一套悬浮自动隐藏样式。默认轨道和滑块透明；悬停、键盘聚焦或滚动对应区域时，仅显示当前区域的 6px 半透明细滑块，避免两条常驻原生滚动条制造视觉噪音。
- **顶栏入口**：工作区返回入口文案为「返回」。

> 这三个比例是**起点不是结论**——原型跑起来后按实际观感调。AC2 要的就是"不塌"。

### 2.2 分栏比例

左侧视频区与右侧字幕区按 **3:2** 分栏：`lg:basis-[60%]` / `lg:w-[40%] lg:flex-none`，`lg` 以下上下堆叠。
字号、搜索框和字幕行样式维持原样，只调整空间占比。

## 3. 假数据形状（镜像真实契约）

**这是本设计最要紧的一节**：mock 必须与后端实际产出**同形**，否则原型"看着对"是假的。
全部 mock 集中在一个模块（`src/data/workspace.ts`），字段名与后端 JSON 逐字对齐。

### 3.1 时间口径改为毫秒

现有 `DEMO_CUES` 是**秒**口径。真实契约是**毫秒**（`TranscriptionSegment.beginTimeMs`）。
mock 改成毫秒，边界处（`seek`）再折成秒——与 `frontend/` 的做法一致（`cue.startMs / 1000`）。
`useTranscriptPlayer` 保持秒口径不变（那是播放关注点，不是契约）。

### 3.2 各 mock 的形状

```ts
// GET /api/tasks/{taskNo}  →  TaskResponse
{ taskNo, inputType: 'URL'|'UPLOAD', sourceUri, createdAt,
  lastRun: { runNo, runType: 'MAIN'|'RECLEAN'|'TRANSLATE', targetLanguage,
             status: 'RUNNING'|'SUCCEEDED'|'FAILED'|'INTERRUPTED',
             currentStage, errorMessage, startedAt, finishedAt } | null }

// GET /api/tasks/{taskNo}/keywords  →  SubtitleKeywordsResponse
{ keywords: string[], fingerprint: string }

// GET /api/tasks/{taskNo}/artifacts  →  ArtifactResponse[]
[ { type: 'CLEANED', lang: '', revision: 1, keywordsFingerprint: string,
    files: { segments: { url, sizeBytes }, subtitles: {...}, context: {...} } } ]

// GET .../artifacts/CLEANED/context.json  →  SubtitleContext
{ theme: string, sourceLanguage: string,
  terms: [ { asr, fixed, note } ], degraded: boolean }

// CLEANED/segments.json  →  SubtitleTrack
{ language: string | null,
  segments: [ { beginTimeMs, endTimeMs, text } ],
  keywordsFingerprint: string | null }

// GET /api/languages  →  LanguageOption[]
[ { code: 'zh-Hans'|'zh-Hant'|'en'|'ja', label: string } ]

// TRANSLATED/segments.json  →  TranslatedTrack
{ sourceLanguage, targetLanguage, skipped,
  segments: [ { beginTimeMs, endTimeMs, sourceText, translatedText } ] }
```

字幕列表由 `segments` 两份**按下标配对**构建（后端保证逐条时间戳一致）。

### 3.3 指纹要真的"能比"

`SubtitleKeywordsResponse.fingerprint` 与 `ArtifactResponse.keywordsFingerprint` 在 mock 里给**不同值**，
这两个字段仍按真实契约保留；首版不展示“字幕与当前关键词不一致”的提示。

### 3.4 覆盖的状态

| 状态 | 怎么造 |
|---|---|
| 就绪（正常） | 默认：有原文、无译文，等待用户翻译 |
| 就绪 + 无译文 | 默认态；`?noTranslation=1` 仅保留为兼容入口 |
| 就绪 + 关键词为空 | `keywords: []` |
| 就绪 + 主题降级 | `context.degraded: true` + `theme: ""` |
| 就绪 + 待翻译 | 有原文、无译文；翻译控件可操作 |
| 翻译中 | 本地计时器模拟进度，控件禁用 |
| 翻译完成 | 双语字幕切到所选目标语种 |
首轮覆盖这些就绪态子形态。`?emptyKeywords=1`、`?degraded=1` 通过 URL 参数切换；默认本身即为无译文态，`?noTranslation=1` 仅保留兼容。不把调试控件放进正式页面。SSE / 进度 / 失败态在第一轮验收通过后再追加。

## 4. 组件划分

保持 `prototype/src/components/` 现有的**扁平**风格，不另起子目录（原型阶段不值得）。

| 组件 | 动作 |
|---|---|
| `components/MediaPane.vue` | 改造：去掉外层 padding，适配"占满 ① 块"的定位 |
| `components/KeywordPane.vue` | **新增**：输入框 + 限高可滚动垂直列表 + 增删 + 标题行「重新生成」 |
| `components/ThemePane.vue` | **新增**：只显示 `theme` + `degraded` 空态，不显示 `terms` |
| `components/TranslationBar.vue` | **新增**：源 / 目标语种双 select、互换、翻译按钮、进度与完成态 |
| `components/TranscriptPane.vue` | 改造：接入翻译控件；保留字幕行 hover 修改图标 + 单条行级编辑态 |
| `views/WorkspaceView.vue` | 重写：编排左右分栏与左列三块 |

**命名尽量贴近将来要重写进 `frontend/` 的形状**（`frontend/` 用 `views/workspace/components/`），
减少 B 的翻译成本。

## 5. 交互细节

### 5.1 关键词区

- 区块标题行右侧放「重新生成」按钮；其下先放输入框，再放已有词列表。输入框不能被已有列表挤到下方。
- 输入框 + 回车 / 加号按钮追加；已有词以**垂直列表**呈现在输入框下方，按后端返回顺序（用户填写顺序）。
- 每行一个关键词，删除按钮放行尾；长词允许换行，不省略到无法辨认。
- 关键词列表设置固定高度上限（如 `max-h-60`）并使用 `overflow-y-auto`，触发时沿用悬浮自动隐藏滚动条。
- 上限对齐后端：**单条 ≤ 50 字、最多 50 条**。原型里也要挡（否则将来会照抄一个没挡的版本）。
- 「重新生成」**语义**：提交当前完整清单（含空清单 → 对应后端 `[]` = 清空重跑）。
  空清单时按钮不禁用，但文案改为「清空并重新生成」，避免用户以为点了没反应。
- 列表为空时，输入框仍保留，下方显示“还没有关键词，输入后按回车添加”。
- 首版不显示关键词指纹不一致提示。

### 5.2 主题区

- 显示 `context.theme`（一两句话）。
- 关键词区与主题区不使用独立背景色，也暂不显示区块级分割线；通过留白和标题层级区分。
- 首版直接使用当前后端形状，不预留长摘要；后续扩展时只替换内容，不改变本区基本信息层级。
- `degraded: true` 或 `theme === ''` → 显示"未能提取主题"文案，不是空白。
- 不显示 `context.terms`：该字段是 LLM 抽取术语，与用户关键词不是一回事，主题区保持单一信息层级。

### 5.3 字幕区「修改字幕」

- 这是**人工修正字幕**的入口，不是整篇字幕的全局编辑模式。
- 每条字幕行在鼠标 hover 或键盘 focus-within 时，**右上角**显示修改（铅笔）图标；图标为无底色、无边框、无阴影的镂空样式，绝对定位，不能挤动正文排版。
- 点击图标后只把**该条原文**切换为可编辑 `textarea`（自适应高度），时间码与译文保持只读；点击图标不能误触发行级 seek。
- 字幕行容器不能嵌套 `<button>`；外层可用可聚焦容器承载 seek，内部单独放修改按钮。
- 该条编辑态提供「保存 / 取消」。保存后先更新原型内存数据，并明确提示"后端接口未实现，仅本地演示"；取消恢复原值。
- **不假装持久化、不静默丢弃修改。**

### 5.4 翻译控件

字幕区头部采用主流翻译器的语言选择形态，放在标题 / 计数与搜索框之间：

```
字幕稿  13 条                         已翻译为英语
[ 简体中文 ▾ ]  [⇄]  [ 英语 ▾ ]  [ 翻译 ]
[ 搜索字幕内容…                          ]
```

- 源语言、目标语言是两个同等权重的原生 `select`，共用 `GET /api/languages` 的 mock 清单；不另造语言名映射。
- 默认源语言取 `context.sourceLanguage`；目标语言默认英语。进入就绪页时 `translatedLanguage = null`，默认不展示任何译文。
- 中间互换按钮只交换两个 select 的值；不立即替换当前译文，译文只在下一次翻译成功后切换。
- 源语言与目标语言相同时，翻译按钮禁用，并在控件下方显示“源语言与目标语言相同，请选择其他目标语种”。
- 点击翻译后进入本地模拟态：翻译按钮显示百分比，控件禁用；约 2 秒后完成，展示所选目标语种的双语字幕。
- 翻译完成提示必须标注“本地演示，未发起真实翻译请求”，不能假装调用后端。
- 重新生成字幕时译文失效：隐藏旧译文、保留语言选择、提示需要重新翻译；翻译按钮恢复可用。
- 主任务 / 重新生成进行中不渲染可操作的翻译控件，避免与字幕生成过程冲突。
- 宽度不足时允许语言栏自然压缩，但两个 `select` 必须保持可读；不允许横向溢出或把翻译按钮挤成图标。

### 5.5 SSE / 非就绪态（下一轮）

首轮固定展示清洗结束的就绪态，不实现状态切换器。第一轮验收通过后，再补 SSE / 进度 / 失败态原型。

## 6. 开放问题（默认值待你确认）

| # | 问题 | 我的默认 |
|---|---|---|

## 7. 不涉及

SSE、真实请求、`frontend/` 的任何改动、契约错位的修复——全部属于子任务 B。
