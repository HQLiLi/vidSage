# 字幕模型重构 5/8：翻译（TRANSLATE run + 复用判据）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），本文只写本段的范围、取舍与验收，不复述终态、不另开一份设计。
> 段级技术落点与执行顺序见 [implement.md](./implement.md)。
> 前置 S4（`10-17-subtitle-reclean-run`）已归档合并：产物表由主管线与重洗在写，**但译文行一条都不会由代码产出**（S4 欠账 #1）。

## Goal

让 TRANSLATE run 真正落表：翻译完成后产出 `TRANSLATED(lang)` 的版本行，`/artifacts` 能看到、能下载；同一个目标语种再翻时命中复用判据，**不调 LLM、不落新 revision、文件一个字节不动**。

**一句话判据**：跑完主管线后 `POST /{taskNo}/translate`（`zh-Hans → ja`）→ `/artifacts` 多一条 `TRANSLATED(ja)`（rev 1、`READY`）；再翻一次 `ja` → run 照样 `SUCCEEDED`，但库里没有新行、盘上文件 mtime 不变、日志出现「跳过翻译」；再翻 `en` → 多一条 `TRANSLATED(en)`，`ja` 那一行与文件不受影响。

## 现状与欠账（代码实证）

| # | 现状 | 影响 | 本段动作 |
|---|---|---|---|
| **#1** | `SubtitlePipeline#translate` 只调 `translationStage.translate` + `supervisor.finish`，**一行 artifact 都不写** | 译文只存在于盘上：`/artifacts` 不列、下载 404，S4 的级联没有真实数据走过 | 补占位 + W4 落定 |
| **#2** | `SubtitleTranslationCoordinator#reuseCheckpointIfPresent` 按「`translated-segments-<lang>.json` 在不在」跳过翻译 | 在产物表成为权威之后，「文件在」不等于「当前版可用」：级联删文件失败时行是 `SUPERSEDED`、文件还在，**文件判据会把旧译文捞回来**；而 READY 行在、只删了 SRT 时（检查点还在），文件判据直接复用、**SRT 永远不补** | 复用判据搬到产物表，coordinator 的文件断点复用整块删掉 |
| **#3** | `SubtitleTaskService#translate` 调了 `requireReadableArtifact(CLEANED)` 但**把产物丢了** | 管线拿不到当前 `CLEANED` 的 id，新译文的 `derived_from_artifact_id` 没得填 | 让前置守卫把产物交出来，id 传进管线（与 S4 的 `regenerate` 同款） |
| **#4** | `SubtitleArtifactStore#resolveArtifactFiles` 按 `belongsTo` **要求该类型的每个文件位都在** | `TRANSLATED` 的 `terms-<lang>.json` 在四种情况下本来就不产出（同语种跳过 / 上下文无术语 / 查证未启用 / 全部查证失败）→ 落定当场算哈希失败，**翻译 run 被判 `FAILED`** | `TERMS` 对 `TRANSLATED` 改成**可选位** |
| **#5** | `SubtitleArtifactStore#isReadable` 与 `SubtitleArtifactQueryService#snapshotOf` 把「任一位为空」当成整版不可用 | 即使 #4 修好，没有 terms 的译文行也**不会出现在 `/artifacts`**、复用判据也永远不命中 | 两处都改成「可选位未登记不算缺」 |
| **#6** | `SubtitleArtifactStore#reclaimFilesOf` 把「文件列为 `NULL`」当异常，返回 `false` | S4 的级联回收遇到真实的「无 terms 译文行」会**永远停在 `SUPERSEDED`**、文件已删也标不了 `DELETED`。S4 的 AC 用手工造的行（三个文件齐全）验的，没踩到 | 回收时可选位未登记 = 正常跳过 |
| **#7** | `SubtitleArtifactStore#settleArtifact` 的两步 UPDATE **不包事务** | 崩在中间会出现「零 `READY`」的可见空窗；父设计 §7.5 对翻译这一步要求**同一事务** | 把一个事务包住两步（机制见 T4） |

