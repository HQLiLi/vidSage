# S5 执行计划：翻译（TRANSLATE run + 复用判据）

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。**
> 本文件写 S5 的**段级技术落点 + 执行顺序 + 验证方式**（父任务章程：每段具体做法写在各子任务的 `implement.md`）。
> **本文件是执行参考稿，不是契约**：发现与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。
> 范围与验收见 [prd.md](./prd.md)；本段对父设计的两处订正见 prd「本段对父设计的订正」。

## 0. 与 S4 实际落地代码的校准

下表是 S5 开头必须按「实际代码」而不是「父设计措辞」来写的地方（2026-10-19 逐项对照 `backend/src/main` 核对）：

| # | 父设计 / 早期草稿的说法 | 实际代码（S4 落地后） | 对 S5 的影响 |
|---|---|---|---|
| 1 | 翻译的 W4 换版包同一事务 | `SubtitleArtifactStore#settleArtifact` **不包事务**；S4 的校准表第 2 条还写着「不要给 `settleArtifact` 加事务」 | S5 **推翻** S4 那条校准（prd T4）：W4 的两步 UPDATE 统一包事务。S4 当时的「不加」是为了缩小改动面；父设计 §7.5 对翻译明确要求事务，且 `ORIGINAL` 走同一个写集，没有理由保留更弱的形态 |
| 2 | `TRANSLATED` 带 `segments` + `subtitles` + `terms` | `resolveArtifactFiles` 按 `belongsTo` **要求每个位都在**、逐个算哈希；**terms 缺一个就抛 ISE** | S5 把 `TERMS` 改成可选位（prd T5）。否则同语种翻译 / 无术语 / 查证未启用 / 全部失败这四条正常路径会让 run 直接 `FAILED` |
| 3 | 产物可读只看状态 | `isReadable` 要求**每个 belong 位**都登记且文件在；`SubtitleArtifactQueryService#snapshotOf` 任一位为空就**丢掉整版** | S5 两处都认可选位；否则无 terms 的译文行永远不出现在 `/artifacts`，复用判据也永远不命中 |
| 4 | 级联回收按文件列删 | `reclaimFilesOf` 把「文件列为 `NULL`」当异常，返回 `false` → 行停在 `SUPERSEDED` | S5 改成「可选位未登记 = 正常跳过」；否则真实译文行的级联永远标不了 `DELETED`（S4 用手工造的三文件行没踩到） |
| 5 | 复用判据查产物表 | `SubtitleTranslationCoordinator#reuseCheckpointIfPresent` 按**文件存在**跳过翻译，并自己发一条 `SKIPPED` 事件 | S5 删掉这段（prd T2），判据搬到管线；`SKIPPED` 事件由管线补（prd T7） |
| 6 | 管线调用前要先落占位 | `SubtitlePipeline#translate` 只调阶段 + `finish`，**一行 artifact 不写**；`regenerate` 是先插占位再跑阶段 | 翻译**反过来**：翻译成功之后才插占位（prd T3，父设计 §7.5 步骤 4→5）。LLM 失败时库里一条垃圾行都不留 |
| 7 | 前置守卫交出 `CLEANED` 的 id | `SubtitleTaskService#translate` 调了 `requireReadableArtifact(CLEANED)` 但**丢掉返回值** | S5 把 `getId()` 传给管线（prd T6），与 `regenerate` 交 `ORIGINAL` id 同款 |
| 8 | 收口能收拾占位行 | `TaskRunSupervisor#finish` 已调 `markCreatingAsFailedByRunId(run.getId())`（S3 补的），与 run 终态落库分成两段 try | **不碰 `TaskRunSupervisor`**；翻译占位行在落定失败时天然被收掉 |
| 9 | 翻译端点要新加 | `POST /{taskNo}/translate`、`TranslationRequest`、`GET /api/languages` 全部已就位；`/artifacts` 与下载端点已支持 `TRANSLATED` + `lang` | S5 **不加任何端点 / DTO** |
| 10 | 事务用 `@Transactional` | `src/main` 里 `@Transactional` 出现次数 = **0**；`SubtitleArtifactStore` 构造里已有 `TransactionTemplate`（S4 为 W5 加的）；store 的事务边界靠单测注入 `RecordingTransactionManager` 直接构造对象来钉（全仓只有 2 个 Spring 上下文测试） | **这不是仓库禁用注解**。选型规则：外部 bean 调用 + 事务段是完整方法 + 无需事务外慢 IO → 优先 `@Transactional`；本处哈希要留事务外、且边界要在裸单测里可见 → 用模板（prd T4）。S5 复用同一个模板，不新建依赖 |
| 11 | 语种码怎么落 | `SubtitleArtifact` 的 `lang` 存规范码（`zh-Hans`）；文件名由 `SubtitleArtifactSpec#getFileName` 经 `Bcp47LanguagePolicy` 转小写（`translated-segments-zh-hans.json`） | 占位 / 查询 / 复用判据一律用 `target.getCode()`；不要手拼文件名 |
| 12 | 翻译阶段落不落 terms | `verifyTermsIfNeeded` 的四条跳过路径都不写 terms；「全部查证失败不落空产物」是刻意的（空壳会被 `CheckpointPolicy.isCompleted` 当成已查证） | T5 的根因；**不要**为了让产物有第三列而写空 terms |

