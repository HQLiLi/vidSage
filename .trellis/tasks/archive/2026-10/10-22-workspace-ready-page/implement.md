# 执行计划：工作区就绪页重写（契约层 + 已完成任务页面）

> 任务：`10-22-workspace-ready-page`（B1）
> 设计依据：本任务 `design.md`（所有契约、路径、判定口径都在那，本文只讲怎么做与怎么验）
> 执行者须知：`prd.md` 是需求、`design.md` 是设计、本文是步骤。三者冲突时以 `design.md` + 后端源码为准，并回来改文档。

---

## 0. 前提与固定口径

### 0.1 分支与范围

- 从 `dev` 开功能分支：`git switch -c feature/workspace-ready-page`（`task.json` 的 `base_branch` 是 `dev`）。
- **只碰 `frontend/src/`、`frontend/src/assets/main.scss`**，不改 `backend/`、不改 `prototype/`、不改 `.trellis/spec/`（规范订正归 B2 R6）。
- `prototype/` 已被 gitignore，本地没有就跑不起来对照；需要对照时先确认它在本机存在，**但不要从它复制代码进去**。

### 0.2 命令（Git Bash）

前端：

```bash
cd frontend
npx vue-tsc --build --force   # 绕开 tsBuildInfo 的增量假绿
npm run lint                  # oxlint + eslint
npm run build                 # type-check + build-only 并行
npm run dev                   # 起页面，http://localhost:5173
```

后端（按仓库既有口径，必须显式 JDK 21）：

```bash
cd backend
JAVA_HOME=/f/java_soft/java21 mvn clean test-compile       # 编译判定一律 clean，不用增量
JAVA_HOME=/f/java_soft/java21 mvn -q spring-boot:run -Dmaven.test.skip=true
```

- 后端测试若红着，只用 `-Dmaven.test.skip=true` 起（`-DskipTests` 不够）。
- 冒烟实例的 TaskStop 杀不掉子 JVM，收尾按 PID `taskkill`。

### 0.3 一条铁律

`npm run type-check` 通过 ≠ 接对了后端。**每一个 AC 都要在真实页面上看**，网络面板要看到真实请求。

---

## 1. 有序执行清单

每一步都有「完成判据」。判据不过不进下一步。

### 步骤 1：契约枚举与类型

**做什么**

- 重写 `frontend/src/enums/task.ts`：只留 `TASK_RUN_TYPE`、`TASK_RUN_STATUS`（`as const`）。
- 新增 `frontend/src/enums/subtitle.ts`：`ARTIFACT_TYPE`（`as const`）——`ArtifactType` 有比较消费，按 `type-safety.md:92` 进 `enums/`，类型联合在 `types/subtitle.ts` 派生。
- 重写 `frontend/src/types/task.ts`：`TaskInputType` / 由枚举派生的 `TaskRunType`、`TaskRunStatus` / `RunResponse` / `TaskResponse` / `TaskRunRef` / `SubmitTaskRequest` / `TranslationRequest` / `RegenerateSubtitlesRequest`，外加进度流事件的形状 `StageProgress` / `TaskRunProgress`（`utils/progress.ts` 要用，放一处免得两份定义漂移）。旧的 `TaskView`、`TaskArtifact`、`TaskConflictResponse`、`TaskProgressMessage`、`SubtitleArtifactFile`、`TranslatedArtifactFile` 全删。
- 新增 `frontend/src/types/subtitle.ts`：`design.md §4.3` 列的全部类型（`ArtifactType` 从 `enums/subtitle.ts` 派生，不在这里裸写联合）。
- 删除 `frontend/src/types/transcript.ts`、`frontend/src/types/media.ts`。

**涉及文件**：`frontend/src/enums/{task,subtitle}.ts`、`frontend/src/types/{task,subtitle}.ts`、(删) `types/{transcript,media}.ts`

**验证**

```bash
cd frontend && grep -rn "TaskView\|TaskConflictResponse\|TaskArtifact\|TranscriptCue\|ExternalToolReport" src/types src/enums
```

**完成判据**：grep 无输出；两个新文件里没有 `any`、没有 TS `enum`；`import` 全用 `@/`；可空字段都是 `| null` 而不是 `?`。

