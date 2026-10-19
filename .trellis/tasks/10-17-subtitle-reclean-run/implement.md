# S4 执行计划：重洗（RECLEAN run + 换版级联）

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。**
> 本文件写 S4 的**段级技术落点 + 执行顺序 + 验证方式**（父任务章程：每段具体做法写在各子任务的 `implement.md`）。
> **本文件是执行参考稿，不是契约**：发现与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。
> 范围与验收见 [prd.md](./prd.md)；本段对父设计的两处订正见 prd「本段对父设计的订正」。

## 0. 与 S3 实际落地代码的校准

下表是 S4 开头必须按「实际代码」而不是「父设计措辞」来写的地方（2026-10-19 逐项对照 `backend/src/main` 核对）：

| # | 父设计 / 早期草稿的说法 | 实际代码（S3 落地后） | 对 S4 的影响 |
|---|---|---|---|
| 1 | 级联需要一句批量 UPDATE | S1 只给了**单行**版 `markSuperseded(id)`；同族中间产物侧的 `markSupersededByTaskId(taskId, at)` 是批量的 | S4 新增 `markSupersededByTaskIdAndType(taskId, type)`（端口 + Mapper + 仓储实现各一处），并删掉零调用点的单行版 |
| 2 | 换版落定要包事务 | `SubtitleArtifactStore#settleArtifact` 明确**不包事务**（W4 非必需），类注释写着「崩在中间只会让这个产物类型暂时没有当前版」 | S4 **保留 `settleArtifact` 原样**，另开一个 W5 方法；不要给 `settleArtifact` 加事务 |
| 3 | 收口要把占位行标 FAILED | `TaskRunSupervisor#finish` 已经调 `markCreatingAsFailedByRunId(run.getId())`（S3 补的），且与 run 终态落库分成两段 try | S4 **不碰 `TaskRunSupervisor`**；RECLEAN 的占位行天然被收掉 |
| 4 | `subtitle_task.updated_at` 由本段更新 | `finish` 里已经调 `subtitleTaskRepository.touchUpdatedAt(...)` | S4 不管 |
| 5 | 删译文文件在 W5 之后 | `SubtitleCleanupCoordinator#deleteTranslatedArtifacts` 在 `doClean` 里、**写完成品之后立刻删**，早于 `settleArtifact` | S4 把它整块摘掉（prd T3） |
| 6 | coordinator 本段不改 | S3 说「三个 coordinator 本段不改行为；S4 / S5 才在它们的调用点加 artifact 逻辑」 | S4 动 `SubtitleCleanupCoordinator`：**只删代码，不加代码** |
| 7 | 前置守卫怎么把 `ORIGINAL` 交给管线 | `SubtitleTaskService#validateReadableArtifact` 返回 `void`，只做「有就过、没有抛 400」 | S4 改名 `requireReadableArtifact` 并返回产物（prd T7） |
| 8 | `SubtitleArtifact` 能按文件位读哈希 | 只有 `getFileName(ArtifactFileKind)`；四个哈希列只有 Lombok 生成的整列 getter | S4 补 `getSha256(ArtifactFileKind)`，与 `getFileName` 对称 |
| 9 | `lang` 在 artifact 里可能是 null | `SubtitleArtifact` 构造里把 null 归成空串；`CLEANED` / `ORIGINAL` 的 `lang` 一律 `""` | 查旧版 / 级联一律用 `""`，不要传 null |
| 10 | 事务用 `@Transactional` | 全仓 `@Transactional` 出现次数 = **0**；W1 用的是 `TransactionTemplate`（`SubtitleTaskService` 构造里自建） | W5 照抄这个形状（prd T4） |

**结论**：S4 的改动面很小 —— 仓储 / SQL / 状态机 / 收口 / 启动收口都已就位，本段主要是**给 store 加一个带事务的换版方法 + 把两条路接上去 + 把删文件从 coordinator 搬走**。

---

## 段内顺序（六步）

```
0  预期文件树与关键形状过审（门）
1  store：settleCleanedAndSupersedeDownstream（纯新增，零调用点）
2  ★ 主管线 CLEANED 落定换方法（行为拐点之一）
3  前置守卫返回产物（纯重构，行为不变）
4  ★ RECLEAN 接线 + 摘掉 coordinator 的删文件（行为拐点之二）
5  文档同步（父 design.md 两处订正）
6  全量验证 + 欠账核对
```

**顺序的理由**

