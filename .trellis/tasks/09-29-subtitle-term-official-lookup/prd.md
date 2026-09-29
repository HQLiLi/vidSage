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

- **R1 触发确定性**：摘要成功后、context 落盘前，对术语表逐条查证（全量，≤30 条/视频），**不做「哪些值得查」的预过滤**——预过滤又要模型自评，回到不可靠老路。
- **R2 触发条件**：仅当源语种 ≠ 目标语种（翻译将发生）时查证；跳过翻译的路径不查、不花钱。
- **R3 调用形态**：RestClient 裸调 DashScope OpenAI 兼容端点，body 直接放 `enable_search: true`（可选 `search_options`）。**不引 SDK**（`pom.xml` 已实测 Jackson 同坐标冲突、SDK 与 Spring AI 不可兼得）、**不写 @Tool**、**不用任何会丢扩展参数的客户端层**（官方文档证实兼容层可能不转发 `enable_search`，自拼 JSON 是要求不是偏好）。
- **R4 提取与弃权**：查证模型只允许从检索结果提取，必须返回「译名 + 来源链接 + 引文」；查不到 / 结果冲突 / 无法提取 → 记「无官方译名」。**查证任何失败形态绝不挡主流程**：单条失败 = 该词未查证；步骤整体失败 = 全表未查证，清洗与产物照常。
- **R5 译名等级**：官方译名优先；无官方但**多个独立来源写法一致**的通用译名兜底，`officialNote` 如实记「通用译名，非官方」。两档都必须有检索证据，不许凭模型先验编造。
- **R6 持久化与复用**：`SubtitleContext.Term` 新增 `official` / `officialNote` 字段，随 `context-<route>.json` 落盘；断点复用命中缓存即不再查证。
- **R7 旧缓存兼容**：已落盘的 context 缺新字段 = 「未查证」→ 翻译前补跑查证并回写（一次性成本，保证旧素材可演进）。
- **R8 可观测**：日志逐条输出查证结论（官方 / 通用 / 无 + 来源域名 + 搜索次数），汇总一行统计（如 `查证 12 条：官方 3 / 通用 2 / 无 7，搜索 12 次，耗时 xx s`）。

## 非目标

- 不动翻译行为：主翻译仍在 deepseek/mimo，不做任何标注渲染。
- 不做清洗侧查证复用（`帕姆→Palm` 类），登记遗留。
- 不做 foreign→zh 的标注语义（维持「保留原样」现状）。
- 不实现引擎 B/C，仅在查证客户端抽象处留可替换的缝。

## Acceptance Criteria

> 2026-09-29 全部验收通过，实测证据见 `research/acceptance-evidence.md`。

1. [x] **首步冒烟（先行门槛）**：真实调用一次 Qwen3.8-Omni-Flash + `enable_search`，实测确认该模型支持联网搜索。⚠️ 官方错误码文档仍有「Omni 系列不支持 enable_search」报错条目，与项目所有者的控制台确认相矛盾——**以实测为准**；若实测不支持，降级到纯文本 flash 档并回改本 PRD 的引擎记录。→ 实测：Omni **支持**但强制 agent 策略（思考模式）→ 必须流式；按预设降级 **qwen-flash + standard 非流式**，引擎记录已在 design §一 回改。
2. [x] 用 MVP-3 的中文素材（`BV1axNJ6REoa`，42 段，含 `只狼`/`弹刀`/`架势条`），`target=en` 跑清洗（触发摘要+查证）：`context-<route>.json` 中 `只狼` 的 `official` 含 Sekiro 且带来源；`弹刀`/`架势条` 等无官方译名的词记录为「无」或有据通用译名。→ 走翻译路径练 R7 旧缓存迁移：`只狼 → Sekiro`（官方，fromsoftware.co.jp）、`打铁 → parry`（通用）、13 条如实弃权。
3. [x] 重跑同素材命中断点：不再发起查证（日志与耗时佐证）。→ `术语查证跳过（全部已查证）: terms=15`，零搜索。
4. [x] 查证失败注入（单测桩：超时 / 错 key / 坏响应）：清洗主流程照常完成、context 正常落盘（official 为空）。→ 失败保持 `official=null` 待补跑、provider 缺席整档跳过，均单测钉住。
5. [x] 单测覆盖：响应解析（含引用字段）、弃权路径、旧缓存补跑回写、跳过翻译不查证的分支。
6. [x] `mvn test` 全绿。→ 321 个测试，0 失败。
7. [x] 统计可读：R8 的逐条与汇总日志在真实运行中出现。→ `术语译名查证完成: 共=15, 官方=1, 通用=1, 无=13, 失败=0`。

## Notes

- **价格读数**（2026-09-29 调研，实施时以控制台为准）：搜索插件约 0.03 元/次按次计费（按 `search_strategy` 计费）；查证模型用最便宜 flash 档，token 费可忽略；单视频最坏 ≈ 1 元。
- **模型档位**：传统 qwen-turbo 档 2026-07-13 已下线；项目所有者指定 Qwen3.8-Omni-Flash 为首选，备选纯文本 flash 档（更便宜），以冒烟结果定。
- 响应里检索引用的具体字段名联调时确认，写进 design。
- 查证模型提取用的 system prompt 独立于现有 `ContentSummarizer`（另立 ChatClient 或纯 RestClient prompt，design 定），沿用「拿不准不要编」的措辞基调 + 接地校验。
- 后续任务衔接：`09-29-subtitle-translate-qwen-mt` 消费本任务的 `official` 字段（qwen-mt terms 干预），见其 prd。
