# 原始语种以字幕产物为准（前端锁定 + 后端校验）

## Goal

修掉「原文其实是中文，翻译请求却把源语种传成英语，译文跟着乱掉」的缺陷：源语种以字幕产物里
ASR 识别出来的语种为准。前端拿到之后把源语种固定住、不允许手选；后端在开跑之前核对请求值与
实际语种，对不上就 400。

## Background

- 语种来源链路已经存在，不缺数据：ASR 自动识别 → `TranscriptionResult.language` →
  `SubtitleTrack.language` → 随 `segments.json` 落盘 → 清洗产物原样继承 → 前端
  `workspaceSubtitles.cleanedTrack.language`。
- 缺陷有两处：
  1. 前端把 LLM 写的 `context.sourceLanguage`（自由文本，后端注释明说「模型拿不准时为空串」）
     排在 ASR 实测值前面；
  2. 不管拿到什么，源语种下拉都能随便改；后端只校验白名单，从不与真实语种核对。
- 已知边界：ASR 的 `language` 可能为 `null`，也可能是白名单外的语种。这两种情况下前端锁不住、
  后端也无从比对。
- ~~ASR 的 `language` 只是「可能」为 null~~ ——**2026-10-26 取证后订正**：云端 ASR 模型
  `qwen-audio-3.1-asr-flash-filetrans` 的结果 JSON 里**根本没有** `language` 字段（原始 JSON 全文
  0 次命中，`transcripts[]` 与 `properties` 都没有；加 `language_hints: ["auto"]` 也不返回）。
  库里 4 个任务的 `language` 全是 `null`，`original-segments.json` 里就是 null。所以这条链路目前
  100% 拿不到 ASR 语种，不是偶尔缺失。
- 唯一有值的语种判断来自 `context.sourceLanguage`（DeepSeek 通读字幕文本后写的短码，实测为 `zh`）。
  故补一条：**清洗阶段在 ASR 没给语种时，用上下文的语种兜底写进 `SubtitleTrack.language`**，
  让前端锁定与后端校验读同一份真数据，而不是各自去猜 context。

## Requirements

**前端**

- R1 源语种优先取 CLEANED 产物 segments 里的 `language`（ASR 实测值），拿不到再用
  `context.sourceLanguage` 兜底。优先级与现状相反。
- R2 拿到可归一的语种后，字幕区头部的源语种以**只读文本**展示，不再是可操作的下拉。
- R3 两个语种之间的互换按钮取消，位置上换成一个 `to` 标签。
- R4 互换能力整体下线：`swap` 事件、store 里的 `swapLanguages`、页面上的处理函数一并删掉，
  不留死代码。
- R5 拿不到语种（`null` 或归一失败）时，源语种退回现在的可编辑下拉，翻译链路不受影响。

**后端**

- R5b **清洗阶段补齐语种**：写 `-clean` 产物时，`SubtitleTrack.language` 取 ASR 给的值；
  ASR 没给（实测恒为 null）就用本次上下文的 `sourceLanguage` 兜底，且只收能归一到支持白名单的
  写法（`zh` → `zh-Hans`），归不拢就留 `null`，不把「中文」这种自由文本写进 BCP-47 字段。
- R6 `POST /api/tasks/{taskNo}/translate` 在创建 run 之前读 CLEANED 产物的实际语种，与请求里的
  `sourceLanguage` 比对；两边都先归一再比（`zh` / `zh-CN` 这类别名要认）。
- R7 不一致 → 400，文案要说明「字幕实际识别出的语种」，让人一眼知道该传什么。
- R8 产物语种为空、或不在支持白名单里 → 跳过比对，按请求值继续（比对不了就别拦）。
  补齐上线前生成的旧产物仍会是 `null`，它们走的就是这条放行分支。

**不变**

- R9 请求体字段不变（`sourceLanguage` 还在），只加校验；落盘产物形状不变（`language` 字段本来就
  在，只是从此会有值）。
- R10 既有行为不退：同语种跳过翻译、`force` 语义、409 互斥、白名单外 400 全部保持原样。

## Constraints

- 比对依据只能是 ASR 的 `SubtitleTrack.language`，**不能**用 `context.sourceLanguage`——
  那是 LLM 自由文本。
- 校验必须在同步守卫段完成（返回 400），不能塞进异步执行体。
- 文案与注释沿用现有中文风格（大白话首行，见项目注释规范）。

## Acceptance Criteria

- [x] AC1 打开一个原文为中文的任务：源语种显示「简体中文」，页面上没有可操作的下拉
      （查询不到 `select[aria-label="源语言"]`）。
- [x] AC2 源语种与目标语种之间是一个 `to` 文本，互换按钮及其图标不存在。
- [x] AC3 源语种锁定为简体中文、目标也选简体中文时，翻译按钮禁用并提示语种相同。
- [x] AC4 直接 POST translate，`sourceLanguage=en` 而产物实际是 `zh-Hans` → 400；响应文案含
      实际识别的语种；任务的 `lastRun` 不变（没建出 run 占位）。
- [x] AC5 传 `sourceLanguage=zh`（别名，产物是 `zh-Hans`）→ 校验通过，正常触发翻译。
- [ ] AC6 ~~产物 `language` 为 `null` 或白名单外时：前端源语种仍是可编辑下拉~~ ——**2026-10-26
      订正**：前端有 context 兜底，旧产物（`language` 仍为 null）只要 context 写着能认的语种，
      依然会锁定（实测任务 `6d452f67…` 显示「简体中文」静态文本）。真正的「可编辑下拉」条件是
      **track 和 context 都给不出可归一的语种**；此情形现有数据造不出来，只走代码路径保证。
      后端在 `language` 为 null 或白名单外时跳过比对、按请求值继续——这条不变。
- [x] AC8 原始字幕 `language` 为 `null`、上下文 `sourceLanguage` 为 `zh` 时，新清洗出的
      `clean-segments.json` 里 `language` 是 `zh-Hans`；原始已有语种时以原始为准；上下文写成
      「中文」这类对不上的自由文本时保持 `null`。
- [x] AC9 前端 `npm run lint` 与 `npm run type-check` 通过；后端新增单测与既有相关单测通过
      （`clean test-compile` 判编译，不是增量）。

## Out of Scope

- 不处理「ASR 识别错语种」的自救路径（用户仍可重新生成字幕）。这是本次显式接受的代价。
- 不动译文产物里的 `sourceLanguage` 字段语义。
- **不换 ASR 模型**：要拿到真实语种检测结果得换会返回 `language` 的模型（Paraformer 系列有文档），
  影响转写质量与成本，另开任务；已登记进 `.trellis/workspace/HeQiu/known-bugs.md`。
- 不回填历史产物：旧 `clean-segments.json` 里的 `null` 保留，重新生成字幕才会带上语种。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning
  before `task.py start`.
