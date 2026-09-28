# MVP-3 LLM 翻译与双语 SRT — 技术设计

> 需求与验收见 `prd.md`。Epic 技术方案见 `../09-24-vidsage/design.md`。
> 本文件 2026-09-28 写，以当时的代码现状为准（清洗层三个任务已归档）。
> 文中「现状」指 `SubtitleCleanupService` / `SubtitleCleanser` / `SubtitleContextSummarizer` 的既有实现。

## 一、管线位置与输入

```text
2b: 原始 segments ──> 清洗: segments-<route>-clean.json ──> 翻译: segments-<route>-translate-<target>.json
                                    │                                        │
                                    └─ context-<route>.json（摘要，两边共用）─┘
```

翻译的输入**只有** `segments-<route>-clean.json`：

- 该文件缺失或为空 → 抛 `IllegalStateException`，消息指明「缺哪个文件、先跑哪一步」
  （照抄 `SubtitleCleanupService` 对 `-raw` 缺失的处理与措辞，含 2026-09-27 已拍板的「本轮接受
  500、4xx 映射留作后续小任务」的既有取舍）。
- **不静默回退到原始 segments**：术语替换的成果只在 `-clean` 里，回退会让它白做，而且是静默的。
- 读进来的段落**不再切分**：清洗阶段已经切过（`SubtitleSegmentSplitter`），`-clean.json` 里的段落
  已是切分后形态。翻译多切一次会改变时间轴，破坏「与输入逐条对齐」这条验收。

## 二、数据模型

### 新增契约（`model/subtitle/`）

```java
/** 一条双语段落：原文与译文配对存放，避免两条平行列表错位。 */
public record TranslatedSegment(long beginTimeMs, long endTimeMs,
                                String sourceText, String translatedText) {}

/** 翻译产物：对照 SubtitleTrack。 */
public record TranslatedTrack(SubtitleRoute route, String sourceLanguage, String targetLanguage,
                              boolean skipped, List<TranslatedSegment> segments) {}

/** 翻译产出描述：对照 CleanedSubtitle。含 Path 与枚举，不直接对外，响应走 web/dto/ 展平。 */
public record TranslatedSubtitle(Path srtPath, Path segmentsPath, SubtitleRoute route,
                                 String sourceLanguage, String targetLanguage, boolean skipped,
                                 int segmentCount, long srtSizeBytes,
                                 int retrySteps, int untranslatedCount) {}
```

**为什么 `TranslatedSegment` 把原文与译文放一条 record 里**：双语 SRT 要逐条同时用到两者，
两条平行 `List` 一旦长度不等就是静默错位。配对存放让错位在类型上不可能。

**为什么 `TranslatedTrack` 存 `skipped`**：跳过（源语种==目标语种）与「翻译了但译文恰好等于原文」
是两回事。让产物自己对读者说明 LLM 有没有跑过，比要求读者去比对两个语种字段可靠。

### 沿用

- `TranscriptionSegment`（`model/asr/`）：清洗产物的段落类型，翻译读它，不改造它。
- `SubtitleContext`：见下节，本期加一个字段。

## 三、上下文缓存与源语种

### 3.1 `SubtitleContext` 加 `sourceLanguage`

```java
public record SubtitleContext(String theme, String sourceLanguage, List<Term> terms)
```

- 紧凑构造器沿用现有兜底风格：`sourceLanguage` 为 `null` → 收敛成空串。
- `empty()` 相应返回 `("", "", List.of())`。
- 字段名用 `sourceLanguage` 而不是 `language`：`SubtitleTrack.language` 已经叫 `language`，
  在同时有目标语种的语境里那个名字会歧义。

**为什么语种走摘要而不是单独发一次调用**：摘要那一步本来就把全篇字幕读进去了，加一个输出字段是
零额外开销；且语种与术语表同源落盘，缓存命中即同时命中两者。

