# MVP-3 LLM 翻译与双语 SRT — 实施计划

> 需求见 `prd.md`，技术设计见 `design.md`（下文 §n 均指该文件）。
> 每个阶段末尾是一个**审查门**：改完即停、给所有者看，批准后才进下一步。

## 环境与命令

```bash
# Git Bash 下必须显式指定 JDK 21，否则报「不支持发行版本 21」
cd backend
JAVA_HOME=/f/java_soft/java21 mvn -q compile
JAVA_HOME=/f/java_soft/java21 mvn test

# 注意：surefire-reports 会残留上一次的报告，统计用例数前先删目录
rm -rf backend/target/surefire-reports
```

基线（开工前先量一次，作为对照）：`mvn test` 全绿，用例数记进本文件末尾的「实测记录」。
冒烟实例起停见文末注意事项。

---

## 步骤 1：观测项（design §九）

**先做这个**：它是两个悬置决策的取证手段，且完全不改行为，风险最低。

1.1 `SubtitleCleanser.clean` 的批次 DEBUG 日志补「本批字数」——切批循环里已累加 `chars`，
带入日志即可，不动切批逻辑。

1.2 `validate` 的返回由 `String` 改为 `ValidationFailure(kind, detail)`：
`FailureKind { KEY_SET, TOO_MUCH_EDIT }`，通过时仍返回 `null`；
`agentLoop` 按 `kind()` 计数并记 DEBUG（只记分类与轮次，不带 detail——detail 含字幕正文，
属脱敏红线内的用户内容）；`appendRetry` 用 `detail()`。
另按 `SubtitleCleanupProperties.logRejectedText` 开关补一条带 detail 的 **TRACE** 日志
（开发期专用：开关只在 `application-local.yml` 里开，生产无此键，且 TRACE 级别再过一道闸门），
用于判断相似度守卫拦下的是模型真改写、还是术语替换被误判。规范例外说明见
`logging-guidelines.md` §四。同步改 `SubtitleCleanserTest` 里对 `validate` 的直测断言。

**验证**：`mvn test` 全绿，用例数不少于基线（`validate` 的测试改断言不改条数）。

**审查门 1**：把 1.1 / 1.2 的 diff 给所有者看。**此时同步做一次真实素材读数**——
跑一条已有工作目录的 `POST /api/subtitles/clean`，从日志里取批次句数/字数分布，
结果填进文末「实测记录」，它将决定步骤 4 的批参数初值。

## 步骤 2：上下文缓存与源语种（design §三）

2.1 `SubtitleContext` 加 `sourceLanguage`（紧凑构造器兜底空串，`empty()` 同步）。
2.2 `ChatClientConfig.ContentSummarizer` 的 system prompt 加第 3 项输出：语种短码，拿不准给空串。
2.3 `SubtitleContextSummarizer.summarize` 返回 `Optional<SubtitleContext>`；成功才 present，
降级返回空 Optional。同步改 `SubtitleContextSummarizerTest` 与 `SubtitleCleanupService` 的调用点。
2.4 `ArtifactFiles` 加 `writeJsonAtomically` / `readJsonOrNull`（design §3.3 的语义边界：
`readJsonOrNull` 只服务断点形状的读，`readRaw` 不并入）。
2.5 `SubtitleArtifactNames.contextFileName(route)`。
2.6 `SubtitleCleanupService` 接上下文缓存（design §3.2 那段固定形状），替换现有的直接 `summarize` 调用。
2.7 `hitTerms` 从 `SubtitleCleanser` 提到 `utils/subtitle/TermMatcher`（design §4.3），
清洗侧改为调用它；`SubtitleCleanserTest` 里对应的直测迁到新的 `TermMatcherTest`。

**验证**：`mvn test` 全绿；`ArtifactFilesTest` / `SubtitleArtifactNamesTest` / `SubtitleContextSummarizerTest`
各补新方法的用例。

**审查门 2**：重点看 2.6 的三行分支形状与 2.7 的搬迁是否只搬不改行为。

