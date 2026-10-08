# 进度权重扁平化

## Goal

把进度权重从「三层映射的 11 个散常量」压成「每个主管线阶段一个相对份额，任务级区间按声明顺序累加算出」。

性质以结构改造为主：改造前后 SSE 事件流除**一格有意修正**外逐格相同，因此验收可机器判。

## Background：现状的三层映射

每个构件对上报自己的 `0..100`，由父级负责压进子区间；装了层层嵌套，就换算层层：

```
任务级              prepare [0,70)    clean [70,100)
  └ prepare 内部      audio [0,60)    transcribe [60,95)   write [95,100)
      └ audio 内部      acquire [0,50)   extract [50,100)
```

- 三层映射的区间/权重常量共 **11 个**，散在 3 个类里（`AudioPreparationStage` 4 / `SubtitlePreparationCoordinator` 4 / `SubtitlePipeline` 3）；另有 `SubtitleCleanupCoordinator` 与 `SubtitleTranslationCoordinator` 各持一对「自报 0..100 的端点」常量（不参与映射）。
- **零配置项**——全是 `private static final int`。
- 折算成任务级，这 11 个常量等价于**每阶段一个数**：`acquire 21 / extract 21 / transcribe 24.5 / write 3.5 / clean 30`。三层不是三套数，是同一组数被三次归一化反复表达。
- 加一个阶段要手改三层里的边界常量，改错一格没有编译器保护。

## Requirements

**R1｜阶段定义单源**——每个主管线阶段只声明一个相对份额；任务级区间由声明顺序累加算出，不再手写，也不再逐层换算。

**R2｜加阶段的代价**——加一个主管线阶段 = 加一条枚举项（份额）+ 在编排处插一行调用；不需要调整任何其他阶段的数字。

**R3｜换算只发生一次**——进度事件的值由**发事件的构件**直接取本阶段区间端点；`SubtitlePipeline` 不再持有任何区间常量、不再 remap。

**R4｜连续进度生产者仍是例外**——clean 的批级进度由 infrastructure 的 cleanser 报自己的 `0..100`，由它的调用者（`SubtitleCleanupCoordinator`）换算进 clean 区间。基础设施层不感知任务级区间。

**R5｜份额数值取「保真档」**——`acquire 42 / extract 42 / transcribe 49 / write 7 / clean 60`（总和 200，累加后截断取整）。数值是渲染进度条用的相对份额，**不追求反映真实耗时**。

**R6｜翻译不进权重表**——`TRANSLATE_SUBTITLES` 不参与任务级分配，仍自成 `0..100` 直通；对它的区间查询调用即抛。

**R7｜三个存量 `*Stage` 改名**（纯改名，行为零变更）：
- `AudioPreparationStage` → `AudioPreparationCoordinator`
- `MediaAcquisitionStage` → `MediaAcquirerDispatcher`
- `TranscriptionStage` → `Transcriber`

**R8｜写字幕的「开始」事件改为发本阶段起点**——与其余四个阶段一致（今天是特例：开始事件发的是 `WRITE_DONE`）。

## Constraints

- 保持**零配置项**（不引入任何新配置）。
- 技术栈、依赖不动；翻译链路不动。
- 不碰（明确出范围）：`SubtitlePipeline` 执行模型拆分、Javadoc 全局重构、`backend-before-reconstruction/` 删除、`LocalPath` 身份算法改 sha256、`@Schema` 落位口径。
- 注释遵循既有口径：复述类（抄取值 / 讲来历）直接删，不是压缩。

## Acceptance Criteria

| # | 判据 | 验证方式 |
|---|---|---|
| AC1 | 三层映射的 11 个常量清零 | `grep -rn "PREPARE_END\|CLEAN_START\|CLEAN_END\|ACQUIRE_END\|TRANSCRIBE_START\|TRANSCRIBE_DONE\|WRITE_DONE\|ACQUIRE_START\|ACQUIRE_DONE\|EXTRACT_START\|EXTRACT_DONE" backend/src/main` → 零命中 |
| AC2 | clean 的自报端点常量清零（翻译类里保留） | `grep -n "STAGE_BEGIN\|STAGE_DONE" backend/src/main/java/com/vidsage/application/subtitle/pipeline/SubtitleCleanupCoordinator.java` → 零命中 |
| AC3 | 管线不再懂区间 | `SubtitlePipeline.java` 里 `ProgressMapper` 零引用 |
| AC4 | 换算只剩一处 | `grep -rn "ProgressMapper" backend/src/main` → 仅 `SubtitleCleanupCoordinator` 与该类自身 |
| AC5 | 累加机制有单测 | 新增 `SubtitleStageName` 单测：首阶段起点 0、末阶段终点 100、翻译调用即抛、区间单调不重叠 |
| AC6 | 加阶段的代价 | 新增一个枚举项的 diff = 枚举 1 行 + 编排 1 行，不含任何数字调整（人工核对 diff） |
| AC7 | 真实链路逐格比对 | 跑一次真实任务（上传路线）存 SSE 事件流，与 `dev`（`dff027b`）对照实例比对：**10 格相同 + 1 格有意变更**（写字幕「开始」`70 → 66`） |
| AC8 | 三个类改名完成 | `grep -rn "AudioPreparationStage\|MediaAcquisitionStage\|TranscriptionStage" backend/src` → 零命中 |
| AC9 | 回归 | `cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test` 全绿；写死进度数值的断言已按新区间改写 |

## Out of Scope

- 进程内的「阶段顺序」仍然只存在于编排代码的语句顺序里（枚举声明顺序提供区间，不提供执行）——见 `design.md` 的诚实边界。
- 翻译链路的真实运行验证（省配额）：它不受本任务影响，其区间查询路径由单测覆盖。
- `SubtitlePipeline` 执行模型拆分（executor / semaphore / 排队 vs 编排）：AI 建议等本任务落地后另评估。

## Open Questions

无。
