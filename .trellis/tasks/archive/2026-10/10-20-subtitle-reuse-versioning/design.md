# 复用不落版：技术设计

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。**
> 本文件只写本段的**技术落点**：边界、契约形状、数据流、技术取舍、兼容与回滚。
> 范围与验收见 [prd.md](./prd.md)；执行顺序与验证命令见 [implement.md](./implement.md)。
> **本文件是执行参考稿，不是契约**：与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。

---

## 1. 边界：本段只动三处

| | 内容 |
|---|---|
| **动** | ① 两个阶段类的**复用判据**从内部提到公开面，真跑入口收窄签名；② `SubtitlePipeline.runPipeline` 的两个占位改**条件创建**；③ `SubtitleArtifactStore` 删 `isContentChanged` |
| **不动** | 六张表 DDL、`schema.sql`、任何端点与 DTO、SSE 事件形状、`SubtitleTranslationCoordinator`（它的复用路径**已经是**本段要的形状，本段只让 ORIGINAL / CLEANED 对齐它）、`SubtitleFingerprintCoordinator` 与 W5′ 级联、`SubtitleTaskService` / `SubtitleArtifactQueryService`（读路径只认 `READY`） |
| **净效果** | `revision` 从「第几次执行产出的」变成「内容第几版」；跳过复用的 run 在产物表零痕迹 |

**为什么只动这三处**：「会不会复用」这个判据现在**藏在阶段类内部**，而占位建在它之前 —— 要让「不复用才插行」成立，答案必须先于建占位拿到。所以判据上移（阶段类），决策点上移（管线），落定侧的旧判据跟着失去理由（store）。

---

## 2. 数据流：改造前 vs 改造后

### 2.1 改造前（第 2 趟 MAIN、内容与关键词都没变）

```
指纹阶段：内容未变化 → return（不级联）
阶段 1：findIntactArtifact(ORIGINAL) → 有 → originalIntact = true
        ★ 无条件插占位（revision +1）
        prepare(..., originalIntact=true) → 检查点命中 → 跳过转写
        settleArtifact → 旧 ORIGINAL 标 SUPERSEDED、新行 READY（指向同一批文件）
阶段 2：findIntactArtifact(CLEANED) → 有 → cleanedIntact = true
        ★ 无条件插占位（revision +1）
        clean(..., allowCheckpointReuse=true) → 检查点命中 → 跳过清洗（但可能补摘上下文并写盘）
        settleCleanedAndSupersedeDownstream → 旧 CLEANED 标 SUPERSEDED、新行 READY
                                             → 内容没变 → 下游译文不动
```

**结果**：文件一个字节没动，库里多两行 + 两行被降级；`revision` 白涨一。

### 2.2 改造后

```
指纹阶段：内容未变化 → return（不变）
阶段 1：intactOriginal = findIntactArtifact(ORIGINAL)
        reused = preparation.reuseIfAvailable(workDir, intactOriginal.isPresent(), listener)
        ├─ 命中 → original = 那条已有的 READY 行（不插行、不落定、不碰文件）
        └─ 未命中 → 插占位 → prepare(...) → settleArtifact
阶段 2：intactCleaned = findIntactArtifact(CLEANED)
        reused = cleanup.reuseIfAvailable(workDir, keywords, intactCleaned.isPresent(), listener)
        ├─ 命中 → 什么都不做（不插行、不落定、下游译文一个字节不动）
        └─ 未命中 → 插占位（derived_from_artifact_id = original.getId()）→ clean(...)
                    → settleCleanedAndSupersedeDownstream（无条件作废下游）
```

**结果**：命中那一趟除 `task_run` 与 `subtitle_task.updated_at` 之外**零写入**。

**两个门禁各自独立**：ORIGINAL 命中而 CLEANED 未命中（关键词改了）是正常路径 —— 此时 CLEANED 的新行 `derived_from_artifact_id` 指向**那条仍 `READY` 的旧 ORIGINAL**。

---

