# 字幕术语官方译名联网查证

## Goal

摘要层产出术语表后，新增「术语查证」步骤：对每条术语联网搜索（Qwen3.8-Omni-Flash + `enable_search`），把目标语种的官方/通用译名写进术语数据并随 context 落盘复用。

本任务交付「译名**可得、可信、可复用、可观测**」；翻译侧如何消费译名是后续任务（`09-29-subtitle-translate-qwen-mt`）的事。

## 背景（2026-09-29 grilling 收敛，全部经项目所有者拍板）

- MVP-3 的 A/B 实验（`archive/2026-09/09-24-vidsage-mvp3-translate/research/term-injection-ab.txt`）证明：zh→en 译文残留中文（`只狼`、`弹刀`…9/42 条）不是模型不会译，是「术语参考 → 保留原样不译」的注入规则把它们钉死的。
- 09-28 归档（`archive/2026-09/09-28-subtitle-term-replacement/research/term-source-limitation.md`）已把联网搜索列为候选解法，并给出关键边界：**模型不觉得自己在猜，自主触发工具可能一次都不发生**——所以触发必须是确定性的。
- 引擎选型：DashScope Qwen + `enable_search`（复用现有 `DASHSCOPE_API_KEY`，零新账号）。备选引擎 B/C（自接搜索 API + LLM 提取 / @Tool 自主调用）都因需要新搜索 key 被归为将来选项，接口留缝即可。
- 范围线：清洗残留（`帕姆→Palm` 类摘要层幻觉）不在本任务范围，根治在换 ASR（09-28 已定方向）。

## Requirements

> 2026-09-29 重构（项目所有者三条意见 + 独立产物裁决），结构见 design.md §二/§三/§四。

- **R1 触发确定性**：术语表逐条查证（全量，≤30 条/视频），**不做「哪些值得查」的预过滤**——预过滤又要模型自评，回到不可靠老路。
- **R2 触发条件与流程位置**：查证编排**内联在字幕翻译流程**（`SubtitleTranslationService`，不建新 service/bean）；仅当源语种 ≠ 目标语种（翻译将发生）且 terms 产物缺失时执行；清洗流程零感知。
- **R3 调用形态**：查证是模型调用，接口 `TermLookupProvider` 落在 `provider/llm/`（领域包根，同 ASRProvider 位置），实现 `provider/llm/dashscope/`（`@ConditionalOnProperty` 配置切换，为将来换模型留缝）。RestClient 裸调 DashScope OpenAI 兼容端点，body 直接放 `enable_search: true`。**不引 SDK**（`pom.xml` 已实测 Jackson 同坐标冲突）、**不写 @Tool**、**不用会丢扩展参数的客户端层**。
- **R4 提取与弃权**：查证模型只允许从检索结果提取，必须返回「译名 + 来源链接 + 引文」；查不到 / 结果冲突 / 证据不全 → 该词 `official=""` 弃权。**查证任何失败形态绝不挡翻译主流程**：单条调用失败 = 该词不进产物文件（下次补查）；provider 缺席 = 查证整体关闭。
- **R5 译名等级**：官方译名优先；无官方但**多个独立来源写法一致**的通用译名兜底，`officialNote` 如实记「通用译名，非官方」。两档都必须有检索证据，不许凭模型先验编造。
- **R6 类型拆分与持久化**：查证产出为独立类型 `VerifiedTerm`/`VerifiedTerms`（`SubtitleContext.Term` 保持摘要层原始三字段不动），落独立产物 `terms-<route>-<target>.json`（文件名含目标语种，换语种重查）。**`context-<route>.json` 永不改写**；文件存在 = 已查证（删文件即重查），无旧缓存迁移问题。
- **R7 可观测**：日志逐条输出查证结论（官方 / 通用 / 无 + 来源域名 + 耗时），汇总一行统计（如 `共=15, 官方=1, 通用=1, 无=13, 失败=0`）。

## 非目标

- 不动翻译行为：主翻译仍在 deepseek/mimo，不做任何标注渲染（消费 terms 产物是任务 2）。
- 不做清洗侧查证复用（`帕姆→Palm` 类），登记遗留。
- 不做 foreign→zh 的标注语义（维持「保留原样」现状）。
- 清洗流程不接入查证（2026-09-29 裁决：编排只挂翻译流程）。

## Acceptance Criteria

> 2026-09-29 重构版验收全部通过，实测证据见 `research/acceptance-evidence.md`（含查证抖动的发现与 temperature 修复）。

1. [x] **首步冒烟（先行门槛）**：实测 `qwen-flash` + `enable_search` + `search_strategy:"standard"` 非流式可用；Omni-Flash 支持搜索但强制 agent 策略 → 必须流式，故弃用（详见 design §一）。
2. [x] 真实素材（`BV1axNJ6REoa`）`target=en` 跑翻译：生成 `terms-asr-cloud-en.json`，`只狼` 的 `official` 为 Sekiro 且 note 带官方来源（www.sekiro.com）；无据词（弹刀/架势条等）`official=""` 弃权；**`context-<route>.json` 与改动前备份逐字节一致（零改写，4 次运行无一例外）**。
3. [x] 删 terms 产物重跑 → 重新查证（搜索再发生，15→30 条）；不删重跑 → 零搜索（`术语查证产物已存在，跳过查证`）。
4. [x] 查证失败注入（单测桩）：该词不进产物文件；翻译主流程照常；provider 缺席 = 查证关闭跳过。
5. [x] 单测覆盖：响应解析与接地校验各分支、查证编排（成功/弃权/失败分流、产物缺失判定、provider 缺席）、`VerifiedTerms` 序列化 round-trip。
6. [x] `mvn test` 全绿。→ 321 个测试，0 失败。
7. [x] 统计可读：逐条与汇总日志在真实运行中出现。→ `共=15, 官方=1, 通用=1, 无=13, 失败=0`，逐条带 verdict/level/来源 host/耗时。

## Notes

- **价格读数**（2026-09-29 调研，实施时以控制台为准）：搜索插件约 0.03 元/次按次计费（按 `search_strategy` 计费）；查证模型用最便宜 flash 档，token 费可忽略；单视频最坏 ≈ 1 元。
- **模型档位**：已定案 `qwen3.7-flash`（2026-09-29）——qwen-flash 别名停在 2025-07-28 快照弃用；qwen3.8-flash 贵 3~4 倍未过冒烟；请求固定 `enable_thinking:false`（思考链让单条 1.5s→20~45s）与 `temperature:0.1`（防逐轮抖动），过程见 design §一 与 research §4。
- 响应里检索引用的具体字段名联调时确认，写进 design。
- 查证模型提取用的 system prompt 独立于现有 `ContentSummarizer`（另立 ChatClient 或纯 RestClient prompt，design 定），沿用「拿不准不要编」的措辞基调 + 接地校验。
- 后续任务衔接：`09-29-subtitle-translate-qwen-mt` 消费本任务的 `official` 字段（qwen-mt terms 干预），见其 prd。
