# 字幕纠错的批间上下文

> **状态（2026-09-28）**：已实现并在真实视频上跑通（分支 `feature/subtitle-context`），`mvn clean test` 235 例含 `SubtitleCleanserTest` 13 例。本文按实际落地重写，被推翻的原始形态（旧类 `SubtitleCorrector` / `SubtitleContextBuilder`、光板 ChatClient、前窗注入）已删除；改道原因见 design.md 顶注。

## Goal

ASR 字幕按批丢给 LLM 纠错，批与批之间不能断开：同音误写靠批内邻句大多能消歧，但**跨批的专有名词一致性**（同一人名在第 1 批和第 5 批写法不同）没有兜底。本任务参照 VideoLingo 的三级上下文补齐这一层，并把前置全篇摘要一并做掉。

**许可证**：`VideoLingo/core/_4_2_translate.py` 等（Apache-2.0，可搬代码，保留版权与许可声明）。同目录 `VideoCaptioner` 是 **GPL-3.0**，只能读思路不能抄代码。

## Requirements（按实际落地形态）

### R1 跨批上下文：后窗 + 对话历史

- **后窗**：每批提示词带下一批开头 2 行（纯文本、无编号、`<subsequent_content>` 包裹），管「未来」
- **跨批对话历史**：每批被采纳的「批次原文 + 纠正结果」作为多轮历史带给后续所有批次，管「过去」。历史是原规划「前窗」的严格超集——前窗给上一批**未清洗**的原文，历史给**已采纳的纠正**，且不会回放 ASR 错字诱导模型改回去
- 上下文不带编号、不进键集，`validate` 守卫一字不改；提示词写明「仅供参考，不要修改，不要出现在返回结果里」

### R2 前置全篇摘要

- 纠错前跑一次 LLM 产出 `{theme, terms}`（`SubtitleContext`，`model/subtitle/`）
- **摘要失败不得让清洗失败**：异常 / 坏 JSON 一律降级 `SubtitleContext.empty()`，记日志继续——与「宁可没纠，不可被带偏」同一纪律

### R3 术语按批过滤

- 自动摘要抽出的 `terms` 只把**本批文本真出现过的**拼进提示词（小写比对），避免长术语表撑肿提示词、稀释注意力
- 人工传入的 `reference` 独立成段拼在正文后（`clean(split, context, reference)` 签名）；与自动术语**分段并列**，未做同词合并——冲突时模型自行取舍，人工段明确标注「参考术语表」

### R4 批大小双判据

- `batch-size=20` 句 / `max-batch-chars=600` 字，先到为准——长短句混排时批的 token 量不再失控
- 单句就超字符上限时强制前进，防死循环

### R5 摘要产物落盘 —— **未实现，出栈**

`context-<route>.json` 二级复用没做：摘要每次清洗现跑，无产物可复用。属功能增补，另行开任务；重跑清洗重复付一次摘要调用是其已知代价。

### R6 LLM 调用走 ChatClient

- 两个带 `defaultSystem` 的**领域 client**（`ChatClientConfig`）：纠错与摘要的 system prompt 语义相反，各挂各的。原「全项目共用一个光板 client」的形态已推翻——光板 + 各自覆盖会让 `defaultSystem` 变死代码
- SDK 类型收敛在 llm 侧类与 config 装配；`SubtitleCleanupService` 直持 `ChatClient` 是**临时违例**，由收尾整理新建 `provider/llm/SubtitleContextSummarizer` 接走消除

## Acceptance Criteria（按实际达成）

- [x] `mvn clean test` 全绿 235 例；新增覆盖：历史注入/排除/裁剪、批内重试、回滚、围栏解析、后窗保留/前窗移除、`historyMaxChars=0` 关闭
- [x] 提示词含后窗、主题、本批过滤后的术语；上下文不带编号
- [x] 摘要失败（桩 LLM 抛异常 / 坏 JSON）清洗仍成功，只是没有 theme 与术语
- [x] 人工 `reference` 与自动 `terms` 并存于提示词
- [x] `validate` 守卫未被改动（键集一致 + 相似度阈值，阈值按句长分档）
- [x] 批粒度双判据切批
- [ ] 摘要产物二级复用（R5，出栈）
- [ ] **跨批一致性人工抽样**——机制由单测 + 每批 DEBUG 日志证明；真实效果待素材 A/B（`history-max-chars: 0` vs `20000` 比 `corrected` / `retry`）。注意这两个指标测不出跨批一致性，只能当辅助信号

## Constraints

- 不引入新依赖；对外 HTTP 契约（`POST /api/subtitles/clean`）与既有产物名不动
- 摘要是新增的一次 LLM 调用，清洗变慢是已知代价
- 新增配置项带 `application.yml` 注释与 Javadoc（`max-batch-chars`、`history-max-chars` 已落地）

## 出栈清单（另行任务）

| 项 | 原因 |
|---|---|
| 摘要产物落盘 + 摘要输入截断 | 功能增补；当前全文直给摘要，长视频 token 量随全篇涨 |
| 并发批处理 | 与「上下文是否生效」混测分不清锅；参考项目 `max_workers: 4` 供届时参考 |
| ~~`LLMProvider` 四件套~~ → **不建**（2026-09-28 定）：供应商缝由 Spring AI 承担，本地 Ollama / 云端 DeepSeek 均 OpenAI 兼容；翻案条件见 provider-pattern.md，`vidsage.llm.provider` 预留键已删 |
