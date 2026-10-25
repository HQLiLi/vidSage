# 交接文档：工作区页前端重写（B 任务）

> 日期：2026-10-22  
> 任务：`.trellis/tasks/10-21-workspace-fe-rewrite`  
> 上游：`.trellis/tasks/10-21-workspace-prototype`（A）  
> 当前状态：`completed`（**已变成父任务**，见下；2026-10-24 收尾归档，见 §12）  
> 结论：**主链路可以对接；完整验收仍缺媒体播放源和单条字幕持久化两个后端接口。**

## 0. 状态更新（2026-10-22）

本任务**已拆成两个子任务**，实现工作不在本目录：

| 子任务 | 范围 |
|---|---|
| `10-22-workspace-ready-page` | 契约层（types / enums / api / utils）+ 已完成任务的工作区页 + 首页提交接线 + 旧物删除 |
| `10-22-workspace-run-page` | run-scoped SSE + 进度 + 重生成 + 翻译 + 失败/重连 + 残留清理 + 规范订正 |

**现行口径**：需求与任务地图看本目录 `prd.md`；技术设计看两个子任务各自的 `design.md`。
本文件剩余章节是**背景留档**——§3 契约事实、§4 缺失接口、§5 契约迁移表、§6 运行时坑仍然有效，
但 §7 / §9 / §10 已按拆分结果更新。

## 1. 一句话结论

B 可以开始“按验证通过的原型重写 `frontend/` 工作区页，并接回当前后端契约”的工作；提交、任务快照、run-scoped SSE、关键词、重新生成、翻译、语种、产物读取都已经有接口。

但 B 不能直接宣称“完整完成”：

1. 播放器没有可用的媒体 HTTP 端点，当前只能无源降级。
2. 单条字幕修正没有持久化端点，当前只能本地演示。
3. B 自己的 `design.md` / `implement.md` 尚未编写，Trellis 还不能进入实现阶段。

## 2. 当前基线

### 2.1 A 原型

- 所有者口径：原型图已完成，A 任务结束。
- `prototype/` 已验证：
  - `npm run type-check` 通过。
  - `npm run build` 通过。
- 原型目前覆盖：
  - 清洗完成后的就绪态。
  - 左列三块：播放器 / 关键词 / 主题。
  - 右侧字幕：搜索、seek、高亮、自动滚动。
  - 字幕行 hover 修改入口与行级编辑演示。
  - 源 / 目标语种选择、互换、本地模拟翻译。
  - `?demo=processing`、`?demo=reclean` 的本地进度演示。
- 仓库记录现状：
  - A 的 `task.json` 仍是 `in_progress`，尚未归档。
  - 原型的失败态没有独立演示入口；如果 B 需要完整非就绪态，应在 B 的 `design.md` 里明确沿用现有卡片还是补最小失败态。
- `prototype/` 被 `.gitignore` 排除，不会产生可审查的 diff；B 必须重写，不要直接搬运原型代码。

### 2.2 B 任务现状

当前 B 目录只有：

- `prd.md`
- 空的 `implement.jsonl` / `check.jsonl`
- `task.json`

缺少：

- `design.md`
- `implement.md`

按 Trellis 规则，B 在补齐设计/执行计划并执行 `task.py start` 前，不应开始实现。

## 3. 当前已有的后端接口

以下均按当前 `backend/` 源码核对：

| 能力 | 接口 | 关键返回 / 说明 |
|---|---|---|
| URL 提交 | `POST /api/tasks` | `202 {taskNo, runNo}` |
| 文件上传 | `POST /api/tasks/upload` | `multipart file`，`202 {taskNo, runNo}` |
| 任务快照 | `GET /api/tasks/{taskNo}` | `TaskResponse`，状态在 `lastRun` |
| run-scoped SSE | `GET /api/tasks/{taskNo}/runs/{runNo}/events` | `text/event-stream`；终态 run 返回 `204` |
| 翻译 | `POST /api/tasks/{taskNo}/translate` | body `{sourceLanguage, targetLanguage}`，`202 {taskNo, runNo}` |
| 重新生成字幕 | `POST /api/tasks/{taskNo}/subtitles/regenerate` | body `{keywords}`；`null` 与 `[]` 语义不同 |
| 关键词读取 | `GET /api/tasks/{taskNo}/keywords` | `{keywords, fingerprint}` |
| 语种清单 | `GET /api/languages` | `[{code, label}]` |
| 产物清单 | `GET /api/tasks/{taskNo}/artifacts` | `ArtifactResponse[]`，带 `files` 下载地址 |
| 产物下载 | `GET /api/tasks/{taskNo}/artifacts/{type}/{file}` | `ORIGINAL` / `CLEANED` / `TRANSLATED`；翻译带 `?lang=` |