> 此步之后仓库整体编译是红的（旧视图还在 import 旧类型），正常，不在这里停下来修。

---

### 步骤 2：API 门面

**做什么**

- 重写 `frontend/src/api/request.ts`：`ApiError.existingTask` → `runRef`；409 的判定改成 `{taskNo: string, runNo: number}`；409 文案固定「该任务已有一次执行正在进行」；新增 `getAbsolute<T>`（逐请求 `baseURL: ''`）。实例、拦截器、门面 `as Promise<T>` 的位置都不动。
- 重写 `frontend/src/api/tasks.ts`：`submitUrl` / `submitUpload(file, keywords?, onUploadProgress?)` / `getTask` / `getTaskKeywords` / `translateTask` / `regenerateSubtitles`。上传保留 `timeout: 0` 与百分比换算；`keywords` 放进 FormData（**不能走查询参数**：axios 1.x 默认把数组序列化成 `keywords[]=a`，后端 `@RequestParam("keywords")` 绑不上，词会被静默丢掉）。
- 新增 `frontend/src/api/artifacts.ts`：`listArtifacts` / `fetchArtifactJson(url)`。
- 新增 `frontend/src/api/languages.ts`：`getLanguages`。
- 删除 `frontend/src/api/media.ts`。

**涉及文件**：`frontend/src/api/{request,tasks,artifacts,languages}.ts`、(删) `api/media.ts`

**验证**

```bash
cd frontend && grep -rn "existingTask\|/events\|source\.mp4\|api/media" src/api
```

**完成判据**：grep 无输出；`request.ts` 里仍然只有一个 `axios.create`；`getAbsolute` 不导出裸实例；四个域文件都不建 `index.ts`。

---

### 步骤 3：纯函数层

**做什么**

- 新增 `frontend/src/utils/artifacts.ts`：`pickArtifact`、`artifactFileUrl`（必须判空）。
- 新增 `frontend/src/utils/language.ts`：`normalizeLanguageCode`（五级匹配，`design.md §5.2`）。
- 重写 `frontend/src/utils/transcript.ts`：`BuildCuesResult` + `buildCues(cleaned, translated)`（按下标配对、时间戳校验、长度不等算 mismatch）。
- 新增 `frontend/src/utils/progress.ts`：`RunProgressSnapshot`、`RECLEAN_STAGE_BASE = 70`、`normalizeRunProgress`。放 `utils/` 不放 `lib/`：`lib/` 是第三方库的单例入口（`directory-structure.md:57`）。

**涉及文件**：`frontend/src/utils/{artifacts,language,transcript,progress}.ts`

**验证**：`cd frontend && npx vue-tsc --build --force`（此时应只剩视图层的报错，`utils/` 本身零报错）。

**完成判据**：每个导出函数都有中文注释（`quality-guidelines.md:30-63`）；函数名动词开头；没有 `as` 强转；所有索引访问都有兜底。

---

### 步骤 4：store

**做什么**

- 新建 `frontend/src/stores/workspace.ts`、`stores/workspaceSubtitles.ts`、`stores/workspaceKeywords.ts`
  三个 Setup Store，state / computed / action 按 `design.md §6`。
- 删除 `frontend/src/stores/task.ts`、`frontend/src/composables/useTaskEvents.ts`、`frontend/src/composables/useTranscriptPlayer.ts`。

**涉及文件**：`frontend/src/stores/{workspace,workspaceSubtitles,workspaceKeywords}.ts`、(删) `stores/task.ts`、`composables/useTaskEvents.ts`、`composables/useTranscriptPlayer.ts`

**验证**

```bash
cd frontend && grep -rn "useTaskStore\|useTaskEvents\|useTranscriptPlayer" src
```

**完成判据**：

- grep 无输出；
- `open` / `refresh` / `reset` 有注释，`addKeyword` 的五种返回都有注释；
- `phase` 的判定顺序与 `design.md §6.2` 的表逐条一致；
- 没有 SSO / EventSource / 定时器（B1 不碰流）。

---

### 步骤 5：全局样式