**摘要 prompt 的改动**（`ChatClientConfig.ContentSummarizer`）：在现有「主题 / 术语」之外加第 3 项
输出——语种，要求给 BCP-47 短码（`zh` / `en` / `ja` / `ko` …），拿不准就给空串。
不引入「短码 → 自然语言名」的映射表：prompt 里直接给短码，模型自己理解。

**实测背景**（决定了为什么不能只靠 ASR）：`TranscriptionResult.language` 字段存在、链路通，
但 2026-09-25 实测中文音频 DashScope 返回 `null`，且提交侧一律不传 `language_hints`
（见 `../../archive/2026-09/09-25-vidsage-mvp2b-subtitles/research/asr-cloud-behavior.md` §三）。
故 ASR 侧语种**实测不可靠**，只能当兜底。

**源语种优先级**（翻译侧判定跳过的依据）：

1. `SubtitleContext.sourceLanguage`（LLM 推断，主路径）
2. `SubtitleTrack.language`（ASR 给的，实测多为 `null`，兜底）
3. 两者皆空 → **不跳过，照译**（宁可多跑一趟，不可静默漏译）

### 3.2 落盘与共享

- 文件名：`context-<route>.json`，加进 `utils/subtitle/SubtitleArtifactNames.contextFileName(route)`。
  **不带目标语种指纹**——上下文（主题 / 术语 / 源语种）与目标语种无关，同一份对任何目标语种都成立。
- 落点（2026-09-28 拍板，方案 B）：**断点分支留在 service 层**，`SubtitleContextSummarizer`
  保持「内容 → 上下文」的纯计算、不碰 I/O。

  理由：本项目「产物断点判断」一贯写在 service（`SubtitleService.readCheckpoint`、
  `SubtitleCleanupService.readCheckpoint`）；而 `SubtitleContextSummarizer` 在 `provider/llm/` 下，
  spec 把 provider 定位为「出站实现」、`provider/llm/` 的终态是具体 worker 类。
  让 worker 管产物布局与断点命中，同一个概念就会两处在 service、一处在 provider。
  保持无 I/O 的附带好处：它的单测仍是纯 mock `ChatClient`，不需要临时目录。

- `SubtitleContextSummarizer.summarize` 的返回改为 `Optional<SubtitleContext>`：
  **成功才 present**；LLM 报错 / 回复解析不出 JSON → 空 Optional，不抛异常（保持现有「摘要只是增益、
  不是前置条件」的语义）。调用方拿到空 Optional 自行降级成 `SubtitleContext.empty()`。

  为什么必须能区分成功与降级：**降级结果绝不能落盘**。若把空上下文写进 `context-<route>.json`，
  下次会命中这份空缓存，摘要永久丢失且不报错。

- 调用侧的固定形状（清洗与翻译各写一遍这三行，是方案 B 接受的代价）：

```java
SubtitleContext context = ArtifactFiles.readJsonOrNull(ctxFile, objectMapper, SubtitleContext.class);
if (context == null) {
    context = contextSummarizer.summarize(content).orElse(null);
    if (context != null) {
        ArtifactFiles.writeJsonAtomically(context, objectMapper, ctxFile);   // 失败不落盘
    } else {
        context = SubtitleContext.empty();                                   // 降级，且不写文件
    }
}
```

- 缓存文件在、但解析失败 → `readJsonOrNull` 返回 `null` → 当没有缓存，重摘要。
  与 `SubtitleService` / `SubtitleCleanupService` 现有断点语义一致。
- **顺序上无歧义**：翻译依赖 `-clean.json`，所以清洗必定先跑并落下上下文；翻译再跑时必然命中缓存。
  不存在「清洗用原始文本摘要、翻译用清洗后文本摘要」两份结果并存的路径
  （除非有人手工删了 `context-*.json`，此时以 `-clean` 拼接文本重摘要一次，可接受）。

### 3.3 `ArtifactFiles` 加两个方法

现状：`utils/fs/ArtifactFiles` 是零依赖的字节助手（读大小 / 原子写 / 原子改名 / 静默删）。

