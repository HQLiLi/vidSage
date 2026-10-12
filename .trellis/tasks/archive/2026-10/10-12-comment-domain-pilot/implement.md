# domain 试点 · 执行计划

> 父 `implement.md` §1 的标准动作（A→E）全程适用，本文件只补充本层的具体清单与门。
> 粒度标准 = 父 `design.md`，不在此重复。**本文件是校准轮的主要产出。**

## 0. 执行顺序

| 阶段 | 内容 | 目的 |
|---|---|---|
| 1 | 三个 `package-info.java`（subtitle / task / media） | 先把「上移的目的地」备好，后续 A 组才有地方搬 |
| 2 | A 组其余文件 | 主体收口 |
| 3 | B 组与 A 组的差集 | 纯长尾块，无史可言 |
| 4 | C 组 3 文件 | 对照：期望「基本不动」，验证标准没写歪 |
| 5 | 正典 + 两份 `quality-guidelines` 改指针 | 产出物 |
| 6 | 自检 + 报审查 | 门 G1 |

## 0.1 工作集校正（收口时实测，2026-10-12）

`prd.md` 的「19 + 3」是摸底估计；按父 `implement.md` Step A 的实测口径
（`>15 行块` ∪ `变更史标记`）得到 **26 + 3 = 29 文件**。差异如下：

| 差在哪 | 具体 |
|---|---|
| 摸底漏了 6 个 >15 行块 | `Bcp47Language.java:65`、`TranslatedTrack.java:8`、`TranslatedSubtitle.java:7`、`TranslatedSegment.java:5`、`CleanedSubtitle.java:7`、`domain/package-info.java:1` |
| 摸底漏了 2 个**中文**决策引用 | `SubtitleArtifact.java:13`、`SubtitleRoute.java:7`——写成「见设计决策 5 / 见设计决策 3」，而摸排模式只匹配英文 `design 决策`，**漏检** |

两个**误报**（含关键词但不是变更史，**不动**）：
`shared/model/StageProgress.java:11`、`shared/port/ProgressListener.java:13` 里的「分层**倒置**」是概念词。

**C 组对照面的结论**：`TranscriptionCommand` / `AudioSource` **一字未动** ✓；
但 `ASRProvider` 实际带两处决策引用（「设计决策 1 / 5」），已按实改——它本来就不该算纯对照面。

**实际改动面**：27 文件 / `+152 −250`。

## 0.2 审查第二轮：新增一类问题（层级错位）

用户审 G1 时指出 `SubtitleRoute` 的问题，并给出了**五条禁令**（已回写 `guides/comment-guidelines.md` §三，
后由 §0.3 补成**六条**：
①注释必须自洽、不依赖别类的知识；②不写实现快照、写职责边界；③实现层细节回实现层；
④事实必须与代码对得上、计数类尤其；⑤同一文件内多处表述一致）。

这暴露了**工作集口径的盲区**：原本的两条筛选线（变更史标记 ∪ >15 行块）**筛不出「层级错位」**——
一个 6 行的注释可以既无变更史、也不长，但满篇是实现快照与跨类类比。

按新禁令**在域内全线复查**后，改动面从 27 扩到 **34 个源码文件**（`+188 −288`）：
在集内改 10 个文件，另**扩入工作集外 5 个**（见 §1 的 #30–#34）——
理由是它们是本届已改句式的**逐字孪生**（`SubtitleTranslator` 与 `SubtitleCleanser` 一字不差），
留着半改不改比不改更糟。

**未改、留待用户定夺的**（见 §2 的 #7）：`TaskStatus`（重复 VideoTask 的三条迁移）、
`SubtitleStageName`（"类加载时算出来"是实现快照 + `/***` 笔误）、`AcquiredMedia`（列了两条输入路线的实现）、
`WorkspaceDir.dirName`（指向另外两个类）、以及 4 个文件里「字段清单式首行」的取舍。

## 0.3 审查第三轮：再加一条禁令

用户看 `SubtitleRoute` 成稿后又指出：**「命名强调「来源路线」而非具体实现；不另设「字幕来源」枚举，
避免同一概念出现两个真相源」这种也直接删了，没啥意义**——注意用户**在上一轮曾建议保留这一句**，
看了成稿才改判。

由此提炼出**禁令 6：不为「没发生的事」写辩护句**（已回写正典 §三）。
判据：**「你为什么不做另一件事」只有读者本来就会去做那件事时才值钱。**
`WorkspaceKey` 的「刻意不做 hex 正则校验」值钱（防人来「修」）；「不另设 X 枚举」不值钱（没人会去建 X）。

按此判据扫域内，命中 4 处，**改了 3 处**：

| # | 位置 | 原文 | 处置 |
|---|---|---|---|
| 45 | `subtitle/model/common/SubtitleRoute.java` | 「命名强调「来源路线」而非具体实现；不另设「字幕来源」枚举，避免同一概念出现两个真相源。」 | **删**（用户指定） |
| 46 | `subtitle/model/cleanup/SubtitleTrack.java` | 「…跟这份字幕睡在同一个文件里**而不是另开一份**：另开就得处理「谁先写谁后写、写到一半崩了算谁的」——那是最难测、半年后最容易回归的一类一致性。」 | **改为陈述用途**（「清洗的断点判据靠它答「现在该不该重跑」」）。原文是归档 `10-11…/design.md` 决策 1 的复制 |
| 47 | `task/VideoTask.java` `isEventTerminal` | 「翻译这一项**将来会去掉**（翻译要改成多语言选择），调用方不要只依赖主状态。」 | **删**。预言会腐化：改动真发生时它成陈迹，没发生时是噪音；且「不要只依赖主状态」当下不可执行 |
| — | `subtitle/model/term/VerifiedTerm.java` | 「与 `SubtitleContext.Term` 分成两个类型是有意的…互不掺水，没有旧缓存迁移问题。」 | **保留**。两个类型都**真实存在**、读者见到重名会自然发问；答案（哪个文件装什么）是契约信息——不属禁令 6 |

> `WorkspaceDir.dirName` 指向另外两个类，仍按 §2 #7 登记未动。

