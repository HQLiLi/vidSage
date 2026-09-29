# 设计：字幕术语官方译名联网查证（2026-09-29 重构版）

> PRD 见同目录 `prd.md`。本文是**重构后**的权威形态；重构动因与结构裁决见文末「重构记录」。

## 一、冒烟实证与引擎定案（2026-09-29 实测，Acceptance #1）

curl 直调 DashScope OpenAI 兼容端点（`/compatible-mode/v1/chat/completions`），问「《只狼》的官方英文名」：

| 尝试 | 结果 |
|---|---|
| `qwen3.8-omni-flash` + `enable_search:true` | 400：`This model only supports search_strategy='agent'` —— Omni **支持**搜索，但它的搜索只实现了 agent 策略 |
| 上式 + `search_strategy:"agent"` | 400：`Non-streaming mode does not support Web Search in thinking mode` —— **agent 策略**的检索是推理循环 → 思考模式 → 该组合必须流式；补 `enable_thinking:false` 被兼容层无视 |
| `qwen-flash` + `search_strategy:"standard"` | ✅ 非流式打通。**但项目所有者查明（2026-09-29）：该别名停在 2025-07-28 快照、14 个月未更新，弃用** |
| `qwen3.7-flash` + standard + 思考默认开 | ✅ 结果正确，但单条 20~45 秒（思考链按输出计费），15 条把同步翻译接口拖到必超时 |
| **`qwen3.7-flash` + standard + `enable_thinking:false`** | ✅ **定案**：单条 1.5~2.7 秒、零思维链、结论质量无损 |

**引擎定案：`qwen3.7-flash` + `enable_search:true` + `search_strategy:"standard"` + `enable_thinking:false` + `temperature:0.1`，非流式。**

两条精确化（修正早期表述）：
- 「必须流式」是 **agent 策略**的约束，不是思考模式在所有组合下的约束——standard 策略连思考模型也能非流式直连（3.7 实测）。
- 价格（官方价目页，元/百万 token）：qwen-flash 0.15/1.5（已弃）；**qwen3.7-flash 0.2/0.8（定案，输出最便宜）**；qwen3.8-flash 0.8/2.7（贵 3~4 倍且未过冒烟，不选）。token 费每视频 ≈ 0.013 元，大头是搜索插件费 0.03 元/次，与模型选择无关。

## 二、结构（项目所有者 2026-09-29 三条重改意见定稿）

```
provider/llm/
├── SubtitleCleanser / SubtitleContextSummarizer / SubtitleTranslator   （具体 worker，不动）
└── termlookup/                            【查证能力自成一包（项目所有者 2026-09-29 定稿）】
    ├── TermLookupProvider.java            【接口】单方法 lookup(TermLookupCommand) → TermLookupResult
    │                                      查证是模型调用且将来可能换模型/换搜索源 → 接口成立；
    │                                      换实现 = @ConditionalOnProperty 配置切换（asr 同款）
    └── dashscope/
        ├── DashScopeTermLookupProvider.java   【实现】RestClient 裸调 + prompt + 接地校验 + 嵌套 DTO
        └── DashScopeTermLookupProperties.java 【实现专用配置】

（包名规范备注：Java 包名全小写连写，`termLookUp` 违反规范、`term-look-up` 非法标识符——
  故为 `termlookup`；`application.yml` 的 `vidsage.term-lookup` 是 Spring 配置键，kebab-case 不动。）

model/subtitle/
├── SubtitleContext.java                   （Term 三字段 asr/fixed/note，摘要层原始术语，恢复原样）
├── TermLookupCommand / TermLookupResult   （接口契约，保留；asr 模式契约放 model/）
├── VerifiedTerm.java                      【新增】查证后术语：asr/fixed/note + official/officialNote
└── VerifiedTerms.java                     【新增】容器 record（terms 产物文件的读写形状）

service/
├── SubtitleTranslationService.java        （查证编排内联在翻译流程里：见 §三）
└── SubtitleCleanupService.java            （完全不感知查证）
```

**类型拆分语义**：`SubtitleContext.Term` = 摘要层产出的原始术语（未清洗视角）；`VerifiedTerm` = 查证后的术语。**无三态**：`official=""` = 弃权（唯一空值语义），失败词不进产物文件。

**为什么接口放 llm 而查证 worker 不自成一包**：项目所有者裁决——查证也是模型调用，归 `provider/llm/`；「将来换模型」正是 provider-pattern「同一能力、多种来源」的接口成立条件（推翻本文件上一版「具体类不抽接口」的读法）。

## 三、编排：内联在翻译流程（无新 service/新 bean）

`SubtitleTranslationService.translate()` 内，在上下文加载之后、真翻译分支里：

1. 触发条件：源语种 ≠ 目标语种（复用 `isSameMainLanguage`）**且** `terms-<route>-<target>.json` 不存在。
2. `ObjectProvider<TermLookupProvider>` 注入：provider 缺席（yml 删 `provider` 行）= 查证关闭，跳过不报错。
3. 循环全部术语逐条 `lookup`：成功（含弃权）→ `VerifiedTerm`；**调用异常 → 跳过该词不进产物**（下次重跑文件缺失时自然补查）。
4. 组装 `VerifiedTerms` 原子写盘 + 统计日志（逐条 DEBUG/INFO + 一行汇总：共/官方/通用/无/失败/耗时）。
5. 文件存在 = 已查证 → 直接跳过（与 -clean 检查点同哲学：删文件即重查）。

清洗流程**零改动**（回退 893a959 引入的 verifier/translateProps 注入）。查证只在翻译发生时发生——目标语种默认 zh 的中文素材不会白烧搜索费。

## 四、持久化：独立产物文件

- `terms-<route>-<target>.json`（`SubtitleArtifactNames.verifiedTermsFileName(route, target)`），内容为 `VerifiedTerms`。
- **`context-<route>.json` 恢复为摘要原样输出，此后永不改写**——旧缓存零迁移（上一版「回写 context + 三态迁移」整体废除）。
- 文件名含 target：换目标语种 = 另一份产物、自动重查。
- 任务 2（qwen-mt 引擎切换）从该文件读 `official` 渲染 terms 干预参数。

## 五、调用形态（不变，随实现迁入 provider/llm/dashscope/）

- 请求：`POST {base-url}/chat/completions`，`enable_search:true` + `search_options.search_strategy`，**RestClient 自拼 JSON**（SDK 因 Jackson 同坐标冲突被禁，pom 有实测记录；兼容客户端层会丢扩展参数，自拼是要求不是偏好）。
- 回复 content 要求 JSON：`{"officialName","level","sourceUrl","quote"}`。
- **接地校验**：有译名必须带合法 http(s) URL + 非空引文，缺任一按「查不到」弃权；level 缺失但证据齐全 → 保守按通用处理；解析失败抛 ISE（该词不进产物、下次补查）。

## 六、重构记录（2026-09-29，首版 → 本版）

首版（commit 893a959）的结构：`provider/termlookup/`（接口+dashscope 实现）、`service/SubtitleTermVerifier`（编排 service）、`Term` 塞 official/officialNote 三态、查证结果回写 context、清洗+翻译双接线点。项目所有者三条重改意见：① 清洗后术语单独建类与原始术语分开；② 查证 LLM 归入 provider/llm（同清洗类），因后续可能换模型而保留接口；③ 编排并入字幕翻译流程，不建新 service。本版按此落地，并采纳「独立产物文件」持久化（消灭三态与旧缓存迁移）。