**结论**：S5 的改动面集中在五处 —— `ArtifactFileKind` 加「必需 / 可选」、`SubtitleArtifactStore` 的四个方法认可选位 + `settleArtifact` 加事务、`SubtitleArtifactQueryService` 的清单认可选位、`SubtitlePipeline#translate` 接线、`SubtitleTranslationCoordinator` 删掉文件断点复用。仓储 / SQL / 状态机 / 端点 / DTO 都不动。

---

## 段内顺序（六步）

```
0  预期文件树与关键形状过审（门）
1  可选文件位：ArtifactFileKind + store 三处 + query 一处（纯地基，行为对现有类型不变）
2  settleArtifact 包事务（W4 的两步 UPDATE）
3  ★ 翻译接线：复用判据 + 成功后落占位 + W4 落定；coordinator 删文件断点复用（行为拐点）
4  前置守卫交出 CLEANED id（编译耦合的小改）
5  文档同步（父 design.md 两处订正）
6  全量验证 + 手工验收 + 欠账核对
```

**顺序的理由**

- **步骤 1 是纯地基**：现有三类产物的行为不变（`ORIGINAL` / `CLEANED` 没有可选位；`TRANSLATED` 还没有代码产出），可以先单独绿。
- **步骤 2 是纯行为加固**：`ORIGINAL` 的落定多一个事务边界，没有可观察的坏处；单测能单独验「一次 begin / 一次 commit」。
- **步骤 3 是唯一的硬拐点**：复用判据、占位时机、W4 落定、coordinator 删代码必须同批 —— 判据搬了一半会同时存在两套，正是 prd T2 要消灭的形态。
- **步骤 4 与步骤 3 编译耦合**（改签名），但语义是纯传递，单独可读。
- **步骤 5 / 6 各自独立**，都绿。

---

## 步骤 0：预期文件树与关键形状过审（门）

**这是一道门，不是准备工作。** 先把下面的文件树、§0.2 的方法形状给项目所有者过一眼，**点头之后才开分支、才写第一行代码**（先例：S1–S4 的同一道门）。

过审时要特别确认的四件事：

1. **复用判据 = `findReadableReady`**（READY 行 + 应带文件位可读），不是字面的「只查 status」。
2. **`TERMS` 对 `TRANSLATED` 是可选位**，四处读取方都要认（这是对父设计的订正）。
3. **占位行在翻译成功之后插**，不是像 `regenerate` 那样先插。
4. **`settleArtifact` 统一包事务**（推翻 S4 校准表第 2 条）。

### 0.1 预期文件树（S5 结束后，只列本段碰的）

```
backend/src/main/java/com/vidsage/
├── domain/subtitle/model/
│   └── ArtifactFileKind.java                        [改] + isRequiredFor（TERMS 对任何类型都不必需）
├── application/subtitle/
│   ├── SubtitleArtifactStore.java                   [改] settleArtifact 包事务；
│   │                                                    resolveArtifactFiles / isReadable / reclaimFilesOf 认可选位
│   ├── SubtitleArtifactQueryService.java            [改] snapshotOf 认可选位，不丢整版
│   └── pipeline/
│       ├── SubtitlePipeline.java                    [改] translate 加 cleanedArtifactId；复用判据；成功后占位 + 落定
│       └── SubtitleTranslationCoordinator.java      [改] − reuseCheckpointIfPresent 及调用；类注释同步
└── application/task/
    └── SubtitleTaskService.java                     [改] translate 把 CLEANED 的 id 交给管线

backend/src/test/java/com/vidsage/
├── domain/subtitle/model/ArtifactFileKindTest.java                    [改] isRequiredFor 矩阵
├── application/subtitle/SubtitleArtifactStoreTest.java                [改] 可选位落定 / 可读 / 回收；settleArtifact 事务
├── application/subtitle/SubtitleArtifactQueryServiceTest.java         [改] 无 terms 的版本仍列出；terms 下载 404
├── application/subtitle/pipeline/SubtitlePipelineTest.java            [改] 翻译复用 / 新建 / 失败
├── application/subtitle/pipeline/SubtitleTranslationCoordinatorTest.java [改] 删掉断点复用两条用例
└── application/task/SubtitleTaskServiceTest.java                      [改] 管线收到 CLEANED id

.trellis/tasks/10-15-subtitle-task-model-refactor/design.md            [改] §1.4 文件组合表 + §7.5 步骤 3（见步骤 5）
```

**不碰**：`schema.sql`、`SubtitleArtifactRepository` / Mapper / MyBatis 仓储、`TaskRunSupervisor`、三个 POST 的 DTO 与 controller、`SubtitleArtifactSpec`、`SupportedLanguage`、`SubtitleArtifact` 的状态机。