- **步骤 1 是纯新增**：新方法没有任何调用点，`clean test-compile` 过、全量测试绿。
- **步骤 2 是第一个行为拐点**：主管线的 `CLEANED` 落定开始走 W5。它不需要 RECLEAN 就能独立验证 —— 「重提交同一个 URL」这条 `MAIN` 路自己就能把 AC4 / AC5 跑出来。
- **步骤 3 是纯重构**：只改方法名与返回值，两个调用点行为不变，可单独绿。
- **步骤 4 是第二个行为拐点**：RECLEAN 开始写 artifact 表；同一步里必须把 coordinator 的删文件摘掉，否则两个落点并存（一个在 W5 前、一个在 W5 后），行为要靠读代码才能推断。
- **步骤 5 / 6 各自独立**，都绿。

---

## 步骤 0：预期文件树与关键形状过审（门）

**这是一道门，不是准备工作。** 先把下面的文件树、§0.2 的方法形状给项目所有者过一眼，**点头之后才开分支、才写第一行代码**（先例：S1 / S2 / S3 的同一道门）。

过审时要特别确认的四件事（都是 2026-10-19 讨论定下的）：

1. **级联判据是「内容真变了」**（比 `CLEANED` 三个文件位的哈希），不是无条件级联。
2. **两条路（`MAIN` + `RECLEAN`）共用同一个 W5 方法**。
3. **删下游译文文件搬到 W5 提交之后，按 DB 行的文件列删**；coordinator 的目录扫描删除整块摘掉。
4. **W5 的事务用 `TransactionTemplate` 圈在 store 内**，算哈希留在事务外。

### 0.1 预期文件树（S4 结束后，只列本段碰的）

```
backend/src/main/java/com/vidsage/
├── domain/subtitle/
│   └── SubtitleArtifact.java                        [改] + getSha256(ArtifactFileKind)
├── domain/subtitle/port/
│   └── SubtitleArtifactRepository.java              [改] + markSupersededByTaskIdAndType（批量）；− 单行 markSuperseded
├── infrastructure/persistence/
│   ├── mapper/SubtitleArtifactMapper.java           [改] 同上，一句批量 UPDATE
│   └── repository/MybatisSubtitleArtifactRepository.java [改] 同上，纯委派
├── application/subtitle/
│   ├── SubtitleArtifactStore.java                   [改] + settleCleanedAndSupersedeDownstream；注入 TransactionTemplate
│   └── pipeline/
│       ├── SubtitlePipeline.java                    [改] runPipeline 的 CLEANED 换方法；regenerate 补占位 + 落定
│       └── SubtitleCleanupCoordinator.java          [改] 删掉 deleteTranslatedArtifacts 及其调用点与相关注释
└── application/task/
    └── SubtitleTaskService.java                     [改] validateReadableArtifact → requireReadableArtifact（返回产物）

backend/src/test/java/com/vidsage/
├── domain/subtitle/SubtitleArtifactTest.java                        [改] getSha256 用例
├── application/subtitle/SubtitleArtifactStoreTest.java              [改] W5 用例（顺序 / 事务 / 判据 / 两阶段回收）
├── application/subtitle/pipeline/SubtitlePipelineTest.java          [改] regenerate 接线
├── application/subtitle/pipeline/SubtitleCleanupCoordinatorTest.java[改] 删掉删文件相关用例
├── application/task/SubtitleTaskServiceTest.java                    [改] 前置守卫返回值
└── infrastructure/persistence/repository/MybatisSubtitleArtifactRepositoryTest.java [改] 批量取代的委派用例（替换单行版）

.trellis/tasks/10-15-subtitle-task-model-refactor/design.md          [改] §5 W5 行 + §7.4 步骤 6 ② / §7.5（见步骤 5）
```

**不碰**：`schema.sql`、`TaskRunSupervisor`、`SubtitleArtifactQueryService`、`ArtifactController` 与全部 DTO、`SubtitleArtifactSpec`、`ArtifactFileKind`、三个 run 之外的一切。

### 0.2 新增 / 改动的方法形状

**`SubtitleArtifactStore`（新增一个方法，改构造）**

```java
// 构造新增 PlatformTransactionManager，自建 TransactionTemplate（与 SubtitleTaskService 同款）
public SubtitleArtifactStore(SubtitleArtifactRepository repository,
                             PlatformTransactionManager transactionManager)

// W4：无下游的类型（ORIGINAL；将来 TRANSLATED 也回到它）—— 原样不动
public SubtitleArtifact settleArtifact(SubtitleArtifact creating, Path workDir)

// W5：CLEANED 专用。换版 + 下游级联（同一事务）+ 事务后回收下游文件。
// 不返回值：落定与回收是一件事的两个半场，不给调用方「只做前半场」的余地
public void settleCleanedAndSupersedeDownstream(SubtitleArtifact creating, Path workDir)

// 内部：内容判据
private static boolean contentChanged(SubtitleArtifact old, Map<ArtifactFileKind, String> newHashes)

// 内部：删一版产物的全部文件位，全删干净才返回 true
private boolean reclaimFilesOf(SubtitleArtifact artifact, Path workDir)
```

