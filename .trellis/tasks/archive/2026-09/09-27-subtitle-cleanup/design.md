# 字幕纠错与断句优化 — 技术设计

> 本文是代码实现依据。读者：本任务的实现者（项目所有者本人）。含边界、契约、数据流、取舍与回滚形态。
> 参考（设计来源，GPL-3.0 只学思路、不抄源码）：
> `/f/learning_projects/asr_references/VideoCaptioner/videocaptioner/core/split/` 与 `core/optimize/`。

## 0. 落点与总体形状

独立服务，不改 2b 的 `SubtitleService`。数据处理链路（与现有 `prepare` 平行）：

```
POST /api/subtitles/clean  (MediaInput)
   → workspaceService.workspaceFor(input)          # 与 prepare 同一工作目录（按媒体内容寻址）
   → 读原始 segments：segments-<route>.json        # 2b 已产出；不存在则报错「先跑 /api/subtitles」
   → [split]  确定性切分超长段
   → [optimize] DeepSeek LLM 纠错（含守卫 + agent loop）
   → 写 cleaned：segments-<route>-clean.json + subtitles-<route>-clean.srt
   → 返回 CleanedSubtitleResponse
```

关键点：**读原始、写 cleaned**，产物带 `-clean` 指纹，**永不覆盖 2b 原始产物**；断点判据是 cleaned JSON 存在且非空。

## 1. 契约类（record）

> 归属：直接暴露为 HTTP 契约、且形状不需展平者，放 `model/`（见 directory-structure.md「契约类的位置」）；
> 需展平成请求/响应的 HTTP record 放 `web/dto/`。

### model 侧（内部）
- `CleanedSubtitle`（`model/subtitle/`，含 `Path` 故**不直接对外、无 `@Schema`**）：清洗后 SRT/segments 两条路径 + `route` + `language` + `segmentCount` + `srtSizeBytes` + `correctedCount` + `retrySteps`。

### web/dto 侧（照 `PrepareSubtitleRequest/PreparedSubtitleResponse` 风格）
- **请求体重用 `PrepareSubtitleRequest`**（不新建 `CleanSubtitleRequest`）：媒体定位方式与产字幕完全一致。`reference`（可选术语/参考）走**查询参数** `@RequestParam(required=false)`，不进请求体。
- `CleanedSubtitleResponse`（`web/dto/`，展平映射）：`route`、`language`、`segmentCount`、`srtPath`、`segmentsPath`、`srtSizeBytes`、`correctedCount`、`retrySteps`。**无 `splitCount`**——切分增量已反映在 `segmentCount`（清洗后段数），修正量在 `correctedCount`。

## 2. `SubtitleCleanupService`（service 层编排）

> 与 `SubtitleService` 同构：只做编排，不写业务算法；算法放独立协作类（下述），可单测。

依赖注入：`WorkspaceService`、`ASRProvider`（取 `route()` 与工作目录定位）、`ObjectMapper`（Jackson 3 / tools.jackson）、
`SubtitleSegmentSplitter`、`SubtitleCorrector`。

方法：
```java
CleanedSubtitle clean(MediaInput input);                  // 无进度
CleanedSubtitle clean(MediaInput input, String reference); // 带可选术语表
```
流程（伪代码）：
1. 卫语句：`input == null` → IAE；前置校验 raw 存在（见 §4）。**所有卫语句排在本边界，早于一切 I/O**（error-handling 硬规则）。
2. `SubtitleRoute route = asrProvider.route()`；`workDir = workspaceService.workspaceFor(input)`。
3. rawSegmentsPath = `segments-<route>.json`。**断点复用**：若 `segments-<route>-clean.json` 存在且非空 → 反序列化返回（跳过切分+纠错）。
   否则 raw 不存在 → 抛 ISE「字幕尚未获取：请先调用 POST /api/subtitles 产出原始字幕」。
4. `SubtitleTrack raw = objectMapper.readValue(...)`。
5. `List<TranscriptionSegment> split = splitter.split(raw.segments())`。
6. `CorrectionReport rpt = corrector.correct(split, reference)`——返回修正后 segments 与统计（修正句数、LLM 重试轮次）。
7. `track = new SubtitleTrack(route, raw.language(), rpt.segments())` → 写 cleaned JSON（检查点）→ 写 cleaned SRT。
8. 返回统计。