## 0.4 审查第四轮：禁令 6 扩到「负向边界句」

用户看 `SubtitleRoute` 成稿后又指出「本枚举仅定义取值域，不承载文件名、扩展名等派生逻辑」**这句也没啥用**
——**这是同一行注释的第三次改判**（第一轮用户建议写、第二轮用户建议删「不另设枚举」、第三轮删这一句）。
三轮下来这句最终只剩两段：是什么 + 预留值怎么演进。

**教训（比规则本身更重要）**：用户对同一句的判断随**成稿实读**反复收敛，
所以「纸面规则判完 ≠ 结束」。收口时**必须把成稿当整体读一遍**，
而不是逐条对着规则打勾——§0.3 已记，此处再记一次。

提炼：**禁令 6 的判据同样管「本 X 不承担 Y」式的负向边界句**。
域内扫出 9 处，删 2 处、留 7 处（校准表见正典 §三 禁令 6）。

| # | 位置 | 处置 |
|---|---|---|
| 48 | `subtitle/model/common/SubtitleRoute.java` | 删「本枚举仅定义取值域，不承载文件名、扩展名等派生逻辑」——没人会往枚举里塞派生逻辑，且「取值域」首行已说过 |
| 49 | `subtitle/port/SubtitleCleanser.java` / `SubtitleTranslator.java` | 删「『纠成什么样才算数』的判据（键集必须与原文完全一致、改写幅度不能超过相似度阈值）**属于实现细节**，住实现类里，不进本接口」——原文**自己就承认那是实现细节**（违禁令 3）。出处核查：实现类 `LlmSubtitleCleanser` 的 Javadoc 有三处、归档 8 份文档有，**spec 里没有**。同时删掉「上层只认这个接口」这句同义反复 |

## 0.5 全局回头：按最终标准重读全部 35 个已改文件

用户问「按这个风格，之前改的其他注释也改过了吗」——**答案是没有**。
标准是四轮里长出来的，前几轮的改动是在**更弱的规则**下做的。于是把 35 个已改文件的
**类级 Javadoc 全部拉出来逐条重读**（脚本 dump 到临时文件再读，避免控制台 GBK 吃中文），
又清掉 9 处残留、提炼出**禁令 7**。

| # | 文件 | 残留 | 违反 |
|---|---|---|---|
| 50 | `subtitle/model/keyword/SubtitleKeywords.java` | 「这份清单由 HTTP 两个入口（提交任务 / 重新生成）与持久化层共用」 | 7（列调用方，grep 一下就有） |
| 51 | `subtitle/policy/Bcp47Language.java` | 三行方法职责 map（复述三个方法名，且各方法自己的注释更全） | 7 |
| 52 | `subtitle/policy/TermVerification.java` | 「触发经济学的判定见 `decide`，执行与记账见 `verify`」 | 7（同义反复） |
| 53 | `subtitle/port/SubtitleKeywordRepository.java`、`task/port/VideoTaskRepository.java` | 首段与次段**说同一件事**（「不直接碰数据库那一套」× 「底下是 MySQL 还是别的存储不关心」） | 5（重复）→ 合并成一句 |
| 54 | `task/VideoTask.java` | 「两台状态机共享 updatedAt」 | 3（实现细节：字段重用） |
| 55 | `task/model/TaskNo.java` | 流转边界枚举了 4 个场景 | 冗长 → 收成「对外一律是 `String`」 |
| 56 | `task/policy/TaskStageEvent.java` | 「重连订阅者最关心的是它」 | 4（论证） |
| 57 | `subtitle/policy/CheckpointPolicy.java` | 末段点名 `ArtifactFiles.sizeOrZero` 这个技术原语 | 3 + 1（跨类）→ 收成一句 |

> **没动的判断项**（我判保留，理由随附）：`AudioSpec` 的「容器 → 编码器映射只写在抽取实现里」与
> `MediaAcquirer` 的「实现不得用配置开关决定装配」（读者**真会**那么做，属禁令 6 的例外）；
> `WorkspaceKey` 的「属共享内核…放 shared 避免跨上下文依赖」（放置理由，短且读者会问）；
> `TermVerification` 的「为什么挂在翻译而不挂清洗」（清洗也用术语表，读者会问）；
> `SubtitleKeywords` 的「没有 asr→fixed 对照关系」（`SubtitleCleanser` 的 @param 专门警告过这个陷阱）。

## 1. 逐文件登记表

