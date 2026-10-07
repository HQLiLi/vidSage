# 构件改名：VideoTaskPipeline → SubtitlePipeline，三个 *Stage → *Coordinator

## Goal

把「管线」与「阶段构件」的名字改到与事实相符——**纯改名，零行为变化**：

- 管线所有者：`VideoTaskPipeline` → `SubtitlePipeline`
- 三个认领进度阶段的构件：`SubtitlePreparationStage` / `SubtitleCleanupStage` / `SubtitleTranslationStage` → `*Coordinator`

执行者：项目所有者本人写 main 代码；AI 改测试、跑验证、给落点清单（沿用 10-07 的约定）。

## Background：三个名字各自错在哪（2026-10-07 命名讨论的结论）

### ① `Video` 是错的，不只是「不够好」

音频是一等输入，走的是**同一条**管线：

| 证据 | 位置 |
|---|---|
| 产品文案写的是「把**音视频**，变成可检索的文字」 | `frontend/src/views/upload/index.vue:188` |
| 上传页只收「视频或音频文件」 | `frontend/src/views/upload/index.vue:115` |
| 工作区按扩展名走**音频卡片**形态（mp3/m4a/wav/aac/flac/ogg/opus） | `frontend/src/views/workspace/components/MediaPane/index.vue:27` |
| 管线**不分辨**音视频：`MediaInput` 三个变体里没这个维度，`runExtractStage` 里零判断 | `AudioPreparationStage.java:152` 起 |
| 后端领域词汇全是 media / audio；`Video` 只出现在聚合根与管线两处 | `domain/media/` 全包 |

`VideoTaskPipeline` 读起来像「一条装着一堆任务的队列」——限定语说的是**流过去的单位**，不是**这条线在干什么**。改为按**产出**命名：整条线（获取媒体 → 抽音频 → 转写 → 写字幕 → 清洗）的产物就是**字幕**，翻译按需触发，产物也是字幕。

### ② `Stage` 是位置，不是执行者；而且这个位置被两类东西共用

六个 `*Stage` 里：**4 个**认领进度阶段事件（在 `emit(...)` 里报出阶段名），**2 个一个都不认领**（`MediaAcquisitionStage` 选一个 `MediaAcquirer` 并委托；`TranscriptionStage` 包 `ASRProvider`）。

### ③ 「执行者」这个名字已被 domain 端口占满

`domain/subtitle/port/` 里躺着 `SubtitleCleanser` / `SubtitleTranslator` / `SubtitleContextSummarizer`，`domain/media/port/` 里有 `MediaAcquirer` / `AudioExtractor`。**给阶段构件起执行者名，最自然的几个已经被占了**——同名的后果比现在的 `Stage` 更糟（端口与编排者同名不同层）。故编排者改用角色名 `Coordinator`。

## 命名判据（本任务要写回 spec 的正文）

> **`*er` / `*or` = 做事的人**（domain 端口，或端口的实现、分派）。
> **`*Coordinator` = 认领进度阶段、驱动若干协作者的那一步**。
> **管线的所有者不带后缀**，前缀说**产出什么**（`SubtitlePipeline`）。

判据是**从数据里长出来的**，不是先立规则再改代码：4 个阶段构件每个都认领 ≥1 个阶段名（**无一例外**），2 个不认领的一个阶段名都不报。

## Requirements

- **R1** `VideoTaskPipeline` → `SubtitlePipeline`（文件名、类名、构造器、bean 名、注释里的提及）
- **R2** 三个阶段构件改名：`SubtitlePreparationStage` → `SubtitlePreparationCoordinator`、`SubtitleCleanupStage` → `SubtitleCleanupCoordinator`、`SubtitleTranslationStage` → `SubtitleTranslationCoordinator`
- **R3** 命名判据写回 `.trellis/spec/backend/directory-structure.md`，**替换** 2026-10-07 被禁掉的那条
- **R4** 测试类与测试文件名跟着改：`SubtitlePreparationStageTest` → `SubtitlePreparationCoordinatorTest`（同理另两个）、`VideoTaskPipelineTest` → `SubtitlePipelineTest`
- **R5** 零行为变化

## Acceptance Criteria