### 0.2 新增 / 改动的方法形状

**`ArtifactFileKind`（加一个判定）**

```java
/**
 * 这种产物类型要求这个文件位必须存在吗？
 *
 * <p>必需位缺文件 = 这一版没产出完，落定要失败、可读判定要按没有处理；
 * 非必需位缺文件 = 正常形态（术语查证没产出就没有），只影响 files 里少一项。
 */
public boolean isRequiredFor(ArtifactType type) {
    return switch (type) {
        case ORIGINAL -> this == SEGMENTS || this == SUBTITLES;
        case CLEANED -> this == SEGMENTS || this == SUBTITLES || this == CONTEXT;
        case TRANSLATED -> this == SEGMENTS || this == SUBTITLES;   // TERMS 可选
    };
}
```

`belongsTo` 语义不变：这个类型**可以**有的文件位（`TERMS` 仍属于 `TRANSLATED`，只是不强制）。

**`SubtitleArtifactStore`（不改签名，改内部）**

```java
// 签名不变；算哈希仍在事务外，事务只包「降级旧版 + 置新行 READY」
public SubtitleArtifact settleArtifact(SubtitleArtifact creating, Path workDir)

// 内部：必需位缺文件仍抛 ISE；可选位没有普通文件就跳过（文件名与哈希都留 null）
private static ArtifactFiles resolveArtifactFiles(ArtifactType type, String lang, Path workDir)

// 内部：必需位必须登记且文件在；可选位登记了才要求文件在
private boolean isReadable(SubtitleArtifact artifact, Path workDir)

// 内部：可选位未登记 = 正常跳过；必需位未登记仍记 WARN 并返回 false
private boolean reclaimFilesOf(SubtitleArtifact artifact, Path workDir)
```

**`SubtitleArtifactQueryService`（只改组装）**

```java
// 可选位未登记 → 少一个 files 项；必需位未登记 → 整版不列
private Optional<ArtifactSnapshot> snapshotOf(SubtitleArtifact artifact, Path workDir)
```

**`SubtitlePipeline`（签名 + 执行体）**

```java
public void translate(TaskRun run, RunRef ref, Workspace workspace,
                      SupportedLanguage source, SupportedLanguage target, long cleanedArtifactId)
```

**`SubtitleTaskService#translate`（一处传递）**

```java
SubtitleArtifact cleaned = requireReadableArtifact(task, workspace, ArtifactType.CLEANED, "清洗后的字幕产物（CLEANED）");
TaskRun run = supervisor.start(task, TaskRunType.TRANSLATE, target.getCode()).orElseThrow(() -> conflictFor(task));
pipeline.translate(run, ref, workspace, source, target, cleaned.getId());
```

### 0.3 `SubtitlePipeline#translate` 的完整目标形状（顺序不能换）

```java
public void translate(TaskRun run, RunRef ref, Workspace workspace,
                      SupportedLanguage source, SupportedLanguage target, long cleanedArtifactId) {
    concurrencyGate.submit(() -> {
        try {
            Path workDir = workDir(workspace);
            ProgressListener listener = progressListener(ref, run);

            // ① 复用判据：当前语种有可读的 READY 译文 → 不调 LLM、不落新版本、不动文件
            Optional<SubtitleArtifact> current = artifactStore.findReadableReady(
                    run.getTaskId(), ArtifactType.TRANSLATED, target.getCode(), workDir);
            if (current.isPresent()) {
                StageEvents.emit(listener, SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), STAGE_DONE,
                        "已有当前译文，跳过翻译", StageProgress.State.SKIPPED);
                log.info("译文已是最新，跳过翻译: taskNo={}, runNo={}, lang={}",
                        ref.taskNo().value(), ref.runNo(), target.getCode());
                supervisor.finish(ref, run, null);
                return;
            }

            // ② 翻译（阶段自己写三个候选文件，原子替换）
            translationStage.translate(toMediaInput(workspace, workDir), workDir, source, target, listener);

            // ③ 成功之后才落占位：LLM 失败时库里不留垃圾行
            SubtitleArtifact translated = artifactStore.createCreatingPlaceholder(
                    run.getTaskId(), run.getId(), ArtifactType.TRANSLATED, target.getCode(),
                    cleanedArtifactId, null);
            artifactStore.settleArtifact(translated, workDir);

            supervisor.finish(ref, run, null);
            log.info("任务翻译完成: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
        } catch (Throwable t) {
            log.error("翻译失败: taskNo={}, runNo={}\n{}", ref.taskNo().value(), ref.runNo(), stackOf(t));
            supervisor.finish(ref, run, reason(t));
        }
    }, () -> {
        log.warn("翻译等待执行配额时被中断: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
        supervisor.finish(ref, run, "翻译在等待执行配额时被中断：请重新触发翻译");
    });
}
```