| # | 文件 | 处置 | 上移去向 | 备注 |
|---|---|---|---|---|
| 1 | `subtitle/package-info.java` | 删两处 `2026-10-06 端口倒置/业务逻辑下沉 Phase B`；policy 桶的三个政策括注压到一句 | 目的地本身 | 19 行（例外登记） |
| 2 | `task/package-info.java` | 同上（`Phase C 落地` / `端口倒置：原为…`）；**补上 `policy/` 桶漏列的 `TaskStageEvent`**；修一处括号失衡 | 目的地本身 | 曾被 `…Phase C 落地）；` 断句 |
| 3 | `media/package-info.java` | 删 `2026-10-06 业务逻辑下沉 Phase C 吸收` | 目的地本身 | — |
| 4 | `subtitle/policy/CheckpointPolicy.java` | 25→15 行：`<ul>` 契约项收成散文；删「四个服务名枚举」（→ `package-info`）、`（2026-10-12 起）`、`（design 决策 5）`、`两处近逐字重复，合流于此` | 服务名 → `subtitle/package-info` | 本层改动最大 |
| 5 | `subtitle/policy/Bcp47Language.java` | 删「三块规则各管一件事」`<ul>`（三条规则各自的方法注释里都有）；`isSameMainLanguage` 17→13 行（去掉 zh-CN 的重复论证） | — | 摸底漏记的长尾块 |
| 6 | `subtitle/policy/TermVerification.java` | 删「判定顺序即旧守卫顺序…与迁移前逐字节一致」「按旧守卫顺序」「（09-28 实证）」；**订正「三条同时成立才查」→ 四条** | — | 见待订正 #3 |
| 7 | `subtitle/port/SubtitleCleanser.java` | `keywords` 的 `（design 决策 2）` 删；论证压短 | — | `@param` 里的负面约束**保留** |
| 8 | `subtitle/port/SubtitleContextSummarizer.java` | 删 `（design 决策 2）`；把重复三遍的「Optional 而不是空上下文」并进 `@return` | — | 关键词通道的 why 保留 |
| 9 | `subtitle/model/keyword/SubtitleKeywords.java` | 26→13 行：删归一化 `<ol>`（构造器与两个私有方法的注释里都有）、删「为什么指纹算法住在值对象身上」（归档 `10-11…/design.md` §2.1 有全文）、删两处 `design 决策 2/5` | 归档 `10-11-subtitle-keyword-regenerate/design.md` | **`MAX_COUNT` 等的注释按新规则保留** |
| 10 | `subtitle/model/cleanup/SubtitleTrack.java` | 24→15 行：删 `（2026-10-12 加，任务 10-11-…）`、`（见 research/asr-cloud-behavior.md §三）`、`见设计决策 5`；`**粗体**` 改 `<b>`；「代价」段并入 `@param` | 归档 `10-11…/design.md` | — |
| 11 | `subtitle/model/cleanup/CleanedSubtitle.java` | 17→15 行；`**粗体**` 改 `<b>`；「不直接对外」两行并一行 | — | 顺带删失效 import |
| 12 | `subtitle/model/common/SubtitleArtifact.java` | 删 `（见设计 §七）`、`见设计决策 5`；`**粗体**` 改 `<b>` | — | 摸底漏检（中文决策引用） |
| 13 | `subtitle/model/common/SubtitleRoute.java` | 删 `（见设计决策 3）`；`本轮` → `目前`（时间中立） | — | 摸底漏检 |
| 14 | `subtitle/model/common/SubtitleArtifactSpec.java` | `（2026-10-12 实测踩过：…）` 只删日期，**保留「matches 恒 false → artifacts 清单恒空」的后果** | — | 类注释本来只有 3 行，未动 |
| 15 | `subtitle/model/term/VerifiedTerm.java` | 21→15 行：`official 无三态` 的 `<ul>` 收成散文、两段合并 | — | — |
| 16 | `subtitle/model/translation/TranslatedTrack.java` | 17→15 行；`skipped` 的论证压短 | — | 无变更史，纯长尾 |
| 17 | `subtitle/model/translation/TranslatedSubtitle.java` | 17→15 行；「不直接对外」两行并一行 | — | 顺带删失效 import |
| 18 | `subtitle/model/translation/TranslatedSegment.java` | 16→15 行 | — | — |
| 19 | `media/model/AudioArtifact.java` | 16→15 行；删 `（2026-10-06 自 AudioPreparationCoordinator 下沉）`；「不做省略特例」并入指纹段 | — | — |
| 20 | `media/model/AudioSpec.java` | 21→15 行；删 `与原先写在…逐字一致——…已被本构造器取代` | — | 两个 `M4A/WAV` 常量注释按新规则**保留** |
| 21 | `media/port/MediaAcquirer.java` | `reuseExisting` 18→15 行；删 `（如 source.<ext>）` 与「上层只知道概念」一段 | — | 类注释 13 行未动 |
| 22 | `task/VideoTask.java` | 25→15 行；删 `（2026-10-02 两段式）`×3、`（2026-10-06 自 repository 内联字面量下沉…）`；订正 `SUCCEED` → `SUCCEEDED` 与半角括号 | — | 方法注释基本未动 |
| 23 | `task/policy/TaskStageEvent.java` | 17→11 行；删 `（2026-10-02 两段式）`、`（2026-10-12 起）`、`原是两个内联在 VideoTaskService 里的包内可见 static…下沉至此` | — | 下沉叙事 → 无去处，直接删（当前事实保留了） |
| 24 | `task/model/TranslationStatus.java` | 删 `（2026-10-02 两段式改造新增）` | — | 枚举常量注释**保留** |
| 25 | `shared/model/SourceUri.java` | 20→12 行；两条公式的 `<ul>` 收成散文 | — | 无变更史，纯长尾 |
| 26 | `shared/model/WorkspaceKey.java` | 18→15 行；删「原 `WorkspaceService.shortHash` 与 `VideoTaskService.KEY_LENGTH` …于此合一」「原 … 吸收于此」 | — | 两个常量注释**保留** |
| 27 | `transcription/port/ASRProvider.java` | 删 `（设计决策 1）`、`见设计决策 5` | — | C 组但实为待改 |
| 28 | `transcription/model/TranscriptionCommand.java` | **不动** | — | 对照面 ✓ |
| 29 | `transcription/model/AudioSource.java` | **不动** | — | 对照面 ✓ |

### 审查第二轮新增（§0.2）