新增：

```java
public static void writeJsonAtomically(Object value, ObjectMapper mapper, Path target);
public static <T> T readJsonOrNull(Path path, ObjectMapper mapper, Class<T> type);
```

- 加进 `ArtifactFiles` 而不是新建类：它已经是「所有需要落盘的函数」的归口
  （媒体与音频走 `moveAtomically`、字幕走 `writeAtomically`），现状只差 JSON 这层。
  加的收益是实的——「序列化成 JSON → 原子写」这三行现在在 `SubtitleService` 与
  `SubtitleCleanupService` 各写了一遍，本期会变成四、五遍。
- 代价：它从「零依赖字节助手」变成「认识 Jackson」。**spec 允许**——
  `directory-structure.md` 明确把 Jackson 与 JDK、slf4j 并列在 `utils/` 的允许清单里。
- `readJsonOrNull` 的语义**只覆盖断点形状的读**：文件不存在 / 为空 / 解析失败一律返回 `null`。
  日志分两档：**不存在或为空时静默**（首跑没有检查点是正常路径，记 WARN 会在每次首跑刷噪音，
  与 `SubtitleService` / `SubtitleCleanupService` 既有 `readCheckpoint` 的行为一致）；
  **解析失败时记 WARN**（文件在但坏了，是需要人看的异常）。
  `SubtitleCleanupService.readRaw`（原始字幕必须能解析，坏了要抛 ISE）**不走它**，
  保持自己的 try-catch——那是另一种语义，不该被这个方法统一掉。

## 四、翻译器

### 4.1 位置与形状

- `provider/llm/SubtitleTranslator`，与 `SubtitleCleanser` 平级、**各自独立**，本期不抽公共基类
  （理由见 prd「不做」第 5 条：要抽的正是「切批判据」与「校验循环」，而这两处现在都处于待审状态）。
- 依赖注入 `@Qualifier("subtitleTranslateChatClient") ChatClient` + `SubtitleTranslateProperties`。

### 4.2 批的语义（本期先按现状沿用，参数后续校准）

沿用清洗的**双判据**切批（句数上限 + 字符上限，先到为准），但两个判据的**理由要分开写清**：

- **字符上限 = 单次请求/回复的规模**，也就是延迟的主旋钮（输出 token 是延迟大头）。
- **句数上限 = 一次要模型保证多少条 JSON 键完整**。与字数无关：同样 600 字，20 条短句比 3 条长句
  的掉行概率高得多，而掉行 → 重试 → 延迟翻倍。所以它不是上下文旋钮，是可靠性护栏。

现状读数：`batch-size: 20` / `max-batch-chars: 600`，按最近一次真实运行（20 句、SRT 2533 字节）
反推正文约 600 字——**两个判据在当前配置下几乎重合**，实际生效的是哪个取决于素材。
这是本期要测的第一件事（§九），测完再校准翻译自己的参数。

**批参数初值的推导**（2026-09-28 按 §九 实测读数定，此前写的「字符预算取清洗的一半」是错的）：

实测显示清洗的 20 句均值 285 字、区间 197~542，**实际抢跑的判据一直是句数**，字符上限 600 从未够到。
翻译的输入字数与清洗**同源**（同一份 `-clean` 字幕），所以只调字符上限改变不了批的句数
——上限 300 对均值 285 而言约合 21 句，句数照旧先到，等于没调。

要控「单次请求的输出体量」，真正有效的旋钮是**句数**。翻译输出膨胀 1.5~2 倍（token 口径，
按字符算更甚），故句数由清洗的 20 降到 **12**；字符上限设 **400** 作**次级护栏**
（高于实测均值 285，常规素材不抢跑；清洗实测出现过 20 句 542 字的长句密集素材，靠它在那类素材上兜底）。

两个值都是**待校准的初值**：§九 的读数只来自两条素材，步骤 6 用真实翻译运行的数据复核后回写。

### 4.3 上下文组织

