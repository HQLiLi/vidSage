# application 层 · 执行计划

> 父 `implement.md` §1 的标准动作（A→E）全程适用，本文件只补**本层的清单与门**。
> **粒度标准 = `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令）**，此处不重复一字。
> 父 `prd.md` 的「不做」四条同样适用（尤其「不动 `@Schema` 落位」「diff 里不许出现非注释行」）。

## 0. 执行顺序

| 阶段 | 内容 | 目的 |
|---|---|---|
| 1 | **9 个 `package-info.java`**（application 根 + 8 个子包） | 先把「上移的目的地」备好 |
| 2 | 编排类（`subtitle/pipeline/` + `task/pipeline/` + `task/VideoTaskService`） | 本层重灾区 |
| 3 | §1 表内其余命中文件 | 主体收口 |
| 4 | 长尾块（16 个 ≥16 行 + **10 个 13–15 行档**） | 13–15 档必须一起看（§0.2 教训 1） |
| 5 | 全层块全量复核 | 机械线一定漏，兜底靠人读一遍 |
| 6 | 自检 + 报审查 | **门 G2（进行中）** |

## 0.1 工作集（2026-10-12 实测 → 收口后校正）

**三条筛选线并集原本 20 文件**；实际改动 **25 个 .java**，其中 **6 个是集外扩入**：

| 集外文件 | 被什么捞出来 |
|---|---|
| `diagnostics/port/ToolDiagnostics.java` | 线③（禁令 3：端口注释写实现在哪、由谁注入） |
| `subtitle/package-info.java` | 阶段 1 顺路（**失实**：写「后缀统一为 `Stage`」，实际全是 `*Coordinator`） |
| `media/pipeline/MediaAcquirerDispatcher.java` | 阶段 5 全层复核（构造器注释含「测试不用起 Spring」= 禁令 3） |
| `media/WorkspaceKeyGenerator.java` | 阶段 5（中英无空格 + 缺句号） |
| `task/port/TaskConcurrencyGate.java` | 阶段 5（缺句号） |
| `task/subscribe/TaskSubscriptionRegistry.java` | 阶段 5（缺句号 + 游离空注释行） |

**唯一「进了工作集但一字未动」的文件**：`diagnostics/ToolDiagnosticReport.java`——逐条核过，
`@param` 与 `@Schema(description=…)` 两份文案**一致**，按正典「只有不一致或腐坏时才订正」不动。

## 0.2 从 domain 试点继承的六条（试点的 implement.md 已归档）

1. **机械线漏整档**：`>15` 会放过 13–15 的噪音块。→ 本层把 **6 行及以上的块全部 dump 出来逐块读过**（144 块）。
2. **成稿要当整体读一遍**：逐条对着规则打勾 ≠ 做完。
3. **上移优先于删除**：见 §4 #1（进度事件序列确实搬去了 spec）。
4. **不代写**：见 §3 #7。
5. **只改注释**：diff 里不出现非注释行——本层实测 **0 行**（无失效 import）。
6. **报审查时「标准补充」（§4）比 diff 更值得看**。

## 0.3 本层已知重点（处置结果）

| 位置 | 处置 |
|---|---|
| `SubtitleCleanupCoordinator` 类注释的「管线形状」 | **删**——5 步箭头链与 `doClean` 本体逐句重复；类注释改留「是什么 + 断点复用靠指纹 + 只读/只写边界」。见 §4 #2 |
| 同上 `clean(...)` 的进度事件 `<ul>` | **上移** → `directory-structure.md` 新增「进度事件序列」小节 |
| `SubtitleTranslationCoordinator` 的 5 处「2026-10-02 两段式」 | 日期删；当前口径（按需触发、事件自成一段）留 |
| `TaskConflictException` 的决策记录 | **删**——`error-handling.md`「尚未建立的约定」有全文，零损失 |
| `TaskSessionRegistry` 的落位说明 | **删**——`directory-structure.md:78` 有全文（读它/写它/分工同形） |
| `TaskProgressMessage` 的载荷契约 | 按 prd 排除项**只订正不改写**（删「2026-10-12 拍板」一处日期，其余一字未动）——已验证 `web/` 只**用** `terminal`、不复述契约 |

## 1. 逐文件登记表（收口后）

| # | 文件 | 处置 | 上移去向 |
|---|---|---|---|
| 1 | `package-info.java`（根） | 删「2026-10-06 端口倒置前…豁免清单 a」（整段变更史）；`**粗体**` → `<b>`（JavaDoc 不渲染 `**`） | — |
| 2 | `diagnostics/package-info.java` | 删「（2026-10-06 端口倒置落地）」；实现类名 `ProcessToolDiagnostics` 换成分层事实「唯一一处 infrastructure → application 依赖」 | — |
| 3 | `media/package-info.java` | **不动**（纯目的地，无病灶） | 目的地 |
| 4 | `subtitle/package-info.java` | **订正失实**：删「后缀统一为 `Stage`」（实际是 `*Coordinator`，且该命名判据单源在 spec） | 目的地 |
| 5 | `task/package-info.java` | 删「（2026-10-05 起按角色分角色子包）」的日期、「2026-10-10 从根迁入」 | 目的地 |
| 6 | `task/message/package-info.java` | 删「2026-10-10 从根迁入——…」整句变更史 | 目的地 |
| 7 | `task/notify/package-info.java` | **不动**（纯目的地） | 目的地 |
| 8 | `task/subscribe/package-info.java` | 删「（历史：2026-09-30 曾定调为顶层 `subscribe/` 包…）」 | 目的地 |
| 9 | `transcription/package-info.java` | **不动**（纯目的地） | 目的地 |
| 10 | `subtitle/pipeline/SubtitleCleanupCoordinator.java` | 类注释 19→11 行；`clean(...)` 删进度事件 `<ul>`（→ spec）与首行流水账；`regenerate` 21 行（删 2 处「design 决策 N」+ 日期 + 任务号，两条规则与「先成功后删」保留）；构造器删「构造函数注入…」样板；3 处「不关心进度/内部走 noop()」统一成「不报告进度」 | spec（进度事件） |
| 11 | `subtitle/pipeline/SubtitleTranslationCoordinator.java` | 类注释 18→11 行；两处常量注释删「2026-10-02 两段式后…不再被 remap」；`hasTranslationArtifact` 17→14 行；`translate(...)` 删进度事件 `<ul>`；`verifyTermsIfNeeded` 25→16 行（四条触发条件与记账规则改为指向 `TermVerification#decide`/`verify`）；删「09-28 实证」 | spec（进度事件） |
| 12 | `subtitle/pipeline/SubtitlePreparationCoordinator.java` | 类注释补齐（原文是半角逗号的无句号标题）；`readCheckpoint` 删「（design 决策 4，回归测试守着这条）」；`@param audioPreparationCoordinator` 的「复用 2a，本阶段不碰 ffmpeg」改白话 | — |
| 13 | `task/pipeline/SubtitlePipeline.java` | 类注释重写（删「通知SSE订阅者」等机制复述）；`regenerateSubtitles` 删 `design §3.2 / PRD D4、D6 / AC9、AC10 / B5`；`translateTask` 的 5 项 `<ol>` 收成散文；`toMediaInput` 删「提交路线共用同一工厂…一致」 | — |
| 14 | `task/VideoTaskService.java` | 类注释 22→13 行（两条路线 `<ul>` 收成散文，公式下沉）；`translate`/`regenerate` 删 `design §3.2`、`AC8`、`AC10`、`（2026-10-02 两段式）`；`snapshot` 删「经 TaskController / TaskSseConnectionManager 推送」；删 3 处复述式行内注释 | — |
| 15 | `task/exception/TaskConflictException.java` | 19→13 行：删「为什么此刻引入第一个自定义异常」整段（decision 记录在 spec）、日期与任务号、`design.md 权衡记录`、`TaskResponse.from` 跨层指针 | — |
| 16 | `task/exception/TaskNotFoundException.java` | 13→8 行：同款删「为什么此刻引入第二个…」整段，改为指向 spec；保留「与 409 的分工」 | — |
| 17 | `task/session/TaskSessionRegistry.java` | 删 `design 决策 6 / PRD D6`、`PRD AC9 / AC10`、落位段（spec 有全文）、`findRunningStage` 的改名史整段 | — |
| 18 | `task/message/TaskProgressMessage.java` | 首行「用于SSE负荷」→「SSE 事件与连接快照的载荷」；删日期，**契约描述按 prd 不动** | — |
| 19 | `task/notify/TaskProgressNotifier.java` | 删 `design.md「SSE 契约」`、`（B5：…）`；补一处句号 | — |
| 20 | `task/query/TaskArtifact.java` | 删「随 web/dto/TaskResponse 对外」「2026-10-06 读模型拆分」；spec 指针保留 | — |
| 21 | `task/query/TaskSnapshot.java` | `sourceUri` 的「日志不落原值」按正典禁令 12 改成**显式约束**；删「2026-10-02 两段式」 | — |
| 22 | `media/pipeline/AudioPreparationCoordinator.java` | 21→16 行：删「**音频产物名与进度权重都是过渡态**…由 MVP-1 定锤」（**已失效的预言**）；`<ul>` 收成散文。**用户审查时再手改一次**（删「跳过必须可观测」一句），现 14 行 | — |
| 23 | `transcription/pipeline/Transcriber.java` | 删装配细节「Spring 按 vidsage.asr.provider…」「本地文件由云端实现先上传」；删构造器样板（单参构造器不留 Javadoc） | — |
| 24 | `diagnostics/port/ToolDiagnostics.java`（集外） | 删「实现在 infrastructure/process/（当前是 X），由 Spring 注进来」（禁令 3）；保留「不得反向调用本层用例服务」 | — |
| 25 | `media/pipeline/MediaAcquirerDispatcher.java`（集外） | 删「测试也可以直接 new 出来传一个 List，不用起 Spring」 | — |
| 26 | `media/WorkspaceKeyGenerator.java`（集外） | 「解析媒体并转化为工作区Key」→ 补空格与句号 | — |
| 27 | `task/port/TaskConcurrencyGate.java`（集外） | 补句号 | — |
| 28 | `task/subscribe/TaskSubscriptionRegistry.java`（集外） | 补句号 + 删游离空注释行 | — |
| 29 | `diagnostics/ToolDiagnosticReport.java` | **一字未动**（两份文案一致） | — |

