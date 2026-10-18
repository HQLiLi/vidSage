# S1 执行计划：数据层地基

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。** 本文件只写「按什么顺序做、每步的判据在哪、怎么验、崩了怎么退」。
> **本文件是执行参考稿，不是契约**：判据以 design.md 与代码为准；发现本文件与 design 冲突时，以 design 为准并回来改这里。
> 范围与验收标准见 [prd.md](./prd.md)。**本段不动任何调用方、不删任何旧件、不引 `@Transactional`。**

## 段内顺序（九步，每步结束都能 `clean test-compile` 过）

```
0 预期目录结构过审（门）  →  1 schema.sql 五张表  →  2 domain/workspace/
  →  3 domain/subtitle/ 枚举与值对象  →  4 五个领域实体 + 实体单测  →  5 五个端口
  →  6 持久化三件套 ×5 + 仓储单测  →  7 全量验证  →  8 收尾：spec 同步 + 欠账核对
```

顺序的理由只有一条：**后面的每一步只依赖前面已落地的类型**。表先于领域（DDL 是唯一形状定义）、值对象先于实体（实体持有它们）、实体先于端口（端口签名用它们当参数与返回）、端口先于实现（实现 `implements` 它）。步骤 8 排在最后，因为 spec 要照**已落地的代码**写，不能照计划写。

---

## 步骤 0：先把预期目录结构过一遍（门）

**这是一道门，不是准备工作。** 本段一次新增四十来个文件，**形状**定错了改起来是全场返工；所以先把下面这棵树给项目所有者过一眼，**点头之后才开分支、才进步骤 1**（先例：2026-10-06 改包结构时，同样是先看目录结构再动手）。

### 预期目录结构（S1 结束后）

```
backend/src/main/resources/
└── schema.sql                                     [改] 追加五张表

backend/src/main/java/com/vidsage/
├── domain/
│   ├── workspace/                                 [新包] 媒体身份上下文
│   │   ├── package-info.java                      [新]
│   │   ├── Workspace.java                         [新] 媒体身份实体
│   │   ├── model/
│   │   │   ├── SourceType.java                    [新] URL / UPLOAD
│   │   │   └── SourceKey.java                     [新] 值对象（bvid|p 或内容 sha256）
│   │   └── port/
│   │       └── WorkspaceRepository.java           [新]
│   └── subtitle/                                  [已存在] 只往根与两个桶里加，不动存量
│       ├── SubtitleTask.java                      [新] 身份实体（无状态列）
│       ├── TaskRun.java                           [新] 状态机（design §4.1）
│       ├── SubtitleArtifact.java                  [新] 状态机（§4.2）—— 与 model/common/ 的旧 record 同名不同包，S2 让名
│       ├── IntermediateFile.java                  [新] 状态机（§4.3）
│       ├── model/                                 [已存在]
│       │   ├── TaskRunType.java                   [新]
│       │   ├── TaskRunStatus.java                 [新]
│       │   ├── ArtifactType.java                  [新]
│       │   ├── SubtitleArtifactStatus.java        [新]
│       │   ├── IntermediateFileType.java          [新]
│       │   └── IntermediateFileStatus.java        [新]
│       └── port/                                  [已存在]
│           ├── SubtitleTaskRepository.java        [新]
│           ├── TaskRunRepository.java             [新]
│           ├── SubtitleArtifactRepository.java    [新]
│           └── IntermediateFileRepository.java    [新]
└── infrastructure/persistence/
    ├── entity/                                    [已存在] 加五个
    │   ├── WorkspaceEntity.java                   [新]
    │   ├── SubtitleTaskEntity.java                [新]
    │   ├── TaskRunEntity.java                     [新]
    │   ├── SubtitleArtifactEntity.java            [新]
    │   └── IntermediateFileEntity.java            [新]
    ├── mapper/                                    [已存在] 加五个
    │   ├── WorkspaceMapper.java                   [新]
    │   ├── SubtitleTaskMapper.java                [新]
    │   ├── TaskRunMapper.java                     [新]
    │   ├── SubtitleArtifactMapper.java            [新]
    │   └── IntermediateFileMapper.java            [新]
    └── repository/                                [已存在] 加五个
        ├── MybatisWorkspaceRepository.java        [新]
        ├── MybatisSubtitleTaskRepository.java     [新]
        ├── MybatisTaskRunRepository.java          [新]
        ├── MybatisSubtitleArtifactRepository.java [新]
        └── MybatisIntermediateFileRepository.java [新]

backend/src/test/java/com/vidsage/                          （测试包与主包镜像对应）
├── domain/
│   ├── subtitle/
│   │   ├── TaskRunTest.java                       [新] 状态迁移逐值覆盖
│   │   ├── SubtitleArtifactTest.java              [新] 同上
│   │   └── IntermediateFileTest.java              [新] 同上
│   └── workspace/model/
│       └── SourceKeyTest.java                     [新] 值对象不变量（先例：WorkspaceKeyTest / SourceUriTest）
└── infrastructure/persistence/repository/
    ├── MybatisWorkspaceRepositoryTest.java        [新]
    ├── MybatisSubtitleTaskRepositoryTest.java     [新]
    ├── MybatisTaskRunRepositoryTest.java          [新]
    ├── MybatisSubtitleArtifactRepositoryTest.java [新]
    └── MybatisIntermediateFileRepositoryTest.java [新]
```