| # | 文件 | 处置 | 触发禁令 |
|---|---|---|---|
| 30 | `subtitle/model/common/SubtitleRoute.java` | **按用户给的文本重写**：订正「含**两个**产不出的值」→ 三个；`一次定死` → `冻结契约`；删「本枚举无字段无方法」「kebab token / `name()` 派生 / 测试保证」「`AudioContainer` 类比」；四个常量注释统一句式 | ②③⑤ |
| 31 | `subtitle/port/SubtitleCleanser.java` | 删「实现在 infrastructure/…（当前是 `LlmSubtitleCleanser`），由 Spring 注进来」+「只声明那一个重载…给测试用」 | ③ |
| 32 | `subtitle/port/SubtitleContextSummarizer.java` | 同上删实现位置与 Spring | ③ |
| 33 | `subtitle/port/SubtitleTranslator.java` | **#31 的逐字孪生**，同款删 | ③ |
| 34 | `subtitle/port/SubtitleKeywordRepository.java` / `task/port/VideoTaskRepository.java` | 删「实现在 infrastructure/…（当前是 MyBatis-Plus 那版 X），由 Spring 注进来」；**保留**「本接口只说『能做什么』」这句职责边界 | ③ |
| 35 | `subtitle/port/TermLookupProvider.java` | 删「（领域包根，同 `SubtitleTranslator` 等的位置）」与「当前实现是 DashScope…零新增账号」；删 `09-28 的实证…已被否决`（变更史，且 `09-28` 漏过模式） | ①③④ |
| 36 | `task/model/TaskNo.java` | 「compact constructor 只校验…」→ 直接说硬不变量；删重复的「生成侧格式由 `newPending` 单点保证」 | ② |
| 37 | `subtitle/policy/Bcp47Language.java` | 删「（见 SubtitleTranslationCoordinator 的卫语句）」，保留调用点顺序约束本身 | ① |
| 38 | `subtitle/policy/CheckpointPolicy.java` | 「四处服务合流」→「各调用方合流」（名字已删，数字无从核） | ④ |
| 39 | `shared/model/WorkspaceKey.java` | 「紧凑构造器只校验非空白这一条硬不变量」→「唯一的硬不变量是「非空白」」 | ② |
| 40 | `task/VideoTask.java` | 删「（不开放 setter）」 | ② |
| 41 | `subtitle/model/translation/TranslatedSegment.java` | 删「（同 `SrtRenderer.toSrt` 的处理）」 | ① |
| 42 | `subtitle/model/common/SubtitleArtifact.java` | 删「——与 `domain/media` 里那些…不同」 | ① |
| 43 | `task/policy/TaskStageEvent.java` | 删「（与 `CheckpointPolicy` 的既有分工一致）」 | ① |
| 44 | `media/port/MediaAcquirer.java` | 删「（目前分为本地和链接【走yt-dlp插件获取】）」与「两个实现同时待在容器里」；`@ConditionalOnProperty` 那句改写成**不依赖实现数量的负面约束**；阶段词表 `（RUNNING / SUCCEEDED / …）` 删（→ spec 材料） | ②③ |

## 2. 标准补充（本层发现的设计缺口）

> 这一节是校准轮的主要产出：**规则在纸面上站不住的地方**。跑完拿它回改父 `design.md` 与正典。

| # | 遇到的情形 | `design.md` 怎么说 | 实际怎么定 | 建议回写正典？ |
|---|---|---|---|---|
| 1 | 全大写常量的注释是否算「复述标识符」该删 | 只说公有方法「可省」项，**没覆盖常量** | **保留**。全大写标识符焊词难读，翻译一次不叫复述；**枚举常量同款保留**（2026-10-12 用户定） | 已回写 `design.md` §1.3「常量 / 字段」+ 正典 §三 |
| 2 | record 的**类级** `@param` 与**构造器** `@param` 分工 | 未覆盖 | 类级描述 **record 组件**（构造后的字段），构造器级描述**入参**；两处各说各的、不重复 | 已回写正典 §三「record 参数」 |
| 3 | **spec 文档指针**（`directory-structure.md`「端口归属判据」、`logging-guidelines` 脱敏精神）算不算「决策出处」该删 | 只把 spec 列为**目的地**，没说代码里的 spec 指针怎么办 | **保留**。spec 随仓库走、是一等公民；`design.md`/`research/` 是**归档品**，才删。两者判然不同 | 已回写正典 §五 |
| 4 | AC2 的 **15 行线** 对 `package-info.java` 是否适用 | 未区分块类型 | **不适用**。它的职责就是承载包级地图，天然长于 15 行 | 已回写正典 §三「package-info」 |
| 5 | AC2 的 15 行线对「5 个组件的 record + 一条非显然不变量」偏紧 | 只说「无 >15 行，除外登记」 | 减到 15 行时**砍的是 `@param` 的行数，不是废话**——这类块应显式登记，而不是靠拆行凑数 | 建议：AC2 的例外登记**必须**写「为什么砍不动」，防止用换行作弊 |
| 6 | 「**本轮**/MVP-3 阶段」这类**项目阶段词**算不算变更史 | 未覆盖 | 是时间叙事，但讲的是产品范围不是改动史。处置：**改写成时间中立的说法**（`本轮不产出` → `目前不产出`） | 建议补进 §1.2 分流表「什么时候改的」一行 |
| 7 | **工作集的筛选口径本身漏了一整类问题** | Step A 只按「变更史标记 ∪ >15 行块」筛 | **层级错位（实现快照 / 跨类类比 / 实现细节混进契约）筛不出来**：一个 6 行的注释可以既无变更史、也不长，却满篇是实现快照。域内按禁令复查后改动面从 27 扩到 32。**后续各层子任务必须加第三条筛选线**：按 `guides/comment-guidelines.md` §三的**十三条禁令**全线复查，不能只跑两条机械线 | **已回写父 `implement.md` Step A**（用户 2026-10-12 定：复查面 = 该层**本届动过的文件**，不铺全量） |
| 8 | 模式清单漏了 `09-28` 这种**短任务号** | AC1 的模式只有 `20xx-xx-xx` 日期与 `NN-NN-<slug>` 任务 slug | `09-28 的实证…` 两样都不匹配，**漏检**（在 `TermLookupProvider` 里）。补模式 `(0[1-9]|1[0-2])-[0-3][0-9]` 有误伤风险（版本号、文件名），暂靠人眼 | **已写进正典「已知缺口」**（用户 2026-10-12 定：不加模式） |
| 9 | `SubtitleStageName` 六个枚举常量只带裸数字权重、且无注释 | 「枚举常量一律保留注释」 | **无解**：权重的**含义**（为什么 42/42/49/7/60）是产品口径，不是能从代码推出来的事实，自行编注释=造假。登记为**已知缺口**：这类「注释缺 + 事实不可推」的情形，收口时只登记、不代写 | **已回写正典 §三「常量 / 字段」** |
| 10 | 「为没发生的事辩护」值不值钱 | §1.2 分流表有「为什么不用现成方案」，但没给**判据** | 判据 = **读者本来就会去做那件事**才值钱。`WorkspaceKey`「不做 hex 正则校验」值钱（防人来「修」）；「不另设 X 枚举」「将来会去掉」不值钱 | **已回写正典 §三 禁令 6** |

