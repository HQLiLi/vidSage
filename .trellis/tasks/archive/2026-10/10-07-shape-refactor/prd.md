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

- [x] **AC1** 后端编译通过 + `mvn clean test` 全绿（~~基线：45 类 / 465 用例~~ → **2026-10-08 终值 45 类 / 473 用例 / 0 失败 / 0 错误 / 1 跳过**；用例数随批次递增，必须 `clean`——会移动/删除类）
- [x] **AC2 阶段名单源**：六个阶段名在全项目**只有一处定义**（`SubtitleStageName` 枚举，2026-10-07 批次 2；值同批改为「动词-对象」kebab）。验：旧六值与 `STAGE_*` 常量在 `backend/src/main` 零命中，新六值只落 `SubtitleStageName.java`（原 grep 命令已随改名过期，改验新六值：`grep -rn '"acquire-media"\|"clean-subtitles"' backend/src/main | grep -v SubtitleStageName` 期望无命中）
- [x] ~~**AC3 权重单源**~~ → **已移出本任务**（2026-10-07）。原表述要求「加一个阶段不需要手算任何区间」，那需要把三层权重树**压成一层、每个阶段一个权重**——而扁平化**必然改变进度数值**（现在的任务级边界 `0 / 42 / 66.5 / 70 / 100` 是三层层映射挤出来的半整数，扁平后复现不了）。它是**有意的行为变更**，与 AC7「进度数值不变」直接冲突，混在一起就没法验证。**改为独立任务**（见文末「后续任务」）。本任务只做阶段名的单源（AC2 已覆盖）。
- [x] **AC3' 权重不动**：本任务**不碰**那 11 个权重常量的**数值**。~~`git diff` 里不该出现它们的改动~~（2026-10-08 修正措辞：`AudioPreparationStage` 四个常量出现过**可见性收窄** → `private`——implement 批次 2 明示的顺带项，数值一行未变；其余权重常量零改动，`git diff` 可验）
- [x] **AC4 产出物三问一处答**：任取一个产出物，命名、检查点判据、对外可见性都能在同一处读到（2026-10-07 批次 1：`SubtitleArtifactSpec`）
- [x] **AC5 身份归属**：`WorkspaceService` 里不再有按 `MediaInput` 变体分派的 `switch`（2026-10-08 批次 3，grep 验）；`MediaInput.InWorkspace` 不再携带 ~~`workspaceDir` / `workspaceKey`~~（2026-10-08 修正：「带结果 → 带身份」定案后它携带的是**身份** `key` + `dirLabel`，已解析的 `workspaceDir` 已移除——`grep -n workspaceDir MediaInput.java` 零命中）
- [x] **AC6 公式成文**：「变更类型 → 落点清单」落到 `design.md` §五（2026-10-08 由雏形改写为终稿，每行附 grep 验证命令）
- [x] **AC7 零行为变化**：~~`git diff` 不含方法签名 / 注解 / 类名变更~~（2026-10-08 删句：批次 3 的核心就是改内部签名——`normalize` 删除、构造器加参、`MediaInput` 加方法——该句按原意只约束**对外行为**）；**对外零变化已实测**：HTTP 契约与数据库零变化 ✓；进度事件序列与数值 before/after 逐条零差异（批次 2，2026-10-07，除四条改名映射）；**三条路线 + 本地夹具的工作区目录名 before/after 逐字一致**（批次 3，2026-10-08：URL `…-acb6245a5db8`、上传 `source.mp4-9466c90f2836` 重传命中同目录、本地 `local-fixture.m4a-f6fdf4ed2885`；after 侧断点复用吃到 before 侧产物为同目录行为学佐证；原始日志已按用户确认删除，结论以 handoff 为准）

## Out of Scope

- **关键词 / 思维导图**（下一批功能，本任务只保证它们来的时候形状是接得住的）
- **生命周期统一**（R4 已明确不做）
- **CQRS**（2026-10-07 讨论中已排除：Fowler 明确警示谨慎使用；本项目读写负载无失衡；两条触发条件〔领域复杂到读写共用一个模型会妥协 / 读写负载严重不对称〕与嗅觉测试均不满足）
- **`LocalPath` 身份算法从「路径+大小+mtime」改成内容 sha256**——那是行为变更（要读整份文件、且会改变既有工作区目录的归属），另立任务
- **`@Schema` 落位口径**（内层类型能不能带 `@Schema`）由用户自行推进；`TranscriptionResult` 当前仍带 `@Schema` 而其理由是死的（无外向端点引用），属未决，本任务不动

## 后续任务（2026-10-07 从本任务拆出，尚未建任务）

- **进度权重的三层树压成一层**（用户原话：「每个阶段是一个进度」）。现状是三层层映射（任务级 → prepare 内部 → audio 内部，边界含 `66.5` 这种半整数），共 11 个 `static final int`，分布在 `VideoTaskPipeline` / `SubtitlePreparationStage` / `AudioPreparationStage`，**零配置项**。目标：每个阶段一个相对权重，区间按累加算出。
  - **性质是有意的行为变更**，不是重构：进度数值会变，**前端进度条的走动方式跟着变**（现在跑完音频抽取只走任务级 21%，扁平后会跳得更明显）。所以数值该按「想让用户看到什么」来定，是产品决定。
  - **必须独立验证**：真实任务跑一遍比对 SSE 事件流，确认新的数值是想要的。不能和重构混在一起。
  - 做了它，AC3 才算达成（加一个阶段只需声明权重，不碰区间）。

## Open Questions

无。三处分叉已由用户 2026-10-07 定案，结论见 `design.md` §六。