## 本段定下的取舍

| # | 取舍 | 理由 | 备选与为什么否掉 |
|---|---|---|---|
| **T1** | **复用判据 = 该任务该目标语种有一条 `READY` 行、且该行应带的文件位可读**（直接用 `SubtitleArtifactStore#findReadableReady`）；**不带 lineage、不比哈希** | ① 「译文过时」不是在这里比出来的 —— 新 `CLEANED` 落定那一刻，S4 已经在同一事务里把下游标掉了（内容真变时才标），所以 `READY` 就等于「基于当前清洗**内容**」；② `derived_from_artifact_id` 只表达派生自哪一版，不表达当前性（父设计 §7.5）；③ 文件可读性是 C25 的读路径自愈（父设计 §7.8「文件不在按没有处理 + 重新生成」），不是第二套版本判据 | 否掉「字面只查 `status='READY'`、不看文件」：纯手工删掉译文文件后，再翻会「成功」但 `/artifacts` 还是空的、下载还是 404 —— 用户看到的是假成功。否掉「比 lineage」：新 `CLEANED` 行的 `derived_from` 指向 `ORIGINAL`，根本连不上旧译文 |
| **T2** | **复用判据落在 `SubtitlePipeline#translate` 的执行体里**（拿到并发配额之后、调翻译阶段之前）；**`SubtitleTranslationCoordinator` 的 `reuseCheckpointIfPresent` 及其调用整块删掉** | 判据只能有一处；文件断点复用是「产物表还不存在」时代的替代品。留着它，在行已 `SUPERSEDED`/不存在但文件残留时会**复用错内容**，正是本次要消灭的静默错交付 | 否掉「保留 coordinator 的文件判据当第二道优化」：两套判据在「行已作废但文件残留」与「只删了 SRT、检查点还在」两种场景下结论相反，读代码的人必须同时理解两条路径。**代价**：崩在「文件已替换、行还没插/没提交」之后且旧行不存在时，下次会多调一次 LLM 而不是复用检查点文件 —— 崩溃是低频事件，正确性优先 |
| **T3** | **占位行在翻译成功之后插**（翻译阶段返回 → 插 `CREATING` → 落定），照父设计 §7.5 步骤 4→5 的顺序 | LLM 失败是最常见的失败点（§7.5 原话）。失败发生在插行之前 → 库里**一条垃圾行都不会留**；旧 `READY` 译文与文件一点没动。插行太早则每次失败都留一条 `FAILED` 占位（由收口标），徒增噪声 | 否掉「照 S4 的 regenerate 在调阶段之前插占位」：S4 那么做是因为重洗覆盖的是**同名文件**、占位先落才能定住版本号；翻译的产出在 LLM 返回前根本不存在，没有「先占位」的必要 |
| **T4** | **W4 换版包进一个事务**：`settleArtifact` 的两步 UPDATE 同刻可见（**`ORIGINAL` 也一起获得这个性质**）。**事务机制按情况选**：外部 bean 调用、事务段是一个完整方法、且不需要在事务外先做慢 IO 时，优先 `@Transactional`；本处被理由 ②③ 挡住，用构造里已有的 `TransactionTemplate` | ① 父设计 §7.5 对翻译这一步写的是「同一事务」；handoff §7.5 的论证：无事务崩在两步之间 → 旧行 `SUPERSEDED` + 文件列已清、新行 `CREATING` → 用户看见「译文没了」；有事务则回滚、旧行仍是 `READY`。② **这里注解不能用**：算哈希必须留在事务外（S4 T4 的纪律），而注解只能标在方法上 —— 把事务段拆成同类私有方法再调用会**绕过 Spring 代理、注解静默失效**（W1 已经踩过这条）。③ store 的事务边界靠单测注入 `RecordingTransactionManager`、直接 `new SubtitleArtifactStore(...)` 来钉（本仓只有 2 个 Spring 上下文测试），注解在裸实例上是空转，AC9 就验不到 | 否掉「另开 `settleTranslated` 只给译文加事务」：同一个 W4 写集被拆成两个名字，而原子性差异在调用点看不出来（违背 S4 T5 的「一个方法对应一个写集」）。否掉「维持无事务」：会制造一次可见的空窗，且父设计已经点名要事务。**可选方案**：「把哈希放进事务、直接用 `@Transactional`」——代价是事务持有连接做文件 IO，且要换一套 Spring 上下文测试来验边界；本段选「哈希在外 + 模板」。**这不是「本仓禁用注解」**：`@Transactional` 该用照用，只是本处不适用 |
| **T5** | **`TERMS` 对 `TRANSLATED` 是可选文件位**（`SEGMENTS` / `SUBTITLES` 仍是必需；`CONTEXT` 对 `CLEANED` 仍是必需）。新增 `ArtifactFileKind#isRequiredFor`，四处读取方都认它：落定算位、可读判定、级联回收、产物清单 | 术语查证在既有实现里明确是「增益不是前提」：同语种跳过、上下文无术语、provider 未配置、全部查证失败**四种路径都不写 terms 文件**（而且「全部失败不落空壳」是刻意的 —— 空壳会被 `CheckpointPolicy.isCompleted` 当成「已查证」，永远不再补查）。产物模型必须如实反映「有没有这个文件」 | 否掉「永远写一份空 terms」：会把「查证失败」固化成「已查证」，下次永不再查，等于悄悄废掉术语查证。否掉「把 terms 从 `TRANSLATED` 里拿掉」：父设计 C55 明确暴露 `terms.json` 下载，它是真实产物 |
| **T6** | **`SubtitleTaskService#translate` 把当前 `CLEANED` 产物的 id 传给管线**（`pipeline.translate(..., cleanedArtifactId)`），管线**不再自己查一次** | 与 S4 T7 同款：守卫已经查过，再查一次是第二条判据 + 多一个 TOCTOU 窗口。互斥键保证：`translate` 的 run 插进去之后，同 task 不可能再有别的 run 改 `CLEANED` | 否掉「管线里重查」：同一件事两处判据；两次读之间产物可能被换版 |
| **T7** | **复用命中仍发一条 `SKIPPED` 阶段事件**（进度 100、消息「已有当前译文，跳过翻译」），然后 run 照常 `SUCCEEDED` | 这是原来 coordinator 文件复用时的用户可见行为；判据搬家不该顺手把 UX 弄丢。SSE 订阅者能看到「这条 run 干完了，没白翻」 | 否掉「只打日志」：订阅者只收到一条终态事件，看不出跳过原因 |
| **T8** | **不做存量回填** | 本段之前没有任何代码写 `TRANSLATED` 行；盘上即使有旧译文文件，也只是孤儿（S7 对账报 ③）。重新翻译一次即产出正确行 | 同 S3 / S4：不写迁移脚本 |