## 3. 待订正清单（本层）

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| 1 | `subtitle/policy/CheckpointPolicy.java` | 原类注释写「四处合流」但只列环境名；`<ul>` 里「产物在但读不动」一条与其它条粒度不一 | 已改为散文；服务名移到 `package-info` |
| 2 | `subtitle/policy/TermVerification.java:30` | **失实**：原文「**三条**同时成立才查」，实际四条（同语种 / 产物已查证 / provider 未装配 / 无术语）且枚举有 5 个取值 | **已订正为「四条」** |
| 3 | `task/package-info.java:10` | **括号失衡断句**：`{@code policy/} 装无状态政策\n 2026-10-06 业务逻辑下沉 Phase C 落地）；` ——半角/全角括号混用，且 `policy/` 桶**漏列唯一的成员** `TaskStageEvent` | **已订正** |
| 4 | `task/VideoTask.java:311` | 原文「完成（**SUCCEED**）或者失败(**FAILED**)」——枚举值应为 `SUCCEEDED`，且半角括号与全文不一致 | **已订正** |
| 5 | `subtitle/model/cleanup/SubtitleTrack.java`、`SubtitleArtifact.java`、`CleanedSubtitle.java` | 用 Markdown `**粗体**` 而非 Javadoc `<b>`——**JavaDoc 不渲染 `**`**，原样印出星号 | **已统一为 `<b>`** |
| 6 | `subtitle/model/cleanup/SubtitleTrack.java:24` | 指向归档研究文档 `research/asr-cloud-behavior.md §三`，该文件在 `.trellis/tasks/archive/…` 下 | 已删（与 `design.md` 指针同判） |
| 7 | 全层 4 份文件 | 「短路语义与迁移前逐字节一致」「旧守卫顺序」「原为…」「原先…」「已被 X 取代」等改写叙事 | 已删（模式清单之外，靠人眼识出，见 §2 备注） |
| 8 | `subtitle/model/translation/TranslatedSubtitle.java`、`subtitle/model/cleanup/CleanedSubtitle.java` | 删掉 `{@link}` 交叉引用后**遗留未使用的 import** | **已删**——本层唯一的非注释行（2 行），见约束说明 |
| 9 | `subtitle/model/common/SubtitleRoute.java:5` | **失实**：原文「四值一次定死、含**两个**目前产不出的值」——实际**三个**（`PLATFORM_MANUAL` / `PLATFORM_AUTO` / `ASR_LOCAL`），`ASR_CLOUD` 是唯一产出的 | **已订正**（用户审 G1 时抓出） |
| 10 | `task/model/TaskStatus.java:6` | 「合法迁移只有三条」与 `VideoTask` 类注释**重复定义同一条规则**——两处要同步，迟早漂移 | **已改**：删重复的三条迁移，只留「权威定义在 `VideoTask`」 |
| 11 | `subtitle/model/common/SubtitleStageName.java:1` | `/***`（三颗星）笔误；「任务级区间在类加载时按顺序算出来」是实现快照；`/* 静态块，用于构造…*/` 是「做什么」的复述 | **已改** |
| 12 | `subtitle/model/common/SubtitleArtifact.java` 等 **4 处** | 首行是「字段清单式」自述（`两个产物路径 + 路线 + 语种 + 句数 + SRT 字节数`）——与 `SubtitleRoute` 的「本枚举无字段无方法」同族，只是没那么露骨 | **已改**为职责描述（`两条落盘产物与它们的元数据`） |
| 13 | `media/model/AcquiredMedia.java` | 「两条输入路线…远程取自 info json，本地由文件名与 `ffprobe` 补齐」——把两种实现的取材方式写进契约 | **已改**：只留「任何输入路线都必须给出」与 `Optional` 取值规则 |
| 14 | `shared/model/WorkspaceDir.java:43` | `dirName` 注释指向 `WorkspaceDirectoryManager` 与 `VideoTaskService` 两个类 | **未动**（不在用户裁定的 4 项内；登记备查） |
| 15 | `subtitle/model/common/SubtitleStageName.java` 的枚举常量 | 六个常量只带 `("acquire-media", 42)` 这样的裸数字，读不出权重含义；且**无注释**（违反「枚举常量一律保留注释」的精神） | **未动**：权重的意义需产品口径，不自行编（见 §2 #9） |
| 16 | `task/VideoTask.java` `requireMainSucceededForTranslation` | **命名与作用域不符**：名字说的是「翻译」，实际也被 `recordRegenerateFailure`（重新生成）在用——守卫真身是「主状态 SUCCEEDED 才能进第二生命周期」 | **未动**（改代码，本轮越界被用户拦回）。注释已按**真实作用域**写（「后置生命周期流转的公共前置守卫：翻译、重新生成等…」）。建议另开一个改名小任务 |
| 17 | 三处失败前缀口径不齐 | `重新生成失败：` 是常量（`REGENERATE_FAILURE_PREFIX`，有生产者 `TaskProgressNotifier:98`）；`翻译失败：` 与 `任务执行失败：` 都是 **`SubtitlePipeline:314/335` 里的硬编码字面量** | **未动**（常量化要动 application 层）。建议另开任务：三个前缀都提成 `VideoTask` 常量并接到生产者——**在那之前，任何注释都不能写「见 X 常量」**，因为常量与生产者会各说各的 |

## 4. 自检（实测结果）

```bash
# 只改注释？（除失效 import 外，应只有注释行）
git diff -w --stat                      # → 46 files, +252 −445；非注释行 3（失效 import）
# 本层变更史归零（模式清单只住正典 §六，照抄那一段）
grep -rnE "<正典 §六 的模式清单>" backend/src/main/java/com/vidsage/domain/   # → 命中 0
# 长尾收敛（粗筛信号：>15 行的块逐个复核「是否只回答那四件事」）
<块行数统计脚本>                        # → 188 块，>15 行剩 3（2 package-info + SourceUri，已登记）
# 行为不变
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
# → tests=570 errors=0 failures=0
```

