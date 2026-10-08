# 会话移交（2026-10-08）：进度权重扁平化 — 执行阶段

> **本文件已重写**。上一版是「规划前」的材料速查；其内容（散点账、现状边界值、载体候选、环境坑速查）已全部折进任务工件
> `prd.md` / `design.md` / `implement.md`，不再单独维护，避免两处口径。

## 一句话状态

任务已建（`.trellis/tasks/10-08-progress-weight-flattening/`），**工件三件套已写完并经用户 review 通过**。
本轮规划期间**没碰任何代码、没开分支**。下一步是 `task.py start` 进实现。

## 下一步怎么起

1. 读工件，顺序：`prd.md` → `design.md` → `implement.md`（都在任务目录里）。
2. `python ./.trellis/scripts/task.py start` —— 状态转 `in_progress`，然后才能动手。
3. 按 `implement.md` **步骤 0** 从 `dev` 开 `feature/progress-weight-flattening`（`task.json` 的 `base_branch` 已核对为 `dev`）。

## 分工：谁写什么（执行期，硬规则）

- **`src/main/` 的生产代码由用户自己写**。他每一步会先来问，AI 的活是**解答 + 给出具体的示例代码**（能直接对着敲的片段，不是指路）。
- **`src/test/` 由 AI 改**：**等用户写完他那部分之后**，AI 按他已经写好的代码去改测试——不是反过来让他迁就测试。
- 因此 `implement.md` 的步骤是**给用户看的清单**，不是 AI 的执行脚本。别抢跑去改 main，也别在 main 定型前先动测试。

## 决策已全部拍完——别再重开

规划期逐条问过用户，结论都写进工件了。速览（细节见对应文件）：

| 决策 | 结论 | 落在哪 |
|---|---|---|
| 权重挂哪 | 加在 `SubtitleStageName` 上，留 domain | design §一 |
| 字段形态 | **单 `weight` + 现算**；不要手写累计值字段 | design §一、§六 |
| 是否在主管线 | 显式字段 `inMainPipeline`，不用 `weight == 0` 当代理 | design §一 |
| 数值 | **档 A 保真**：`42/42/49/7/60`（用户原话：数值是渲染进度条用的占位，不必反映真实耗时） | prd R5、design §四 |
| 中间层映射 | 消失；换算深度恒为 1 | design §二 |
| 谁取数 | 谁发事件谁取数；clean 批级是唯一例外（provider 报 0..100，由调用者换算） | design §三 |
| 写字幕「开始」事件 | 改发本阶段起点（**唯一一格有意变更**：70 → 66） | prd R8、design §四 |
| 改名三个 | `AudioPreparationCoordinator` / `MediaAcquirerDispatcher` / `Transcriber` | prd R7、design §五 |
| `ProgressMapper` | 留（只剩 clean 一个使用者） | design §三 |
| 枚举里 6 行复述注释 | 删 | implement 步骤 3 |
| 失败 / 跳过事件 | 沿用：跳过发 `.end()`、失败发 `.start()` | implement 步骤 2 |

**`design.md` §六 是「否掉的方案清单」**（8 条，含理由）——有人再提手写累计值、父级 remap、另立载体、`SubtitleStage` 命名时，先去那儿看一眼。

## 执行时容易出岔的三处

1. **AC7 的期望值**：逐格比对时**差异必须恰好是那一格**（写字幕开始 70→66）。多一格就是回归，结果要写进完成报告——不写会被后人当成 bug。
2. **翻译链路不跑真实验证**（省配额）：它不受本任务影响，由单测覆盖。这是**已接受的边界**，别在完成报告里当成疏漏。
3. **改名后必须 `mvn clean`**：`target/classes/` 会留旧 class，只删 surefire 报告不够。

环境坑（JDK 路径、`clean` 的必要性、`TaskStop` 杀不掉子 JVM、Git Bash 的 `/tmp`、上传路线优先）都在 `implement.md` 的验证步骤与 memory 里，不用重新踩。

## 本任务做完之后要另议的遗留

1. **Javadoc 全局重构**——用户定另开一次。它要搭车进 spec 的两条约定：① 注释里不抄 kebab 值；② 阶段名 = 动词-对象（`acquire-media` 这种值）。
2. **`SubtitlePipeline` 执行模型拆分**（executor / semaphore / 排队 vs 编排）——AI 建议等本任务落地后再评估。
3. **`backend-before-reconstruction/`**（873 文件的旧代码对照副本）——形状重构已完结，可执行删除；本任务明确不碰。
4. **`../vidsage-before` worktree**——本任务要用（需从 `6bda4f5` 重切到 `dff027b`），用完是否保留由用户定。