**`SubtitleArtifact`（加一个访问器）**

```java
// 与已有的 getFileName(ArtifactFileKind) 对称，让「文件位 → 哈希列」的映射单源在 ArtifactFileKind
public String getSha256(ArtifactFileKind kind)
```

**`SubtitlePipeline`（两个方法改签名 / 改内容）**

```java
// 签名加一个参数：新 CLEANED 占位的 derived_from_artifact_id
public void regenerate(TaskRun run, RunRef ref, Workspace workspace,
                       SubtitleKeywords keywords, long originalArtifactId)
```

**`SubtitleTaskService`（一个私有方法改名 + 返回值）**

```java
// 原 validateReadableArtifact（void）→ 返回产物本身；取不到仍抛 IAE → 400
private SubtitleArtifact requireReadableArtifact(SubtitleTask task, Workspace workspace,
                                                 ArtifactType type, String description)
```

### 0.3 W5 的完整形状（顺序不能换）

```java
public void settleCleanedAndSupersedeDownstream(SubtitleArtifact creating, Path workDir) {
    // ── 事务外：算文件名与哈希（慢文件 IO，不进事务）──────────────────
    ArtifactFiles files = resolveArtifactFiles(creating.getArtifactType(), creating.getLang(), workDir);

    // ── 事务内：降级旧版 → 级联下游 → 新行 READY ────────────────────
    List<SubtitleArtifact> translationsToReclaim = transactionTemplate.execute(status -> {
        Optional<SubtitleArtifact> previousReady = repository.findReadyByTaskIdAndTypeAndLang(
                creating.getTaskId(), creating.getArtifactType(), creating.getLang());
        // ① 旧版降级（无条件：换版总要降级旧版）
        previousReady.ifPresent(previous -> repository.markSupersededAndClearFiles(previous.getId()));
        // ② 内容真变了才级联下游：先把行读出来（删文件要用文件列），再用一句批量把状态全标掉
        List<SubtitleArtifact> translationsToSupersede =
                isContentChanged(previousReady.orElse(null), files)
                        ? findDownstreamTranslations(creating.getTaskId())
                        : List.of();
        if (!translationsToSupersede.isEmpty()) {
            repository.markSupersededByTaskIdAndType(creating.getTaskId(), ArtifactType.TRANSLATED);
        }
        // ③ 新行置 READY
        markReady(creating, files);
        return translationsToSupersede;
    });

    // ── 事务提交后：先标 DB 已做，再删文件；删干净才标 DELETED ──────────
    for (SubtitleArtifact translation : translationsToReclaim) {
        if (reclaimFilesOf(translation, workDir)) {
            repository.markDeleted(translation.getId());
        }
    }
}
```

**内容判据**（三个文件位逐个比，任一不同即「变了」）：

```java
private static boolean isContentChanged(SubtitleArtifact previousReady, ArtifactFiles files) {
    if (previousReady == null) {
        return true;                       // 无旧行可比：安全方向（首跑时下游必然为空，等于空操作）
    }
    for (ArtifactFileKind kind : ArtifactFileKind.values()) {
        if (!kind.belongsTo(previousReady.getArtifactType())) continue;
        String before = previousReady.getSha256(kind);
        if (before == null || !before.equals(files.hashes().get(kind))) {
            return true;                   // 旧行哈希列缺失也当「变了」
        }
    }
    return false;
}
```

> 取名口径：变量要自带名词（`translationsToReclaim` 而不是 `orphaned`），返回布尔的判定函数要 `is` 前缀（`isContentChanged` 而不是 `contentChanged`）—— 两条都记在 `directory-structure.md` 的命名约定里。

**回收**（两阶段第二步）：

```java
private boolean reclaimFilesOf(SubtitleArtifact artifact, Path workDir) {
    boolean allGone = true;
    for (ArtifactFileKind kind : ArtifactFileKind.values()) {
        if (!kind.belongsTo(artifact.getArtifactType())) continue;
        String fileName = artifact.getFileName(kind);
        if (fileName == null) {
            allGone = false;               // 缺登记：不敢说「文件已确认回收」
            log.warn(...);                 // 只记 taskId / type / lang / 文件位
            continue;
        }
        try {
            Files.deleteIfExists(workDir.resolve(fileName));
        } catch (IOException e) {
            allGone = false;
            log.warn(...);                 // 不记路径（内嵌 source_key）
        }
    }
    return allGone;
}
```

