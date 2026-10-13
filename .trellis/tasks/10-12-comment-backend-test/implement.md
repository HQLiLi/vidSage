# backend test 层 · 执行计划

> 父 `implement.md` §1 的标准动作（**A→E**）全程适用，本文件只补**本层的清单与门**。
> **粒度标准 = `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令）**，此处不重复一字。
> 父 `prd.md` 的「不做」四条同样适用（尤其**不改测试方法名**、**不动 `@Schema` 落位**、**diff 里不许出现非注释行**）。
>
> **状态：planning（2026-10-13 建）。** §0.1 四条筛选线已实测（只读扫描，未动文件）；§1–§3 的表随执行填。

## 0. 执行顺序

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工作集实测：四条筛选线 + 块统计（§0.1） | ✅ |
| 1 | **类注释迁移史族**（8 文件）：`Bcp47LanguageTest` / `CheckpointPolicyTest` / `TermVerificationTest` / `WorkspaceKeyGeneratorTest` / `NioWorkspaceDirectoryManagerTest` / `AudioPreparationCoordinatorTest` / `SubtitleArtifactSpecTest` / `MessageHistoryTest` | ✅ |
| 2 | **阶段分隔行括注**（6 文件 11 处）：`（2026-10-02 两段式…）` 一族 | ✅ |
| 3 | **事故 / 故障叙事**：`LlmSubtitleCleanserTest:302`、`SubtitleArtifactSpecTest:115`、`TaskSseConnectionManagerTest:191`、`SubtitlePipelineTest:518` | ✅ |
| 4 | **`design.md` 指针**：`TaskProgressNotifierTest:37`、`WorkspaceKeyGeneratorTest:50`；归档指针随各行处理 | ✅ |
| 5 | 其余命中：`MediaControllerTest:23`、`SubtitleCleanupCoordinatorTest:192`、`SubtitleTranslationCoordinatorTest:399`、`TermMatcherTest:14`、`SrtRendererTest:109`、`TaskProgressMessageTest:15`、`TaskControllerTest` 四处、`SubtitlePipelineTest:352` | ✅ |
| 6 | **全层块全量复核**：`≥6 行` 的 35 块逐块读（其中 **14 块落在工作集外**）——**捞到 5 个文件** | ✅ |
| 7 | 自检（§5）+ 报审查（§6） | ⬜ |

## 0.1 工作集（2026-10-13 实测）

56 文件 / 10,264 行 / 970 注释行（**9.5%**，与 `prd.md` 一致）/ 140 个 Javadoc 块。
块分布：`1–5 行 ×105`、`6–11 行 ×32`、`12–15 行 ×2`、`≥16 行 ×1`。

**长尾线几乎空转**——全层只有 1 块超 15 行。这层的活不在「长」，在「变」。

四条筛选线**并集 21 文件**（22 减去一条纯误报命中）：

| 线 | 命中 | 说明 |
|---|---|---|
| ① 变更史 / 归档指针（正典 §六） | 30 处 / 14 文件 | 主力是阶段分隔行的日期括注（11 处） |
| ② 自然语言变体（机械线拦不住） | 9 处 | `MVP-1` ×5、`原先是`、`将来`、`本次故障…`、`已随 MVP-1 收编删除` |
| ③ 层级错位（父 Step A 第 3 批） | 1 处 | `shared/subtitle/SrtRendererTest:109`（禁令 1 跨类类比） |
| ④ 解释既成安排（父 Step A 第 4 批，尺子形态） | 1 处，**是误报** | `SemaphoreTaskConcurrencyGateTest:72`「只差纳秒」是叙述 |

**工作集 21 文件**：

```
application/media/pipeline/AudioPreparationCoordinatorTest
application/media/WorkspaceKeyGeneratorTest
application/subtitle/pipeline/SubtitleCleanupCoordinatorTest
application/subtitle/pipeline/SubtitleTranslationCoordinatorTest
application/task/message/TaskProgressMessageTest
application/task/notify/TaskProgressNotifierTest
application/task/pipeline/SubtitlePipelineTest
application/task/VideoTaskServiceTest
domain/subtitle/model/common/SubtitleArtifactSpecTest
domain/subtitle/policy/Bcp47LanguageTest
domain/subtitle/policy/CheckpointPolicyTest
domain/subtitle/policy/TermVerificationTest
domain/task/VideoTaskTest
infrastructure/fs/NioWorkspaceDirectoryManagerTest
infrastructure/provider/llm/LlmSubtitleCleanserTest
infrastructure/provider/llm/MessageHistoryTest
shared/subtitle/SrtRendererTest
shared/subtitle/TermMatcherTest
web/controller/MediaControllerTest
web/controller/TaskControllerTest
web/sse/TaskSseConnectionManagerTest
```

**三条误报（复核时跳过，别顺手改）**：

| 位置 | 命中模式 | 为什么是误报 |
|---|---|---|
| `VideoTaskServiceTest:159` | `逐字节一致` | 是断言语义（上传流与落盘文件内容一致），不是「迁移前逐字节一致」那类变更史 |
| `SubtitlePipelineTest:156` | `原为` | 撞在 `上传任务应**还原为** InWorkspace` 里 |
| `SemaphoreTaskConcurrencyGateTest:72` | `只差` | 「与第二根的 countDown 之间只差纳秒」是叙述，不是「只差 JSON 这层」 |

**工作集外的 14 个 `≥6 行` 块**（阶段 6 必读，别因为「没命中机械线」就跳过）：
`ModelProbeTest:12`(13)、`AudioSpecTest:9`(11)、`LlmSubtitleTranslatorTest:29`(9)、`VidSageApplicationTests:7`(8)、
`ProcessToolDiagnosticsTest:18`(8)、`SemaphoreTaskConcurrencyGateTest:16`(8)、`TaskSubscriptionRegistryTest:20`(7)、
`MybatisSubtitleKeywordRepositoryTest:23`(7)、`MybatisVideoTaskRepositoryTest:29`(7)、`ProcessHelperMain:7`(7)、
`LlmSubtitleContextSummarizerTest:18`(7)、`DashScopeTermLookupProviderTest:18`(7)、`SubtitlePreparationCoordinatorTest:38`(6)、
`TaskSessionRegistryTest:11`(6)。

**本层预判的边界情形（执行时按正典判，拿不准就登记 §4）**：
`ModelProbeTest` 与 `ProcessHelperMain` 的类注释写的是**怎么跑它**（`MODEL_PROBE` 门禁、`PROBE_PROVIDER`、
`java -cp …ProcessHelperMain <mode>`）——正典 §二 分流表把「命令 / 参数清单」划归 spec，但**测试类的用法说明**
是使用契约（不写读者不知道门禁存在）。**预判：留**；若判为流水账，记进 §4。

## 0.2 从上四层继承的教训（四份 `implement.md` 均已归档）

1. **机械线漏整档**：`>15` 会放过 12–15 的噪音块 → 本层把 **`≥6` 行全部 dump 逐块读**（35 块）。
2. **成稿要当整体读一遍**：逐条对着规则打勾 ≠ 做完。
3. **上移优先于删除**：搬得走的先搬，再从代码里删本地副本。
4. **不代写**：缺注释、原因又推不出来的，登记进 §3，不自己编一句（假注释比没注释糟）。
5. **只改注释**：diff 里不出现非注释行。
6. **报审查时 §4（标准补充）比 diff 更值得看。**
7. **用户审查时会自己动手删**他认为属废话的段；**删掉的不登记、不还原**（照 application 层 `implement.md` §2「收口后复核」的写法留一行说明即可）。
8. **grep 只捞典型词**：第 4 批拦的是自然语言，误报与漏检都多——真判据是人读一遍（正典 §六「已知缺口」）。

## 0.3 本层已知重点与预判

| 位置 | 预判 |
|---|---|
| **阶段分隔行族**（`SubtitlePipelineTest:352`、`VideoTaskServiceTest:315/375`、`VideoTaskTest:169/323`、`TaskControllerTest:285/329/431`、`TaskSseConnectionManagerTest:147`、`SubtitleTranslationCoordinatorTest:277`，共 11 处） | **分隔行的结构作用留、括注删**：`// ---------- 按需翻译（2026-10-02 两段式，translateTask） ----------` → `// ---------- 按需翻译 ----------`。正典 §二「什么时候改的 → 不留」打的是括注，不是结构 |
| **「迁移 / 拆分而来（日期）：断言值不变」族**（`Bcp47LanguageTest:17-18`、`CheckpointPolicyTest:14-16`、`TermVerificationTest:21-22`、`WorkspaceKeyGeneratorTest:26-28`、`NioWorkspaceDirectoryManagerTest:19-21`、`AudioPreparationCoordinatorTest:101-104`、`SubtitleArtifactSpecTest:16-17`、`MessageHistoryTest:15`） | **整段删**（变更史）。同段里「本类留 X 断言、Y 随端口走 ZTest」的**分工句按禁令 7（索引）也删**——`grep` 一下就有。**但**同段里真正的接缝陈述要保（如 `AudioPreparationCoordinatorTest:103-104`「它钉的是服务↔命名规则的接缝」= 本用例存在的理由） |
| `TermMatcherTest:14-15` | 删「原 `SubtitleCleanser.hitTerms` 的判据——搬迁有意为之，判据一字不改，这批用例就是『没搬丢东西』的证据」（变更史 + 别处方法指涉）；「这批用例钉的是…判据」留 |
| `TermMatcherTest:18-21` | **留**：「必须各钉一条，否则将来有人把两个入口合并成一个，测试不会红」防的是真会发生的合并（禁令 6 判据）；`将来`是误报命中 |
| `TaskProgressMessageTest:15` | 删「（2026-10-12 起）」，留两截判据与出处（`VideoTask.isEventTerminal` / stage 状态位）；后半的「真值表由 `VideoTaskTest` 钉住」是索引 → 删 |
| `TaskProgressNotifierTest:37` | 删「`design.md` 保真清单」与「（第 5 / 6 / 8 条）」，留「钉住哪三件事」 |
| `WorkspaceKeyGeneratorTest:50` | 删「`design.md` §一 的语义陷阱注记」，留「两行不能并一行」（防误改的坑） |
| `SubtitlePipelineTest:518` | 删「（拍板 2026-10-12）」，留「成功后必须清掉，否则挂着失败文案」 |
| `SubtitleArtifactSpecTest:115` | 事故叙事（「2026-10-12 踩过：…」）；缺口**已修**且有钉子 → 删叙事，留往返不变量那句 |
| `LlmSubtitleCleanserTest:302` | 删「正是本次故障的守卫反杀点」这句叙事，**保**前半的阈值算术（0.667 < 0.7 → 靠基线拉回 1.0），改写为现状陈述（正典 §二 两条边界第 2 条） |
| `TaskSseConnectionManagerTest:191` | 「窗口竞态的历史缺陷路径（MVP-1 真实抓到过）」——**缺口至今没修**（并发竞态另开、未排期）→ 按 §二 例外**改写为现状的「已知局限」**，不提是哪次事故 |
| `SubtitleCleanupCoordinatorTest:192` / `SubtitleTranslationCoordinatorTest:399` | 删句末「，MVP-1」，其余（「管线通过 listener 拿阶段边界与批级进度」）是现状语义 → 留 |
| `MediaControllerTest:23` | 「原『抽音频』端点已随 MVP-1 异步管线收编删除」→ 整段删（变更史） |
| `SrtRendererTest:109` | 「（同 `toSrt` 对 null 文本的处理）」= 禁令 1 跨类类比 → 改自洽表述 |
| `TaskSseConnectionManagerTest:30`（**唯一 `≥16` 块，16 行**） | 删「（含翻译子状态的判定，2026-10-02 两段式）」的日期括注；`<ul>` 覆盖清单与用例名重复度高 → 复核后压缩；「为什么这里测不到字节形态」的边界陈述**留** |
| `LlmSubtitleCleanserTest:30`（10 行） | 「真 `ChatClient` + 桩 `ChatModel`」的手法说明与「重点钉重试的多轮消息形态」= 测试意图 → **留**；无变更史，预测一字未动 |
| `AudioPreparationCoordinatorTest:101-104` | 见第一族：删迁移句，**留**「它钉的是服务↔命名规则的接缝」 |
| `@TempDir(cleanup = CleanupMode.NEVER)` | 父 `prd.md` 明文**不动**（负面约束，删了会有人踩 flake 的坑）；其上方的约定性注释同理 |