## 3. 契约：方法形状

> **过审时定下的两条口径**（2026-10-20，过审门）：
>
> - **`reuseIfAvailable` 收一个 `ProgressListener`**。命中时那几条 SKIPPED 事件走 `StageEvents.emit`，
>   而 listener 是每 run 一份、由管线传下来的，阶段类是单例、自己拿不到。**否决「管线拿 `Optional` 后自己发事件」**：
>   阶段名与三条文案要搬到管线，判据与事件被拆到两个类，正是 D1 要避免的漂移面。
> - **开始事件的文案挂在 `requireContextSummary` 上**（见 §3.2 末）。它是代理，不是「是不是重清洗」这个概念本身 ——
>   本段不为此新造参数（prd「本段不做」第一条：不为管线拆分预先造形状）。

### 3.1 `SubtitlePreparationCoordinator`

```java
/**
 * 能不能复用原始转写：产物表说完好（artifactIntact），且盘上 segments 检查点解析得出来。
 * 命中时交出那份产出（含 SRT 字节数），并广播两条 SKIPPED（转写 / 写盘）。
 * 调用方据此决定插不插占位 —— 所以这条判据必须先于建占位拿到。
 */
public Optional<SubtitleStageOutput> reuseIfAvailable(Path workDir, boolean artifactIntact,
                                                      ProgressListener listener);

/**
 * 真的产出一份字幕：要音频 → 转写 → 写两个产物。
 * 只在 reuseIfAvailable 没命中时调用；不再自带复用判据，也不再回头读文件检查点。
 */
public SubtitleStageOutput prepare(MediaInput input, Path workDir, ProgressListener listener,
                                   IntermediateFileOwner owner);
```

**删除**：`prepare(input, workDir, boolean reuseCompletedOriginal, owner)` 与带 `reuseCompletedOriginal` 的重载。

### 3.2 `SubtitleCleanupCoordinator`

```java
/**
 * 能不能复用清洗产物：产物表说完好（artifactIntact），且盘上 -clean 检查点解析得出来、
 * 文件里记的关键词指纹与当前一致。命中时交出那份产出，并广播一条 SKIPPED。
 * 命中时**不碰 context.json**（那份摘要的兑现点挪到下次真清洗）。
 */
public Optional<CleanedSubtitle> reuseIfAvailable(Path workDir, SubtitleKeywords keywords,
                                                  boolean artifactIntact, ProgressListener listener);

/**
 * 真的清洗一份字幕：切分 → （读上下文缓存或现摘要）→ 纠错 → 写两个 -clean 产物。
 *
 * @param mayUseContextCache 产物表里有没有完好的清洗产物；false 时连上下文缓存都不读
 *                           —— 那份摘要也是旧内容算出来的（原参数名 allowCheckpointReuse
 *                           已名不副实：本方法不再复用任何检查点）
 */
public CleanedSubtitle clean(MediaInput input, Path workDir, SubtitleKeywords keywords,
                             ProgressListener listener, boolean mayUseContextCache);

/** 按当前关键词重新生成（RECLEAN）：跳过检查点、摘要失败即整体失败。签名不变。 */
public CleanedSubtitle regenerate(MediaInput input, Path workDir, SubtitleKeywords keywords,
                                  ProgressListener listener);
```

**删除**：`ensureUsableContext`（私有方法 + 调用点）。

**`doClean` 私有签名收窄**：`(input, workDir, keywords, listener, allowCheckpointReuse, forceReClean, requireContextSummary)`
→ `(input, workDir, keywords, listener, mayUseContextCache, requireContextSummary)`。

- `forceReClean` 消失：skip 分支已搬进 `reuseIfAvailable`，`regenerate` 与 `clean` 的差别只剩 `requireContextSummary`。
- `current`（检查点解析得出来 + 指纹一致）**保留**，但用途收窄成「上下文缓存能不能用」；判定条件是 `mayUseContextCache && current`。
- `regenerate` 传 `mayUseContextCache=true`（RECLEAN 的前置就是「有完好的 CLEANED」，由 `SubtitleTaskService.requireIntactArtifact` 保证）。