PS：上表按文件列，行号见 `git diff`。改动面 **25 文件 / +148 −285**（另有 spec 1 份 +16，不计入「只改注释」的扫描面）。

## 2. 例外登记表（16 行及以上的块）

> 判据是「这块是否只回答那四件事」，行数只作粗筛。**不接受靠拆行凑数**。

| 文件 | 行数 | 为什么砍不动 |
|---|---|---|
| `task/package-info.java` | 19 | **package-info 豁免**（正典 §三明文）。承载根包 + 7 个角色子包的构件地图 |
| `task/query/TaskSnapshot.java` | 20 | 11 个 record 组件的 `@param` 占 11 行——**成员注释不属「类注释四件事」的管辖**，正典明文「公式、参数语义一律下沉到成员注释」 |
| `subtitle/pipeline/SubtitleCleanupCoordinator.java`（`regenerate`） | 21 | 两条与 `clean` 相反的规则（跳过检查点 / 摘要失败即整体失败），各带**为什么**；再加「先成功、后删」这条防重排的不变量。删任一条都会让下次改这块的人踩坑 |
| `subtitle/pipeline/SubtitleTranslationCoordinator.java`（`verifyTermsIfNeeded`） | 16 | 是什么 + 为什么挂在翻译流程 + 两处单源指针 + 4 个 `@param`；四个触发条件已收成指针，正文只剩「为什么」 |
| `task/pipeline/SubtitlePipeline.java`（`regenerateSubtitles`） | 16 | 异步体三条纪律各是一条不变量（先成功后删 / finally 退登记 / 不写 current_stage），删任一条都要靠读实现才能还原 |
| `task/session/TaskSessionRegistry.java` | 16 | 「它是一把锁，不是状态」+ 两条纪律；后者是**跨类契约**（写在调用方的 finally 里），删了没有任何地方再提醒 |