**`SubtitlePipeline#regenerate` 的目标形状**（与 `runPipeline` 的 CLEANED 段同款）：

```java
Path workDir = workDir(workspace);
SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(
        run.getTaskId(), run.getId(), ArtifactType.CLEANED, "",
        originalArtifactId, keywords.computeFingerprint());
cleanupStage.regenerate(toMediaInput(workspace, workDir), workDir, keywords, progressListener(ref, run));
artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
supervisor.finish(ref, run, null);
```

---

## 步骤 1：`SubtitleArtifactStore` 的 W5 方法（纯新增）

### 1.1 改构造

```java
private final SubtitleArtifactRepository repository;
private final TransactionTemplate transactionTemplate;

public SubtitleArtifactStore(SubtitleArtifactRepository repository,
                             PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
}
```

形状照 `SubtitleTaskService`：自建模板，不用 `@Transactional` 注解。

### 1.2 新增 `getSha256(ArtifactFileKind)`

`domain/subtitle/SubtitleArtifact.java`，紧挨已有的 `getFileName(ArtifactFileKind)`：

```java
public String getSha256(ArtifactFileKind kind) {
    return switch (kind) {
        case SEGMENTS -> segmentsSha256;
        case SUBTITLES -> subtitlesSha256;
        case CONTEXT -> contextSha256;
        case TERMS -> termsSha256;
    };
}
```

穷举 `switch` 不写 `default`（与 `getFileName` / `ArtifactFileKind#belongsTo` 同款：将来加文件位时编译期报错）。

### 1.3 新增 `settleCleanedAndSupersedeDownstream` / `contentChanged` / `reclaimFilesOf`

按 §0.3 的形状写。要点：

- **哈希在事务外算**：读不到文件就抛 ISE，此时旧版与下游都一点没动，调用方的 catch 走 `supervisor.finish` → 占位行被收成 `FAILED`。
- **旧行必须在清列之前读**：`markSupersededAndClearFiles` 会把八个文件/哈希列一起清空，清完就再也比不出「内容变了没有」。
- **级联只挑 `READY` 的 `TRANSLATED`**：`findReadyByTaskId` 返回该 task 全部 `READY` 行（含刚被降级的旧 `CLEANED`？不会 —— 它已被标 `SUPERSEDED`，`active_flag` 变 NULL，查不出来），按 `artifact_type` 过滤。
- **级联用一句批量 `markSupersededByTaskIdAndType`**（孤儿型，文件列留着）；**不要**用 `markSupersededAndClearFiles`（那是覆盖型，会把删文件要用的路径清掉）。行仍然要先读出来 —— 那是给事务提交后删文件用的，跟改状态不是同一件事。
- **回收在事务外**，且**先删文件、后标 `DELETED`**（§8.2 的删序：先标 DB 已在上一步做过，这里补「文件确认回收」这半步）。
- **删文件失败不标 `DELETED`**：那一行停在 `SUPERSEDED`，由 S7 对账的 ② 报出来。

### 1.4 测试（`SubtitleArtifactStoreTest`，mock 仓储 + `TransactionTemplate` + `@TempDir`）

| 用例 | 断言 |
|---|---|
| 换版三步顺序 | `InOrder`：`markSupersededAndClearFiles` → `markSupersededByTaskIdAndType`（下游，一次）→ `updateReady` |
| 内容变了 → 级联 | 旧行三个哈希与新算的不同 → `markSupersededByTaskIdAndType` 被调**一次** |
| 内容没变 → 不级联 | 旧行三个哈希与新算的**全同** → **批量取代一次都没调**，但 `markSupersededAndClearFiles` + `updateReady` 照做 |
| 旧行哈希列为 NULL | 视为「变了」→ 级联 |
| 无旧行 | 视为「变了」→ 查询下游（结果为空）→ 不报错 |
| 事务包裹 | 三段写被**同一个**事务包住：`begin` 一次、`commit` 一次（用记录型 `PlatformTransactionManager` 替身，见下） |
| 事务回滚被触发 | `updateReady` 抛异常 → `rollback` 被调用一次、`commit` **零次**，且异常从方法里传出去（不吞） |
| 回收两阶段 | 下游行文件都在 → 三个文件被删 + `markDeleted`；缺一个文件列 → **不调 `markDeleted`**；`deleteIfExists` 抛 IOException → **不调 `markDeleted`** |
| 首次落定 | 旧行不存在 → 只走降级跳过 + 置 READY，不删任何文件 |

**事务用例怎么测**（已核过基建，别走弯路）：

