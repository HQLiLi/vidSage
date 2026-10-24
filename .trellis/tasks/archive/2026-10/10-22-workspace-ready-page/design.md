# 设计：工作区就绪页重写（契约层 + 已完成任务页面）

> 任务：`10-22-workspace-ready-page`（代号 B1）
> 父任务：`10-21-workspace-fe-rewrite`；兄弟任务：`10-22-workspace-run-page`（B2）
> 原型：`10-21-workspace-prototype`（已验证，`prototype/` 被 gitignore）
> 本文只写设计，不写实现代码。所有契约都对着 `backend/` 当前源码核过，行号见文末。

---

## 1. 这个任务要交付什么

一句话：**把 `frontend/` 的契约层整体换到当前后端，并按原型把「任务已完成」的工作区页重建出来。**

交付后能做的事：

- 打开一个已经跑完的任务：看到真实字幕、真实关键词、真实主题（数据全部来自真实接口）。
- 搜索字幕、选中某条、逐条改原文（保存后明确告诉用户「只是本地演示」）。
- 从顶栏「产物」下拉下载真实存在的产物文件。
- 从首页提交 URL / 上传文件，跳到后端返回的真 `taskNo`；409 跳到既有任务。

**不做**（B2 的范围）：SSE 订阅、实时进度、「重新生成」按钮、翻译控件与译文显示、顶部错误条与断线重试。B1 交付后的中间态是：能看已完成任务、能提交并跳走，进行中的任务只显示「执行中，请稍后刷新」。

---

## 2. 起点：现在是什么样，哪些要换掉

前端自 2026-10-13 起没动过，后端字幕模型重构把契约整层换了。现在对任何任务都跑不通，而且 `MediaPane` 拼的 `/api/tasks/{taskNo}/artifacts/source.mp4`（`frontend/src/views/workspace/components/MediaPane/index.vue:38`）是死链——产物端点已经是闭集（`ORIGINAL` / `CLEANED` / `TRANSLATED`，文件位只有四个）。

### 2.1 旧物与新物的对照

| 旧（现状） | 新（本任务） | 为什么要换 |
|---|---|---|
| `types/task.ts:34-65` 扁平 `TaskView` + `TaskConflictResponse` + `TaskArtifact` | `TaskResponse` / `RunResponse` / `TaskRunRef` + 三个请求体 | 后端 `TaskResponse.java:25-30` 把状态、阶段、错误、起止时间全收进 `lastRun`；产物清单也从任务视图里删掉了 |
| `types/transcript.ts` 的 `TranscriptCue` | `types/subtitle.ts` 的 `SubtitleCue` | 后端段落是毫秒口径的 `beginTimeMs/endTimeMs`，且要能按下标配对译文 |
| `enums/task.ts` 的 `TASK_STATUS` / `TRANSLATION_STATUS` | `TASK_RUN_TYPE` / `TASK_RUN_STATUS` | 后端只有 run 概念，没有独立的翻译子状态（`TaskRunStatus.java` 四值） |
| `api/tasks.ts` 六个函数返回 `TaskView` | 七个端点，触发类返回 `TaskRunRef` | 202 / 409 都是 `{taskNo, runNo}`（`TaskRunRefResponse.java:9-18`） |
| `api/request.ts:61` `ApiError.existingTask` | `ApiError.runRef` | 409 响应体就是 ref，没有 `error` 字段 |
| `api/tasks.ts:53-55` `fetchArtifactJson(taskNo, name)` | `api/artifacts.ts` 的 `fetchArtifactJson(url)` | 产物 URL 是全路径 `/api/...`，且由清单给出，前端不再自己拼 |
| `api/media.ts` + `types/media.ts` | 删掉 | 「本就无消费方」（PRD R4）；后端 `MediaController` 还在，但前端没人用 |
| `stores/task.ts` + `composables/useTaskEvents.ts` | `stores/workspace.ts`（B1 不含 SSE） | 旧 SSE 路径 `/api/tasks/{taskNo}/events`（`useTaskEvents.ts:57`）已不存在 |
| `composables/useTranscriptPlayer.ts` | 删掉 | 后端没有媒体端点，没有 `<video>` 可挂，播放器、seek、当前句高亮都失去时间源 |
| `views/workspace/components/MediaPane/`（含 `AudioWaveform.vue`） | 扁平 `MediaPane.vue` | 双形态（视频/音频）依赖可播媒体；B1 只有 16:9 占位，没有子组件，按 `directory-structure.md:59` 的「复合组件才下沉」规则回到扁平文件 |
| `WorkspaceHeader.vue` / `TranscriptPane.vue` / `views/workspace/index.vue` | 重写 | 布局从「左媒体 + 右字幕」改为「左列三块 + 右字幕」 |

### 2.2 删除清单（B1 执行）

- `frontend/src/composables/useTaskEvents.ts`
- `frontend/src/stores/task.ts`
- `frontend/src/types/transcript.ts`
- `frontend/src/composables/useTranscriptPlayer.ts`
- `frontend/src/views/workspace/components/MediaPane/`（整个目录，含 `components/AudioWaveform.vue`）
- `frontend/src/api/media.ts`
- `frontend/src/types/media.ts`

删之前确认：`grep -rn "useTaskEvents\|useTranscriptPlayer\|TranscriptCue\|api/media\|types/media" frontend/src` 的结果只剩这些文件自己。

### 2.3 新增 / 重写清单

新增：

- `frontend/src/types/subtitle.ts`
- `frontend/src/api/artifacts.ts`、`frontend/src/api/languages.ts`
- `frontend/src/utils/artifacts.ts`、`frontend/src/utils/language.ts`、`frontend/src/utils/progress.ts`
- `frontend/src/enums/subtitle.ts`（`ARTIFACT_TYPE` 运行时值）
- `frontend/src/views/workspace/components/ArtifactMenu.vue`
- `frontend/src/views/workspace/components/KeywordPane.vue`
- `frontend/src/views/workspace/components/ThemePane.vue`
- `frontend/src/views/workspace/components/StatusCard.vue`

重写（保留路径，内容全换）：

- `frontend/src/enums/task.ts`、`frontend/src/types/task.ts`
- `frontend/src/api/request.ts`、`frontend/src/api/tasks.ts`
- `frontend/src/utils/transcript.ts`
- `frontend/src/stores/workspace.ts`（新路径，`stores/task.ts` 改名而来）
- `frontend/src/views/workspace/index.vue`
- `frontend/src/views/workspace/components/{WorkspaceHeader,MediaPane,TranscriptPane}.vue`
- `frontend/src/views/upload/index.vue`（只改提交接线，布局不动）
- `frontend/src/assets/main.scss`（补悬浮滚动条样式，见 §8.6）

---

## 3. 契约事实（已对源码核对）

### 3.1 端点

