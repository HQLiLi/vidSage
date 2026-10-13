# infrastructure 层 · 执行计划

> 父 `implement.md` §1 的标准动作（A→E）全程适用，本文件只补**本层的清单与门**。
> **粒度标准 = `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令）**，此处不重复一字。
> 父 `prd.md` 的「不做」四条同样适用（尤其「不动 `@Schema` 落位」「diff 里不许出现非注释行」）。

## 0. 执行顺序（已走完）

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | package-info：**新增 `provider/llm/` 一份**；**订正 `persistence/` 失实**；复核根 / `process` / `provider` | ✅ |
| 2 | LLM worker 与 termlookup（`provider/llm/` 5 类 + `termlookup/dashscope` 2 类） | ✅ |
| 3 | 外部工具适配器（`process/` 4 类 + `provider/audio/ffmpeg` + `provider/download/*`） | ✅ |
| 4 | 线② / 线③ 其余命中（`persistence/` 4 类 + `provider/asr/dashscope` 2 类） | ✅ |
| 5 | 全层块全量复核（**6 行及以上全部 dump 出来逐块读过**，115 块） | ✅ |
| 6 | 自检 + 报审查 | **门 G3 待裁** |

## 0.1 工作集（2026-10-13 实测 → 收口后校正）

三条筛选线的并集原本 18 文件；**实际改动 26 个 .java + 新增 1 个**（改动面 = 本层 31 个文件里的 27 个）。
**未动的 4 个**：`process/ExternalToolProperties`、`process/ProcessOutcome`、
`provider/asr/dashscope/DashScopeProperties`、`provider/download/ytdlp/YtDlpProperties` ——逐块核过，
四条都是 `@param` 语义 + 负面约束，无变更史、无粒度错位。

| 集外扩入 | 被什么捞出来 |
|---|---|
| `fs/NioFileStateReader`、`fs/NioWorkspaceDirectoryManager` | 阶段 5 全层复核（类注释是「XX 的实现」复述类名 + 无句号；`ensureCreated` 三行「做什么」） |
| `concurrency/SemaphoreTaskConcurrencyGate` | 阶段 5（`submit` 首行无句号） |
| `provider/package-info` | 「本层**不再**另建抽象接口」的「不再」 |
| `process/ExternalProcessRunner` | 线②的 research/ 指针 + 阶段 5 的两个构造器样板 |
| `persistence/mapper/VideoTaskMapper` | 阶段 5（与 `SubtitleKeywordMapper` **逐字重复**的可见性边界段） |

## 0.2 从上两层继承的教训（试点与 application 的 `implement.md` 已归档）

1. **机械线漏整档**：`>15` 会放过 13–15 的噪音块 → 本层把 **6 行及以上全部 dump 逐块读**（87 + 17 + 11 = 115 块）。
2. **成稿要当整体读一遍**：逐条对着规则打勾 ≠ 做完。
3. **上移优先于删除**：搬得走的先搬（批级进度 → spec），再从代码里删本地副本。
4. **不代写**：缺注释且原因推不出来的，登记进待订正清单。
5. **只改注释**：diff 里不出现非注释行——本层实测 **0 行**（中途误删过一次 `@Mapper`，当场发现并还原，最终 diff 干净）。
6. **报审查时「标准补充」（§4）比 diff 更值得看**。
7. **两类不单独立项扫**（上层裁定，本层沿用）：`@param` 纯复述参数名 **暂不动**；标点硬伤**跟随改动**。

## 0.3 本层已知重点（处置结果）

| 位置 | 处置 |
|---|---|
| LLM worker 的提示词设计理由 | 按预判**直接删本地副本**——归档 `10-11-subtitle-keyword-regenerate/design.md` 决策 5 / 决策 7 有全文。AC「提示词说明已上移（查得到）」成立 |
| 模型/温度的**校准依据** | `DashScopeTermLookupProperties#model` 的选型对比（qwen-flash 别名停更 / Omni 死路 / 价格）删——归档 `2026-09/09-29-subtitle-term-official-lookup/design.md` §选型 有**逐字同源的全文**（含三个备选与价格表）；`temperature` 的 why **留**（防人来改回默认温度） |
| 「包级 static：可直测」 ×**17** | 一律删测试说明；保留承载非测试 why 的那几处（`ExternalProcessRunner` 的 native.encoding 确定性理由、`daysSinceRelease` 的 `@param today`） |
| 外部工具坑位 | **全部保留**（prd 明文的「别当噪音删」）。抽查见 §5 |
| `LlmSubtitleCleanser#baselineOf` 那类守卫 | 保留（正典 §三 点名的例外） |
| `（见 quality-guidelines）` / `directory-structure.md…` / `provider-pattern.md…` / `docs/prd/…` 指针 | **全部保留**（正典 §五：spec 与随仓库走的文档是一等公民） |
| `2b` 遗留阶段编号 2 处 | `LocalFileMediaAcquirer:121` → 改白话「后续阶段」；`YtDlpRunner:22` → **整段删**：那是**失效预言**（代码里根本没有 `--write-subs`，平台字幕功能未落地，属禁令 6） |
| `persistence/package-info` 失实 | 已补 `subtitle_keyword` 三件套（见 §3 #1） |
| **新发现（Step A 没覆盖）** | `SubtitleKeywordMapper` 有一段「可见性边界（硬规则）…」与 `VideoTaskMapper` **一字不差**；`DashScopeTermLookupProvider` 在 `record ChatRequest` 上挂着**两个 Javadoc 块**（前一个是残留副本）；`LlmSubtitleCleanser.clean(…, listener)` 与 `emitBatchProgress` 各有一处**未闭合括号的残句** |

## 1. 逐文件登记表（收口后）

| # | 文件 | 处置 | 上移去向 |
|---|---|---|---|
| 1 | `package-info.java`（根，21 行） | **一字未动**——逐块核过，是本层必要的分包地图 + 依赖规则 | — |
| 2 | `persistence/package-info.java` | **补 `subtitle_keyword` 三件套**（原只列 task 表，漏了 10-11 加进来的三个） | 目的地 |
| 3 | `process/package-info.java` | 删「（**原** provider/tool 与 model/process **合并**）」——正典 §六 记过的自然语言变体 | — |
| 4 | `provider/package-info.java` | 「本层**不再**另建抽象接口」→「不另建」 | — |
| 5 | `provider/llm/package-info.java` | **新增**（用户裁定：只补这一份）。承载 llm 子包的 worker→端口映射 + 两个包私有助手的分界 + 「外部文本一律走 PromptDataBlock」 | 目的地 |
| 6 | `persistence/entity/VideoTaskEntity.java` | 删「主 PRD 对实体类的既定形态」指针；`@Data` 句去快照腔；**`sourceUri` 按禁令 12 改显式约束**（与 application 的 `TaskSnapshot` 同款写法）；`translationStatus` 删「（2026-10-02 两段式）」 | — |
| 7 | `persistence/entity/SubtitleKeywordEntity.java` | 删「普通类 +」与**「与 VideoTaskEntity 同形态」**（禁令 1），改写边界句 | — |
| 8 | `persistence/mapper/VideoTaskMapper.java` | 删「可见性边界（硬规则）…」整段——`directory-structure.md:230/245`、`database-guidelines.md:36/38` 有全文，代码里是**第三份副本** | spec（已有） |
| 9 | `persistence/mapper/SubtitleKeywordMapper.java` | 同上（与 #8 逐字重复） | spec（已有） |
| 10 | `persistence/repository/MybatisVideoTaskRepository.java` | 删构造器样板；删「（design.md 数据流节）」；删「Wrapper 等 MP 类型不出本包」（spec 已有）；删 `toDomain` / `toEntity` 两处复述（类注释已说「转换点全项目只此一处」） | — |
| 11 | `persistence/repository/MybatisSubtitleKeywordRepository.java` | 删构造器样板 +「Wrapper 不出本包」 | — |
| 12 | `process/ExternalProcessRunner.java` | 删「（逐条有实测依据，见 **research/**external-process-integration.md）」；删两个构造器 Javadoc（含「包级可见：供测试…」）；删 `drain` 的测试说明。**六条关键行为与「已知局限」全留** | — |
| 13 | `process/ProcessToolDiagnostics.java` | 删构造器样板；删 3 处「包级 static：测试…」；类注释「只给同层的 X / Y 用」→「只给同层实现用」（禁令 7） | — |
| 14 | `provider/llm/LlmSubtitleCleanser.java` | **批级进度上移 spec**（类注释与 `clean` 各一份重复表述，且 §三 明文「禁止进度事件枚举」）；两处**残句订正**；`FORMAT` / `parse` 去「原本 `.entity()`…」变更史；`buildUserPrompt` 删「（2026-10-12 起）」「（design 决策 2）」与「之前只有后窗有标签」叙事、删测试钩子行内注释；`ValidationFailure` 去「（拆分前）」「包级可见为单测」；三参重载「不关心进度（内部走 noop()）」→「不报告进度」 | spec（进度事件序列） |
| 15 | `provider/llm/LlmSubtitleTranslator.java` | 同款：删「（design 决策 8）」「**09-28 实证**」（正典 §六 记的漏检短任务号）、`validate` 的「包级可见是为了直接单测」、`parse` 里「与 `.entity()` 同一条路」、`translate` 的进度残句；「本期不抽公共骨架」改写为无时间性表述 | spec（进度事件序列） |
| 16 | `provider/llm/LlmSubtitleContextSummarizer.java` | 删「（2026-10-12 起）」「（见 design §3.2）」；`buildUserPrompt` 的「此前…裸传…现在裹上」改成现状陈述 | — |
| 17 | `provider/llm/MessageHistory.java` | 删「不是 design 决策 8 要推迟的那套骨架…」整句（禁令 6：读者不会去做那件事）；删「包级可见：只有 …/llm/ 下的两个 worker 用它」（禁令 7）。**「同一判据只有一份 → directory-structure.md:399」是真 spec 指针，留** | — |
| 18 | `provider/llm/PromptDataBlock.java` | 类注释 **29 → 7 行**：删日期 + 任务号、三条 `<li>` 的黑名单论证（归档 design 决策 5 有全文）、「与 SubtitleKeywords 紧凑构造器是同一条变换」跨类类比（禁令 1）、「包私有而非 shared/」落位说明（→ #5） | 归档 design（已有）/ package-info |
| 19 | `provider/llm/termlookup/dashscope/DashScopeTermLookupProperties.java` | 删 `model` 的选型依据整段（归档 `09-29-subtitle-term-official-lookup/design.md` §选型 有全文）；`baseUrl` / `searchStrategy` / `temperature` 的负面约束与 why **全留** | 归档 design（已有） |
| 20 | `…/termlookup/dashscope/DashScopeTermLookupProvider.java` | 删「（09-29 任务定）」、构造器样板、4 处「包级可见：单测…」；**合并 `record ChatRequest` 上重复挂着的两个 Javadoc 块**（真腐坏）；两条「实测（2026-09-29，…）」去日期；`enableThinking` 的 why 留 | — |
| 21 | `provider/asr/dashscope/DashScopeAsrProvider.java` | 删「让**设计决策 5**（空转写是合法产出）在云端可达」的决策出处；删构造器样板 ×2；删「实测（**research/**asr-cloud-behavior.md §四）」；删 2 处「包级…测试」；`TemporaryFileUploader` 去「以便单元测试注入假实现」。**`docs/prd/local-model-deployment.md` 是随仓库走的文档，指针留** | — |
| 22 | `provider/audio/ffmpeg/FfmpegAudioExtractor.java` | 删构造器样板；删 **7 处**「（研究 §X）」「（design §X）」；`muxerFor` 去「（ffmpeg 9.0.2-essentials_build，2026-09-25）」；`buildCommand` 删命令顺序流水账（§二：方法注释不写流水账）。**选轨理由、ipod brand 实测、失败消息分流全留** | — |
| 23 | `provider/download/local/LocalFileMediaAcquirer.java` | 删「（**MVP-1** 上传落盘后的…）」；删「两个下载器同时驻留容器，故**不加** `@ConditionalOnProperty`」（`provider-pattern.md` §2b 有判据）；构造器样板；**2b → 白话**；删 `parseDurationMs` 的「包级 static：测试…」 | spec（已有） |
| 24 | `provider/download/ytdlp/YtDlpMediaAcquirer.java` | 删 **4 处**「研究 §X」指针、**8 处**「包级 static：可直测」、类注释的「（见 design §三 决策 2）」与 @ConditionalOnProperty 句、`FORMATS_MISSING_MARKER` 的「**实现期实测修正**／与 implement.md 的假设不同」叙事（改客观陈述）、`--no-progress` 旁的「将来做 SSE 进度时…」**失效预言**。**实测结论、bot/URL/画质降级三条子串、info json 不含路径字段的判据全留** | — |
| 25 | `provider/download/ytdlp/YtDlpRunner.java` | **删「2b 拉平台字幕时复用同一入口…」整段**（失效预言：全仓无 `--write-subs`）；构造器样板；删「（design §八 的消息形状是…）」保留「同源」理由 | — |
| 26 | `fs/NioFileStateReader.java` | 类注释重写（原「文件状态阅读实现」= 复述类名 + 无句号） | — |
| 27 | `fs/NioWorkspaceDirectoryManager.java` | 类注释重写（原「工作区目录管理器 的实现类」）；`getWorkspaceRoot` 删复述式 Javadoc；`ensureCreated` 删三行「做什么」 | — |
| 28 | `concurrency/SemaphoreTaskConcurrencyGate.java` | `submit` 首行补句号。**类注释的跨类契约（放许可 / onQueueInterrupted）全留** | — |

**改动面**：`backend/src/main/java/com/vidsage/infrastructure/` **26 文件改 + 1 文件新增**，`+102 −250`（新增的 package-info 14 行另计）。
另有 spec 1 份（+1 行）、任务卡 6 份（父 `design.md` + 4 份子任务 prd + 本任务 prd 头部）。

## 2. 例外登记表（16 行及以上的块）

> 判据是「这块是否只回答那四件事 / 是不是真承载知识」，行数只作粗筛。**不接受靠拆行凑数**。
> 收口后 ≥16 行共 **11 块**，逐块复核如下：

| 文件 | 行数 | 为什么砍不动 |
|---|---|---|
| `package-info.java`（根） | 21 | **package-info 豁免**（正典 §三明文）。承载两类居民 + 5 个分包 + 与 `shared` 的分界 + 依赖规则，**一字未动** |
| `provider/llm/LlmSubtitleTranslator.java`（类） | 23 | 「不像清洗那样设相似度守卫」是**反直觉的**（同一个项目里两个 worker 判据相反，不写必被「统一」掉）；「刻意各自独立、不抽公共骨架」防的是真实的抽取冲动；后窗/历史两条路的区别是翻译一致性的载体。删任一段都会让人改错 |
| `process/ExternalProcessRunner.java`（类） | 24 | 六条关键行为**逐条都是实测得来的坑**（不 shell / 并发读尽 / native.encoding / 先杀后代 / 有界等待 / 非零不抛），prd 明文的「别当噪音删」；「已知局限」是刻意不假装解决 |
| `provider/llm/LlmSubtitleContextSummarizer.java`（类） | 18 | 「摘要只是增益不是前置条件」定的是失败语义（降级而非中断）；「只碰计算不碰 I/O」划的是与 service 层的边界 |
| `provider/llm/LlmSubtitleCleanser.java`（类） | 16 | 批/相似度双判据 + 后窗/历史两路；进度段已压成一行指针 |
| `provider/llm/LlmSubtitleCleanser.java`（`validate`） | 19 | 两个硬约束 + 「取较大值」的两个反例（只比原文会误判术语替换、只比基线会误判保留原文）+ 分类为何要结构化。**正是正典 §三 点名的「守卫」** |
| `provider/llm/LlmSubtitleCleanser.java`（`agentLoop`） | 18 | 「两条时间尺度取舍相反」是一条**改动时极易踩反**的不变量（批内留失败往返、跨批不留） |
| `provider/download/ytdlp/YtDlpMediaAcquirer.java`（类） | 18 | 四条都是实测固定的下载配方（模板 / info json 缺路径字段 / 不按退出码分类 / WARNING 转 WARN） |
| `provider/llm/LlmSubtitleTranslator.java`（`agentLoop`） | 18 | 同 #6 的翻译版；参数语义也在这块 |
| `provider/asr/dashscope/DashScopeAsrProvider.java`（类） | 17 | 两个音频来源的两条路 + 「SDK 类型只在实现内部」的边界；`<ul>` 已是压过一轮的形状 |
| `provider/llm/termlookup/dashscope/DashScopeTermLookupProvider.java`（类） | 16 | 「为什么裸调兼容端点」是**防人来改成 ChatClient** 的（改了搜索会静默失效）+ 接地口径 |
| `…/DashScopeTermLookupProperties.java`（类） | 15 | `baseUrl` 的「必须自拼 JSON」与 `temperature` 的「确定性抽取」各是一条防误改的 why；删了会有人改回默认温度或换 SDK |

> **收口后复核（2026-10-13，用户审查时手改）**：用户又删了一批他认为属废话的段（LLM 三个 worker 与
> persistence 两处类注释里的「实现领域端口 X——上层只认那个接口，不认识本类」一类），**按用户要求不逐条登记**
> ——行数已按手改后的实测值更新（Cleanser 类 19→16、Translator 类 25→23、Summarizer 类 20→18）。
> **该改动是用户做的，本任务不还原。**

> `LlmSubtitleCleanser` 的三块 + 两个 worker 的类注释**加起来**才是完整的守卫体系说明——这层的信息密度是
> 先天如此（外部工具与 LLM 的行为都不可从代码推出），不是注水。

## 3. 待订正清单（本层，已逐条订正）

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| 1 | `persistence/package-info.java:2-6` | **失实**：只列 `VideoTask*` 三件套，漏了同包的 `SubtitleKeywordEntity` / `SubtitleKeywordMapper` / `MybatisSubtitleKeywordRepository` | ✅ 已补全 |
| 2 | `YtDlpRunner:22` | **失效预言**：写「2b 拉平台字幕时**将**复用同一入口」——全仓 grep 无 `--skip-download` / `--write-subs`，该功能未落地 | ✅ 整段删（禁令 6） |
| 3 | `LocalFileMediaAcquirer:121` | 「workDir 留给 **2b** 落字幕产物用，本阶段不需要」——遗留管线阶段编号 | ✅ 改白话 |
| 4 | `LlmSubtitleCleanser.clean(…, listener)` | **残句**：「发一条批级进度\n进度 = 批序号…）」，缺左括号 | ✅ 整段上移 spec（见 §1 #14） |
| 5 | `LlmSubtitleCleanser.emitBatchProgress` | **残句**：「（stage 固定 {state 为 RUNNING）」 | ✅ 订正 |
| 6 | `DashScopeTermLookupProvider`（`record ChatRequest`） | **两个 Javadoc 块叠在一起**（前一个是无正文的残留副本） | ✅ 合并 |
| 7 | `LlmSubtitleTranslator:buildUserPrompt` | 「（**09-28 实证**：…）」——正典 §六 自己记的**漏检短任务号** | ✅ 去任务号，实测结论留 |
| 8 | `YtDlpMediaAcquirer:225` | 「**将来**做 SSE 进度时改用 `--progress-template`」——失效预言（SSE 进度早已落地） | ✅ 改写为现状 |
| 9 | `LlmSubtitleCleanser.termMetrics` | 「——**本次故障**正是静默漏报造成的」——事故叙事 | ✅ 改写为非时间性表述 |
| 10 | `fs/NioFileStateReader`、`fs/NioWorkspaceDirectoryManager` | 类注释是「XX 的实现」复述类名 + 无句号 | ✅ 重写 |
| 11 | 父 `design.md:12-13` | §1 章节目录列到 §六，漏 **§七 元规则**（正典自称「七章地图」）——已漂移的极短副本 | ✅ 补 §七 |
| 12 | 父 `design.md:58` | 验证策略表「**上表模式** `grep`」——「上表」指向 §1 里**已删**的模式表，悬空指针 | ✅ 改为「正典 §六 的模式清单」 |
| 13 | 子任务 3–6 的 prd 头部 | 「本层的 `implement.md` 在**门 G1 通过**…之后才写」——G1 已通过，失效描述 | ✅ 4 份均订正为现状 |
| 14 | `DashScopeAsrProvider:116` | **代码问题**：`String reason = …` 声明后未使用（IDE 报 unused） | ⚠️ **登记不改**——本任务边界是「diff 里不许出现非注释行」；建议另开小任务或随手清理 |

## 4. 标准补充（本层对正典的反馈）

| # | 遇到的情形 | 正典怎么说 | 实际怎么定 | 建议回写正典？ |
|---|---|---|---|---|
| 1 | **「包级可见 / 包级 static：供测试…」这一族**（本层 17 处，句式各异：「可直测」「便于测试注入假实现」「无需 Spring 容器即可直接调用」） | 禁令 3 只点名了「带 listener 的便捷重载**留给测试用**」这一种 | **一律删**（与禁令 3 同类、与禁令 7「测试在哪里」也同类） | 建议：禁令 3 补一句「凡以『给测试用 / 可直测 / 便于注入假实现』为理由的可见性说明，同属本条」 |
| 2 | **构件内部的进度事件序列**再次外溢（`cleanser` 自报的批级进度） | §二 把进度事件序列归 spec | **按 spec 处理**，且为「清单为空时不发批级事件」这一格补写了 `directory-structure.md` 的表格（application 层已就该条提过一次） | **第二次撞到同一缝**，建议采纳 application §4 #1 的修订 |
| 3 | **私有方法的「命令行顺序清单」**该不该留 | §三 私有方法「只写为什么，不写做什么」；§二 把「流程步骤」归 spec | 本层**删**了 `buildCommand` 的命令顺序流水账（签名与实现两行内就能读完） | 建议：§二 的「流程步骤」明确包含「命令 / 参数清单」 |
| 4 | **事故叙事**（「本次故障正是…」） | §六 模式清单拦「日期 / 任务号 / 迁移前…」，但**拦不住**「本次故障」「上一次」这类 | 改写为非时间性表述（保留 why） | 建议：§六 已知缺口补一条「事故 / 故障叙事」 |
| 5 | **`docs/` 下的文档指针算不算归档品** | §五 表只列了 `design.md` / `research/` / `oss-references` / 任务号 / 日期 | 判为**随仓库走**（`docs/prd/local-model-deployment.md` 在仓库里），**保留** | 建议：§五 表把「`docs/**` 等随仓库提交的文档」并入保留行 |
| 6 | 归档 design.md 里有**逐字同源的全文**时怎么办（模型选型） | §二「上移优先于删除」 | 直接删本地副本、**不写指针**（§五：归档指针是负资产） | 已按正典办，无需回写 |

## 5. 自检（实测值）

```bash
# 只改注释？（应只有注释行）
cd f:/personal_project/vidSage && git diff -w --stat
#   → infrastructure/ 26 files changed, +102 −250；非注释行 **0**
# 本层变更史归零（模式清单照抄正典 §六，另加了 `design §` 一条试）
grep -rnE '20[0-9]{2}-[0-9]{2}-[0-9]{2}|[0-9]{2}-[0-9]{2}-[a-z][a-z-]{4,}|设计决策|design 决策|design\.md|design §|research/|oss-references|迁移前|字节一致|旧守卫' \
  backend/src/main/java/com/vidsage/infrastructure/          # → 命中 0
# 自然语言变体人眼扫一遍（正典 §六 已知缺口：原为/原先/此前…）
grep -rnE '原为|原先|此前|以前|曾经|已废弃|已被|本阶段|将来' …   # → 逐条判过，见 §3
# 长尾收敛（115 块 = 6–11 行 87 + 12–15 行 17 + ≥16 行 11，全部人工读过）
# 行为不变（先清陈旧报告，避免统计夹杂旧值）
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
#   → tests=570 errors=0 skipped=1 failures=0
```

**外部工具坑位未被误删（抽查 AC）**：`ExternalProcessRunner` 六条关键行为 + 「已知局限」全在；
`YtDlpMediaAcquirer` 的 bot / 非法 URL / 画质降级三条子串与「info json 不含路径字段」的判据全在；
`FfmpegAudioExtractor` 的选轨理由、`ipod` brand、失败消息分流全在。**删掉的只有出处与「给测试用」的说明。**

## 6. 门 G3 · 报审查（**2026-10-13 待裁**）

报审按 ① 改动文件清单（含集外扩入及理由）② 例外登记（§2）③ 待订正清单（§3）
④ 标准补充（§4）⑤ `git diff -w` 摘要 五段输出，**停下等裁定**，不自行提交。

## 7. 分支

`feature/comment-infrastructure`（从 `dev` 开，已 `set-branch`）；改完等审查 → 提交 → 合并回 `dev` → 删分支 → 归档。