三条路，与清洗同形但内容不同：

| 通道 | 清洗给什么 | 翻译给什么 |
|---|---|---|
| 全篇主题 | `SubtitleContext.theme` | 同左 |
| 术语参考 | 本批命中术语（按 `asr` 匹配；`asr → fixed` + 替换理由） | 本批命中术语（**按 `fixed` 匹配**，见下），语义改为「这些是专有名词，保留给定写法不译」 |
| 后窗 | 下一批开头 2 行**原文** | 同左 |
| 跨批对话历史 | 前几批的「原文批次 + 已采纳的纠正结果」 | 前几批的「原文批次 + 已采纳的**译文**」 |

跨批历史在翻译里的作用比清洗更强：它是「术语与称谓全片一致」的主要载体——后续批次能看见前面
把某个称呼译成了什么。裁剪沿用同一套按对裁剪（`historyMaxChars`，成对增删，永不出现孤立 assistant）。

**术语命中判定必须与清洗共用一份实现，但匹配的字段不同**：清洗侧 `SubtitleCleanser.hitTerms`
（判据是「本批原文拼成小写后包含术语 asr 的小写」）被渲染术语参考、构造校验基线、统计替换指标
三处共用；翻译是第四个使用者。故本期把它提到 `utils/subtitle/TermMatcher`（纯静态、无状态，
符合 `utils/<领域>/` 只收纯算法助手的定位）：**判据本体只有一份**，但给两个有名字的入口——

- `hitTermsByAsr(...)` —— 清洗用，拿 `asr` 匹配；
- `hitTermsByFixed(...)` —— 翻译用，拿 `fixed` 匹配。

**为什么是两个入口而不是一个（2026-09-28 实现期订正）**：两者**输入文本不同**——清洗吃原始 ASR
字幕（写的是 `asr` 那个错写法），翻译吃清洗后的 `-clean` 字幕（命中过的术语已多被替换成 `fixed`）。
用一个入口按 `asr` 硬套会同时踩两个坑：① 清洗**成功替换**的术语在 `-clean` 里只剩 `fixed`，按
`asr` 匹配不到 → 漏进翻译的术语参考 → 本该保留原文的专有名词可能被翻掉；② 清洗判断读不通、
**故意不换**的术语在 `-clean` 里仍是 `asr`，按 `asr` 匹配会命中 → 术语参考里给出 `fixed`，而清洗
刚裁定这句里它不是那个词，指令与正文自相矛盾。`asr == fixed` 的条目（原文写法本来就对，如「只狼」）
两种匹配等价，按 `fixed` 照样命中，正是该进参考的那批。**这是有意的搬迁，不是「先不抽骨架」的
例外**——搬的是「同一判据只能有一份」的纯函数，不是批循环与校验循环那套骨架。

### 4.4 提示词

挂在 `ChatClientConfig` 的 `subtitleTranslateChatClient` bean 上，要点：

- 角色：专业字幕翻译，把每条字幕译成目标语种（配置值以短码形式写进 prompt）。
- **条号原样保留，不许合并、拆分、删除或新增任何一条**（对应校验里的键集一致）。
- **术语参考里的写法是专有名词，保留原样不译**；跨书写系统时也照给定的写法输出。
- 结合上下文理解整段，不要逐字硬译；但不许添加原文没有的信息。
- 只输出 JSON 对象，键为条号字符串、值为译文；不要解释、注释或代码块。

### 4.5 校验与重试

- **键集必须与输入完全一致**（防掉行 / 串行 / 多行）。这是翻译的头号故障，必须留。
- **每条译文 trim 后非空**。
- **不设相似度守卫**：译文与原文天然不相似，`TextSimilarity` 那套在翻译里没有意义，也不引入
  （prd「不做」第 4 条只针对清洗侧的守卫去留）。
- 失败重试走多轮消息（把失败回复作为 assistant 消息留在局部历史里 + 追一条说明原因的 user 消息），
  上限 `maxSteps`，与清洗同形。