**不动的**：`domain/task/`、`domain/media/`、`domain/transcription/`、`domain/shared/`、`application/`、`web/`、`config/`、`shared/` **一处不动**（AC2 要拿 `git diff --stat` 核）。`domain/workspace/` 下**不建空的测试目录** —— 测试类只落在上面这几个真有类的路径（「目录随代码出现，不预建空目录」）。

**这棵树要对照的三条**

1. **新包只有一个** `domain/workspace/`；`domain/subtitle/` 是往已存在的包里加类，不是新包 —— 别顺手给它换个名或挪位置。
2. **`persistence/` 仍是三个子包平铺**（`entity` / `mapper` / `repository`），**不按表分领域二级包**（需求 17）。
3. **测试包严格镜像主包**（`XxxTest` ↔ `Xxx`，同包路径），没有「测试专用」的额外层级。

### 过审之后

当前分支是 `dev`。本段既碰代码（`schema.sql` + 新 Java 文件）又碰规范（步骤 8），按 [git-workflow](../../../.trellis/spec/guides/git-workflow.md) 的 v5 口径**两条都要开 feature 分支**（从 `dev` 切，`feature/subtitle-model-foundation`）—— 不能直接在 `dev` 上做。

---

## 步骤 1：`schema.sql` 加五张表

**改哪个文件**：`backend/src/main/resources/schema.sql`（本段唯一被改的旧文件）。

**做什么**

1. 在 `subtitle_keyword` 之后追加五张表，顺序 `workspace` → `subtitle_task` → `task_run` → `subtitle_artifact` → `intermediate_file`（跟着依赖顺序排，人读得顺）。
2. DDL **逐字照抄** design §1.1 / §1.2 / §1.3 / §1.4 / §1.6 —— 列注释、`CONSTRAINT` 具名、`ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`、表注释一处不省。抄错一个索引名，父任务的 AC1 就不成立。
3. 头部注释补存量迁移说明（见 prd 需求 6）：本版只新增五张表、旧库直接跑即可；`subtitle_keyword` 的列名与类型会在 S2 变，届时旧库必须手工 `DROP TABLE subtitle_keyword` 一次。
4. `task` 与 `subtitle_keyword` 两张旧表的定义**一个字节都不动**。

**三个容易抄错的点**

| 点 | 对的 | 错的 |
|---|---|---|
| 枚举列 | `VARCHAR(32)`（N6） | 跟旧 `task` 表一样用 `ENUM(...)` |
| 唯一约束写法 | `CONSTRAINT uk_xxx UNIQUE (...)` | `UNIQUE KEY` / `UNIQUE INDEX` |
| 生成列 | `active_flag VARCHAR(32) GENERATED ALWAYS AS (IF(status = 'RUNNING', 'RUNNING', NULL)) STORED` | 省掉 `STORED`、或写成普通列 |

**自检**：起一次服务（`spring.sql.init.mode: always` 会跑本文件），不报错即 DDL 语法与重名约束都被 MySQL 接受；再起一次，幂等不报错。列/索引名对版见步骤 7。

---

## 步骤 2：`domain/workspace/`

| 文件 | 内容 |
|---|---|
| `package-info.java` | 照 `domain/task/package-info.java` 的写法：这个上下文装什么、`model/` 与 `port/` 各装什么 |
| `model/SourceType.java` | `URL` / `UPLOAD`（design §3：原 `TaskInputType` 改名，对齐 `workspace.source_type` 列） |
| `model/SourceKey.java` | 值对象（`record`）：`bvid\|p` 或内容 sha256 |
| `Workspace.java` | 根：媒体身份实体 |

**`SourceKey` 本段只落不变量**（非空白 + 不超 `VARCHAR(512)` 的列宽）；**形态构造**（`bvid\|p` 的拼装、sha256 的判定、剥跟踪参数）是 `MediaIdentityResolver` 的活，属 S2。别在这儿提前实现 C7–C12。

**`Workspace` 的字段**（照 design §1.1）：`id` / `sourceType` / `sourceKey` / `sourceUri` / `canonicalUrl` / `durationMs` / `publishedAt` / `createdAt` / `updatedAt`。
- 内容指纹**不另拼一列串**（C27），就是 `durationMs` + `publishedAt` 两个可读列。
- `sourceUri` 的字段 Javadoc 必须写脱敏红线（照 `VideoTask.sourceUri` 的原话：本字段值不得直接写入日志）。
- 入口：`of(SourceType, SourceKey, String sourceUri, String canonicalUrl)`（新造，两列指纹为 `null` —— 首跑就是这样，C48）+ `restore(...)`（persistence 还原）。

---

## 步骤 3：`domain/subtitle/` 的枚举与值对象

**七个枚举**（落 `domain/subtitle/model/`，与 `TaskNo` 同层）：

| 枚举 | 值 | 依据 |
|---|---|---|
| `TaskRunType` | `MAIN` / `RECLEAN` / `TRANSLATE` | design §1.3 `run_type` |
| `TaskRunStatus` | `RUNNING` / `SUCCEEDED` / `FAILED` / `INTERRUPTED` | design §1.3 + §4.1（**没有 `PENDING`**） |
| `ArtifactType` | `ORIGINAL` / `CLEANED` / `TRANSLATED` | design §1.4 |
| `SubtitleArtifactStatus` | `CREATING` / `READY` / `SUPERSEDED` / `FAILED` / `DELETED` | design §4.2 |
| `IntermediateFileType` | `SOURCE_MEDIA` / `INFO_JSON` / `AUDIO` | design §1.6（**没有 `ASR_RAW`**，C50 订正过） |
| `IntermediateFileStatus` | `READY` / `SUPERSEDED` / `DELETED` | design §4.3（**只有三个值**） |