- [x] **AC1 编译 + 全绿**：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test` → **45 类 / 469 用例 / 0 失败 / 0 错误 / 1 跳过，退出码 0**（与基线一字不差）
- [ ] **AC2 旧名清零**：`grep -rn "VideoTaskPipeline\|SubtitlePreparationStage\|SubtitleCleanupStage\|SubtitleTranslationStage" backend/src .trellis/spec` —— 命中只允许出现在**解释这次改名的叙述里**（`directory-structure.md` 的「管线不跟聚合根前缀」那条刻意点名旧名讲理由）。
  - **现状：仅剩 `CheckpointPolicy.java:18` 一处**——main 源码的 Javadoc 点名旧测试类名 `SubtitlePreparationStageTest`，待改
- [ ] **AC3 零行为变化** —— **挂起**（用户 2026-10-07 定：等全部改完再跑）。跑一条真实任务，SSE 事件流与改名前逐条比对，除 `taskNo` 外零差异；`task.workspace_dir` 逐字不变
- [x] **AC4 判据落地**：`directory-structure.md` 的「暂停中」标记被新判据替换（已核）。
  - **注意不是「全项目没有 `*Stage` 类」**——存量三个（`AudioPreparationStage` / `MediaAcquisitionStage` / `TranscriptionStage`）按 Out of Scope 保留，判据只保证**不再新增**
- [x] **AC5 未误伤**：`VideoTask` / `VideoTaskRepository` / `VideoTaskMapper` **零改动**（已核）；`VideoTaskService` / `TaskStageEvent` 只有对改名类的引用改动（import / 字段 / 构造参数 / `{@link}` / `{@code}`），逐行核过，没有顺手改别的

## Out of Scope

| 不做 | 原因 |
|---|---|
| `AudioPreparationStage` | 用户定：本轮只改「主要的 3 个」（`VideoTaskPipeline` 直接持有的那 3 个）。它仍在 `SubtitlePreparationCoordinator` 内部被调用 |
| `MediaAcquisitionStage` | 用户判断它将来会变成 utils，先不管 |
| `TranscriptionStage` | 用户定：先放着（记为待处理），以后连同管线一起改 → 见「决定」1 |
| 聚合根 `VideoTask` 的窄化 | 牵连 `VideoTaskService` / `VideoTaskRepository` / `VideoTaskMapper` 全族，是独立议题 |
| `SubtitlePipeline` 的**包位置** | 现住 `application/task/pipeline/`；挪去 `application/subtitle/pipeline/` 会让 subtitle 包反向依赖 media / transcription。另议 |
| 权重（三层压一层） | 10-07 拆出的独立任务 |

## 决定（2026-10-07 用户拍板）

1. **`TranscriptionStage`：先放着，不合并**，记为**待处理**——以后连同「管线后续修改」一起改。
   - 留档理由：它已被调用方绕过一半——`SubtitlePreparationCoordinator` 同时持有 `TranscriptionStage` 和它包装的 `ASRProvider`，`route()` / `requiredAudioSpec()` 直接问 provider，只有 `transcribe(...)` 走包装类。**调用方一边用包装、一边直接依赖被包装的接口，说明这层没包住什么。**
2. **`VideoTaskPipeline:34-36` 那段 Javadoc：六个阶段名（acquire / extract-audio / transcribe / write-subtitles）归 10-07 批次 2。**
   - ⚠️ **但那段里的两个类名（`SubtitlePreparationStage` / `SubtitleCleanupStage`）本任务照改**——AC2 要求旧类名清零，不改就过不了。本任务只**不动**那六个阶段名的枚举。
3. **变量名：以 main 为准，测试逐字对齐。** main 里字段 / 构造参数怎么命名，测试就跟着来（测试里的**类型**本来就跑不掉要改）。
4. 是否挂成 10-07 的子任务：未另表态，**按独立任务处理**（`task.json` 的 `parent` 为空）。
5. （已核实，非待定）`SubtitlePipeline` 的 bean 名会随类名变成 `subtitlePipeline`；项目里**没有按名注入**这些类——所有 `@Qualifier` 都只用于 `ChatClient`。零风险，仅记录。

## 与 10-07-shape-refactor 的关系（**开工顺序有约束**）

本任务从 `feature/shape-refactor` 分出去（`base_branch` 已戳为该分支）。

**建议排在 10-07 批次 2 之后做**，理由是成本不对称：

- 批次 2 的落点清单是**逐行核过**的（`SubtitlePreparationStage:57,62`、`SubtitleCleanupStage:153,157,192`、`SubtitleTranslationStage:197,201,262` 等 12 处引用）——**先改名会让这批行号全部失效，清单得重做**。
- 本任务的落点全是**类名出现的位置**，任何时候按类名重扫都是一分钟的事。

即：**批次 2 → 提交 → 从那时的新 HEAD 切本任务的分支 → 改名**。`feature/pipeline-stage-rename` 这条分支**尚未创建**。