`STAGE_DONE` 就是原来 coordinator 里的 `100`。**别**在管线里再写一个 100 的私有常量：要么复用同一个语义名（在管线内定义为 `private static final int STAGE_DONE = 100;` 并注释「翻译阶段进度终点」），要么直接用 `100` 加行内注释 —— 二选一，别两处各写一个不同名的常量。

---

## 步骤 1：可选文件位（纯地基）

### 1.1 `ArtifactFileKind#isRequiredFor`

按 §0.2 的判定加进去。`belongsTo` 一个字不动；两个方法的分工写进类注释：

- `belongsTo` —— 这个类型**可以**有哪个位；
- `isRequiredFor` —— 这个位**必须**有才能算「这一版产出完了」。

### 1.2 `resolveArtifactFiles`：可选位没有文件就跳过

```java
for (ArtifactFileKind kind : ArtifactFileKind.values()) {
    if (!kind.belongsTo(type)) {
        continue;
    }
    String fileName = kind.specFor(type).getFileName(lang);
    Path file = workDir.resolve(fileName);
    if (!kind.isRequiredFor(type) && !ArtifactFileUtils.isRegularFile(file)) {
        // 可选位没有这个文件：不登记、不算哈希，留 null
        continue;
    }
    hashes.put(kind, ArtifactFileUtils.sha256(file));
    names.put(kind, fileName);
}
```

要点：

- **必需位不先判存在**：仍然直接 `sha256`，读不到就抛 ISE（消息指出缺哪个位），保持「旧版一点没动」的语义。
- **可选位用 `isRegularFile` 判存在**：0 字节文件也算有（空术语表是合法的；是否「已查证」由 `CheckpointPolicy` 按大小另判，不在这里管）。
- 判存在与算哈希之间理论上文件可能被删 —— 那种情况下 `sha256` 抛 ISE，run 收成 `FAILED`、旧版不动。这是可接受的收敛方向，不额外吞异常。

`markReady` 不用改：它本来就按四个位取 `names.get(kind)` / `hashes.get(kind)`，取不到就是 `null`。

### 1.3 `isReadable`：可选位未登记不算缺

```java
String fileName = artifact.getFileName(kind);
if (fileName == null) {
    if (kind.isRequiredFor(artifact.getArtifactType())) {
        log.warn("产物缺少必需文件位登记，按没有处理: ...");
        return false;
    }
    continue;                     // 可选位本来就没有，正常
}
if (!ArtifactFileUtils.isRegularFile(workDir.resolve(fileName))) {
    log.warn("产物文件不在工作区，按没有处理: ...");
    return false;                 // 登记了但文件没了：整版不可用（C25 自愈）
}
```

**注意**：可选位**登记了**但文件被删 → 整版不可用，这条不能松。否则下载端点会把「文件列指着不存在的文件」的版本当成可读，最后在读文件时 500。

### 1.4 `reclaimFilesOf`：可选位未登记 = 正常跳过

```java
String fileName = artifact.getFileName(kind);
if (fileName == null) {
    if (kind.isRequiredFor(artifact.getArtifactType())) {
        log.warn("产物缺少必需文件位登记，无法确认文件已回收: ...");
        allGone = false;
    }
    continue;
}
Files.deleteIfExists(workDir.resolve(fileName));
```

这条直接修 S4 欠账 #2 暴露的一半：没有 terms 的真实译文行被级联时，回收能正常返回 `true` → 标 `DELETED`。

### 1.5 `SubtitleArtifactQueryService#snapshotOf`：可选位未登记不丢整版

```java
String fileName = artifact.getFileName(kind);
if (fileName == null) {
    if (kind.isRequiredFor(artifact.getArtifactType())) {
        return Optional.empty();
    }
    continue;                     // 可选位没有：files 里少一项
}
```

### 1.6 测试

| 文件 | 用例 |
|---|---|
| `ArtifactFileKindTest` | `isRequiredFor` 三类型 × 四位的穷举矩阵（`ORIGINAL` 两位必需、`CLEANED` 三位必需、`TRANSLATED` 只有两位必需）；`TERMS.belongsTo(TRANSLATED)` 仍为真 |
| `SubtitleArtifactStoreTest` | ① `settleTranslatedWithoutTermsRegistersTwoSlots`（只放 segments + srt → 成功，`termsFileName` / `termsSha256` 为 null）；② `settleTranslatedWithTermsRegistersThreeSlots`；③ `findReadableReady` 无 terms 仍返回；④ 登记了 terms 但文件被删 → 返回空 |
| `SubtitleArtifactStoreTest` | ⑤ 级联回收一条**无 terms** 的真实形态译本行 → 文件删干净、`markDeleted` 被调用（替代/补充 S4 的三文件行用例） |
| `SubtitleArtifactQueryServiceTest` | ⑥ 无 terms 的 `TRANSLATED` 版本仍出现在清单里，`files` 只有两项；⑦ 下载 `terms.json` → `ArtifactNotFoundException` |

### 1.7 判据