**命名口径**（design 钉了三个名：`SourceType` / `TaskRunStatus` / `IntermediateFileType`，其余四个是本段按同一条规则推的，写在这儿免得实施时另起一套）：
**列名自足就用列名，不自足就补表名** —— `task_run.status` → `TaskRunStatus`（`status` 太泛）、`task_run.run_type` → `TaskRunType`、`workspace.source_type` → `SourceType`（design 定名）、`subtitle_artifact.artifact_type` → `ArtifactType`（列名自带 `Artifact` 限定，自足）、`subtitle_artifact.status` → `SubtitleArtifactStatus`、`intermediate_file.file_type` → `IntermediateFileType`（列名里的 `file` 与 `artifact` 一样是自足量词，但 `FileType` 裸名太泛，故补表名）、`intermediate_file.status` → `IntermediateFileStatus`。
**四个「种类」列一律 `_type`**：`source_type` / `run_type` / `artifact_type` / `file_type` —— 模型里同一个概念只用一种说法。
`ArtifactType` / `SubtitleArtifactStatus` / `TaskRunType` / `IntermediateFileStatus` 四个名本段新起，仓库里无同名（已 grep 过）。

**枚举的 Javadoc 首行写人话**（本仓要求全大写常量与枚举常量一律留注释），别写成「状态枚举定义」这类空话。

**值对象**：本段不新增 `domain/subtitle/model/` 下的值对象 —— `TaskNo` 复用现成的那份（见欠账 2），`SubtitleKeywords` 不动。

---

## 步骤 4：五个领域实体 + 实体单测

**样板**：`domain/task/VideoTask` —— 私有构造 + 静态工厂 + `restore` + 迁移方法 + `requireStatus` 守卫 + 时间戳在迁移方法里落。**新写的方法一律动词开头**（directory-structure.md，2026-10-12 立）。

**工厂名分三族**（2026-10-17 立、2026-10-18 补「`of` 必须带实参」，口径同步进 directory-structure.md）：

- **`of(实参…)` / `from(实参…)`** —— 「**由形参构成 / 转变而来**」，所以**必须带实参**。本段的 `Workspace.of` / `SubtitleTask.of` / `TaskRun.of` / `IntermediateFile.ofReady` 都带实参，归这族。另有一条判据是「造出来是不是完整可用」：`TaskRun` 的 `RUNNING` 是正常起点不是半成品、`Workspace` 没有状态列（指纹两列留空是可选元数据，不是占位）—— 都算完整可用。
- **凭空生成（无「由谁而来」）→ 按来源 / 状态命名，不套 `of`** —— 本段的 `TaskNo.random()`；既有先例 `SubtitleContext.empty()` / `TermLookupResult.none()`（同 JDK 的 `Optional.empty()`）。空参 `of()` 只在「宾语本身可取 0」时合法（`List.of()` ✓）。
- **`createXxx(...)`** —— **初态是半成品**的实体：造出来的东西还缺一块、要靠后续迁移补齐。本段只有 **`SubtitleArtifact.createCreatingArtifact(...)`** —— 它产出的是 `CREATING` 占位行，四列文件列与四列哈希列**全 `null`**（design §1.4 要点 3），要等「换版落定」才可用。存量 `VideoTask.newPending(...)` 是同一族的先例。
- **只有返回 `boolean` 的才配 `is`**。

| 实体 | 工厂 | 迁移方法（每个值恰好一条路径） |
|---|---|---|
| `domain/subtitle/SubtitleTask` | `of(long workspaceId)`（**内部生成 `task_no`**：调 `TaskNo.random()`）+ `restore` | **没有状态可迁** —— 该表无状态列；只有 `recordRunFinished(LocalDateTime at)` 一个变更入口（C45，即原来的 `touchUpdatedAt`） |
| `domain/subtitle/TaskRun` | `of(long taskId, TaskRunType, String targetLanguage)` → 状态 `RUNNING`、`startedAt` = now（§1.3）+ `restore` | `succeed()` / `fail(String errorMessage)` / `updateStage(String stage)` |
| `domain/subtitle/SubtitleArtifact` | `createCreatingArtifact(long taskId, long derivedFromRunId, ArtifactType, String lang, Long derivedFromArtifactId, String keywordsFingerprint)` → 状态 `CREATING`，**文件列与哈希列全 `null`**（§1.4 要点 3）+ `restore` | `completeCreation(八个文件/哈希参数)` / `supersede()` / `confirmDeletion()` |
| `domain/subtitle/IntermediateFile` | `ofReady(long taskId, String fileName, IntermediateFileType, Long derivedFromRunId)` → 状态 `READY` + `restore` | `revive()`（复活，§4.3）/ `supersede()` / `confirmDeletion()` |
| `domain/workspace/Workspace` | 见步骤 2 | `updateFingerprint(Integer durationMs, LocalDateTime publishedAt)`（§7.3 步骤 2.4） |

**2026-10-17 评审轮改名**（用户审查意见：`markReady` 这类名不够清晰；**实体迁移一律改「目标态当动词」**，对齐存量状态机 `VideoTask` 的 `start/succeed/fail`）：