**「只改注释」的例外**：删了 2 行因注释改动而失效的 `import`（`TranslatedSubtitle` 的
`CleanedSubtitle`、`CleanedSubtitle` 的 `SubtitleArtifact`）。行为等价，但不是注释行——如实登记。
`mvn test` 全绿说明没有手滑改代码；它对注释改对没改对**没有证明力**，那要靠人审 diff。

## 0.6 方法/字段级也回头一遍

用户追问「只改了类注释，还是方法注释也一起改了」——答案是**都改了**（第一轮就一起动），
但**方法级没像类级那样按最终七条禁令重读过**。于是把 35 个文件的**方法/字段级 Javadoc 全拉出来**
（109 个块，机械筛出 35 个可疑）重审，又清掉 6 处：

| # | 位置 | 残留 | 违反 |
|---|---|---|---|
| 58 | `SubtitleKeywords.computeFingerprint` | 「不带算法版本位：将来改归一化规则时…那正是想要的行为」 | 6（预言/论证） |
| 59 | `CheckpointPolicy.resolveContext` | 「摘要请求惰性求值…（翻译依赖 -clean，清洗必定先跑…）」 | 5（与类注释重复） |
| 60 | `TermVerification.verify` | 「查证任何失败形态都不抛出本方法——与「摘要失败降级空上下文」同一哲学」 | 1（跨类类比） |
| 61 | `TermVerification.decide` | 正文 5 行与下面 4 个 `@param` **逐条重复** | 5 → 正文删掉，why 并进 `@param` |
| 62 | `TaskStageEvent.fromTranslation` | 「与主状态渲染（`from`）同哲学…」（类注释已说）+「前置条件…调用点已保证」 | 5 + 4（论证） |
| 63 | `AudioArtifact.fileName` | 「音频准备服务按本名解析抽取目标」 | 7（列调用方） |

**留下的**（我判保留）：`isSameMainLanguage` 的 15 行（zh-CN 陷阱 + 空值语义都是真 why）、
`SubtitleCleanser.clean` 的 `@param keywords`（陷阱真实）、`TranslationStatus` 的「刻意不用 NONE 占位值」
（读者会自然去加 NONE）、`MediaAcquirer.reuseExisting`（两条负向边界都有真实动机）。

**仍未动的判断项**：`ASRProvider.transcribe` 首段「实现方决定各字段的支持程度…不感知具体供应商」（泛泛的抽象腔，
属禁令 2/3 边缘）、`MediaAcquirer.acquire`「远程路线下载到 workDir，本地路线直接采用不拷贝」（逐路线实现细节），
以及全层 `@param` 里纯复述参数名的若干行（如 `@param workDir 任务工作目录`）——**这三类要动了就是全层尺度的事，留给你定**。

## 0.7 审查第五轮：`SourceUri` 校准——契约可达性 / 类注释 vs 成员注释

用户直接重写了 `SourceUri` 的类注释并给了 7 条理由，带来**三条新判据**（已回写正典 §三 禁令 8–10）：

1. **契约必须可达**：「不同输入必须规范化出不同串」技术上不成立（哈希碰撞 + `绝对路径|大小|mtime`
   都保证不了唯一）→ 降级成「本类**不承诺**不同输入一定出不同串」。同类扫描命中 2 处，已改。
2. **用词必须准确**：「大小 + mtime」不是「内容指纹」，是很弱的**身份指纹**——不写这个词。
3. **类注释稳定、成员注释精确**：类注释只回答「是什么 / 不变量是什么 / 和其他类型什么关系 /
   边界在哪」，公式与参数语义下沉到成员注释。**判据：改一处公式要改两遍 = 违例。**

| # | 文件 | 处置 |
|---|---|---|
| 64 | `shared/model/SourceUri.java` | 按用户给的文本整段重写 |
| 65 | `shared/model/WorkspaceKey.java` | 公式段降为路线概述（`ofHash` 取身份串摘要 / `ofDigest` 收已算好的摘要）；删与 `ofDigest` 重复的「流式边写边算，不再二次哈希」 |
| 66 | `task/VideoTask.java` | 三条迁移降为「见各自的方法注释」（原来是 3 条、方法上各又写一遍） |
| 67 | `subtitle/model/keyword/SubtitleKeywords.java` | 类注释与紧凑构造器注释**说同一件事** → 类注释留契约（「拿到的都是归一化、去重、校验过的一份」），构造器留细节 |
| 68 | `subtitle/model/term/VerifiedTerm.java` | 类注释的 `official` 取值说明与 `@param official` 重复 → 类注释只留「空串 ≠ 没查」这条边界 |
| 69 | `subtitle/policy/CheckpointPolicy.java` | 类注释删「摘要请求经 `Supplier` 惰性传入…」（`resolveContext` 的 `@param` 已说） |
| 70 | `subtitle/policy/TermVerification.java` | 类注释删「产物落盘由调用方按 `Outcome#persistable()` 执行」（`verify` 的 `@return` 已说） |
| 71 | `media/model/AudioSpec.java` | 紧凑构造器注释删与类注释重复的「违反契约的取值到不了实例」 |
| 72 | `shared/model/WorkspaceDir.java` | **不可达断言**：「唯一性**完全由哈希保证**」→「区分不同工作区靠 key 后缀（哈希碰撞无法完全排除）」；`of` 删两个调用方类名；`sanitizeName` 删「`VideoTaskServiceTest` 跨包复用公式断言」（测试位置，禁令 3） |
| 73 | `media/model/MediaInput.java` | **不可达断言**：「唯一性完全由 key 保证」→ 同款改法。（本文件此前不在工作集，因缺陷同类而扩入） |

**仍未动、列给用户**：`MediaInput` 的类注释（三行编号列表、「远程URL」中英混排）+ `getContentKey`
（「决定它是在哪个工作区目录」语句不通）+ `ofTaskInput` 缺注释。

## 0.8 审查第六轮：`VideoTask` 成稿（**并确立「只改注释」的边界**）

