# 验收证据：术语官方译名联网查证（2026-09-29）

> 逐条对应 `prd.md` 的验收标准。环境：本地 Spring Boot（8080），`DASHSCOPE_API_KEY` 已设；
> 素材 `BV1axNJ6REoa`（42 段中文剑术解析视频，MVP-3 同一条），`target=en` 经
> `application-local.yml` 临时覆盖（跑完已还原）。旧 `context-asr-cloud.json`（无 official 字段）
> **故意保留**，让翻译路径练的是 PRD R7 的旧缓存迁移。

## 1. 冒烟（先行门槛）—— 通过（引擎按预设降级）

curl 直调兼容端点，问「《只狼》的官方英文名」：

| 尝试 | 结果 |
|---|---|
| `qwen3.8-omni-flash` + `enable_search:true` | 400：`This model only supports search_strategy='agent'` —— Omni **支持**搜索（错误码文档「Omni 不支持」对本代模型已过时），但强制 agent 策略 |
| 上式 + `search_strategy:"agent"` | 400：`Non-streaming mode does not support Web Search in thinking mode`；补 `enable_thinking:false` 被兼容层无视 |
| **`qwen-flash` + `search_strategy:"standard"`** | ✅ 非流式打通：`Sekiro: Shadows Die Twice` + Steam 来源页，`prompt_tokens=961`（检索证据进上下文） |

**引擎定案：qwen-flash + standard，非流式**——PRD 验收 #1 预设的降级路线，比 Omni 便宜且免 SSE 解析。

## 2. 真实运行：旧缓存迁移 + 查证落盘 —— 通过

`POST /api/subtitles/translate`（-translate 检查点已删、旧 context 保留）：44.8s（查证 10.6s + 翻译 34s），
`retrySteps=0, untranslatedCount=0`。15 条术语全部查证，回写 `context-asr-cloud.json`：

| 术语 | official | officialNote |
|---|---|---|
| **只狼** | **Sekiro** | **官方译名（来源：www.fromsoftware.co.jp）** |
| 打铁 | parry | 通用译名，非官方（来源：www.swordstudy.org） |
| 弹刀 / 架势条 / 气绝 / 震刀 / 日耳曼 / Ochs / 举顶格 / 好纳尔人 / 菲奥雷 / 农民杀手 / 劈挂 / 受流 / 重庆南坪印象城 | （空串，弃权） | 联网查证未找到有据可依的目标语种译名，弃权 |

统计日志：`术语译名查证完成: 共=15, 官方=1, 通用=1, 无=13, 失败=0, cost=10627ms`，逐条 INFO 各带耗时。

读法（三条都值得记）：

- **最关键的词命中最硬的来源**：`只狼 → Sekiro` 直接给了发行商官网（fromsoftware.co.jp），任务 2 的
  `只狼（Sekiro）` 标注有了依据。
- **Q10.2 的直觉被数据证实**：`弹刀`、`架势条` 在严格的证据口径下**查不到**有据译名——它们会走
  「放开直译」分支，正好是项目所有者预期的行为。只有一个例外：`打铁 → parry`（通用），任务 2
  设计时可裁「common 是否也标注」（见 design §四）。
- **弃权语义真实生效**：13 条空串 + note 可追溯，不会反复重搜烧钱。

## 3. 断点复用不再重搜 —— 通过

删 -translate 检查点重跑（context 现在已全查证）：日志只有
`术语查证跳过（全部已查证）: terms=15`，**没有** `术语译名查证开始`——零次搜索；43.4s 全是翻译本身
（当前 chat 供应商为 mimo，比 MVP-3 的 deepseek 慢，与本任务无关）。

## 4. 失败路径 —— 单测覆盖（未在真实运行中制造失败）

`DashScopeTermLookupProviderTest`（10 测）：证据不全弃权 / 围栏剥离 / 坏回复抛 ISE / 缺 key / 空参。
`SubtitleTermVerifierTest`（8 测试）：**null/空串三态**（失败保持 null 待补跑、弃权空串持久化不再重搜）、
同语言 / 空目标 / 无术语 / 全查证 / provider 缺席五档跳过都不发调用、旧缓存 JSON 反序列化 official=null
而弃权空串 round-trip 不丢。`mvn test`：**321 个测试全绿**（1 跳过为既有 ModelProbe）。

## 覆盖与缺口

- **清洗路径的查证落盘**（fresh-summarize 分支）未在真实运行单独验证——它与翻译路径共用同一个
  verifier 与写盘调用，翻译路径已实测；要单独练需删 -clean 重跑清洗（烧一次清洗 LLM），收益低。
- 真实运行只覆盖 zh→en（唯一有素材的方向，与 PRD 范围一致）。
- 查证质量是单次抽样：`打铁 → parry` 这类通用译名的可靠性、以及 prompt 对冷门中文武术词的
  弃权倾向，等任务 2 接上消费侧后再评估是否要调。
