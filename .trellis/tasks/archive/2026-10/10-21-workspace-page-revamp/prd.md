# 工作区页改版：原型验证 + 前端重写接当前契约

## Goal

把 `/workspace/:taskNo` 工作区页换一套布局（首页 `/` 布局不动），并**顺带把前端接回当前后端契约**——
前端自 2026-10-13 起未再改动，而后端字幕模型重构（10-15 → 10-20）把契约整层换掉了，
现在前端对任何任务都跑不通。

交付分两步，由两个子任务承载：**先在 `prototype/` 用假数据把新布局验证出来，再按验证通过的原型重写 `frontend/` 并接真接口**。

## Background：已查证的事实

### 1. 前端与当前后端契约的六处错位（全部实测/静态可判）

前端 `src/` 最后改动 `412384f`（2026-10-13）。后端字幕模型重构在其后。逐条：

| # | 前端现在 | 后端现在 | 后果 |
|---|---|---|---|
| 1 | SSE 订 `GET /api/tasks/{taskNo}/events` | 唯一 SSE 映射是 `GET /api/tasks/{taskNo}/runs/{runNo}/events`（`TaskController:147`） | 订阅 404，流直接死 |
| 2 | `TaskView` 扁平：`status` / `currentStage` / `errorMessage` / `startedAt` / `finishedAt` / `artifacts` 全在顶层 | `TaskResponse` 只给 `{taskNo, inputType, sourceUri, createdAt, lastRun}`；扁平五字段已删、`artifacts` 也删 | `taskView.status` 恒 `undefined` → `isRunning` / `isSucceeded` / `isFailed` 全 false，页面永不进就绪态 |
| 3 | 提交 / 上传标注返回 `TaskView` | 四个触发点（提交 / 上传 / 翻译 / 重生成）统一返回 `{taskNo, runNo}`（`TaskRunRefResponse`） | `view.taskNo` 是 `undefined`，跳 `/workspace/undefined` |
| 4 | `translate(taskNo)` 无 body、返回 `TaskView` | 要 `TranslationRequest{sourceLanguage, targetLanguage}`，返回 `{taskNo, runNo}` | 翻译必 400 |
| 5 | 产物从 `TaskView.artifacts` 取，`fetchArtifactJson(taskNo, name)` 打 `/{taskNo}/artifacts/{name}` | 清单走 `GET /{taskNo}/artifacts`（`ArtifactResponse[]`），下载走 `/{taskNo}/artifacts/{type}/{file}`（`type` ∈ ORIGINAL/CLEANED/TRANSLATED，`file` ∈ segments.json/subtitles.srt/context.json/terms.json） | 拿不到产物清单，也下不到文件 |
| 6 | 媒体播放源 `/api/tasks/{taskNo}/artifacts/source.mp4` | **不存在**。旧端点 `/{taskNo}/artifacts/{fileName}`（扁平任意名）已被闭集替换；当前全仓 11 个端点无一能吐媒体文件，也无静态资源处理器 | **视频 / 音频无法播放** |

**可观察症状**：打开任意 `/workspace/:taskNo`，停在「加载失败 · 进度连接已断开，请刷新页面重试」——
SSE 404 触发 fatal 分支，`diagnoseFatal()` 重拉快照却成功，于是判定"端点坏了"。

**`npm run type-check` 会通过**——类型全在本地自洽，典型假绿。

### 2. 你要的三块内容，后端现状

| 功能 | 后端 | 说明 |
|---|---|---|
| 关键词列表 | ✅ **已有** `GET /{taskNo}/keywords` → `{keywords, fingerprint}` | 存 `subtitle_keyword` 表；`fingerprint` 是后端单源算的，拿它比 `ArtifactResponse.keywordsFingerprint` 即可判断"字幕对不对得上当前关键词" |
| 关键词重生成 | ✅ **已有** `POST /{taskNo}/subtitles/regenerate` | `keywords: null`（字段缺席）= 不动已有清单按现状重跑；`[]` = 清空。两者语义不同 |
| 主题 | ✅ **已有**，但不在独立端点 | `theme` 存在 CLEANED 产物的 `context.json`（`SubtitleContext{theme, sourceLanguage, terms, degraded}`），下载走 `GET /{taskNo}/artifacts/CLEANED/context.json`；`GET /{taskNo}/artifacts` 的 `files` 映射里已给出该地址 |
| 修改字幕 | ❌ **缺** | 所有者确认后端未写；已裁定目标是字幕行级人工修正 |

**主题的形态**：system prompt 要求「用**一两句话**说明这段内容在讲什么（话题、领域、场景）」——
是一两句话的主题，不是长文总结。首版已裁定直接使用当前形状，不新增长摘要；以后可再扩展。

**主题的空态**：摘要失败时落 `degraded: true` + `theme: ""`（有意落盘留痕），主题区必须处理。

### 3. `prototype/` 不进仓库

`.gitignore:20` 有 `/prototype/`。因此子任务 A 的产出**没有任何 diff 可审**，验收只能是"跑起来看"，
也不会出现在任何分支上。这与 `prototype/README.md` 的约定一致（验证通过**重写**进 `frontend/`，不直接搬运）。

### 4. 前端采取"重写"而非"补丁式对齐"

所有者裁定：可以无视旧组件与旧对象，重写完成后把它们收掉。所以子任务 B 不是改几行，
而是按新原型重建工作区页 + 重建契约层（types / api / store / SSE 订阅）。

## Requirements（源需求集）

