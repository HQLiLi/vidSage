# 字幕模型重构 4/8：重洗（RECLEAN run + 换版级联）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），本文只写本段的范围、取舍与验收，不复述终态、不另开一份设计。
> 段级技术落点与执行顺序见 [implement.md](./implement.md)。
> 前置 S3（`10-17-subtitle-artifact-authority`）已归档合并：产物表已经在跑，但**只有主管线会写它**。

## Goal

让「清洗产物换版」这件事把下游一起收干净：新 `CLEANED` 落定走 **W5**（旧版降级 + 下游译文级联作废 + 新行 `READY`，同一事务），事务提交之后再删下游译文的文件并标 `DELETED`。

两条会产生新 `CLEANED` 的路**共用同一套落定**：

| 入口 | run 类型 | 落到哪个方法 | 清洗会不会跳过 |
|---|---|---|---|
| `POST /{taskNo}/subtitles/regenerate` | `RECLEAN` | `cleanupStage.regenerate` | **永不跳过**（这个变体的语义就是重跑一遍） |
| `POST /api/tasks` 再提交同一个 URL | `MAIN` | `cleanupStage.clean` | **会跳过** —— 检查点指纹一致时直接返回，文件不动 |

**一句话判据**：跑完一次主管线后触发「重新生成字幕」→ `/artifacts` 里 `CLEANED` 的 `revision` 递增、`keywordsFingerprint` 换成新关键词的指纹；库里旧 `CLEANED` 行变 `SUPERSEDED` **且八个文件/哈希列全 NULL、不会出现 `DELETED`**；若它下面挂着译文且内容真变了，那些行变 `DELETED`、文件从工作区消失。

## 现状与欠账（代码实证）

S3 收尾的 11 条欠账里，本段关掉 **#1 / #3** 两条：

| 欠账 | 现状实证 | 本段动作 |
|---|---|---|
| **#1** RECLEAN 不动 artifact 表 | `SubtitlePipeline#regenerate` 只调 `cleanupStage.regenerate` + `supervisor.finish`，**一行 artifact 都没写** → 重洗后 `CLEANED` 行仍指旧 revision / 旧指纹 / 旧哈希，`/artifacts` 显示的元信息与文件内容对不上 | 补占位 + W5 落定 |
| **#3** 重新生成删了译文文件，但 DB 里的 `TRANSLATED` 行不会被标 | `SubtitleCleanupCoordinator#deleteTranslatedArtifacts` 按目录扫描删 `translated-*` / `terms-*`，且时机在 W5 之前 | 级联 + 删文件搬家（见 T3） |

另外三处**本段必须补上、S3 没做的**：

- `SubtitleArtifactStore` 只有 W4 版落定（哈希 → 降级旧版 → 置 `READY`），**没有级联**，构造里也没有事务模板。
- `SubtitleArtifactRepository#markDeleted` S1 就写好了，**至今零生产调用点**。级联要的批量取代（`markSupersededByTaskIdAndType`）S1 只给了单行版 `markSuperseded(id)`，本段换成批量（见 T6）。
- `SubtitleArtifact` 有 `getFileName(kind)`，但**没有哈希列的按位访问器**，级联的内容判据拿不到旧行的哈希。

## 本段定下的取舍

