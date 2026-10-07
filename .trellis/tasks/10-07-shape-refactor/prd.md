# 形状重构：阶段 / 产出物 / 输入身份的统一表示

> 承接 2026-10-07 会话的架构讨论。前置任务 `10-06-package-structure`（包结构分桶）已归档。
> **本任务的执行者是项目所有者本人**，不是 sub-agent——产出是设计蓝图 + 改造步骤 + 示例代码，用户照做。

## Goal

用户原话：

> 标准是我自己能知道我加新功能时该做什么，而不是不知道如何添加，所以我需要一个**类似于公式的架构**，而目前只有 DDD 的形，我却不能直接套 DDD 的做法。

目标：让「加一个**阶段** / 加一个**产出物** / 加一种**输入来源**」这三类变更，都能翻译成**确定的落点清单**——改哪几处是可预测的常数，而不是每次现场摸索。

## Background：现状诊断（2026-10-07 审查 application 层得出）

三个核心概念在当前代码里**只有具体实例、没有统一表示**。这是「公式写不出来」的物理原因：公式的输入是「变更类型」，而**被加的那个东西没有类型**。

### ① 「阶段」不存在

六个阶段（`acquire` / `extract-audio` / `transcribe` / `write-subtitles` / `clean` / `translate`）在代码里是：

- **六个类**：`MediaAcquisitionStage`、`AudioPreparationStage`、`TranscriptionStage`、`SubtitlePreparationStage`、`SubtitleCleanupStage`、`SubtitleTranslationStage`
- **三份名字常量**：`AudioPreparationStage`（acquire / extract-audio）、`SubtitlePreparationStage`（transcribe / write-subtitles）、`StageEvent`（translate / clean）
- **三层进度权重**：`VideoTaskPipeline`（0..70 / 70..100）、`SubtitlePreparationStage`（内部 0..60 / 60..95 / 95..100）、`AudioPreparationStage`（自身 0..100）——三层各自 remap

最能说明问题的一处：`StageEvent` 的 `@param stage` 注释用文字指着**别的类里的常量**（`"acquire" / "extract-audio"`）。注释必须指向别处，就是单源缺失的指纹。

另：`SubtitlePreparationStage` 名不副实——它不是一个阶段，是**四个阶段的编排器**（acquire / extract-audio / transcribe / write-subtitles 都藏在它内部）。所以对外说「2 个阶段」的地方实际是 6 个。

### ② 「产出物」不存在

一个产出物的三件事分散在三处，以 `segments-<route>-clean.json` 为例：

| 它要知道的事 | 现在写在哪 |
|---|---|
| 文件叫什么 | `shared/subtitle/SubtitleArtifactNames` |
| 什么算「已完成」（检查点） | `domain/subtitle/policy/CheckpointPolicy` |
| 能不能被用户下载 | `domain/task/policy/TaskArtifactScan` 的**后缀白名单**（`*.srt` 与 `segments-*.json`） |

三处各管一段，没有一处写着「这三个答案是同一个产出物的三个属性」。**注意第三处跨了上下文**——产物是 subtitle 的，可见性判据却住在 `domain/task`。

### ③ 「输入身份」不存在（且类型自破约束）

`MediaInput` 的类注释自述：

> **负面约束**：本类型只承载「位置」，不含进度监听等编排参数……唯一携带工作区元数据的是 `InWorkspace`

而它的成员是：

```java
record RemoteUrl(String value) implements MediaInput { }        // 只有位置 ✓
record LocalPath(Path value) implements MediaInput { }          // 只有位置 ✓
record InWorkspace(Path value, Path workspaceDir,               // 位置 + 工作区身份 ✗
                   WorkspaceKey workspaceKey) implements MediaInput { }
```

**类型自己声明了一条约束，自己破了一条。**

为什么会长出这个特例：身份算法按「输入从哪来」分派（`WorkspaceService.normalize()` 的三路 switch）——URL 取 URL 原文、LocalPath 取「绝对路径｜大小｜mtime」、`InWorkspace` **直接抛异常**（代码明说它「不参与哈希重算」）。上传路线的身份是**文件内容的 sha256**，而「从位置推导」这条路给不出内容哈希，于是另起炉灶塞进变体里。

`LocalPath` 那个三元组还不稳定：挪个位置、或 `touch` 一下，身份就变 → 重算目录 → 断点复用失效。

### ④ 「生命周期」不存在（本任务明确不处理）