- **本仓没有内存库可起**：`pom.xml` 只有 `mysql-connector-j`，**没有 H2 / HSQLDB / Testcontainers**；`MybatisSubtitleArtifactRepositoryTest` 也是「Mapper 用 Mockito mock、不连真库」的纯单测，`database-guidelines.md` 明确要求单测不连真库。
- **所以走记录型事务管理器替身**：写一个测试内的小类继承 `AbstractPlatformTransactionManager`，在 `doGetTransaction` / `doBegin` / `doCommit` / `doRollback` 里往一个 `List<String>` 记事件，注入 store。断言两件事：
  1. **包裹关系**：一次 `settleCleanedAndSupersedeDownstream` 只产生一次 `begin` + 一次 `commit`，且三次仓储写都发生在两者之间；
  2. **回滚被触发**：让 `updateReady` 抛异常 → 事件里是 `begin` → `rollback`（**没有** `commit`），且异常从方法里传出去。
- **诚实的边界**：这验的是「三段写被同一个事务包住、失败时会回滚」，**不是**「MySQL 真的回滚了」—— 后者是 Spring 事务管理器的语义，本段不重复实现、也不假装验过。这一条写进收尾报告。
- 不要为了验回滚给测试加 H2：既破既有口径，H2 也跑不动 `active_flag` 那种 MySQL 生成列。

### 1.5 判据

`clean test-compile` 过 + 全量测试绿（本步零生产调用点，行为不变）。

---

## 步骤 2：★ 主管线 CLEANED 落定换方法（行为拐点之一）

### 2.1 `SubtitlePipeline#runPipeline`

只有一行变化：

```java
// 原：artifactStore.settleArtifact(cleaned, workDir);
artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
```

`ORIGINAL` 那一段（`settleArtifact(original, workDir)`）**不动**。

### 2.2 为什么这是拐点

从这一步起，**任何**产生新 `CLEANED` 的路（含重新提交 URL 的 `MAIN`）都会在内容真变了时作废下游译文。这也是 AC4 / AC5 的落点。

### 2.3 测试（`SubtitlePipelineTest`）

| 用例 | 断言 |
|---|---|
| `runPipeline` 的 CLEANED 段 | 调的是 `settleCleanedAndSupersedeDownstream`，`ORIGINAL` 段仍是 `settleArtifact` |
| 占位参数 | CLEANED 占位带 `derived_from_artifact_id` = ORIGINAL 的 id、`keywords_fingerprint` = 关键词指纹 |

### 2.4 判据

`clean test-compile` 过 + 全量测试绿。

---

## 步骤 3：前置守卫返回产物（纯重构）

### 3.1 `SubtitleTaskService`

```java
// 原：private void validateReadableArtifact(SubtitleTask task, Workspace workspace,
//                                          ArtifactType type, String description)
private SubtitleArtifact requireReadableArtifact(SubtitleTask task, Workspace workspace,
                                                 ArtifactType type, String description) {
    Path workDir = finder.workspaceDir(workspace);
    return artifactStore.findReadableReady(task.getId(), type, "", workDir)
            .orElseThrow(() -> new IllegalArgumentException("字幕尚未就绪：该任务还没有可读的 " + description
                    + "；请先等主管线跑完（可查 GET /api/tasks/{taskNo} 或订阅其 SSE 事件）再试"));
}
```

- `regenerate`：`SubtitleArtifact original = requireReadableArtifact(task, workspace, ORIGINAL, "…");` → 后面把 `original.getId()` 传给管线（步骤 4）。
- `translate`：改成 `requireReadableArtifact(...)`，**返回值丢弃**（S5 才会用）。
- 方法注释补一句：返回的是**当下**可读的那一版，调用方拿它的 id 填 `derived_from_artifact_id`（审计用，不参与读取判据）。

### 3.2 测试（`SubtitleTaskServiceTest`）

- 既有「无 `READY` 时 400 且不插 run」的用例**行为不变**，只改断言方式（现在能拿到返回值）。
- 补一条：返回的产物 id 与 `artifactStore` 给出的一致。

### 3.3 判据

`clean test-compile` 过 + 全量测试绿（两个调用点行为不变）。

---

## 步骤 4：★ RECLEAN 接线 + 摘掉 coordinator 的删文件（行为拐点之二）

### 4.1 `SubtitlePipeline#regenerate`

签名加 `long originalArtifactId`，方法体改成 §0.3 的目标形状。要点：

- **占位在 `cleanupStage.regenerate` 之前**（与主管线同款：`CREATING` 表示「这次执行要产出一版」，且 revision 在写文件前就确定）。
- **`derived_from_artifact_id` = 当前 `ORIGINAL` 的 id**（父设计 §7.4 步骤 5）；`keywords_fingerprint` = 本次关键词的指纹。
- **失败不手回收口**：任何一步抛异常都交给现有的 `catch` → `supervisor.finish`。
- 两段 try 的 catch 块**不动**（日志与 `reason(t)` 保持原样）。