| # | 取舍 | 理由 | 备选与为什么否掉 |
|---|---|---|---|
| **T1** | **级联判据 = 内容真变了**（比 `CLEANED` 该带的三个文件位的哈希，与旧 `READY` 行逐个比），**不是无条件** | 哈希相同就是内容相同（SHA-256 碰撞概率 2⁻²⁵⁶，工程上为零），所以这条判据不是启发式、是精确判据。它只干一件事：把「重新提交同一个 URL、清洗命中检查点、字幕一字未改」这种情况下的 LLM 钱省下来 —— 否则用户会看到译文凭空消失且无解释，S5 的「再翻同语种不发 LLM」也会被一次重提交打掉 | 否掉「无条件级联」（父设计 §7.4 步骤 6 ② 的字面）：重提交会作废全部译文，而盘上字幕一字未改。**这是本段对父设计的订正，见下节** |
| **T2** | **级联挂在两条路共用的落定方法上**，`MAIN` 的 `CLEANED` 落定也走它 | 两个入口都会改 `CLEANED` 内容（`MAIN` 侧：重提交时带了新关键词 → 指纹不一致 → `clean()` 真重洗 → 文件被原子替换）。级联只挂 `RECLEAN` 会在 `MAIN` 这条路上留口子：下游译文仍是 `READY` 但基于旧内容，而 S5 的复用判据只看 `status='READY'`（父设计 §7.5 明确不带 `derived_from_artifact_id`）→ **静默复用旧译文**。父设计 §5 给 W5 的定义是「新 `CLEANED` READY ⇒ 下游全部 `SUPERSEDED`」，**没有限定 run 类型** | 否掉「只 `RECLEAN` 级联」：改动面小，但把不变量削弱成「只在重洗之后成立」，正是父设计最忌的错交付类型 |
| **T3** | **删下游译文文件搬到 W5 提交之后，按被级联那几行的文件列删**（`translated-segments-*.json` / `translated-subtitles-*.srt` / `terms-*.json`），删完再标 `DELETED`；`SubtitleCleanupCoordinator#deleteTranslatedArtifacts` **整块删掉** | ① 父设计 §7.4 步骤 7 与 §8.2 的写序纪律都是「先标 DB、后删文件」；② 路径是 DB 的一列，删什么由行决定，不再靠目录扫描猜；③ 顺手修掉现状一个真问题 —— 现在文件在 W5 之前就删了，若落定失败，用户会同时失去译文而 DB 还说它 `READY`；④ coordinator 回到「纯文件生产者」的定位（S3 的 T1） | 否掉「留在 coordinator」：顺序与父设计相反，且 coordinator 要开始知道 artifact 表的存在。否掉「两处都删」：同一件事两套判据，读代码的人要同时理解两条路径。**代价**：盘上有、表里没有的译文孤儿文件不再被自动清理 —— 父设计把孤儿列为接受项 ③，归 S7 对账报出 |
| **T4** | **W5 的事务边界圈在 `SubtitleArtifactStore` 内，用 `TransactionTemplate` 显式圈**；算哈希留在事务外 | 与 `SubtitleTaskService` 的 W1 同款（同类内调用不隔 Spring 代理）。哈希是慢文件 IO，必须留在事务外；而「降级旧版 → 级联下游 → 新行 READY」三步的顺序单源就在 store | 否掉 `@Transactional` 注解：要么把哈希圈进事务，要么拆两个方法后踩同类自调用、注解静默失效（W1 已经踩过这条）。否掉「新建一个 `ArtifactVersioning` 组件专管 W5」：多一个构件，且换版顺序与事务边界分居两处 |
| **T5** | **新增 `settleCleanedAndSupersedeDownstream(creating, workDir)`，与 `settleArtifact` 并列**（`settleArtifact` 原样留给无下游的类型） | 两个方法各自对应一个写集（W4 / W5），调用点一眼能看出「这一步会作废下游译文」 | 否掉「把级联塞进 `settleArtifact` 按类型分支」：一个名字下藏两种行为，调用点看不出译文刚被作废。否掉「让调用方自己调两步（落定 + 级联）」：漏掉第二步会留下永远挂着文件的 `SUPERSEDED` 行，且没有任何东西会报错 |
| **T6** | **级联走一句批量 UPDATE**：新增 `markSupersededByTaskIdAndType(taskId, type)`（`WHERE task_id=? AND artifact_type=? AND status='READY'`），替换 S1 的单行版 `markSuperseded(id)` | ① 父设计 §7.5 的原话就是这个 `WHERE` 子句 —— 那本来就是一条批量语句；② 同族的中间产物侧 S1 已经写成批量（`IntermediateFileRepository#markSupersededByTaskId`），两处级联一个形状；③ **读行与改行是两件事**：「事务提交后要按文件列删文件」需要把行读出来，但改状态不必逐行跟读 | 否掉「逐行 `markSuperseded(id)`」：与父设计措辞和同族形状都不一致，且 N 个语种就是 N 条语句。**副作用**：单行版随之零调用点，一并删掉（端口 + Mapper + 仓储实现 + 仓储单测） |
| **T7** | **`SubtitleTaskService#validateReadableArtifact` 改名 `requireReadableArtifact` 并返回产物本身**（取不到仍抛 400） | `regenerate` 要拿当前 `ORIGINAL` 的 id 填新 `CLEANED` 占位的 `derived_from_artifact_id`（父设计 §7.4 步骤 5）。与其在管线里再查一次（第二条判据、多一次 TOCTOU），不如让前置守卫把它交出来。`require` 的语义与 `SubtitleTaskFinder#require` 一致 | 否掉「管线里自己再查一次」：同一件事两个判据，且两次读之间产物可能被换版 |
| **T8** | **本段的级联只认 `TRANSLATED`**，`WHERE artifact_type='TRANSLATED' AND status='READY'`，**不带 lineage** | 父设计 §7.5 已定：级联不依赖 `derived_from_artifact_id` 被正确维护，更简单也更健壮。找「旧 `CLEANED`」同理走 `status='READY'` | 否掉「按 `derived_from_artifact_id` 找下游」：新 `CLEANED` 行的 `derived_from` 指向 `ORIGINAL`，不是旧 `CLEANED`，根本连不上 |
| **T9** | **手工验收用 SQL 造一行 `TRANSLATED`**（含盘上三个文件），不写任何临时代码 | S4 结束时 `TRANSLATED` 行一条都不会由代码产出（S5 才写），但不这么验，级联在真环境里一次都没跑过。这是父设计允许的「绕道记账」，与 S6 用 SQL 直查中间产物表同性质 | 否掉「级联只做单测、手工验收留给 S5」：S4 的 AC 里会有一条永远打不了勾。否掉「把 S5 的译文落表提前并进来」：破坏分段，S5 的交付被掏空 |
| **T10** | **不做存量回填** | 本地数据可弃；S4 之前库里不会有 `TRANSLATED` 行，所以「旧行哈希列为 NULL」这类异常数据在真实环境里不存在 | 同 S3（不写迁移脚本） |

