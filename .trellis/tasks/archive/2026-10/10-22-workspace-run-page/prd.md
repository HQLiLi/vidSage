# 工作区运行态：run-scoped SSE、重生成与翻译、收尾清理

> 父任务：`10-21-workspace-fe-rewrite`（源需求集 R1–R6 与任务地图在那）。
> 前置任务：`10-22-workspace-ready-page`（契约层与已完成任务页面；本任务在其之上做增量，不重建契约层）。

## Goal

在就绪页的基础上补齐**运行态**：run-scoped SSE 订阅、实时进度、关键词重生成、翻译（选语种 / 互换 / 触发 /
双语显示 / 译文失效）、失败与中断态、断线重试。

最后做收尾：清掉旧契约残留、逐条实测六处契约错位、订正已经过期的 `.trellis/spec/frontend/`。

## 依赖

**依赖 `10-22-workspace-ready-page` 完成**。本任务在其重建的 `stores/workspace.ts` 与
`views/workspace/components/` 上做增量：契约层（types / enums / api / utils）**不重建**，只补运行态需要的那几块
（`composables/useRunEvents.ts`、store 的 run 相关 state 与 action、几个组件的运行态分支）。

## Requirements

- **R1 run-scoped SSE**：`GET /api/tasks/{taskNo}/runs/{runNo}/events`，一条 run 一条流。
  - 触发接口返回的 `runNo` 是订阅入口；`GET /{taskNo}` 快照里的 `lastRun.runNo` 是刷新页面的恢复入口。
  - **已终态的 run 返回 204**：不把它当「端点坏了」。EventSource 的 `onerror` 不带状态码，
    204 与 404 都是 `readyState === CLOSED`，所以前端不编码 204/404 语义——
    一律「关流 → 重拉快照 → 由 `lastRun.status` 判定终态」。
  - 断线重连：关掉浏览器自带的自动重连，改自管（1s / 2s / 4s，最多重试 3 次），
    仍失败则出「连接中断，请重试」与手动重试入口。
  - 换 run（重生成 / 翻译）必须用**新** `runNo` 重新订阅，旧流作废；带代际校验防止旧流串台。
  - 页面卸载必须关流（store 作用域不随路由卸载）。

- **R2 进度显示**：
  - `stage.progress` 对 MAIN 是任务级 0–100；RECLEAN 复用清洗阶段区间，原始值 70→100；
    TRANSLATE 是本次 run 的 0–100。
  - **RECLEAN 归一到 0–100**，与 TRANSLATE 观感一致（用镜像后端权重表的单个常量实现）。
  - 重连时服务端推的快照进度一律是 0，必须按 `runNo` 做单调保护，不能让进度条回退。

- **R3 关键词重生成**：关键词区标题行右侧加「重新生成」；点它把**当前完整清单**提交给
  `POST /{taskNo}/subtitles/regenerate`（**总是提交数组**：空数组 = 清空，绝不发 `null`，`null` 的语义是「不动清单」）。
  清单为空时按钮文案改为「清空并重新生成」。触发后订阅新 runNo，进度走 SSE。
  运行中禁用输入 / 增删 / 按钮。

- **R4 翻译**：
  - 字幕区头部放源 / 目标语种双 `select`（取 `GET /api/languages`）+ 互换 + 翻译按钮。
  - 源语种与目标语种相同时禁用翻译按钮并给出说明。
  - `POST /{taskNo}/translate` 带 `{sourceLanguage, targetLanguage}` body。
  - 翻译成功后按 `lastRun.targetLanguage` 取对应 TRANSLATED 产物，与原文按下标配对显示；
      原文与译文时间戳不一致时不显示译文并给出提示。
  - 已存在的 TRANSLATED 产物直接显示（切语种时不必重跑）。
  - **译文失效**：重生成成功后后端会作废旧译文，刷新产物清单即可；界面给出
    「字幕已重新生成，原译文已失效，需要重新翻译」的提示。

- **R5 失败与中断**：无可用字幕且 `lastRun` 终态为 FAILED / INTERRUPTED → 错误卡（带 `errorMessage` + 重试）；
  有可用字幕时只出顶部错误条（如翻译失败），内容照常看。

- **R6 收尾**：
  - 全量 grep 确认旧契约类型、旧 SSE 路径、旧产物取值方式均无残留。
  - `npm run type-check` / `npm run lint` / `npm run build` 全绿。
  - **六处契约错位逐条实测**（不接受「静态看着对」）。
  - 订正 `.trellis/spec/frontend/api.md`（仍写着旧 `/events`、旧 `TaskView`、旧 409 形状），
    顺手修 `directory-structure.md` / `state-management.md` 里「目录为空」的失实描述。

## Acceptance Criteria

- [ ] **AC1** 提交 URL / 本地文件 → 实时进度（0→100）→ 就绪双栏 → 触发翻译 → 双语字幕，端到端跑通。
- [ ] **AC2** SSE 订阅路径带 `runNo`；重生成 / 翻译后换用新 `runNo` 订阅，旧流不再推送。
- [ ] **AC3** 终态 run 订阅拿到 204 时不报「端点坏了」，而是回查快照 / 产物按完成处理。
- [ ] **AC4** 断线后自动重连；连续失败给出可重试的提示，不无限静默重试。
- [ ] **AC5** 进度百分比单调不回退；RECLEAN 归一到 0–100；重连快照的 0 不把进度打回起点。
- [ ] **AC6** 改关键词后点「重新生成」触发 `POST /subtitles/regenerate`，进度走 SSE，
      完成后关键词与字幕刷新；空清单提交的是 `[]` 而不是 `null`。
- [ ] **AC7** 翻译可选用语种与互换；源目标相同则禁用并说明；成功后显示双语字幕。
- [ ] **AC8** 重生成成功后旧译文消失并出现失效提示。
- [ ] **AC9** 失败 / 中断任务显示错误卡或错误条，带后端给的 `errorMessage`。
- [ ] **AC10** 六处契约错位逐条实测通过（提交 / 上传 / 翻译 / 重生成都读 `{taskNo, runNo}`；
      快照读 `lastRun`；产物走 `/artifacts` 清单 + `files` url；SSE 带 runNo；无 `source.mp4` 死链）。
- [ ] **AC11** `frontend/` 无旧契约残留；`npm run type-check`、`npm run lint`、`npm run build` 通过。
- [ ] **AC12** `.trellis/spec/frontend/api.md` 已按当前后端契约重写，其余前端规范里的失实描述已订正。

## Notes

- `type-check` 是假绿，验收必须实跑。
- SSE 抓取要同 shell 提交 + 订阅；清理工作区用绝对路径。
- 本任务不碰媒体播放（后端无端点，R3 第 6 条），播放器维持 B1 的占位形态。