### 4.2 `SubtitleTaskService#regenerate`

```java
SubtitleArtifact original = requireReadableArtifact(task, workspace, ArtifactType.ORIGINAL, "原始转写产物（ORIGINAL）");
TaskRun run = supervisor.start(task, TaskRunType.RECLEAN, null).orElseThrow(() -> conflictFor(task));
if (validated != null) {
    keywordRepository.replace(task.getId(), validated);
}
SubtitleKeywords effective = validated != null ? validated : keywordRepository.findByTaskId(task.getId());
RunRef ref = RunRef.of(task.getTaskNo(), run);
pipeline.regenerate(run, ref, workspace, effective, original.getId());
```

### 4.3 `SubtitleCleanupCoordinator`：摘掉 `deleteTranslatedArtifacts`

- 删掉 `private void deleteTranslatedArtifacts(Path workDir)` 整个方法。
- 删掉 `doClean` 末尾的 `if (regenerate) { deleteTranslatedArtifacts(workDir); }`。
- 删掉不再用到的 import（`java.io.IOException`、`java.util.stream.Stream` —— **先编译确认，别凭印象删**；`Files` / `Path` 还有别处在用）。
- 订正 `regenerate` 的 Javadoc：删掉「删除顺序是『先成功、后删』」那一段，改成一句说明「作废下游译文与删它们的文件由落定产物那一步负责，本类只写文件」。
- 订正 `doClean` 的 `@param regenerate` 描述（去掉「成功后清理翻译与术语产物」）。
- 类注释里若有涉及删文件的句子，一并订正。

### 4.4 为什么两步必须同批

若只做 4.1 不做 4.3，文件会在 W5 **之前**被 coordinator 删掉、在 W5 **之后**又被 store 删一遍（第二次是 `deleteIfExists` 空操作）。功能上能跑，但「删文件在哪一步」这件事要靠读两个文件才能推断，而 prd T3 正是要把它收成一处。

### 4.5 测试

| 文件 | 用例 | 断言 |
|---|---|---|
| `SubtitlePipelineTest` | `regenerate` 接线 | 占位 → coordinator → `settleCleanedAndSupersedeDownstream` → `finish`，顺序 `InOrder`；占位参数正确 |
| `SubtitlePipelineTest` | `regenerate` 失败 | coordinator 抛错 → 仍走 `finish(ref, run, 非null)`，**没有**落定调用 |
| `SubtitleCleanupCoordinatorTest` | 删掉删文件相关用例 | 断言 `regenerate` 成功后工作区里 `translated-*` / `terms-*` **仍在**（本类不再管它们） |
| `SubtitleTaskServiceTest` | `regenerate` 传 id | 管线收到 `original.getId()` |

### 4.6 判据

`clean test-compile` 过 + 全量测试绿。

---

## 步骤 5：文档同步

| 文件 | 改什么 |
|---|---|
| 父 `design.md` §5 写集表 W5 行 | 「新 `CLEANED` READY ⇒ 下游全部 `SUPERSEDED`」→ 补判据：「新 `CLEANED` **内容变了**（三个文件位哈希与旧 `READY` 行不同）⇒ 下游全部 `SUPERSEDED`」 |
| 父 `design.md` §7.4 步骤 6 ② / §7.5 | 同上补判据；补一句「`MAIN` 的 `CLEANED` 落定走同一套 W5」；§7.4 步骤 7 补「删哪些文件由被级联那几行的文件列决定」 |
| 父 `design.md` §7.3 步骤 4 | 补一句：`CLEANED` 落定走 W5（不是 W4），因为下游译文要在同一事务里作废 |

**不改**：父 prd 的任务地图（S4 的描述仍然成立）、handoff.md（它是讨论记录，按约定不回改）。

**为什么不另开一份 `design.md`**：父任务章程写着「技术终态设计在 design.md；每段的具体做法写在各子任务的 `implement.md`」。S1–S3 都只有 `prd.md` + `implement.md`。

---

## 步骤 6：全量验证 + 欠账核对

### 6.1 命令