### 本段对父设计的订正

父设计有两处写法与本段落地的口径不同，**本段顺手订正 `design.md`**（纯文档改动，见 implement.md 步骤 5）：

| 位置 | 原文 | 订正为 |
|---|---|---|
| §1.4 三代文件组合表 | `TRANSLATED(lang)` = `segments` + `subtitles` + `terms` | `TRANSLATED(lang)` = `segments` + `subtitles` + **可选** `terms`；三个文件列仍然都归它，只是 `terms` 允许为空（查证未启用 / 无术语 / 全部失败 / 同语种 —— 四种路径本来就不写这个文件） |
| §7.5 步骤 3 | 「只读：`TRANSLATED(lang, status=READY)` 存在？」 | 「只读：该语种有一条 `READY` 行，**且该行应带的文件位可读**（C25）。不带 lineage、不比哈希」——把 §7.5 的「只认 status」与 §7.8 的「文件不在按没有处理 + 重新生成」合成一句，避免实施时把它读成「不看文件」 |

订正的理由只有一条：**父设计写这两处时，术语查证「可能不产出 terms 文件」与「文件可能被手工删掉」这两个事实还没有落进产物模型**。前者是本段要求产物模型如实表达的事实，后者是 S3 的读路径本来就有的自愈行为（`findReadableReady`），§7.5 的字面把两者都盖住了。