## 1. 逐文件登记表（收口后）

| # | 文件 | 命中线 | 处置 | 上移去向 |
|---|---|---|---|---|
| 1 | `application/media/pipeline/AudioPreparationCoordinatorTest.java` | ① | 删「已随命名规则迁往 `domain/media/AudioArtifactTest`（2026-10-06…）」整段，留接缝陈述 | — |
| 2 | `application/media/WorkspaceKeyGeneratorTest.java` | ① | 类注释删「由 `WorkspaceServiceTest` 拆分而来（2026-10-09 端口倒置）…断言值与拆分前逐字一致」；`workDir` 的 Javadoc 删 `design.md` 指针、留「两行不能并一行」 | — |
| 3 | `application/subtitle/pipeline/SubtitleCleanupCoordinatorTest.java` | ② | 句末「，MVP-1」删；类注释「清洗算法…它的覆盖在 `SubtitleCleanserTest`」改为不点测试位置的边界陈述 | — |
| 4 | `application/subtitle/pipeline/SubtitleTranslationCoordinatorTest.java` | ① | 类注释删「已随逻辑下沉（2026-10-06 Phase B）迁到 …」整段与「它的覆盖在 `SubtitleTranslatorTest`」指针；分隔行删日期括注 | — |
| 5 | `application/task/message/TaskProgressMessageTest.java` | ① | 删「（2026-10-12 起）」与「（真值表由 `VideoTaskTest` 钉住）」；两截判据与紧凑构造器那条留 | — |
| 6 | `application/task/notify/TaskProgressNotifierTest.java` | ① | 删「`design.md` 保真清单」与「（第 5 / 6 / 8 条）」，三件事留 | — |
| 7 | `application/task/pipeline/SubtitlePipelineTest.java` | ① | 类注释删「2026-10-02 两段式：」前缀；「按需翻译」分隔行删括注；「（拍板 2026-10-12）」删 | — |
| 8 | `application/task/VideoTaskServiceTest.java` | ① | 三处分隔行删日期括注；`:159`「逐字节一致」判为**误报**不动 | — |
| 9 | `domain/media/model/AudioSpecTest.java` | 人读补扫 | 类注释删「本测试类是两个用例的归宿：原 `FfmpegAudioExtractorTest`…」变更史，留构造期不变量与异常类型判据 | — |
| 10 | `domain/subtitle/model/common/SubtitleArtifactSpecTest.java` | ① | 类注释删「原 `SubtitleArtifactNamesTest` 的对等重写（2026-10-07 产出物登记表）」；`:115` 事故叙事改写为不变量 + 失败模式 | — |
| 11 | `domain/subtitle/policy/Bcp47LanguageTest.java` | ① | 类注释删迁移史，改「语种归一化与源语种推断」 | — |
| 12 | `domain/subtitle/policy/CheckpointPolicyTest.java` | ① | 类注释删迁移史（含三个测试类的行号指涉），SRT 边界改自洽、去悬空 | — |
| 13 | `domain/subtitle/policy/TermVerificationTest.java` | ① | 类注释删迁移史；`:68`「判定顺序即旧守卫顺序…与迁移前一致」整行删（方法名已述） | — |
| 14 | `domain/task/VideoTaskTest.java` | ① | 类注释覆盖清单**剥日期**（清单本身保留）；两处分隔行删日期括注 | — |
| 15 | `infrastructure/fs/NioWorkspaceDirectoryManagerTest.java` | ① | 类注释删拆分史；分隔行删「（MVP-1 上传路线）」 | — |
| 16 | `infrastructure/persistence/repository/MybatisSubtitleKeywordRepositoryTest.java` | 人读补扫 | 删「（真库连通由 `VidSageApplicationTests` …验证）」指针 | — |
| 17 | `infrastructure/persistence/repository/MybatisVideoTaskRepositoryTest.java` | 人读补扫 | 同上 | — |
| 18 | `infrastructure/provider/llm/LlmSubtitleCleanserTest.java` | ② | `:302` 删「——正是本次故障的守卫反杀点」，阈值算术留 | — |
| 19 | `infrastructure/provider/llm/LlmSubtitleContextSummarizerTest.java` | 人读补扫 | 「同 `SubtitleCleanserTest` 的手法」改为自洽表述 | — |
| 20 | `infrastructure/provider/llm/LlmSubtitleTranslatorTest.java` | 人读补扫 | 「手法对照 `SubtitleCleanserTest`」改为自洽表述 | — |
| 21 | `infrastructure/provider/llm/MessageHistoryTest.java` | ② | 类注释删「这些用例原先是 `SubtitleCleanserTest` 里…」迁移史 | — |
| 22 | `shared/subtitle/SrtRendererTest.java` | ③ | `:109` 删跨类类比「（同 `toSrt` 对 null 文本的处理）」，改自洽 | — |
| 23 | `shared/subtitle/TermMatcherTest.java` | ② | 类注释删「钉的是原 `SubtitleCleanser.hitTerms` 的判据——搬迁…」；「两个入口必须各钉一条」保留 | — |
| 24 | `web/controller/MediaControllerTest.java` | ② | 删「原『抽音频』端点已随 MVP-1 异步管线收编删除」整段 | — |
| 25 | `web/controller/TaskControllerTest.java` | ① | 三处分隔行删日期括注 | — |
| 26 | `web/sse/TaskSseConnectionManagerTest.java` | ①② | 类注释删日期括注、`<ul>` 覆盖清单、「注册表行为由 `TaskSubscriptionRegistryTest` 钉住」索引；分隔行删日期；`:191` 事故叙事改写为现状 | — |