### 3.1 产物形状要点

- `CLEANED/segments.json`：`{language, segments, keywordsFingerprint}`。
- `CLEANED/context.json`：`{theme, sourceLanguage, terms, degraded}`。
- `TRANSLATED/segments.json`：`{sourceLanguage, targetLanguage, skipped, segments}`。
- 原文与译文段落按数组下标配对，后端保证时间戳逐条一致。
- 时间戳字段是 `beginTimeMs` / `endTimeMs`，不是秒。
- 主题直接取 `context.theme`；`degraded: true` 或空主题要显示明确空态。

### 3.2 SSE 事件形状

事件名为 `progress`，data 形状以 `TaskRunProgress` 为准：

```json
{
  "taskNo": "...",
  "runNo": 1,
  "runType": "MAIN",
  "status": "RUNNING",
  "stage": {
    "stage": "transcribe-audio",
    "progress": 42,
    "message": "正在识别语音并生成时间码",
    "state": "RUNNING"
  },
  "terminal": false
}
```

`TRANSLATE` 变体额外带 `targetLanguage`。  
不要沿用旧前端的 `taskStatus` / `translationStatus` 字段名。

## 4. 缺失接口

### 4.1 媒体播放源接口（P1，播放器必需）

**现状**

- 后端有 `SOURCE_MEDIA` 中间文件记录。
- 但当前 12 个 controller 端点里没有任何端点能返回媒体字节。
- 旧前端拼接的 `/api/tasks/{taskNo}/artifacts/source.mp4` 已被闭集 artifacts 端点替换，是死链。
- 没有静态资源处理器，也没有 `WebMvcConfigurer#addResourceHandlers`。

**建议 contract**

```http
GET  /api/tasks/{taskNo}/media
HEAD /api/tasks/{taskNo}/media
```

要求：

- `200` 或支持 Range 时返回 `206 Partial Content`。
- 响应头包含 `Content-Type`、`Content-Length`、`Accept-Ranges: bytes`。
- `Range` 请求返回 `Content-Range`。
- 文件不存在 / 已清理返回明确 `404`。
- 不建议复用 `artifacts/{type}/{file}` 的 `type` 闭集，单开 `/media` 更干净。

**可选补充**

如果前端需要在播放前区分视频 / 音频，增加媒体描述或让 `HEAD` 返回足够信息：

```json
{
  "kind": "VIDEO",
  "contentType": "video/mp4",
  "sizeBytes": 123456,
  "url": "/api/tasks/{taskNo}/media"
}
```

否则 URL 任务只能靠 `sourceUri` 扩展名猜 `audio` / `video`，对播客类 URL 不可靠。

### 4.2 单条字幕修正持久化接口（P1/P2，完整编辑必需）

**现状**

- 原型与 B 的 PRD 都要求字幕行 hover 修改图标 + 单条原文编辑。
- 后端没有更新 CLEANED segments 或重建 SRT 的接口。
- 当前只能修改前端内存，并明确提示“后端接口未实现，仅本地演示”。

**建议 contract**

```http
PATCH /api/tasks/{taskNo}/subtitles/segments/{index}
Content-Type: application/json

{
  "text": "修正后的原文",
  "baseRevision": 1
}
```

后端职责：

- 更新 `CLEANED/segments.json` 对应段落的 `text`。
- 同步重建 `CLEANED/subtitles.srt`。
- `CLEANED` artifact revision +1。
- 作废旧 `TRANSLATED` 产物，因为原文变化后旧译文已经失效。
- 有 run 正在跑时返回 `409`，避免与重新生成 / 翻译并发。
- `baseRevision` 用于乐观并发保护。

**当前数据模型缺一个稳定段落 id**：`TranscriptionSegment` 只有 `beginTimeMs` / `endTimeMs` / `text`。  
最小方案是 `index + baseRevision`；更稳的方案是以后给段落加稳定 id。不要只按时间戳定位，避免时间相同或重跑后错位。

### 4.3 关键词即时保存接口（可选，不阻塞当前 AC）

当前设计是：

- 前端增删关键词只改本地列表。
- 点「重新生成」时把完整清单提交给 `POST /subtitles/regenerate`。

因此不需要单独的 `PUT /keywords`。  
如果产品要求“关键词输入后立刻持久化、不点重新生成也不丢”，才需要：

```http
PUT /api/tasks/{taskNo}/keywords
{ "keywords": ["..."] }
```

按当前 PRD，这个接口暂不列为缺口。

## 5. B 必须完成的契约迁移

旧前端 `frontend/` 仍在使用 2026-10-13 的契约，B 需要整体替换：