```bash
cd backend

# 1) 判编译 —— 增量编译会给假绿，一律 clean
JAVA_HOME=<JDK21 路径> mvn -q clean test-compile

# 2) 全量测试 —— 先删陈旧报告，免得读到上一轮的 XML
rm -rf target/surefire-reports
JAVA_HOME=<JDK21 路径> mvn test
grep -rc '@Test\|@ParameterizedTest' src/test --include=*.java | awk -F: '{s+=$2} END {print s}'   # 静态数 ≥ 610

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
curl -s localhost:8080/api/tasks/$T/artifacts        # CLEANED rev1 READY

# ── AC3 前置：造一行下游译文（S5 之前唯一的办法）────────────────────
# 盘上放齐三个文件（内容随便，本段只看「行状态」与「文件在不在」）
D=backend/data/works/BV11QMd6WEJp_p1
: > $D/translated-segments-zh-hant.json
: > $D/translated-subtitles-zh-hant.srt
: > $D/terms-zh-hant.json
$DB "
INSERT INTO subtitle_artifact
  (task_id, derived_from_run_id, artifact_type, lang, revision, status,
   derived_from_artifact_id, keywords_fingerprint,
   segments_file_name, subtitles_file_name, terms_file_name,
   segments_sha256, subtitles_sha256, terms_sha256, created_at)
SELECT t.id, r.id, 'TRANSLATED', 'zh-Hant', 1, 'READY', a.id, NULL,
       'translated-segments-zh-hant.json', 'translated-subtitles-zh-hant.srt', 'terms-zh-hant.json',
       'deadbeef', 'deadbeef', 'deadbeef', NOW()
FROM subtitle_task t
JOIN task_run r       ON r.task_id = t.id AND r.run_type = 'MAIN'
JOIN subtitle_artifact a ON a.task_id = t.id AND a.artifact_type = 'CLEANED' AND a.status = 'READY'
WHERE t.task_no = '$T' LIMIT 1;"

# ── AC1 / AC2 / AC3：带新关键词重新生成 ────────────────────────────
curl -s -X POST localhost:8080/api/tasks/$T/subtitles/regenerate \
     -H 'Content-Type: application/json' -d '{"keywords":["某某专有名词"]}'
# 等 lastRun.status=SUCCEEDED
$DB "SELECT artifact_type, lang, revision, status, keywords_fingerprint,
            segments_file_name, context_file_name, terms_file_name,
            segments_sha256 IS NULL, subtitles_sha256 IS NULL,
            context_sha256 IS NULL, terms_sha256 IS NULL
     FROM subtitle_artifact
     WHERE task_id = (SELECT id FROM subtitle_task WHERE task_no='$T')
     ORDER BY artifact_type, lang, revision;"
#   → CLEANED rev1 SUPERSEDED，八个文件/哈希列全 NULL（最后一列全 1）
#   → CLEANED rev2 READY，keywords_fingerprint = 新关键词的指纹
#   → TRANSLATED zh-Hant DELETED
#   → ORIGINAL 仍 rev1 READY，哈希列原样（AC1）
ls $D/translated-segments-zh-hant.json    # 不存在（AC3）
curl -s localhost:8080/api/tasks/$T/artifacts   # TRANSLATED 消失；CLEANED 的 revision 与指纹已换
curl -s localhost:8080/api/tasks/$T/keywords    # fingerprint 与 CLEANED 行相等

# ── AC4：内容没变不级联 ──────────────────────────────────────────
# 再照上面造一行 TRANSLATED READY + 三个文件
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'      # 不带关键词
# 等 SUCCEEDED
$DB "SELECT artifact_type, revision, status, keywords_fingerprint FROM subtitle_artifact
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T')
     ORDER BY artifact_type, revision;"
#   → CLEANED rev3 READY，但哈希与 rev2 相同（比对 segments_sha256 即可）
#   → TRANSLATED zh-Hant **仍是 READY**、三个文件还在
#   （日志里有「字幕清洗断点复用跳过」）

# ── AC5：MAIN 真重洗时级联 ───────────────────────────────────────
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp","keywords":["另一个词"]}'
# 等 SUCCEEDED
#   → CLEANED rev4 READY 且 segments_sha256 与 rev3 不同
#   → TRANSLATED zh-Hant 变 DELETED、三个文件被删

# ── AC9：前置与互斥 ─────────────────────────────────────────────
# 无 READY ORIGINAL 的任务（或手工把 original-* 移走）→ 400，且 task_run 没多一行
# 同一 task 连发两次 regenerate → 第二次 409，响应体 {taskNo, runNo}
```

> **造行的 SQL 只是人跑的命令**，不进代码库（父 prd 两条原则：不写任何「只为某一段验收、后面必须删掉」的代码）。
> 造出来的行 `derived_from_run_id` 指向真实 MAIN run、`segments_sha256` 是假值 —— 本段不看哈希内容，只看行状态与文件在不在。S5 落地后这条绕道自然取消。

### 6.4 收尾

逐条核对下面的欠账表 → 报告（含测试条数基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。

---

## 边界矩阵