**做什么**：把 `.overlay-scrollbar` 写进 `frontend/src/assets/main.scss`（规则照 `prototype/src/assets/tailwind.css:40-76` 重写，不复制注释里的日期）。

**涉及文件**：`frontend/src/assets/main.scss`

**完成判据**：桌面端左列与字幕列表默认看不到滚动条轨道；鼠标移进/键盘焦点进入时出现 6px 半透明滑块。

---

### 步骤 6：工作区组件

**做什么**

- 重写 `views/workspace/components/WorkspaceHeader.vue`（返回 / 标题 / `ArtifactMenu`；留下 B2 进度胶囊的位置注释）。
- 新增 `views/workspace/components/ArtifactMenu.vue`（分组清单 + 下载 + 外部点击收起）。
- 重写 `views/workspace/components/MediaPane.vue`（扁平文件，16:9 占位）并删除整个 `MediaPane/` 目录（含 `AudioWaveform.vue`）。
- 新增 `KeywordPane.vue` / `ThemePane.vue` / `StatusCard.vue`。
- 重写 `TranscriptPane.vue`（搜索 / 选中 / 禁用的编辑入口占位；不留 TranslationBar）。
- 重写 `views/workspace/index.vue`（状态机 + 3:2 分栏 + 入场动效 + 卸载 `reset()`）。

**涉及文件**：`frontend/src/views/workspace/index.vue`、`frontend/src/views/workspace/components/*.vue`、(删) `views/workspace/components/MediaPane/`

**验证**

```bash
cd frontend && ls src/views/workspace/components
# 期望：ArtifactMenu.vue KeywordPane.vue MediaPane.vue StatusCard.vue ThemePane.vue TranscriptPane.vue WorkspaceHeader.vue
cd frontend && npx vue-tsc --build --force
```

**完成判据**：

- `components/` 下没有子目录；
- 每个 `<button>` 都有 `type`，每个 `v-for` 都有 `key`（`component-guidelines.md:69,76`）；
- 字幕行的铅笔按钮在 hover 与 Tab 聚焦时都出现，且不挤动正文；
- 组件读到的是 store，不再接受 `player` / `cues` 之类的外部注入。

---

### 步骤 7：首页提交接线

**做什么**（只改提交，布局一行不动）

- `frontend/src/views/upload/index.vue`：`TaskView` → `TaskRunRef`；`runSubmit` 的入参与跳转取 `ref.taskNo`。
- `toSubmitError`：`err.runRef` 存在 → 直接 `router.push({ name:'workspace', params:{ taskNo: ref.taskNo } })`，不再渲染「查看该任务」卡片；其余错误保持内联文案。
- `submitUpload(file, undefined, onProgress)`（第二参数是 keywords，B1 不传）。

**涉及文件**：`frontend/src/views/upload/index.vue`

**验证**：`cd frontend && npx vue-tsc --build --force && npm run lint`

**完成判据**：模板结构（HeroWave / 卡片 / 双模式 / 上传进度条）与改动前一致；`SubmitError` 只剩 `{kind:'message'}`（或保留 conflict 分支但立即跳转——二选一，选了要在代码注释里说明）。

> 默认选择：**409 直接跳**既有任务页（PRD R3「409 跳既有任务」）。备选：保留卡片点「查看该任务」——但那会多一次点击，且和 AC7 的字面要求不符。

---

### 步骤 8：收尾与全量 grep

**做什么**

- 删掉所有旧名残留：`grep` 一遍，逐个确认。
- 检查注释：新写的导出函数都有注释；不留「迁移前…」「设计决策 N」这类历史话（`quality-guidelines.md:30-63`）。
- 确认 `frontend/src/utils/format.ts` 仍被使用（时间码 / 体积），没有变成孤儿。
- 确认 `frontend/src/lib/gsap.ts` 是唯一的 gsap 入口。

**验证**

```bash
cd frontend
grep -rn "TaskView\|TaskConflictResponse\|existingTask\|/events\|source\.mp4\|TranscriptCue\|useTaskEvents\|useTranscriptPlayer\|api/media\|types/media\|AudioWaveform" src
grep -rn "from 'gsap'" src
grep -rn "axios" src | grep -v "src/api/request.ts"
```