用户贴了 `VideoTask` 的完整成稿 + 逐条理由。**我照稿改注释时顺手做了两处代码改动**
（重命名私有守卫 `requireMainSucceededForTranslation` → `...ForPostLifecycle`、新增公开常量
`TRANSLATION_FAILURE_PREFIX`），**被用户拦回**：「给你看的，你主要是改注释，代码不要动」。

→ 两处代码改动**已全部收回**，全层非注释行回到只剩那 2 行失效 import。

**边界据此明确**：本任务的 diff 里**不允许出现非注释行**；要改代码，另开任务、另走门。
（此前那 2 行失效 import 是唯一例外，见 §3 #8。）

**采纳的注释改动**：`它带会`→`它带有`（笔误）；`贫血的实体类`→`由 domain 承载，而不是只做数据容器的实体`；
`sourceUri` 的「日志不落原值」前置成显式约束 `<b>约束：本字段值不得直接写入日志</b>`；
`workspaceKey` 删掉派生规则（与 `SourceUri` 同一原则）；`isEventTerminal` 补「用于事件分发——返回 true 表示
该任务不会再产生后续状态事件」；`REGENERATE_FAILURE_PREFIX` 删掉 `TaskProgressNotifier.failRegenerate` 这个调用方名；
守卫的方法注释改按**真实作用域**写。

**我偏离成稿的一处**：成稿的 `errorMessage` 注释写「各自带前缀区分（见 `TRANSLATION_FAILURE_PREFIX` /
`REGENERATE_FAILURE_PREFIX`）」——**这句不成立**：`任务执行失败：` 根本没有常量。改写成
「各自带前缀区分」，不含常量引用（详见 §3 #17）。

**一个反噬**：光加常量、不改生产者，会让「下次改前缀会漏」**更容易发生**——常量看着像权威单源，
实际不是。所以本轮不加常量，改为登记任务（§3 #17）。

## 0.9 审查第七轮：`StageProgress`——**AC2 的机械线漏了一整档**

用户指着 `StageProgress.java` 问「这个文件没有动吗」。**没动，而且原因是筛检漏了它**：
它的类注释是 **15 行**，`>15` 这条线正好放过。

### 漏掉的档位有多大（实测）

| 块行数 | 个数 |
|---|---|
| 16+ | 3（2 `package-info` + `SourceUri`，已登记） |
| **13–15** | **26**（其中 **24 个已在工作集**，只有 2 个没碰过） |
| 10–12 | 14（其中 **5 个没碰过**） |
| 6–9 | 61 |
| ≤5 | 82 |

把没碰过的 7 个逐个审了，**5 个有同类问题**：

| # | 文件 | 残留 | 违反 |
|---|---|---|---|
| 74 | `shared/model/StageProgress.java` | 「形状参考 **oss-references 第 2 条**」——那是 `09-24-vidsage` 归档任务里的研究索引，而那份 prd 自己写着「索引不是规格…只读摘要等于二手转述」；「刻意保持最小（阶段名+进度+消息+状态）」= 复述 4 个 `@param`；整段「不是 SSE 专属 + 六个调用方类名 + 为什么住 shared（分层倒置教学）」 | 7 / 5 / 6 |
| 75 | `shared/port/ProgressListener.java` | **#74 的孪生**：同一段「放在 domain/shared 而非 application」+ 同一句「SSE 只是链尾那一跳」+ 列三层调用方 | 6 / 7 |
| 76 | `media/port/AudioExtractor.java` | 「**当前只有一个实现（ffmpeg 路线）**」= 实现快照；「未来接入 GPU 硬解…本接口即接入点」= 预言；「实现内部负责原子落盘（先写临时文件再原子改名）」= 机制细节 | 2 / 6 / 3 |
| 77 | `subtitle/model/common/SubtitleContext.java` | 「三样东西各有用处：…」= 复述三个 `@param` | 5 / 7 |
| 78 | `subtitle/model/term/TermLookupCommand.java` | 指向 `SubtitleTranslationCoordinator#verifyTermsIfNeeded`（application 层调用方），且「触发确定性由上层编排保证」在 `TermLookupProvider` 已说过一遍 | 7 / 5 |
| 79 | `subtitle/model/cleanup/CleanseResult.java` | 半角双引号 `"这趟干了多少事"`——全文其它地方用「」 | 标点不一致 |

**干净的**：`subtitle/model/term/TermLookupResult.java`（「查不到 ≠ 失败」是真契约）——所以不是「见长块就砍」。

### AC2 的三个校准点（建议改判据）

1. `package-info` 天然超标（职责就是包级地图）；
2. **用户亲手定的 `SourceUri` 成稿本身就是 16 行**——压到 15 要砍掉四件事之一；
3. **15 行块可以是 80% 噪音**（`StageProgress` 15 行里只有 `progress` 的取值域带信息）。

三条指向同一个结论：**`>15 行` 只能当粗筛，真判据应是「这块是否只回答那四件事（是什么 / 不变量 /
与其他类型的关系 / 边界）」**。建议把 AC2 改成这个口径——否则我们会用换行去凑一个本来就定紧的数。

**仍未动**：`SubtitleContext` 的 `@param sourceLanguage` 里「不叫 language，免得与 `SubtitleTrack#language()` 看岔」
——跨类命名理由，我判保留（字段名是 JSON 契约，不能随手改），但**这条属禁令 1 边缘，请用户定**。

## 0.10 全层全量复核（56 文件 / 186 块）

用户要求「检查当前任务范围内的所有类」。把 **domain 全层 56 个 .java 的全部 186 个注释块**
dump 到临时文件逐块读完（不是只跑 grep——前几轮的教训是机械线会漏）。

### 改了 12 处

