# 进度载荷命名收口：StageEvent → StageProgress

> **状态：planning，未开工。** 本文件目前只记录来源与已定决策，完整 PRD + design + implement 待开工时展开。
> 来源：2026-10-11 与 `10-11-sse-surface-consolidation` 同一轮讨论中拆出（见该任务 `prd.md` 的 D5）。

## Goal

把管线进度载荷的命名**归队**到本仓库早已在用的 Progress 词族，并订正两处过期的 Javadoc。

仓库现状是同一件事有两个词在打架：

| 角色 | 名字 | 用词 |
|---|---|---|
| 回调端口（收它的） | `ProgressListener` | **Progress** |
| 映射器（造它的） | `ProgressMapper` | **Progress** |
| SSE 消息（装它的） | `TaskProgressMessage` | **Progress** |
| 通知器（送它的） | `TaskProgressNotifier` | **Progress** |
| **载荷本身** | `StageEvent` | **Event** ← 就它不合群 |

于是 `ProgressListener.onEvent(StageEvent)` 一行里出现两个词。起因是`StageEvent` 这个名字
**一眼看不出它是什么**（2026-10-11 项目所有者原话）。

## 已定决策（2026-10-11 讨论中拍板）

| 项 | 现名 | 新名 | 规模 |
|---|---|---|---|
| 载荷（`domain/shared/model/`） | `StageEvent` | **`StageProgress`** | 31 文件 / 176 处 |
| 嵌套枚举 | `StageEvent.StageState` | **`StageProgress.State`** | 19 文件 / 88 处 |
| 回调方法 | `ProgressListener.onEvent(...)` | **`onProgress(...)`** | 25 处 |

合计约 **285 处**。

**被否的候选**（存档，勿重提）：
- `StageProgressPayload` —— `Payload` 与 `DTO` / `VO` 同族，是**分类词**，`spec/backend/directory-structure.md:281`
  已明确排除此类词进类名；且本类型住在 `domain/shared/model/` 属**领域词汇**，戴传输层的帽子是层次错位。
- `StageEvent` 保留 —— 与"一眼看出是什么"的诉求相抵。

## 位置不动（已查证，有判据）

`StageProgress` 留在 `domain/shared/model/`，**不能搬**：

- 它**不是 SSE 专属**，是**管线进度的中立契约**。`ProgressListener` 有 12 个 main 消费者横跨三层：
  infrastructure（`LlmSubtitleCleanser` / `LlmSubtitleTranslator` 按批上报）、application（四个 coordinator
  + `SubtitlePipeline`）、domain 端口（`SubtitleCleanser` / `SubtitleTranslator`）、shared（`ProgressMapper.remap`）。
- 搬到 `application/` → infrastructure 依赖它就构成**反向依赖 application 的分层倒置**
  （`ProgressListener` 自己的 Javadoc 写明了这条）。
- 留 `domain/shared/` 的判据在 `spec/backend/directory-structure.md:67`（task / transcription / subtitle
  三处共用的进度词汇）。
- SSE 只是**链尾那一跳**：`SubtitlePipeline` → `TaskProgressNotifier` → 组装 `TaskProgressMessage` → emitter。

## 一并要做的注释订正（失实）

两处「过渡形态」的说法已经过期——MVP-1 早已定锤，且定锤的**结论是"保持现状"**
（`archive/2026-10/09-24-vidsage-mvp1-submit/design.md:12`：「`StageEvent` 本体保持最小不动」）。
按 `doc-revision-convention`（过期的重写为现状）：`StageEvent.java` 与 `ProgressListener.java` 的
Javadoc 改为陈述现状，并把「被三层共用、SSE 只是链尾消费者」写进去（比原来那句悬空承诺有用）。

## 范围与不做

- **不做**：`TaskSseConnectionManager` 的配对收口、`terminal` 契约字段、前端断连处理——那些归
  `10-11-sse-surface-consolidation`。
- **不做**：`SSE 载荷按消费方裁剪`（`TaskProgressMessage.stage` 只留 progress + message）——该问题
  2026-10-11 讨论过，决定"先不管"；真要做得单独议。

## 待决（开工时展开）

- 政策类 `TaskStageEvent`（`domain/task/policy/`，5 文件 / 16 处）是否一并改名——本轮定为"暂留"，
  理由是载荷改名后它与 `StageProgress` 已不再撞脸，剩下的只是 `-Event` 后缀名不副实。
- 本次改名与「前端 `types/task.ts` 的 `StageEvent` 镜像」「spec 提及」的同步清单。

## 对 SSE 契约的影响：零

类型名不进 JSON（payload 里的键是 `stage`，值才是那四个字段），故本任务**不改对外契约**、
不影响 SSE 流的对照验证。

## Notes

- 创建：2026-10-11，`base_branch = dev`，`--no-start`（不抢当时会话的活动任务）。
- 仓库先例：`10-06-package-structure`（67 类改名移动）、`10-06-naming-alignment`（Q1–Q7 逐条裁定）、
  `10-07-pipeline-stage-rename`——三次命名收口都是独立任务，本次沿用。