> **收口后复核**：`AudioPreparationCoordinator` 的类注释经用户审查手改（删掉「跳过时发 SKIPPED 并记 INFO
> 日志：断点复用必须可观测」一句，并留了一处空白注释行），**已降到 14 行**，不再计入例外。该改动是用户做的，
> 本任务不还原。

## 3. 待订正清单（本层，已逐条订正）

| # | 位置 | 现象 | 处置 |
|---|---|---|---|
| 1 | `subtitle/package-info.java:8` | **失实**：「后缀统一为 {@code Stage}」——三个类实际全是 `*Coordinator` | 已删该分句（命名判据单源在 spec §构件说明） |
| 2 | `media/pipeline/AudioPreparationCoordinator.java` | **失效预言**：「音频产物名与进度权重都是过渡态…应由 MVP-1 的异步管线 / SSE payload 定锤」——MVP-1 早已落地，这句既陈又误导 | 已删整段 |
| 3 | `subtitle/pipeline/SubtitlePreparationCoordinator.java:33` | 首行是半角逗号的无句号标题「字幕编排,从媒体音频中提取文本并落盘为字幕文件」 | 已改写 |
| 4 | `task/pipeline/SubtitlePipeline.java` 类注释 | 「通知SSE订阅者」中英无空格；首行无句号 | 已改写 |
| 5 | `media/WorkspaceKeyGenerator.java:21` | 「解析媒体并转化为工作区Key」中英无空格、无句号 | 已订正 |
| 6 | `task/message/TaskProgressMessage.java:9` | 「任务进度信息，用于SSE负荷」——「负荷」是「载荷」的误用 | 已订正 |
| 7 | 全层多处 | **`@param` 纯复述参数名**（如 `FileStateReader` 的「@param path 媒体路径 / @return 媒体状态快照」） | **未动**——试点把这一类明确留给你定（属全层尺度的判断，不是本层能单方面拍板的） |
| 8 | 全层多处 | **遗留阶段编号 `2b` / `2a`**：`SubtitleCleanupCoordinator` 的 2 处已改白话；`LocalFileMediaAcquirer:121`、`YtDlpRunner:22` 仍在（**infrastructure 层，不属本层扫描面**） | 本层 2 处已订正；另 2 处登记，建议随 infrastructure 子任务一并处理 |

