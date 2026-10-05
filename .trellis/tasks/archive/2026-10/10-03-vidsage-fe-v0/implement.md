# 前端 v0 执行计划

> 前置阅读顺序：`prd.md` → `design.md` → 本文。实现全程受 `.trellis/spec/frontend/` 约束
> （动手前按 trellis-before-dev 流程加载）。

## 执行清单（按序）

### 第 1 步：设计令牌与基础设施

- [ ] 1.1 `src/assets/tailwind.css`：迁入原型 `@theme` 令牌（黑白灰 9 枚 + sage 梯度 7 枚）
      与 `@layer base`，注释保留「深绿仅氛围素材」的约束语义（重写，不搬运）。
- [ ] 1.2 `src/utils/format.ts`：`formatTime`（时长秒 → mm:ss，注意是时长口径、
      与 dayjs 无关，见 design §2.1）、`formatBytes`（产物体积展示）。每函数带 JSDoc
      （quality-guidelines 硬要求）。
- [ ] 1.3 安装 `dayjs` 并建 `src/lib/day.ts` 单例：`locale('zh-cn')` + `relativeTime`
      插件，`export default dayjs`；组件一律 `import dayjs from '@/lib/day'`，禁止直接
      `from 'dayjs'`（模式同 `lib/gsap.ts`）。
- [ ] 1.4 `src/routes/index.ts`：`:videoId` → `:taskNo`，注释同步改；页面 props 同步。

### 第 2 步：契约类型包与 API 层

- [ ] 2.1 建 `src/types/` 全局共享类型包（D7 两级策略，自 `api/types.ts` 迁出、该文件
      删除）：`types/task.ts`（TaskView / SubmitUrlRequest / TaskConflictResponse +
      新增 `StageState` / `StageEvent` / `TaskProgressMessage` / `TranscriptionSegment` /
      `TranslatedSegment`）、`types/media.ts`（ExternalToolReport）、`types/transcript.ts`
      （`TranscriptCue` 视图模型，供 store / useTranscriptPlayer / 两面板共享）。
      逐字段镜像后端 record；包文件头注明两级策略（跨模块共享才准入，模块私有留模块内）。
- [ ] 2.2 `src/api/tasks.ts` / `src/api/media.ts`：类型导入改 `@/types/task`、
      `@/types/media`；`submitUpload(file, onUploadProgress?)`——axios config 加
      `onUploadProgress` 换算百分比回调 + 该请求 `timeout: 0`（覆写全局 15s）。

### 第 3 步：状态与组合式逻辑

- [ ] 3.1 `src/composables/useTaskEvents.ts`：EventSource 订阅 `progress` 事件，
      JSON.parse 为 `TaskProgressMessage`；暴露 `latest` ref / `subscribe(taskNo)` /
      `close()`；终态判定（主状态终态且 translationStatus !== 'RUNNING'）后自动 close；
      `onScopeDispose` 清理。
- [ ] 3.2 `src/composables/useTranscriptPlayer.ts`：重写原型逻辑——attach/detach、
      rAF 循环、seek 钳制、toggle、`activeIndex`（按 startMs/endMs 匹配）、`onScopeDispose`
      清理；`Cue` 类型直接用 `@/types/transcript` 的 `TranscriptCue`（2.1 已建，
      5.3 定稿字段）。
- [ ] 3.3 `src/stores/task.ts`（Pinia setup store，id `'task'`）：state `taskView` /
      `progress` / `cues` / `loadError`；computed `isRunning` / `isSucceeded` / `isFailed` /
      `isTranslating` / `hasTranslation`；actions：`load(taskNo)`（GET 快照 + 终态直接建
      cues）、`subscribe()`（SSE，SUCCEEDED 时拉产物建 cues）、`translate()`、
      `fetchCues()`（产物选择纯函数 + 下标合并，见 design 3.2）。所有成员注释 + return。

### 第 4 步：公共组件

- [ ] 4.1 `src/components/BrandLogo.vue`：重写原型 BrandLogo（svg + 字标），跨页复用。

### 第 5 步：上传页

- [ ] 5.1 `src/views/upload/index.vue`：hero 声波横幅（内联 SVG + 伪随机波形 computed）、
      本地/链接双模式叠放切换、拖放高亮、`fileInput` 隐藏域。
- [ ] 5.2 提交接线：本地文件 `submitUpload` + 上传百分比进度态；链接 `submitUrl`；
      202 → `router.push`；409 → 冲突提示 + 「查看该任务」；其余错误内联文案。