**完成判据**：三条 grep 全部无输出（第三条允许 `package.json` 之外无命中）。

---

### 步骤 9：编译与 lint

```bash
cd frontend
npx vue-tsc --build --force
npm run lint
npm run build
```

**完成判据**：三条命令 exit 0，且 `build` 真的产出了 `dist/`。若 `build` 的 `type-check` 与 `--force` 结果不一致，以 `--force` 为准查。

---

### 步骤 10：端到端实跑（AC 验收）

1. 起后端：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q spring-boot:run -Dmaven.test.skip=true`。
2. 起前端：`cd frontend && npm run dev`。
3. 造一个已完成任务：在首页上传一个小视频/音频（或提交一个短视频链接），等到工作区变成就绪双栏。**记下它的 `taskNo`**，后面 AC2–AC6、AC8 都用它。
4. 按 §4 的表格逐条验，边验边留证据（截图 + Network 面板）。
5. 验完杀后端：`netstat -ano | grep 8080` 找 PID，再 `taskkill //PID <pid> //F`（子 JVM 也要杀）。

---

## 2. 评审门

| 门 | 时点 | 要看什么 | 不过怎么办 |
|---|---|---|---|
| G0 设计评审 | 动手前 | `design.md` 的契约、phase 判定、偏离清单、B1/B2 边界 | 回改设计，不动代码 |
| G1 契约层 | 步骤 1–4 完成 | 旧名 grep 干净；类型逐字段对得上后端 DTO；store 的 `phase` 表与文档一致 | 回到对应步骤，不要「先让页面跑起来再说」 |
| G2 页面形态 | 步骤 6–7 完成 | 打开已完成任务能看到真实数据；布局与原型同形；偏离都在清单里 | 改组件或补偏离说明 |
| G3 全绿 | 步骤 8–9 | 三条 grep + 三条命令全过 | 修到过 |
| G4 AC 走查 | 步骤 10 | §4 表格逐条打勾并附证据 | 未过的 AC 不许标完成 |

**每个门停一次，不要一口气冲到 G4。** 这是 `AGENTS.md` 里「改完即停等审查」的口径。

---

## 3. 回滚点

按步骤分组提交，每组一笔，能单独 `git revert`：

| 提交 | 包含 | 回滚影响 |
|---|---|---|
| 1 | 步骤 1（enums + types） | 仓库编译红，需连提交 2–6 一起回滚 |
| 2 | 步骤 2–3（api + utils/lib） | 同上 |
| 3 | 步骤 4–5（store + 全局样式） | 页面组件仍在旧 store 上，编译红 |
| 4 | 步骤 6（工作区组件 + 页面） | 回到旧工作区页（但契约层已是新的，仍红） |
| 5 | 步骤 7（首页接线） | 首页编译红 |
| 6 | 步骤 8–9 的清理与格式 | 可单独回滚 |

真正的安全回滚点是「提交 1 之前」：分支没动过 `dev`，`git reset --hard dev` 即可。**不要**在中间态往 `dev` 合并。

---

## 4. 验收对照表（AC1–AC11）

每条都写「怎么验 / 看到什么算过」。AC9 的三种态要分别造：

- 404：地址栏把 `taskNo` 改成一个不存在的 32 位串。
- 加载失败：后端停掉再进工作区（或把代理指到空端口）。
- RUNNING：提交一个大一点的文件，趁它没跑完立刻进工作区。