`clean test-compile` 过；全量测试绿；`ORIGINAL` / `CLEANED` 的现有用例**一条都不用改行为断言**（它们没有可选位）。

---

## 步骤 2：`settleArtifact` 包事务（W4）

### 2.0 事务机制怎么选（别写成「一律用模板」）

**默认优先 `@Transactional`**：方法被别的 bean 调用（走代理）、事务段就是一个完整方法、事务里不做慢 IO —— 这三条都满足时，注解更短也更好读。

**只有下面几种情况才用 `TransactionTemplate`**：

1. 事务段必须被**同类内部方法**调用（自调用绕过 Spring 代理，注解静默失效 —— W1 踩过）；
2. 要在事务外先做慢 IO（网络 / 大文件哈希），而事务段拆出去又会踩第 1 条；
3. 事务边界需要在**不启 Spring 上下文的单测**里被观察到。

**本处三条里踩了 2 和 3**：哈希要留在事务外（S4 T4 的纪律），拆成同类私有方法会踩自调用；store 的事务行为靠 `RecordingTransactionManager` + 直接构造对象来钉，注解在裸实例上是空转、边界验不到。所以本处用模板 —— **这是「看情况」的结果，不是仓库惯例**。如果将来某个事务方法三条都不踩，直接上 `@Transactional`。

### 2.1 目标形状

```java
public SubtitleArtifact settleArtifact(SubtitleArtifact creating, Path workDir) {
    // 事务外：算文件名与哈希（慢文件 IO）
    ArtifactFiles files = resolveArtifactFiles(creating.getArtifactType(), creating.getLang(), workDir);

    // 事务内：旧版降级 + 新行 READY 必须同刻可见。
    // 用模板不用 @Transactional 的理由见 prd T4 / 本节 2.0：哈希要留事务外、
    // 事务段只能是内部方法（自调用会绕过代理），且边界要在裸单测里可观察。
    transactionTemplate.executeWithoutResult(status -> {
        repository.findReadyByTaskIdAndTypeAndLang(creating.getTaskId(), creating.getArtifactType(),
                        creating.getLang())
                .ifPresent(old -> repository.markSupersededAndClearFiles(old.getId()));
        markReady(creating, files);
    });
    return creating;
}
```

要点：

- **哈希失败（文件缺）时连事务都不开**：`resolveArtifactFiles` 在 `executeWithoutResult` 之前抛，旧版与下游一点没动。
- 事务模板复用构造里已有的 `transactionTemplate`，不新增依赖。
- `settleCleanedAndSupersedeDownstream` 一个字不动（它本来就有事务）。

### 2.2 测试（`SubtitleArtifactStoreTest`）

- `settleOriginalWrapsSupersedeAndMarkReadyInOneTransaction`：事件序列 `begin → find? → markSupersededAndClearFiles → updateReady → commit`（按 `RecordingTransactionManager` 现有的事件记账方式）。
- `settleRollsBackWhenMarkingNewReadyFails`：`updateReady` 抛异常 → `rollback` 一次、`commit` 零次，异常传出。
- `settleThrowsWithoutOpeningTransactionWhenAFileIsMissing`：缺文件 → 没有 `begin`，也没有任何仓储写。
- 现有 `settleSupersedesOldReadyBeforeMarkingNewOneReady` 等用例断言补上事务事件（顺序断言不能松）。

### 2.3 判据

`clean test-compile` 过；全量测试绿；W5 的两个事务用例（S4 留下的）不受影响。

---

## 步骤 3：★ 翻译接线（行为拐点）

### 3.1 `SubtitlePipeline#translate`

按 §0.3 的完整形状改。三件事必须同批：

1. 复用判据（`findReadableReady`）；
2. 翻译成功后的占位 + `settleArtifact`；
3. `supervisor.finish` 的三个出口（复用命中 / 正常完成 / 异常）都保持无条件。

**不要**在翻译之前插占位（prd T3）；**不要**自己查 `CLEANED`（id 由调用方给）。

### 3.2 `SubtitleTranslationCoordinator`：删掉文件断点复用

- 删 `reuseCheckpointIfPresent` 方法与 `translate()` 里的调用（`Optional<TranslatedSubtitle> isReused = ...` 那三行）。
- 类注释里「<b>断点复用</b>：…存在且非空即跳过翻译…」整段改写为：「是否复用由产物表的当前有效版决定，本类只负责真翻译并写文件」。
- **保留**：同语种跳过 LLM、术语查证四条跳过路径、`writeTranslatedArtifacts` 的「先 JSON 后 SRT、都原子替换」、进度事件、返回的 `TranslatedSubtitle` 形状。
- `Optional` 的 import 若因此不再使用就删掉（判编译会告诉你）。

### 3.3 测试（`SubtitlePipelineTest`，`artifactStore` 是 mock）