**改动面**：**26 个 .java 改**（工作集 21 + 人读补扫新增 5：`AudioSpecTest`、两个 MyBatis 仓储测试、
`LlmSubtitleTranslatorTest`、`LlmSubtitleContextSummarizerTest`），收口后 `+45 −97`；**非注释行 0**。
**人读补扫确实捞到了工作集外的东西**——5 个文件，全是 `≥6 行` 块的人读发现（机械线一条没命中）。

## 2. 例外登记表（滚动填）

> `≥16 行` 的块在这里逐条登记。**本层空表**——收口前全层只有 1 块（`TaskSseConnectionManagerTest:30`，16 行），
> 改写后 `≥16` 行块归零（140 块的分布：`1–5 ×109`、`6–11 ×30`、`12–15 ×1`、`≥16 ×0`），无需豁免。

| 文件 | 块位置 | 行数 | 理由 |
|---|---|---|---|
| （空） | | | |

## 3. 待订正清单（滚动填）

> 断句残句 / 失实表述。**本层种子为空**——父计划 §3 #1（`TaskResponse` 残句）已由 web 层订正，
> #2（`@param` × `@Schema`）属 `web/dto/`，本层不适用。
> 执行中未发现断句残句或与代码矛盾的表述。

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| 1 | `WorkspaceKeyGeneratorTest:32`、`NioWorkspaceDirectoryManagerTest:25`、`ProcessToolDiagnosticsTest`（类注释段） | `@TempDir(cleanup = CleanupMode.NEVER)` 的约定性注释带**归档指针**（「沿用 `implement.md` 记录的环境性清理规避」）与**事故频次叙事**（「本仓库已两次独立复现」）——按正典 §五 /  §二 两条边界第 2 条都该处理 | **未动**（父 `prd.md` 明文「不动那条约定性注释」）。**请裁定**：剥掉指针与频次句、只留负面约束，还是整条保持原样？ |