> 父 prd 任务地图里 S5 那一行写的「复用判据只认 `status`」是同一句的摘要，按本段口径执行：**状态判据只有 `status`**（不看 lineage、不比哈希），文件可读性是 C25 的读路径自愈，不是第二套版本判据。

## Requirements

### 写路径：TRANSLATE 落定（§7.5 步骤 3–6）

1. `SubtitlePipeline#translate` 签名加 `long cleanedArtifactId`；执行体内顺序写死：
   1. 定位 `workDir`；
   2. **复用判据**：`artifactStore.findReadableReady(run.getTaskId(), TRANSLATED, target.getCode(), workDir)` 有值 → 发 `SKIPPED` 阶段事件、`supervisor.finish(ref, run, null)`、**直接返回**（不调翻译阶段、不插占位、不写文件）；
   3. 没有 → `translationStage.translate(...)`（翻译阶段自己原子替换三个候选文件）；
   4. `artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(), TRANSLATED, target.getCode(), cleanedArtifactId, null)`；
   5. `artifactStore.settleArtifact(placeholder, workDir)`；
   6. `supervisor.finish(ref, run, null)`。
2. `SubtitleTaskService#translate` 用 `requireReadableArtifact(CLEANED)` 的返回值，把 `getId()` 传给管线；其余同步判定（404 / 400 / 409）与响应形状**一个都不变**。
3. 占位的 `lang` 用 `target.getCode()`（规范码，如 `zh-Hans`），与产物行、下载端点的归一口径一致；文件名仍由 `SubtitleArtifactSpec` 按盘上规则小写。

### 可选文件位（`TERMS`）

4. `ArtifactFileKind` 新增 `isRequiredFor(ArtifactType)`：`SEGMENTS` / `SUBTITLES` 对所有类型必需；`CONTEXT` 仅 `CLEANED` 必需；`TERMS` **对任何类型都不必需**。`belongsTo` 语义不变（这个类型**可以**有的位）。
5. `SubtitleArtifactStore#resolveArtifactFiles`：必需位读不到 → 仍抛 ISE（旧版与下游一点没动）；可选位**工作区里有普通文件才登记并算哈希，没有就留 `null`**。
6. `SubtitleArtifactStore#isReadable`：必需位必须登记且文件在；可选位**登记了才要求文件在**，未登记不算缺。
7. `SubtitleArtifactStore#reclaimFilesOf`：可选位未登记 → **正常跳过**（不再 `WARN` + 返回 `false`）；登记了 → 照常删。
8. `SubtitleArtifactQueryService#snapshotOf`：可选位未登记 → 只少一个 `files` 项，**不把整版丢掉**；必需位缺失仍整版不列。
9. 下载端点的参数校验不变：`TRANSLATED ... terms.json` 合法，但该版没登记 terms → 404（现有防御分支已覆盖）。

### W4 换版

10. `settleArtifact`：算文件名与哈希留在**事务外**；事务内「查旧 `READY` → 覆盖型取代并清列 → 新行写八列并置 `READY`」，一次 `begin` / 一次 `commit`。事务机制按 T4 的选型规则：本处用 `TransactionTemplate`，**不是全仓一律模板**。
11. 事务任一步抛异常 → 回滚，异常向外抛，由 `supervisor.finish` 收成 `FAILED`；旧版仍是 `READY`。

### 翻译编排（coordinator）

12. 删掉 `SubtitleTranslationCoordinator#reuseCheckpointIfPresent` 及其调用；「文件存在 → 跳过翻译」的行为不再存在。类注释里关于断点复用的段落同步改写（判据已归产物表）。
13. 保留：同语种跳过 LLM（但仍产出译文 = 原文）、术语查证的四条跳过路径、三个文件的原子写入、进度事件。**这些行为的语义一个字都不改**。

