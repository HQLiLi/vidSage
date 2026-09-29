# 设计：字幕术语官方译名联网查证

> 2026-09-29。PRD 见同目录 `prd.md`；本文记冒烟实证与技术形态。

## 一、冒烟实证（2026-09-29 实测，Acceptance #1）

用 curl 直调 DashScope OpenAI 兼容端点（`/compatible-mode/v1/chat/completions`），问「《只狼》的官方英文名」：

| 尝试 | 结果 |
|---|---|
| `qwen3.8-omni-flash` + `enable_search:true` | ❌ 400：`This model only supports search_strategy='agent' when enable_search=true` —— **Omni 支持搜索**（官方错误码文档的「Omni 不支持 enable_search」对本代模型已过时），但强制 agent 策略 |
| 上式 + `search_options:{search_strategy:"agent"}` | ❌ 400：`Non-streaming mode does not support Web Search in thinking mode` —— agent 策略走思考模式，**非流式不可用**；补 `enable_thinking:false` 也被兼容层无视 |
| 上式 + `stream:true` | 可行，但要解析 SSE |
| **`qwen-flash` + `search_strategy:"standard"`** | ✅ **非流式一次打通**：返回 `Sekiro: Shadows Die Twice` + Steam 来源页 URL；`prompt_tokens=961`（检索证据确实进了上下文） |

**结论：查证引擎 = `qwen-flash` + `enable_search:true` + `search_options.search_strategy:"standard"`，非流式。**
比 PRD 首选的 Omni-Flash 更便宜且省掉 SSE 解析；这正是 PRD 验收 #1 预设的降级路线（「以实测为准」）。Omni 路线留档：若将来要更强检索，须配流式解析。

## 二、调用形态

- 端点：`POST {base-url}/chat/completions`，`Authorization: Bearer <key>`（key 复用 `DASHSCOPE_API_KEY`）。
- 请求体（RestClient + Jackson 序列化，**自拼 record 而非手写字符串**，也**不经任何会丢扩展参数的客户端层**）：

```json
{
  "model": "qwen-flash",
  "messages": [{"role": "system", "content": "<查证提示词>"}, {"role": "user", "content": "<术语+主题+目标语种>"}],
  "enable_search": true,
  "search_options": {"search_strategy": "standard"}
}
```

- 回复 `choices[0].message.content` 要求为 JSON：
  `{"officialName": "...", "level": "official|common|none", "sourceUrl": "https://...", "quote": "来源页原文引文"}`
- **接地校验（Java 侧机械执行）**：`officialName` 非空时，`sourceUrl` 必须是合法 http(s) URL 且 `quote` 非空，否则按「查不到」弃权——模型报了译名但给不出证据，等于没查到。这是 Q11 定下的「软接地」：搜索在供应商侧执行，拿不到原始摘要做逐字比对，就用「证据字段齐全」做下限约束。
- 解析失败（content 不是合法 JSON）按「查证失败」抛 ISE → 该词保持未查证，下次补跑；**与「查不到」（弃权，持久化为空串）严格区分**：前者重试，后者不再花搜索费。

## 三、结构与数据流

```
service/SubtitleTermVerifier          ← 编排：挑未查证术语 → 逐条调 provider → 组装新 context + 统计
provider/termlookup/TermLookupProvider(接口)          ← 将来引擎 B/C（自接搜索 API）的实现缝
provider/termlookup/dashscope/DashScopeTermLookupProvider + Properties
model/subtitle/TermLookupCommand / TermLookupResult   ← 契约
model/subtitle/SubtitleContext.Term                   ← + official / officialNote
```

- **字段语义**：`official == null` = 未查证（旧缓存 / 查证失败）；`""` = 查过、无有据译名（弃权，不再重搜）；非空 = 查到。null/空串之别是旧缓存迁移（PRD R7）的判定依据，**紧凑构造器不得把 null 收敛成空串**。
- **触发条件**（verifier 内聚）：`terms` 非空 ∧ 源语言与目标语言主子标签不同 ∧ 存在 `official == null` 的术语 ∧ 装配了查证 provider。装配缺席（yml 删 `provider` 行）= 整个查证关闭，翻译照常。
- **两个接线点**：清洗（摘要成功后、落盘前，正好一次写盘）；翻译（读到旧缓存上下文且要真翻译时，补跑并回写）。判定谓词复用 `SubtitleTranslationService.isSameMainLanguage`（同包包级可见，单一判据）。
- 主翻译、清洗、摘要的提示词与行为**零改动**；`official` 的消费在任务 2（qwen-mt terms 干预）。

## 四、遗留注意（给任务 2）

- 查证会如实地把**游戏机制词**也查出「官方译名」（如 `弹刀 → Deflect` 是游戏内官方英译）。PRD 谈到的标注语义是「有官方译名 → 标注」，而 Q10.2 的直觉把 `弹刀` 当作「无官方译名 → 放开直译」。两件事不冲突但要 **task 2 设计时显式定夺**：`level` 字段（official/common）已如实持久化，届时可按「media 标题类才标注」或「official 才标注」裁决，数据都在。
- `officialNote` 里存来源 host 与等级，供人工审阅查证质量（Q7 的「官方优先、通用兜底」可追溯）。