| 能力 | 端点 | 返回 | 源码 |
|---|---|---|---|
| 提交 URL | `POST /api/tasks` | 202 `{taskNo, runNo}` | `TaskController.java:78-87` |
| 上传文件 | `POST /api/tasks/upload`，multipart 字段 `file`，可选重复 `keywords` | 202 `{taskNo, runNo}` | `TaskController.java:105-119` |
| 任务快照 | `GET /api/tasks/{taskNo}` | `TaskResponse`（状态在 `lastRun`） | `TaskController.java:131-138` |
| SSE（B2） | `GET /api/tasks/{taskNo}/runs/{runNo}/events` | 流；终态 run 返回 204 | `TaskController.java:156-169` |
| 翻译（B2） | `POST /api/tasks/{taskNo}/translate` | 202 `{taskNo, runNo}` | `TaskController.java:184-194` |
| 重生成（B2） | `POST /api/tasks/{taskNo}/subtitles/regenerate` | 202 `{taskNo, runNo}` | `TaskController.java:213-223` |
| 关键词 | `GET /api/tasks/{taskNo}/keywords` | `{keywords, fingerprint}` | `TaskController.java:234-240` |
| 产物清单 | `GET /api/tasks/{taskNo}/artifacts` | `ArtifactResponse[]` | `ArtifactController.java:61-69` |
| 产物下载 | `GET /api/tasks/{taskNo}/artifacts/{type}/{file}?lang=` | 文件字节 | `ArtifactController.java:89-108` |
| 语种清单 | `GET /api/languages` | `[{code, label}]` | `LanguageController.java:38-41` |

错误体分两类：

- `400/404/500` 是 `{"error": "中文说明"}`（`ApiExceptionHandler`）。
- `409` 是 `{"taskNo": "...", "runNo": 1}`，**和 202 同形、没有 error 字段**（`TaskRunRefResponse.java:9-18`）。

### 3.2 产物形状

- `ArtifactResponse.files` 的键是**短键**：`segments` / `subtitles` / `context` / `terms`（`ArtifactFileKind.java:20-29`），不是文件名。
- 值是 `{url, sizeBytes}`，`url` 是 `/api/tasks/...` 全路径（`ArtifactResponse.java:56-60`）。
- `lang`：只有 TRANSLATED 有值，其余是**空串** `""`，不是 null（`ArtifactResponse.java:24-32`）。
- `keywordsFingerprint`：只有 CLEANED 有值，其余 null。
- `CLEANED/segments.json` = `{language, segments, keywordsFingerprint}`，是**信封不是裸数组**（`SubtitleTrack.java:20-22`）；`language` 可能为 null。
- `CLEANED/context.json` = `{theme, sourceLanguage, terms, degraded}`；构造器把 null 收敛成空串/空表（`SubtitleContext.java:50-54`）。
- `TRANSLATED/segments.json` = `{sourceLanguage, targetLanguage, skipped, segments}`（`TranslatedTrack.java:22-23`）。
- 时间戳是 `beginTimeMs` / `endTimeMs`，毫秒（`TranscriptionSegment.java:10`）。

### 3.3 五个必须记住的坑

1. **产物 url 全路径**：实例 baseURL 已是 `/api`，`request.get('/api/tasks/...')` 会拼成 `/api/api/tasks/...`。必须走新增的 `getAbsolute`。
2. **`context.sourceLanguage` 是 LLM 写的自由文本**：`SubtitleContext.java:19` 注释说「BCP-47 短码，拿不准时为空串」，实测可能是 `zh` / `中文` / `zh-CN`。它是给翻译侧做跳过判断的，不是给前端下拉用的白名单码，所以初始源语种必须归一（`utils/language.ts`）。
3. **`noUncheckedIndexedAccess`**（`type-safety.md:17`）：`artifact.files[key]` 的类型是 `ArtifactFileResponse | undefined`，取 url 必须判空。
4. **`type-check` 是假绿**：它只证明类型自洽，不证明接口对得上。验收必须把前后端跑起来看。
5. **后端没有媒体端点**：播放器只能占位，不造假播放器、不留死链。

---

## 4. 契约层：类型与 API 门面

### 4.1 `enums/task.ts`（重写）

```ts
export const TASK_RUN_TYPE = { MAIN: 'MAIN', RECLEAN: 'RECLEAN', TRANSLATE: 'TRANSLATE' } as const
export const TASK_RUN_STATUS = {
  RUNNING: 'RUNNING', SUCCEEDED: 'SUCCEEDED', FAILED: 'FAILED', INTERRUPTED: 'INTERRUPTED',
} as const
```

理由：`TASK_RUN_STATUS` 在 `phase` 判定、上传页跳转、StatusCard 里都要比较，按 `type-safety.md:92` 用命名常量而不是裸字面量；`as const` 不用 TS `enum`。

`ArtifactType` 同样有比较消费（`utils/artifacts.ts` 按 type 取产物、`ArtifactMenu.vue` 按 type 分组），
按 `type-safety.md:92` 走一样的路子——运行时值放 `enums/subtitle.ts`，类型联合在 `types/subtitle.ts` 派生：

```ts
// enums/subtitle.ts
export const ARTIFACT_TYPE = {
  ORIGINAL: 'ORIGINAL',
  CLEANED: 'CLEANED',
  TRANSLATED: 'TRANSLATED',
} as const
```

后端的 `ArtifactType` 在字幕域（`domain/subtitle/model/ArtifactType.java`），
所以前端也按域分文件，不塞进 `enums/task.ts`。

### 4.2 `types/task.ts`（重写）

```ts
import type { TASK_RUN_STATUS, TASK_RUN_TYPE } from '@/enums/task'

export type TaskInputType = 'URL' | 'UPLOAD'
export type TaskRunType = (typeof TASK_RUN_TYPE)[keyof typeof TASK_RUN_TYPE]
export type TaskRunStatus = (typeof TASK_RUN_STATUS)[keyof typeof TASK_RUN_STATUS]

export interface RunResponse {
  runNo: number
  runType: TaskRunType
  targetLanguage: string | null
  status: TaskRunStatus
  currentStage: string | null
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
}

export interface TaskResponse {
  taskNo: string
  inputType: TaskInputType
  sourceUri: string
  createdAt: string
  lastRun: RunResponse | null
}

export interface TaskRunRef { taskNo: string; runNo: number }
export interface SubmitTaskRequest { url: string; keywords?: string[] }
export interface TranslationRequest { sourceLanguage: string; targetLanguage: string }
export interface RegenerateSubtitlesRequest { keywords: string[] }
```

- `TaskInputType` 保持裸联合：B1 里没有比较消费（播放器已经是纯占位，不再按扩展名分音频/视频）。
- `startedAt` 非空、`finishedAt` 可空，照 `RunResponse.java:22-32` 逐字段镜像。
- `RegenerateSubtitlesRequest.keywords` 写成必填数组：只有 B2 用它，而 B2 的规则是「总是发数组，绝不发 null」（B2 PRD R3）。后端允许 null 的语义留在注释里。
- 同一文件里还放进度流事件的形状 `StageProgress` / `TaskRunProgress`（见 §5.4）：
  B1 的 `utils/progress.ts` 要用它换算百分比，B2 订阅时也直接用——放一处免得两份定义漂移。

