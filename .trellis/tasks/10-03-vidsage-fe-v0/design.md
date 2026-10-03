# 前端 v0 技术设计

> 需求见 `prd.md`。设计目标：把原型验证的视觉/交互方案按 `.trellis/spec/frontend/` 规范
> **重写**进 `frontend/`，接通后端 v0 真实契约。原型代码（`prototype/src/`）只作行为参照，
> 不搬运文件。

## 1. 架构与边界

### 1.1 数据流

```
上传页（views/upload/）                 工作区页（views/workspace/）
┌─────────────────────┐   router.push   ┌──────────────────────────────┐
│ submitUrl/submitUpload│ ──taskNo────▶ │ stores/task.ts（唯一状态源）    │
│ axios onUploadProgress │              │  load(): GET /tasks/{taskNo}  │
└─────────────────────┘               │  subscribe(): useTaskEvents   │
                                      │   SSE progress 事件 → progress │
                                      │  SUCCEEDED → 拉产物 → cues      │
                                      │  translate(): POST /translate  │
                                      └──────┬───────────────┬─────────┘
                                             │ props         │ props
                                      MediaPane        TranscriptPane
                                      (真实媒体播放)     (双行双语字幕稿)
                                             └── useTranscriptPlayer ──┘
```

- 工作区状态（TaskView / SSE 进度 / cues）被 header、媒体面板、字幕稿三方消费 →
  建 Pinia setup store `src/stores/task.ts`（`useTaskStore`），符合 state-management.md
  「跨组件共享且响应式 → 建 store」。上传页状态（表单、拖拽、上传进度）是页面私有 →
  留在视图内，不建 store。
- SSE 按 api.md 不进 `src/api/`：`src/composables/useTaskEvents.ts` 封装 EventSource。
- 纯函数（时间/字节格式化）放 `src/utils/format.ts`（component-guidelines.md：纯函数不进
  composables）。

### 1.2 文件布局（新增/改动）

```
src/
├── api/
│   ├── tasks.ts            [改] submitUpload 增 onUploadProgress 回调 + 超时覆写；
│   │                            契约类型改从 @/types/* 导入
│   └── types.ts            [删] 内容迁入 src/types/ 包（D7，用户定调与 api 分离）
├── types/                  [新] 跨模块共享类型包（两级类型策略的全局层，见 §2）
│   ├── task.ts             [新] 任务域契约镜像：TaskView/SubmitUrlRequest/
│   │                            TaskConflictResponse/TaskProgressMessage/
│   │                            StageEvent/StageState/TranscriptionSegment/
│   │                            TranslatedSegment
│   ├── media.ts            [新] ExternalToolReport（契约镜像）
│   └── transcript.ts       [新] TranscriptCue（跨 store/composable/视图共享的
│                                前端视图模型）
├── assets/
│   └── tailwind.css        [改] 迁入原型 @theme 设计令牌（黑白灰 + sage 梯度）
├── components/
│   └── BrandLogo.vue       [新] 品牌 logo（上传/工作区两页共用，跨页组件）
├── composables/
│   ├── useTaskEvents.ts    [新] SSE 订阅（EventSource，onScopeDispose 清理）
│   └── useTranscriptPlayer.ts [新] 字幕联动播放器（重写自原型，媒体元素唯一时间源）
├── lib/
│   ├── gsap.ts             [已有] GSAP 统一入口（本任务不动）
│   └── day.ts              [新] dayjs 单例配置（zh-cn locale + relativeTime 插件），
│                                模式同 gsap.ts——组件一律从 @/lib/day 导入
├── stores/
│   └── task.ts             [新] useTaskStore：taskView/progress/cues/错误态 + actions
├── utils/
│   └── format.ts           [新] formatTime（时长 mm:ss）/ formatBytes（纯函数，
│                                均不属日历时间，不引 dayjs——见 §2.1）
├── routes/
│   └── index.ts            [改] :videoId → :taskNo（对齐后端契约命名）
└── views/
    ├── upload/
    │   ├── index.vue       [改] 重写为原型上传态 + 真实提交
    │   └── components/     [新] 仅本页使用的组件（如 HeroBanner）按需落位
    └── workspace/
        ├── index.vue       [改] 重写为状态机壳（进度/失败/就绪）
        └── components/     [新] WorkspaceHeader / MediaPane / TranscriptPane /
                                  ArtifactMenu（页面私有，不上移 components/）
```