## 步骤 3：翻译契约与渲染（design §二、§五、§六）

3.1 `model/subtitle/TranslatedSegment` / `TranslatedTrack` / `TranslatedSubtitle`。
3.2 `SrtRenderer.toBilingualSrt`（译文==原文时该条只渲染一行）。
3.3 `SubtitleArtifactNames` 加 `translateSegmentsFileName` / `translateSrtFileName`（带目标语种指纹）。

**验证**：`SrtRendererTest` / `SubtitleArtifactNamesTest` 补用例——
双语渲染、译文==原文单行、指纹串形状（`subtitles-asr-cloud-translate-zh.srt`）。

**审查门 3**：契约形状是冻结面，逐字段过一遍再往下。

## 步骤 4：翻译器（design §四）

4.1 `config/SubtitleTranslateProperties`（§七 的字段）。
4.2 `application.yml` + `src/test/resources/application.yml` 加 `vidsage.subtitle.translate.*`
（批参数初值取步骤 1 的读数；若读数显示清洗的句数先到，翻译的字符初值按 §4.2 的膨胀系数折算）。
4.3 `ChatClientConfig.subtitleTranslateChatClient`（§4.4 的提示词）。
4.4 `provider/llm/SubtitleTranslator`：切批（双判据）、三路上下文、跨批历史按对裁剪、
多轮重试、键集与空译文校验、用尽重试回退原文并计 `untranslatedCount`。

> **实现期发现的 design 缺陷与修法（2026-09-28）**：design §4.3 原定「术语命中判定与清洗共用一份
> 实现」，但两个 worker 的**输入文本不同**——清洗吃原始 ASR 文本（含 `asr`），翻译吃 `-clean` 文本
> （命中术语的字面已被换成 `fixed`）。共用单一按 `asr` 匹配的入口会：① 漏掉已被替换成 `fixed` 的
> 术语（本该保留原文的专有名词可能被翻掉）；② 命中清洗**故意没换**的术语，把 `fixed` 写进参考，
> 与清洗刚做的判断自相矛盾。**修法**：`TermMatcher` 判据本体保持一份，拆成 `hitTermsByAsr`（清洗）
> 与 `hitTermsByFixed`（翻译）两个入口；`SubtitleCleanser` 改调前者（行为不变），`SubtitleTranslator`
> 调后者。design §4.3 与决策记录 11 已同步订正。`TermMatcherTest` 补三条钉住两个入口的差别。

**验证**：`SubtitleTranslatorTest`（mock `ChatClient`，不起容器）覆盖：
键集不一致触发重试、空译文触发重试、用尽重试回退原文、跨批历史成对裁剪、
术语参考只列本批命中项、失败往返不进跨批历史。

**审查门 4**：翻译器是全任务的核心，逐个用例对着 design §4.5 核。

## 步骤 5：编排与 HTTP 面（design §八）

5.1 `service/SubtitleTranslationService`（§8.1 的 12 步）。
5.2 `web/dto/TranslatedSubtitleResponse`（展平 + `@Schema`）。
5.3 `SubtitleController` 加 `POST /api/subtitles/translate` + 类注释扩写「临时面」范围。

**验证**：`SubtitleTranslationServiceTest` 覆盖断点复用、`-clean` 缺失报错、跳过分支、
摘要降级不落盘；`SubtitleControllerTest` 覆盖请求体歧义（url/localPath 都传/都不传）。

**审查门 5**：全部单测绿后停，给所有者看整体 diff。

## 步骤 6：端到端真实调用

依次跑（同一媒体输入）：

```bash
# 1) 产原始字幕（若工作目录已有可跳过）
# 2) 清洗
curl -X POST localhost:8080/api/subtitles/clean -H 'Content-Type: application/json' -d '{"localPath":"<media>"}'
# 3) 翻译
curl -X POST localhost:8080/api/subtitles/translate -H 'Content-Type: application/json' -d '{"localPath":"<media>"}'
# 4) 再跑一次翻译，验证断点复用（LLM 不应被调用，日志里无批次行）
```