### 4.3 `types/subtitle.ts`（新增）

形状严格按共享契约：`ArtifactType` / `ArtifactFileResponse` / `ArtifactResponse` / `TranscriptionSegment` / `SubtitleTrack` / `SubtitleTerm` / `SubtitleContext` / `TranslatedSegment` / `TranslatedTrack` / `LanguageOption` / `SubtitleKeywordsResponse` / `SubtitleCue`。

`ArtifactType` 本身不在这份文件里定义，它从 `enums/subtitle.ts` 派生（见 §4.1）：

```ts
import type { ARTIFACT_TYPE } from '@/enums/subtitle'

export type ArtifactType = (typeof ARTIFACT_TYPE)[keyof typeof ARTIFACT_TYPE]
```

两处要写清：

- `ArtifactResponse.files: Record<string, ArtifactFileResponse>`，注释写明「键是 segments / subtitles / context / terms 短键」。
- `SubtitleCue.index` 是**后端段落下标**（配对键），不是渲染位置；后续做编辑时也按它定位。

> **已解决**：`type-safety.md:92` 要求「有比较消费的契约枚举进 `enums/`」。`ArtifactType` 在 `utils/artifacts.ts` 与 `ArtifactMenu.vue` 里会被比较，所以按规范落到 `enums/subtitle.ts` 的 `ARTIFACT_TYPE`，`types/subtitle.ts` 用 `typeof` 派生（见 §4.1）。B2 用同一份路径。

### 4.4 `api/request.ts`（重写门面，实例保留）

保留：唯一实例、baseURL 来自 `VITE_API_BASE_URL`、成功解包、错误归一化、门面边界唯一 `as Promise<T>`（`type-safety.md:95`）。

改三处：

```ts
export class ApiError extends Error {
  readonly status: number | null
  readonly runRef: TaskRunRef | null
  constructor(message: string, status: number | null = null, runRef: TaskRunRef | null = null)
}

export const request = {
  get: <T>(url: string, config?: AxiosRequestConfig): Promise<T> => ...,
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> => ...,
  getAbsolute: <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    instance.get<T, T>(url, { ...config, baseURL: '' }) as Promise<T>,
}
```

1. `existingTask` → `runRef`，判定函数从「含 existingTask 的 TaskConflictResponse」改成「`typeof taskNo === 'string' && typeof runNo === 'number'`」。
2. 409 没有 `error` 字段，归一化时补一句固定文案：`new ApiError('该任务已有一次执行正在进行', 409, ref)`；不是 ref 形状的 409 退回 `请求失败（HTTP 409）`。
3. `getAbsolute` 用**逐请求覆写 `baseURL: ''`**，而不是 `url.slice('/api'.length)` 或 `new URL(...).toString()`。理由：仍是同一个实例（符合 `api.md:10` 的单实例要求），不依赖窗口环境，也不猜 baseURL 的字面前缀。备选：把 `/api` 前缀从 url 里剥掉——一旦 `VITE_API_BASE_URL` 改成别的值就悄悄拼错。

### 4.5 `api/tasks.ts`（重写）

```ts
export async function submitUrl(req: SubmitTaskRequest): Promise<TaskRunRef>
export async function submitUpload(
  file: File,
  keywords?: string[],
  onUploadProgress?: (percent: number) => void,
): Promise<TaskRunRef>
export async function getTask(taskNo: string): Promise<TaskResponse>
export async function getTaskKeywords(taskNo: string): Promise<SubtitleKeywordsResponse>
export async function translateTask(taskNo: string, req: TranslationRequest): Promise<TaskRunRef>
export async function regenerateSubtitles(taskNo: string, req: RegenerateSubtitlesRequest): Promise<TaskRunRef>
```

- `submitUpload` 的 `keywords` **放进 FormData**（每个词一个同名字段），对应后端 `@RequestParam("keywords")` 读同名多值字段。
  初稿曾想走查询参数（`config.params = { keywords }`）——**那是错的**：axios 1.x 默认把数组序列化成
  `keywords[]=a&keywords[]=b`，后端按确切名字 `keywords` 取参，绑不上，词会被静默丢掉；
  传空数组还会产出空 query，与「不传」完全同形。走 FormData 没有这个问题。
- 保留 `timeout: 0` 与上传百分比换算（大文件不能被 15s 全局超时误伤）。
- 旧的 `translate` 改名为 `translateTask`（与 `getTask` 同前缀，避免和 store action `translate` 撞名）。

### 4.6 `api/artifacts.ts`、`api/languages.ts`（新增）

```ts
export async function listArtifacts(taskNo: string): Promise<ArtifactResponse[]>
export async function fetchArtifactJson<T>(url: string): Promise<T>   // 内部走 request.getAbsolute

export async function getLanguages(): Promise<LanguageOption[]>
```

一域一文件、不建 barrel（`api.md:40`）。`fetchArtifactJson` 收 url 而不是 `(taskNo, name)`，因为清单已经给了完整地址，前端不该再维护一套拼法。

---

## 5. 纯函数层：utils 与 lib

### 5.1 `utils/artifacts.ts`

```ts
export function pickArtifact(
  artifacts: ArtifactResponse[],
  type: ArtifactType,
  lang?: string,
): ArtifactResponse | null

export function artifactFileUrl(artifact: ArtifactResponse | null, key: string): string | null
```

- `pickArtifact`：先按 `type` 过滤；传了 `lang` 再按 `artifact.lang` 大小写不敏感比较（给 B2 按目标语种取译文用）；不传取第一项；没有返回 null。
- `artifactFileUrl`：`artifact?.files[key]?.url ?? null`——判空是 `noUncheckedIndexedAccess` 的硬要求，也顺手把 null artifact 吃掉。

### 5.2 `utils/language.ts`

```ts
export function normalizeLanguageCode(raw: string, options: LanguageOption[]): string | null
```

按顺序尝试，命中即返回 `options` 里的规范 code：

1. 去空白后和 `option.code` 大小写不敏感全等。
2. 和 `option.label` 全等（LLM 可能直接写「简体中文」）。
3. 中文同义词：`中文` / `汉语` / `简体` → 简体；`繁体` / `繁体中文` → 繁体；`英文` → 英语；`日文` → 日语。只在该 code 真的存在于 `options` 时才返回。
4. BCP-47 前缀规则：`zh` 开头看是否含 `hant` / `tw` / `hk` / `mo` → 繁体，否则简体；`en` 开头 → 英语；`ja` 开头 → 日语。
5. 都不中返回 `null`。

**兜底行为（写死在这里，B2 直接用）**：`null` 时 `selectedSourceLanguage = ''`，下拉显示「未识别」占位；翻译按钮由 B2 按「源语种为空」禁用。没有猜一个默认值的分支——猜错比空着更糟。