### 本段对父设计的订正

父设计有两处写法与本段落地的口径不同，**本段顺手订正 `design.md`**（纯文档改动，见 implement.md 步骤 5）：

| 位置 | 原文 | 订正为 |
|---|---|---|
| §5 写集表 W5 行 | 「新 `CLEANED` READY ⇒ 下游全部 `SUPERSEDED`」 | 补一句判据：「新 `CLEANED` **内容变了**（三个文件位哈希与旧 `READY` 行不同）⇒ 下游全部 `SUPERSEDED`」 |
| §7.4 步骤 6 ② / §7.5 的对应说明 | 「所有下游 `TRANSLATED(*)` → `SUPERSEDED`（级联）」 | 同上补判据；并说明 `MAIN` 的 `CLEANED` 落定走同一套 W5 |

订正的理由只有一条：**「`READY` 的译文基于当前 `CLEANED`」这条不变量的正确形式是「基于当前 `CLEANED` 的内容」**，不是「基于当前 `CLEANED` 那一行」。父设计写这句话时还没有「`MAIN` 命中断点复用、清洗跳过、文件一字未改但照样落一版新 revision」这个行为（那是 S3 才落地的）。

## Requirements

### 写路径：W5 换版落定（§7.4 步骤 5–7 / §5 W5）

1. `SubtitleArtifactStore` 新增 `settleCleanedAndSupersedeDownstream(SubtitleArtifact creating, Path workDir)`，构造注入 `PlatformTransactionManager` 并自建 `TransactionTemplate`。
2. 事务**外**先算文件名与哈希：按 `ArtifactFileKind` 求出 `CLEANED` 应带的三个文件位，逐个求文件名（`kind.specFor(CLEANED).getFileName(creating.getLang())`）并算 sha256。读不到文件 → 抛 ISE（走收口），**旧版与下游都一点没动**。
3. 事务**内**三步，顺序写死：
   1. 读同 `(task, CLEANED, "")` 的旧 `READY` 行（**在清列之前读**，哈希列的判据要用它）；
   2. 旧行存在 → `markSupersededAndClearFiles(old.getId())`（覆盖型：状态 + 八个文件/哈希列一起动）；
   3. **内容变了才级联**：判据见下；变了就先读出该 task 全部 `READY` 的 `TRANSLATED(*)` 行（事务提交后删文件要用它们的文件列），再用**一句批量** `markSupersededByTaskIdAndType(taskId, TRANSLATED)` 把状态全标掉（孤儿型：只标状态，**文件列留着**）；
   4. 新行 `completeCreation(三个文件名, 三个哈希)` + `updateReady`。
4. 事务**提交后**（`TransactionTemplate` 之外）：对每一行被级联的译文，按它的文件列逐个 `Files.deleteIfExists(workDir.resolve(fileName))`，**全部删成功才** `markDeleted(id)`；任一删除失败或文件列缺登记 → 记 WARN、**不标 `DELETED`**（停在 `SUPERSEDED`，由 S7 对账报 ②）。
   这一段**整体是尽力而为**：`markDeleted` 落库失败也只记 WARN、**不把这一趟判成失败** —— 新版已经 `READY` 且事务已提交，回收没做完不该让用户看到 `FAILED`。留下的「已作废但文件还在」正是对账 ② 报的那一类。
