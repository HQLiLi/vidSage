# 注释收口 · 后端测试源码

> **状态：planning，未开工。** 父任务 `10-12-comment-consolidation` 的**子任务 5**。
> **粒度标准在 `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令），不在这里也不在父 `design.md`**
> ——父 `design.md` 曾复制过一份标准，2026-10-12 已删（复制必然漂移）。需求与跨子任务验收见父 `prd.md`。
> **门 G1 已通过（2026-10-12，domain 试点与 application 两层均已收口归档）**；本层开工时按**正典当前版本**写 `implement.md`。

## Goal

收口 backend 测试源码 56 个文件。但注意：**这层本来就克制**（注释占比 9.5%，main 是 27.3%），
所以本子任务的定位不是「删」，而是**核查两类具体毛病**：

1. **变更史残留**：测试里往往会留「回归卡」「先例」之类的历史引用
   （已知先例：`SubtitleTranslationCoordinatorTest`、`LlmSubtitleCleanserTest` 等在被 spec 引用时
   带过日期/任务号）。扫一遍，有则删。
2. **注释复述测试名**：既有 spec 明确要求「测试方法名描述行为」，那方法名已经说清了的事，
   其上方的注释若只是重复一遍，属多余。**但**：测试注释里描述**为什么选这个用例**、
   **为什么阈值是这个数**、**这条钉住了什么反例**的，是最有价值的注释，必须留——
   尤其 spec 里点名的「必须有一条钉住双目标的用例」那类。

## 已知的层内重点

- `LlmSubtitleCleanserTest.java`（533 行 / 41 注释）：守卫校验的用例，注释多与阈值/基线有关；
- `SubtitleTranslationCoordinatorTest.java`（664 / 86）；
- `SubtitlePipelineTest.java`（685 / 47）；
- `VideoTaskServiceTest.java`（637 / 38）——注释占比 6.0%，基本不必动。

## 范围

**做**：`backend/src/test/java` 全部注释的核查与必要收口。

**不做**：同父 `prd.md`「不做」四条；另**不改测试方法名**（命名规则已有 spec 管，本任务只管注释）；
**不动 `@TempDir(cleanup = CleanupMode.NEVER)` 那条约定性注释**（属负面约束，删了会有人踩 flake 的坑）。

## 验收标准（在父 AC 之外的本层条目）

- [ ] `backend/src/test/java` 下变更史 / 归档指针模式命中数为 0（清单见正典 §六，**不要硬编条数**）。
- [ ] 抽查 5 个测试类：留下的注释都在说「为什么」而非「做什么」。
- [ ] 被删的注释里，若有钉住反例/阈值的说明，逐条确认「删了不会丢信息」。
- [ ] `mvn clean test` 全绿，用例数**不少于**收口前（本层只应改注释，不应影响用例）。