逐条核 `prd.md` 的验收标准，特别是：时间轴逐条对齐、术语保留原文、术语译法全片一致、
第二次翻译不重复摘要（日志里 `SubtitleContextSummarizer` 无调用）、跳过分支。

证据落 `learning-records`（真实运行记录，含耗时与统计）。

**审查门 6**：验收标准逐条过，未过的写明原因（不勾）。

## 步骤 7：规范回写与收尾

7.1 若步骤 2.7 的 `TermMatcher` 改变了 `utils/` 的示例清单，
同步 `directory-structure.md` 的「参照」一行。
7.2 若 `ArtifactFiles` 的职责描述因 Jackson 依赖变化，同步该类的 Javadoc 与
`directory-structure.md` 中「`utils/` 允许 Jackson」的既有表述（若原文已覆盖则不改）。
7.3 把步骤 1 的实测读数与步骤 6 的真实运行记录填进本文件文末。
7.4 规范回写提交与实现提交分开（沿用既有惯例）。

**审查门 7**：Phase 3 收尾，随后按分支工作流合并回 `dev`。

---

## 实测记录

> 开工后逐步填。空白即「未做」。

### 基线

- 开工前 `mvn test`（2026-09-28 量，`feature/mvp3-translate` 起点）：25 类 / 239 用例，全绿

### 步骤 1：清洗批次的句数与字数分布（决定翻译批参数）

实测（2026-09-28，两条素材）：

| 素材 | 时长 | 清洗后段数 | 批数 | 每批句数 | 每批字数 | 触发的判据 |
|---|---|---|---|---|---|---|
| B站 `BV1axNJ6REoa`（短视频） | 短 | 42 | 3 | 20 / 20 / 2 | 542 / 545 / 59 | **句数** |
| B站 `BV1C2ae6DEqW`（长视频） | 21m52s | 319 | 16 | 15 批均 20，末批 19 | 197 ~ 413，均值 ≈ 285 | **句数** |

**结论：句数上限（20）在两条素材上都是决定性的先到者，字符上限（600）从未够到。**
长视频 16 批的字数峰值 413，连 600 的七成都不到 —— 不是「差一点」，是差得远。
→ 当前配置下 `max-batch-chars: 600` 是**死参数**；「批大小是字数旋钮」这个直觉与实测相反。

顺带一条观察：短视频每批 542 字、长视频每批 285 字 —— 同样是 20 句，字数差近一倍。
说明「20 句」对应的实际负载随素材类型大幅浮动，**只按句数控制批次，批与批之间的请求规模是不可控的**。
这恰好印证了 design §4.2 的写法：两个判据管的是不同的东西（字符管延迟规模、句数管输出条目完整性），
缺一不可 —— 只是当前字符阈值定得太松，让它事实上失效了。

**对翻译的推论（2026-09-28 订正，原推论有误）**：

原推论说「翻译的字符预算折算到 300 后会远早于句数触发，句数上限退为护栏」——**算错了**。
翻译的输入字数与清洗**同源**（同一份 `-clean` 字幕），所以只要字符上限还高于素材均值，
实际抢跑的仍然是句数。实测清洗 20 句均值 285 字，上限设 300 时约合 21 句，
**句数照旧先到**，等于没调。

正确的推论：要控「单次请求的输出体量」，在这个任务里真正有效的旋钮是**句数**，
因为它是实际抢跑的那个。翻译的输出膨胀 1.5~2 倍（token 口径，按字符算更甚），
故与清洗保持相当的单请求体量，句数应从 20 降到约 10~12。

字符上限仍要留，但定位降为**次级护栏**：设 400（高于实测均值 285，常规素材不抢跑；
清洗实测出现过 20 句 542 字的长句密集素材，靠它在那类素材上兜底）。

步骤 4 按此定初值，步骤 6 用真实素材的读数校准后在此补记。

### 步骤 1：清洗校验失败分类计数