**开始事件的文案**：现在写的是 `forceReClean ? "开始重新生成字幕" : "开始清洗"`，`forceReClean` 消失后改判
`requireContextSummary`（删掉 `forceReClean` 之后，`clean` 与 `regenerate` 进 `doClean` 的差别只剩它），
文案与条数一字不变。**这是代理**：该布尔的名字说的是「摘要失败要不要整体失败」，与「这是重清洗」不是一回事。
真判据（「是不是重清洗」）在本类里根本不存在，本段也不造 —— 等管线拆成三条时，重清洗是独立管线，
「我是谁」由管线身份自带，这个参数会变成多余的一层。

### 3.3 `SubtitlePipeline.runPipeline`

```java
// 阶段 1：先问复用，再决定插不插
Optional<SubtitleArtifact> intactOriginal = artifactStore
        .findIntactArtifact(run.getTaskId(), ArtifactType.ORIGINAL, "", workDir);
Optional<SubtitleStageOutput> reusedOriginal = subtitlePreparationCoordinator
        .reuseIfAvailable(workDir, intactOriginal.isPresent(), listener);

SubtitleArtifact original;
if (reusedOriginal.isPresent()) {
    // 复用命中：产物表那一侧必然有这条完好行（判据要求它先成立），整条链一个字节不动
    original = intactOriginal.orElseThrow(() -> new IllegalStateException(
            "产物表说没有完好的原始产物，准备阶段却报告可复用：判据不一致"));
} else {
    original = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
            ArtifactType.ORIGINAL, "", null, null);
    subtitlePreparationCoordinator.prepare(input, workDir, listener, owner);
    original = artifactStore.settleArtifact(original, workDir);
}

// 阶段 2
SubtitleKeywords keywords = keywordRepository.findByTaskId(run.getTaskId());
Optional<SubtitleArtifact> intactCleaned = artifactStore
        .findIntactArtifact(run.getTaskId(), ArtifactType.CLEANED, "", workDir);
if (cleanupStage.reuseIfAvailable(workDir, keywords, intactCleaned.isPresent(), listener).isEmpty()) {
    SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
            ArtifactType.CLEANED, "", original.getId(), keywords.computeFingerprint());
    cleanupStage.clean(input, workDir, keywords, listener, intactCleaned.isPresent());
    artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
}
// 命中：不插行、不落定，下游译文与它们的文件一个字节不动
```

**`original` 变量的两个来源**（这正是 handoff §5 点名的难点）：命中时是**那条已有的 `READY` 行**，未命中时是刚落定的新行。两种情况下 `original.getId()` 都是「当前有效的原始产物 id」。

**`regenerate` / `translate` 两条路径不动**（RECLEAN 恒真重跑；TRANSLATE 的复用命中本来就不落版）。

### 3.4 `SubtitleArtifactStore`

```java
public void settleCleanedAndSupersedeDownstream(SubtitleArtifact creating, Path workDir) {
    ArtifactFiles files = resolveArtifactFiles(creating.getArtifactType(), creating.getLang(), workDir);

    List<SubtitleArtifact> translationsToReclaim = transactionTemplate.execute(status -> {
        // 旧行必须先读出来：markSupersededAndClearFiles 要它的 id
        repository.findReadyByTaskIdAndTypeAndLang(creating.getTaskId(), creating.getArtifactType(),
                        creating.getLang())
                .ifPresent(previous -> repository.markSupersededAndClearFiles(previous.getId()));

        // 清洗真重跑 ⇒ 下游译文一律作废（「内容没变就不作废」那条判据随「跳过不落版」一起退场）
        // 先读出来：事务提交后要按它们的文件列删文件。改状态另走一句批量，不逐行。
        List<SubtitleArtifact> translationsToSupersede = findDownstreamTranslations(creating.getTaskId());
        if (!translationsToSupersede.isEmpty()) {
            repository.markSupersededByTaskIdAndType(creating.getTaskId(), ArtifactType.TRANSLATED);
        }

        markReady(creating, files);
        return translationsToSupersede;
    });

    for (SubtitleArtifact translation : translationsToReclaim == null
            ? List.<SubtitleArtifact>of() : translationsToReclaim) {
        reclaimSuperseded(translation, workDir);
    }
}
```