## 2. 契约与类型（两级类型策略，2026-10-03 用户定调 D7）

**全局层 `src/types/`**：准入门槛 = **跨模块共享**。放契约镜像与跨模块视图模型，按域一文件：
- `task.ts` / `media.ts`：后端契约镜像（与 Java record / 枚举逐字段对齐的既有约定延续）；
- `transcript.ts`：`TranscriptCue` 视图模型——store 产出、`useTranscriptPlayer` 与两个
  面板消费，三方共享故入全局层；且 composable 需要 Cue 形状又不应反向依赖 store，
  无依赖叶子包正好解此耦合。

**模块层**：仅本模块使用的类型就地放——SFC 局部类型内联、推断/派生类型跟随源模块导出
（如 `TranscriptPlayer` 即 `useTranscriptPlayer` 的 `ReturnType`，不强迁全局）；确需成
文件时建**模块内** `types/`（如 `api/types/`），v0 暂无此类需求。

连带义务：`.trellis/spec/frontend/directory-structure.md`（及 api.md 若有引用）目前约定
「types.ts 在 api/ 内」，须在本任务 3.3 spec update 步修订为两级策略，否则后续会话会按
旧规范改回。

继续「与 Java record 逐字段镜像」的既有约定：

```ts
type StageState = 'RUNNING' | 'SUCCEEDED' | 'SKIPPED' | 'FAILED'
interface StageEvent { stage: string; progress: number; message: string; state: StageState }
interface TaskProgressMessage {
  taskNo: string
  taskStatus: TaskStatus
  stage: StageEvent | null
  translationStatus: TranslationStatus | null
}
/** 产物段落 record 镜像 */
interface TranscriptionSegment { beginTimeMs: number; endTimeMs: number; text: string }
interface TranslatedSegment { beginTimeMs: number; endTimeMs: number; sourceText: string | null; translatedText: string | null }
/** 产物 JSON 信封（2026-10-03 冒烟实证，backend/data/works 真实产物）——不是裸数组 */
interface SubtitleArtifactFile { route: string; language: string | null; segments: TranscriptionSegment[] }
interface TranslatedArtifactFile { route: string; sourceLanguage: string; targetLanguage: string; skipped: boolean; segments: TranslatedSegment[] }
```

`tasks.ts` 的 `submitUpload` 增加可选 `onUploadProgress?: (percent: number) => void`，
并在该请求上覆写 `timeout: 0`——全局 15s 超时对 2GB 上传必然误伤。

### 2.1 时间处理分野（2026-10-03 用户定调：时间格式化用主流轻量库）

| 场景 | 工具 | 理由 |
|---|---|---|
| **日历时间**：`TaskView` 的 createdAt / startedAt / finishedAt 展示、任务耗时/已运行时长 | **dayjs**（新依赖，`~2kB gzip`），统一经 `src/lib/day.ts` 单例配置（`locale('zh-cn')` + `relativeTime` 插件），组件禁止直接 `from 'dayjs'` | 日期边界（时区、locale、相对时间）是 dayjs 的本职，不手搓 |
| **媒体时长**：播放器 currentTime / duration / 字幕时间码（`mm:ss`） | 纯函数 `formatTime`（`utils/format.ts`） | 这是**时长**不是日历时间——dayjs 无时长 API，硬套 `dayjs(0).ms(s)` 会在 ≥1 小时时静默丢小时位；3 行纯函数即可且无依赖 |
| 字节体积（产物 sizeBytes） | 纯函数 `formatBytes` | 非时间问题 |

工作区消费点（dayjs 的落点）：进度卡显示「已提交 hh:mm · 已运行 x 分钟」；失败卡显示
提交/失败时刻。就绪态 header 标题下不排时间（原型顶栏已密）。

