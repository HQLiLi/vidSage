# 字幕模型重构 6.5：复用不落版（跳过即不换版）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），
> 本文只写本段的范围、取舍与验收，不复述终态、不另开一份设计。
> 段级技术落点与执行顺序见 [implement.md](./implement.md)。
> 前置 S6（`10-17-subtitle-content-change`）已归档合并；本段从 `dev` 切分支。
> **输入**：[handoff-reuse-versioning.md](../../workspace/HeQiu/handoff-reuse-versioning.md) —— 本文吸收它的全部输入，
> 那份文档自此降为**论证留档**（与父 `handoff.md` 同待遇），验收口径以本文为准。

## Goal

把主管线的**「跳过也落一版新产物」**改成**「不复用才落版」**：

1. **复用命中 ⇒ 整条链一个字节不动**：`ORIGINAL` / `CLEANED` 不插新行、不标旧行、不写文件列与哈希列；下游译文不被作废、文件不被删。
2. **`revision` 回归「内容第几版」**：不再随执行次数递增，只在真产出时递增。
3. **`derived_from_run_id` 保持「最后一条真正产出它的 run」**：跳过不刷新。
4. **删掉 `settleCleanedAndSupersedeDownstream` 的 `isContentChanged` 条件作废**：清洗真重跑 ⇒ 无条件作废下游。
5. **复用命中不再补摘上下文**：`ensureUsableContext` 退场，复用命中真正做到零写入。

**一句话判据**：同一 URL 再提交、内容与关键词都没变 ⇒ `subtitle_artifact` 的行（id / revision / status / 八个文件与哈希列）
逐行与提交前**完全一致**，工作区里所有文件 mtime 不变。

## 现状与欠账（代码实证）