| 旧名 | 新名 | 位置 |
|---|---|---|
| `SubtitleArtifact.ofCreating(...)` | `createCreatingArtifact(...)` | 实体工厂（半成品初态 → `create*` 族） |
| `SubtitleArtifact.markReady(8 参)` | `completeCreation(8 参)` | 实体迁移（对着 `CREATING` 状态名） |
| `SubtitleArtifact.markSuperseded()` | `supersede()` | 实体迁移 |
| `SubtitleArtifact.markDeleted()` | `confirmDeletion()` | 实体迁移（语义是「文件已确认回收」，叫 `delete()` 会被读成「这方法删文件」） |
| `IntermediateFile.markReady()` | `revive()` | 实体迁移（复活 —— design §4.3 自己的词） |
| `IntermediateFile.markSuperseded()` | `supersede()` | 实体迁移 |
| `IntermediateFile.markDeleted()` | `confirmDeletion()` | 实体迁移 |
| `SubtitleTask.touchUpdatedAt(at)` | `recordRunFinished(at)` | 实体（C45 收口；`record*` 是本仓既有动词，而 `touchUpdatedAt` 命名的是列不是事件） |
| `SubtitleArtifactRepository.markReady(artifact)` | `updateReady(artifact)` | 仓储（它不只打标、还写 8 列，与 `insertCreating` 成对） |

**不改的**：仓储那族其余 `mark*`（`markSupersededAndClearFiles` / `markSuperseded` / `markDeleted` / `markCreatingAsFailed*` / `markSupersededByTaskId`，以及 `IntermediateFileRepository.markReady`）—— 它们是「给库里那行盲打标」的独立一族，逐个读得懂；两层同名反而会让调用点分不清自己调的是内存迁移还是写库。仓储的批量 `touchUpdatedAt(ids, at)` 也保留（列级批量操作，收口与启动收口共用）。

**`supersede` 保留**（评审轮问过要不要换成更常用的 `replace`，结论：不换）—— 两条依据：① **`replace` 在本仓已有既定含义且方向相反**：`SubtitleKeywordRepository.replace(workspaceKey, keywords)` 是**覆盖写**（把新内容写进去），而 `supersede()` 干的是反过来的事 —— 新版本已被调用方插好，它只把**旧的**标成「不再是当前版」；② `supersede` 与状态值 `SUPERSEDED` 一一对应（同 `succeed()` ↔ `SUCCEEDED`），且与仓储族的 `markSuperseded*` **共享词根**，两层配对一眼可见 —— 换 `replace` 会断掉这个配对（仓储族已定不动）。

**`revive` 保留**（评审轮问过要不要换成 `regenerate`，结论：不换）—— 关键理由是**成对**：`revive` 与 `supersede` 在英语里是同一根轴的两个方向（被取代 ⇄ 复活），正好对着 design §4.3 那个 **READY ⇄ SUPERSEDED 的双向**迁移（`supersede()` 去、`revive()` 回），名字自解释出这张图。
（附带口径，评审轮由项目所有者定：**撞名本身不构成禁用理由 —— 词靠作用域消除歧义**。所以我当时拿「`regenerate` 已被 RECLEAN 的 `SubtitleCleanupCoordinator.regenerate` / `regenerateSubtitles` 占用」当主依据是权重给高了；真正站得住的反对是**名实不符** —— 这个方法不产出内容，文件是产出方写好的，它只翻状态。）

**2026-10-18 评审轮（第二批）：task 号生成收进 `TaskNo` + `of` 口径订正。**

1. **`SubtitleTask.newTaskNo()` 删掉，改成 `TaskNo.random()`**（`TaskNo` 加一个静态工厂，返回 `TaskNo`）。理由：`SubtitleTask` 与存量的 `VideoTask` 各有一份一模一样的 `UUID.randomUUID().toString().replace("-", "")`，而 `TaskNo` 的 javadoc 本来就写着这套语义（「32 位短 UUID」）—— 本仓「同一判据只有一份」。**存量的 `VideoTask.newTaskNo()` 不动**（S2 随该类整块删，prd 的 S2 行「旧栈整块删」）；于是 `TaskNo` 是本段唯一被改的既有 Java 文件（AC2 白名单 +1）。
2. **`of` 的判据订正**（写进 `directory-structure.md`）：`of(x)` 读作「由 x 构成 / 转变而来」，**必须有实参**；空参只在「宾语本身可取 0」时成立（`List.of()` ✓，`Optional.of()` ✗ → JDK 另给 `empty()`）。所以凭空生成的任务号叫 **`random()`** 而不是 `ofRandom()`。连带撤下 2026-10-12 定的两条待改项（`none` → `ofNone`、`empty` → `ofEmpty`）：那两条本来就对（同 JDK 的 `Optional.empty()`），加 `of` 前缀反而错。

**2026-10-18 评审轮（第一批）：`kind` → `file_type`**（用户审查时发现「同一个概念两个词」）—— `intermediate_file.kind` 改为 **`file_type`**，枚举 `IntermediateFileKind` → **`IntermediateFileType`**。根因是 `kind` 从 handoff 早期那张被 C5 砍掉、又被 C62 救回的 `temp_file` 草稿里继承下来，**没人复核过**；N3 当初只钉了表名与枚举名。现在六张表里四个「种类」列一律 `_type`（`source_type` / `run_type` / `artifact_type` / `file_type`）。字段名 `kind` → `fileType`（MyBatis-Plus 的驼峰→下划线映射自动落到 `file_type`）。**假朋友**：`LlmSubtitleCleanser` 的 `ValidationFailure.kind` / `FailureKind` 与此无关，不动。