## 3. 关键机制设计

### 3.1 SSE 接线（`composables/useTaskEvents.ts`）

- `new EventSource('/api/tasks/${taskNo}/events')`，`addEventListener('progress', …)`，
  data JSON.parse 为 `TaskProgressMessage`。
- 断线不手动重连：浏览器 EventSource 自动重连，后端连接建立即推 DB 快照，进度无感恢复
  （`TaskSseHub` 契约）。未知 taskNo 在订阅前已被 `getTask` 404 拦下。
- **终态判定与后端同一口径**：`taskStatus ∈ {SUCCEEDED, FAILED}` 且
  `translationStatus !== 'RUNNING'` 才 `close()`（翻译事件主状态恒 SUCCEEDED，只看主状态
  会把翻译中的连接提前收掉，见 `TaskController.isTerminal`）。
- composable 内 `onScopeDispose(close)` 清理，不留给调用方。

### 3.2 产物选择与 Cue 构建（`stores/task.ts` 内纯函数）

后端产物名带路线指纹（`SubtitleArtifactNames`）：`segments-<route>.json`、
`segments-<route>-clean.json`、`segments-<route>-translate-<lang>.json`。

- **原文段落**：artifacts 中 `segments-*.json` 且不含 `-translate-`；有 `-clean.` 后缀者
  优先（清洗后是展示口径），否则取第一个。
- **译文段落**：`segments-*-translate-*.json`，存在即认为已有翻译。
- **合并**：产物 JSON 为信封结构（`{route, language, segments}` / 译文多
  `sourceLanguage/targetLanguage/skipped`），取 `.segments` 后按下标逐条配对（后端
  `TranslatedSegment` 契约保证时间戳逐条一致），防御性取 `min(len)`。渲染模型：

```ts
interface TranscriptCue { id: string; startMs: number; endMs: number; text: string; translatedText?: string }
```

- 经产物端点取 JSON：`request.get(`/api/tasks/${taskNo}/artifacts/${name}`)`（同源，axios
  可用；下载链接则用原生 `<a href download>` 直连端点，不走 axios）。

### 3.3 媒体播放（MediaPane）

- 播放源：`/api/tasks/${taskNo}/artifacts/source.mp4`——上传与本站落盘同名
  （`TaskService.UPLOAD_SOURCE_FILE_NAME`），URL 任务亦保留 `source.mp4`（backend/data 实证），
  产物端点仅做路径穿越校验，可直取。
- 形态判定：`inputType === 'UPLOAD'` 且 `sourceUri` 扩展名为音频
  （mp3/m4a/wav/aac/flac/ogg/opus）→ 原型音频卡片（封面 + 可点击波形 + 控制条）；
  其余 → 视频黑场舞台。URL 任务一律按视频。
- 联动：`useTranscriptPlayer` 重写自原型——媒体元素唯一时间源 + rAF 循环（波形平滑）、
  activeIndex 按时间段匹配、seek 钳制、`onScopeDispose` 清理。
- **Range 风险**：控制器返回 `ResponseEntity<FileSystemResource>`，Spring 对 Resource
  返回值的 Range 支持需实测（`curl -H "Range: bytes=0-1023" -i`）。不支持时拖动进度条会
  触发整段重拉——v0 接受（演示视频短），实测异常则登记后续任务加 `ResourceRegion`
  媒体端点，不在本任务扩散后端改动。

### 3.4 工作区状态机（`views/workspace/index.vue`）

```
loading（GET 快照中）
  ├─ PENDING/RUNNING ──▶ 进度卡：stage.message + progress 条 + 百分比（SSE 驱动）
  │                        └─ SUCCEEDED ──▶ 就绪：header + 双栏工作区
  ├─ FAILED ──▶ 失败卡：errorMessage + 重新上传（回 `/`）
  └─ 404 ──▶ 任务不存在卡：引导回上传页（页内状态，不动用全局 404 路由）
```

- 原型的「解析进度卡」语义移到工作区（上传页只管提交与上传进度）；阶段文案直接用
  `stage.message`（后端中文），不再硬编码原型四阶段。