**删除**：私有方法 `isContentChanged`；「旧行必须在清列之前读：清完文件列与哈希列，就再也比不出『内容变了没有』」那条注释（理由变了，但**读还在**，因为要 id）。

**`settleArtifact` 只改 Javadoc**：语义从「每次落版」收窄成「**只在真产出之后调用**；跳过复用时不调用」。方法体不动。

**四个 `sha256` 列照旧写**：那是父设计 §8.4 ② 的对账检测用的，与「比哈希决定要不要作废下游」是两件事。本段删的是**判据**，不是哈希列。

---

## 4. 技术取舍

| # | 选了 | 理由 | 否掉的备选 |
|---|---|---|---|
| **D1** | 阶段类暴露 `reuseIfAvailable(...)`，**一次调用同时回答「能不能复用」并交出复用结果** | ① 检查点**只解析一次**（一小时视频的 `-segments.json` 几百 KB，读两遍白花）；② 顺带把 `prepare` / `clean` 的复用参数**删掉**，「复用」与「真跑」在调用点一眼分得开；③ 「判据」与「跳过分支」是同一段代码，不会各自漂移 | ① **纯谓词 + 真跑入口再读一遍**：判据与跳过分支各自读同一文件，多一处漂移面，且白解析一次；② **返回 record 带 `mayUseContextCache`**：把两件不相干的事（能不能复用 / 缓存能不能读）塞进一个返回值，`reuseIfAvailable` 的契约会变浑 |
| **D2** | 复用判据的**输入**是管线算好的布尔值（`intactOriginal.isPresent()` / `intactCleaned.isPresent()`） | 产物表的完好判定归产物门的职责（`findIntactArtifact`），阶段类只回答「盘上检查点这一半」；这是 S6 定下的分工（prd T5） | 让阶段类注入 `SubtitleArtifactStore`：阶段类会同时管两张表（prd T5 已否） |
| **D3** | 命中时**不调用**落定方法，而不是「先插后删」 | `revision` 已 `+1`，删行会在 `uk(task_id, artifact_type, revision, lang)` 上**留洞**（prd T1） | 先插占位、确认复用时回头删掉那条 `CREATING` |
| **D4** | `clean` 的 `allowCheckpointReuse` **改名** `mayUseContextCache` 而不是删掉 | 它的另一半职责（决定上下文缓存能不能读）**还在**：产物表说不能复用时，盘上那份 `context.json` 是旧内容算出来的，绝不能读 | 一并删掉：`clean` 会在「产物表拒绝复用但检查点指纹恰好一致」时读到过期摘要 |
| **D5** | `reuseIfAvailable` 命中时**照旧广播 SKIPPED 阶段事件** | 阶段事件的文案与条数不变 → SSE / 前端表现不变；本段不新增契约 | 不发事件：用户会看到进度条卡住不动 |

---

## 5. 边界矩阵