**2026-10-17 评审轮改名（第二批）**：私有状态守卫 `requireStatus` → **`validateStatus`**（`SubtitleArtifact` / `IntermediateFile`），`requireRunning` → **`validateRunning`**（`TaskRun`，同族特化版，签名形状不变）。口径已写进 `error-handling.md` 的「前置守卫的命名」：**不返回值的守卫叫 `validateXxx`**；而 `requireFfmpeg()` / `requirePublicUrl()` / `requireTitle()` 这类**取值即抛**的访问器保留 `requireXxx`（它们返回一个值，`validate` 不承诺返回）。存量四处 void 守卫（`VideoTask.requireStatus` / `requireMainSucceededForPostLifecycle`、两处 `requireTaskNo`）记在那份文档的待改清单里，**本段不碰**（动旧文件会破 AC2）。

**三条必须守住的**

1. **一个值只给一条写入路径**（D-6）。所以：`TaskRun` **不给 `interrupt()`** —— `INTERRUPTED` 的唯一路径是启动收口的批量 UPDATE（§4.1、§7.6）；`SubtitleArtifact` 的 `FAILED` 有两条路径（C61 收口 / C15 启动收口），但**实体只承载正常路径**，启动收口那条是盲批量 UPDATE，不走实体。
2. **`restore` 必须接受任何状态**（含 `INTERRUPTED` / `FAILED` / `SUPERSEDED`），且**不做迁移校验** —— 它读的是已落库的事实，不是「迁过去」。校验只在迁移方法里。
3. **`run_no` 与 `revision` 落库前是 `null`**（由仓储按 `MAX+1` 生成后回填，§1.3 / §1.4），跟 `id` 一样是「数据库生成、回填进领域对象」的字段。别在实体里自己算。

**`SubtitleArtifact.completeCreation` 的签名**：四个文件列 + 四个哈希列共 8 个参数，丑但直白。若实施时觉得读不动，**允许**改成一个小 `record`（`domain/subtitle/model/` 下，把「文件列 + 它的 sha256」配成一对，正好把 §8.4 纪律 2「哈希跟着文件列同生同灭」表达成结构）—— 二选一，实施时定，**在收尾说明里写清选了哪个**。

**实体单测**（镜像到 `src/test/java/com/vidsage/domain/...`）：**只有三个带状态机的实体开测试类**（`TaskRun` / `SubtitleArtifact` / `IntermediateFile` —— design §4 只有这三处状态）。按 design §4 的表逐值覆盖 —— 每条合法迁移验「终态 + 时间戳落位」，每条非法迁移（如 `SUCCEEDED` 再 `succeed()`、`CREATING` 直接 `markDeleted()`）验抛 `IllegalStateException`。异常路径的测试命名照 [quality-guidelines.md](../../../.trellis/spec/backend/quality-guidelines.md) 的 `throwsWhen...` 模式。`SubtitleTask`（无状态列）与 `Workspace`（只有 `updateFingerprint`）不单开测试类，随各自的 Repository 单测一起验。

---

## 步骤 5：五个端口

落 `domain/workspace/port/`（1 个）与 `domain/subtitle/port/`（4 个）。**端口无框架注解、纯契约**（directory-structure.md）。`SubtitleKeywordRepository` 本段不动。

**全局约定（所有写入方法适用）**

- **时间戳由调用方传入**（照 `SubtitleKeywordMapper.replaceByWorkspaceKey` 的样板），不在仓储里取 `now()` —— 单测能断言落位，S2 的编排层也能把「同一次收尾」的多个时间戳对齐。
- **带 `updated_at` 的表，每条 UPDATE 都同句刷它**（design §1.5 的通用口径）。
- **`insert` 一律返回回填了自增 id 的那份**（照 `VideoTaskRepository.insert` 样板），调用方以返回值为准。

### 5.1 `WorkspaceRepository`

| 方法 | 依据 |
|---|---|
| `Workspace insert(Workspace)` | §7.1 步骤 3 未命中分支（W1 两表同插的一半） |
| `Optional<Workspace> findById(long)` | §8.1 的读路径公式要 `source_key` 才算得出目录名；管线从 task 拿到 `workspace_id` 后取它 |
| `Optional<Workspace> findBySourceTypeAndSourceKey(SourceType, SourceKey)` | §7.1 步骤 3 命中分支 |
| `Optional<Workspace> findForShareBySourceTypeAndSourceKey(SourceType, SourceKey)` | §7.1 步骤 3 的并发分支（C63）：**锁定读**，`FOR SHARE` 不是 `FOR UPDATE` |
| `int updateFingerprint(long workspaceId, Integer durationMs, LocalDateTime publishedAt, LocalDateTime now)` | §7.3 步骤 2.4（W5′ 的另一半）；`now` 同句刷 `updated_at`。首跑两列由 `null` 写入也是走它（C48） |

### 5.2 `SubtitleTaskRepository`

| 方法 | 依据 |
|---|---|
| `SubtitleTask insert(SubtitleTask)` | §7.1 步骤 3（W1 的另一半） |
| `Optional<SubtitleTask> findByTaskNo(TaskNo)` | §6 `GET /api/tasks/{taskNo}` |
| `Optional<SubtitleTask> findByWorkspaceId(long)` | §7.1 步骤 3 命中分支（走 `uk_subtitle_task_workspace_id`） |
| `Optional<SubtitleTask> findForShareByWorkspaceId(long)` | C63：**两行都要锁定读**，否则 `subtitle_task` 读空只能去 INSERT 再撞一次 `uk_workspace` |
| `int touchUpdatedAt(Collection<Long> taskIds, LocalDateTime at)` | C45（每次 run 收尾）+ C15 第三条（启动收口批量）—— 一个方法覆盖单条与批量两种调用 |

### 5.3 `TaskRunRepository`

