# 会话移交（2026-10-08）：下一个任务「进度权重扁平化」

> **给新会话**：用户会要你**创建**这个任务。创建要用户点头（Phase 1.0）——他主动来提这事即视为同意，
> 但**动手实现仍要等规划工件评审 + `task.py start`**。材料基本齐了，本文是让你**别从零推导**的速查。

## 任务一句话

把进度权重从「三层映射的 11 个散常量」压成「每阶段一个权重、区间按累加算出」。
**性质是有意的行为变更**（进度数值会变、前端进度条走动会变），**必须独立验证**，不能和重构混着验。

## 材料在哪（先读这些）

1. `.trellis/tasks/archive/2026-10/10-07-shape-refactor/prd.md` 的**「后续任务」一节**——本任务的出生地：
   为什么从形状重构里拆出来、性质定论、验证要求，全在那儿。
2. 同目录 `design.md` §二（阶段概念的目标形状是「名字 + 相对权重」——上个任务只落了名字，权重是留的口子）
   与 §五落点清单终稿（「加一个管线阶段」行注明权重仍要手算）。
3. 同目录 `implement.md` / `handoff.md` 的「为什么批次 2 不做权重」节（与 AC7 冲突的完整推演）。
4. spec：`.trellis/spec/backend/directory-structure.md`——落位判据（归属优先）、`infrastructure/fs/` 分层行、
   端口归属实例都是 2026-10-07/08 新立的，**别按旧口径讨论**。

## 关键事实（已核实，直接引用，别重新 grep）

- **散点**：4 个文件 29 处——`AudioPreparationStage` 12 / `SubtitlePreparationCoordinator` 11 /
  `SubtitlePipeline` 5 / `shared/progress/ProgressMapper` 1；其中权重 `static final int` 共 **11 个，零配置项**。
- **现状边界值**（三层层映射挤出来的）：任务级 `0 / 42 / 66.5 / 70 / 100`；
  实测事件序列（批次 2 验收）：`0/21/21/42/42/66/70/70/70/100/100`。
- **载体已就位**：`domain/subtitle/model/common/SubtitleStageName` 枚举（六阶段，值 = 动词-对象 kebab）。
  扁平化是往它身上加权重字段，还是另立「阶段定义」载体——**这是新会话的形状讨论，
  按惯例先给方案 + 理由 + 备选，等用户拍板**。
- **数值该定成什么是产品决定**（用户原话方向：「每个阶段是一个进度」）——数值表先给用户看，
  由他定「想让用户看到什么」，别自己拍。
- **验证办法**：真实任务跑一遍存 SSE 事件流——但这次比的**不是**「零差异」，而是
  「新数值是不是想要的」。before 侧代码 = 当前 `dev`（`dff027b`），起对照实例照批次 2/3 的
  worktree 办法（`../vidsage-before` 还在，但停在 `6bda4f5`，**要用 dev 重新 checkout 一份**）。
- 改动会影响 `SubtitlePipeline` 的进度 remap、批级 `ProgressMapper.remap` 调用点——
  单测里大量 `assertEquals(40, progress…)` 之类**写死数值的断言会跟着改**（AI 的活）。

## 环境坑速查（都是实测过的）

- `mvn -q clean test` **必须带 `clean`**（IDE 同时编译 `target/classes` 会撞出 `NoClassDefFoundError`）
- 起实例后 `TaskStop` 杀不掉 `spring-boot:run` 的子 JVM → 按 PID `taskkill //PID <n> //F`
- Git Bash 的 `/tmp` 与 Windows 程序不通用 → 文件用相对路径或 Windows 绝对路径
- yt-dlp 下 B 站可能撞会员格式提示 / 偶发 300 秒超时（与代码无关）→ 验收优先走**上传路线**

## 工作约定（memory 会自动加载，这里备忘）

main 用户写、测试 AI 改；改完停等审查、逐门批准；结构/选型先方案+备选再动手；命名先 grep 算账；
注释要少（复述类直接删）；验收尽量机器可判。

## 上个任务的遗留（建任务时可顺带问用户要不要一起处理）

- ✅ 两个已合并分支已删（2026-10-08 本会话：本地 + 远端，远端现只剩 `dev` / `master`）
- `backend-before-reconstruction/`（873 文件旧代码对照副本）：形状重构已完结，**可执行删除**
- 存量 `*Stage` 改名（`MediaAcquisitionStage` / `TranscriptionStage` → `-er`/`-or` 名）：
  已定「连同管线一起改」——可并入本任务或单独小任务
- `SubtitlePipeline` 执行模型拆分（executor/semaphore/排队 vs 编排）：AI 建议**等权重扁平化落地后再评估**
- Javadoc 全局重构：用户定另开一次；两条约定（注释不抄 kebab 值、阶段名 = 动词-对象）搭车那次进 spec
- `../vidsage-before` worktree 保留（对照办法可复用；本任务要重新指到 dev）