| 素材 | 批数 | `KEY_SET` 次数 | `TOO_MUCH_EDIT` 次数 | 说明 |
|---|---|---|---|---|
| `BV1axNJ6REoa` | 3 | 0 | 0 | 整趟 `retrySteps=0`，日志里无任何「校验未过」行 |
| `BV1C2ae6DEqW` | 16 | 0 | 0 | 同上 |

**结论（守卫去留的取证）：两条素材共 19 批，相似度守卫 0 次触发。**

这个读数比「没触发」本身更有力的地方在于：守卫是**事后**校验，拦不住模型改写、只能在改写后打回重试。
因此「从未触发」意味着它在 19 批真实素材上**没有产生过任何被观测到的价值** ——
要么模型本来就不改写（那守卫是死重量），要么它改写了但阈值松到没拦（那守卫同样无效）。
两种解释都指向删除。

**但本期仍不决定去留**（design §9.2 的边界）。理由：19 批只覆盖「剑术 / 游戏讲解」一个领域，
且守卫的价值本就体现为「低频但致命」——真要在生产里删掉它，最好再补一条完全不同领域的素材
（如台词密集、口语化重的访谈或对话类）确认同样不触发。读数手段已就位（分类计数 + 开发期详情日志）。

### 步骤 2：摘要语种输出的真实校验

提示词改动是本步唯一无法用单测覆盖的风险点（LLM 可能不按格式返回）。
2026-09-28 用 `BV1axNJ6REoa` 跑了一次真实清洗（10.4s）验证：

```
字幕摘要结果: theme=视频讲解冷兵器剑术中的防御技术…, sourceLanguage=zh, terms=震刀 → 震刀（…）；…
```

**结论：模型按格式返回了 BCP-47 短码 `zh`，未退化成语言名。** 落盘的
`context-asr-cloud.json`（2995 字节）形状为 `theme` / `sourceLanguage` / `terms`，与契约一致。

用户原有的 `-clean` 产物跑前备份、跑后按 md5 逐字节还原（该工作目录现在多出一个
`context-asr-cloud.json`，是本步新建产物的首个真实实例，保留）。

### 附：长视频各阶段耗时（B站 `BV1C2ae6DEqW`，21m52s，从零到清洗完成）

| 阶段 | 耗时 | 说明 |
|---|---|---|
| 下载（yt-dlp） | **112.6s** | 产出 155MB 的 `source.mp4` |
| 抽音频（ffmpeg） | **4.0s** | 8.1MB `audio-16k1ch48k-t0.m4a` |
| 生成字幕（DashScope ASR） | **48.3s** | 其中 DashScope 侧任务本身 46.2s（含提交与轮询） |
| *产字幕 HTTP 端点合计* | *165.0s* | 下载 + 抽音频 + 转写 + 写盘 |
| 清洗（319 段 / 16 批） | **30.0s** | 含一次全篇摘要 |

两个值得记住的比例：

- **下载占了产字幕端点的 68%**（112.6 / 165.0）。这是网络与 yt-dlp 的耗时，与 LLM 无关；
  但它意味着「同步端点顶到客户端超时」的风险主要来自下载，而不是转写。
- **清洗的 16 批花了 30s**：约 1.9s/批（含摘要那次调用）。按此推，翻译若要跑同样 16 批，
  单批延迟相当（输出更长，会略慢），量级仍在 30~60s —— 所以**翻译阶段替长视频做同步端点，
  真正的风险不在于慢，而在于它叠加在 165s 的产字幕之后**。

### 步骤 6：端到端真实运行（2026-09-28）

> **前提说明**：手上两条素材都是中文视频，默认 `target-language: zh` 会走「跳过翻译」分支、
> 测不到翻译器。故跑真实翻译时把 `application-local.yml`（不进仓库）的目标语种临时改成 `en`，
> 跑完已还原。zh→en 是配置可达、但非本项目主用例的路径（主用例是外语视频→中文）。