### 非功能性

14. **不新增端点、不改任何 DTO、不改表结构**：`/artifacts`、下载端点、三个 POST、`GET /api/languages` 全部照 S3/S4 原样。
15. **不做存量回填**；不写迁移脚本。
16. **脱敏红线不变**：新增日志只记 `taskNo` / `taskId` / `runNo` / `type` / `lang` / 文件位 / 条数，不记 `source_uri`，不打印工作区绝对路径。
17. **代码注释不写 `C*` / `W*` / `§` / 段编号 / 日期**（`comment-guidelines.md` 的内容分流表 + pre-commit 钩子）；理由写在本任务文档里。
18. 判编译一律 `clean test-compile`；测试基线开工前重量（S4 收尾静态数 **623**）。

## 约束

- 编号引用（`C*` / `N*` / `W*` / `D-*`）的定义见父任务 [handoff.md](../10-15-subtitle-task-model-refactor/handoff.md)；实施以父 `design.md` 的收敛版为准（本段的两处订正除外）。
- **本段不写任何「只为这一段验收、后面必须删掉」的代码**（父 prd 两条原则之一）。
- **不碰 `TaskRunSupervisor`**：S3 已把「本次 run 插的、仍停在 `CREATING` 的占位行 → `FAILED`」放进 `finish`；翻译占位行在落定失败时天然被它收掉。
- **不改 `SubtitleArtifact` 的状态机与四个哈希列的写序纪律**：`CREATING` 行仍然永远全 NULL。
- 对外只暴露 `taskNo`；内部自增 id 不出现在任何响应里。
- `sourceLanguage` 只是给 LLM 的入参，**不参与复用判据、不进产物身份**（artifact 行只有 `lang` = 目标语种）；同一目标语种已有可读 `READY` 就复用，无论这次传的源语种是什么。

## 范围外

| 不做 | 交给 | 为什么 |
|---|---|---|
| `intermediate_file` 登记、中间产物的复用判据、指纹探测阶段、内容变更级联 | **S6** | 与 artifact 表是两张表、两类判据 |
| `POST /api/maintenance/reconcile` 与哈希比对 | **S7** | 本段只保证哈希列按纪律写对 |
| 网络调用超时 | **S8** | 与产物换版无关 |
| 前端接新接口 | 另开任务（C60） | 本段结束时前端仍点不通 |
| 历史版本 / 版本回退 | — | C52 / C58 明确不做 |
| 把三条执行体拆成三条独立管线（主任务 / 重清洗 / 翻译） | **未排期**（用户 2026-10-19 记） | 拆分后「重提交会不会作废译文」这条判据要重审 |

## 验收标准

