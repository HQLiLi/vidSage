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
| 1 | 目的地就绪：复核 `shared/package-info.java`（14 行）有没有漏构件 | |
| 2 | `web/dto/` 5 类——`@param`×`@Schema` 最密的一处 + 本层唯一已知残句 | |
| 3 | `web/controller/` 3 类 + `web/sse/` 1 类——负向契约密集区（错误映射 / 心跳 / 订阅边界） | |
| 4 | `shared/` 命中 5 类（`progress` / `fs` / `subtitle`） | |
| 5 | `config/` 命中 3 类——校准依据上移、坑保留 | |
| 6 | **全层块全量复核**：≥6 行全部 dump 逐块读过（56 块 = 40 + 9 + 7，见 §0.2 教训 1） | |
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

**spec 指针（留，别误删）**：`ApiExceptionHandler:18,25`（`error-handling.md` / `logging-guidelines.md`）、`TaskController:32`（`logging-guidelines` 那半句）、`web/dto/TaskArtifactResponse:8` 与 `shared/fs/ArtifactFiles:20` 与 `shared/subtitle/TextSimilarity:11` 与 `config/WorkspaceProperties:12`（`directory-structure.md`）、`shared/text/TextTail:19`（`error-handling` 判空条）。已逐个核过：这些章节在 spec 里都存在（如 `directory-structure.md:355`「契约类的位置」、`error-handling.md:64`「字符串判空」）。

## 1. 逐文件登记表（执行中填，收口后按实测校正）

| # | 文件 | 命中线 | 处置 | 上移去向 |
|---|---|---|---|---|
| 1 | `web/dto/TaskResponse.java` | ①② | | |
| 2 | `web/dto/TaskArtifactResponse.java` | ② | | |
| 3 | `web/dto/SubmitUrlRequest.java` | — | | |
| 4 | `web/dto/RegenerateSubtitlesRequest.java` | — | | |
| 5 | `web/dto/SubtitleKeywordsResponse.java` | — | | |
| 6 | `web/dto/TaskConflictResponse.java` | — | | |
| 7 | `web/controller/ApiExceptionHandler.java` | ② | | |
| 8 | `web/controller/MediaController.java` | ② | | |
| 9 | `web/controller/TaskController.java` | ② | | |
| 10 | `web/sse/TaskSseConnectionManager.java` | ②③ | | |
| 11 | `shared/package-info.java` | 目的地 | | |
| 12 | `shared/progress/ProgressMapper.java` | ② | | |
| 13 | `shared/fs/ArtifactFiles.java` | ① | | |
| 14 | `shared/subtitle/SrtRenderer.java` | ②③ | | |
| 15 | `shared/subtitle/TermMatcher.java` | ① | | |
| 16 | `shared/subtitle/SubtitleSegmentSplitter.java` | ① | | |
| 17 | `shared/subtitle/TextSimilarity.java` | — | | |
| 18 | `shared/text/TextTail.java` | — | | |
| 19 | `config/ChatClientConfig.java` | ② | | |
| 20 | `config/SubtitleCleanupProperties.java` | ① | | |
| 21 | `config/SubtitleTranslateProperties.java` | ① | | |
| 22 | `config/TaskProperties.java` | —（阶段 6 专门看） | | |
| 23 | `config/WorkspaceProperties.java` | —（阶段 6 专门看） | | |

## 2. 例外登记表（滚动填）

> 判据是「这块是否只回答那四件事 / 是不是真承载知识」，行数只作粗筛，**不接受靠拆行凑数**。
> 本层 ≥16 行共 7 块（§0.1 线①），收口后逐块复核并登记；8–15 行的块在阶段 6 逐块读时判为「砍不动」的也可进表。

| 文件 | 块位置 | 行数 | 为什么砍不动 |
|---|---|---|---|
| （待填） | | | |

## 3. 待订正清单（滚动填）

> 断句残句 / 失实表述 / 失效预言。**只登记，不改代码。**

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| 1 | `web/dto/TaskResponse.java:45-47` | **残句**：「字段一一对应，产物清单逐元素映射为」后无下文（父计划 §3 #1 的外溢） | 补全或整句删 |
| 2 | `web/dto/*` 6 文件 | `@param` × `@Schema` 文案重复 | **不动**（父 `prd.md`「不做」），只在不一致时订正 |
| 3 | `web/controller/ApiExceptionHandler.java:20` | 「因此把**原先**…」变更史叙事（同一知识在 `error-handling.md:3` 有全文） | 改写为现状 |
| 4 | `shared/fs/ArtifactFiles.java:16` | 「它**不再是**「零依赖字节助手」」否定式快照 | 按禁令 2 改写为职责边界 |
| 5 | `shared/progress/ProgressMapper.java:10` | 「上提（**2026-09-30**，MVP-1）」 | 删 |
| 6 | `shared/subtitle/TextSimilarity.java:11`、`shared/subtitle/TermMatcher.java:22`、`config/SubtitleTranslateProperties.java:10` | 「将来 / 谁将来真要用」——**多为防误改的 why 而非预言**，逐个判；判不动就在本表写明理由 | 判 |
| 7 | `web/controller/MediaController.java:17` | 「异步管线落地收编删除（见本任务 `research/acceptance-evidence.md`）」——归档指针 | 删指针，收编结论按现状表述 |
| 8 | （执行中继续登记） | | |

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

## 5. 自检（命令 · 实测值待填）

```bash
cd f:/personal_project/vidSage
# 只改注释？（应只有注释行）
git diff -w --stat
# 本层变更史归零？—— 模式清单只住正典 §六，照抄那一段
grep -rnE "<照抄正典 §六 的模式清单>" backend/src/main/java/com/vidsage/{web,shared,config}   # 期望 0
# 自然语言变体人眼扫（正典 §六 已知缺口）
grep -rnE '原为|原先|此前|以前|曾经|已废弃|已被|本阶段|将来|本次故障|上一次' \
  backend/src/main/java/com/vidsage/{web,shared,config}                                       # 逐条判，见 §3
# 长尾收敛：≥6 行的块全部 dump 逐块读（56 块），≥16 行的进 §2
# 行为不变（先删陈旧报告，避免统计夹杂旧值）
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
```

基线**实测**、不引用历史数字（infra 层收口时实测 570；本层以开工时实测为准）。

## 6. 门 G4 · 报审查

按 ① 改动文件清单 ② 例外登记（§2）③ 待订正清单（§3）④ 标准补充（§4）⑤ `git diff -w` 摘要 五段输出，**停下等审查，不自行提交**。

## 7. 分支

执行时从当前分支 `dev` 开 `feature/comment-web-shared-config`；改完停等审查，通过后 `--no-ff` 合回 `dev`。
