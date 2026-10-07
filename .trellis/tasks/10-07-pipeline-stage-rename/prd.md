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

- [ ] **AC1 编译 + 全绿**：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test`
- [ ] **AC2 旧名清零**：`grep -rn "SubtitlePreparationStage\|SubtitleCleanupStage\|SubtitleTranslationStage\|VideoTaskPipeline" backend/src .trellis/spec` → 无命中
- [ ] **AC3 零行为变化**：跑一条真实任务，SSE 事件流与改名前逐条比对，**除 `taskNo` 外零差异**；`task.workspace_dir` 逐字不变（类名不影响事件内容，但这是最便宜的一道保险）
- [ ] **AC4 判据落地**：`directory-structure.md` 的「暂停中」标记被新判据替换，且全项目不再有 `*Stage` 结尾的类
- [ ] **AC5 未误伤**：`VideoTaskService` / `VideoTask` / `VideoTaskRepository` / `VideoTaskMapper` / `TaskStageEvent` 一行未动

## Out of Scope

| 不做 | 原因 |
|---|---|
| `AudioPreparationStage` | 用户定：本轮只改「主要的 3 个」（`VideoTaskPipeline` 直接持有的那 3 个）。它仍在 `SubtitlePreparationCoordinator` 内部被调用 |
| `MediaAcquisitionStage` | 用户判断它将来会变成 utils，先不管 |
| `TranscriptionStage` | AI 建议合掉，用户未表态 → 见 Open Questions 1 |
| 聚合根 `VideoTask` 的窄化 | 牵连 `VideoTaskService` / `VideoTaskRepository` / `VideoTaskMapper` 全族，是独立议题 |
| `SubtitlePipeline` 的**包位置** | 现住 `application/task/pipeline/`；挪去 `application/subtitle/pipeline/` 会让 subtitle 包反向依赖 media / transcription。另议 |
| 权重（三层压一层） | 10-07 拆出的独立任务 |

## Open Questions

1. **`TranscriptionStage` 是否合并掉？** 它已被调用方绕过一半——`SubtitlePreparationCoordinator` 同时持有 `TranscriptionStage` 和它包装的 `ASRProvider`，`route()` / `requiredAudioSpec()` 直接问 provider，只有 `transcribe(...)` 走包装类。**调用方一边用包装、一边直接依赖被包装的接口，说明这层没包住什么。** AI 倾向合掉；它会影响本任务的范围（少一个类、少一个名字）。
2. **`VideoTaskPipeline:34-36` 那段 Javadoc** 列着六个阶段名（acquire / extract-audio / transcribe / write-subtitles），与 10-07 批次 2 要消灭的「文字复述」**重叠**。本任务顺手处理，还是留给批次 2？
3. **变量名（小驼峰）跟不跟改**：`subtitlePreparationStage` / `cleanupStage` / `translationStage` / `pipeline`。测试里那些 `SubtitleCleanupStage s = service()` 的**类型**必须跟着改，跑不掉；字段名是否也跟着改是选择。
4. **`SubtitlePipeline` 的 bean 名**会随类名变成 `subtitlePipeline`（`@Component` 默认取类名首字母小写）。已核实：项目里**没有按名注入**这些类——所有 `@Qualifier` 都只用于 `ChatClient`。所以零风险，仅记录。

## 与 10-07-shape-refactor 的关系（**开工顺序有约束**）

本任务从 `feature/shape-refactor` 分出去（`base_branch` 已戳为该分支）。

**建议排在 10-07 批次 2 之后做**，理由是成本不对称：

- 批次 2 的落点清单是**逐行核过**的（`SubtitlePreparationStage:57,62`、`SubtitleCleanupStage:153,157,192`、`SubtitleTranslationStage:197,201,262` 等 12 处引用）——**先改名会让这批行号全部失效，清单得重做**。
- 本任务的落点全是**类名出现的位置**，任何时候按类名重扫都是一分钟的事。

即：**批次 2 → 提交 → 从那时的新 HEAD 切本任务的分支 → 改名**。`feature/pipeline-stage-rename` 这条分支**尚未创建**。