为什么前端要维护一份别名：后端 `SupportedLanguage.java:17-20` 的白名单是「提交时校验」用的，`context.sourceLanguage` 是 LLM 输出、不保证等于白名单码，这里只是把自由文本映射到同一个可选集合，不复制后端的拒绝逻辑。备选：不加第 3、4 步，只做 1、2——但实测 LLM 常写 `zh`，会直接归零，源语种选择不可用。

### 5.3 `utils/transcript.ts`（重写）

```ts
export interface BuildCuesResult { cues: SubtitleCue[]; mismatch: boolean }

export function buildCues(
  cleaned: SubtitleTrack | null,
  translated: TranslatedTrack | null,
): BuildCuesResult
```

规则：

- `cleaned` 为 null 或 `segments` 为空 → `{ cues: [], mismatch: false }`。空字幕是合法产出（纯音乐），不报错。
- **按下标配对**：第 i 条原文与第 i 条译文配对。两边长度不等，或任一配对的 `beginTimeMs/endTimeMs` 不一致 → `mismatch = true`，并且**整份译文都不挂**（不按条挑），避免用户看到错位的双语。
- `translatedText` 只在「非空且不等于 `sourceText`」时保留，其余写 null（后端 `skipped: true` 时译文就等于原文）。
- `text` 用 `sourceText ?? 原文 text`？不：B1 的原文一律取 CLEANED 的 `segment.text`；译文文件里的 `sourceText` 只用于上面那条相等判断。理由：译文可能落后于最新清洗（后端会作废旧译文），原文必须以 CLEANED 为准。
- `id` 不再造字符串：`SubtitleCue` 带 `index`，v-for 的 key 用它。它是有业务含义的后端段落序号，不是 `v-for` 的渲染下标。

### 5.4 `utils/progress.ts`（B1 只落纯换算）

```ts
// 事件的形状（StageProgress / TaskRunProgress）定义在 types/task.ts，
// 这里直接用，不另立一份——两份定义早晚会漂移。

export const RECLEAN_STAGE_BASE = 70
export const RECLEAN_STAGE_END = 100

export interface RunProgressView {
  percent: number
  message: string
}

export function normalizeRunProgress(
  runType: TaskRunType,
  raw: TaskRunProgress | null,
): RunProgressView
```

B1 落地：常量、纯换算（MAIN / TRANSLATE 原样取 `stage.progress`；RECLEAN 把 70–100 线性映射到 0–100；`raw` / `stage` 为 null 时 0 与空文案）。**不做**：单调保护、重连快照、run 代际校验——那些是有状态的，归 B2。

> **为什么放 `utils/` 不放 `lib/`**：`directory-structure.md:57` 把 `lib/` 定成「框架无关的单例模块」，现在只有 `gsap.ts`（第三方库的全项目唯一入口）；`normalizeRunProgress` 是纯函数加工，按 `directory-structure.md:58` 归 `utils/`。初稿曾按共享契约放在 `lib/`，已改。

---

## 6. 状态层：按域拆三个 store

三个 Pinia Setup Store（`state-management.md:33-50`），都只服务工作区页：

- `stores/workspace.ts`（id `'workspace'`）：任务快照、产物清单、语种与页面状态，外加加载编排。
- `stores/workspaceSubtitles.ts`（id `'workspaceSubtitles'`）：清洗后字幕、上下文、译文与字幕行。
- `stores/workspaceKeywords.ts`（id `'workspaceKeywords'`）：关键词清单与增删，上限常量也在这。

拆分理由：七个组件各自只读一两个字段，B2 还要再塞进一批运行态字段；按域拆开后每个 store
4–6 个字段，B2 的运行态进 `workspace`、翻译态进 `workspaceSubtitles`，不用二次搬家。
代价是 `phase` 要读 `workspaceSubtitles.cleanedTrack`，`reset()` 要扇出到另外两个 store。

### 6.1 state

**`workspace`**

| 字段 | 类型 | 初值 | 说明 |
|---|---|---|---|
| `taskNo` | `string` | `''` | 当前任务 |
| `snapshot` | `TaskResponse \| null` | null | GET 快照 |
| `loadError` | `LoadError \| null` | null | `{kind:'not-found'} \| {kind:'error', message}` |
| `artifacts` | `ArtifactResponse[]` | `[]` | 产物清单 |
| `languages` | `LanguageOption[]` | `[]` | `GET /api/languages` |
| `selectedSourceLanguage` | `string` | `''` | 归一后的规范 code；`''` = 未识别 |
| `selectedTargetLanguage` | `string` | `'en'` | 默认英语（原型口径）；B1 不选语种，留给 B2 |

**`workspaceSubtitles`**

| 字段 | 类型 | 初值 | 说明 |
|---|---|---|---|
| `cleanedTrack` | `SubtitleTrack \| null` | null | `CLEANED/segments.json` |
| `context` | `SubtitleContext \| null` | null | `CLEANED/context.json` |
| `translatedTrack` | `TranslatedTrack \| null` | null | B1 恒为 null（见 §9） |
| `translatedLanguage` | `string \| null` | null | B1 恒为 null |

**`workspaceKeywords`**

| 字段 | 类型 | 初值 | 说明 |
|---|---|---|---|
| `keywords` | `string[]` | `[]` | 服务端读到后就是本地可编辑清单 |
| `keywordsFingerprint` | `string \| null` | null | B1 不比对，留给 B2 |

`LoadError`、`WorkspacePhase`、`WorkspaceStatusKind` 的类型在 `stores/workspace.ts`；
运行时值 `WORKSPACE_PHASE` / `WORKSPACE_LOAD_ERROR_KIND` 在 `enums/workspace.ts`，类型由它派生。
`KeywordAddResult` 与 `MAX_KEYWORD_LENGTH` / `MAX_KEYWORDS` 在 `stores/workspaceKeywords.ts`。
都按 `type-safety.md:84` 与「有比较消费的枚举值放 enums/」处理。

### 6.2 computed

**`workspace`**：`lastRun: RunResponse | null`、
`phase: WorkspacePhase`（联合由 `enums/workspace.ts` 的 `WORKSPACE_PHASE` 派生）。
**`workspaceSubtitles`**：`cues: SubtitleCue[]`、`translationMismatch: boolean`。

`WorkspaceStatusKind`（`Exclude<WorkspacePhase, 'ready'>`）是状态卡的 props 类型，
要求由页面算好（见 §7.1），不放进 store。

`phase` 判定顺序（照这个顺序 if / return）：

| 条件 | phase |
|---|---|
| `loadError.kind === 'not-found'` | `not-found` |
| `loadError` 非空 | `error` |
| `snapshot === null` | `loading` |
| `cleanedTrack !== null`（读 `workspaceSubtitles`） | `ready` |
| `lastRun === null` | `error`（还没有任何执行记录，见 §6.3） |
| `lastRun.status === RUNNING` | `running` |
| `lastRun.status === SUCCEEDED` | `error`（跑完了却找不到可用字幕产物） |
| `FAILED` / `INTERRUPTED` | `run-failed` |