- **用尽重试仍失败 → `translatedText` 回退为 `sourceText`**（宁可没译，不可乱译），
  并计入 `untranslatedCount` 让调用方看得见——静默产出未翻译的行是本条最需要防的事。
- 批内重试的失败往返**不进**跨批历史（只收被采纳的那一次），理由同清洗：
  否则等于告诉后面的批次「这种输出被接受过」。

## 五、双语 SRT 渲染

`SrtRenderer` 加一个方法（该类的 public 方法已是既有跨包入口）：

```java
public static String toBilingualSrt(List<TranslatedSegment> segments);
```

- 每条：序号 / 时间轴 / 原文行 / **译文行** / 空行。
- **译文与原文相同时只渲染译文行之外的一行**（即整条只有一行文本）。跳过的产物因此不会出现
  两行一模一样的字幕；这也让「跳过」在 SRT 里自然可读，不需要额外标记。
- 时间轴沿用 `formatTimestamp`，不新写格式化。

## 六、产物命名

加进 `SubtitleArtifactNames`：

```java
public static String translateSegmentsFileName(SubtitleRoute route, String targetLanguage);
    // segments-<route>-translate-<target>.json   检查点
public static String translateSrtFileName(SubtitleRoute route, String targetLanguage);
    // subtitles-<route>-translate-<target>.srt   派生视图
public static String contextFileName(SubtitleRoute route);
    // context-<route>.json                       摘要缓存
```

**目标语种必须进指纹**，理由与现有 `route` 指纹完全同源：工作目录按媒体内容寻址，
目标语种是配置驱动的，同一媒体下唯一会变的就是「译成什么语」。不带指纹时，改了
`target-language` 再跑会命中上一次的产物被当成已完成而跳过，返回与当前配置不符的字幕且不报错。
（`contextFileName` 相反，不带——见 §3.2。）

**目标语种先归一化再拼名**：`trim()` + `toLowerCase(Locale.ROOT)`。不归一化的话，配置写成 `ZH`
就会静默产出第二份产物、绕开断点——与「必须带指纹」要防的是同一类事故。不做更进一步的 BCP-47
规范化（如 `zh_cn` → `zh-CN`）：那属于「配置写错该报错」而非「替它猜」的分界。

**语种为空时抛 `IllegalStateException`，不兜底**（2026-09-28 定）。两个理由：
空语种会拼出 `translate-.json` 这种名字，任何两个未配置的语种都会撞到同一份产物；
异常类型按 `error-handling.md` 的「非法值是谁产生的」判据取 ISE——该值来自配置而非请求体，
属「环境不对」，映射 500。抛 IAE 会变成 400，等于告诉调用方「你的请求有问题」，
而请求本身完全合法，错的是部署配置。
（注：同包 `SubtitleSegmentSplitter` 对同样来自配置的 `maxSegmentMs` 抛的是 IAE，
那是既有代码与规范的一处不一致，不是本任务该跟的规矩；是否回头修它，另立小任务，不在本期范围。）

## 七、配置

```yaml
vidsage:
  subtitle:
    translate:
      # 目标语种（BCP-47 短码）。源语种与之相同则跳过翻译，不调用 LLM
      target-language: zh
      # 一次丢给 LLM 翻译多少句（一「批」）；与 max-batch-chars 双判据，先到为准
      batch-size: 12
      # 一次最多多少字（次级护栏，见下方批参数依据）
      max-batch-chars: 400
      # 重试上限：模型回得不对（掉行 / 空译文）就重来，最多几次
      max-steps: 3
      # 跨批对话历史的字符上限（语义同清洗，见 SubtitleCleanupProperties）
      history-max-chars: 20000
```

`config/SubtitleTranslateProperties` 独立于 `SubtitleCleanupProperties`：两者的批参数语义虽同形，
但校准依据不同（等长 vs 膨胀），合并会让将来只调一边的人误改另一边。

