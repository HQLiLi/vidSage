# 执行台账 — 10-05-workflow-dispatch-tuning

> 按正典 `.trellis/spec/guides/dispatch-protocol.md` §6 格式固化。本次为本协议第一个台账样本。
> 已知局限：宿主未逐派发报告起止时刻，且派发间隙（主线决策/修复轮）未计时，故**起止 epoch 列省略**（不编造）；耗时列为宿主报告的精确 `duration_ms`。改进项：下一个任务的派发词执行时在派发/收取两侧各记一次 `date +%s`。

## 派发记录

| # | 派发（批次） | 子代理 | 覆盖步骤 | 耗时 | subagent_tokens | tool_uses | duration_ms |
|---|---|---|---|---|---|---|---|
| 1 | B1 正典文档 | trellis-implement（高档） | 正典 `dispatch-protocol.md`（R1–R6+适配表）+ `guides/index.md` 条目 | 13.1 min | 392,576 | 16 | 788,645 |
| 2 | B2 镜像+脚本（mechanic 降级，见下） | trellis-implement（高档） | `workflow.md` 注入块镜像×2、`.agents/skills/trellis-check/SKILL.md`、`.agents/skills/trellis-implement/SKILL.md`（新建）、`tools/trellis-worktree.sh` | 18.3 min | 528,653 | 19 | 1,100,023 |
| 3 | B2 修复跟进 | trellis-implement（高档） | `.zcode/skills/trellis-check/SKILL.md` 副本同步、`workflow.md` 过时陈述改写 | 4.9 min | 448,065 | 6 | 291,437 |
| 4 | 检查面 A（正典完备性） | trellis-check | AC1 / AC5 行内容 / AC6 中立+基线 / AC7 | 4.2 min | 93,459 | 9 | 250,438 |
| 5 | 检查面 B（镜像一致性） | trellis-check | AC2 / AC3 / 镜像保真 / SYNC MARKER / 结构完整性 | 3.3 min | 75,653 | 6 | 201,128 |
| 6 | 检查面 C（脚本与落点） | trellis-check | AC4 / AC6 脚本侧 | 2.8 min | 69,735 | 4 | 167,782 |
| 7 | 主线（命令关+修复） | 主会话 | 8 处 P0/P1/P2 修复（.gitignore×1、脚本×2、正典×3、workflow.md×1、prd×1）+ 命令关验证 | — | —（主线无 usage 报告） | — | — |

派发子代理耗时合计 ≈ 46.7 min；检查面 A/C 并行、A/B/C 与 B2 修复跟进部分重叠，实际墙钟小于合计。

## AC5 生效实测记录（ZCode mechanic）

- **尝试**：机械批次（B2）以 `subagent_type=trellis-mechanic` 派发 → 宿主拒绝：`Agent type 'trellis-mechanic' not found`。结论：ZCode 在**会话启动时枚举** agent 类型，`.codex/agents/*.toml` 新增文件**不热加载**。
- **处置**：按正典 §7 降级规则——该工具本轮全高档执行 + 本记录入台账；协议其余收益（并行 §1/§2、减轮数 §3）不受影响。
- **待办**：新会话复测（复测通过后派一个机械批次对照表观速率/思考时长，结果补记本表）。复测不通过则 ZCode 适配行能力等级应改判「不生效」并维持降级。

## 与基线对照（供下个任务复核）

基线（10-03）：105 min 墙钟 / 184k 输出 token / 六派发 10–40 tok/s / 最肥批 81 调用。本次为文档任务，无构建验证；token 总量以派发记录合计为准（1,608,141），结构上已体现：检查拆面后单面最大 9 次调用（对比基线全量检查 97 次）、单批 ≤4 文件。