| 场景 | 表现 |
|---|---|
| 内容未变 + 关键词未变 + 两版产物都完好 | 两个门禁都命中 → **零落版**；`subtitle_artifact` 逐行不变；所有文件 mtime 不变（含 `context.json`） |
| 内容未变 + 关键词改了 | ORIGINAL 命中（不落版）；CLEANED 未命中（指纹不符）→ 落一版新 CLEANED，`derived_from_artifact_id` = **那条仍 READY 的旧 ORIGINAL**；下游译文无条件作废 |
| 内容未变 + 手工把 ORIGINAL 行打成 `SUPERSEDED`（文件保留） | ORIGINAL 未命中 → 真重跑、落新版；CLEANED 那半**仍可能命中**（检查点还在、指纹还一致）→ 不落新版。**已知窗口**，见下 |
| 内容变了（指纹级联） | 级联把旧 artifact 全标 `SUPERSEDED` → 两个门禁都不命中 → 真重跑、各落新版（S6 行为不变） |
| 首跑（无旧产物） | 两个门禁都不命中 → 各落 `rev1` |
| 产物行 `READY` 但文件被手工删了 | `findIntactArtifact` 返回空（WARN，不改状态）→ 未命中 → 真重跑、落新版 |
| 产物行 `READY`、文件在、但检查点 JSON 解析不动 | 未命中 → 真重跑、落新版（不拿坏检查点当命中） |
| 摘要上次降级过（`context.json` 带 `degraded: true`） | 复用命中 → **不补摘、不写盘**；降级标记留到下次真清洗时被正常摘要覆盖 |
| 跳过复用的那趟 run 失败了（理论上不会：跳过不发外部调用） | 无新占位行 → `markCreatingAsFailedByRunId` 空操作；旧行仍是 `READY` |
| 跳过复用的那趟 run 成功 | 产物表零痕迹；`task_run` 落 `SUCCEEDED`；`subtitle_task.updated_at` 刷新 |
| RECLEAN（真重跑清洗） | 落一版新 CLEANED，**无条件**作废下游译文（判据已删）；产出与旧版逐字节相同也照样作废 |
| 同一 task 并发 MAIN | 撞 `uk_active_run` → 409（不变；并发不归本段处理） |

**已知窗口（不做，记账）**：「ORIGINAL 真重跑 + CLEANED 复用命中」这一格。父设计 handoff 的 O4 说这种组合「在内容变更级联下不可达」——**只在级联那条路上不可达**；手工把 ORIGINAL 行打成 `SUPERSEDED`（S6 的 AC6a 就是这么验的）时**可达**。此时 CLEANED 的内容是基于**旧转写**算的，而 ORIGINAL 已经是新的一版。
现状同样存在这个窗口（改造前 CLEANED 会落新版、血缘指向新 ORIGINAL，但**内容**仍来自旧转写 —— 那样连血缘都是假的）；改造后至少血缘诚实（指向那条旧 ORIGINAL）。**本段不修**，理由：要修就得让「ORIGINAL 真重跑 ⇒ CLEANED 强制重跑」，那是给两个门禁加耦合，与 T2 的独立判断相冲，而触发路径只有「人工改行」或「检查点损坏」两种。

---

## 6. 兼容与回滚

- **无表结构变更、无端点变更、无 DTO 变更、无 SSE 事件变更** → 彻底回退就是 `git revert` 本段提交，不需要 DROP / 重建。
  （唯一的 DDL 面改动是 `derived_from_run_id` 的**列注释**，`schema.sql` 里改字面、不动列与约束；真库要等下次重建才看到新注释，
  `CREATE TABLE IF NOT EXISTS` 不会去改已存在的表。）
- **旧数据照旧可读**：读路径只认 `READY`（`findIntactArtifact` / `requireIntactArtifact`），本段不改它。
- **存量 `SUPERSEDED` 垃圾行不回填、不清理**：它们文件列已清空、没有任何消费者，对账 ②（`SUPERSEDED` 但文件还在）也不会报它。本段只保证**不再新增**。
- **前端不受影响**：契约一个字节没动。
- **回退后的行为**：恢复「跳过也落一版」。已按新口径产生的行（`revision` 不连续）在旧口径下仍然合法 —— `revision` 只要求同 `(task,type,lang)` 内 `MAX+1`，不要求连续。

---

## 7. 存量测试改造清单

> 过审门时按实际代码把清单补全了：初稿漏了 6 条（协调器里凡走跳过分支的用例，都随分支搬走）。