| 用例 | 断言 |
|---|---|
| `translateReusesCurrentReadableTranslationWithoutCallingStage` | `findReadableReady(TASK_ID, TRANSLATED, "ja", workDir)` 返回 `Optional.of(...)` → `translationStage` **零调用**、`createCreatingPlaceholder` **零调用**、`settleArtifact` **零调用**、`supervisor.finish(ref, run, null)` 一次；进度监听收到一条 `SKIPPED` |
| `translateCreatesPlaceholderThenSettlesThenFinishes` | `findReadableReady` → 空 → 阶段调用一次 → 占位参数为 `(taskId, runId, TRANSLATED, "ja", cleanedArtifactId, null)` → `settleArtifact(placeholder, workDir)` → `finish(null)`；顺序用 `InOrder` 钉住 |
| `translateFailureFinishesRunWithoutPlaceholder` | 阶段抛异常 → `finish(ref, run, 原因)` 一次；`createCreatingPlaceholder` / `settleArtifact` **零调用** |
| `translateRecreatesWhenReadyRowHasMissingFiles` | `findReadableReady` 返回空（store 层已把「文件不在」过滤掉）→ 阶段照常调用。**这条其实由步骤 1 的 store 用例保证**，管线侧只钉「空就翻译」 |

`SubtitleTranslationCoordinatorTest`：

- **删** `translateReusesCheckpointSkipsTranslatorAndZeroesStats`、`translateCheckpointHitEmitsSingleSkippedEvent`（行为已不存在）。
- 其余用例保持；如果某个用例依赖「预置 translated 文件来触发复用」，改成首跑语义（预置文件改为断言会被覆盖）。

### 3.4 判据

`clean test-compile` 过；全量测试绿；grep 全仓不再有「按 translated-segments 文件存在跳过翻译」的分支。

---

## 步骤 4：前置守卫交出 `CLEANED` id

### 4.1 `SubtitleTaskService#translate`

按 §0.2 改：把 `requireReadableArtifact(...)` 的返回值接住，传 `cleaned.getId()`。其余（语种解析、404、409、日志）不动。

### 4.2 测试（`SubtitleTaskServiceTest`）

`translateDispatchesTranslateRunWhenReadableCleanedExists` 的 verify 改成：

```java
verify(pipeline).translate(eq(run), any(), any(), any(SupportedLanguage.class),
        any(SupportedLanguage.class), eq(readyArtifact(ArtifactType.CLEANED).getId()));
```

再加一条：`translateRejectsWhenNoReadableCleaned` 断言 `supervisor.start` 零调用（现有用例已覆盖，确认参数列表变化后仍成立）。

### 4.3 判据

`clean test-compile` 过；全量测试绿。

---

## 步骤 5：文档同步

| 文件 | 改什么 |
|---|---|
| 父 `design.md` §1.4 三代文件组合表 | `TRANSLATED(lang)` 行的 `terms` 标成**可选位**，并补一句「查证未启用 / 无术语 / 全部查证失败 / 同语种时不产出；产物行该列为 `NULL`」 |
| 父 `design.md` §7.5 步骤 3 | 判据补全为「该语种有一条 `READY` 行，**且该行应带的文件位可读**（C25）」；保留「不带 lineage、不比哈希」 |
| 父 `design.md` §7.5 步骤 5 附近 | 补一句「占位行在翻译成功之后插；LLM 失败时没有占位行」（这是原文步骤顺序的字面，但容易在实施时被 S4 的 regenerate 形状带偏，值得写明） |

**不改**：父 prd 的任务地图（S5 的描述仍然成立）、handoff.md（讨论记录，按约定不回改）。

**为什么不另开一份 `design.md`**：父任务章程写着「技术终态设计在 design.md；每段的具体做法写在各子任务的 `implement.md`」。S1–S4 都只有 `prd.md` + `implement.md`。

---

## 步骤 6：全量验证 + 手工验收 + 欠账核对

### 6.1 命令

```bash
cd backend

# 1) 判编译 —— 增量编译会给假绿，一律 clean
JAVA_HOME=<JDK21 路径> mvn -q clean test-compile

# 2) 全量测试 —— 先删陈旧报告，免得读到上一轮的 XML
rm -rf target/surefire-reports
JAVA_HOME=<JDK21 路径> mvn test
grep -rc '@Test\|@ParameterizedTest' src/test --include=*.java | awk -F: '{s+=$2} END {print s}'   # 静态数 ≥ 623

# 3) 起服务（真库跑 schema.sql；本段无表结构变更）
JAVA_HOME=<JDK21 路径> mvn spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true
# 冒烟完按 PID taskkill（TaskStop 杀不掉子 JVM）
```

### 6.2 从零准备

```sql
-- 本地存量可弃；无 DB 外键，删除顺序无所谓
DELETE FROM subtitle_artifact;
DELETE FROM task_run;
DELETE FROM subtitle_keyword;
DELETE FROM subtitle_task;
DELETE FROM workspace;
```

```bash
rm -rf backend/data/works
```

### 6.3 手工验收（对应 prd 的 AC）