- [ ] **AC1 首次翻译落表**：主管线跑完（`CLEANED` `READY`）→ `POST /{taskNo}/translate`（`zh-Hans → ja`）→ run `SUCCEEDED`；库里恰好一条 `TRANSLATED` `READY`（`lang='ja'`、`revision=1`、`derived_from_run_id` = 本次 run、`derived_from_artifact_id` = 当前 `CLEANED` 的 id、三列哈希与盘上文件一致）；`/artifacts` 出现该版，下载 `segments.json` / `subtitles.srt` 成功；没有残留 `CREATING`；`ORIGINAL` / `CLEANED` 两行一点没动。
- [ ] **AC2 同语种再翻不发 LLM**：紧接着再 `POST` 一次同样的 `zh-Hans → ja` → run `SUCCEEDED`；**没有新 revision**（仍 rev 1）、三个文件 mtime 不变；日志出现「跳过翻译」；SSE 收到一条 `SKIPPED` 的翻译阶段事件。
- [ ] **AC3 换语种互不影响**：再翻 `en` → 新增 `TRANSLATED(en)` rev 1 `READY`；`ja` 那一行仍是 `READY`、文件原样。
- [ ] **AC4 文件缺失自愈**：手工删掉某语种译文的 `translated-subtitles-*.srt` → 再翻同语种 → **判为不可复用**、真跑一次翻译，落 rev 2 `READY`；rev 1 变 `SUPERSEDED` 且八个文件/哈希列全 NULL（覆盖型，不出现 `DELETED`）；文件重新出现。
- [ ] **AC5 失败不破坏旧版**：让翻译阶段在 LLM 处抛错（临时把模型 key 改坏 / 断网后重启实例）→ run `FAILED`、`error_message` 有原因；**本次没有新增 artifact 行**（占位是在翻译成功之后插的）；旧 `READY` 译文与文件原样可用；随后恢复正常再翻，能落新版本。
- [ ] **AC6 `terms` 可选**：选一条**不会产出 terms** 的任务（同语种翻译，如 `zh-Hans → zh-Hans`；或关掉 / 弄坏术语查证让全部失败）→ 译文 `READY`、`terms_file_name` / `terms_sha256` 都是 `NULL`；`/artifacts` **照样列出该版**（`files` 只有 `segments` / `subtitles`）；下载 `terms.json` → 404。随后触发一次**内容真变**的重洗 → 该译文行走完 `SUPERSEDED → 删文件 → DELETED`，**不卡在 `SUPERSEDED`**（S4 欠账 #2）。
- [ ] **AC7 级联回归用真译文行**：用 AC1 产出的真译文行重跑 S4 的 AC3 / AC4 / AC5 —— 内容真变的重洗 → 译文变 `DELETED`、文件从工作区消失；内容没变的重提交 → 译文仍 `READY`、文件还在。（S4 欠账 #2）
- [ ] **AC8 前置与互斥回归**：没有可读 `CLEANED` 的任务 `translate` → 400 且不插 run；同一 task 并发两次 `translate` → 一方 409，响应体 `{taskNo, runNo}` 与 202 同形状。
- [ ] **AC9 W4 事务**（单测）：一次 `begin`、一次 `commit`，「旧行降级」与「新行 `READY`」两条写都在其间；第二次写抛异常 → `rollback` 被触发、`commit` 零次，异常从方法里传出。**边界**：本仓没有内存库可起（无 H2 / Testcontainers），验的是「包裹关系 + 回滚被触发」，不是「MySQL 真的回滚了」—— 收尾报告里写明这一条。
- [ ] **AC10 可选位四处一致**（单测）：落定（无 terms 不抛）、可读判定（无 terms 仍可读）、级联回收（无 terms 仍能标 `DELETED`）、产物清单（无 terms 不丢整版）各至少一条用例；`ArtifactFileKind#isRequiredFor` 的三种类型矩阵有穷举用例。
- [ ] **AC11 旧判据退场**：`SubtitleTranslationCoordinator` 里不再有任何「文件存在 → 跳过翻译」的分支；全仓 grep `translated-segments` 不再出现在复用判据里（组装文件名照旧）；`/artifacts`、下载端点、三个 POST 的响应形状与 S4 结束时一致。
- [ ] **AC12 回归**：`clean test-compile` 过；全量测试绿且条数 ≥ 623（新增测试只增不减）；后端能起服务；从零重建 `backend/data/works*` 后能走通「提交 → 等完成 → 翻译 → `/artifacts` 与下载 → 同语种再翻看 revision 与 mtime 不变」。

## Notes

- **分支**：按 [git-workflow](../../../.trellis/spec/guides/git-workflow.md) 开 feature 分支，从当前 `dev` 切 `feature/subtitle-translate-run`；**完成后合回 `dev`**。
- **测试基线**：S4 收尾静态数 623（`grep -c '@Test\|@ParameterizedTest'`）；开工前先 `rm -rf target/surefire-reports` 再跑全量，以实测数为准。
- **环境命令**：判编译 / 跑测试 / 起服务 / 推送代理见 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。起冒烟实例后按 PID `taskkill`（`TaskStop` 杀不掉子 JVM）。
- **本段结束时哪些东西还没到位**：见 [implement.md](./implement.md) 末尾的欠账清单，实施完必须逐条核对并原样写进收尾报告。
