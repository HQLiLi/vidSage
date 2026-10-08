# 会话移交（2026-10-08 第二轮）：SubtitlePipeline 执行模型拆分 —— 工件已成、待激活

> 全文已重写为**实现阶段**的交接。上一版（2026-10-08 第一轮会话，`15e6112`）写的是「任务尚未创建、
> 先通读 SubtitlePipeline 全文」——那些都已经做过，按订正惯例直接重写为现状。
> 前一个任务 `10-08-progress-weight-flattening` 已完结归档（merge `7cdcb2c` / archive `db2bf67`，均已推）。

## 一句话状态

任务 `10-08-pipeline-executor-split` 的 **prd / design / implement 三件套已写完并经用户过目**（原话「差不多」）。
工件齐了、结论定了，**还没动一行代码**：
`planning` 状态、**未 `task.py start`**、**未开分支**、当前在 `dev` 上。
**任务目录尚未入库**（`git status` 只有 `?? .trellis/tasks/10-08-pipeline-executor-split/`）——第一次提交要把它带上，否则归档会踩 pathspec 错。

## 下一步怎么起（新会话照这个顺序做）

1. **先读工件，本文档只是补充**：`prd.md`（动机 / R1–R9 / AC1–AC7 / Key decisions Q1–Q6）→ `design.md`（八节，
   含「否掉的七个方案」与「诚实边界」）→ `implement.md`（步骤 0–9，示例代码全在里面）。
2. 工件用户已认可 → `python ./.trellis/scripts/task.py start` 激活（转 `in_progress`）。
3. `implement.md` 步骤 0 开分支：`git checkout -b feature/pipeline-executor-split`（从 `dev` 起）。
   注意 **spec 改动不在「纯文档可直接在 dev 上改」的例外里**——步骤 1 要改 `.trellis/spec/backend/directory-structure.md`，
   所以必须先开分支。
4. **执行分工（沿用既有口径）**：主源码**用户写**（每步先问；示例代码在 `implement.md`，不在会话里贴）；
   测试**我按他写出来的代码改**。
5. 每步做完停下等审查；提交 / 推送 / 合并 / 归档**各是一道门**，沉默 ≠ 批准；归档只有用户跑 `finish-work` 或明说才做。

## 决定清单（只做指针，正文在工件）

| 决定 | 落点 |
|---|---|
| 切法 A「执行壳」= executor + semaphore + 排队骨架；状态/编排/进度/终态全留管线 | prd Key decisions Q1、design §一 |
| 名字 `TaskConcurrencyGate`（「异步」不进类名，写注释里） | prd Q2、design §二 |
| 端口在 `application/task/port/`、实现在 `infrastructure/concurrency/`；**D 保留**（不回退单向依赖） | prd Q4、design §二 |
| 主管线装载挪进 body（不提到调用线程） | prd Q3、design §四之 1 |
| infrastructure 范围放宽 + 判据改「有没有状态」；`shared`/`config`/`web`/依赖表都不动 | prd Q5、design §六 |
| 本次收益定位 = 轻（归位 + 配对单点化 + 中断路径可测 + 立一条分层判例） | prd Q6、design §八 |

## 工件之外的热上下文（会话里磨过、工件只落结论的部分）

- **「甲（裸 Executor）/ 乙（自造端口）」这套判断用户是第一次接触**，追问了两轮（含"线程管理怎么会属于乙"）。
  判据链在 design §二，新会话不用重讲——但**别当成显然**，用户是从「为什么需要端口」这个层次建立的。
- 用户对这个任务的期望是**先做轻的那半，最麻烦的（状态迁移 × 广播三角）排在后面另开**（prd Out of scope 已记）。
  别把本次包装成"大改造"。
- 会话里还核出两处**与本任务无关**的并发竞态（并发 `POST /translate` 双跑同一任务；提交查重 check-then-insert
  且 schema 无唯一约束）——已写进 prd Out of scope，将来做「状态 × 广播三角」时是输入。

## 环境坑（memory 里有，implement.md 末尾也抄了一份）

JDK：`JAVA_HOME=/f/java_soft/java21`；量测试前删 `target/surefire-reports`、改名/改结构后必须 `mvn clean`；
冒烟实例 `TaskStop` 杀不掉子 JVM，按端口 PID `taskkill //PID <pid> //F`；Git Bash 的 `/tmp` 不通用，证据落任务 `evidence/`。