## 4. 标准补充（本层对正典的反馈）

> 各层执行中遇到正典没覆盖的情形，逐条记在这里，**用户裁定后回写正典**（正典逐层沉淀，不是一次冻结）。
> **报审查时这一节比 diff 更值得看。**

| # | 遇到的情形 | 正典怎么说 | 实际怎么定 | 建议回写正典？ |
|---|---|---|---|---|
| ① | **测试类的「覆盖清单」**（`<p>覆盖「…」「…」。」）算不算禁令 7 的索引？ | 禁令 7 删「它有哪些方法」；禁令 10 说类注释要答「边界在哪」 | **分层办**：内嵌日期的（`VideoTaskTest`）**剥日期保清单**；纯清单的（`TaskControllerTest`、`VideoTaskServiceTest`、`TaskSubscriptionRegistryTest` 等）**一字未动**；只对 `TaskSseConnectionManagerTest` 的 `<ul>` 整块改写（它同时带日期与指针） | **建议补一句**：「测试类的覆盖清单属职责边界，剥掉日期与指针即可，不按禁令 7 整块删」 |
| ② | 指向**另一个测试**的指针：「它的覆盖在 `XTest`」「真库连通由 `YTest` 验证」「手法对照 `XTest`」 | 禁令 7 把「测试在哪里」列为删（属禁令 3 的实现层细节） | 一律删或改为自洽表述（本层 5 处） | 正典已覆盖，**无需补** |
| ③ | **进度区间口径**（`prepare 0..70 / clean 70..100`）写在测试类注释里 | §二 分流表把「进度事件序列 / 区间」划归 spec | `directory-structure.md:133` 只有 cleanser 内部 `0..100`，**没有 prepare/clean 的区间**——上移会凭空造 spec 内容，故**保留**在 `SubtitlePipelineTest` 类注释里 | **待定**：若确认要把 prepare/clean 区间收进 `directory-structure.md` 的进度表，下一层顺手做；否则保留 |
| ④ | `@TempDir(cleanup=NEVER)` 的约定性注释含归档指针与事故频次叙事 | §五 归档指针删；§二 两条边界第 2 条事故叙事删 | 因父 `prd.md` 明文「不动」→ **原样保留**，登记进 §3 待你裁定 | 视 §3 #1 的裁定结果 |

**本层新发现（执行中填）**：见上表 ①②③。

## 5. 自检（实测值 2026-10-13）

```bash
cd f:/personal_project/vidSage && git diff -w --stat
#   → 26 files changed, +45 −97；**非注释行 0**
#     （逐行核过 +/- 行：全部是注释行，或删段留下的行合并）
grep -rnE "<照抄正典 §六 的模式清单>" backend/src/test/java
#   → 只 1 处：application/task/VideoTaskServiceTest.java:159 「逐字节一致」
#     ——「文件内容与上传流逐字节一致」是断言语义，不是「迁移前逐字节一致」那类变更史。
#     按正典 §六「已知误报不为此收窄模式，靠 review 排除」→ 判为误报，不改。
grep -rnE '原为|原先|此前|曾经|已废弃|本阶段|将来|本次故障|MVP-1|不再|逐字节一致' backend/src/test/java
#   → 「MVP-1」「原先是」「本次故障」「拍板 2026-10-12」全部清掉；「将来」剩 TermMatcherTest:20
#     一处，判为**留**（防「把两个入口合并成一个」的真误改，属禁令 6 判据）。
#     「逐字节一致」= 上面那处误报。
# 块统计：140 块不变；1–5 行 105→109、6–11 行 32→30、12–15 行 2→1、≥16 行 1→0
# 删完回查两件事：① 悬空引用——已修 2 处（CheckpointPolicyTest 的 SRT 句、
#   TaskProgressMessageTest 的「真值表由 VideoTaskTest 钉住」随被删句一起走）；
#   ② 注释块内裸空行——脚本扫过，0 处
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
#   → tests=570 errors=0 skipped=1 failures=0（与 web 层收口后基线一致，用例数未减）
```

**行为不变**：570 / 0 / 1 / 0。注释改动不参与编译，这条的价值在于排除「手滑改了代码」——
已另行逐行核过 diff，非注释行 0。

## 6. 门 G5 · 报审查

按 ① 改动文件清单 ② 例外登记（§2）③ 待订正清单（§3）④ 标准补充（§4）⑤ `git diff -w` 摘要 五段输出，
**停下等审查，不自行提交**（分支工作流硬规则）。

## 7. 分支

执行时从当前分支 `dev` 开 `feature/comment-backend-test`；改完停等审查，通过后 `--no-ff` 合回 `dev`。
