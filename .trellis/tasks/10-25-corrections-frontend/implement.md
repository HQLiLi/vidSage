# 前端字幕纠错交互 — 执行清单

> 设计口径见本子任务 `design.md` 与父任务 `design.md` §6。前端改动的回滚点是整条分支，后端接口保留无副作用。

## 0. 前置

- 分支：从 `dev` 切 `feature/corrections-frontend`，合回 `dev`。
- 验证命令（`frontend/` 下）：

```bash
cd frontend && npm run type-check
cd frontend && npm run lint
```

- 手工走查需要后端 + 前端同时起，准备一个**已完成、已有字幕**的任务；AC6 需要一个能触发翻译/重生成的语种。
  后端启动按项目口径：`JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dmaven.test.skip=true`。

## 1. 类型与请求层

- [x] `types/subtitle.ts`：`TranscriptionSegment.id?`、`TranslatedSegment.id?`；
      `SubtitleCue` 加 `id` / `sourceText` / `translationStale`；
      加 `SubtitleCorrection`、`SubtitleCorrectionListResponse`、`SaveCorrectionRequest`、
      `ClearStaleCorrectionsResponse`。
- [x] `enums/subtitle.ts`：加 `CORRECTION_FIELD`。
- [x] `types/task.ts`：`TranslationRequest` 加 `force?: boolean`。
- [x] `api/request.ts`：门面补 `put` / `delete`（同款受控断言）。
- [x] `api/tasks.ts`：加 `listCorrections` / `saveCorrection` / `deleteCorrection` / `clearStaleCorrections`。

## 2. `utils/transcript.ts`

- [x] 加 `buildSegmentId(segment)`：`id` 有值用值，没有按 `seg-<begin>-<end>` 现算。
- [x] `buildCues` 改按 id 配对，失败退回「同下标 + 时间戳」，再失败 `mismatch=true`。
- [x] `toCue` 填 `id`、`sourceText`、`translationStale`。
- [x] 核对：源目标语种相同（后端把 `translatedText` 写成与原文相同）时仍视为没译文，
      不会被标成「待更新」。

## 3. store

- [x] `stores/workspaceSubtitles.ts`：`corrections`、`staleCorrectionCount`、`loadCorrections`、
      `hasCorrection`、`saveCorrection`、`deleteCorrection`、`clearStaleCorrections`；
      `reset()` 一并清空。
- [x] `saveCorrection` 成功后的本地补丁：原文改 `cleanedTrack`，译文只在语种对得上时改
      `translatedTrack`；列表按 `segmentId + field + lang` upsert。
- [x] `stores/workspace.ts`：`loadAll` 拉纠错；新增 `reloadSubtitles()`、`revertCorrection()`；
      `translate()` 带 `force`。

## 4. 组件

- [x] 新增 `views/workspace/components/SubtitleCueRow.vue`：
      - [x] 两个铅笔（原文 / 译文），`busy` 时隐藏，译文铅笔要求有译文；
      - [x] 编辑态 textarea、自动聚焦、行内保存 / 取消 / 恢复；
      - [x] 失焦保存、保存按钮 `mousedown.prevent`、Esc 放弃、Ctrl/⌘+Enter 保存；
      - [x] 幂等、`saving` 去重、失败还原 + 行内错误、空内容拦截、IME 组合态保护；
      - [x] `busy` 变真时放弃编辑。
- [x] `TranscriptPane.vue`：改用 `SubtitleCueRow`；加失效纠错提示条与清除按钮；
      `retranslate` 汇总后传给 `TranslationBar`。
- [x] `TranslationBar.vue`：加 `retranslate` prop，按钮文案切「重新翻译」。

## 5. 验证门

- [x] `npm run type-check` 绿。
- [x] `npm run lint` 绿；`npm run build` 也通过。
- [x] 手工走查（任务 `1c992d200c534b3696449961be742d83`，产物里没有 `id`）：
  - [x] AC1 完成态 hover 出铅笔（两条各一个）；译文铅笔只在有译文时出。
        运行中隐藏是 `v-if="!busy"`，沿用原占位按钮的同一个判据，未在运行中单独截图。
  - [x] AC2 失焦保存成功（译文行）；点保存只发一次 PUT；Esc 不发请求。
  - [x] AC3 Ctrl/Enter 保存成功；刷新页面后两条改动仍在。
  - [x] AC4 用超长内容触发后端 400：草稿还原成编辑前文本，行内显示后端文案，无 JS 异常。
  - [x] AC5 改原文后译文行立刻出现「原文已改，译文待更新」；点「重新翻译」后端真重翻
        （en revision 2 → 3），完成后标记消失，手改译文仍叠在新机器译文上。
  - [x] AC6 en 的译文纠错在 ja 下不出现，切回 en 仍在。
  - [x] AC7 直接往库里插一条 `seg-999-1000` 的纠错：页面显示「1 条纠错已失效」，
        点清除后提示消失、库里只留下有效条目。
  - [x] AC8 旧产物（磁盘 JSON 没有 `id`）展示、保存、恢复都正常。
  - [x] 恢复入口：编辑有纠错的原文时出现「恢复原文」，点完文本回到机器原文、失效标记消失。
- [x] 代码走查：请求都走 `request` 门面；没有裸 `as`；没有行内 `style`；按钮都带 `type`。

> 走查用的纠错已在库里清掉，en 译文重新翻过一次恢复成与机器原文一致的状态。

## 6. 收尾

- [ ] 更新 `.trellis/spec/frontend/`（如新增「纠错前端交互」一条，写清恢复入口与刷新时机）。
- [ ] 提交信息按项目惯例；推送带本机代理。
- [ ] 子任务归档，回父任务做集成评审。