判据是**「有没有字幕可看」**，不是「lastRun 是什么状态」：只要 `cleanedTrack` 在，页面就是 `ready`。
翻译失败、重新生成失败时 `lastRun` 是 FAILED，但 CLEANED 字幕还好好的——把整页换成错误卡等于把能看的内容藏起来。
没有字幕时才按 `lastRun` 分流：还在跑 → `running`；失败或中断 → `run-failed`；跑完了却没产物 → `error`。

（B2 的设计把这个判据写成了同一套，见 B2 `design.md` §6.1。）

`cues`：内部只算一次 `buildCues(cleanedTrack, translatedTrack)`，结果存一个私有 computed `builtCues`，对外派生出 `cues` 与 `translationMismatch`，避免两个 computed 各跑一遍配对。

### 6.3 action

**`workspace`**

```ts
async function open(taskNo: string): Promise<void>
async function refresh(): Promise<void>
function reset(): void
```

**`workspaceSubtitles`**

```ts
async function load(artifact: ArtifactResponse): Promise<string | null>
function reset(): void
```

**`workspaceKeywords`**

```ts
async function load(taskNo: string): Promise<void>
function addKeyword(text: string): KeywordAddResult
function removeKeyword(text: string): void
function reset(): void
```

**`open`**：`reset()` → 写 `taskNo` → 走一次 `loadAll()`。

**`loadAll()`（内部）**，顺序与失败策略：

1. 清 `loadError`，取一个自增 `token` 防串台（同一页面快速切 `taskNo` 时，旧请求回来不许覆盖新状态）。
2. `getLanguages()` 与 `getTask()` 并行发出。语言清单失败降级为 `[]`（B1 不显示语言栏，不阻塞页面）。
3. 快照失败：404 → `loadError = {kind:'not-found'}`；其他 → `{kind:'error', message}`。到此为止，不再拉产物。
4. `lastRun === null` → 写 `loadError = {kind:'error', message:'这个任务还没有任何执行记录，请重新提交'}` 并结束（对应 `phase === 'error'`）。
5. `lastRun.status === RUNNING` → 结束。B1 不拉产物，页面显示运行中占位。
6. 终态（SUCCEEDED / FAILED / INTERRUPTED）→ 拉产物：
   - `listArtifacts()` 失败 → 写 `loadError = {kind:'error', message}`。
   - 选中 CLEANED 产物后交给 `workspaceSubtitles.load(artifact)`：段落拉取失败返回错误文案，写 `loadError`（字幕是本页主体，拿不到就给可重试的错误卡）；`context.json` 失败不算错，`context` 留 null，主题区走「未能提取主题」空态。
7. 关键词：`workspaceKeywords.load(taskNo)` 独立发出，**不阻塞**主流程；失败时保持 `keywords = []`（已知缺口，见 §11）。
8. 源语种归一：`normalizeLanguageCode(workspaceSubtitles.context?.sourceLanguage, languages)`，失败再用 `workspaceSubtitles.cleanedTrack?.language`，仍失败为 `''`。
9. 目标语种：`'en'` 若在 `languages` 里就用它，否则取第一个 code；`languages` 为空时保持 `'en'`。

**`refresh`**：不改 `taskNo`，重跑 `loadAll()`。它是错误卡「重试」和运行中占位「刷新」的落点。字幕与关键词会被服务端值覆盖——B1 的关键词本来就不落库，接受这个损失。

**`addKeyword`**：`trim` 后按顺序校验，返回结果给输入区显示提示：

- 空 → `'empty'`
- `Array.from(text).length > 50` → `'too-long'`
- 已经有 50 条 → `'too-many'`
- 与已有项完全相同 → `'duplicate'`（不去重后端，只挡用户重复点）
- 否则 push → `'added'`

校验放 store 而不是组件：B2 的「重新生成」提交的是 store 里的 `keywords`，上限必须在唯一的真相源上挡一次。备选：只校验条数、允许重复——关键词要交给 LLM，重复没有意义。

**`removeKeyword`**：按值删第一条匹配项。用值不用下标，是因为模板里 `v-for` 的 `key` 不能用位置下标，值 + 去重保证唯一。

**`workspaceSubtitles.load`**：按产物取 `segments.json` 与 `context.json`。段落是硬依赖，返回错误文案让 `workspace` 写 `loadError`；上下文失败返回 null 的成功结果，主题区自己降级。内部用自增 token 丢弃迟到的响应。

**`reset`**：三个 store 各自把自己清空；`workspace.reset()` 负责扇出调用另外两个的 `reset()`。`views/workspace/index.vue` 卸载时调用，避免回到工作区看到上一个任务的残留；B2 的 SSE 关闭也会挂在这里。

---

## 7. 页面结构与状态机

### 7.1 页面壳 `views/workspace/index.vue`

```
defineProps<{ taskNo: string }>()          // 路由 props: true 注入，不用 useRoute()

onMounted  → store.open(props.taskNo)
watch(taskNo) → store.open(...)
onUnmounted → store.reset()

phase === WORKSPACE_PHASE.READY
├─ WorkspaceHeader
└─ main（lg 以上左右分栏，以下上下堆叠）
   ├─ 左列（lg 60%）：MediaPane(aspect-video) / KeywordPane / ThemePane
   └─ 右列（lg 40%）：TranscriptPane

phase !== WORKSPACE_PHASE.READY
├─ AppHeader
└─ main（居中窄栏）
   └─ StatusCard :kind="statusKind"（statusKind = phase 去掉 ready 后的值）
```

- 3:2 分栏照原型：左 `lg:basis-[60%] lg:flex-none`，右 `lg:w-[40%] lg:flex-none`，`lg` 以下 `flex-col` 堆叠（原型 `WorkspaceView.vue:238-287`）。
- 左列是「一个主块 + 两个信息块」，**不等分**：播放器固定 16:9 `shrink-0`；关键词列表本体 `max-h-60 overflow-y-auto`；主题区 `shrink-0` 按内容撑高。左列整体 `lg:overflow-y-auto` 兜底。
- 滚动：桌面两个滚动区（左列、字幕列表）用同一套悬浮自动隐藏滚动条（§8.6）；移动端 `main` 自己是滚动容器。
- 入场动效沿用现状：`gsap.context` + `gsap.matchMedia('(prefers-reduced-motion: no-preference)')` + `power2.out` 0.4s + `stagger 0.08` + `y: 16`，`phase` 首次变 `ready` 时播一次，`onUnmounted` 里 `ctx.revert()`。溢出剪裁按 `animation.md:97-100`：`main` 在移动端是滚动容器（`overflow-y-auto`），变换溢出落在它自己的滚动区里、不会产生文档滚动条；`lg` 起 `main` 不滚（`lg:overflow-hidden`），把窗格溢出剪掉。
- 顶栏上方的「顶部错误条」位置**空着**：B2 在 `<main>` 之前插一条 `shrink-0` 的提示条，不改本页布局。

### 7.2 `WorkspaceHeader.vue`