- 翻译子流程：就绪态 header 的「翻译」按钮 → `POST /translate`（202）→ 同一条 SSE 继续
  推 translationStatus；按钮态随 `RUNNING` 禁用；翻译 SUCCEEDED → 重拉 artifacts +
  译文段落合并进 cues；FAILED → 按钮恢复可点（可重试），错误提示内联。
- 产物导出：header「产物」按钮弹小型面板（绝对定位 + 点击外部关闭），列出 artifacts
  清单（文件名 + 体积），每项 `<a>` 直连下载端点。替代原型禁用的「导出 SRT」占位。

### 3.5 上传页（`views/upload/index.vue`）

- 视觉复刻原型：sage 声波 hero（确定性伪随机波形的 SVG，内联于本页）、本地/链接双模式
  切换（两面板叠同一 grid 单元，切换不跳高）。
- 本地文件：拖放 + 选择器 → `submitUpload` 带 axios `onUploadProgress`，上传态显示
  百分比进度（替代原型模拟动画）；成功 202 即 `router.push(/workspace/${taskNo})`。
- 链接：`submitUrl`；提交中按钮禁用。
- **409 冲突**：提示「已有进行中任务」+ 按钮「查看该任务」跳 `existingTask.taskNo`。
- 其余提交失败：卡内内联错误文案（axios error.response.data.message 优先）。

### 3.6 双行双语字幕稿（TranscriptPane，2026-10-03 用户已定）

- `translatedText` 存在的条目：原文（text-ink）在上、译文（text-foreground，稍强调）在
  下；无译文条目保持单行。当前句高亮、时间码、左侧指示条同原型。
- 搜索同时匹配原文与译文。
- 行高增加后自动滚动跟随的居中逻辑不变（offsetTop 计算按元素实测，不受双行影响）。

## 4. GSAP 动效清单（克制，见 animation.md）

| # | 位置 | 效果 | 参数 |
|---|---|---|---|
| 1 | 上传页入场 | hero 横幅与上传卡 `autoAlpha: 0 → 1` + `y: 16 → 0` stagger | `power2.out`，0.4s，stagger 0.08 |
| 2 | 工作区就绪 | 双栏面板同款入场 stagger | 同上 |

硬规则执行：`import { gsap } from '@/lib/gsap'`；只在 `onMounted`/数据就绪回调里创建；
`gsap.context(fn, rootRef)` + `onUnmounted(() => ctx?.revert())`；`gsap.matchMedia()`
带 `(prefers-reduced-motion: no-preference)` 分支，降级即静态；目标用
`data-animate` 属性选择器；只动 transform/opacity。hover 微交互仍走 Tailwind
`transition-*`，不进 GSAP。不新增插件注册（无 ScrollTrigger 场景）。

## 5. 兼容与回滚

- 全部为前端改动；无后端、无数据迁移、无接口破坏。改动面：`routes/index.ts`（参数名）、
  `api/tasks.ts`（函数签名，唯一调用方是本任务新增的上传页）、`tailwind.css`（空文件填充）。
- 回滚 = `git revert` 本任务提交，无残留状态。
- `views/upload` / `views/workspace` 现为占位页，重写无兼容包袱。

## 6. 已知风险与对策

| 风险 | 对策 |
|---|---|
| 产物端点 Range 支持 | **已实测解决（2026-10-03 冒烟）**：`curl -H "Range: bytes=0-1023"` 返回 206 + 1024 字节，Spring 对 `ResponseEntity<FileSystemResource>` 自动处理 Range，播放器 seek 无忧，无需媒体端点 |
| 2GB 上传超 15s 全局超时 | submitUpload 单请求 `timeout: 0` 覆写 |
| SSE 在部分代理下被缓冲 | dev 直连 vite proxy 无此问题；生产反代配置属部署层，v0 不管 |
| segments 双数组错位 | 后端配对契约 + 下标合并 + min(len) 防御；发现错位按后端缺陷处理 |
| URL 任务无标题信息（source.info.json 不在 artifacts 清单） | header 展示 `sourceUri` 原文并 truncate，v0 接受 |
