# 工作区就绪页重写：契约层与已完成任务的页面

> 父任务：`10-21-workspace-fe-rewrite`（源需求集 R1–R6 与任务地图在那）。
> 兄弟任务：`10-22-workspace-run-page`（运行态；依赖本任务）。
> 原型依据：`10-21-workspace-prototype`（已验证通过）。

## Goal

把 `frontend/` 的**契约层**（types / enums / api / utils）整体换到当前后端契约上，
并按已验证原型重建工作区页的「任务已完成」形态：左列三块（播放器占位 / 关键词 / 主题）+ 右侧字幕区。

交付后能做的事：打开一个已跑完的任务，看到真实字幕、真实关键词、真实主题；
搜索、选中、~~逐条改写原文（本地演示）~~（2026-10-24 延期：编辑接口未就绪）；下载产物；
从首页提交新任务并跳到正确的 `taskNo`。

## 依赖

- 依赖 `10-21-workspace-prototype` 的原型验证通过（已满足）。`prototype/` 被 gitignore，
  **要按原型重写，不要搬运原型代码**。
- 不依赖 `10-22-workspace-run-page`；本任务先交付并可独立验收。

## Requirements

- **R1 契约层整体替换**（`frontend/src/`）：
  - `types/task.ts` → `TaskResponse` / `RunResponse` / `TaskRunRef` + 三个请求体；
    旧的扁平 `TaskView`、`TaskArtifact`、`TaskConflictResponse` 一律删掉。
  - `types/subtitle.ts`（新）→ `SubtitleTrack` / `SubtitleContext` / `TranslatedTrack` /
    `ArtifactResponse` / `ArtifactFileResponse` / `LanguageOption` / 视图模型 `SubtitleCue`。
  - `enums/task.ts` → `TASK_RUN_TYPE` / `TASK_RUN_STATUS`（`as const` 运行时值，键镜像后端枚举）。
  - `api/request.ts` → 去掉 `existingTask`，改为 `runRef`；新增 `getAbsolute<T>`（产物 url 是 `/api/...` 全路径，
    直接 `request.get` 会双前缀）。
  - `api/tasks.ts` → 七个端点一次写全（读：快照 / 关键词 / 语种；触发：提交 / 上传 / 翻译 / 重生成）。
  - `api/artifacts.ts`（新）、`api/languages.ts`（新）。
  - `utils/artifacts.ts`（按 type / lang 取产物、取文件 url）、`utils/language.ts`（自由文本语种码归一）、
    `utils/transcript.ts`（原文与译文按下标配对 + 时间戳一致性校验）、`utils/progress.ts`（进度归一，本任务先落地）。

- **R2 按原型重建工作区页**（`views/workspace/`）：
  - 3:2 分栏（`lg` 以下上下堆叠），左列竖排三块，右列字幕区。
  - 顶栏：返回 / 标题 / 「产物」下拉。**不加**视频-音频切换与禁用的导出按钮。
  - 播放器：`aspect-video` 16:9 黑场占位 + 明确「媒体预览暂不可用」，不造假播放器。
  - 关键词区：标题行 + 计数 + 输入框 + 垂直列表（`max-h-60` 可滚动）+ 增删；
    单条 ≤ 50 字、最多 50 条，挡住超限。**本任务不含「重新生成」按钮**（归 B2）。
  - 主题区：只显示 `context.theme`；`degraded: true` 或空主题显示明确空态，不留白。
  - 字幕区：标题 + 计数 + 搜索框 + 字幕列表（时间码 / 原文）；行 hover 显示铅笔图标。
    ~~点击进入该条原文的行内编辑（时间码与译文只读），保存 / 取消，Esc 取消、Ctrl/⌘+Enter 保存。
    保存后必须提示「后端接口未实现，仅本地演示」，不假装持久化。~~
    （2026-10-24 延期：后端编辑接口未就绪，本任务只保留禁用的铅笔占位，行内编辑与本地覆盖不做，
    等接口就绪后再排。）

- **R3 首页只改提交接线**：布局不动。提交 / 上传 / 翻译 / 重生成的返回值一律当 `{taskNo, runNo}` 用，
  跳 `/workspace/<真 taskNo>`；409 的响应体同样是 ref，跳那个任务的页。

- **R4 旧物收掉**（不留双份）：`composables/useTaskEvents.ts`、`stores/task.ts`、`types/transcript.ts`、
  `composables/useTranscriptPlayer.ts`、`views/workspace/components/MediaPane/components/AudioWaveform.vue`、
  `api/media.ts` 与 `types/media.ts`（本就无消费方）。

- **R5 规范**：遵循 `.trellis/spec/frontend/` 全部规范（目录结构、组件、状态、类型、样式、动画、API、质量）。
  特别注意 `type-safety.md` 的两级类型策略与 `noUncheckedIndexedAccess`、
  `api.md` 的唯一实例与「域文件不建 barrel」。

## 本任务不做的（B2 的范围）

SSE 订阅、进度显示、「重新生成」按钮、翻译控件与译文显示、顶部错误条与断线重试。
本任务交付后的中间态：能打开已完成任务、能提交并跳走，但进行中的任务只显示「执行中，请稍后刷新」。

## Acceptance Criteria

- [ ] **AC1** 工作区页布局与原型一致；有意偏离（无视频/音频切换、无导出按钮、播放器占位、
      补失败态）已在 `implement.md` 写明理由。
- [ ] **AC2** 打开一个已完成任务：左列三块与右侧字幕区齐全，数据全部来自真实接口
      （快照 / 产物清单 / 关键词 / 主题 / 语种），无假数据。
- [ ] **AC3** 主题区显示 `context.json` 的 `theme`；`degraded: true` 时显示明确空态而非空白。
- [ ] **AC4** 关键词区显示 `GET /keywords` 的真实列表；可增删、遵守 ≤50 字 / ≤50 条上限；
      增删只改本地清单（本任务不落库，也不提交）。
- [ ] **AC5** ~~字幕行 hover / 键盘聚焦时显示修改图标，点击只编辑该条原文；保存后出现
      「后端接口未实现，仅本地演示」提示，不误导用户。~~
      （2026-10-24 延期：编辑接口未就绪，改为「hover 显示禁用的铅笔占位」，编辑链路不验收。）
- [ ] **AC6** 搜索按原文内容过滤并显示 `命中/总数` 计数；无结果时给出明确空态。
- [ ] **AC7** 首页提交后跳到 `/workspace/<真 taskNo>`；409 跳既有任务。
- [ ] **AC8** 「产物」下拉可下载 CLEANED 的 `subtitles.srt` 与 `segments.json` 等实际存在的文件。
- [ ] **AC9** 任务不存在（404）显示「任务不存在」卡；加载失败显示可重试的错误卡；
      任务处于 RUNNING 时显示「执行中，请稍后刷新」占位。
- [ ] **AC10** `frontend/` 中不再残留扁平 `TaskView`、旧 `/events` 订阅路径、旧产物取值方式与被替换的旧组件；
      `npm run type-check` 与 `npm run lint` 通过。
- [ ] **AC11** 偏离原型的每一处都写进了 `implement.md` 并给了理由。

## Notes

- `type-check` 在本项目是**假绿**（旧类型本地自洽也能过），验收不能只看它；
  必须真的把页面跑起来看。
- 后端起服务按既有口径：显式 JDK 21；测试红着用 `-Dmaven.test.skip=true` 启动。
- 契约细节（端点、DTO 形状、七个坑）见本任务 `design.md`。