内容：返回（`router.push('/')`）/ 竖线 / 任务标题（`snapshot.sourceUri`，`truncate`）/ 产物下拉。外壳继续用跨页公共组件 `AppHeader`（`frontend/src/components/AppHeader.vue`），保证和上传页同一条顶栏。

- **不加**视频/音频切换（没有媒体，切换没有意义）。
- **不加**导出按钮（PRD 明确去掉）。
- **不加** B1 的进度胶囊；B2 会在标题与「产物」之间插一个胶囊，这里在模板里留注释标记位置。

### 7.3 `ArtifactMenu.vue`

按产物分组列出，而不是平铺文件：

```
原始转写 · rev 1
  字幕段落 (JSON)  4.7 KB
  字幕文件 (SRT)   2.9 KB
清洗后字幕 · rev 2
  ...
译文（英语）· rev 1
  ...
```

- 分组标题：`ORIGINAL → 原始转写`、`CLEANED → 清洗后字幕`、`TRANSLATED → 译文（{lang label}）`；`lang` 用 `languages` 映射不到时直接显示 code。
- 文件标签：`segments → 字幕段落 (JSON)`、`subtitles → 字幕文件 (SRT)`、`context → 主题与术语 (JSON)`、`terms → 术语表 (JSON)`，不认识的键显示键名。
- 下载：`<a :href="file.url" :download>` 直连，**不吃 `getAbsolute`**（那是给 JSON 拉取用的；`<a>` 本来就该拿全路径，`api.md:29` 的「双拼法」）。
- 体积用 `formatBytes`（`utils/format.ts`）。
- 展开态本组件自己管，`document` 点击外部收起，监听在 `onScopeDispose` 里摘掉（`component-guidelines.md:99`）。
- 清单为空：「暂无可下载的产物」。

### 7.4 `MediaPane.vue`（扁平文件）

`aspect-video w-full shrink-0` 的黑场（`bg-primary`）+ 居中的图标与两行文案：

```
媒体预览暂不可用
后端暂未提供媒体地址，字幕仍可正常查看与搜索
```

- 不渲染 `<video>` / `<audio>`，不写 `src`，不留 `source.mp4` 死链。
- 不显示视频/音频切换与播放控件——没有源，控件是假的。
- 布局位置照原型（左列第一块，占满宽度，固定 16:9）。
- B2 也不碰它；等后端补了媒体端点，只需在这里加 `src` 与控件（在文件头留一条注释）。

### 7.5 `KeywordPane.vue`

- 标题行：`关键词` + `{keywords.length}/50`。**B1 右侧不放「重新生成」**，但保留按钮位（模板注释标记），B2 直接填进去。
- 输入框 + 加号按钮：回车或点加号添加（`@keydown.enter`，`event.isComposing` 时跳过，防止中文输入法选词误触发）；输入框 `maxlength="50"`；下方提示「回车或点 + 添加，单条最多 50 字」+ 当前字数。
- `addKeyword` 返回非 `'added'` 时显示对应提示：`最多添加 50 条关键词。` / `单条关键词最多 50 字。` / `这个关键词已经在清单里了。`
- 列表：垂直列表，每行一个关键词，长词 `break-words` 换行不省略，行尾删除按钮。`max-h-60 overflow-y-auto` + 悬浮滚动条。
- 空态：「还没有关键词，输入后按回车添加」。
- 增删只改本地清单，不请求、不提示已保存（B2 点「重新生成」才提交）。

### 7.6 `ThemePane.vue`

- 只显示 `context.theme`，不显示 `terms`（`terms` 是 LLM 抽的术语，和用户关键词不是一回事，原型也是这个口径）。
- `context === null` / `degraded === true` / `theme.trim() === ''` → 明确空态卡：「未能提取主题」+「本次清洗没有产出可用主题，字幕仍可正常查看。」不留白。
- 不显示「更新中」「请尝试重新生成」这类运行态/操作引导（B2 才有重生成）。

### 7.7 `TranscriptPane.vue`

头部（从上到下）：标题 `字幕稿` + `命中/总数 条`；**（B2 的 TranslationBar 插在这里）**；搜索框。

列表：时间码 + 原文；一行一条。

交互细节：

- **搜索**：只匹配原文（B1 没有译文），大小写不敏感，`trim` 后为空则不过滤；计数显示 `filtered.length/cues.length 条`；无结果显示「没有匹配「query」的字幕」；无字幕显示「这条任务还没有可用字幕」。
- **选中**：点整行选中（`bg-mist` + 左侧竖条），再点取消。B1 没有媒体，点击**不 seek**——原型里点击跳转时间点的动作在没有播放器时是个假交互，所以去掉（偏离清单 §9）。选中状态是组件本地 `ref`，`cues` 条数变化时清掉，避免下标指到别的句子。
- **行容器不是控件**：整行只是可点区域，不给 `role="button"`、不设 `tabindex`——它既没有键盘动作，也会让内嵌的按钮变成非法嵌套（ARIA 不允许 button 角色里有交互元素）。选中态用 `aria-current` 标出。
- **修改入口（占位）**：`group` 行，右上角铅笔按钮 `opacity-0 group-hover:opacity-100`，绝对定位不挤动正文。后端还没有改单条字幕的接口，本任务把按钮设成 `disabled`，`title` / `aria-label` 写「编辑功能暂未开放」；不进入编辑态、不做本地覆盖（2026-10-24 延期，见 PRD AC5）。
- 行不设自动滚动跟随（没有当前句）。

### 7.8 `StatusCard.vue`

一个组件承包四种非就绪态，props 只有 `kind`（`'loading' | 'not-found' | 'error' | 'running' | 'run-failed'`），内容从 store 取：

| kind | 标题 | 正文 | 按钮 |
|---|---|---|---|
| `loading` | — | 「正在加载任务…」脉冲 | 无 |
| `not-found` | 任务不存在 | 没有找到任务 `{taskNo}`，链接可能输入有误，或任务已被清理。 | 返回上传 |
| `error` | 加载失败 | `loadError.message` | 重试（`refresh()`）/ 返回上传 |
| `running` | 执行中，请稍后刷新 | 任务正在后台处理，完成后刷新本页即可看到字幕。 | 刷新（`refresh()`）/ 返回上传 |
| `run-failed` | 任务处理失败 | `lastRun.errorMessage`（空时：中断 →「上次执行被中断，请重新提交」；失败 →「处理过程中出现错误，未返回具体原因」）+ `finishedAt`（有则 `MM-DD HH:mm`） | 返回上传 |

样式沿用现有卡片（`rounded-2xl border border-border bg-background p-5`），失败类加 `role="alert"`。

---

## 8. 数据流

### 8.1 打开页面

```
路由 props.taskNo
  → index.vue onMounted → store.open(taskNo)
    → reset() → taskNo = x
    → getTask(x) ∥ getLanguages()
    → (终态) listArtifacts(x)
        → pickArtifact(ARTIFACT_TYPE.CLEANED)
        → fetchArtifactJson<SubtitleTrack>(segments.url)
        → fetchArtifactJson<SubtitleContext>(context.url)
    → getTaskKeywords(x)（并行、不阻塞）
    → 源语种归一
  → phase: ready / running / run-failed / not-found / error
```

