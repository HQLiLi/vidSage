# 字幕翻译引擎切换 qwen-mt-flash

> **状态：planning，暂缓——2026-09-30 项目所有者拍板：暂不排期，降级为未来可选测试任务。**
> 前置对照实验已完成并合入 dev（`feature/subtitle-official-terms-translate`，官方译名经提示词接入
> deepseek 翻译，双语种实测通过）。本文件是 grilling 决策存档 + 2026-09-29 文档核实的 API 事实；
> 开工时再展开成完整 PRD + design。

## 2026-09-30 暂缓背景

官方译名接入走**提示词路线**先行落地（本任务原方案的 terms API 干预留作未来对照）：deepseek 实测
5/5 查证译名全部生效、弃权词正确保留原样，zh→ja 同样成立——提示词路线够用的证据成立后，
引擎切换不再紧迫。重启本任务的触发条件：A/B 实测中提示词路线出现压不住的译名偏移，
或成本/质量需要 MT 特调模型。

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

## 待核实（开工前）→ 2026-09-29 已核实（官方文档 help.aliyun.com/zh/model-studio/qwen-mt-api、/machine-translation）

- ~~terms 数量 / 长度上限与格式~~ → 每条 `{source, target}` 双必选 string；**无官方数量上限**，
  实际受输入 8192 token 总额约束（本项目术语量级 ≤15 条，无压力）。
- ~~单发调用是否真无多轮能力~~ → **官方定论：仅支持单轮**。「不支持多轮对话」「不支持 System
  Message」「messages 有且仅有一个 role=user」——跨批历史机制在 qwen-mt 下无载体，批协议只能传
  纯文本（任何指令文字都会被翻译掉），掉行重试只能原样重发。
- ~~响应格式与错误语义~~ → 兼容端点为标准 chat.completion，无 reasoning_content；
  finish_reason 非流式仅 stop / length 两种（length=截断，须当校验失败处理）。
- 补充：temperature 默认 0.65（与 top_p 二选一）；max_tokens 默认值文档未给——**实现时显式设置**
  并把 length 计入校验；语种用英文名或代码（Chinese/zh，92 种）；`domains` 领域提示只收英文语句，
  是「非术语称谓一致性」潜在载体的候选；流式仅 flash/lite 支持（本项目用非流式，无关）。

## 验收草案

- MVP-3 素材 zh→en：`只狼` 每次出现渲染 `只狼（Sekiro）`；`弹刀`/`架势条` 放开直译；翻不出的词保留原文不算失败。
- 真实素材 A/B（deepseek vs qwen-mt-flash）译文质量不回退。
- 全片译法一致性抽查。
- `mvn test` 全绿。
