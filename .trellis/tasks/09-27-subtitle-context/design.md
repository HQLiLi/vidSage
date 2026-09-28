# 设计：字幕纠错的批间上下文

> 本文按 2026-09-28 的实际落地代码重写。原始规划在实施中改道，被推翻的内容（旧类 `SubtitleCorrector` / `SubtitleContextBuilder`、光板 ChatClient、前窗注入）已删除，不再保留。改道原因只记一笔：原规划的 `Llm` 函数式接口 `(system, user) -> String` 表达不了 `.messages()` 多轮消息——批内重试要让模型看见自己上一条错在哪，跨批历史要成对携带往返，窄缝装不下，故 `SubtitleCleanser` 直持 `ChatClient`。

## 一、数据流（现状）

```
segments-<route>.json（原始，只读，必须已存在）
   │
   ├─ SubtitleCleanupService.summarizeContent(全篇拼接文本)    摘要出跨批上下文
   │     └─ @Qualifier("ContentSummarizer") ChatClient
   │        .prompt().user(全文).call().entity(SubtitleContext.class)
   │        任何 RuntimeException → SubtitleContext.empty() 降级，清洗继续
   │
   ├─ SubtitleSegmentSplitter(props.maxSegmentMs()=8000)       按时长切长句（另一层切分，别混）
   │
   ├─ SubtitleCleanser.clean(split, context, reference)        逐批纠错
   │     └─ 批粒度双判据：batchSize=20 句 / maxBatchChars=600 字，先到为准；
   │        单句就超字符上限也强制前进（防死循环）
   │        每批：history 副本起手 → buildUserPrompt → ask → parse → validate
   │        不过 → 带原因重试 ≤ maxSteps=3 轮；采纳 → 本批往返追加进跨批历史
   │        全败 → 回滚原文，不入历史
   │
   └─ 产物：segments-<route>-clean.json（检查点，先写）+ subtitles-<route>-clean.srt（派生，后写）
      都走 ArtifactFiles.writeAtomically 原子替换
```

**断点复用判据**：`-clean.json` 存在且非空 → 整条链路跳过（含摘要）。拿 JSON 不拿 SRT 当检查点：空转写产出 0 字节 SRT，会让空清洗永远判「未完成」。

## 二、类分工

| 类 | 位置 | 职责 |
|---|---|---|
| `SubtitleCleanupService` | `service/` | 编排：断点判据、摘要、切分、调清洗、写产物 |
| `SubtitleCleanser` | `com.vidsage.llm`（临时顶层包，收尾时挪 `provider/llm/`） | 批切分、提示词、`validate`、`agentLoop`、跨批历史 |
| `SubtitleContext` | `model/subtitle/` | `record(theme, terms)`，`Term(asr, fixed, note)`，`empty()` 静态工厂 |
| `ChatClientConfig` | `config/` | 两个带 `defaultSystem` 的领域 client：`subtitleCleanupChatClient`（纠错）、`ContentSummarizer`（摘要） |
| `TextSimilarity` | `utils/subtitle/` | 相似度，`validate` 用 |
| `SubtitleCleanupProperties` | `config/` | `maxSegmentMs` / `batchSize` / `maxBatchChars` / `maxSteps` / `historyMaxChars` / `similarityNormal` / `similarityShortText` |

## 三、跨批上下文的两条路

**1. 后窗——管「未来」**。下一批开头 2 行（类内常量 `LINES_AFTER=2`，未做成配置），以不带编号的纯文本放进 `<subsequent_content>`。不带编号就不会落进键集，`validate` 一行不用改。历史只覆盖过去，替代不了它：下一批开头才第一次出现的专有名词，本批只能靠它看见。

**2. 跨批对话历史——管「过去」**。第 k 批被采纳后，往共享 `history` 成对追加 `UserMessage(批次 JSON)` + `AssistantMessage(回复原文)`，带给后续所有批次。它是原始规划「前窗」的严格超集：前窗给的是上一批**未清洗**的原文，历史给的是**已采纳的纠正**；还避免了前窗回放上一批 ASR 错字、诱导模型把改对的词改回去。

历史机制的硬规矩：