| # | 文件 | 问题 | 违反 |
|---|---|---|---|
| 80 | `media/model/MediaFileInfo.java` | **`@param path` 根本不存在**——record 是 `(Path path, long sizeBytes, long modifyTimeMs)` 三个组件，注释只写了两个；首行「媒体信息」无句号 | 8（漏了就是失实） |
| 81 | `shared/package-info.java` | 「**后三者与 WorkspaceKey 同为**寻址词汇」——列了 4 个，「后三者」把 WorkspaceKey 自己也包进去了，自指；且把进度词汇 `StageProgress` 和寻址词汇混在一句说 | 9 |
| 82 | `shared/model/SourceUri.java` `ofRemoteUrl` | 「（**与原 normalize 公式一致**，不做改写）」——**变更史漏检**：AC1 的模式没有「原 + 空格」这一形态 | AC1 模式缺口 |
| 83 | `media/model/MediaInput.java` 类注释 | 「1.远程URL 2.本地文件 3.已经上传的文件」——**JavaDoc 不渲染这种编号**，且与三个嵌套 record 重复、中英无空格 | 7 |
| 84 | 同上 `getContentKey` | 「获取媒体的内容寻址key，决定它是在哪个工作区目录」——缺空格 + 语句不通；`@param`/`@return` **双复述** | 7 |
| 85 | 同上 `InWorkspace` | `@param dirLabel` 与接口方法 `dirLabel()` 的注释重复 | 5 |
| 86 | `subtitle/model/common/SubtitleArtifactSpec.java` `isDownloadable` | 「传入的文件是否可下载 / `@param fileName 传入文件` / `@return 是否可下载`」——**三重复述方法名** | 7 |
| 87 | `subtitle/model/common/SubtitleStageName.java` `getRange` | 「根据**Stage**获取**它的**起始和结束区间」——中英混排 + 代词指代不清；`@return` 与首行重复，且与 `RANGES` 字段注释**又说一遍** | 5 / 9 |
| 88 | `subtitle/model/common/SubtitleContext.java` 紧凑构造器 | 「null 一律收敛成**空值**」——实际是**空串**（theme / sourceLanguage）或**空表**（terms）；同族的 `VerifiedTerms` / `Outcome` 都写「空表」 | 9 |
| 89 | `shared/model/WorkspaceDir.java` `FALLBACK_NAME` | 「（与 **ensureWorkspaceDirectory** 同口径）」——指向另一个类 | 7 |
| 90 | `media/model/AudioContainer.java` | 「具体编码器与参数由各抽取实现自行映射，不在契约里出现 ffmpeg 词汇」——与 `AudioSpec` 同句**跨文件重复** | 5 |
| 91 | `subtitle/model/common/SubtitleStageName.java` `inMainPipeline` | 缺句号 | 标点 |

### 明确干净的一批（不是「见块就砍」）

`TranscriptionSegment` / `TranscriptionCommand` / `AudioSource` / `TranscriptionResult` /
`TaskInputType` / `TranslateResult` / `VerifiedTerms` / `TermLookupResult` / `VideoTaskRepository`
/ `SubtitleKeywordRepository` —— 逐块读完，**一字未动**。

### 待用户定（我没代写 / 没删）

| 位置 | 情形 |
|---|---|
| `MediaFileInfo.UNAVAILABLE` / `isAvailable()` | **均无注释**：全大写常量无注释、公开方法无注释。按「写与删是两件事」我没代写——补写是写的人的活 |
| `MediaAcquirer.acquire` | 「远程路线下载到 `workDir`，本地路线直接采用不拷贝」= 逐路线的实现细节 |
| `ASRProvider.transcribe` 首段 | 「实现方决定各字段的支持程度…不感知具体供应商」= 泛泛抽象腔 |
| `ASRProvider.requiredAudioSpec` / `route` 的 `@return` | 点名具体实现（「如云端实现要求 `AudioSpec.M4A_AAC_48K_MONO`」） |
| `SubtitleContext.sourceLanguage` | 「不叫 `language`，免得与 `SubtitleTrack#language()` 看岔」= 跨类命名理由，属禁令 1 边缘 |

## 0.11 收口决定（2026-10-12 用户裁定）

| 决定 | 内容 |
|---|---|
| **AC2 改判据** | 从「任一块 ≤ 15 行」改为「**任一块只回答四件事**（是什么 / 不变量 / 与其他类型的关系 / 边界）」；`>15 行` **降级为触发人工复核的信号**。理由：三条校准点（`package-info` 天然超标、用户成稿 `SourceUri` 16 行、`StageProgress` 15 行 80% 噪音）——**数行数既能放过噪音，也能误伤好块**。已回写父 `prd.md` AC2 / 父 `design.md` §5 / 正典 §三 禁令 10 |
| **5 个待定项** | **现在做完**（见下表） |
| **两个跨层代码项** | **另开任务** `10-12-failure-prefix-and-guard`（父级不挂在本任务下——本任务的边界是「只改注释」） |

### 5 个待定项（已做）

| # | 文件 | 处置 |
|---|---|---|
| 92 | `media/model/MediaFileInfo.java` | 补 `UNAVAILABLE`（全大写常量）与 `isAvailable()`（公开方法）的注释——此前两者**都没有** |
| 93 | `media/port/MediaAcquirer.java` `acquire` | 「远程路线下载到 workDir，本地路线直接采用不拷贝」→ 改成**真契约**：「返回的路径**不一定在 `workDir` 里**——本地路线的输入文件是直接采用、不拷贝的」。原句是逐路线实现描述，但它承载了「返回路径指向哪」这个真信息，不能整句删 |
| 94 | `transcription/port/ASRProvider.java` `transcribe` | 删首段泛泛抽象腔（「实现方决定各字段的支持程度…不感知具体供应商」） |
| 95 | 同上 `requiredAudioSpec` / `route` | `@return` 删掉点名具体实现的例子（「如云端实现要求 `AudioSpec.M4A_AAC_48K_MONO`」） |
| 96 | `subtitle/model/common/SubtitleContext.java` | 删 `@param sourceLanguage` 的跨类命名理由；顺带清掉因此失效的 `SubtitleTrack` import |

## 5. 门 G1 · 报审查

向用户输出四段，然后**停**：

1. domain 的 diff 摘要
2. 例外登记（>15 行且保留下来的块 + 理由）
3. 标准补充表（§2）——**规则哪里没兜住**，这是用户最该看的部分
4. 正典草稿（`guides/comment-guidelines.md`）

用户确认后才 `task.py finish` 本子任务、开子任务 2。