文件名（照 `SubtitleService` 的指纹纪律，static 方法、可单测）：
```java
segmentsFileName(route)   → "segments-<routeToken>-clean.json"
srtFileName(route)        → "subtitles-<routeToken>-clean.srt"
```
`routeToken` 复用 `SubtitleService.routeToken`（kebab，`asr-cloud`）。

## 3. 切分 `SubtitleSegmentSplitter`（确定性，无 LLM）

输入 `List<TranscriptionSegment>`，输出切分后的列表，**保持时间升序与内容顺序**。

算法（对每个原段，时长 `d = endMs - beginMs`，预算 `MaxSegmentMs`，默认 **8000ms**，经 `@ConfigurationProperties` 可调，见 §7）：
1. `d <= MaxSegmentMs` → 原样保留。
2. 否则在该段内找断点：以段内字符为轴，`targetEnd = beginMs + MaxSegmentMs` 对应一个字符位置预算；
   从该位置**左向扫描**找最近的分隔符——中文 `、，。！？；：`、英文/空格/句点 `/ `、数字标点 `,.;`。找到即在分隔符右侧切开。
3. 无分隔符 → 按字符比例硬切。
4. 时间戳分配：**按字符数比例**把 `[beginMs,endMs]` 切成各子段（与文本长度成比例，保证不越界、总区间守恒）。
   边界 clamp 到原段区间内；不做静音检测（第一版，见「不做」）。
5. 空文本子段丢弃（防御）：切分后 `text` 为空白的不输出。

取舍：确定性切分便宜、可测、无 LLM 成本；不能做语义边界的智能断点，对「长句语气未尽但到时长」会硬切。这是接受项，语义切分留第二版。

## 4. LLM 纠错 `SubtitleCorrector`

> 只改识别错误；保持原语言；最小改动。用 DeepSeek（复用 `LLMProvider` 既有抽象路径——MVP-3 的 `DeepSeekLLMProvider` 尚未落地，本任务**先直接注入 Spring AI `ChatModel`**，与 MVP-3 同一个注入点，后续 MVP-3 抽 `LLMProvider` 时把它括进来）。

输入切分后 segments，按**批次**并发纠错（默认 batch=20），返回修正后 segments + 统计。

**LLM 调用契约**（JSON 进、JSON 出）：
- 送：`{"1": "<text>", "2": "<text>", ...}`（index→text，引用参考照此输入）。
- 出：同形状字典，缺键必补全；**只输出 JSON，无注释/markdown**。
- system prompt（照参考语义重写，不整段复制 GPL 文案）：专业字幕修正者；修识别错误、清口头语/非言语符号（呃/um/ah/笑声等）、规范标点；**保持原语言（中文保持中文，英文保持英文）不翻译**；不合并不拆分条目（编号守恒）；可选 reference 术语表纠正误识别术语。
- user prompt：`Correct the following subtitles, keep original language, do not translate.` + 可选 `<reference>术语</reference>`。
- `temperature` 低值（约 0.2），确保保守。

**守卫（把 LLM 摁在「最小改动」上，参考 agent loop）**：
1. **键全集一致**：返回的 key set 必须与原输入完全一致（不许掉行/加行）。不一致 → 反馈重试。
2. **相似度阈值**：对每条算原文↔改文相似度（Java 实现轻量 `SequenceMatcher` 等价物：最长公共子序列 / Levenshtein 比值）。阈值：`原文字符数 <= 10` 用 0.3，否则 0.7。低于阈值 → 判定「改动过大」，反馈「只修错误、尽量保留原词与原结构」重试。
   - 相似度函数独立可单测（对齐 `difflib.SequenceMatcher.ratio()` 语义 ≈ `2*M / (len-a + len-b)`）。
3. **agent loop**：最多 `MAX_STEPS=3` 轮——LLM 出 → 校验 → 不通过则把上次结果与校验错误拼进消息再调；3 轮不通过取「最后有效结果」，仍不合规则**保留原文**（兜底不伤数据）。
4. **统计**：`correctedCount`（文本有变的段数，用于验收日志）。

**产物侧小规则（照参考）—— 未实现，已作废（2026-09-27 check 后订正）**：原设想「纠错后若某段只剩标点/空白则置空、不为空段造序号」。实际**不可达**：相似度守卫会拒掉「非空→空」（ratio=0 必低于阈值），且「短句被改成纯标点」这类非空结果本就不触发「不造序号」。故本轮不做，仅留此记录以免重启时误以为漏做。

