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
| 1 | **类注释迁移史族**（8 文件）：`Bcp47LanguageTest` / `CheckpointPolicyTest` / `TermVerificationTest` / `WorkspaceKeyGeneratorTest` / `NioWorkspaceDirectoryManagerTest` / `AudioPreparationCoordinatorTest` / `SubtitleArtifactSpecTest` / `MessageHistoryTest` | ⬜ |
| 2 | **阶段分隔行括注**（6 文件 11 处）：`（2026-10-02 两段式…）` 一族 | ⬜ |
| 3 | **事故 / 故障叙事**：`LlmSubtitleCleanserTest:302`、`SubtitleArtifactSpecTest:115`、`TaskSseConnectionManagerTest:191`、`SubtitlePipelineTest:518` | ⬜ |
| 4 | **`design.md` 指针**：`TaskProgressNotifierTest:37`、`WorkspaceKeyGeneratorTest:50`；归档指针随各行处理 | ⬜ |
| 5 | 其余命中：`MediaControllerTest:23`、`SubtitleCleanupCoordinatorTest:192`、`SubtitleTranslationCoordinatorTest:399`、`TermMatcherTest:14`、`SrtRendererTest:109`、`TaskProgressMessageTest:15`、`TaskControllerTest` 四处、`SubtitlePipelineTest:352` | ⬜ |
| 6 | **全层块全量复核**：`≥6 行` 的 35 块逐块读（其中 **14 块落在工作集外**，见 §0.1 末） | ⬜ |
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
| （待填） | | | | |

## 2. 例外登记表（滚动填）

> `≥16 行` 的块在这里逐条登记。收口前全层只有 1 块（`TaskSseConnectionManagerTest:30`，16 行），
> 压到线下就整表为空；压不动就写明「为什么砍不动」。

| 文件 | 块位置 | 行数 | 理由 |
|---|---|---|---|
| （待填） | | | |

## 3. 待订正清单（滚动填）

> 断句残句 / 失实表述。**本层种子为空**——父计划 §3 #1（`TaskResponse` 残句）已由 web 层订正，
> #2（`@param` × `@Schema`）属 `web/dto/`，本层不适用。执行中发现的补进来。

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| | | | |

## 4. 标准补充（本层对正典的反馈）

> 各层执行中遇到正典没覆盖的情形，逐条记在这里，**用户裁定后回写正典**（正典逐层沉淀，不是一次冻结）。
> **报审查时这一节比 diff 更值得看。**

| # | 遇到的情形 | 正典怎么说 | 实际怎么定 | 建议回写正典？ |
|---|---|---|---|---|
| | | | | |

## 5. 自检（执行后填实测值）

```bash
cd f:/personal_project/vidSage && git diff -w --stat
#   → 期望：只有注释行变化；非注释行 0
grep -rnE "<照抄正典 §六 的模式清单>" backend/src/test/java
#   → 期望命中 0
grep -rnE '原为|原先|此前|曾经|已废弃|本阶段|将来|本次故障|MVP-1|不再|逐字节一致' backend/src/test/java
#   → 逐条判：现状语义或防误改的 why 留；变更史删（三条误报见 §0.1）
<块行数统计脚本>
#   → 140 块的分布复算；≥16 行块复核「是否只回答那四件事」
# 删完回查两件事（正典 §一 末尾）：① 悬空引用  ② 注释块内的裸空行
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
#   → 全绿，用例数不少于收口前（本层只改注释，不应影响用例）
```

## 6. 门 G5 · 报审查

按 ① 改动文件清单 ② 例外登记（§2）③ 待订正清单（§3）④ 标准补充（§4）⑤ `git diff -w` 摘要 五段输出，
**停下等审查，不自行提交**（分支工作流硬规则）。

## 7. 分支

执行时从当前分支 `dev` 开 `feature/comment-backend-test`；改完停等审查，通过后 `--no-ff` 合回 `dev`。