| 方法 | 依据 |
|---|---|
| `Optional<TaskRun> insertRunning(TaskRun)` | W2 / §7.7 抢互斥。**撞 `uk_active_run` 返回空**（prd 需求 14 已定口径），仓储内 catch `DuplicateKeyException` 不外抛。`run_no` 在这里按 `MAX+1` 生成并回填（§1.3） |
| `Optional<TaskRun> findLatestByTaskId(long)` | §6 的 `lastRun`：**含正在跑的那条**，按 `run_no` 倒序取一条；从未执行 → 空 |
| `int update(TaskRun)` | §7.3 步骤 1（推进 `current_stage`）与 C43 收口（`fail(...)` 之后整体回写）。照 `VideoTaskRepository.update` 样板 |
| `List<Long> interruptAllRunning(LocalDateTime at)` | §7.6 启动收口第一条：残留 `RUNNING` → `INTERRUPTED` + `finished_at`。**返回被收掉的 `task_id`**（第三条 UPDATE 要用），同时供「上个进程有 N 个任务没跑完」的日志 |

### 5.4 `SubtitleArtifactRepository`

| 方法 | 依据 |
|---|---|
| `SubtitleArtifact insertCreating(SubtitleArtifact)` | W3 / §7.3 步骤 3（C19 占位）。`revision` 在这里按 `MAX+1` 生成并回填（§1.4） |
| `Optional<SubtitleArtifact> findReadyByTaskIdAndTypeAndLang(long, ArtifactType, String lang)` | §7.5 步骤 3 的复用判据、§7.8 读路径只认 `READY` |
| `List<SubtitleArtifact> findReadyByTaskId(long)` | §6 C54：`/artifacts` 只给当前有效产物 |
| `int updateReady(SubtitleArtifact)` | C41 换版落定第 4 步：**一条 UPDATE 同时写四列文件列 + 四列哈希列 + `READY`**（§8.4 哈希三条纪律）。**2026-10-17 评审轮**：原叫 `markReady` —— 它不只打标、还写 8 列，改 `update` 与 `insertCreating` 成对；同族的其余 `mark*`（纯状态翻转）保持不变 |
| `int markSupersededAndClearFiles(long artifactId)` | C41 换版落定第 3 步 / C24：**覆盖型**（同 `(task,type,lang)` 换版）—— 清空文件列与哈希列，停在 `SUPERSEDED` |
| `int markSuperseded(long artifactId)` | §7.4 步骤 6② 级联：**孤儿型**（下游译文）—— 只标状态，**文件列留着**（C52：路径是 DB 的列，删文件时要用） |
| `int markDeleted(long artifactId)` | C22 两阶段第二步（文件确认回收之后） |
| `int markCreatingAsFailedByRunId(long runId)` | C61 收口：本次 run 插的、仍停在 `CREATING` 的行 → `FAILED`（靠 `derived_from_run_id` 定位） |
| `int markCreatingAsFailed()` | C15 启动收口第二条：残留 `CREATING` → `FAILED`（无差别批量） |

`markSupersededAndClearFiles` 与 `markSuperseded` **是两个方法不是两个重载** —— 它们的差别是「清不清文件列」，而那正是 C23 两种终态的分界，名字里必须看得见。

### 5.5 `IntermediateFileRepository`

| 方法 | 依据 |
|---|---|
| `IntermediateFile insertReady(IntermediateFile)` | §4.3 首次产出（`READY` 是唯一入口；本表**没有 `CREATING`**） |
| `List<IntermediateFile> findByTaskId(long)` | §7.3 步骤 2③ 级联要遍历该 task 的全部中间产物 |
| `Optional<IntermediateFile> findReadyByTaskIdAndFileName(long, String)` | C62 复用判据：「表里 `READY` **且**文件在」 |
| `int markReady(long id, LocalDateTime at)` | §4.3 重新产出：`SUPERSEDED` / `DELETED` → `READY`（复用同一行，`uk_intermediate_file_task_id_file_name` 唯一） |
| `int markSupersededByTaskId(long taskId, LocalDateTime at)` | §7.3 步骤 2③（W5′）：指纹变了 → 中间产物全标 `SUPERSEDED`（**只标 `READY` 的**，`DELETED` 的不回退） |
| `int markDeleted(long id, LocalDateTime at)` | C22 两阶段：中间产物**没有文件列**可清空，所以必须「删文件 → 标 `DELETED`」 |

> **2026-10-17 订正**：上面三个方法原本写的是 `markReady(long id)` 这种不带 `at` 的形状，与「全局约定：时间戳由调用方传入」自相矛盾 —— 实施时子 agent 照方法表落成了「仓储内取 `now()`」，check 阶段查出。改收 `at` 的三条理由：① 另外四个端口的写入方法**都**收 `at`（`updateFingerprint` / `touchUpdatedAt` / `interruptAllRunning` / `update(TaskRun)`），只有这一处不收，S2 的接线者会当场犯疑；② W5′ 是一个事务里同时动 artifact 级联、中间产物级联与 `workspace` 指纹，**同一个时刻**才好对账；③ 收 `at` 单测才能钉住时间戳，不然只能断言 `any(LocalDateTime.class)`。
> 另：`markSupersededByTaskId` 的过滤条件是 `status = 'READY'`（design §4.3 里 `SUPERSEDED` 的唯一入边是 `READY`；不过滤会把已 `DELETED` 的行「复活」成 `SUPERSEDED`）。

**S7 才要的扫表查询（C65 四类不一致）本段不落** —— 它们是那个只读端点的读路径，且要等 `intermediate_file` 登记（S6）之后才有意义。

---