| AC | 怎么验 | 看到什么算过 |
|---|---|---|
| **AC1** 布局与原型一致，偏离有理由 | 1280px 以上开一个已完成任务；左边三块、右边字幕区；再缩到 `lg` 以下 | 左列上到下是 16:9 占位 / 关键词 / 主题，右列字幕区；`lg` 以下变上下堆叠；顶栏只有返回 / 标题 / 产物。偏离项与 §5 的清单一一对应，没有清单外的差异 |
| **AC2** 数据全部来自真实接口 | Network 面板过滤 `/api`，进页面 | 依次看到 `GET /api/tasks/{taskNo}`、`GET /api/tasks/{taskNo}/artifacts`、`GET /api/tasks/{taskNo}/keywords`、`GET /api/languages`、`GET /api/tasks/{taskNo}/artifacts/CLEANED/segments.json`、`.../context.json`；字幕条数与 `segments.json` 的 `segments.length` 一致；关键词与 `/keywords` 返回一致；没有一条 mock 数据 |
| **AC3** 主题区显示真实 theme / degraded 空态 | 正常任务看主题文字；找一个 `context.degraded: true` 或 `theme: ""` 的任务（没有就手动改库/改产物验证一次） | 正常：显示 `context.theme` 原文；degraded/空：显示「未能提取主题」卡片，不是空白 |
| **AC4** 关键词真实列表 + 增删 + 上限 | 数一下 UI 条数与 `/keywords` 返回；输入 51 字、加第 51 条、重复添加、删一条 | 初始与接口一致；51 字被拒并提示「单条关键词最多 50 字。」；第 51 条被拒并提示「最多添加 50 条关键词。」；删除立即生效；全程**没有**任何写接口请求（Network 里没有 POST） |
| **AC5**（2026-10-24 延期）hover 出铅笔，编辑入口为禁用占位 | 鼠标移到某行 | 铅笔出现且不挤动文字；按钮 `disabled`，`title` 为「编辑功能暂未开放」；页面上没有 textarea / 保存 / 取消；Network 中没有 PATCH/PUT。行内编辑与本地覆盖不验收 |
| **AC6** 搜索按原文过滤 + 命中计数 + 空态 | 搜一个确定存在的词、一个确定不存在的词、清空 | 计数从 `N/N 条` 变成 `M/N 条`（只按原文匹配）；无结果显示「没有匹配「…」的字幕」；清空恢复全量 |
| **AC7** 首页提交跳真 taskNo；409 跳既有任务 | 首页提交 URL / 本地文件；再对同一来源重复提交一次 | 第一次地址栏是 `/workspace/<响应里的 taskNo>`（与 Network 里 202 响应的 `taskNo` 逐字一致）；第二次（409）也跳到响应里的 `taskNo`，不停在错误卡 |
| **AC8** 产物下拉可下载真实文件 | 点「产物」，逐个点 CLEANED 的 `字幕文件 (SRT)` 与 `字幕段落 (JSON)` | 下拉按 ORIGINAL / CLEANED / TRANSLATED 分组，只列真实存在的文件位；下载得到非空文件，SRT 是 UTF-8 文本、JSON 能解析；Network 里 url 是 `/api/tasks/...` 单前缀（不是 `/api/api/...`） |
| **AC9** 404 / 加载失败 / RUNNING 三种卡 | 见本节开头三种造法 | 404：「任务不存在」+ 任务号 + 返回上传；加载失败：「加载失败」+ 具体文案 + 重试（点了会重新发 GET）+ 返回上传；RUNNING：「执行中，请稍后刷新」+ 刷新 + 返回上传。三种都不出现空白页 |
| **AC10** 无残留 + type-check/lint | 跑 §1 步骤 8 的三条 grep 与步骤 9 的三条命令 | 三条 grep 无输出；`vue-tsc --build --force` / `lint` / `build` 全 exit 0 |
| **AC11** 偏离都写进 implement.md | 对照 §5 | §5 覆盖了实现里每一处与原型不同的地方，且理由成立 |

---

## 5. 与原型的偏离清单（AC1 / AC11 的判定表）