**加载顺序的理由**：快照决定有没有东西可拉；产物清单决定文件地址；segments/context 决定页面内容；关键词与语言清单互不依赖，并行。任何一个附属请求失败都不该把整页拖进错误卡，唯一例外是「字幕本身拉不到」。

### 8.2 字幕构建

`cleanedTrack.segments` + `translatedTrack?.segments` → `buildCues` → `cues`。B1 里 `translatedTrack` 恒为 null，所以双语的第二条永远不出现；配对与校验逻辑现在就写好，B2 只要把译文塞进 `workspaceSubtitles`。

### 8.3 语种归一

`context.sourceLanguage`（LLM 自由文本）→ `normalizeLanguageCode` → 规范 code → `selectedSourceLanguage`；失败回退 `cleanedTrack.language`；再失败 `''`。归一结果只是 UI 初值，B2 提交翻译时会把它放进 `TranslationRequest.sourceLanguage`，后端还会再按白名单校验一次——前端归一不是校验，只是别让下拉显示一个不存在的选项。

### 8.4 关键词本地态

`GET /keywords` 的 `keywords` 直接覆盖 store；用户增删只改 store；B1 没有提交动作，`keywordsFingerprint` 只存不用。B2 的「重新生成」会提交 `keywords`（数组，空就是 `[]`），成功后重新拉一次关键词与产物。

### 8.5 产物下载

`ArtifactMenu` 的 `<a>` 直接拿 `file.url`（`/api/tasks/...`）→ dev 由 Vite proxy 转发 → 浏览器按 `Content-Disposition` 处理，`download` 属性保证存成文件。JSON 拉取走 `fetchArtifactJson` → `getAbsolute`。

### 8.6 悬浮滚动条

把原型 `prototype/src/assets/tailwind.css:40-76` 的 `.overlay-scrollbar` 重写进 `frontend/src/assets/main.scss`：默认 `scrollbar-color: transparent transparent`；`:hover` / `:focus-within` 时露出 6px 半透明滑块（`color-mix` 用 `--color-faint`）。

为什么放 `main.scss` 而不是 `tailwind.css`：`styling.md:15` 把全局样式归到 `main.scss`；原型放 `@layer components` 只是当时怎么快怎么来。备选：照原型留在 `tailwind.css` 的 `@layer components` 里——两处都是全局，评审若要求与原型逐字对齐可以改回去。

---

## 9. 与原型的偏离清单（AC1 / AC11 逐条看这里）

| # | 原型 | B1 | 理由 |
|---|---|---|---|
| 1 | 顶栏有视频/音频切换 | 去掉 | 后端没有媒体端点，切换不改变任何东西，是假交互 |
| 2 | 顶栏有禁用的「导出 SRT」 | 去掉 | PRD R2 明确不加；真导出应该走产物端点下载，而不是再造一个按钮 |
| 3 | 播放器能放 demo 媒体（视频黑场 / 音频封面 + 波形 + 播控） | 16:9 黑场 + 「媒体预览暂不可用」 | 同上；`source.mp4` 是死链，不留死链、不造假播放器 |
| 4 | 关键词区右侧有「重新生成」 | 不放（留位置） | B2 的范围；B1 没有 `POST /regenerate` 的接线与进度 |
| 5 | 关键词区在运行中禁用输入 | 不实现 | B1 没有运行中页面 |
| 6 | 主题区有「正在生成 / 更新中」 | 不实现 | 同上 |
| 7 | 字幕区有语言选择、翻译按钮、译文第二行、「已翻译为…」 | 不建（TranslationBar 由 B2 建） | PRD 明确「翻译控件与译文显示」归 B2 |
| 8 | 字幕行点击 seek、当前句高亮、自动滚动跟随 | 去掉，改成本地「选中」高亮；行容器由 `role="button"`（原型）改为普通列表项 | 没有媒体元素就没有 `currentTime`，seek 与「当前句」无处可依；行上没有键盘动作，再挂 button 角色会让内嵌的铅笔按钮非法嵌套 |
| 9 | 字幕搜索同时匹配原文与译文 | 只匹配原文 | B1 不显示译文，匹配看不见的内容会让计数对不上 |
| 10 | 有 `?demo=processing` 等调试入口 | 不建 | 调试开关不进正式页面 |
| 11 | 没有失败 / 中断 / 任务不存在 / 加载失败态 | 补 StatusCard 四态 | 真实后端会失败；父任务已裁定「补最小失败态」，AC9 要求 |
| 12 | 顶栏没有进度胶囊 | 不放 | B2 加 |
| 13 | 空清单时按钮文案「清空并重新生成」 | 不涉及 | 没有按钮 |
| 14 | 行内编辑（textarea / 保存取消 / 本地覆盖） | 只保留禁用的铅笔占位 | 后端没有改单条字幕的接口；2026-10-24 裁为延期，PRD AC5 同步改 |

同一份清单会原样出现在 `implement.md` 的验收对照里。

---

## 10. 为 B2 预留的接口（只列缝合点，不替 B2 设计）

**类型 / API / 纯函数（B1 就位，B2 直接 import）**

- `translateTask` / `regenerateSubtitles`（`api/tasks.ts`）、`TranslationRequest` / `RegenerateSubtitlesRequest`（`types/task.ts`）。
- `api/artifacts.ts` 的 `listArtifacts` 与 `fetchArtifactJson`（译文产物同样走这两个）。
- `pickArtifact(artifacts, ARTIFACT_TYPE.TRANSLATED, lang)` 取目标语种译文。
- `buildCues(cleaned, translated)` 的配对与 `mismatch` 已经写好，B2 只需把 `translatedTrack` 填进 store。
- `normalizeRunProgress` 的类型与常量（`RECLEAN_STAGE_BASE`）。
- `getAbsolute` 供 SSE/其他全路径场景复用（B2 的 SSE 走 `composables/`，不走 axios，但同款路径口径要一致）。

**store（B1 不建，B2 增补）**

- state：`runProgress`、`activeRunNo`、`streamError`、`translationInvalidated`。
- computed：`streamState`（B2 把它落成 **computed** 而不是 state——流的真实状态只有 composable 知道，
  store 再存一份就是两个状态源，早晚不同步）、`runPercent`、`isBusy`。
- action：`startRun`、`retryStream`（组件不该自己拼 `TaskRunRef`）、`regenerate`、`translate`、
  `selectTargetLanguage`、`closeStream`。
- `refresh()` 会被 B2 加一个可选参数（`reloadKeywords`），B1 的调用点不传即可。
- `types/task.ts` 的 `StageProgress` / `TaskRunProgress` **已在 B1 建好**（B1 的 `utils/progress.ts` 要用），
  B2 直接用，不必再往这个文件里加类型。
- `reset()` 里要调 `closeStream()`——B1 的 `reset` 已经挂在页面卸载上，B2 补一行即可。