## 步骤 6：持久化三件套 ×5 + 仓储单测

**落点**：`infrastructure/persistence/{entity,mapper,repository}/`。命名与可见性照 [directory-structure.md](../../../.trellis/spec/backend/directory-structure.md)「持久化三层」：`WorkspaceEntity` / `WorkspaceMapper` / `MybatisWorkspaceRepository`，以此类推。

**照抄的样板**：`SubtitleKeywordEntity` / `SubtitleKeywordMapper` / `MybatisSubtitleKeywordRepository`（三件套里最短的一份）+ `MybatisVideoTaskRepository`（值对象转换与 `insert` 回填 id）。

**八条实现约定**

1. Entity：`@TableName` + `@Getter/@Setter`（**不用 `@Data`**）+ `@TableId(type = IdType.AUTO)`；字段用 `LocalDateTime` / Java enum，值对象（`TaskNo` / `SourceKey` / `SourceType`）一律落成 `String` / enum 列。
2. **生成列 `active_flag` 不映射进 Entity** —— 它是 DB 算出来的，插入与更新都不带它。
3. 转换（Entity ↔ 领域对象）**只出现在 Repository**，Mapper / SQL / Entity 对它无感知（database-guidelines.md 的原话）。
4. `run_no` / `revision` 的 `MAX+1` 在 Repository（先 `SELECT MAX` 再 INSERT）；并发下撞键的兜底见 design §1.3 的「残留一条理论上的缝 —— 接受，不修」。
5. 定向 UPDATE（`markReady` / `markSuperseded*` / `markDeleted` / `touchUpdatedAt` / `updateFingerprint` / `interruptAllRunning`）**手写 `@Update`**，照 `SubtitleKeywordMapper.replaceByWorkspaceKey` 的样板；其余单表 CRUD 走 `BaseMapper` + `Wrappers`。
6. **锁定读**（两个 `findForShare...`）用 `@Select` 手写或 `.last("FOR SHARE")`，**不许用 `FOR UPDATE`**（C63：duplicate-key 失败已在索引记录上留了 S 锁，`FOR UPDATE` 要 X 锁就变成 S→X 升级 → 两个撞键者互等 → 死锁）。
7. `insertRunning` 的 `catch (DuplicateKeyException)` **必须在仓储方法内部、不外抛** —— 不跨 Spring 代理边界就不会把事务标成 rollback-only（C63 的两条落地约束之一）。本段还没有事务，但这条约定 S2 接线时仍然成立，所以现在就得写对。
8. 批量 UPDATE（`interruptAllRunning` / `markCreatingAsFailed` / `markSupersededByTaskId` / `touchUpdatedAt`）用 `IN (...)` 或按 `task_id` 过滤的**一条语句**，不许在仓储里写循环。

**仓储单测**（`src/test/java/com/vidsage/infrastructure/persistence/repository/`，镜像主包）：Mockito mock Mapper，**不连真库**。每个 Repository 覆盖 —— 实体 ↔ 领域对象**逐字段**双向转换、`insert` 回填 id（以及 `run_no` / `revision`）、查询条件（`ArgumentCaptor` 抓 Wrapper / 参数）、`Optional` 的空与非空两条路径、`insertRunning` 撞键返回空。

`LambdaQueryWrapper` 的列名解析走 `TableInfo` 缓存，纯单测要在 `@BeforeAll` 手工注册一次：

```java
TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), XxxEntity.class);
```

**真库连通不靠单测**：`VidSageApplicationTests` 标 `@ActiveProfiles("local")` 那条冒烟负责。

---

## 步骤 7：全量验证

```bash
# 1) 判编译 —— 增量编译会给假绿，一律 clean
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 2) 全量测试 —— 先删陈旧报告，否则 surefire 会数进幽灵测试
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/target/surefire-reports/*.xml

# 3) 起服务（真库跑 schema.sql）；起两次，验幂等
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q -Dmaven.test.skip=true spring-boot:run
```

**注意**：`spring-boot:run` 绑 `test-compile`，测试源码红着根本起不来 —— 只跑 main 要加 `-Dmaven.test.skip=true`（`-DskipTests` **不够**）。

**AC1 对版**：用惯用的 MySQL 客户端（CLI / Workbench）`SHOW CREATE TABLE` 五张新表，逐列逐索引与 design §1 对。这一步**必须人眼看**，起服务成功只证明语法合法、不证明索引名对。

**AC6 旧契约回归**：起服务后提交一个 URL → 202 → 旧形状 `GET /api/tasks/{taskNo}` → 跑完。这一步验的是「新地基没碰坏旧栈」—— 本段一行调用方代码都没动，它理应原样通过；**如果它红了，说明 `schema.sql` 改出了副作用（如误删旧表定义），优先查这里**。

---

## 步骤 8：spec 同步 + 欠账核对

**改哪个文件**：`.trellis/spec/backend/database-guidelines.md`。

**为什么本段就要改，不等 S8**（2026-10-17 定）：该文档的「建表契约（现状：`task` 表）」一节整段是照旧 `task` 表写的，其中「状态等低基数列用 MySQL `ENUM`，与 Java enum 按名映射」与 design §2.1 的 N6（`VARCHAR(32)`）**直接冲突**；「时间列四件套」也不成立 —— 新六表没有一张同时有那四列。S2–S7 的实现读 spec 时拿到的会是错规则，等 S8 太晚。

**怎么做**：按本仓的订正惯例 —— **spec 是现状文档，直接重写为现状**（不写删除线、不留旧说法；prd 才用删除线 + 日期）。这一节要覆盖的现状：