## 5. 断点复用与产物完整性

- 检查点 = `segments-<route>-clean.json` 存在且字节数 > 0（**不拿 SRT 当检查点**——空转写产出 0 字节 SRT，理由同 SubtitleService design 决策 4，回归测试守着）。
- 写盘：先 JSON（检查点）再 SRT；两者都**原子替换**（`<name>.tmp` 再 move，同 SubtitleService §writeAtomically）。
- 断点复用时也校验：仅 cleaned JSON 反序列化成功才算命中；解析失败当未完成重跑（同 SubtitleService.readCheckpoint）。

## 6. HTTP 入口 `SubtitleController#clean`

`POST /api/subtitles/clean`，请求体重用 `PrepareSubtitleRequest`；`reference` 走查询参数（`@RequestParam(required=false)`，见 §7）。
- 同步端点（对称「临时面」）：理由同 `prepare`——MVP-1 异步管线落地后本端点随之改或删。
- 流程：`toMediaInput(request)`（复用现有私有方法）→ `cleanupService.clean(input, reference)` → `CleanedSubtitleResponse.from(cleaned)`。
- 日志只记请求形态（`sourceType`、`route`、split/corrected 计数、耗时），不记 URL / 路径原值（现有脱敏纪律）。
- 异常映射：`IllegalArgumentException`→400；raw 缺失抛 `IllegalStateException`→**500**（走现有 `ApiExceptionHandler`）。**2026-09-27 决策**：raw 缺失语义上更贴 409/400（调用顺序错、非服务端故障），但本轮**接受 500**（「服务端状态缺失」可归入 error-handling 的「环境不对」），改 4xx 需新增异常类或动既有 handler、超本任务「只新增」范围；4xx 映射留作后续小任务。

## 7. 配置

`SubtitleCleanupProperties`（`@ConfigurationProperties(prefix = "vidsage.subtitle.cleanup")`，照 `DashScopeProperties` 模式）：
- `maxSegmentMs`（默认 8000）：切分时长预算。
- `batchSize`（默认 20）：纠错并发批次。
- `maxSteps`（默认 3）：agent loop 上限。
- `similarityNormal`（默认 0.7）、`similarityShortText`（默认 0.3）：两类相似度阈值。
- `reference` 经**查询参数** `@RequestParam(required=false)` 传入（不进配置、不进请求体）。注意术语表将来若变长，查询串有长度上限，届时再考虑改请求体。

## 8. 取舍与风险（显式记账）

- **确定性切分 vs 语义切分**：硬切可能把一句语气切成两段；接受，语义切分留第二版。
- **每视频新增一轮 LLM 成本**：纠错是独立 LLM 调用（约等于多转一次字幕的量级）；对短/中视频可接受。若成本暴露，加「跳过纠错的开关」或只对错字密集段纠错（后续）。
- **纠错可能引入新错**：靠相似度守卫 + 键矛盾 + 失败保留原文三重叠底，把坏输出概率压到最低。
- **与 MVP-3 的耦合点**：清理后 `TranscriptionSegment` 结构不变（只有 text/time 变化），MVP-3 直接消费 clean JSON，无需改契约——这是最大的兼容性红利。

## 9. 回滚形态

- 代码回滚：本任务全部新增在 `model/subtitle/`、`service/`、`web/` 的新类与 `SubtitleCleanupProperties`，**不修改** `SubtitleService`/`SubtitleController` 现有路径，回滚即删新增。
- 产物回滚：`-clean` 是独立文件，删掉即回到 2b 原始字幕；原始产物未被动过。
- 断点回滚：删 `segments-<route>-clean.json` 即重跑。

## 10. 测试要点（与 implement.md 对应）

- 切分：给定构造段（含 >MaxSegmentMs、含/不含分隔符），断言切分块数、时间守恒（各子段 end-begin 总和=原区间）、顺序。
- 相似度函数：构造原文/改文对，断言 ratio 匹配手算值。
- 纠错：mock `ChatModel`（不起容器）驱动 agent loop——先给合规输出（断言一次通过）、再给超阈值改写（断言触发反馈重试、最后取高相似结果）、再给全程失败（断言保留原文）。
- 断点复用：构造非空 clean JSON，断言跳过切分/纠错。
- 控制器：mock service，断言 400（缺 raw/歧义输入）、200（正常），路径校验走 `toMediaInput`。