```bash
DB='mysql -u root -pzwl61758157 --default-character-set=utf8mb4 vidsage -N -B -e'

# ── 起步：提交 + 等完成 ───────────────────────────────────────────
T=$(curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
      -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}' | sed -E 's/.*"taskNo":"([^"]+)".*/\1/')
# 轮询 GET /api/tasks/$T 直到 lastRun.status=SUCCEEDED
curl -s localhost:8080/api/tasks/$T/artifacts        # ORIGINAL + CLEANED

# ── AC1：首次翻译 ────────────────────────────────────────────────
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"ja"}'
# 等 lastRun.status=SUCCEEDED
curl -s localhost:8080/api/tasks/$T/artifacts
#   → 多一条 TRANSLATED(lang=ja, revision=1)，files 里有 segments / subtitles（有术语时还有 terms）
curl -s -o /dev/null -w '%{http_code}\n' \
     "localhost:8080/api/tasks/$T/artifacts/TRANSLATED/subtitles.srt?lang=ja"   # 200
$DB "SELECT artifact_type, lang, revision, status, derived_from_run_id, derived_from_artifact_id,
            segments_file_name, subtitles_file_name, terms_file_name,
            segments_sha256 IS NOT NULL, subtitles_sha256 IS NOT NULL, terms_sha256 IS NOT NULL
     FROM subtitle_artifact
     WHERE task_id = (SELECT id FROM subtitle_task WHERE task_no='$T')
     ORDER BY artifact_type, lang, revision;"
#   → TRANSLATED ja rev1 READY；derived_from_artifact_id = CLEANED 的 id
#   → 没有残留 CREATING

# ── AC2：同语种再翻不发 LLM ──────────────────────────────────────
ls -l --time-style=full-iso backend/data/works/BV11QMd6WEJp_p1/translated-*-ja.*
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"ja"}'
# 等 SUCCEEDED
ls -l --time-style=full-iso backend/data/works/BV11QMd6WEJp_p1/translated-*-ja.*   # mtime 不变
$DB "SELECT lang, revision, status FROM subtitle_artifact
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T') AND artifact_type='TRANSLATED';"
#   → 仍然只有 ja rev1 READY（没有 rev2）
# 日志里有「译文已是最新，跳过翻译」

# ── AC3：换语种互不影响 ──────────────────────────────────────────
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"en"}'
# 等 SUCCEEDED
#   → 多一条 TRANSLATED en rev1 READY；ja 那一行仍 READY、文件原样

# ── AC4：文件缺失自愈 ───────────────────────────────────────────
rm backend/data/works/BV11QMd6WEJp_p1/translated-subtitles-en.srt
curl -s localhost:8080/api/tasks/$T/artifacts          # en 这一版暂时不出现
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"en"}'
# 等 SUCCEEDED
#   → en rev2 READY；rev1 SUPERSEDED 且八个文件/哈希列全 NULL；文件重新出现

# ── AC6：无 terms 的真实形态（同语种最省事，不调 LLM）─────────────
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"zh-Hans"}'
# 等 SUCCEEDED
$DB "SELECT lang, revision, terms_file_name, terms_sha256 FROM subtitle_artifact
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T') AND artifact_type='TRANSLATED';"
#   → zh-Hans 一行 READY，terms_file_name / terms_sha256 都是 NULL
curl -s localhost:8080/api/tasks/$T/artifacts          # 该版仍列出，files 只有 segments / subtitles
curl -s -o /dev/null -w '%{http_code}\n' \
     "localhost:8080/api/tasks/$T/artifacts/TRANSLATED/terms.json?lang=zh-Hans"   # 404

# ── AC6 后半 / AC7：级联回收真译文行（S4 欠账 #2）─────────────────
curl -s -X POST localhost:8080/api/tasks/$T/subtitles/regenerate \
     -H 'Content-Type: application/json' -d '{"keywords":["某某专有名词"]}'
# 等 SUCCEEDED；内容真变了
$DB "SELECT lang, revision, status FROM subtitle_artifact
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T') AND artifact_type='TRANSLATED'
     ORDER BY lang, revision;"
#   → 所有旧译文行走完 SUPERSEDED → DELETED（包括没有 terms 的 zh-Hans 行，不卡在 SUPERSEDED）
ls backend/data/works/BV11QMd6WEJp_p1/translated-* 2>/dev/null    # 不存在
# 再翻一次 ja → 新 rev READY（证明级联后可重新产出）

# ── AC8：前置与互斥 ─────────────────────────────────────────────
# 没有可读 CLEANED 的任务（新提交、还没跑完）→ 400，且 task_run 没多一行
# 同一 task 连发两次 translate → 第二次 409，响应体 {taskNo, runNo}
```

> 手工验收里的 SQL / curl 都是**人跑的命令**，不进代码库（父 prd 两条原则之一）。
> AC5 的失败注入：临时把翻译模型的 key 改坏或断网，重启实例后触发一次翻译；验完恢复。

### 6.4 收尾

逐条核对下面的欠账表 → 报告（含测试条数基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。

---

## 边界矩阵