- [ ] 5.3 `TranscriptCue` 类型定稿（`src/types/transcript.ts`，见 2.1），3.2 收口。
- [ ] 5.4 GSAP 入场（design §4 #1）：`gsap.context(root)` + matchMedia 降级 +
      `data-animate` 选择器 + `onUnmounted revert`。

### 第 6 步：工作区页

- [ ] 6.1 `views/workspace/index.vue`：状态机壳（loading / 进度卡 / 失败卡 / 404 卡 /
      就绪双栏），进度卡渲染 `progress.stage.message + progress`，并经 `@/lib/day` 显示
      「已提交 hh:mm · 已运行 x 分钟」；失败卡显示提交/失败时刻（design §2.1）。
- [ ] 6.2 `components/WorkspaceHeader.vue`：返回（router.push `/`）+ 标题（truncate）+
      「翻译」按钮（状态机：就绪可点 / RUNNING 禁用 / 已译隐藏或对勾）+ 「产物」弹出面板
      （`<a href="/api/tasks/{taskNo}/artifacts/{name}" download>` 列表）。
- [ ] 6.3 `components/MediaPane.vue`：视频舞台 / 音频卡片双形态（判定规则见 design 3.3），
      attach 到 useTranscriptPlayer；波形点击 seek。
- [ ] 6.4 `components/TranscriptPane.vue`：搜索（匹配原文+译文）、双行双语渲染（design
      3.6）、当前句高亮 + 左指示条、自动滚动跟随（用户滚动 3s 暂停）、点击行 seek、
      空态文案。
- [ ] 6.5 GSAP 入场（design §4 #2），同款硬规则。

### 第 7 步：收尾

- [ ] 7.1 `views/not-found/index.vue` 文案核对（保持既有即可，不强制改）。
- [ ] 7.2 spec 同步（Trellis 3.3）：`.trellis/spec/frontend/directory-structure.md` 与
      `api.md` 中「types.ts 在 api/ 内」的约定改为 D7 两级类型策略（全局 `src/types/`
      收跨模块共享类型 + 模块私有类型留模块内），含目录树与目录职责表；避免后续会话按
      旧规范把结构改回。
- [ ] 7.3 全量检查（见下）通过后提交。
- [ ] 7.4 执行报告（2026-10-03 用户要求）：任务收尾时输出「每个子代理的 token 消耗 +
      各执行步骤的墙钟耗时」汇总表，随完成汇报一并列出。

## 验证命令

```bash
cd frontend
npm run type-check   # vue-tsc --build
npm run lint         # oxlint + eslint（--fix 会改文件，提交前看 diff）
npm run build        # 类型检查 + 构建并行
```

端到端手验（需后端在 localhost:8080 运行，`backend/` 下 `mvn spring-boot:run`）：

1. 上传一个小体积本地视频 → 上传百分比可见 → 跳工作区 → SSE 阶段推进到成功 →
   字幕稿 + 播放器联动（点击字幕 seek、当前句跟随、搜索过滤）。
2. 提交一条 B 站链接 → 同样走通；再提交同链接 → 409 → 跳既有任务。
3. 点「翻译」→ 按钮态变化 → 完成后字幕稿双行双语 → 「产物」面板可下载双语 SRT。
4. 工作区刷新 → 快照恢复无感。
5. `curl -si -H "Range: bytes=0-1023" http://localhost:8080/api/tasks/<taskNo>/artifacts/source.mp4 | head -8`
   ——记录是否 206；非 206 则在任务 journal 登记，随 finish-work 决定后续任务。
6. 直接访问 `/workspace/不存在id` → 任务不存在引导卡。

## 风险文件与回滚点

| 文件 | 风险 | 回滚 |
|---|---|---|
| `package.json` | 新增 `dayjs` 依赖（约 2kB gzip，零传递依赖，低） | 随任务提交整体 revert |
| `src/types/` 迁移 | 动既有 `api/types.ts`（消费方仅 tasks/media 两文件，低）；**规范若漏改，后续会话会按旧约定改回**——7.2 必须做 | 同上 |
| `src/routes/index.ts` | 参数改名（当前无其他消费方，低） | 同上 |
| `src/api/tasks.ts` | 函数签名变更（唯一调用方是本任务上传页，低） | 同上 |
| `src/assets/tailwind.css` | 令牌拼写错误会全站无色 | 同上 |

每步一个可编译增量：1–4 步完成时项目仍应通过 type-check（views 还是占位页）；
5–6 步各自结束时也保持三命令全绿。

## task.py start 前检查

- [ ] `prd.md` / `design.md` / `implement.md` 三件齐且相互引用一致。
- [ ] `implement.jsonl` / `check.jsonl` 已含真实规范条目（非占位）。
- [ ] 用户已批准最终规划摘要。