| 场景 | 表现 |
|---|---|
| RECLEAN 在 LLM 阶段失败（占位之后、写文件之前） | 占位行被收口标 `FAILED`；旧 `CLEANED` 与下游译文**一点没动** |
| RECLEAN 在原子替换之后、W5 事务之前崩 | 盘上是新清洗内容、DB 说旧 `CLEANED` `READY`（残留窗口 ①）；重跑收敛 |
| W5 事务任一步抛异常 | 三条写全回滚；旧 `CLEANED` 仍 `READY`、下游译文仍 `READY`；重跑收敛 |
| W5 提交后、删文件前崩 | 下游译文行 `SUPERSEDED` 且文件列还在（对账 ② 报出）；下次触发重洗或对账处理 |
| 删文件抛 IOException | 该行停在 `SUPERSEDED`（**不标 `DELETED`**）+ WARN；其余行照常回收 |
| 回收段抛运行时异常（`markDeleted` 落库失败等） | 只记 WARN，**run 照常判成功** —— 新版已 `READY` 且事务已提交，回收没做完不该让用户看到 `FAILED`；那一行留给对账 ② |
| 下游行某个文件列为 `NULL`（异常数据） | 同上：不标 `DELETED` + WARN |
| 内容没变（三个哈希全同） | 换版照做（旧 `CLEANED` → `SUPERSEDED` 清列、新行 `READY`），**下游译文不动** |
| 旧 `CLEANED` 行哈希列为 `NULL` | 视为「内容变了」→ 级联（安全方向） |
| 下游行是 `READY` 但盘上文件已被手工删 | 级联照做（只看 DB 行）；回收时 `deleteIfExists` 空操作 → 标 `DELETED` |
| 下游行已是 `SUPERSEDED` 且文件还在（上次崩） | 级联只挑 `READY`，这条不碰；留给对账 ② |
| 盘上有译文文件、表里没有（孤儿） | **不再被重洗清理**（prd T3 的代价）；由 S7 对账 ③ 报出，人工回收 |
| 同一 task 并发 RECLEAN | `uk_active_run` 撞键 → 409；store 不处理并发 |
| `MAIN` 与 `RECLEAN` 并发 | 同上（互斥键保证不可能同时跑） |

---

## 回滚点

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| **步骤 1 之后** | 新方法全在但零调用点 | 零代价 |
| **步骤 2 之后** | 主管线的 `CLEANED` 落定走 W5 | 零代价；`TRANSLATED` 行此时还不存在，级联是空操作 |
| **步骤 3 之后** | 前置守卫返回产物 | 零代价；纯重构 |
| **步骤 4 之后** | RECLEAN 写 artifact 表；coordinator 不再删文件 | 零代价；可单独回滚 |
| **步骤 5** | 只动文档 | 单独可回滚 |

**彻底回退**：`git revert` 本段提交；无表结构变更，不需要 DROP / 重建。已写入的行（多出来的 `CLEANED` revision）可留（旧代码读不出问题）或手工清空。

---

## 欠账清单（本段结束时哪些东西还没到位、交给哪一段）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | **TRANSLATE 不动 artifact 表**：译文文件在盘上，但 `TRANSLATED` 行没有任何代码会产出，`/artifacts` 不列、下载 404 | **S5** | 版本落定（W4）+ 复用判据只认 `status` |
| 2 | **`TRANSLATED` 行的级联路径没有真实数据走过**：本段的 AC3 / AC4 / AC5 靠手工 `INSERT` 造行验的 | **S5** | S5 落地后重跑一遍 AC3 / AC4 / AC5，用真译文行 |
| 3 | 复用判据仍是「文件在不在」，`intermediate_file` 零登记 | **S6** | C62 改造 |
| 4 | 内容指纹两列仍恒为 `NULL`；内容变更级联未做 | **S6** | 指纹探测阶段 |
| 5 | 哈希列只写不校；对账端点不存在 | **S7** | 只读对账 + 哈希比对 |
| 6 | **无 DB 行的孤儿译文文件不再被重洗清理**（删文件改由 DB 行驱动） | **S7** | 对账 ③ 报出，人工回收 |
| 8 | 网络调用无超时 | **S8** | C46 订正 |
| 9 | 存量工作区没有 artifact 行（S3 及以前） | **不做** | 本地数据可弃；重新提交即产出 |
| 10 | 前端一定点不通（新端点未接） | **另开任务**（C60） | 后端全部段做完再动 |
| 11 | **三条执行体将来要拆成三条独立管线**（主任务 / 重清洗 / 翻译），届时「重新提交会不会作废译文」这条判据要重审 | **未排期**（用户 2026-10-19 记） | 本段先按「内容真变了才作废」落地 |

**收尾动作**：跑完步骤 6 → 逐条核对上表 → 报告（含测试基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。