| 场景 | 表现 |
|---|---|
| LLM 阶段失败（翻译返回之前） | 没有占位行；旧 `READY` 译文与文件一点没动；run `FAILED` |
| 翻译写完文件、插占位之前崩 | DB 仍是旧版（或没有）；下次翻译：旧行可读 → 命中复用、跳过（盘上内容更新，C64 ① 接受）；旧行不存在 → 多调一次 LLM，重写同名文件 |
| 占位之后、W4 之前崩 | 占位行残留 `CREATING` → 收口（本次进程还活着）或启动收口标 `FAILED`；旧版仍 `READY` |
| W4 事务任一步抛异常 | 回滚，旧版仍 `READY`；新占位由收口标 `FAILED`；重翻收敛 |
| 文件列已替换、W4 未提交（C64 ①） | 读路径拿到新内容、DB 记旧版；新内容比 DB 记的更对；重翻 / 命中复用自动纠正 |
| 两个译文文件不同源（C64 ②） | 本次不根治；哈希列留着，S7 对账比对时报出「需人工重跑」 |
| 首次翻译时 terms 不存在 | 正常：`terms_file_name` / `terms_sha256` 为 `NULL`，`/artifacts` 仍列出该版（两个文件），下载 terms → 404 |
| 同语种翻译（源 = 目标） | 不调 LLM、不查术语；仍产出一版 `TRANSLATED(lang)`（译文 = 原文），同样走复用判据 |
| 译文文件被手工删掉一个 | 该版 `isReadable` 为假 → `/artifacts` 不列、复用不命中 → 再翻即重建（覆盖型换版） |
| 只删 terms 文件、segments/SRT 都在 | 该版不可读（登记了但文件不在）→ 再翻会重建整版；下次查证时术语文件缺失会重新查 |
| 只删 terms 文件、想只重查术语 | 整版重翻（重新查证）；单删 terms 不会触发「仅重查」—— 与现状一致，不是回归 |
| 已 `SUPERSEDED` 的译文文件还在（上次回收失败） | 复用判据只认 `READY`，不会捞旧行；文件由 S7 对账 ② 报出 |
| 同一 task 并发两次翻译 | `uk_active_run` 撞键 → 409；store 不处理并发 |
| 目标语种不在白名单 | 同步段 400（`SupportedLanguage.parse`），不插 run |

---

## 回滚点

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| **步骤 1 之后** | 可选位地基就位，三类产物行为不变 | 零代价；`TRANSLATED` 仍无人产出 |
| **步骤 2 之后** | W4 多一个事务边界 | 零代价；可单独回滚 |
| **步骤 3 之后** | 翻译开始落表；coordinator 不再按文件复用 | 行为拐点；**步骤 3 与 4 编译耦合，要回滚就一起回滚** |
| **步骤 4 之后** | 服务把 CLEANED id 交给管线 | 零代价；纯传递 |
| **步骤 5** | 只动文档 | 单独可回滚 |

**彻底回退**：`git revert` 本段提交；无表结构变更，不需要 DROP / 重建。已写入的 `TRANSLATED` 行可留（旧代码读不出问题）或手工清空。

---

## 欠账清单（本段结束时哪些东西还没到位、交给哪一段）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | **中间产物仍是「文件在不在」判据**：转写 / 音频 / 清洗的复用判据还是文件存在，`intermediate_file` 零登记 | **S6** | C62 改造 |
| 2 | **内容指纹两列仍恒为 `NULL`**；内容变更级联未做 | **S6** | 指纹探测阶段 |
| 3 | **哈希列只写不校**；对账端点不存在 | **S7** | 只读对账 + 哈希比对 |
| 4 | **无 DB 行的孤儿译文文件**（S5 之前的旧产物）不再被自动清理；本段不做回填 | **S7** | 对账 ③ 报出，人工回收；重新翻译会覆盖同名文件 |
| 5 | 网络调用无超时 | **S8** | C46 订正 |
| 6 | 前端一定点不通（新端点未接） | **另开任务**（C60） | 后端全部段做完再动 |
| 7 | **三条执行体将来要拆成三条独立管线**（主任务 / 重清洗 / 翻译），届时「重新提交会不会作废译文」这条判据要重审 | **未排期**（用户 2026-10-19 记） | 本段先按 S4 的「内容真变了才作废」+ 本段的复用判据落地 |
| 8 | 历史版本 / 版本回退 | **不做** | C52 / C58 |
| 9 | **失败信息里带工作区绝对路径**（既有缺陷，不是本段引入）：`ArtifactFileUtils.writeAtomically` 的异常消息拼了 `target` 全路径，经 `run.error_message` 会出现在 `GET /api/tasks/{taskNo}` | **另开小修 / 择机** | 本段手工验收 AC5 注入写入失败时暴露；修法是消息只带文件名段，不拼绝对路径（与日志脱敏红线同口径） |

**收尾动作**：跑完步骤 6 → 逐条核对上表 → 报告（含测试基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。