5. **内容变了**的判据：把新算的三个哈希与旧 `READY` 行的对应哈希列逐个比，**任一不同即视为变了**；旧行不存在、或某个哈希列为 `NULL`，也视为变了（安全方向 —— 首跑时下游必然为空，等于空操作）。
6. `SubtitleArtifact` 新增 `getSha256(ArtifactFileKind)`（与已有的 `getFileName(ArtifactFileKind)` 对称），让「文件位 → 哈希列」的映射与 `ArtifactFileKind` 一样单源。
7. `settleArtifact` **原样不动**（W4，无下游的类型：`ORIGINAL`；将来 `TRANSLATED` 也回到它）。

### 接线：两条路都换到 W5

8. `SubtitlePipeline#runPipeline` 的 `CLEANED` 落定从 `settleArtifact` 换成 `settleCleanedAndSupersedeDownstream`；`ORIGINAL` 那一段不动。
9. `SubtitlePipeline#regenerate` 补上 artifact 生命周期，形状与主管线同款：
   - 调 `cleanupStage.regenerate` **之前**插 `CLEANED` 占位（`derived_from_run_id` = 本次 run、`derived_from_artifact_id` = 当前 `ORIGINAL` 的 id、`keywords_fingerprint` = 本次关键词的指纹）；
   - `regenerate` 返回后调 `settleCleanedAndSupersedeDownstream` 落定。
   - **不重跑 ASR、不重抽音频、不动 `ORIGINAL`**。
10. `SubtitlePipeline#regenerate` 的签名加一个 `long originalArtifactId`（由 `SubtitleTaskService` 交出，见 T7）。
11. `SubtitleCleanupCoordinator` **整块删掉 `deleteTranslatedArtifacts` 及其调用点**，连带订正类与方法上关于「先成功、后删」的注释 —— 删文件已归 W5 之后的回收步骤，coordinator 不再管 DB 里有什么。
12. `SubtitleTaskService#validateReadableArtifact` → `requireReadableArtifact`，返回 `SubtitleArtifact`；`regenerate` 把 `original.getId()` 传给管线。`translate` 的调用点同步改名（行为不变）。

### 非功能性

13. **本段不新增任何端点、不改任何 DTO**：`/artifacts`、下载端点、三个 POST 的形状全部照 S3 原样。
14. **不做存量回填**；不写迁移脚本。
15. **脱敏红线不变**：新增日志只记 `taskNo` / `taskId` / `runNo` / `type` / `lang` / 文件位 / 条数，不记 `source_uri`，不打印工作区绝对路径。
16. **代码注释不写 `C*` / `W*` / `§` / 段编号 / 日期**（`comment-guidelines.md` 的内容分流表 + pre-commit 钩子）；理由写在本任务文档里。
17. 判编译一律 `clean test-compile`；测试基线开工前重量（S3 收尾静态数 **610**）。

## 约束

- 编号引用（`C*` / `N*` / `W*` / `D-*`）的定义见父任务 [handoff.md](../10-15-subtitle-task-model-refactor/handoff.md)；实施以父 `design.md` 的收敛版为准（本段的两处订正除外）。
- **本段不写任何「只为这一段验收、后面必须删掉」的代码**（父 prd 两条原则之一）。手工验收里那条 `INSERT` 是**人跑的命令**，不进代码库。
- **只新增一句 SQL**：级联的批量取代（`markSupersededByTaskIdAndType`），同时删掉零调用点的单行版 `markSuperseded`；`markDeleted` / `markSupersededAndClearFiles` 原样复用。
- **`TaskRunSupervisor` 不碰**：S3 已把「本次 run 插的、仍停在 `CREATING` 的占位行 → `FAILED`」补进 `finish`，RECLEAN 的占位行天然被它收掉。
- 对外只暴露 `taskNo`；内部自增 id 不出现在任何响应里。

## 范围外

| 不做 | 交给 | 为什么 |
|---|---|---|
| TRANSLATE 的版本落定、复用判据、`TRANSLATED` 行的正常产出 | **S5** | 本段只负责「把下游作废」，不负责「把下游产出来」 |
| `intermediate_file` 登记、复用判据从「文件在」改成「表里 `READY` 且文件在」、指纹探测阶段、内容变更级联 | **S6** | 与 artifact 表是两张表、两类判据 |
| `POST /api/maintenance/reconcile` 与哈希比对 | **S7** | 本段只保证哈希列按纪律写对、`DELETED` 按两阶段标对 |
| 网络调用超时 | **S8** | 与产物换版无关 |
| 前端接新接口 | 另开任务（C60） | 本段结束时前端仍点不通 |
| 历史版本 / 版本回退 | — | C52 / C54 明确不做 |
| 把三条执行体拆成三条独立管线（主任务 / 重清洗 / 翻译） | **未排期**（用户 2026-10-19 记） | 拆分后「重提交会不会作废译文」这条判据要重审；本段先按 T1 的内容判据落地 |