| # | 场景 | 输入 | 结果 | 耗时 |
|---|---|---|---|---|
| 1 | 跳过分支 | 短视频 `BV1axNJ6REoa`（42 段），target=zh | `skipped=true`，两条产物照常落盘 | **0.087s**（未调 LLM） |
| 2 | 真实翻译 | 同一素材，target=en | 4 批（12/12/12/6 句），retry=0，untranslated=0 | **8.34s** |
| 3 | 断点复用 | 再跑一次场景 2 | 直接返回，统计归零，未调 LLM | **0.104s** |
| 4 | 长视频 | `BV1C2ae6DEqW`（319 段），target=en | 27 批（每批 12 句，末批 7），retry=0，untranslated=0 | **41.4s** |

**对齐性**：场景 1 的产物 SRT 与 `-clean.srt` **逐字节相同**（`diff` 无差异）——符合「译文==原文时
该条只渲染一行」，跳过的产物不会出现两行一模一样的字幕。场景 2 的 42 条时间轴与 `-clean`
**逐条一致**（`diff` 无差异）。

**上下文缓存**：场景 2 命中 `context-asr-cloud.json`（该目录在步骤 2 已生成），未重跑摘要；
场景 4 的目录没有缓存，摘要跑了一次并落盘。两条路径都验到了。

**批量参数校准（design §4.2 的待校准项，结论：保持 12 / 400 不动）**

- 输出膨胀实测：短视频 8491 / 4859 = **1.75×**，长视频 42165 / 25285 = **1.67×**，
  落在设计估的 1.5~2× 区间内。
- 长视频 27 批的**字数峰值 272**，字符护栏 400 全程未触发 —— 与清洗实测同一结论：
  **句数才是决定性判据**。故当初「12 句翻译 ≈ 20 句清洗的输出体量」的折算成立，参数维持原值。

### 步骤 6 发现的缺陷：中文专有名词在英文译文里保留中文写法

42 条译文里 **9 条**含中文（原文与译文对照见 `research/zh-to-en-remnant.txt`），例如：

```
原文: 其实就已经像《只狼》里面的弹刀基本的雏形了。
译文: This is actually like the basic prototype of 弹刀 in 只狼.

原文: 在中国的剑法里面也会有把这个东西称呼为叫做举顶格。
译文: In Chinese swordsmanship, this is also called 举顶格.
```

**成因**：system prompt 写的是「术语参考里的写法是专有名词，一律保留原样不译」，而术语表的
`fixed` 值本身是中文（摘要把「震刀」「弹刀」「架势条」「只狼」等都列进了术语表，
只是 `asr == fixed`、无需替换）。于是模型在英文句子里把中文原样钉住。

**这是规则设计时的目标语种假设问题**：该规则按「外语 → 中文」设计
（`Mittelhau` 不该被译成「米特豪」），同一条规则在「中文 → 英文」下会把中文专有名词留在英文里。
主用例（外语→中文）不受影响；`zh→en` 是配置可达但非主用例的路径。

**去留待所有者定**（见 PRD「已知边界」一节的待决项）：倾向接受现状，因为放松这条规则
（让模型按目标语种给专有名词取译名）会反过来威胁主用例——设计里「拿不准不要编」的原则
正是为防 `Mittelhau` 被编成中文音译而立的。

### 验收标准逐条结果

| # | 验收标准 | 结果 |
|---|---|---|
| 1 | 产出双语 SRT，条数与起止时间与输入逐条一致 | **通过**（42/42，时间轴 diff 无差异） |
| 2 | 译文使用目标语种；术语表内的专有名词保留原文写法 | **通过**（但有上述「中文术语留在英文译文里」的边界，见上一节） |
| 3 | 同一术语/称谓全片译法一致 | **通过**（抽查：`架势条`、`弹刀`、`日耳曼` 在各自出现的多条里写法一致） |
| 4 | 源语种 == 目标语种时不调 LLM、产物完整、响应带标记 | **通过**（场景 1） |
| 5 | 上下文落盘；清洗跑完后翻译不重复摘要 | **通过**（场景 2 缓存命中） |
| 6 | 摘要失败时不落盘 | **通过**（单测覆盖，未在真实运行中制造失败） |
| 7 | 断点复用：产物已存在时重跑不调 LLM、统计归零 | **通过**（场景 3） |
| 8 | `-clean` 缺失时报错指明缺哪个文件、先跑哪一步 | **通过**（单测覆盖，未在真实运行中制造缺失） |
| 9 | 观测项落地并有真实素材读数 | **通过**（步骤 1 的两条读数） |
| 10 | 至少一条真实运行证据（learning-records） | 待步骤 7 |