| 欠账 | 现状实证 | 本段动作 |
|---|---|---|
| **主管线无条件插两版占位** | `SubtitlePipeline.runPipeline` 里两次 `createCreatingPlaceholder` 都不带条件（[L210-211](../../../backend/src/main/java/com/vidsage/application/subtitle/pipeline/SubtitlePipeline.java#L210-L211) `ORIGINAL`、[L219-220](../../../backend/src/main/java/com/vidsage/application/subtitle/pipeline/SubtitlePipeline.java#L219-L220) `CLEANED`）；跳过复用时照插 | 改成**条件创建**：先问复用判据，不复用才插 |
| **跳过时旧版仍被降级** | `settleArtifact` / `settleCleanedAndSupersedeDownstream` 无条件把旧 `READY` 行标 `SUPERSEDED` + 清空文件列 | 跳过时**不调用落定**，整条链不动 |
| **`revision` 语义 = 「第几次执行产出的」** | 版本号由仓储按同类型 `MAX+1` 生成，与「内容变没变」无关 | 变成「内容第几版」——design §8.5 的版本回退要的正是这个语义 |
| **`derived_from_run_id` 每次复用都刷成本次 run** | 占位入参就是 `run.getId()`；而文件其实是更早那条 run 写的 | 跳过时不刷新，该列注释改准 |
| **`isContentChanged` 条件作废** | [SubtitleArtifactStore.isContentChanged](../../../backend/src/main/java/com/vidsage/application/subtitle/SubtitleArtifactStore.java#L249-L263)：三个文件位哈希逐个比，相同就不作废下游 | **整段删掉**；连带两条存量测试（`settleCleanedKeepsDownstreamWhenContentIsUnchanged` / `settleCleanedTreatsMissingOldHashesAsChangedContent`） |
| **复用命中仍会重写 `context.json`** | [SubtitleCleanupCoordinator.java:186](../../../backend/src/main/java/com/vidsage/application/subtitle/pipeline/SubtitleCleanupCoordinator.java#L186) 在复用分支里调 `ensureUsableContext`；上一趟摘要降级过就重摘并写盘 | 复用分支不再补摘，`ensureUsableContext` 整个删掉 |
| **两条级联的判据因此变浑** | design §5 W5 的注与 §396 那条订正（2026-10-19）**都是为解释本欠账而挂的**：「落新版本 ≠ 内容变了」 | 欠账消掉后，这两处订正同步收敛 |

### 一条订正（2026-10-20）

[handoff-reuse-versioning.md](../../workspace/HeQiu/handoff-reuse-versioning.md) §7 写「**AC2** 现在写的是『文件 mtime 不变 + `ORIGINAL` rev13 → rev14』」——
~~AC2 断言了 revision 递增~~。查证结果：S6 的 AC2（prd 与验收记录都如此）只断言 **mtime 不变 + 中间产物仍 `READY`**；
`rev14` 出现在 S6 的 **AC1** 验收记录里（那是一趟内容真变了的重跑，递增本来就对）。
**「每次落一版」的证据是代码本身**（`runPipeline` 无条件插占位），不是某条 AC —— 所以本段的 AC 直接按代码事实重写，不必去改 S6 的 AC。

## 本段范围

- **管线**：两个占位改条件创建；跳过时取**已有的那条 `READY` 行**当 `original` / `derived_from_artifact_id` 的取值来源。
- **两个阶段类**：复用判据提到公开面，供管线在**建占位之前**先问（`SubtitlePreparationCoordinator` 那一半 = 表完好 + 检查点可解析；`SubtitleCleanupCoordinator` 那一半 = 表完好 + 检查点可解析 + **关键词指纹一致**）。
- **落定方法**：删 `isContentChanged` 那条路径，`settleCleanedAndSupersedeDownstream` 变成「清洗真重跑 ⇒ 无条件作废下游」。
- **清洗复用分支**：不再补摘上下文。
- **文档**：父 `design.md` 的受影响章节（§5 W5 注、§7.3 步骤 3/4、§7.4 步骤 6、§615/§616、§8.5、§396 那条订正）+ S6 的 `prd.md` / `implement.md` 里引用过落版行为的表述。

**本段不新增任何对外契约**：端点、请求/响应形状、SSE 事件一律不动。

## 本段不做

| 不做 | 原因 |
|---|---|
| **把 1 个管线拆成 3 个**（主任务 / 重清洗 / 翻译） | 后续另开任务；「重清洗无条件作废老产物」那条语义由它定稿。本段只把判据删掉，不为它预先造形状 |
| **`CONTEXT` 改成可选文件位**（摘要失败就不落 `context.json`） | 讨论过并**否掉**：带降级标记的 `context.json` 是**完整合法、自描述**的文件（`degraded: true` 就在里面），它是有意义的状态记录。改成可选位会让「这版摘要失败过」在产物清单里**看不出来**，是丢信息不是修约束 |
| **清理存量垃圾行** | 库里已有的 `SUPERSEDED` + 八个文件列全 `NULL` 的行**无害**：没有任何消费者、对账 ②（`SUPERSEDED` 但文件还在）也不会报它（文件列已清空）。本段只保证**不再新增** |
| 只读对账端点 | S7（`10-17-subtitle-reconcile-endpoint`）——**本段是它的前置**，见 Notes |
| 网络调用统一超时 | S8（`10-17-subtitle-hardening-wrapup`） |
| 版本回退 | C52 |
| 前端接新接口 | 另开任务（C60） |
| 删除功能 | C38，范围外 |

## 本段定下的取舍

| # | 取舍 | 理由 | 备选与为什么否掉 |
|---|---|---|---|
| **T1** | 复用判据**提到阶段类的公开面**（如 `hasReusableCheckpoint(...)`），管线先问 → 再决定插不插 → 再让它干活 | 「会不会复用」现在藏在阶段类内部，而占位建在它之前；要让「不复用才插行」成立，答案必须**先于建占位**拿到 | ① 管线照旧先插占位、确认复用时回头删掉那条 `CREATING`：`revision` 已经 `+1`，删行会在 `uk(task_id, artifact_type, revision, lang)` 上**留洞**；② 让阶段类自己插占位：prd T5 已否（阶段类会同时管两张表） |
| **T2** | `ORIGINAL` 与 `CLEANED` **各自独立判断**复用 | 关键词改了 → 清洗必须重跑，而 `ORIGINAL` 完全可以复用；两个门禁的判据本来就不同（后者多一半关键词指纹） | 耦合（一个要重跑就都插新行）：`ORIGINAL` 明明没重算却落一版，正好是本段要消掉的问题 |
| **T3** | 跳过时 `derived_from_run_id` **不动** —— 保持「最后一条真正产出它的 run」 | 比现状更诚实（现状每次复用都把它刷成本次 run，而文件是更早那条 run 写的）；与译文复用路径现状同口径（`SubtitlePipeline.translate` 命中时整行不碰） | 刷成本次 run：与 `intermediate_file.derived_from_run_id`「跟着最近一次产出走」同口径，但要单开一条 UPDATE，且要重新定义「复用算不算产出」。**唯一的消费者是 `markCreatingAsFailedByRunId`（收口把本次 run 的残留 `CREATING` 标 `FAILED`）——跳过时压根没有新行，它是空操作**，所以不动这一列不会漏收口 |
| **T4** | 跳过的 run 在 `subtitle_artifact` 里**零痕迹** | 「这次跑交付了什么」的答案是「没交付新的，沿用上一版」；`task_run` 已有终态可查 | 给 run 加「复用了哪一版」的记录：新列/新表，超出本段。**连带义务**：这条口径必须写进父 `design.md`，否则 S7 对账容易按「每 run 至少一版」写、把正常情况报成缺口 |
| **T5** | 复用命中**不再补摘上下文**，`ensureUsableContext` 删掉 | 清洗（和它那份上下文）的输入只有**原始 SRT + 关键词**；两者都复用命中的话，这一趟没有重算它的理由。降级标记许诺的「下次重试摘要」由**下次真清洗**兑现 | ① 复用命中补摘后同步刷 `context_sha256`：复用不再零写入，要加一条定向 UPDATE 和一个回传；好处只是让翻译侧之后能命中缓存；② 接受 S7 对账 ④ 出现假阳性：④ 的存在意义就是「报了就得真去重跑」，假阳性会把这个信号废掉 |
| **T6** | 删 `isContentChanged`，**清洗真重跑 ⇒ 无条件作废下游** | 跳过不落版之后，能走到这个方法的只剩「真重跑」，判据失去存在理由 —— 少一个判据少一个错口 | 留着它：只为「真重跑但产出逐字节相同」这一个罕见情形服务，而那个情形下「重跑就是想要一份新的」本来就是用户的意图。**连带**：RECLEAN 产出与旧版完全相同时也会作废译文（用户得重新翻） |

> **四个 `sha256` 列仍然要写**（`ORIGINAL` / `CLEANED` / `TRANSLATED` 的换版落定那条 UPDATE 照旧写）——
> 那是 design §8.4 ② 的对账检测用的，与「比哈希决定要不要作废下游」是两件事。本段删的是**判据**，不是哈希列。

## Acceptance Criteria

> 每条都要能机器判或亲手贴输出。「文件 mtime 不变」这类断言按 S5/S6 的做法直接 `ls --time-style=full-iso`。

- [x] **AC1 复用命中不落版（`ORIGINAL`）**：同一 URL 再提交、内容与关键词都没变 → 提交前后各查一次
      `SELECT id, artifact_type, lang, revision, status, derived_from_run_id, segments_file_name, subtitles_file_name, context_file_name, terms_file_name, segments_sha256, subtitles_sha256, context_sha256, terms_sha256 FROM subtitle_artifact WHERE task_id=? ORDER BY id;`
      → **逐行完全一致**（行数、id、revision、status、八个文件/哈希列一个不差）。
- [x] **AC2 复用命中不落版（`CLEANED`）+ 下游不动**：AC1 那一趟里，`CLEANED` 行同样逐行不变；**若该任务有 `READY` 译文，译文行也不变**（不被 `SUPERSEDED`、文件不被删）。日志出现两条断点复用跳过（转写 / 清洗）。
- [x] **AC3 复用命中零写入**：AC1 那一趟前后，除 `task_run`（阶段推进与终态）与 `subtitle_task.updated_at`（run 收尾）之外，
      `subtitle_artifact` / `intermediate_file` / `subtitle_keyword` / `workspace` **一个字段不变**；
      工作区目录里**所有文件** mtime 不变 —— **`context.json` 必须在其中**（这条是 T5 的直接验收）。
- [x] **AC4 真重跑照旧落版**：手工把 `ORIGINAL` 行改成 `SUPERSEDED`（**文件保留**）→ 重提交 → 转写真的重跑，
      落一版新 `ORIGINAL`（`revision` 比原来 `+1`、`derived_from_run_id` = 本次 run），旧行仍是 `SUPERSEDED`。
- [x] **AC5 两个门禁独立判断（T2 边界）**：改关键词后重提交 → `ORIGINAL` **复用**（行逐行不变），
      `CLEANED` 落新版且 `derived_from_artifact_id` 指向**那条仍 `READY` 的旧 `ORIGINAL`**；基于旧清洗的译文被作废。
- [x] **AC6 级联判据已删（T6）**：RECLEAN 之后，`READY` 译文**一律被作废**（不因「产出与旧版相同」而保留）。
      真库验收走「RECLEAN 后译文消失」；「新旧哈希完全相同仍作废」这条由**单测**覆盖（真库难复现逐字节相同）。
- [x] **AC7 首跑不受影响**：新 URL 首跑 → `ORIGINAL` rev1 / `CLEANED` rev1 落定，与现状一致。
- [x] **AC8 内容变更不受影响**：改内容重提交 → 旧产物全 `SUPERSEDED`、中间产物按 S6 口径处理、主管线真重跑（S6 的 AC3/AC4 回归）。
- [x] **AC9 回归**：`clean test-compile` 过；全量测试绿；后端能起服务；一次完整流程（提交 → 产物 → 翻译 → 再提交 → 重新生成字幕）走通。
- [x] **AC10 文档一致**：父 `design.md` 里不再有「跳过也落一版新 revision」的表述（§5 W5 注、§7.3 步骤 3/4、§7.4 步骤 6、§615/§616、§8.5、§396 那条订正）；
      S6 的 `prd.md` / `implement.md` 里引用过落版行为的表述已按订正惯例处理。

## Notes

- **测试基线**：本任务开始时静态数 `@Test`/`@ParameterizedTest` = **681**（与 S6 归档时一致）。**动手前重量一次**。
- **硬顺序**：本段是 **S7 的前置**。S7 的对账判据（尤其第 ④ 类「表 `READY` 但文件哈希对不上」与「每 run 至少一版」的隐含假设）
  建立在本段的口径上 —— 本段先落地，S7 才不会把正常情况报成缺口。S7 的 `prd.md` 里要补写这条前置。
- **两条级联的时序差异不变**（design §7.3 已写死）：重洗时旧产物在整个运行期间仍可下载；指纹变了时该 task 在整个主管线期间零 `READY`。本段**不动**它。
- **`markSupersededAndClearFiles` 清空八个列这件事不变**：文件名不带 `revision`、物理上每 `(type, lang)` 只留一份（C24）。
- **脱敏红线不变**：`source_uri`（URL 原文 / 上传原始文件名）不得写日志；本段不新增日志，但改动的 Javadoc 不得引入目录名或路径。
- 手工验收用的 SQL / curl 只进 `implement.md` 的验收段，**不进代码库**。
- 环境命令（判编译、跑测试、起服务、推送代理）见父 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。