| 旧契约 | 当前契约 |
|---|---|
| 扁平 `TaskView`，状态字段在顶层 | `TaskResponse`，状态在 `lastRun` |
| 顶层 `artifacts: TaskArtifact[]` | `GET /{taskNo}/artifacts` 返回 `ArtifactResponse[]` |
| `GET /api/tasks/{taskNo}/events` | `GET /api/tasks/{taskNo}/runs/{runNo}/events` |
| 触发接口返回 `TaskView` | 返回 `{taskNo, runNo}` |
| 翻译无 body | `{sourceLanguage, targetLanguage}` |
| 产物按扁平文件名取 | `files.segments/subtitles/context/terms` 下的 `url` |
| 409 `{error, existingTask}` | `{taskNo, runNo}` |
| `PENDING` / `RUNNING` | 当前只有 `RUNNING` / `SUCCEEDED` / `FAILED` / `INTERRUPTED` |
| `taskStatus` / `translationStatus` | `TaskRunProgress.status` / `runType` / `terminal` |

## 6. 关键运行时坑

1. **终态 run 的 SSE 是 204**  
   不要把它当“SSE 端点坏了”。快照显示 run 已终态时不应再订阅；如果订阅与收口之间有竞态，收到 204 应回查 `GET /api/tasks/{taskNo}` 和产物，按完成处理。

2. **触发接口不返回任务视图**  
   提交 / 上传 / 重新生成 / 翻译都只返回 `{taskNo, runNo}`。拿到 ref 后再请求快照并订阅对应 run，不能把返回值直接赋给 `taskView`。

3. **重新生成和翻译会切换 runNo**  
   旧 run 的 SSE 终态关闭后，必须用触发响应里的新 `runNo` 重新订阅，不能复用旧流。

4. **短任务可能在订阅前就结束**  
   翻译 / 重新生成可能很快收口；订阅拿到 204 时，仍要以 GET 快照和最新产物为准。

5. **原文修改必须让译文失效**  
   只要 CLEANED 原文变化，旧 TRANSLATED 产物就不能继续展示；对应接口应明确处理 revision 与旧译文作废，不能静默保留旧译文。

6. **当前前端规范已过期**  
   `.trellis/spec/frontend/api.md` 仍写着旧 `/events`、旧 `TaskView` 和旧 409 形状。B 实现后必须在收敛阶段同步订正规范，否则后续任务会继续照着旧契约写。

## 7. 拆分与顺序（2026-10-22 更新）

### 已定

1. **拆成两个子任务**，切面是「读已完成的任务」vs「跑进行中的任务」：
   - B1 `10-22-workspace-ready-page`：契约层 + 已完成任务的页面 + 首页提交接线 + 旧物删除。
   - B2 `10-22-workspace-run-page`：SSE + 进度 + 重生成 + 翻译 + 失败/重连 + 收尾清理。
   - 顺序：B2 依赖 B1，不重建契约层。
2. **Q5 定为**：
   - `lastRun.runNo` 作为页面恢复入口；
   - 触发接口返回的 `runNo` 作为新流订阅入口；
   - 204 作为"run 已终态，回查快照/产物"的合法分支——前端不编码 204/404 语义，
     一律"关流 → 重拉快照 → 由 `lastRun.status` 判定"。
3. **媒体**：仍不做后端端点，前端按"无源"降级并显式标注（16:9 占位）。
4. **单条字幕**：不新建后端任务，保留本地演示提示，不假装持久化。
5. **关键词即时保存**：不做，`PUT /keywords` 不列为缺口。

### 不建议

- 不要把媒体端点混进前端重写任务；否则会跨前后端边界，验收和 review 都变复杂。
- 不要在 A 的最终验收未确认时直接搬运 `prototype/` 代码到 `frontend/`。
- 不要继续沿用旧 `TaskView` 或 `/events` 兼容层；目标是整体替换旧契约。

## 8. 验收映射提醒

| B AC | 当前可行性 |
|---|---|
| 布局与原型一致 | 可做 |
| 提交 → 进度 → 就绪 → 翻译 | 可做；媒体播放需等媒体端点 |
| 关键词真实读取 / 重新生成 | 可做 |
| 主题真实读取 / degraded 空态 | 可做 |
| 单条字幕编辑 | 交互可做；持久化需等新接口 |
| 首页提交跳转真 taskNo | 可做 |
| 无旧契约残留 / type-check / lint | 可做 |
| 六处契约逐条实测 | 可做 |

## 9. 待所有者确认（2026-10-22 已定）

| # | 事项 | 结论 |
|---|---|---|
| 1 | 媒体接口是否单开后端任务 | **不在本任务树内**。前端按"无源"降级：16:9 占位 + 明说不可播，不留死链 |
| 2 | 字幕修改是否要求持久化 | **首版接受本地演示**，记为已知缺口；后端接口要另开任务 |
| 3 | A 是否现在归档 | 仓库记录仍是 `in_progress`；所有者已口头确认结束，**归档动作仍未执行** |
| 4 | 关键词是否要求即时保存 | **不要求**；「增删后点重新生成」即保存，`PUT /keywords` 不列为缺口 |