---

## 遗留（本期不做，留给后续）

- **术语的「保留原文」规则没有方向性 —— 待另立任务**（本次最重要的遗留，详见 `prd.md`「已知边界」
  与 `research/acceptance-evidence.md` 附节）。
  - 现状：规则按「外语 → 中文」设计，在 `zh → en` 方向会把中文专有名词钉在英文句子里
    （实测 42 条里 9 条）；对照实验证明该方向上术语注入是**净负面**。
  - 目标形态（所有者已定）：**有官方译名就用官方的，没有就不翻**。
  - **为什么本次不做**：「有没有官方译名」模型判断不了——实验显示它对知名词准
    （`弹刀 → Deflect`）、对冷门词靠猜（`举顶格` 给的是描述性意译）。要靠**联网搜索**支撑，
    这是一块新东西：管线多一个阶段 + 一个外部依赖（按项目规范要走 provider 四件套）+ 契约要动
    （`Term` 得装目标语种的译名，而 `SubtitleContext` 现在明确「不带目标语种指纹」）+ 成本延迟模型变化。
  - **新任务的两条前置**：① 先找一条**外语素材**——改动动的是主用例赖以成立的规则，
    没有外语素材就验不了（本次最大验证缺口同源）；② 搜索服务选型要**先实测**（国内网络可达性
    直接决定能用谁），按项目「已实测」的作风来。
- **`SubtitleContext` 缓存指纹与目标语种的关系待重新审视**：一旦术语译名进契约，
  现决策 6「上下文不带目标语种指纹」就不再成立——见上一项。

- **`jsonQuote` 有两份私有副本**：`SubtitleCleanser` 与 `SubtitleTranslator` 各一份逐字相同的
  私有静态方法。它不触发「同一判据只有一份」（是纯格式化助手，不是判据），抽出来要同时动清洗器与
  `utils/`，超出本期「保持清洗行为不变」的边界。**等 design 决策 8 的骨架抽取定案时一并处理**
  （届时连同「批循环 + 重试循环要不要抽公共骨架」一起决定）。
- **`maxSegmentMs` 抛 IAE 与规范不一致**：`SubtitleSegmentSplitter` 对来自配置的 `maxSegmentMs`
  抛 `IllegalArgumentException`，而 `error-handling.md` 的判据是「配置问题属环境不对 → ISE」。
  既有代码问题，已记入 design §六，另立小任务，不在本期范围。
- **「单句就超字符上限」分支无直测**：`end == start ? end = start + 1` 那条防死循环的兜底，
  清洗器与翻译器都没有直测用例。非本期引入，implement.md 未要求。
- **两个 service 的完成汇总日志级别不一致**：`SubtitleCleanupService` 的完成汇总用 INFO，
  而新建的 `SubtitleTranslationService` 用 DEBUG。按 `logging-guidelines.md` 的级别分层，
  service 层编排节点应为 DEBUG——**偏离规范的是清洗那一侧**（且它与 Controller 的 INFO 出口重复）。
  翻译侧按规范走，未去迁就既有写法；是否把清洗侧统一到 DEBUG，另立小任务。

## 注意事项

- **冒烟实例的进程清理**：起过 `spring-boot:run` 之后，`TaskStop` 杀不掉它派生的子 JVM，
  必须按 PID `taskkill`（`netstat -ano | grep 8080` 找 PID）。
- **不要动 `.codex/config.toml`**（所有者自己改权限时动的，未提交）。
- 每次测量用例数前先 `rm -rf target/surefire-reports`，否则会读进陈旧报告。
- 翻译端点同步阻塞，长视频会跑很久；实测优先用短视频素材。