## 八、HTTP 面与编排

### 8.1 `SubtitleTranslationService`（`service/`）

```text
1. 校验 input 非空 → IAE
2. route = asrProvider.route()；workDir = workspaceService.workspaceFor(input)
3. 解析四条路径：-clean.json（输入）、-translate-<target>.json、-translate-<target>.srt、context.json
4. 断点：-translate-<target>.json 存在且非空 → 反序列化直接返回，各项统计归零
5. 前置：-clean.json 必须存在且非空，否则 ISE（消息指明缺哪个文件、先跑哪一步）
6. 读 clean track
7. context = 读缓存，miss 则摘要并落盘（§3.2 的固定形状；拼接文本取 -clean 的段落）
8. 源语种 = context.sourceLanguage 优先，空则回退 track.language()，仍空则不跳过
9. 若源语种非空且 equalsIgnoreCase(target)：跳过 → 每条 translatedText = sourceText，
   skipped = true，不调用 LLM，不发翻译阶段日志的批次数
10. 否则 translator.translate(segments, context, target)
11. 写两条产物：先 JSON（检查点）再 SRT（派生视图），都走 ArtifactFiles 原子写
12. 返回 TranslatedSubtitle
```

- **不碰 `-clean` 产物**（只读）：与 `SubtitleCleanupService` 不碰原始产物同一条纪律。
- 断点判据用 JSON 而非 SRT：理由同清洗（SRT 是派生视图；空转写会产出 0 字节 SRT，
  拿它当检查点会让空产物永远判「未完成」）。

### 8.2 `SubtitleController` 加 `POST /api/subtitles/translate`

- 请求体复用 `PrepareSubtitleRequest`（`url` / `localPath` 二选一）。
- **目标语种不进请求参数，只走配置**：与 `route`（`vidsage.asr.provider`）同样由配置驱动，
  产物名带指纹后也不存在「同一份媒体译成两种语言」的冲突；不引入请求级覆盖，少一个非法输入面。
  （同 `SubtitleController` 不暴露音频规格的理由。）
- 响应 DTO `web/dto/TranslatedSubtitleResponse`：由 `TranslatedSubtitle` 展平
  （路径转字符串、枚举转名字），字段都带 `@Schema`，形状对照 `CleanedSubtitleResponse`。
- 入口与出口各一条 INFO 日志（形态 + 耗时 + 统计），照抄 `clean` 端点的写法。
- 类注释里的「临时面」说明同步扩到本端点：长视频逐批往返会跑很久。

## 九、观测项（两个悬置决策的取证）

这两项**不改行为**，只加读数。

### 9.1 清洗批次的字数

`SubtitleCleanser.clean` 现有的批次 DEBUG 日志是
`序号 / 句数 / 历史轮数 / 历史字符`，补一个**本批字数**（切批循环里已经在累加 `chars`，
把它带出来即可）。跑一条真实素材读这行，即可判断 `batch-size` 与 `max-batch-chars`
哪个实际生效，进而校准翻译的批参数（§4.2）。

### 9.2 清洗校验失败的分类

现在 `validate` 返回一个字符串、`retrySteps` 合并计数，两类失败分不开。改法：

```java
/** 校验失败：kind 是分类，detail 是给人看的原因。null 表示通过。 */
record ValidationFailure(FailureKind kind, String detail) { enum FailureKind { KEY_SET, TOO_MUCH_EDIT } }
```

`validate` 的返回类型由 `String` 改为 `ValidationFailure`（通过时仍返回 `null`），
`agentLoop` 里按 `kind()` 计数并记 DEBUG。

`detail` 里内嵌本句原文与模型改写的文本，属脱敏红线（`logging-guidelines.md` §四）内的用户内容，
故**默认不进日志**：上面那条 DEBUG 只记分类与轮次、不带 detail，它才是生产环境唯一的读数来源。
另按开关补一条带 detail 的日志，机制与约束：