| 测试 | 处置 | 原因 |
|---|---|---|
| `SubtitleArtifactStoreTest.settleCleanedKeepsDownstreamWhenContentIsUnchanged` | **删** | 断言的正是被删掉的判据 |
| `SubtitleArtifactStoreTest.settleCleanedTreatsMissingOldHashesAsChangedContent` | **删** | 「旧行没记哈希就算变了」这条规则随判据一起消失；级联已无条件 |
| `SubtitleCleanupCoordinatorTest.reuseRewritesMissingContextWithoutRerunningCleanser` | **删** | 断言复用分支补摘并写盘 —— 正是 T5 要删的动作 |
| `SubtitleCleanupCoordinatorTest.reuseResummarizesWhenCachedContextIsDegraded` | **删** | 同上（这次是「上次降级过就重摘」那条） |
| `SubtitlePreparationCoordinatorTest.checkpointHitsWhenSegmentsJsonExists` | **改** | 走 `reuseIfAvailable`，并断言 `prepare` 不再被调用 |
| `SubtitlePreparationCoordinatorTest.ignoresFileCheckpointWhenArtifactIsNotReusable` | **改** | 改成断言 `reuseIfAvailable(…, false)` 返回空 |
| `SubtitlePreparationCoordinatorTest.srtAloneDoesNotCountAsCheckpoint` | **改** | 检查点判定上移，改成断言「只有 SRT 时 `reuseIfAvailable` 返回空」 |
| `SubtitlePreparationCoordinatorTest.emptyTranscriptionProducesZeroByteSrtAndValidJson` | **改** | 仅签名：`prepare` 少一个参数 |
| `SubtitleCleanupCoordinatorTest.cleanReusesCheckpointSkipsCleanser` | **改** | 跳过分支搬走，改走 `reuseIfAvailable` |
| `SubtitleCleanupCoordinatorTest.cleanCheckpointHitEmitsSingleSkippedEvent` | **改** | 同上；那条 SKIPPED 事件的断言随之搬过去 |
| `SubtitleCleanupCoordinatorTest.cleanSkipsCheckpointOnlyWhenFingerprintMatches` | **改** | 同上（指纹那一半） |
| `SubtitleCleanupCoordinatorTest.emptyTranscriptionWritesNonEmptyCheckpointDespiteZeroByteSrt` | **改** | 二跑那半原本靠 `clean` 自己跳过；`clean` 恒真跑后不改就会变红 |
| `SubtitleCleanupCoordinatorTest.cleanRerunsWhenFingerprintDiffers` | **改** | 现在仍绿，但门禁已不在此 —— 断言点迁到 `reuseIfAvailable` |
| `SubtitleCleanupCoordinatorTest.cleanRerunsWhenArtifactGateSaysNotReusable` | **改** | 同上（`mayUseContextCache=false` 那一半） |
| `SubtitlePipelineTest.wrapsEachStageWithArtifactPlaceholderAndSettle` | **改** | 未命中路径才插占位；改名或拆成命中 / 未命中两条 |
| `SubtitlePipelineTest.passesArtifactReadabilityToPreparationAndCleanup` | **改** | 传下去的布尔值语义变了（`prepare` 不再收它；`clean` 收的是 `mayUseContextCache`） |

**新增用例**（要点，具体写法见 implement.md 步骤 1/2）：

- 管线：命中 ORIGINAL → **不插占位、不落定**；命中 CLEANED → **不插占位、不落定、不调 `settleCleanedAndSupersedeDownstream`**
- 管线：ORIGINAL 命中 + CLEANED 未命中 → 新 CLEANED 的 `derived_from_artifact_id` = 那条旧 ORIGINAL 的 id
- 管线：判据不一致（`intactOriginal` 为空但 `reuseIfAvailable` 报命中）→ 抛 ISE
- 协调器：`reuseIfAvailable` 命中时 **`context.json` 一个字节不动**（T5 的直接钉法）
- store：旧行哈希与新版**完全相同**时，下游**仍被**作废（AC6 的单测钉法）