## 验收标准

- [ ] **AC1 换版落表**：主管线跑完（`CLEANED` rev 1 `READY`）→ 触发一次「重新生成字幕」→ 库里 `CLEANED` 恰好一条 `READY`（rev 2，`keywords_fingerprint` = 本次关键词的指纹），rev 1 变 `SUPERSEDED` **且八个文件/哈希列全 NULL**；没有残留 `CREATING`；`ORIGINAL` 那一行**一点没动**（仍 rev 1 `READY`，哈希列原样）。
- [ ] **AC2 两种终态分清**：旧 `CLEANED` 停在 `SUPERSEDED`，**不出现 `DELETED`**（覆盖型）；被级联的下游译文走完 `SUPERSEDED → 删文件 → DELETED`（孤儿型）。
- [ ] **AC3 级联作废**：手工造一行 `READY` 的 `TRANSLATED(zh-Hant)` + 盘上放齐三个文件 → 触发一次**带新关键词**的「重新生成字幕」→ 该行变 `DELETED`、三个文件从工作区消失、`/artifacts` 里 `TRANSLATED` 消失。
- [ ] **AC4 内容没变不级联**：主管线跑完 + 造一行 `READY` 的 `TRANSLATED` → **重新提交同一个 URL、不带关键词**（`MAIN` 命中断点复用、清洗跳过、文件一字未改）→ `CLEANED` 落了新 revision（`READY`）但哈希与上一版相同，**下游译文仍是 `READY`、文件还在**。
- [ ] **AC5 `MAIN` 真重洗时级联**：同上场景，**重新提交同一个 URL 但带新关键词** → `MAIN` 真重洗、`CLEANED` 哈希变了 → 下游译文变 `DELETED`、文件被删。
- [ ] **AC6 失败不破坏旧版**：让 `regenerate` 在占位之后抛错（打桩或断网）→ 本次 run 的占位行变 `FAILED`、run 终态 `FAILED`；旧 `CLEANED` 与下游译文**原样可用**（状态与文件都没动）。
- [ ] **AC7 事务包裹与回滚**（单测）：W5 的三步写（旧版降级 / 下游级联 / 新行 `READY`）被**同一个事务**包住（一次 `begin`、一次 `commit`，三段写都在其间）；第三步抛异常 → **`rollback` 被触发、`commit` 零次**，异常从方法里传出。**边界**：本仓没有内存库可起（无 H2 / Testcontainers，`database-guidelines.md` 要求单测不连真库），所以验的是「包裹关系 + 回滚被触发」，不是「MySQL 真的回滚了」—— 收尾报告里写明这一条。
- [ ] **AC8 回收是两阶段**（单测）：删文件失败（或文件列缺登记）→ 该行停在 `SUPERSEDED`、**不标 `DELETED`**，且其余行照常回收。
- [ ] **AC9 前置与互斥回归**：无 `READY` `ORIGINAL` 时 `regenerate` → 400 且不插 run；同一 task 并发 `regenerate` → 一方 409。
- [ ] **AC10 回归**：`clean test-compile` 过；全量测试绿且条数 ≥ 610（新增测试只增不减）；后端能起服务；从零重建 `backend/data/works*` 后能走通「提交 → 等完成 → `regenerate` → 看 `revision` 与指纹变化」。
- [ ] **AC11 删文件只有一个落点**：`SubtitleCleanupCoordinator` 里不再有任何删文件代码；全仓「删 `translated-*` / `terms-*`」的动作只在 W5 提交之后发生。

## Notes

- **分支**：按 [git-workflow](../../../.trellis/spec/guides/git-workflow.md) 开 feature 分支，从当前 `dev` 切 `feature/subtitle-reclean-run`；**完成后合回 `dev`**。
- **测试基线**：S3 收尾静态数 610（`grep -c '@Test\|@ParameterizedTest'`）；开工前先 `rm -rf target/surefire-reports` 再跑全量，以实测数为准。
- **环境命令**：判编译 / 跑测试 / 起服务 / 推送代理见 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。起冒烟实例后按 PID `taskkill`（`TaskStop` 杀不掉子 JVM）。
- **本段结束时哪些东西还没到位**：见 [implement.md](./implement.md) 末尾的欠账清单，实施完必须逐条核对并原样写进收尾报告。