- `vidsage.subtitle.cleanup.log-rejected-text`（`SubtitleCleanupProperties` 末尾字段）为 `true` 时才打；
- 这个键只写在**不进仓库**的 `application-local.yml`，生产配置里没有它 → 开关开不了，
  这是结构性保证；
- 级别用 **TRACE** 而非 DEBUG：即使开关被误开，也要再过一道级别闸门（生产基线 `com.vidsage: DEBUG`，
  TRACE 不输出）；
- 用途与边界：只为判断「守卫拦下的是模型真改写、还是合法的术语替换被误判」，不得推广成
  「用户内容可以进日志」。规范侧例外说明见 `logging-guidelines.md` §四。

**为什么用结构化而不是按字符串前缀分类**：前缀分类是脆的——文案一改就静默失效，
而它服务的正是「观测」这件事本身。改签名会动到 `SubtitleCleanserTest`（`validate` 是包级可见、
有直测），属可接受的代价。

跑真实素材看读数：

- `TOO_MUCH_EDIT` 0 次触发 → 该守卫在实际场景里从没拦下过东西 → 支持删除，
  换来 `validate` 大幅简化（砍掉双目标 `Math.max` 与 `baselineOf`）。
- 触发且拦下的是真改写 → 留，但阈值要重新定，并把「`similarity-short-text: 0.3` 是怎么来的」补上。

**去留不在本期决定**，本期只交付读数。

## 十、决策记录

| # | 决策 | 日期 | 理由 / 备选 |
|---|---|---|---|
| 1 | 输入吃 `-clean.json`，缺失即报错，不回退 | 2026-09-28 | 术语替换成果只在 `-clean` 里。备选：吃原始并自行注入术语，会让「清洗用的原文」与「翻译用的原文」分叉 |
| 2 | 专有名词保留原文不译 | 2026-09-28 | 与「字幕显示原文」的既有拍板一致。备选：查表给中文译名，需扩 `Term` 契约（动已冻结契约） |
| 3 | 语种并入摘要调用，随上下文落盘 | 2026-09-28 | 零额外调用；ASR 侧 `language` 实测恒 null，靠不住 |
| 4 | 上下文缓存落盘，断点分支留 service（方案 B） | 2026-09-28 | 与现有「断点判断都在 service」一致；provider worker 不碰 I/O 与产物布局。备选：summarizer 收口全部（少三行，但打破既有分工） |
| 5 | 摘要失败不落盘 | 2026-09-28 | 落盘会让空缓存永久生效、摘要丢失且不报错 |
| 6 | 翻译产物名带目标语种指纹；上下文名不带 | 2026-09-28 | 与 route 指纹同源（防配置变更后误用旧产物）；上下文与目标语种无关 |
| 7 | 不预留反思/重写轮次 | 2026-09-28 | 与 `speakerId` 不预留、`LLMProvider` 不建同一把尺子；反思轮天然可加 |
| 8 | 翻译器独立实现，暂不抽公共骨架 | 2026-09-28 | 要抽的切批判据与校验循环均处于待审状态（决策 9、10），抽了会返工 |
| 9 | 相似度守卫先观测、不决定去留 | 2026-09-28 | 无数据可判。先验倾向删（prompt 已写死禁止改写 + 复杂度真实），但要读数 |
| 10 | 批的句数上限保留，理由改写为「输出条目完整性」 | 2026-09-28 | 字符上限管延迟，句数上限管掉行；两者理由不同，不能合并成一个字数旋钮 |
| 11 | `hitTerms` 提到 `utils/subtitle/TermMatcher`，并拆成 `hitTermsByAsr` / `hitTermsByFixed` 两个入口 | 2026-09-28（实现期订正，原方案是单一 `hitTerms` 按 asr） | 同一判据必须只有一份（清洗注释已明确要求），翻译是第四个使用者；但清洗吃原始 ASR 文本、翻译吃 `-clean` 文本，两者该匹配的字段不同（详见 §4.3），故判据本体一份、入口两个 |