## 10. 交接检查清单（2026-10-22 更新）

父任务侧：

- [x] 拆成两个子任务并挂上父子关系
- [x] 父任务 `prd.md` 改写为源需求集 + 任务地图 + AC 归属表
- [x] 两个子任务的 `prd.md` 写完
- [x] 两个子任务的 `design.md` / `implement.md` 写完
- [x] 父与子的 `implement.jsonl` / `check.jsonl` 填好
- [x] **A（`10-21-workspace-prototype`）归档**（2026-10-24）
- [x] 两个子任务各自 `task.py start` → 实现 → 验收 → 归档
- [ ] 订正 `.trellis/spec/frontend/api.md`（**延期**，按所有者指示本次只记 journal）

B1 侧：

- [x] `npm run type-check` / `npm run lint` / `npm run build` 全绿
- [x] 实跑：打开已完成任务 → 左三块 + 右字幕区齐全、数据来自真实接口
- [x] 实跑：首页提交 → 跳到真 `taskNo`
- [x] 实跑：产物下拉能下载 CLEANED 的 srt / segments.json
- [x] 逐条验 AC1–AC11（AC5 行内编辑 2026-10-24 裁为延期，只留禁用铅笔占位）

B2 侧：

- [x] 实跑：提交 → 进度 0→100 → 就绪 → 翻译 → 双语
- [x] 实跑：重生成后换新 `runNo` 订阅、译文失效提示出现
- [x] 实跑：断线重连、终态 204 不报错
- [x] 逐条实测六处契约错位（提交 / 上传 `202` 与 `409` 同形未单独留档）
- [x] 全量 grep 无旧契约残留（2026-10-24 复跑：`vue-tsc` exit 0、oxlint / eslint 无告警）
- [ ] 订正 `.trellis/spec/frontend/` 里的失实描述（**延期**，同上）

## 11. 事实来源

- `.trellis/tasks/10-21-workspace-fe-rewrite/prd.md`
- `.trellis/tasks/10-21-workspace-page-revamp/prd.md`
- `.trellis/tasks/10-21-workspace-prototype/prd.md`
- `.trellis/tasks/10-21-workspace-prototype/design.md`
- `backend/src/main/java/com/vidsage/web/controller/TaskController.java`
- `backend/src/main/java/com/vidsage/web/controller/ArtifactController.java`
- `backend/src/main/java/com/vidsage/web/controller/LanguageController.java`
- `backend/src/main/java/com/vidsage/web/dto/TaskResponse.java`
- `backend/src/main/java/com/vidsage/web/dto/RunResponse.java`
- `backend/src/main/java/com/vidsage/web/dto/TaskRunRefResponse.java`
- `backend/src/main/java/com/vidsage/web/dto/ArtifactResponse.java`
- `backend/src/main/java/com/vidsage/application/task/message/TaskRunProgress.java`
- `prototype/src/data/workspace.ts`
- `frontend/src/types/task.ts`
- `frontend/src/api/tasks.ts`
- `frontend/src/composables/useTaskEvents.ts`
- `.trellis/spec/frontend/api.md`

---

如果后续实现与本文件冲突，以当前后端源码和 B 任务最终 `design.md` 为准，并同步更新本文件。

## 12. 收尾（2026-10-24）

本任务是**容器**，自身不含实现工作，实现全部由两个子任务完成并已归档、合并进 `dev`：

| 子任务 | 归档 | 合并 |
|---|---|---|
| `10-22-workspace-ready-page` | ✅ | `4cf2d5d`（`feature/workspace-ready-page` → `dev`） |
| `10-22-workspace-run-page` | ✅ | `a08c478`（`feature/workspace-run-page` → `dev`） |

收尾时的 AC 口径：

- **AC5 行内编辑**：按 2026-10-24 产品裁定延期——后端无改单条字幕的接口，前端只留 hover 时的禁用铅笔占位（`title`「编辑功能暂未开放」），没有编辑链路，不假装持久化。
- **六处契约错位第 6 条（媒体播放）**：后端没有媒体字节端点，父任务 Q4 已裁定属本任务树外的后端缺口；前端去掉了死链、保留 16:9 无源占位。
- **AC12 规范订正**：按所有者指示本次不做，`.trellis/spec/frontend/` 仍是旧契约，已登记在 `.trellis/workspace/HeQiu/known-bugs.md` §6 第 3 条。
- 六处错位实测中「提交 / 上传 `202`」「`409` 同形」未单独留档，其余已实跑（同 known-bugs §6 第 4 条）。