- **R1 工作区页新布局**：顶栏不动；其下左右分栏——**左侧视频区竖分三块**（① 视频 / 音频播放器 ② 用户自定义关键词列表 + 右侧「重生成」③ 主题区，显示媒体主题）；**右侧字幕区维持原样**。字幕条目在鼠标移入 / 键盘聚焦时于右侧显示修改图标，点击后进入该条的原文人工修正。
- **R2 首页 `/` 布局不动**，但其提交逻辑必须接回新契约（第 3 处错位）。
- **R3 前端接回当前后端契约**：上表六处全部修好。
- **R4 关键词与主题用真实接口**，不做假数据；主题区只显示 `theme`，不显示 `terms`。**修改字幕**后端未实现，原型与前端均实现行级编辑交互，但保存只能明确标注为本地演示，不假装已持久化。
- **R5 旧组件 / 旧类型在重写后收掉**，不留双份。
- **R6 设计先行**：新布局先在 `prototype/` 验证，通过后才重写进 `frontend/`。
- **R7 原型分两轮验收**：第一轮只做清洗结束的就绪态主页面；第一轮验收通过后，再追加 SSE / 进度 / 失败态原型。SSE 原型未验收前不进入前端重写。

## 任务映射

| 子任务 | 交付物 | 验收面 | 产出进 git |
|---|---|---|---|
| `10-21-workspace-prototype` | `prototype/` 里的新布局（假数据） | 跑起来看：布局对不对、交互顺不顺 | ❌ 本地 |
| `10-21-workspace-fe-rewrite` | `frontend/` 重写后的工作区页 + 契约层 | 端到端：提交 → 进度 → 就绪 → 翻译 → 关键词 / 主题 | ✅ |

**顺序**：B 依赖 A。A 未验证通过前不开 B 的实现（写进 B 的 `prd.md`）。

## Cross-child Acceptance Criteria

- [x] **AC1** B 的产物布局与 A 验证通过的原型一致；任何有意偏离都要在 B 的 `implement.md` 里写明理由。
      （B1 `implement.md` §5 列了 14 条偏离；A 归档前补修了窄屏主题块重叠，窄屏下 A/B 一个整页滚动、一个左列内部滚动，属非视觉性实现差异。）
- [x] **AC2** 端到端跑通：提交 URL 或本地文件 → 看实时进度 → 进就绪双栏 → 触发翻译 → 看到双语字幕。
      （B2 Playwright 实跑：MAIN 上传 0→43→70→100 带骨架、翻译走真 SSE 并双语显示。）
- [x] **AC3** 关键词区显示真实关键词（`GET /keywords`），增删后点「重生成」触发 `POST /subtitles/regenerate`，进度走 SSE。
      （B2 实跑：空清单抓包确认发 `[]`，重生成换新 `runNo`。）
- [x] **AC4** 主题区显示 `context.json` 的 `theme`；`degraded: true` 时显示明确的空态而非空白。（B1 实跑。）
- [x] **AC5** 字幕条目 hover / 聚焦时显示修改图标，点击后只编辑该条字幕内容；保存动作明确说明后端未实现，不误导用户。
      （2026-10-24 裁定延期：后端无改单条字幕接口，前端只留禁用铅笔占位，编辑链路不验收。见 B1 `prd.md` AC5 与 `implement.md` §5 第 14 条。）
- [x] **AC6** 首页 `/` 提交后能正确跳到 `/workspace/<真 taskNo>`。（B1 实跑，409 跳既有任务。）
- [x] **AC7** `frontend/` 中不再残留旧契约类型（扁平 `TaskView`）与旧 SSE 订阅路径；`npm run type-check` 与 `npm run lint` 通过。
      （2026-10-24 复跑：`vue-tsc --build` exit 0、oxlint / eslint 无告警；grep 无旧契约命中。）
- [x] **AC8** 六处错位逐条修好，且**逐条实测**（不接受"静态看着对"）。
      （五处已实跑；第 6 处媒体播放是后端缺口，见 Q4，前端已去死链并留 16:9 无源占位。「提交 / 上传 `202`」「`409` 同形」两条未单独留档，见 known-bugs §6 第 4 条。）

## Open Questions（2026-10-24 已全部裁定）

| # | 问题 | 结论 |
|---|---|---|
| Q4 | ~~媒体播放源端点由谁补（第 6 处错位是后端缺口，前端修不了）？~~ | **不在本任务树内**。前端按无源降级（16:9 占位 + 明说不可播），后端端点另开任务；缺口登记在 known-bugs §6 第 1 条。 |
| Q5 | ~~SSE 订阅模型（run-scoped）怎么落到前端？~~ | 已按 B 的 `design.md` 落地：一条 run 一条流，触发接口返回的 `runNo` 作订阅入口，`lastRun.runNo` 作恢复入口，终态 `204` 回查快照判定。 |

## Notes

- 本文件是**父任务**的源需求集与任务地图。父任务本身不含实现工作，不 `start`。
- 子任务的详细验收标准写在各自 `prd.md` 里；跨子任务的验收写在上面的 Cross-child AC。
- 原型当前先交付“清洗结束”就绪态；SSE / 进度 / 失败态在第一轮验收通过后再追加。
- 2026-10-24 收尾：A（`10-21-workspace-prototype`）与 B（`10-21-workspace-fe-rewrite`）均已归档；B 的实现由两个子任务完成并合入 `dev`（`4cf2d5d`、`a08c478`）。遗留项见 `.trellis/workspace/HeQiu/known-bugs.md` §6。
