# 派发执行台账（协议 §6）

> 落点依据：`.trellis/spec/guides/dispatch-protocol.md` §6。
> **字段边界**：只固化宿主报告过的 usage 三件套与 `duration_ms`；**开始 / 结束 epoch 宿主未报告，
> 一律记 `—`，不推算、不编造**（协议 §6 明令不得编造未报告字段）。
> 补记说明：本表于 2026-10-11 批次 4 末尾补记（前三笔派发当时未即时落表，属未遵守「不攒批」，如实登记）。

| # | 派发 | 子代理 | 覆盖步骤 | 开始 epoch | 结束 epoch | 耗时 | usage 三件套 |
|---|---|---|---|---|---|---|---|
| 1 | 批次 2 前端主源码 | trellis-implement（opus） | implement.md 2.1–2.4 | — | — | 69.4 s | `subagent_tokens` 79926 / `tool_uses` 22 / `duration_ms` 69413 |
| 2 | 检查读面·规范关 | trellis-check | 全量（读面） | — | — | 109.6 s | `subagent_tokens` 105652 / `tool_uses` 25 / `duration_ms` 109630 |
| 3 | 检查读面·契约关 | trellis-check | 全量（读面） | — | — | 83.6 s | `subagent_tokens` 102282 / `tool_uses` 34 / `duration_ms` 83638 |
| 4 | 检查读面·逻辑关 | trellis-check | 全量（读面） | — | — | 202.3 s | `subagent_tokens` 136304 / `tool_uses` 25 / `duration_ms` 202266 |
| 5 | 增量重检·逻辑关 | trellis-check | 审查意见修复后的新代码路径 | — | — | 119.2 s | `subagent_tokens` 97165 / `tool_uses` 15 / `duration_ms` 119241 |
| 6 | 增量重检·规范+契约关 | trellis-check | 修复 delta 的合规与一致性 | — | — | 88.5 s | `subagent_tokens` 108804 / `tool_uses` 28 / `duration_ms` 88456 |
| 7 | 窄范围复检（单面） | trellis-check | 只审 `open()` catch 放宽 + `register()` 改 compute 这 8 行 | — | — | 53.0 s | `subagent_tokens` 63635 / `tool_uses` 8 / `duration_ms` 53043 |

## 未派发（主线亲自执行，不计入台账）

- 步骤 0 基线记录、批次 0 spec 判据先行：机械命令与文档编辑，`duration_ms` 宿主未按批次报告。
- 批次 3 测试与镜像：清单已由 implement.md 给出且依赖一个未定项（`hasBucket` 探针），需主线即时判断，
  按「一项一派既浪费固定上下文、又留不住判断」未派发。
- 批次 4 验证（AC4–AC7）：需起/停服务、清工作区授权、Playwright 驱动，属主线独占资源。
- 命令关（type-check / lint / build）：协议 §2 要求单独走，由主线执行。

## 本任务对本协议的偏离（如实登记）

1. **批次 3 未派发**（理由见上）。
2. **未使用 worktree**：协议 §5.1 的默认只对「并行实现批次」生效，本任务实现批次同一时刻只有一个。
3. **台账补记**：违反「每个派发结束即记，不攒批」。