| # | 原型 | B1 实际 | 理由 |
|---|---|---|---|
| 1 | 视频 / 音频切换 | 无 | 后端没有媒体端点，切换不改变任何东西 |
| 2 | 禁用的「导出 SRT」 | 无 | PRD 明确去掉；真导出走产物下载 |
| 3 | 可播放的 demo 媒体（视频黑场 / 音频卡 + 波形） | 16:9 黑场 + 「媒体预览暂不可用」 | 无媒体源；不留 `source.mp4` 死链、不造假播放器 |
| 4 | 关键词区「重新生成」 | 无（留按钮位） | B2 范围 |
| 5 | 运行中禁用关键词输入 | 无 | B1 没有运行中的双栏页 |
| 6 | 主题区「正在生成 / 更新中」 | 无 | 无运行态 |
| 7 | 语言选择 / 翻译按钮 / 译文第二行 / 「已翻译为…」 | 无（留 TranslationBar 位置） | B2 范围 |
| 8 | 点击字幕行 seek + 当前句高亮 + 自动滚动 | 点击只做本地选中高亮；行容器不再是 `role="button"`，键盘操作走行尾修改按钮 | 无媒体元素，seek 与「当前句」没有时间源；行上没有键盘动作，挂 button 角色会让内嵌按钮非法嵌套 |
| 9 | 搜索匹配原文 + 译文 | 只匹配原文 | B1 不显示译文，匹配不可见内容会让计数对不上 |
| 10 | `?demo=processing` 等调试入口 | 无 | 调试开关不进正式页面 |
| 11 | 无失败 / 中断 / 任务不存在 / 加载失败态 | 补 StatusCard 四态 | 真实后端会失败；父任务裁定补最小失败态，AC9 要求 |
| 12 | 无进度胶囊 | 无（留位置） | B2 加 |
| 13 | 空关键词时按钮改「清空并重新生成」 | 无此按钮 | 同 #4 |
| 14 | 行内编辑（textarea / 保存取消 / 本地覆盖） | 只保留禁用的铅笔占位 | 后端没有改单条字幕的接口；2026-10-24 裁为延期 |

---

## 6. 交付物清单

**新增（16）**

- `frontend/src/types/subtitle.ts`
- `frontend/src/enums/subtitle.ts`、`frontend/src/enums/workspace.ts`
- `frontend/src/api/artifacts.ts`、`frontend/src/api/languages.ts`
- `frontend/src/utils/artifacts.ts`、`frontend/src/utils/language.ts`
- `frontend/src/utils/progress.ts`
- `frontend/src/stores/workspace.ts`、`workspaceSubtitles.ts`、`workspaceKeywords.ts`
- `frontend/src/views/workspace/components/ArtifactMenu.vue`
- `frontend/src/views/workspace/components/KeywordPane.vue`
- `frontend/src/views/workspace/components/ThemePane.vue`
- `frontend/src/views/workspace/components/StatusCard.vue`

**重写（10）**

- `frontend/src/enums/task.ts`、`frontend/src/types/task.ts`
- `frontend/src/api/request.ts`、`frontend/src/api/tasks.ts`
- `frontend/src/utils/transcript.ts`
- `frontend/src/views/workspace/index.vue`
- `frontend/src/views/workspace/components/WorkspaceHeader.vue`
- `frontend/src/views/workspace/components/MediaPane.vue`（原 `MediaPane/index.vue`）
- `frontend/src/views/workspace/components/TranscriptPane.vue`
- `frontend/src/views/upload/index.vue`（仅提交接线）

**删除（7）**

- `frontend/src/composables/useTaskEvents.ts`
- `frontend/src/composables/useTranscriptPlayer.ts`
- `frontend/src/stores/task.ts`
- `frontend/src/types/transcript.ts`
- `frontend/src/types/media.ts`
- `frontend/src/api/media.ts`
- `frontend/src/views/workspace/components/MediaPane/`（整个目录，含 `components/AudioWaveform.vue`）

**样式（1）**

- `frontend/src/assets/main.scss`（补 `.overlay-scrollbar`）

**证据（随任务目录提交）**

只留 5 张关键截图，其余过程证据用文字记录在 §8：

- AC1 的 1280px 就绪页 + 800px 堆叠截图。
- AC3 的 degraded 空态截图。
- AC5 的铅笔占位截图（按钮禁用，无编辑态）。
- AC9 的 404 卡截图。
- AC2 的 Network 六请求、AC8 的产物链接与下载大小、加载失败 / RUNNING 卡、grep 与命令结果，
  以 §8 的文字记录为准，不另存截图。

---

## 7. 收尾检查

- [ ] `git status` 里没有 `frontend/dist/`、没有 `prototype/`、没有 `backend/data/` 的意外改动。
- [ ] 没有手工格式化痕迹（跑了 `npm run format` 或至少 `npm run lint`）。
- [ ] 新写函数/action/composable 都有注释，注释是白话，不写日期与任务号。
- [ ] 任务目录里 `design.md` / `implement.md` 的偏离清单与实际实现一致（改了实现就回来改文档）。
- [ ] 已通知 B2：共享契约按本文落地，B2 在 `stores/workspace.ts`（运行态）与
      `workspaceSubtitles.ts`（翻译态）上做增量。