## 4. 标准补充（本层对正典的反馈）

| # | 遇到的情形 | 正典怎么说 | 实际怎么定 | 建议回写正典？ |
|---|---|---|---|---|
| 1 | **「构件内部的步骤序列」该去哪**——`clean(...)` 的进度事件 `<ul>`、类注释的 5 步箭头链 | §二 把「流程步骤、进度事件序列」归 `.trellis/spec/`，把「本包有哪些构件」归 `package-info`；两者在「一个构件内部的步骤序列」上重叠 | **按 spec 处理**：进度事件序列已落 `directory-structure.md` 新增的「进度事件序列」小节；**5 步箭头链直接删**（与 `doClean` 本体逐句重复，属禁令 7 的「复述方法名成散文」）——子任务 prd 里写的「管线形状 → package-info」这一条，实测下来**该段其实不配进包级地图** | 建议：正典 §二 补一句「构件内部的步骤序列不属 package-info，也不进 spec——它与实现重复时直接删」 |
| 2 | **上移的目的地没有现成的家时怎么办** | §二 只说「上移优先于删除」，没说「目的地不存在」这一档 | 本层动了 spec（新增一小节） | **用户 2026-10-12 裁定：保留**（「先1」）。仍是本任务唯一一次改 spec 正文 |
| 3 | 标点硬伤（缺句号 / 中英无空格） | 禁令 4 说「笔误、错字、断句残句同样算硬伤」，但没说要清到什么程度 | 本层**只改自己读到的**，没有专程为标点扫全层——为几处句号铺一遍 144 块不划算 | 建议：明确「标点跟随改动，不单独立项扫」 |
| 4 | `@param` 复述参数名 | §三「公有方法」说 `@param` 只写语义/单位/null/负面约束 | **未动**，登记（见 §3 #7） | 待你定 |

## 5. 自检（实测值）

```bash
# 只改注释？（应只有注释行）
git diff -w --stat                                  # → 25 files, +148 −285；非注释行 0
# 本层变更史归零（模式清单照抄正典 §六）
grep -rnE "<正典 §六 的模式清单>" backend/src/main/java/com/vidsage/application/   # → 命中 0
# 长尾收敛（144 块；6 行及以上全部人工读过）
#   ≥16 行 6 块（1 个 package-info 豁免 + 5 个已登记理由）
#   13–15 行 10 块 / 10–12 行 19 块：逐块核过，无「大杂烩」
# 行为不变（先清陈旧报告，避免统计夹杂旧值）
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
# → tests=570 errors=0 skipped=1 failures=0
```

## 6. 门 G2 · 报审查（**2026-10-12 已通过**）

报审按 ① 改动文件清单（含 6 个集外扩入的文件及理由）② 例外登记（§2）③ 待订正清单（§3）
④ 标准补充（§4）⑤ `git diff -w` 摘要 五段输出。

**用户裁定**：① 保留 spec 新增的「进度事件序列」小节；② 认可「管线形状直接删、不进 package-info」
的处置；③ `@param` 复述参数名一类**暂不动**；④ `AudioPreparationCoordinator` 的类注释由**用户本人**
再删一句（「跳过必须可观测」），本任务不还原。

裁定后即提交本子任务 → 合并回 `dev` → 删除 `feature/comment-application` → 归档。
