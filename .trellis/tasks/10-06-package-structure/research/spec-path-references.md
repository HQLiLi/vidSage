# 研究：spec 里引用受影响包路径的 25 处（2026-10-06 抓取，改动前的位置）

用途：`implement.md` 批次 5（spec 对账）的靶子清单。**行号是改动前的，包移动后会漂移——按文件 + 引用内容定位，别按行号**。

## `directory-structure.md`（12 处，最重）

| 行 | 引用 | 对账动作 |
|---|---|---|
| 49 | `domain/task/VideoTaskRepository`（持久化三层行内） | 端口移 `domain/task/port/` → 改写 |
| 63 | 「如 `domain/task/VideoTask`（`PENDING → RUNNING → …`）」 | `VideoTask` 留根，路径不变 |
| 64 | 端口清单 `ASRProvider` → `domain/transcription/`；`MediaAcquirer`/`AudioExtractor` → `domain/media/`；`TermLookupProvider`/`SubtitleCleanser`/`SubtitleTranslator`/`SubtitleContextSummarizer` → `domain/subtitle/`；`VideoTaskRepository` → `domain/task/` | 全部端口进各自 `port/` → 改写 |
| 65 | 值对象 `TaskNo` → `domain/task/`；跨上下文词汇 → `domain/shared/` | 两者都进 `model/` → 改写 |
| 66 | 纯函数领域政策段（`CheckpointPolicy`/`TermVerification`/`Bcp47Language`/`TaskArtifactScan`） | 进 `policy/` → 改写并补新判据 |
| 100 | `application/diagnostics/ToolDiagnostics` + 出参 `ToolDiagnosticReport` | 端口进 `port/`，出参留根 → 改写 |
| 162 | **阈值句**：「角色子包随 mass 出现…1–3 类的小上下文平铺即可，类多且角色分明再分子包」 | **主产出**：改写成角色桶新判据（见 `design.md` §2） |
| 188 | `domain/task/VideoTaskRepository`（端口在 domain、实现在此） | 同 49 |
| 272 | 表格 `ToolDiagnosticReport` → `application/diagnostics/` | 位置说明改「留根」，@Schema 那部分不动（Out of Scope） |
| 273 | 表格 `TranscriptionResult` → `domain/transcription/` | 改 `domain/transcription/model/`；**其理由栏「直接充当对外契约」与事实不符（无端点用它）属用户自行推进的 @Schema 议题，本次不改** |
| 289 | 「参照：`domain/transcription/`（端口与契约同居上下文包）…」 | 「端口与契约同居」的表述已被本次拆分推翻 → 必须改写，否则自相矛盾 |
| 170 | `infrastructure/provider/llm/` 形态段 | 不拆，核对无残留旧路径即可 |

## `provider-pattern.md`（5 处）

| 引用 | 对账动作 |
|---|---|
| `domain/<上下文>/`（接口位于领域层）+ `domain/transcription/ASRProvider` 例 | 端口改 `domain/<上下文>/port/` |
| 契约例 `domain/transcription/Transcription*` | 改 `domain/transcription/model/` |
| 已落地端口位置清单（`ASRProvider`/`MediaAcquirer`/`AudioExtractor`/`TermLookupProvider`/三个字幕端口） | 全部加 `port/` |
| 参照 `domain/media/MediaAcquirer.java` | 改 `domain/media/port/` |
| 参照 `domain/transcription/AudioSource.java` | 改 `domain/transcription/model/` |

## `quality-guidelines.md`（2 处）

- 「参照：`domain/transcription/ASRProvider.java`、`TranscriptionCommand.java`、`AudioSource.java`…」→ 分别改 `port/` 与 `model/`
- 「不预先抽接口」改判段的 `domain` 端口引用 → 核对并更新

## 其余 3 份（各 1–2 处）

- `database-guidelines.md:37`：值对象 `domain/task/TaskNo`、`domain/shared/WorkspaceKey` → 均改 `model/`
- `error-handling.md:44`：`domain/task/TaskNo` 紧凑构造器 → 改 `model/`
- `logging-guidelines.md:4`：`application/transcription/TranscriptionStage.java` → 改 `application/transcription/pipeline/`
- `index.md:45`：仅提 springdoc 依赖，**核对后大概率无需改动**

## 核对命令

```bash
grep -rn "domain/subtitle\|domain/task\|domain/media\|domain/transcription\|domain/shared\|application/subtitle\|application/media\|application/transcription\|application/diagnostics" .trellis/spec --include=*.md
```
改完再跑一次，逐条确认剩下的是「拆分后的真实路径」或「不依赖具体路径的表述」。