---

## 8. 实测记录（2026-10-22）

环境：后端 `spring-boot:run`（JDK 21 / `-Dmaven.test.skip=true`）跑在 8080，前端 `npm run dev` 跑在 5173。
用库里已有的跑完任务 `322b56f527dc4bb788a4c7c1c578edfb`（MAIN SUCCEEDED，READY 的 ORIGINAL + CLEANED）验就绪态；
用 `https://example.invalid/前端重写冒烟` 提交的新任务 `b905f95c912d4a0eaafd0d2816b12f84` 验提交与失败态
（该链接 yt-dlp 秒失败，不触发 ASR / LLM，不耗 API 额度；库里会留一条失败任务，可手工清）。

| AC | 实测 | 证据 |
|---|---|---|
| AC1 布局 | 1280px 左三块右字幕；800px 上下堆叠。顶栏只有返回 / 标题 / 产物 | `evidence/b1-ready-1280.png`、`evidence/b1-narrow-800.png` |
| AC2 数据来自真实接口 | Network 面板正好 6 条 GET：`/api/languages`、`/api/tasks/{taskNo}`、`/artifacts`、`/artifacts/CLEANED/segments.json`、`/artifacts/CLEANED/context.json`、`/keywords`。全部单 `/api` 前缀，字幕 8 条与 `segments.length` 一致 | 浏览器 Network 记录 |
| AC3 主题 | 显示 `context.theme` 原文（真实摘要） | `evidence/b1-ready-1280.png` |
| AC4 关键词 | 真实读回空清单；加「沟通技巧」后计数 1/50 并出现在列表里；51 字被拒并提示「单条关键词最多 50 字。」；全程零写请求 | `evidence/b1-ready-1280.png` |
| AC5 行级编辑 | 2026-10-24 延期：改为 hover 出现禁用的铅笔占位，无编辑态；Network 无 PATCH/PUT | `evidence/review-2026-10-24-*.png`（2026-10-24 复审） |
| AC6 搜索 | 搜「方案」→ 计数 1/8 条并只留命中行；清空后恢复全量 | 浏览器交互记录 |
| AC7 首页提交 | `POST /api/tasks` → 202 → 跳 `/workspace/b905f95c912d4a0eaafd0d2816b12f84`，与响应里的 taskNo 逐字一致 | `evidence/b1-submitted.png` |
| AC8 产物下载 | 下拉按 ORIGINAL / CLEANED 分组，列真实文件位与体积，地址是单前缀全路径 | `evidence/b1-artifact-menu.png` |
| AC9 三种卡 | 404→「任务不存在」；RUNNING→「执行中，请稍后刷新」；FAILED→「任务处理失败」+ 后端失败原因 + 结束时刻 | `evidence/b1-404.png`、`evidence/b1-submitted.png`、`evidence/b1-run-failed-fixed.png` |
| AC10 无残留 + 编译 | 三条 grep 无输出；`vue-tsc --build --force` / `lint` / `build` 全 exit 0 | 见 §1 步骤 8–9 |
| AC11 偏离清单 | §5 的 14 条与实现一致 | 本文件 §5 |

**实测中发现并修掉的一个 bug**：失败 / 中断且没有 CLEANED 产物时，store 会写 `loadError`，
把 `phase` 抢到 `error` 分支，页面显示成「加载失败 / 这次执行没有产出可用的字幕」，
而不是「任务处理失败 + 后端给的失败原因」。改法：只有「跑成功却没产物」才写 `loadError`，
失败 / 中断不写，让 `phase` 走 `run-failed`，失败原因由失败卡自己从 `lastRun.errorMessage` 取。

### 8.1 质量检查后的修复（2026-10-23）

`trellis-check` 报回 1 个 P0、3 个 P2 与 5 处规范失实，已全部处理：