**组件**

- `WorkspaceHeader`：标题与「产物」之间留了进度胶囊的位置（模板注释）。
- `KeywordPane`：标题行右侧留了「重新生成」的位置，`addKeyword` / `removeKeyword` 已是唯一真相源。
- `TranscriptPane`：标题行与搜索框之间留了 `TranslationBar` 的位置；`translatedText` 字段已经在 `SubtitleCue` 里。
- `StatusCard`：`run-failed` / `running` 两种卡已经在，B2 可继续用；顶部错误条是 `<main>` 前的新元素，不改本页布局。
- `index.vue`：`phase === 'running'` 现在的落点是 StatusCard，B2 会把它换成「双栏 + 进度」；`phase` 已经是 `'running'`，不用改判定。

**明确不预留的**：媒体播放（后端缺口，不在本任务树内）、行内编辑（后端没有改单条字幕的接口，2026-10-24 裁为延期）。

---

## 11. 已知缺口与风险

1. **关键词读取失败没有独立提示**：`getTaskKeywords` 失败时只保持空表，用户看到「还没有关键词」而不是「加载失败」。为了不加共享契约之外的 state，B1 接受这个降级；`refresh()` 可以重试。B2 如果需要，加 `keywordsError` 字段。
2. **语言清单失败没有任何提示**：B1 不显示语言栏，所以影响只落在 B2；B2 接语言栏时要补重试或空态。
3. **已存在译文的任务在 B1 里只显示原文**：即使 `artifacts` 里有 TRANSLATED，`translatedTrack` 也保持 null。这是 PRD「B1 不做译文显示」的直接结果，不是数据丢失；B2 接上即可。
4. **行内编辑延期后的遗留问题**：后端没有段落稳定 id（`TranscriptionSegment` 只有时间戳 + 文本）。将来接编辑接口时，如果仍按下标存，产物重新生成后可能错位；届时应改用 `index + revision`（handoff §4.2 的记录）。
5. **`ArtifactType` 的落位**已按 `type-safety.md:92` 改为 `enums/subtitle.ts`（见 §4.1）；B2 必须用同一份路径。
6. ~~`lib/progress.ts` 与目录规范不一致~~ 已改：`utils/progress.ts`（见 §5.4）。
7. **规范文档已过期**：`.trellis/spec/frontend/api.md:11,38,56-60` 还写着旧 `/events`、`api/media.ts`、202 返回 `TaskView`、409 带 `existingTask`；`directory-structure.md:55` 的 `types/` 文件清单也还是 `task/media/transcript`。B1 **不改规范**（父任务把「订正规范」划给 B2 R6），但实现时以本文与后端源码为准，不要照旧规范写。
8. **`MediaController` 仍在 `/api/media/tools`**：B1 删的是前端没人用的 `api/media.ts` / `types/media.ts`，不是后端能力。将来要用诊断接口，按当前 `ToolDiagnosticReport` 重建域文件（不是恢复旧文件，旧 DTO 名已对不上）。
9. **`type-check` 假绿**：验收必须起前后端看真实页面（§12 已写具体判据）。

---

## 12. 事实来源（写文档时逐条核过）

**任务文档**

- `.trellis/tasks/10-22-workspace-ready-page/prd.md`
- `.trellis/tasks/10-21-workspace-fe-rewrite/prd.md`、`handoff.md`
- `.trellis/tasks/10-21-workspace-prototype/design.md`
- `.trellis/tasks/10-22-workspace-run-page/prd.md`（只读 B2 边界）

**规范**

- `.trellis/spec/frontend/api.md:10,29,40,56-60`
- `.trellis/spec/frontend/type-safety.md:17,78-95`
- `.trellis/spec/frontend/directory-structure.md:55,57-59,63`
- `.trellis/spec/frontend/state-management.md:33-50,94`
- `.trellis/spec/frontend/component-guidelines.md:69,76,99`
- `.trellis/spec/frontend/styling.md:15`
- `.trellis/spec/frontend/animation.md:97-100`
- `.trellis/spec/frontend/quality-guidelines.md:30-63,186-197`

**后端**

- `backend/src/main/java/com/vidsage/web/controller/TaskController.java:78-87,105-119,131-138,156-169,184-194,213-223,234-240`
- `backend/src/main/java/com/vidsage/web/controller/ArtifactController.java:61-69,89-108`
- `backend/src/main/java/com/vidsage/web/controller/LanguageController.java:28-41`
- `backend/src/main/java/com/vidsage/web/dto/TaskResponse.java:25-30`
- `backend/src/main/java/com/vidsage/web/dto/RunResponse.java:22-32`
- `backend/src/main/java/com/vidsage/web/dto/TaskRunRefResponse.java:9-18`
- `backend/src/main/java/com/vidsage/web/dto/ArtifactResponse.java:24-32,56-60`
- `backend/src/main/java/com/vidsage/web/dto/ArtifactFileResponse.java:8-15`
- `backend/src/main/java/com/vidsage/web/dto/SubtitleKeywordsResponse.java:18-23`
- `backend/src/main/java/com/vidsage/application/task/message/TaskRunProgress.java:37-62`
- `backend/src/main/java/com/vidsage/domain/subtitle/model/ArtifactFileKind.java:20-29`
- `backend/src/main/java/com/vidsage/domain/subtitle/model/cleanup/SubtitleTrack.java:20-22`
- `backend/src/main/java/com/vidsage/domain/subtitle/model/common/SubtitleContext.java:19-20,39-54`
- `backend/src/main/java/com/vidsage/domain/subtitle/model/translation/TranslatedTrack.java:22-23`
- `backend/src/main/java/com/vidsage/domain/subtitle/model/translation/SupportedLanguage.java:17-20`
- `backend/src/main/java/com/vidsage/domain/transcription/model/TranscriptionSegment.java:10`
- `backend/src/main/java/com/vidsage/domain/shared/model/StageProgress.java:11-29`

**前端现状（只读）**

- `frontend/src/types/task.ts:34-65`
- `frontend/src/api/request.ts:57-110`
- `frontend/src/api/tasks.ts:53-61`
- `frontend/src/composables/useTaskEvents.ts:57`
- `frontend/src/stores/task.ts`
- `frontend/src/views/workspace/index.vue:146-179`
- `frontend/src/views/workspace/components/MediaPane/index.vue:38`
- `frontend/src/views/workspace/components/WorkspaceHeader.vue:39-41`
- `frontend/src/views/upload/index.vue:56-77`
- `frontend/src/assets/tailwind.css:8-31`（设计令牌）

**原型（形态参考，不搬代码）**

- `prototype/src/views/WorkspaceView.vue:238-287`
- `prototype/src/components/KeywordPane.vue:67-194`
- `prototype/src/components/ThemePane.vue:22-90`
- `prototype/src/components/TranscriptPane.vue:200-546`
- `prototype/src/components/MediaPane.vue:49-158`
- `prototype/src/assets/tailwind.css:40-76`