| 现状 | 出处 |
|---|---|
| 枚举列一律 `VARCHAR(32)`，不用 `ENUM`、也不用 `CHECK`（加值零 DDL） | design §2.1 / N6 |
| 具名约束：`pk_` / `uk_` / `idx_` 带表名段、主键具名、唯一约束写 `CONSTRAINT ... UNIQUE` | design §2 / N4 |
| 生成列 + 唯一索引做「部分唯一」（`active_flag`）是互斥/当前版的全部机制 | design §1.3 / §1.4、C14 |
| 时间列不再「四件套」：按表各取所需，`updated_at` 的写入点 = 同一条 UPDATE 里一起刷 | design §1.5 的通用口径 |
| 表清单从 1 张（`task`）变成六张（`task` 由 S2 删） | design §1 |
| `source_uri` 的脱敏红线（不得写日志） | 现状已有，别改丢 |

**别把 spec 写成第二份 design**：`database-guidelines.md` 管的是「本仓数据库侧契约」，不抄 DDL 全文、不抄 `C*` 编号 —— 指向 design 的那几节即可（design 是任务工件，归档后仍在仓库里）。

**同一步做完欠账核对**：跑完步骤 7 → 逐条核对下面的欠账表 → 报告（含「`markReady` 8 参还是小 record」这个实施时定的选择）→ **停等审查，不自行提交/合并**。

---

## 回滚点

**本段全增量、无数据迁移、无调用方改动**，所以回滚就是「丢弃这批新增文件 + `git checkout -- backend/src/main/resources/schema.sql .trellis/spec/backend/database-guidelines.md`」。旧库会留下五张空表 —— 无害（下次 `CREATE TABLE IF NOT EXISTS` 照样幂等）；要彻底清干净就手工 `DROP TABLE` 那五张（§9.4i：存量可弃）。

**步骤 8 单独可回滚**：spec 那一节与代码没有耦合（它只是描述现状），改了不合适就单独回退它一个文件，代码不用动。

**段内三个干净落点**（做一半要停就停在这三处，别停在中间）：

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| 步骤 1 后 | 只有 DDL | 五张空表躺在库里，零代码引用 |
| 步骤 3 后 | 表 + 领域词汇 | 枚举与值对象无引用 |
| 步骤 6 后 | 全套实现 + 单测 | 已可独立验收（AC1–AC5） |

---

## 欠账清单（本段结束时哪些东西还没到位、交给哪一段）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | `subtitle_keyword` 换锚点 + **新增 `updated_at` 列**（design §1.5 定案「加」）+ 它的写入点 —— 三件事 | **S2** | 一起做：改列名与类型、净增 `updated_at`、upsert 里同句刷它（`ON DUPLICATE KEY UPDATE keywords = ?, updated_at = ?`）。**本段不动这张表**（需求 6）—— 它既改列名又改类型，只能手工 `DROP` 后重建，列由那次重建带进来 |
| 2 | `TaskNo` 复用 `domain/task/model/TaskNo` → `domain/subtitle → domain/task` 是一条**临时依赖** | **S2** | 搬类时改 import（`SubtitleTask` + 它的 Entity/Repository 转换处）。~~javadoc 那句「由 `VideoTask#newPending` 生成」~~ **已在 2026-10-18 评审轮改掉**（连 `random()` 工厂一起），这条只剩搬类 |
| 3 | 三对同名/同义并存：`domain/subtitle/SubtitleArtifact`（新实体）vs `domain/subtitle/model/common/SubtitleArtifact`（旧 record）；`SourceType` vs `TaskInputType`；`TaskRunStatus` vs `TaskStatus` | **S2** | 删旧 + 让名（N1/N5）。**阿里 SDK 的 `com.alibaba.dashscope.common.TaskStatus` 是同名假朋友，改名走 IDE rename symbol，不做文本替换** |
| 4 | `schema.sql` 头部只写了「S2 会改 `subtitle_keyword`」的预告 | **S2** | 补全真正的手工 `DROP TABLE subtitle_keyword` 说明（父任务 AC1 明确要求） |
| 5 | 旧 `task` 表仍在 `schema.sql`，旧仓储仍被调用方使用 | **S2** | 整块删 |
| 6 | 端口里凡属「S2 才接线」的方法（C63 两个锁定读、C15 启动收口、C43/C61 收口置位、C41 换版落定）**只有单测覆盖，没有端到端证据** | **S2 起** | 接上调用方后才有真证据；S1 的验收不含这些方法的运行时正确性 |
| 7 | `MediaIdentityResolver`（C7–C12 的规范化策略）未落；`SourceKey` 只有「非空白 + 不超列宽」的不变量 | **S2** | 形态构造（`bvid\|p` 拼装 / sha256 判定 / 剥跟踪参数 / 短链跟跳转）随解析器一起落 |
| 8 | 对账要用的四类扫表查询未落 | **S7** | 它们是只读端点的读路径，且要等 `intermediate_file` 登记（S6）之后才有意义 |

> **原先的第 9 条（`database-guidelines.md` 的建表契约一节失实）已定**（2026-10-17）：**本段收尾时就改**，不再留到 S8 —— 见上面步骤 8。父任务地图把 spec 更新排在 S8，那一条只覆盖「全量收口」，这一节由 S1 提前改掉。

**收尾动作**：跑完步骤 7 → 做步骤 8 的 spec 同步 → 逐条核对上表 → 报告（含「`markReady` 8 参还是小 record」这个实施时定的选择）→ **停等审查，不自行提交/合并**。
