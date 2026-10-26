# 执行计划：原始语种以字幕产物为准

## 0. 开工

- [x] 从 `dev` 切 `feature/source-language-authority`，`task.py set-branch`。
- [x] `task.py start` 置 `in_progress`。
- [x] 取证：直连 DashScope 原始 API 跑 3 秒样例，下载结果 JSON 确认**上游不返回 `language`**
      （全文 0 次命中；`language_hints: ["auto"]` 也不返回）。

## 1. 后端

- [x] `SubtitleCleanupCoordinator`
  - [x] 新增 `resolveLanguage(asrLanguage, context)`：ASR 有值用 ASR，否则用上下文里能归一到
        白名单的语种（`zh` → `zh-Hans`），都对不上就 `null`。
  - [x] `doClean` 写 `SubtitleTrack` 时改用它。
  - [x] 测试补 3 条：兜底生效 / ASR 优先 / 自由文本留 null（`SubtitleCleanupCoordinatorTest`）。
- [x] `SubtitleTranslateService`
  - [x] 注入 `SubtitleTrackReader`（构造器参数放在 `artifactStore` 之后）。
  - [x] `translate()` 里算一次 `workDir`，`requireIntactArtifact` 改签名收 `workDir`。
  - [x] 新增 `validateSourceLanguage(taskId, workDir, requested)`，判定见 `design.md` §3.2。
  - [x] 类 Javadoc 的 `@throws IllegalArgumentException` 补「或源语种与字幕实际语种不一致」。
- [x] `TaskController.translate` 的 `@Operation` / `@throws` 同步补 400 分支说明。
- [x] `TranslationRequest.sourceLanguage` 的 `@Schema` 补一句约束。
- [x] `SubtitleTranslateServiceTest` 补 5 条用例（见 `design.md` §4）。

## 2. 前端

- [x] `stores/workspace.ts`
  - [x] `manualSourceLanguage` / `detectedSourceLanguage` / `sourceLanguageLocked` /
        `selectedSourceLanguage`（computed）四件套。
  - [x] `selectSourceLanguage` 加锁判断；删 `swapLanguages`。
  - [x] `applyLanguages` 去掉源语种回填；`reset` 清手动值。
  - [x] 导出里加 `sourceLanguageLocked`，删 `swapLanguages`。
- [x] `components/TranslationBar.vue`
  - [x] 新增 `sourceLocked` prop；锁定时源语种渲染静态文本。
  - [x] 互换按钮 → `to` 标签；删 `swap` 事件、SVG、`title` 文案。
- [x] `components/TranscriptPane.vue`
  - [x] 透传 `source-locked`；删 `onSwapLanguages` 与 `@swap`。

## 3. 验证

- [x] 后端 `mvn clean test-compile`（判编译）。
- [x] `mvn -Dtest='SubtitleCleanupCoordinatorTest,SubtitleTranslateServiceTest' test`
      → 27 + 12 全绿。
- [x] 后端全量 `mvn test` → 853 通过、1 跳过。
- [x] 前端 `npm run lint`、`npm run type-check`。
- [x] 真机全链路（任务 `91437acbc5964e9cb7757c0bfdf4e885`，
      `https://www.bilibili.com/video/BV1J3nE6gEQZ/`）：
  - [x] AC8：`ORIGINAL` 的 `language` 为 `null`，`CLEANED` 被补成 `zh-Hans`，`context` 为 `zh`。
  - [x] AC4：`POST translate {sourceLanguage:"en", targetLanguage:"ja"}` → `400`，文案
        「源语种与字幕实际语种不一致：请求 en，字幕实际是 zh-Hans（简体中文）…」；
        `lastRun` 仍是 `2 MAIN SUCCEEDED`，没建 run 占位。
  - [x] AC5：`{sourceLanguage:"zh"}` → `202`，runNo 3，产出 `TRANSLATED ja`，
        `sourceLanguage: zh-Hans`、10 段、译文为日文。
  - [x] AC1–AC3（Playwright + 浏览器）：`select[aria-label="源语言"]` 不存在、静态文本
        「简体中文」带 title；中间一个 `to`、无互换按钮；目标选简体中文时按钮禁用并提示
        「源语言与目标语言相同」。截图：`evidence/translation-bar-locked.png`。
  - [x] 旧产物（`6d452f67…`，track `language` 仍为 null）页面仍锁定「简体中文」——走的是前端
        context 兜底，符合 `design.md` §1 的裁定。
- [ ] AC6 的「可编辑下拉」分支无法造数据实测（现有任务 context 都有 `zh`），仅由代码路径保证。

> 冒烟环境：后端 `mvn spring-boot:run -Dspring-boot.run.arguments=--vidsage.tools.default-timeout-seconds=1800`
> （该视频 yt-dlp 默认 300 秒超时，放宽后才下载完成），前端 `npm run dev`。两个进程都由本会话拉起。

## 4. 收口

- [ ] 跑 `trellis-check`（或 `/trellis:finish-work`）。
- [ ] spec 更新判断：往后端 `error-handling.md` 补一条「源语种与产物语种校验」，
      往前端 `state-management.md` 补「识别值锁死、认不出退回手动」的口径。
- [ ] 提交前停在这里等审查，**不自行 commit / merge / archive**。
