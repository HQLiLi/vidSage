# 前端 v0：按原型落地两态页面

## Goal

把 `prototype/` 验证通过的设计方向（黑白灰单色 UI + 深绿氛围横幅，上传态 → 工作区态两态页面）
**重写**进 `frontend/`，并接通后端 v0 的真实契约（任务提交 / SSE 进度 / 按需翻译 / 产物下载），
交付一个可端到端跑通的浏览器界面：提交 B 站链接或本地文件 → 实时看到解析进度 →
阅读带时间码的双语字幕稿 → 导出 SRT。

原型 README 的约束：验证通过的方案**重写**进 `frontend/`，不直接搬运代码。

## Background（仓库证据）

### 后端契约（v0 已完成，`TaskController.java`）

| 端点 | 语义 |
|---|---|
| `POST /api/tasks` | 提交 URL 任务，202 返回 `TaskView`（PENDING） |
| `POST /api/tasks/upload` | multipart 字段 `file` 上传本地文件，202；上限 2GB |
| `GET /api/tasks/{taskNo}` | 查任务视图（状态 / 阶段 / 错误 / 产物清单） |
| `GET /api/tasks/{taskNo}/events` | SSE，事件名 `progress`，data 为 `TaskProgressMessage` JSON；连接建立先推 DB 快照，终态推完即关；30s 心跳 |
| `POST /api/tasks/{taskNo}/translate` | 按需触发翻译，202；幂等；主任务未成功返回 400 |
| `GET /api/tasks/{taskNo}/artifacts/{fileName}` | 产物下载；路径穿越校验后允许工作区内任意常规文件 |
| `GET /api/media/tools` | yt-dlp / ffmpeg / ffprobe 诊断 |

- **两段式设计（2026-10-02）**：主管线跑到原文字幕为止；翻译由用户显式触发——UI 必须暴露「翻译」入口。
- `TaskProgressMessage` = `{ taskNo, taskStatus, stage: { stage, progress, message, state } | null, translationStatus }`；翻译事件的主状态恒为 SUCCEEDED，终态判定 = 主状态终态**且**翻译不在 RUNNING（`TaskController.isTerminal`）。
- 409 冲突响应体 `TaskConflictResponse` = `{ error, existingTask: TaskView }`。
- 产物命名（`SubtitleArtifactNames`）：`segments-<路线>.json` / `…-clean.json` / `…-translate-<lang>.json` 与对应 `subtitles-*.srt`；`TaskView.artifacts` 只列 `*.srt` 与 `segments-*.json`。
- 工作区保留 `source.mp4`（URL 下载与上传同名牌，`TaskService.UPLOAD_SOURCE_FILE_NAME`），经产物端点可取回 → 工作区播放器可用真实媒体。
- 无任务列表端点（只能按 taskNo 查）→ 任务历史不在本轮范围。

### 前端现状

- 骨架已就位：vue-router（`/` 上传、`/workspace/:videoId`、404）、axios 唯一实例、API 层
  `src/api/{tasks,media,types}.ts`（与后端 record 逐字段镜像）、GSAP 统一入口 `src/lib/gsap.ts`、Pinia 已挂载。
- views 全是占位页；`src/assets/tailwind.css` 为空——设计令牌尚未迁入。
- Vite dev 代理 `/api → localhost:8080`（同源相对路径，零 CORS）。

### 原型已验证的设计（行为参照，不搬运）

- 设计令牌：黑白灰单色（`--color-primary` 即黑）+ 深绿氛围梯度 `sage-50…950`（主色 `#3d5a48`，2026-10-02 定稿），组件只准引用 token。
- 上传态：深绿声波 hero 横幅 + 本地文件/链接双模式 + 进度条。
- 工作区态：顶栏（返回/标题/导出）+ 左媒体面板（视频黑场舞台 / 音频封面卡片+可点击波形）+ 右字幕稿（搜索、当前句高亮、点击 seek、自动滚动跟随且用户滚动后暂停 3s）。
- 联动机制：`useTranscriptPlayer`——媒体元素为唯一时间源，rAF 循环让进度平滑。

## Requirements

- **R1 设计令牌**：原型的 `@theme` 令牌与 base 层重写进 `frontend/src/assets/tailwind.css`。
- **R2 上传态**：复刻原型布局；本地文件走 `submitUpload`（axios 上传百分比进度，单请求
  覆写超时避免 15s 全局值误伤大文件），链接走 `submitUrl`；202 后跳 `/workspace/{taskNo}`；
  409 冲突提示并提供跳转 `existingTask.taskNo`；其余提交错误内联展示。
- **R3 工作区状态机**：GET 快照 + SSE 驱动 loading / 进度 / 失败 / 404 / 就绪五态；
  进度卡渲染 `stage.message` 与百分比，并显示提交时刻与已运行时长；失败卡展示
  `errorMessage` 与提交/失败时刻，并可回上传页。日历时间经 dayjs（`@/lib/day` 单例）格式化
  （2026-10-03 用户定调：时间格式化用主流轻量库）。
- **R4 双栏工作区**（就绪态）：媒体面板接真实媒体 `source.mp4`（上传音频按扩展名判定走
  音频卡片形态）；字幕稿渲染产物 `segments-*.json`（原文取 clean 优先，见 design 3.2）；
  搜索、当前句高亮、点击 seek、自动滚动跟随，行为对齐原型。