| 项 | 处理 |
|---|---|
| **P0** `submitUpload` 的 `keywords` 走查询参数会被 axios 序列化成 `keywords[]=a`，后端 `@RequestParam("keywords")` 绑不上、词被静默丢弃（已用 `axios.getUri` 实测确认） | 改走 FormData（每个词一个同名字段）；`design.md` §4.5 原来的「axios 会展开成重复参数」断言一并订正 |
| P2 关键词上限常量在 store 与组件各一份 | 从 store 导出，`KeywordPane.vue` 改为引用（2026-10-24 拆分后位于 `stores/workspaceKeywords.ts`） |
| P2 `ARTIFACT_TYPE_LABEL` 零消费方、标签表重复 | `ArtifactMenu.vue` 改用 utils 那份，删掉组件内重复表 |
| P2 字幕行 `role="button"` 内嵌真 `<button>`（ARIA 不允许，且行上没有键盘动作） | 行容器改为普通列表项（`ul`/`li`），去掉 `role` 与 `tabindex`；键盘操作走行尾修改按钮，`aria-label` 带条号与时间码，选中态用 `aria-current` |
| 规范失实 5 处 | 订正 `directory-structure.md`（api / types / enums / lib / utils / stores 清单、复合组件例子）、`state-management.md`（「目录为空」与导入示例）、`type-safety.md`（类型文件清单、枚举示例）、`component-guidelines.md`（composables 现状）。`api.md` 的旧契约仍归 B2 R6 |

修复后复跑：`vue-tsc --build --force` / `npm run lint` / `npm run build` 三绿。

**未做的事（如实记录）**：修复后**没有**再做浏览器实跑。原本已重启前后端准备复验，
但 Playwright MCP 在本会话连接超时，且 5173 被另一应用占用。改动只涉及列表语义化、
常量引用与 aria 属性，`type-check` / `lint` / `build` 全过、模板结构已人工核对（`ul` 内只有 `li`）。
就绪态、交互与三种卡的可视证据仍是修复前那一轮的截图。

### 8.2 Playwright 复审与收口（2026-10-24）

起真实前后端（8080 / 5173），用已完成任务 `322b56f527dc4bb788a4c7c1c578edfb` 逐条走查 AC1–AC11：
布局与堆叠、6 条真实 GET、主题正常/降级空态、关键词上限与零写请求、搜索计数与空态、
提交 202 跳真 taskNo（409 用路由拦截验证）、产物下载（602 B SRT 内容正确）、
404 / 加载失败+重试 / RUNNING 三态、三条残留 grep 与 `vue-tsc` / `lint` / `build` 全绿。
证据在 `evidence/review-2026-10-24-*.png`。

复审发现并处理的三件事：

| 项 | 处理 |
|---|---|
| P3 关键词区两处「0/50」含义不同（清单条数 vs 草稿字数） | 草稿计数改为「已输入 N/50 字」 |
| store 字段过多 | 按域拆为 `workspace` / `workspaceSubtitles` / `workspaceKeywords`；`phase` 读字幕 store 的 `cleanedTrack`，`reset()` 扇出 |
| `WorkspacePhase` / 加载失败种类用裸字面量 | 运行时值移到 `enums/workspace.ts`（`WORKSPACE_PHASE` / `WORKSPACE_LOAD_ERROR_KIND`），比较处改用命名常量；`type-safety.md` 的「只放后端契约枚举」表述一并订正为通用枚举 |

同时按产品裁决调整 AC5：后端没有改单条字幕的接口，**行内编辑与本地覆盖延期**，
`localSegmentEdits` / `updateSegmentText` 删除，字幕行只保留禁用的铅笔占位
（`title`「编辑功能暂未开放」）。PRD AC5 已加删除线与日期。

复跑：`vue-tsc --build --force` / `npm run lint` / `npm run build` 全 exit 0；
页面复验铅笔 hover 出现且为禁用态，无 textarea 与写请求。

**证据收口（2026-10-24）**：`evidence/` 只保留 5 张关键截图（就绪 1280 / 堆叠 800 / 404 /
degraded 空态 / 铅笔占位）。重复截图、已被取代的失败态截图、以及行内编辑延期后失实的编辑态截图
（`b1-edit-state` / `b1-after-save` 等）已删除，其余验证以本文文字记录为准。
