# web / shared / config 层 · 执行计划

> 父 `implement.md` §1 的标准动作（A→E）全程适用，本文件只补**本层的清单与门**。
> **粒度标准 = `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令）**，此处不重复一字。
> 父 `prd.md` 的「不做」四条同样适用（尤其「不动 `@Schema` 落位」「diff 里不许出现非注释行」）。
>
> **状态：planning（2026-10-13 建）。** §0.1 的三条筛选线已实测（只读）；§1–§3 的表随执行填，收口后按实测校正。

## 0. 执行顺序

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | **已裁定（2026-10-13）：不新建** `web/`、`config/` 的 `package-info.java`（理由见 §0.1 末） | ✅ |
| 1 | 目的地就绪：复核 `shared/package-info.java`（14 行）有没有漏构件 | ✅ 只删「（原 utils/ 改名）」一处，构件清单完整 |
| 2 | `web/dto/` 5 类——`@param`×`@Schema` 最密的一处 + 本层唯一已知残句 | ✅ |
| 3 | `web/controller/` 3 类 + `web/sse/` 1 类——负向契约密集区（错误映射 / 心跳 / 订阅边界） | ✅ |
| 4 | `shared/` 命中 5 类（`progress` / `fs` / `subtitle`） | ✅ 另动 `TextSimilarity` / `TextTail` / `package-info` |
| 5 | `config/` 命中 3 类——校准依据上移、坑保留 | ✅ 两个 Properties 一字未动（无可上移内容） |
| 6 | **全层块全量复核**：≥6 行全部 dump 逐块读过（56 块 = 40 + 9 + 7，见 §0.2 教训 1） | ✅ 23 个文件全文读完 |
| 7 | 自检（§5）+ 报审查（§6） | **门 G4 待裁** |

## 0.1 工作集（2026-10-13 实测）

三包 **23 文件 / 83 个 Javadoc 块**（1–5 行 27、6–11 行 40、12–15 行 9、≥16 行 7）。
三条筛选线**并集 14 文件**（web 6 / shared 5 / config 3）：

| 线 | 命中 | 文件（行号） |
|---|---|---|
| ① ≥16 行块（粗筛信号） | 7 块 / 6 文件 | `web/dto/TaskResponse:8`(20)、`shared/fs/ArtifactFiles:11`(19)、`:118`(17)、`shared/subtitle/SubtitleSegmentSplitter:65`(16)、`shared/subtitle/TermMatcher:9`(19)、`config/SubtitleCleanupProperties:5`(22)、`config/SubtitleTranslateProperties:6`(24) |
| ② 变更史 / 归档指针（模式清单照抄正典 §六） | 15 行 / 9 文件 | `web/controller/ApiExceptionHandler:64`、`MediaController:17`、`TaskController:28,32,59,162`、`web/dto/TaskArtifactResponse:7`、`TaskResponse:19`、`web/sse/TaskSseConnectionManager:25,46`、`shared/progress/ProgressMapper:10,11`、`shared/subtitle/SrtRenderer:22,35`、`config/ChatClientConfig:102` |
| ③ 层级错位（父 Step A 第 3 批） | 3 行 / 2 文件 | `web/sse/TaskSseConnectionManager:160`（「给测试用」）、`shared/subtitle/SrtRenderer:22`（与线②同一行）、`SrtRenderer:56`（跨类类比） |

**未命中三条线的 9 文件**：`web/dto/` 的 `SubmitUrlRequest` / `RegenerateSubtitlesRequest` / `SubtitleKeywordsResponse` / `TaskConflictResponse`、`shared/subtitle/TextSimilarity`、`shared/text/TextTail`、`config/TaskProperties` / `WorkspaceProperties`，以及 `shared/package-info.java`（阶段 1 的目的地，不是「不碰」）。

两条要单独交代：

- **`config/TaskProperties` 与 `WorkspaceProperties` 在阶段 6 专门看一眼**——`prd.md` 的层内重点写的是「四个 `@ConfigurationProperties`」，这两条没被机械线捞到，别漏。
- **未命中 ≠ 干净**：`TextTail:19`、`TextSimilarity:11`、`WorkspaceProperties:12`、`ArtifactFiles:20` 都带 spec 指针，阶段 6 要确认没被顺手删掉（指针清单见 §0.3 末）。

**阶段 6 的人眼补扫**：`原为 / 原先 / 此前 / 曾经 / 不再 / 将来 / 本次事故` 这类自然语言变体，正典 §六 已知缺口写明机械线拦不住。本层已实测出种子，见 §3。

**阶段 0 已裁定（2026-10-13）· 不新建**：本层唯一的 `package-info.java` 在 `shared/`（14 行）；`web/` 与 `config/` **都没有**（全仓 21 份，`domain` / `application` / `infrastructure` 的各个子包都有）。**不补**的理由是目的地已经存在——`web/` 的包级内容（`dto` / `sse` / `controller` 的分工、`SseEmitter` 只活 `sse/`、异常映射的边界）在 `directory-structure.md:56/76/114` 与 `error-handling.md:157` 都有归宿，`config/` 更薄；与 infra 层「只补一份（那一份是代码里无处可去的）」同一判据。阶段 6 若真撞上无处可放且值得留的内容，再登记请示。

## 0.2 从上三层继承的教训（三份 `implement.md` 均已归档）

1. **机械线漏整档**：`>15` 会放过 12–15 的噪音块 → 本层把 **≥6 行全部 dump 逐块读**（56 块）。
2. **成稿要当整体读一遍**：逐条对着规则打勾 ≠ 做完。
3. **上移优先于删除**：搬得走的先搬，再从代码里删本地副本。
4. **不代写**：缺注释、原因又推不出来的，登记进 §3，不自己编一句（假注释比没注释糟）。
5. **只改注释**：diff 里不出现非注释行（infra 层中途误删过一个 `@Mapper` 注解，当场还原）。
6. **报审查时 §4（标准补充）比 diff 更值得看**。
7. **两类不单独立项扫**：`@param` 纯复述参数名**暂不动**；标点硬伤**跟随改动**顺手修。
8. **用户审查时会自己动手删**他认为属废话的段；**删掉的不登记、不还原**（照 application 层 `implement.md` §2「收口后复核」的写法留一行说明即可）。

## 0.3 本层已知重点与预判

| 位置 | 预判 |
|---|---|
| `web/dto/TaskResponse.java:45-47` | **必订正**：`from(...)` 的 Javadoc 是断句残句（停在「产物清单逐元素映射为」）。父计划 §3 #1 登记的外溢，归本层 |
| `web/dto/TaskResponse.java:8`（20 行）+ `:19` | 本层最长的块：类注释按四件事复核；`@param translationStatus` 的「（2026-10-02 两段式）」是变更史 |
| `web/dto/*` 的 `@param` × `@Schema` 重复面（`TaskResponse` 12 字段、`TaskArtifactResponse` 3、`SubtitleKeywordsResponse` 3、`SubmitUrlRequest` 2、`TaskConflictResponse` 2、`RegenerateSubtitlesRequest` 1） | 父 `prd.md`「不做」：**不主动删任一侧**，只在两份文案已经不一致或腐坏时订正——本层 AC 是「面没被扩大 + 已发现的不一致已订正」 |
| `web/controller/ApiExceptionHandler`（104 行 / 55 注释） | 异常 → HTTP 状态的映射属**负向契约 → 留**；「为什么不提前造异常体系」属决策，`error-handling.md:62` 与 `:175` 有全文，**删本地副本零损失**；`:20`「因此把**原先**…」与 `:64`「（2026-10-12 起）」是变更史 |
| `web/controller/TaskController:32` | 一句话里两种指针：`design.md` 任务表 → 删；`logging-guidelines` 脱敏红线 → 留。同款混装在 `MediaController:17`（`research/` 归档指针 → 删） |
| `web/sse/TaskSseConnectionManager.java:46` | **正典点名的实例**：正典 §三「常量注释」条原文就写「可写『30s 是防代理空闲断连的常规值』，不写『（design.md「SSE 契约」）』」——即删出处、留 why |
| 同文件 `:25`「心跳（design.md「SSE 契约」）」 | 出处删。全文在归档 `09-24-vidsage-mvp1-submit/design.md:98`「## SSE 契约」；`directory-structure.md:56` 已列 `web/sse/` 职责（心跳在内） |
| 同文件 `:160`「给测试用——…」 | 禁令 3 / 禁令 7 → 删测试用途说明（即 infra 层那族「供测试…」，本层此一处） |
| `shared/subtitle/SrtRenderer.java:35` | 「（design 决策 7：自己写，不引库）」→ 决策出处删；全文在归档 `09-25-vidsage-mvp2b-subtitles/design.md:173` §决策 7 |
| 同文件 `:22` | ① `research/asr-cloud-behavior.md` 指针 → 删（归档品）；② 负值钳零的**防御理由留**；`:56`「同 `toSrt` 对 `null` 文本的处理」= 禁令 1 跨类类比 → 改为自洽表述 |
| `shared/subtitle/TermMatcher`（54.8%，19 行类注释） | `hitTermsByAsr` 是 **spec 点名的单源**（`backend/quality-guidelines.md:150`）→ 判据描述留；`:17,:54,:55` 的「已被替换 / 不再被选中」逐个判（多为现状语义，不是变更史）；`:22`「翻译是第四个使用者」= 禁令 7 的索引 |
| `shared/subtitle/SubtitleSegmentSplitter`（40.9%，16 行块） | 注释密度高但无机械线命中——纯靠阶段 6 人读 |
| `shared/fs/ArtifactFiles.java:16` | 「它**不再是**「零依赖字节助手」」= 否定式快照（禁令 2）→ 改写为职责边界；`:20` 的 `directory-structure.md` 指针留，但「（原 utils/）」是变更史 → 删 |
| `shared/progress/ProgressMapper.java:10-11` | 「从 `SubtitlePreparationCoordinator` 的私有 `remap` 上提（2026-09-30，MVP-1）」整段变更史 → 删；「翻译事件不再经过映射」是现状口径 → 压缩留 |
| `config/` 的四个 `@ConfigurationProperties` | `@param` 里的**校准依据**（为什么是这个量级）→ `design.md`；「缺席时绑定成 0 = 静默关闭，yml 里必须显式写」这类**坑** → 留。`SubtitleTranslateProperties:10`「合并会让将来只调一边的人误改另一边」防的是真误改 → 判为留 |
| `config/ChatClientConfig.java:102` | 「design §3.1 已定…」→ 章节号指针删（任务卡归档后章节号会漂） |

**spec 指针（留，别误删）**：`ApiExceptionHandler:25`（`logging-guidelines.md` 的反模式）、`TaskController:32`（`logging-guidelines` 那半句）、`web/dto/TaskArtifactResponse:8` 与 `shared/fs/ArtifactFiles:20` 与 `shared/subtitle/TextSimilarity:11` 与 `config/WorkspaceProperties:12`（`directory-structure.md`）、`shared/text/TextTail:19`（`error-handling` 判空条）。已逐个核过：这些章节在 spec 里都存在（如 `directory-structure.md:355`「契约类的位置」、`error-handling.md:64`「字符串判空」）。

> `ApiExceptionHandler:18` 的 `error-handling.md` 指针**不单独回补**：它原长在「为什么现在才提取」那段变更史里，随整段一起删；那份知识在 `error-handling.md:62/154/175` 全量存在（属正典 §二「上移优先于删除」）。本条经独立质检指出后修正——原稿把 `:18` 也列进了白名单，与实际 diff 不符。

## 1. 逐文件登记表（收口后）

| # | 文件 | 命中线 | 处置 | 上移去向 |
|---|---|---|---|---|
| 1 | `web/dto/TaskResponse.java` | ①② | 删「（2026-10-02 两段式）」；`sourceUri` 按禁令 12 改显式约束（与 `TaskSnapshot` 同款）；类首行补句号、合并双空行；`from(...)` 残句补齐 | — |
| 2 | `web/dto/TaskArtifactResponse.java` | ② | 删「（2026-10-06 读模型拆分」日期——顺带修好它**没闭合的括号**；保留 spec 指针判据 2 | — |
| 3 | `web/dto/SubmitUrlRequest.java` | — | **一字未动**：`@param` 的缺席语义与负面约束本就在，两份文案一致 | — |
| 4 | `web/dto/RegenerateSubtitlesRequest.java` | — | 删「（只有 keywords 一个字段）」当前实现快照（禁令 2）。**「写在 @Operation 里给前端看」保留**——已核实 `TaskController:199` 真写着同一语义，不是失实 | — |
| 5 | `web/dto/SubtitleKeywordsResponse.java` | — | `from` 的「与 `TaskResponse.from` 同一分工」跨类类比（禁令 1）改自洽；删复述式 `@return 对外响应`（正典 §四 TS 例） | — |
| 6 | `web/dto/TaskConflictResponse.java` | — | 合并双空行；正文一字未改 | — |
| 7 | `web/controller/ApiExceptionHandler.java` | ② | 删「为什么现在才提取」整段（变更史 + 决策）；类首行不再枚举状态码（原写「400 / 500」漏了 404 / 409）；`handleTaskNotFound` 删「引入时机见…」与三端点清单，留 404 vs 400 的判据；`handleTaskConflict` 删「（2026-10-12 起）」与链式指针 | `error-handling.md`（已有全文） |
| 8 | `web/controller/MediaController.java` | ② | 删「原『临时面』…收编删除（见 `research/…`）」整段（变更史 + 归档指针）；删构造器样板（含「实现在 `infrastructure/process/`」） | — |
| 9 | `web/controller/TaskController.java` | ② | 类注释删「（MVP-1 异步管线的唯一前端依赖）」「（2026-10-02 两段式裁定）」与 `design.md` 指针（**留** `logging-guidelines` 脱敏红线那半句）；删构造器样板；两处方法注释去日期；「长视频不再顶超时」改「不会顶」 | — |
| 10 | `web/sse/TaskSseConnectionManager.java` | ②③ | 删心跳常量与类注释的 `design.md「SSE 契约」`指针（正典 §三 常量条点名的就是这一行）；删「（原顶层包 subscribe/）」；`emitterCount` / `hasBucket` 的「给测试用…」说明整块删（①）；`sendTo` 的「做什么」块与两条叙述式行内注释删；`beat` 留 why；合并两处双空行 | — |
| 11 | `shared/package-info.java` | 目的地 | 删「（原 utils/ 改名）」 | — |
| 12 | `shared/progress/ProgressMapper.java` | ② | 删整段变更史（「从 … 上提（2026-09-30，MVP-1）…」）；类注释不再复述 `shared/` 准入判据（package-info 已有）；删私有构造样板注释 | — |
| 13 | `shared/fs/ArtifactFiles.java` | ① | 「它**不再是**『零依赖字节助手』」改写成职责边界（禁令 2）；删「（原 utils/）」与复述准入判据那句；`readJsonOrNull` 删与两个 Coordinator「同一条语义」的跨类指涉，负向约束改为不点名的表述 | — |
| 14 | `shared/subtitle/SrtRenderer.java` | ②③ | 删 `research/asr-cloud-behavior.md` 指针；删「（design 决策 7：自己写，不引库）」；`toBilingualSrt` 的「同 `toSrt` 对 `null` 的处理」跨类类比改自洽 | 归档 `09-25-…mvp2b-subtitles/design.md:173`（已有） |
| 15 | `shared/subtitle/TermMatcher.java` | ① | 类注释删「三处共用 / 翻译是第四个使用者」的索引与计数、删与 `TextSimilarity` 的类比与 infrastructure 指涉、删私有构造的重复行内注释；`hit` 删步骤流水账（③）。**`hitTermsByAsr` / `hitTermsByFixed` 的判据描述全留**（spec 点名的单源） | `directory-structure.md:399`（已有） |
| 16 | `shared/subtitle/SubtitleSegmentSplitter.java` | ① | `BREAK_CHARS` 的残句整句删（见 §3 #8）；`splitLongSegment` 的 `<ul>` 步骤流水账删（③，类注释已述同一算法）；循环内「做什么」行内注释删 | — |
| 17 | `shared/subtitle/TextSimilarity.java` | — | 「唯一消费者是 `SubtitleCleanser`」索引删，**留**「别提前造 util 桶」的 why 与 spec 指针；私有构造重复行内注释删 | — |
| 18 | `shared/text/TextTail.java` | — | 方法首行对类注释的复述删（禁令 10）；spec 指针与判空 why 全留 | — |
| 19 | `config/ChatClientConfig.java` | ② | 删「design §3.1 已定…」（该口径 `SubtitleTranslateProperties#targetLanguage` 已述）；三个 `@Bean` 的空 `@param` / `@return` 与复述标签删；`/***` 笔误改正；类后双空行合并 | — |
| 20 | `config/SubtitleCleanupProperties.java` | ① | **一字未动**：逐条核过，全是字段语义 + 「缺席绑定成 0 = 静默关闭」这类坑，**没有可上移的校准依据** | — |
| 21 | `config/SubtitleTranslateProperties.java` | ① | **一字未动**：同上；类注释「故意分成两个类」是防合并的 why，留 | — |
| 22 | `config/TaskProperties.java` | —（阶段 6 专门看） | 删「（MVP-1）」；`@param` 里的实现类引用（`SemaphoreTaskConcurrencyGate`）删（禁令 3） | — |
| 23 | `config/WorkspaceProperties.java` | —（阶段 6 专门看） | 删「（配置块在后续步骤才补进 yml）」；「由它带出」的变更史口吻改现状；spec 指针留 | — |

**改动面**：三包 **20 个 .java 改**（23 个里的 20 个），收口后 `+54 −159`（含门 G4 审查回合的手改，见 §3 末）；未动的 3 个是 `SubmitUrlRequest`、`SubtitleCleanupProperties`、`SubtitleTranslateProperties`。

## 2. 例外登记表（**4 块 ≥16 行**，逐块复核）

> 判据是「这块是否只回答那四件事 / 是不是真承载知识」，行数只作粗筛，**不接受靠拆行凑数**。
> 收口前 ≥16 行共 7 块（83 块里的），收口后 **4 块**（77 块里的）——`ArtifactFiles:118`（我的阶段 2/4）、
> `SubtitleSegmentSplitter:65`（我的阶段 6）已压到线下，`TermMatcher` 类注释经审查回合手改后 17 → 15
> 行，也就地离开本表（它的判据描述仍在，只是不再超线）。

| 文件 | 块位置 | 行数 | 为什么砍不动 |
|---|---|---|---|
| `config/SubtitleTranslateProperties.java` | 类注释 `:6` | 24 | 一条类级 why（「故意分成两个类」防的是合并后只调一边的人误改另一边）+ 4 个 `@param`，每个都带「缺席时绑定成 0 → 静默漂移，yml 必须显式写」的坑。删任一条都会让人漏配 |
| `config/SubtitleCleanupProperties.java` | 类注释 `:5` | 22 | 8 个 `@param`：阈值语义之外还有 `logRejectedText`「只在 application-local.yml 里开」的**结构性保证**，以及两个字段「缺席默认值方向相反」的对照说明——都是配置时必需的 |
| `web/dto/TaskResponse.java` | 类注释 `:8` | 20 | 1 行摘要 + 1 行「SSE 快照是另一个形状」的边界 + 11 个 `@param`。**这是 record 组件契约，砍不动的是参数语义**（`null` 含义、产物清单的边界），不是叙述 |
| `shared/fs/ArtifactFiles.java` | 类注释 `:11` | 16 | 三段各防一件事：JSON 依赖为何合法、**两套原子落盘为何不合并**（合了丢 `ATOMIC_MOVE` 语义）、落盘为何全归口本类。删任一段都会有人去「整理」 |

**12–15 行档共 9 块**（`ApiExceptionHandler:63`、`TaskController` 两处、`TaskSseConnectionManager`、`ArtifactFiles` 两处、`shared/package-info`、`TermMatcher`、`WorkspaceProperties`）**已逐块读过**，按 §0.2 教训 1 的标准一起看——没有 13–15 行档的纯噪音块；`shared/package-info`（13 行）是包级地图，正典明文豁免。

## 3. 待订正清单（已逐条订正）

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| 1 | `web/dto/TaskResponse.java:45-47` | **残句**：「字段一一对应，产物清单逐元素映射为」后无下文（父计划 §3 #1 的外溢） | ✅ 补成「…逐元素映射。」 |
| 2 | `web/dto/*` 6 文件 | `@param` × `@Schema` 文案重复 | ✅ **未动**（父 `prd.md`「不做」）；逐条核过，**没发现不一致或腐坏的面，面也没被扩大** |
| 3 | `web/controller/ApiExceptionHandler.java:20` | 「因此把**原先**…」变更史叙事 | ✅ 整段删（`error-handling.md:3` 有同源全文） |
| 4 | `shared/fs/ArtifactFiles.java:16` | 「它**不再是**『零依赖字节助手』」否定式快照 | ✅ 改写为职责边界（禁令 2） |
| 5 | `shared/progress/ProgressMapper.java:10` | 「上提（**2026-09-30**，MVP-1）」 | ✅ 整段删 |
| 6 | `TextSimilarity:11` / `TermMatcher:22` / `SubtitleTranslateProperties:10` | 「将来 / 谁将来真要用」 | ✅ 逐条判为**防误改的 why**（不是预言），全留 |
| 7 | `web/controller/MediaController.java:17` | 「收编删除（见本任务 `research/acceptance-evidence.md`）」 | ✅ 整段删（变更史 + 归档指针） |
| 8 | `shared/subtitle/SubtitleSegmentSplitter.java:20-22` | **残句/失实**：`BREAK_CHARS` 注释写「句号「。」不在这里单列…**它和「。」同属断点家族**」——主语自指，且常量里「。」出现了两次 | ✅ 整句删。**原意不可复原**（推测原文讲的是英文句点「.」，但无从证实）——按「不代写」原则没补新理由，**留待你裁定要不要补** |
| 9 | `web/dto/TaskArtifactResponse.java:7-9` | 类注释的括号**没闭合**（`（2026-10-06 …` 一路没 `）`） | ✅ 订正时一并修好 |
| 10 | `config/ChatClientConfig.java:60` | `/***`（三颗星）笔误；`@param builder` / `@return` 三个 `@Bean` 全是空标签或复述类型 | ✅ 改 `/**`、空标签删 |
| 11 | `config/TaskProperties.java:7`、`WorkspaceProperties.java:10` | 「（MVP-1）」「（配置块在后续步骤才补进 yml）」——里程碑与计划残余 | ✅ 删 |
| 12 | `config/ChatClientConfig.java:18` | **代码问题**：方法名 `ContentSummarizer` 首字母大写（Java 约定是 `contentSummarizer`） | ⚠️ **登记不改**——本任务边界是「diff 里不许出现非注释行」，建议另开小任务 |

### 审查回合追加（门 G4 后，2026-10-13）

用户在审稿时**手删了一批他认为属废话的段**（按既定口径**不逐条登记、不还原**）。删的是同一族——**「解释既成安排」的自我辩护**：落位与命名依据（「按 `directory-structure.md` 判据 2…」）、可见性辩护（「为什么是 public」）、反事实推演（「不归口的话…会在每个 service 各抄一遍」）、机制推论（「没有该配置块也能加载 Spring 上下文」）、以及 `TaskController` 里**已在 DTO 字段上有一份**的日志约束。

**判据一句话**：读者读完**不会改变接下来要做什么**的段 → 删；**防后来者做错决定**的段（「别合并两套原子落盘」「别换 `@Scheduled`」「为什么有两个入口」）→ 留。后者他一条没动。

**按同一判据我又扫了一遍三包**，改了 6 处：

| # | 位置 | 处置 |
|---|---|---|
| 1 | `SrtRenderer#toBilingualSrt` | 删「「跳过」在 SRT 里自然可读，不需要额外标记」——「我们没做什么」 |
| 2 | `SubtitleSegmentSplitter` 类注释 | 删预言「真要语义级分句，等确定性格栅在真数据上暴露不足再上 LLM 方案」 |
| 3 | `MediaController` 类注释 | 删依赖列举「依赖 `application/`，不直接依赖 `infrastructure/` 也不依赖 `domain/`」（依赖规则是 spec 的事） |
| 4 | `TermMatcher#hit` | 「命中判定的本体，**只有一份**」——「只有一份」的依据（类里那句）已被你删，悬空 → 去掉 |
| 5 | `ArtifactFiles` 类注释 | 「，只差 JSON 这层」同因悬空 → 去掉 |
| 6 | `TaskArtifactResponse`、`TextSimilarity` | 删段留下的**注释块内裸空行**（2 处）→ 归一 |

**两处我判为值钱、建议留，等你定**：

- `SubtitleKeywordsResponse` 类注释的「**为什么返回 `fingerprint` 而不是让前端自己算**」——读者读完会改变行为（别自己算、别删这个字段）→ 建议留。
- `RegenerateSubtitlesRequest` 的「不直接复用领域类型 `SubtitleKeywords`——domain 词汇不进 web 契约」——防的是真会有的冲动（DTO 复用领域类型）→ 建议留，可缩成一句。

**其他三层**（domain / application / infrastructure）已收口归档，同样味道的段**可能还在**；本轮不动，列为可选后续。

**这把尺子已回写正典**：`.trellis/spec/guides/comment-guidelines.md` §一「一把更顺手的尺子」（会改变 → 留；解释既成安排 → 删，含五种形态）+ §一 末尾「删完回查两件事」+ §三 禁令 6 的三条指引线（点明其判据是这把尺子的特例）。本层 prd 未设「正典补充」交付项，故记在此处。

## 4. 标准补充（本层对正典的反馈）

> infra 层 §4 留了 5 条，**2026-10-13 用户逐条裁定、已回写正典**（①②③④ 五处落点见下）。本层执行时按**已补的正典**办。

| # | 待裁定项 | 裁定与落点 |
|---|---|---|
| ① | 「给测试用 / 可直测 / 便于注入假实现」族 | **删**。已写进正典 §三 禁令 3（「凡以…为由的可见性说明，同属本条」）。本层 1 处：`TaskSseConnectionManager:160` |
| ② | 构件内部步骤序列该去哪 | **采纳**。已写进正典 §二「两条边界」第 1 条：与实现逐句重复的直接删，只有跨构件共用的口径才进 spec |
| ③ | 私有方法的「命令 / 参数清单」 | **删**（流水账无用）。正典 §二 分流表该行已加「命令 / 参数清单」，§三「私有方法」条已补一句 |
| ④ | 事故 / 故障叙事 | **一律删**；唯一例外是**叙事背后的缺口至今没修**——改写成现状的「已知局限」陈述。正典 §二 新增一行 +「两条边界」第 2 条 |
| ⑤ | `docs/**` 指针算不算归档品 | **保留**，且已写进正典 §五 保留行（「`docs/**` 等随仓库提交的文档」，与归档品分开）。本层 1 处：`DashScopeAsrProvider:270`——指针落点已核实，`docs/prd/local-model-deployment.md:57` 有同一条实测记录 |
| ⑥ | （infra 已按正典办，无需回写） | — |

**本层新发现（执行中填）**：

| # | 遇到的情形 | 正典怎么说 | 实际怎么定 | 建议回写正典？ |
|---|---|---|---|---|
| | | | | |

## 5. 自检（实测值 2026-10-13）

```bash
cd f:/personal_project/vidSage && git diff -w --stat
#   → 20 files changed, +54 −159（含审查回合手改）；**非注释行 0**（逐行核过 +/- 行，全部是注释或空行）
grep -rnE "<照抄正典 §六 的模式清单>" backend/src/main/java/com/vidsage/{web,shared,config}
#   → 命中 0
grep -rnE '原为|原先|此前|曾经|已废弃|本阶段|将来|本次故障|MVP-1|不再' …
#   → 7 处全在 TermMatcher / TextSimilarity / SubtitleTranslateProperties / ApiExceptionHandler / TaskController，
#     逐条判为现状语义或防误改的 why（§3 #6），只有 TaskController 的「不再顶超时」顺手改成「不会顶」
grep -rnE '<线③ 层级错位模式串>' …
#   → 1 处：SrtRenderer「符合 SRT 对长时长的处理」——「SRT」是规范名不是类名，正常语句，**判为误报**
# 块统计：83 → 77；≥16 行 7 → 4（全进 §2）。审查回合后**重跑过**：块内裸空行 0
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
#   → tests=570 errors=0 skipped=1 failures=0
```

**行为不变**：570 / 0 / 1 / 0，与 infra 层收口后实测一致（注释改动不参与编译，这条的价值在于排除手滑改了代码——已另行逐行核过 diff，非注释行 0）。

## 6. 门 G4 · 报审查

按 ① 改动文件清单 ② 例外登记（§2）③ 待订正清单（§3）④ 标准补充（§4）⑤ `git diff -w` 摘要 五段输出，**停下等审查，不自行提交**。

**独立质检（trellis-check，2026-10-13）**：六条 AC 全 **PASS**；非注释行独立复核为 0；知识零损失抽查三处（SSE 契约 / 决策 7 / 异常体系）落点均在，该留的判据与配置坑全在；**未改任何文件**。它提的三条分歧处理如下：① `ApiExceptionHandler:18` 白名单与 diff 不符 → 已订正（见 §0.3 末）；② `BREAK_CHARS` 残句不回补理由 → 维持 §3 #8 登记；③ `TermMatcher:52`「也不再被选中」保留 → 同意。

**§4 标准补充 · 本层新发现为空**（无新类型的外溢）→ 按父计划口径，**本层不动 spec**（Phase 3.3 无产出）。

**审查回合（门 G4 后）**：用户手删了一批「解释既成安排」的段（不逐条登记）；我按同一判据追加改了 6 处、并留两处待你定（见 §3 末「审查回合追加」）。追加后重跑自检：非注释行 0、正典 §六 模式 0、注释块内裸空行 0、`mvn test` 570/0/1/0。**改动仍未提交**，等第二次审查。

## 7. 分支

执行时从当前分支 `dev` 开 `feature/comment-web-shared-config`；改完停等审查，通过后 `--no-ff` 合回 `dev`。