- **R5 双行双语**（2026-10-03 用户已定）：译文存在时原文在上、译文在下同时展示；未翻译
  条目单行；搜索同时命中原文与译文。
- **R6 翻译入口与产物导出**：任务成功后 header 提供可用的「翻译」按钮（SSE 跟踪
  translationStatus，翻译中禁用）；「产物」面板列出 artifacts 清单并提供下载链接（替代原型
  禁用的导出占位）。
- **R7 GSAP 入场动效**：上传页与工作区就绪态各一组克制入场（design §4），严格遵循
  animation.md 硬规则（context/revert、reduced-motion 降级、data-animate 选择器）。
- **R8 刷新恢复**：工作区刷新后经 GET 快照 + SSE 快照恢复，进度无感（后端已保证，前端
  正确接线即可）。

## Acceptance Criteria

- [ ] AC1 上传本地视频：上传页出现真实上传百分比；提交后跳工作区，SSE 阶段进度推进到成功。
- [ ] AC2 提交 B 站链接同样走通到成功；重复提交同一 URL 时 409 被处理并可跳既有任务。
- [ ] AC3 字幕稿与媒体联动：当前句高亮并自动滚动跟随，点击字幕行 seek，搜索可过滤（含译文）。
- [ ] AC4 翻译：点击「翻译」后按钮态随 translationStatus 变化，完成后字幕稿呈双行双语，
      「产物」面板可下载双语 SRT。
- [ ] AC5 刷新工作区，状态经快照恢复，进度无感。
- [ ] AC6 访问不存在的 taskNo 显示任务不存在引导；FAILED 任务展示后端错误文案。
- [ ] AC7 进度卡显示提交时刻与已运行时长（中文表述）；失败卡显示提交/失败时刻。
- [ ] AC8 `npm run type-check`、`npm run lint`、`npm run build` 全绿。

## Key Decisions

| 决策 | 内容 | 依据 |
|---|---|---|
| D1 译文呈现=双行双语 | 原文/译文同屏双行 | 2026-10-03 用户拍板（三选项比选） |
| D2 工作区播放器用产物端点取 `source.mp4` | 不新增后端端点 | openArtifact 允许工作区内任意文件；Range 支持实测，异常登记后续 |
| D3 原型代码重写不搬运 | 按规范落实命名/类型/目录 | 原型 README 与 directory-structure.md 双重约定 |
| D4 路由参数 `:videoId` → `:taskNo` | 对齐后端契约命名 | 修改无既有消费方，零破坏 |
| D5 进度语义移到工作区 | 上传页只管提交与上传进度 | SSE 阶段事件在工作区消费；上传页职责单一 |
| D6 时间处理分野 | 日历时间（提交/完成时刻、耗时）用 **dayjs**（`lib/day.ts` 单例配置）；媒体时长 `mm:ss` 保持纯函数 | 2026-10-03 用户定调引入库；时长非日历时间，dayjs 无时长 API（≥1h 会静默丢小时位） |
| D7 两级类型组织 | 全局 `src/types/` 收**跨模块共享**类型（契约镜像 `task.ts`/`media.ts` + 跨模块视图模型 `transcript.ts`）；模块私有类型留模块内（就地内联或模块内 `types/`）；`api/types.ts` 迁出后删除；**规范 directory-structure.md / api.md 随本任务同步修订** | 2026-10-03 用户定调：全局共享与模块私有两级管理 |
| D8 baseURL 收敛 | axios 实例 baseURL 来自 `VITE_API_BASE_URL`（`frontend/.env`，缺省回退 `/api`）；request 衍生调用点去 `/api` 前缀；SSE / `<a download>` / 媒体 src 三类非 axios 出口保留全路径（刻意双拼法，已写入注释与 api.md） | 2026-10-03 用户定调（推翻 api.md 原「不引入 VITE_API_BASE_URL」条款，spec 已同步） |
| D9 ApiError 归一化 | 响应**错误**拦截器把错误归一化为 `ApiError`（status / message / existingTask，定义在 request.ts）；三个调用点删本地 isErrorBody 拷贝改读字段；成功路径当时保持透传；api.md 同步 | 2026-10-03 用户定调（质检 P2-1 三份拷贝随之消解） |
| D10 成功解包门面 | 成功拦截器返回 `response.data`；**不导出裸实例**，导出类型化门面 `request = { get, post }`（单泛型位直接返回 `Promise<T>`）；门面边界两处受控断言 `as Promise<T>` 为 type-safety.md no-as 条款唯一例外（axios 1.20 条件类型签名 `AxiosResponseResult` 所迫，零 as 方案已实测排除） | 2026-10-03 用户定调；6 调用点删除手写 `.data` |

## Out of Scope

- 任务列表 / 历史（后端无列表端点）。
- 结构化总结 UI（后端 MVP-4 尚未实现）。
- 工具诊断 `getTools` 的 UI 呈现。
- 媒体流式播放优化端点（ResourceRegion）——仅当 Range 实测失败时登记后续任务。
- 平台字幕直取、说话人分离、时间戳问答（阶段二）。
- 直接搬运原型代码（必须重写并遵守 `.trellis/spec/frontend/`）。
