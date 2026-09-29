# 字幕翻译引擎切换 qwen-mt-flash

> **状态：planning——等前置任务 `09-29-subtitle-term-official-lookup` 交付并看过查证效果后再启动。**
> 本文件目前是 2026-09-29 grilling 的决策存档，开工时再展开成完整 PRD + design。

## Goal

主翻译从 deepseek/mimo 切到 qwen-mt-flash：批协议重做为纯文本输出，官方译名经 `translation_options.terms` 强制生效，实现「原词（官方译名）」标注（每次出现都标）。

## 选型理由（2026-09-29 拍板）

- **术语干预 = API 级保证**：前置任务产出的 `terms-<route>-<target>.json`（`VerifiedTerm.official`）渲染成术语对传入 terms 参数，如 `[{"source": "只狼", "target": "只狼（Sekiro）"}]`，每次命中强制按 target 输出——09-28 实证「提示词压不住模型」的终极解法是不压，换协议。
- **价格**：输入 0.7 / 输出 1.95 元每百万 token；翻译是输出膨胀任务，输出侧低于 deepseek（~3 元/百万）约 35%。
- **专注翻译**：MT 特调模型，效果或优于通用对话模型（待 A/B 验证）。
- 无官方译名的词不进 terms 表 = 自动放开直译；真翻不出来保留原文不算失败（项目所有者定的容忍线）。

## 关键改造点（design 阶段展开）

| 现有机制（deepseek/mimo） | qwen-mt-flash 下的变化 |
|---|---|
| 批协议「条号→译文 JSON」+ 键集校验 + 掉行重试 | JSON 协议作废，纯文本输出；掉行检测按行数/顺序重设计 |
| 跨批对话历史承载「称谓全片一致」 | MT 单发翻译，历史机制消失；术语一致性由 terms 承接，**非术语称谓一致性失去载体（风险项）** |
| deepseek 世界知识兜底表外词 | 特调模型世界知识可能更弱，**术语表覆盖率更关键**——用 MVP-3 素材 A/B 对照 |
| Spring AI ChatClient + mimo 高峰开关 | RestClient 裸调（SDK Jackson 禁区同样生效），`translation_options` 是 DashScope 私有参数；deepseek/mimo 退守摘要+清洗，pom「翻译走 LLM 唯一路线」注释作废 |

## 待核实（开工前）

- terms 数量 / 长度上限与格式。
- 单发调用是否真无多轮能力（跨批语义的最终定论）。
- 响应格式与错误语义。

## 验收草案

- MVP-3 素材 zh→en：`只狼` 每次出现渲染 `只狼（Sekiro）`；`弹刀`/`架势条` 放开直译；翻不出的词保留原文不算失败。
- 真实素材 A/B（deepseek vs qwen-mt-flash）译文质量不回退。
- 全片译法一致性抽查。
- `mvn test` 全绿。