- **生命周期**：`clean()` 的局部变量，返回即焚。绝不能是实例字段——本类是单例且 `spring.threads.virtual.enabled: true`，挂字段会在并发请求间静默串味
- **每批的 `messages` 是 `new ArrayList<>(history)` 的副本**：批内重试的失败往返只进副本，不进长期记录；直接用 history 会让下一批收到上一批的任务两遍
- **只有被采纳的往返入历史**；重试用尽回滚原文的批次不留痕（不给后面的批次看坏样板）
- **上限裁剪**：`historyMaxChars`（默认 20000，0 = 关闭退回「每批独立」）。以「一对往返」为单位从最老丢，守卫 `size() > 2` 保证刚追加的一对无论多长都保留；按对裁不按条裁（只留 assistant 会造出孤立的回答）。不变量：`size()` 恒为偶数，偶下标 user、奇下标 assistant
- **字符是粗代理**：中文约 1 字 ≈ 1 token，英文约 4 字 ≈ 1 token（偏保守）。不引入 tokenizer。只算历史，不含 system / 格式说明 / 本批任务
- **已知代价**：每批重发全部历史，输入 token 总量随「批数 × 上限」增长。默认 20000 是估算，没实测 token 与模型窗口；设 0 是逃生口，也是 A/B 对照开关
- **存回复原文而非规范化 JSON**：与 `appendRetry` 同构（assistant 消息只有一种表示）。代价：历史里的 assistant 文本与实际写进输出的文本不逐字节相等（`BeanOutputConverter` 剥围栏/空白 + 阈值内措辞差），读代码别误以为恒等

**格式说明只钉当前任务消息**：`ChatClient` 只往最后一条 `UserMessage` 追加 `FORMAT.getFormat()`，而重试的反馈会成为最后一条——所以格式说明拼在任务提示词末尾，整份请求恰出现一次。

## 四、提示词形态

```
### Context Information
<subsequent_content>
（下一批前 2 行，纯文本无编号；末批整段省略）
</subsequent_content>
以下后文仅供参考，不要修改，也不要出现在返回结果里。

### 内容主题
（theme；空则整段省略）

### 术语参考
- "asr原文写法" 应为 "正确写法"（note）
（只列本批文本真出现过的术语，小写比对；无则整段省略）

请修正以下字幕，保持原语言，不要翻译：
{
  "1": "……",
  "2": "……"
}

参考术语表：
（人工 reference 原样拼入；可为空）
```

重试反馈追加在失败回复之后：`上次回复校验未通过，原因：X` + `请只做最小修正，重新输出包含全部条目的合法 JSON`。

两个 system prompt 各挂各的 client（语义相反，不能共享）：

- **ContentSummarizer**：字幕术语分析——主题一两句；术语最多 30 条按重要性排序，`asr` 必须逐字照抄原文（不做任何规范化）、`fixed` 正确写法、`note` 类型背景。拿不准不编（错一条比漏一条危害大）。只输出 JSON
- **subtitleCleanupChatClient**：最小改动——修错字、去口头语、规范标点、不翻译、条目数不变（不合并/拆分/增删）。只输出「条号 → 文本」JSON

## 五、validate 与降级纪律

`validate`（包级可见，直接单测）两个硬约束：

1. **键集完全一致**：缺条目、多条目都重试（上下文不带编号，天然在键集外，守卫无需区分「可改/仅参考」）
2. **最小改动**：每条改写幅度不得超相似度阈值——原句 ≤ 10 字用 `similarityShortText=0.3`，其余 `similarityNormal=0.7`

降级链（同一纪律：宁可没纠/没上下文，不可被带偏/打挂）：

- 模型回 `{"k": null}` 或掉键 → 该条回滚原文（不判 null 会 NPE）
- `ask` 抛异常（网络/限流）→ 当「没拿到回复」走重试，不伪造 assistant 轮次
- 摘要任何失败 → `SubtitleContext.empty()`，清洗继续
- 批重试用尽 → 保留原文整批回滚

## 六、装配与测试形态

- `SubtitleCleanser`：`@Component`，构造注入 `@Qualifier("subtitleCleanupChatClient") ChatClient` + `SubtitleCleanupProperties`。公开签名无 SDK 类型（ChatClient 是私有字段）
- `SubtitleCleanupService`：构造注入 `@Qualifier("ContentSummarizer") ChatClient`——**临时违例**（SDK 类型进 service/），收口步新建 `provider/llm/SubtitleContextSummarizer` 接走，service 摘掉 Spring AI 类型
- 测试：**真 ChatClient + 桩 ChatModel**，不起 Spring 容器（`SubtitleCleanserTest` 13 例：消息形态、批内重试、跨批历史注入/排除/裁剪、围栏解析、回滚、`historyMaxChars=0` 关闭）

## 七、未实现（明确出栈，另行任务）

- **摘要产物落盘（R5）**：无 `context-<route>.json` 二级复用，重跑清洗重复付一次摘要调用
- **摘要输入截断**：全文直给摘要，长视频 token 量随全篇涨（原规划 summaryChars 未落地）
- **后窗行数配置**：常量 2（原规划 context-lines-before/after 未落地；前窗已随历史移除）
- **并发批处理**（参考项目 `max_workers: 4` 供届时参考）；**LLMProvider 四件套**（MVP3，`vidsage.llm.provider` 键已预留，落地后 worker 改吃该缝）