`VideoTask` 里住着两台状态机：主状态机（`TaskStatus` + `start/succeed/fail`）与翻译子状态机（`TranslationStatus` + `translationStart/translationSucceed/translationFail`）。

**决策 4 的结论是「不做」**，理由见下。

## Requirements

- **R1 阶段成为类型**（决策 1 = 要）：阶段名与进度权重各自全项目单源；「加一个阶段」的改动是常数，且不需要手算任何进度区间。
- **R2 产出物成为类型**（决策 2 = 要）：一个产出物的「命名 / 检查点 / 对外可见性」三问在同一处可答；「加一个产出物」= 加一条登记。
- **R3 输入身份收敛**（决策 3 = 要）：身份成为输入**自身的属性**，而不是外部 `switch` 反推的结果；`WorkspaceService.normalize()` 的三路 switch 消失；`MediaInput.InWorkspace` 的契约破口随之补上（不再需要携带 `workspaceDir` / `workspaceKey`）。
- **R4 生命周期不统一**（决策 4 = 不做）：**明确记录不做及其理由**，不得顺手做掉。理由——只有一个实例（翻译），两种候选形状（「生命周期是任务的」vs「是产物自己的」）落在同一份代码上看起来一模一样，分不出来；造错抽象比没有抽象更糟（没有抽象时照抄一遍即可，造错之后要先推翻再重做）。
- **R5 零行为变化**：类名、方法签名、HTTP 契约（含 SSE payload 字段名与类型）、JSON 形状、数据库结构、**进度事件的序列与数值**均不变。R3 允许删除 `normalize()` 的 switch 与 `InWorkspace` 的两个字段，但**身份串的取值必须与改造前逐字一致**（同一输入仍落到同一工作区目录）。
- **R6 产出物：变更类型 → 落点清单**。这是本任务的**最终交付物**——一张可代入的表，每类变更一行：输入是什么、判据是什么、要碰哪几处。

## Acceptance Criteria

- [ ] **AC1** 后端编译通过 + `mvn clean test` 全绿（基线：45 类 / 465 用例 / 0 失败 / 1 跳过；必须 `clean`——会移动/删除类）
- [ ] **AC2 阶段名单源**：六个阶段名在全项目**只有一处定义**。`grep -rn '"acquire"\|"extract-audio"\|"transcribe"\|"write-subtitles"\|"clean"\|"translate"' backend/src/main` 的命中必须全部落在定义点（及使用它的引用处，不得再有第二处字面量定义）
- [ ] **AC3 权重单源**：进度区间只在一处计算，且**加一个阶段不需要手算任何区间**（新增阶段只声明自己的相对权重）
- [ ] **AC4 产出物三问一处答**：任取一个产出物，命名、检查点判据、对外可见性都能在同一处读到
- [ ] **AC5 身份归属**：`WorkspaceService` 里不再有按 `MediaInput` 变体分派的 `switch`；`MediaInput.InWorkspace` 不再携带 `workspaceDir` / `workspaceKey`
- [ ] **AC6 公式成文**：「变更类型 → 落点清单」落到 `design.md`，每行的落点都**可 grep 验证**（不是「大概改这一片」）
- [ ] **AC7 零行为变化**：`git diff` 不含方法签名 / 注解 / 类名变更；HTTP 契约与数据库零变化；**进度事件的序列与数值逐一比对无差异**（这是本任务最容易破的一条，因为权重重排正是 A1 的动作对象）

## Out of Scope

- **关键词 / 思维导图**（下一批功能，本任务只保证它们来的时候形状是接得住的）
- **生命周期统一**（R4 已明确不做）
- **CQRS**（2026-10-07 讨论中已排除：Fowler 明确警示谨慎使用；本项目读写负载无失衡；两条触发条件〔领域复杂到读写共用一个模型会妥协 / 读写负载严重不对称〕与嗅觉测试均不满足）
- **`LocalPath` 身份算法从「路径+大小+mtime」改成内容 sha256**——那是行为变更（要读整份文件、且会改变既有工作区目录的归属），另立任务
- **`@Schema` 落位口径**（内层类型能不能带 `@Schema`）由用户自行推进；`TranscriptionResult` 当前仍带 `@Schema` 而其理由是死的（无外向端点引用），属未决，本任务不动

## Open Questions

设计有三处真实分叉，见 `design.md` 的「待定分叉」一节——需用户确认后才写 `implement.md`：

1. 阶段类型做到哪一档（只单源 vs 完整列表化）
2. 产出物用静态登记表还是每类一个类型
3. 身份的承载形态（`MediaInput` 的方法 vs 独立类型）
