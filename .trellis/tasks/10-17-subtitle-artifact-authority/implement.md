# S3 执行计划：产物权威

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。**
> 本文件写 S3 的**段级技术落点 + 执行顺序 + 验证方式**（父任务章程：每段具体做法写在各子任务的 `implement.md`）。
> **本文件是执行参考稿，不是契约**：发现与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。
> 范围与验收见 [prd.md](./prd.md)。

## 0. 与 S1 / S2 实际落地代码的校准

父设计描述的是终态，S1 / S2 落地时有些做法与最早的草稿不同。**下表是 S3 开头必须按「实际代码」而不是「父设计措辞」来写的地方**（2026-10-18 逐项对照 `backend/src/main` 核对）：

| # | 父设计 / 早期草稿的说法 | 实际代码（S1 / S2 落地后） | 对 S3 的影响 |
|---|---|---|---|
| 1 | artifact 仓储与 SQL 由本段新建 | **S1 已实现全部方法**：`insertCreating` / `findReadyByTaskIdAndTypeAndLang` / `findReadyByTaskId` / `updateReady` / `markSupersededAndClearFiles` / `markSuperseded` / `markDeleted` / `markCreatingAsFailedByRunId` / `markCreatingAsFailed`；Mapper 的 SQL 也已写好 | S3 **只调用，不改仓储与 Mapper**，不写任何新 SQL |
| 2 | `revision` 由应用层生成 | `MybatisSubtitleArtifactRepository#insertCreating` 已按 `MAX+1` 生成并回填 | `createCreatingPlaceholder` 之后直接拿回填值，不另算 |
| 3 | 状态机迁移写在服务里 | `SubtitleArtifact` 已有 `createCreatingArtifact` / `completeCreation` / `supersede` / `confirmDeletion` / `restore` | `settleArtifact` 用 `completeCreation`；**不要**在 store 里重写状态校验 |
| 4 | 管线的包与签名按草稿 | `SubtitlePipeline` 在 `application/subtitle/pipeline/`；coordinator 收 `(MediaInput, Path workDir, …)`（S2 已换） | 接线点就是 `runPipeline`，直接拼 `workDir` |
| 5 | 收口与启动收口一起改 | `TaskRunSupervisor#finish` 已存在；`recoverInterruptedRuns` 已经调用 `markCreatingAsFailed()` | S3 只补 `finish` 里的 `markCreatingAsFailedByRunId`，**不动启动收口** |
| 6 | `/keywords` 的 fingerprint 从 `clean-segments.json` 读 | S2 已订正：端点算的是**关键词清单的纯函数**（`SubtitleKeywords#computeFingerprint`），不读文件 | S3 **不改端点**（prd T8）；只让 `/artifacts` 暴露 `CLEANED.keywordsFingerprint` |
| 7 | `SubtitleArtifactSpec` 含 downloadable 白名单 | S2 只保留文件名模板（`getFileName` / `getLanguage` / `matches`），`downloadable` 已删 | `ArtifactFileKind.specFor` 只依赖 `getFileName`，不要重新引入白名单 |
| 8 | 读路径可以直接 `root/dir/file` 拼 | `SubtitleTaskFinder#workspaceDir` **只定位、不创建**；写路径用 `WorkspaceDirectoryManager#ensureWorkspaceDirectory` | 查询 / 下载用 `finder.workspaceDir`；主管线仍用 `ensure` |
| 9 | `lang` 直接当文件名片段 | `SubtitleArtifactSpec#getFileName` 内部走 `Bcp47LanguagePolicy#resolveTargetLanguage`，**转小写**；而 DB `lang` 列存 `SupportedLanguage#getCode()`（`zh-Hans` 混合大小写） | `settleArtifact` 用 `artifact.getLang()` 求文件名（得到小写名，与盘上一致）；下载先用 `SupportedLanguage.parse(...).getCode()` 归一，再按 DB 列匹配 |
| 10 | `id` 可能是 `long` | `TaskRun#getId()` / `SubtitleArtifact#getId()` 都是 `Long`，落库后非空 | `createCreatingPlaceholder` 的返回值已落库；`settleArtifact` / 降级传 `getId()`，不判空 |
| 11 | 失败前缀、`VideoTask`、旧产物端点 | S2 已全部退场；`TaskController` 当前没有任何产物端点 | S3 新增端点，不复活旧形状 |

**结论**：S3 的改动面比父设计读起来小得多——仓储 / SQL / 状态机 / 启动收口都已经就位，本段主要是**接线 + 读端点 + 前置判据**。

---

## 段内顺序（九步）

```
0  预期文件树与契约形状过审（门）
1  ArtifactFileKind + ArtifactFileUtils 两个助手
2  SubtitleArtifactStore（写路径 + 可读 READY 读路径）
3  ★ 主管线接线 + 收口置位（行为开始变化）
4  读路径用例：快照 / 查询服务 / 404 异常
5  HTTP：两个端点 + 两个 DTO
6  后置会话前置判据换表 + keywords 注释订正
7  文档与规范同步
8  全量验证 + 欠账核对
```

**顺序的理由**

- **步骤 1–2 是纯新增**：没有任何生产调用点，每一步结束都能 `clean test-compile` 过、全量测试绿。
- **步骤 3 是行为拐点**：从这一步起主管线开始写 `subtitle_artifact`。它不需要读端点就能独立验证（SQL 直查），回滚也只影响新写入的行。
- **步骤 4–5 是读路径**：先有应用层读模型与用例，再加 HTTP 外壳；两步各自可绿。
- **步骤 6 依赖步骤 4**（前置判据要用 store 的 `findReadableReady`）。
- **步骤 7–8 各自独立**，都绿。

---

## 步骤 0：预期文件树与契约形状过审（门）

**这是一道门，不是准备工作。** 先把下面的文件树、§0.3 的契约形状给项目所有者过一眼，**点头之后才开分支、才写第一行代码**（先例：S1 / S2 的同一道门）。

过审时要特别确认的四件事：

1. 下载 `file` 取 `segments.json` / `subtitles.srt` / `context.json` / `terms.json`，`/artifacts` 的 `files` 键取 `segments` / `subtitles` / `context` / `terms`（prd T4）。
2. C25 按**整版**消失，不逐文件过滤（prd T3）。
3. `/keywords` 端点不动，只让 `/artifacts` 暴露 `keywordsFingerprint`（prd T8）。
4. 换版两次 UPDATE 不包事务（prd T6）。

### 0.1 预期文件树（S3 结束后，只列本段碰的）

```
backend/src/main/java/com/vidsage/
├── domain/subtitle/
│   ├── SubtitleArtifact.java                        [改] + fileName(ArtifactFileKind)
│   └── model/ArtifactFileKind.java                  [新]
├── application/subtitle/
│   ├── package-info.java                            [改] 根包地图补两个构件
│   ├── SubtitleArtifactStore.java                   [新] @Component
│   ├── SubtitleArtifactQueryService.java            [新] @Service
│   ├── exception/ArtifactNotFoundException.java     [新]
│   ├── query/
│   │   ├── ArtifactSnapshot.java                    [新]
│   │   ├── ArtifactFileSnapshot.java                [新]
│   │   └── ArtifactDownload.java                    [新]
│   └── pipeline/SubtitlePipeline.java               [改] createCreatingPlaceholder / settleArtifact 两段
├── application/task/
│   ├── TaskRunSupervisor.java                       [改] 收口置位；两段 try
│   └── SubtitleTaskService.java                     [改] 前置判据换表
├── shared/fs/ArtifactFileUtils.java                     [改] + isRegularFile / sha256
└── web/
    ├── controller/ArtifactController.java           [新] 产物清单 + 下载两个端点
    ├── controller/TaskController.java               [不改]
    ├── controller/ApiExceptionHandler.java          [改] + ArtifactNotFound → 404
    └── dto/
        ├── ArtifactResponse.java                    [新]
        ├── ArtifactFileResponse.java                [新]
        └── SubtitleKeywordsResponse.java            [改] 只改 Javadoc

backend/src/test/java/com/vidsage/
├── domain/subtitle/model/ArtifactFileKindTest.java              [新]
├── application/subtitle/SubtitleArtifactStoreTest.java          [新]
├── application/subtitle/SubtitleArtifactQueryServiceTest.java   [新]
├── shared/fs/ArtifactFilesTest.java                             [改]
├── application/subtitle/pipeline/SubtitlePipelineTest.java      [改]
├── application/task/TaskRunSupervisorTest.java                  [改]
├── application/task/SubtitleTaskServiceTest.java                [改]
└── web/controller/ArtifactControllerTest.java                   [新]

.trellis/tasks/10-15-subtitle-task-model-refactor/design.md      [改] §6 C55 补两句
```

**不碰**：三个 coordinator、仓储与 Mapper、`schema.sql`、`SubtitleArtifactSpec`。

### 0.2 新增类职责

| 类 | 层 / 注解 | 职责 |
|---|---|---|
| `ArtifactFileKind` | `domain/subtitle/model/` | 四个逻辑文件位：JSON 键、URL token、`belongsTo(type)`、`specFor(type)` |
| `SubtitleArtifactStore` | `application/subtitle/`，`@Component` | `createCreatingPlaceholder` / `settleArtifact` / 可读 READY 查询；只调仓储，不写 SQL |
| `SubtitleArtifactQueryService` | `application/subtitle/`，`@Service` | `/artifacts` 列表与下载解析（有 web 调用面） |
| `ArtifactController` | `web/controller/`，`@RestController` | 产物清单与下载两个端点；只做接收 → 卫语句 → 委托 → 返回 |
| `ArtifactNotFoundException` | `application/subtitle/exception/` | 产物不存在 / 文件缺失 → 404 |
| `ArtifactSnapshot` / `ArtifactFileSnapshot` / `ArtifactDownload` | `application/subtitle/query/` | 零 web 注解的读模型 |
| `ArtifactResponse` / `ArtifactFileResponse` | `web/dto/` | HTTP 契约；URL 只在这里拼 |

### 0.3 契约形状

**`GET /api/tasks/{taskNo}/artifacts`**（顺序固定：ORIGINAL → CLEANED → TRANSLATED，同类型按 `lang` 升序）：

```jsonc
[
  { "type": "ORIGINAL", "lang": "", "revision": 1, "keywordsFingerprint": null,
    "files": {
      "segments":  { "url": "/api/tasks/{taskNo}/artifacts/ORIGINAL/segments.json",  "sizeBytes": 10240 },
      "subtitles": { "url": "/api/tasks/{taskNo}/artifacts/ORIGINAL/subtitles.srt",  "sizeBytes": 4096 }
    } },
  { "type": "CLEANED", "lang": "", "revision": 1, "keywordsFingerprint": "a1b2c3d4e5f60708",
    "files": {
      "segments":  { "url": "/api/tasks/{taskNo}/artifacts/CLEANED/segments.json",  "sizeBytes": 10240 },
      "subtitles": { "url": "/api/tasks/{taskNo}/artifacts/CLEANED/subtitles.srt",  "sizeBytes": 4096 },
      "context":   { "url": "/api/tasks/{taskNo}/artifacts/CLEANED/context.json",   "sizeBytes": 1024 }
    } },
  { "type": "TRANSLATED", "lang": "zh-Hans", "revision": 1, "keywordsFingerprint": null,
    "files": {
      "segments":  { "url": "/api/tasks/{taskNo}/artifacts/TRANSLATED/segments.json?lang=zh-Hans", ... },
      "subtitles": { "url": "/api/tasks/{taskNo}/artifacts/TRANSLATED/subtitles.srt?lang=zh-Hans", ... },
      "terms":     { "url": "/api/tasks/{taskNo}/artifacts/TRANSLATED/terms.json?lang=zh-Hans",     ... }
    } }
]
```

- `lang` 无语言维度时是空串（与 DB 列一致），不是 null。
- `keywordsFingerprint` 只有 `CLEANED` 非空；其余为 null。
- `files` 的键顺序固定为 `segments` → `subtitles` →（`context` 或 `terms`）。
- 文件缺失的整版不会出现在列表里（C25）。

**`GET /api/tasks/{taskNo}/artifacts/{type}/{file}?lang=`**

| 参数 | 规则 |
|---|---|
| `type` | `ORIGINAL` / `CLEANED` / `TRANSLATED`，不区分大小写 |
| `file` | `segments.json` / `subtitles.srt` / `context.json` / `terms.json` |
| `lang` | 只有 `TRANSLATED` 必填；按 `SupportedLanguage.parse` 归一（`zh` 与 `zh-Hans` 等价）；其余类型不允许带 |

| 文件位 | Content-Type |
|---|---|
| `SEGMENTS` / `CONTEXT` / `TERMS` | `application/json` |
| `SUBTITLES` | `text/plain;charset=UTF-8` |

`Content-Disposition: inline; filename="<DB 里的文件名>"`；正文是文件字节本身。

**错误映射**

| 场景 | 状态 | 异常 / 处理 |
|---|---|---|
| taskNo 不存在 | 404 | `TaskNotFoundException`（现有） |
| `type` 不是三个枚举值 | 400 | `IllegalArgumentException`（中文说明取值） |
| `file` 不是四个 token | 400 | `IllegalArgumentException` |
| `file` 与 `type` 不匹配（如 ORIGINAL + `context.json`） | 400 | `IllegalArgumentException` |
| `TRANSLATED` 缺 `lang` | 400 | `IllegalArgumentException` |
| 非 `TRANSLATED` 带了 `lang` | 400 | `IllegalArgumentException` |
| `lang` 不在白名单 | 400 | `UnsupportedLanguageException`（现有） |
| 该 `(type, lang)` 没有可读 `READY`（含文件缺失、文件列 null） | 404 | `ArtifactNotFoundException`（新增） |

---

## 步骤 1：`ArtifactFileKind` + `ArtifactFileUtils` 两个助手

### 1.1 `domain/subtitle/model/ArtifactFileKind.java`

```java
public enum ArtifactFileKind {
    SEGMENTS("segments", "segments.json"),
    SUBTITLES("subtitles", "subtitles.srt"),
    CONTEXT("context", "context.json"),
    TERMS("terms", "terms.json");
}
```

三个派生查询（纯函数）：

| 方法 | 语义 |
|---|---|
| `key()` | `/artifacts` 响应里 `files` 的键 |
| `token()` | 下载 URL 里的 `{file}` 段；`fromToken(String)` 反向解析（未知返回空） |
| `belongsTo(ArtifactType)` | 该类型应带这个文件位 |
| `specFor(ArtifactType)` | 该类型下对应的 `SubtitleArtifactSpec` 模板；不属于该类型时抛 ISE |

类型 → 文件位 / 文件名模板（与父设计 §1.4 一一对应）：

| 文件位 | ORIGINAL | CLEANED | TRANSLATED |
|---|---|---|---|
| `SEGMENTS` | `ORIGINAL_SEGMENTS` → `original-segments.json` | `CLEAN_SEGMENTS` → `clean-segments.json` | `TRANSLATED_SEGMENTS` → `translated-segments-{lang}.json` |
| `SUBTITLES` | `ORIGINAL_SRT` → `original-subtitles.srt` | `CLEAN_SRT` → `clean-subtitles.srt` | `TRANSLATED_SRT` → `translated-subtitles-{lang}.srt` |
| `CONTEXT` | — | `CONTEXT` → `context.json` | — |
| `TERMS` | — | — | `TERMS` → `terms-{lang}.json` |

要点：

- `key()` / `token()` 两套名字都写死，不互相推导（token 的扩展名是契约的一部分）。
- `belongsTo` / `specFor` 用 `switch` 穷举类型，不写 `default`（将来加 `ArtifactType` 值时编译期报错）。
- `specFor` 对不适用组合抛 `IllegalStateException`，消息说明「哪个类型不该有哪个文件位」。

### 1.2 `shared/fs/ArtifactFileUtils`

- `public static boolean isRegularFile(Path path)`：薄包装 `Files.isRegularFile`；不引入「0 字节算缺失」的语义。
- `public static String sha256(Path path)`：`MessageDigest` + `DigestInputStream`，返回 64 位小写十六进制；IO 失败抛 `IllegalStateException`（消息含文件名，不含绝对路径）。

### 1.3 测试

- `ArtifactFileKindTest`：四个 token 反解、未知 token 为空；`belongsTo` 的 3×4 全矩阵；`specFor` 的 4 个不适用组合抛 ISE。
- `ArtifactFilesTest` 补：`sha256("abc")` = `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`；缺失文件抛 ISE；0 字节文件 `isRegularFile` 为 true。

**判据**：`clean test-compile` 过 + 全量测试绿。

---

## 步骤 2：`SubtitleArtifactStore`

### 2.1 方法

`@Component`，构造注入 `SubtitleArtifactRepository`：

```java
SubtitleArtifact createCreatingPlaceholder(long taskId, long runId, ArtifactType type, String lang,
                       Long derivedFromArtifactId, String keywordsFingerprint);
SubtitleArtifact settleArtifact(SubtitleArtifact creating, Path workDir);
Optional<SubtitleArtifact> findReadableReady(long taskId, ArtifactType type, String lang, Path workDir);
List<SubtitleArtifact> listReadableReady(long taskId, Path workDir);
```

`createCreatingPlaceholder` 直接 `repository.insertCreating(SubtitleArtifact.createCreatingArtifact(...))`，不碰 revision。

### 2.2 `settleArtifact` 的五步（顺序不能换）

```
1. 按 type 求出应带的文件位集合
2. 逐个求文件名（kind.specFor(type).getFileName(artifact.getLang())）并算 sha256
     —— 文件此刻已由 coordinator 原子替换落盘；读不到就抛 ISE（settleArtifact 失败，走收口）
3. 查同 (task,type,lang) 的旧 READY
     —— 存在：markSupersededAndClearFiles(old.getId())（文件列 + 哈希列八列一起清）
     —— 不存在：跳过
4. artifact.completeCreation(四个文件名, 四个哈希)（只填该类型有的，其余 null）
5. repository.updateReady(artifact)
```

- **哈希放在降级之前**：哈希失败时旧 `READY` 还没被动过；反过来会让该 `(type,lang)` 在收口前没有任何可用版本。
- **降级用 `markSupersededAndClearFiles`**（覆盖型）；**不要**用 `markSuperseded` —— 后者给 S4 的级联用。
- 文件名一律走 `kind.specFor(type).getFileName(lang)`；**不要**从 coordinator 的返回值拿路径（那会形成第二条映射来源）。

**为什么不包事务**（父设计 W4 判为非必需）：

| 崩点 | DB 状态 | 收敛方式 |
|---|---|---|
| 降级后、置 READY 前 | 旧行 `SUPERSEDED` 且文件列已清，新行仍 `CREATING` | 收口把新行标 `FAILED`；再跑一次 MAIN 重新落定 |
| 置 READY 后、run 收口前 | 新行 `READY`、旧行 `SUPERSEDED` —— 已经是正确终态 | run 收口只补 run 状态 |

中间窗口内该 `(type,lang)` 没有 `READY`，`/artifacts` 与下载按「没有」处理。这是可恢复窗口，不是数据错。

### 2.3 可读判定（C25）

「可读」= 行 `READY` **且**该类型应带的每个文件位都满足：

1. 对应文件列非 null；
2. `workDir.resolve(fileName)` 是普通文件（`ArtifactFileUtils.isRegularFile`）。

任一不满足 → 记 WARN（只记 `taskId` / `type` / `lang` / 文件位，不记路径与 `source_uri`）、**整版**当没有。**不写 DB、不改状态**。

**0 字节文件算存在**：空转写会产出 0 字节 SRT，这是合法产物；判据用 `isRegularFile`，不用 `size > 0`。

`listReadableReady` 不排序（排序归查询服务，见步骤 4）。

### 2.4 测试（`SubtitleArtifactStoreTest`，mock 仓储 + `@TempDir(cleanup = CleanupMode.NEVER)`）

| 用例 | 断言 |
|---|---|
| `createCreatingPlaceholder` 透传 | 仓储收到 `CREATING` 且文件列全 null |
| `settleArtifact` ORIGINAL | 写两个文件名 + 两个哈希，context/terms 为 null |
| `settleArtifact` CLEANED | 写三个文件名 + 三个哈希，terms 为 null |
| `settleArtifact` 顺序 | `InOrder`：先 `markSupersededAndClearFiles`，后 `updateReady` |
| 无旧 READY | 不调用降级 |
| 哈希失败（文件不在） | 抛 ISE，且**没有调用**任何写方法 |
| `findReadableReady` | 文件齐 → 有值；缺一个 → 空 |
| `listReadableReady` | 一好一坏 → 只回好的 |

**判据**：`clean test-compile` 过 + 全量测试绿。

---

## 步骤 3：主管线接线 + 收口置位（行为拐点）

### 3.1 `SubtitlePipeline#runPipeline`

构造注入 `SubtitleArtifactStore`，改写为：

```java
Path workDir = workDir(workspace);
MediaInput input = toMediaInput(workspace, workDir);
ProgressListener listener = progressListener(ref, run);

// ORIGINAL：先插占位，再让 coordinator 写文件，最后落定
SubtitleArtifact original = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
        ArtifactType.ORIGINAL, "", null, null);
subtitlePreparationCoordinator.prepare(input, workDir, listener);
original = artifactStore.settleArtifact(original, workDir);

// CLEANED：继承自刚落定的 ORIGINAL
SubtitleKeywords keywords = keywordRepository.findByTaskId(run.getTaskId());
SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
        ArtifactType.CLEANED, "", original.getId(), keywords.computeFingerprint());
cleanupStage.clean(input, workDir, keywords, listener);
artifactStore.settleArtifact(cleaned, workDir);

supervisor.finish(ref, run, null);
```

- **占位必须在 coordinator 之前**：`CREATING` 表示「这次执行要产出一版」；提前插行还让 revision 在写文件前就确定。
- **先读关键词、后起 CLEANED 占位**：指纹要在插入时写进占位行；读关键词失败时 ORIGINAL 仍是一版合法产物。
- **失败不手回收口**：任何一步抛异常都交给现有的 `catch` → `supervisor.finish`；本类不 catch 占位行。
- `regenerate` / `translate` 两条执行体**本段不动**（S4 / S5 接管）。

### 3.2 `TaskRunSupervisor#finish`

落库段拆两段 try，各自 best-effort：

```java
try {
    subtitleArtifactRepository.markCreatingAsFailedByRunId(run.getId());
} catch (RuntimeException e) {
    log.error("run 占位产物收口失败（仍会落 run 终态）: runNo={}", run.getRunNo(), e);
}

try {
    run.succeed(); / run.fail(...);
    taskRunRepository.update(run);
    subtitleTaskRepository.touchUpdatedAt(...);
} catch (RuntimeException e) {
    log.error("run 终态落库失败，仍会广播终态并清流: runNo={}", run.getRunNo(), e);
}

// 广播终态 / 清流仍在两个 try 之外，顺序不变
```

- 两段互不阻断：产物占位收口失败不能挡住 run 终态，run 终态落库失败也不能让占位行永远 `CREATING`。
- `recoverInterruptedRuns` 不动（已有 `markCreatingAsFailed()`）。

### 3.3 测试

- `SubtitlePipelineTest`：补 store mock；用 `InOrder` 验 `createCreatingPlaceholder(ORIGINAL)` → `prepare` → `settleArtifact` → `createCreatingPlaceholder(CLEANED)` → `clean` → `settleArtifact` → `finish`；验 CLEANED 的 `derivedFromArtifactId` 是 ORIGINAL 落定后的 id；验 prepare 抛错时 `finish` 收到错误且不再调用后续 settle。
- `TaskRunSupervisorTest`：`finish` 调用 `markCreatingAsFailedByRunId`；run 终态 `update` 抛异常时仍调用；`markCreatingAsFailedByRunId` 抛异常时仍落 run 终态。

**判据**：`clean test-compile` 过 + 全量测试绿。

**本步之后可用 SQL 直查**（还没有 HTTP）：

```sql
SELECT artifact_type, status, revision, segments_file_name, subtitles_file_name,
       context_file_name, terms_file_name
FROM subtitle_artifact WHERE task_id = ? ORDER BY id;
```

应见两条 `READY`、无 `CREATING`。

---

## 步骤 3.5：降级上下文标记（补做，紧接步骤 3）

**为什么补**：`settleArtifact` 要求 CLEANED 三件套齐全，而摘要失败时旧实现不落 `context.json` → `settleArtifact` 抛错、run FAILED；
且下次 MAIN 复用 clean 检查点，永远不补写 context，形成反复失败。修法是「降级也落盘，用字段标记」，让「这次试过了」
与「这是有效缓存」分开。

**落点**

| 文件 | 改动 |
|---|---|
| `domain/subtitle/model/common/SubtitleContext` | 尾部加 `boolean degraded` 组件；保留三参便利构造器（委托四参、`degraded=false`）；`empty()` 保持非降级；新增静态工厂 `degradedEmpty()` = 空 + `degraded=true` |
| `domain/subtitle/policy/CheckpointPolicy#resolveContext` | 缓存只有 `cached != null && !cached.degraded()` 才算 `CACHE_HIT`（降级标记当未命中，重新摘要）；摘要失败仍返回 `Source.DEGRADED_EMPTY`，但 context 用 `SubtitleContext.degradedEmpty()` |
| `application/subtitle/pipeline/SubtitleCleanupCoordinator` | `DEGRADED_EMPTY` 且非 `regenerate`：把带标记的降级上下文原子写盘；`regenerate` 分支不变（失败即抛、不写）。断点复用分支返回前先 `ensureUsableContext`：文件在且非降级就直接返回；缺失或降级就读原始 segments 补摘一次并按结果写盘（成功写成功那份、失败写降级标记）；原始 segments 缺失按现有语义抛 ISE |
| `domain/subtitle/port/SubtitleContextSummarizer` | Javadoc 同步：失败返回空 Optional；空结果落盘时由调用方写成带降级标记的空上下文，读回不当缓存命中 |

`SubtitleTranslationCoordinator` 代码不改（已走 `resolveContext`），只补测试证明「缓存带 `degraded=true` 时会重新摘要」。

**判据**：`clean test-compile` 过 + 全量测试绿；`SubtitleContext` 的三参构造与 `empty()` 语义不变（既有用例不重写）。

---

## 步骤 4：读路径用例

### 4.1 读模型（`application/subtitle/query/`，零 web 注解）

```java
public record ArtifactSnapshot(ArtifactType type, String lang, int revision,
                               String keywordsFingerprint, List<ArtifactFileSnapshot> files) {}

public record ArtifactFileSnapshot(ArtifactFileKind kind, long sizeBytes) {}

public record ArtifactDownload(Path path, ArtifactFileKind kind) {}
```

### 4.2 `ArtifactNotFoundException`

消息格式：

> 产物不可用：taskNo=…，type=…，file=…；该任务还没有这一版产物，或产物文件已被回收，请等主管线跑完或重新生成后再试

**不带磁盘路径**。

### 4.3 `SubtitleArtifactQueryService`（`@Service`）

构造注入 `SubtitleTaskFinder`、`SubtitleArtifactStore`。

`List<ArtifactSnapshot> listArtifacts(String taskNo)`：

1. `finder.require` → `finder.requireWorkspace` → `finder.workspaceDir`；
2. `store.listReadableReady`；
3. 排序：`ArtifactType.ordinal()` 再 `lang`；
4. 每个文件位取 size。**注意 TOCTOU**：可读判定与取 size 之间文件可能被删；`Files.size` 抛 `IOException` 时把整版从结果里去掉并记 WARN，不抛 500；
5. `keywordsFingerprint` 原样透传（非 CLEANED 本来就是 null）。

`ArtifactDownload openArtifact(String taskNo, String typeToken, String fileToken, String langToken)`：

1. 解析 `ArtifactType`（大写归一，未知 → IAE）；
2. `ArtifactFileKind.fromToken`（未知 → IAE）；`belongsTo` 不成立 → IAE；
3. 语种归一：`TRANSLATED` 必填并走 `SupportedLanguage.parse(...).getCode()`；其余类型带 `lang` → IAE；
4. `store.findReadableReady(...)` 为空 → `ArtifactNotFoundException`；
5. `artifact.fileName(kind)` 为 null → `ArtifactNotFoundException`（防御；可读判定本应已挡住）；
6. 返回 `new ArtifactDownload(workDir.resolve(fileName), kind)`。

### 4.4 `ArtifactSnapshot` 组装

只遍历 `kind.belongsTo(type)` 的文件位，保证 `files` 的键顺序是枚举声明顺序（`SEGMENTS` → `SUBTITLES` → `CONTEXT`/`TERMS`）；**不要**用 `HashMap`。

### 4.5 测试（`SubtitleArtifactQueryServiceTest`）

mock `SubtitleTaskFinder` 与 `SubtitleArtifactStore`：

- 列表排序（TRANSLATED 混在两个无语言版本之间时仍排最后）；
- 文件缺失的版本不出现在列表；
- 下载的四个 400 分支：未知 type、未知 file、file/type 不匹配、lang 缺失/多余；
- 下载的 404 分支：task 不存在由 finder 抛；无可读 READY；文件列 null。

**判据**：`clean test-compile` 过 + 全量测试绿。

---

## 步骤 5：HTTP 端点

### 5.1 DTO（`web/dto/`）

```java
@Schema(description = "一版产物")
public record ArtifactResponse(
        String type, String lang, int revision, String keywordsFingerprint,
        Map<String, ArtifactFileResponse> files) {
    public static ArtifactResponse from(String taskNo, ArtifactSnapshot snapshot) { ... }
}

@Schema(description = "产物文件")
public record ArtifactFileResponse(String url, long sizeBytes) {}
```

`from` 里拼 URL（唯一拼 URL 的地方）：

```
"/api/tasks/" + taskNo + "/artifacts/" + type.name() + "/" + kind.token()
    + (type == TRANSLATED ? "?lang=" + lang : "")
```

`files` 用 `LinkedHashMap`，按 `snapshot.files()` 的顺序放。字段都要 `@Schema` 描述。

### 5.2 `ArtifactController`（新，产物端点独立成类）

`@RestController` + `@RequestMapping("/api/tasks")`，只注入 `SubtitleArtifactQueryService`。

- `@GetMapping("/{taskNo}/artifacts")` → `List<ArtifactResponse>`；taskNo 空白走卫语句（400）。
- `@GetMapping("/{taskNo}/artifacts/{type}/{file}")` → `ResponseEntity<Resource>`：
  - `new FileSystemResource(download.path())`；
  - Content-Type：`SUBTITLES` → `MediaType.valueOf("text/plain;charset=UTF-8")`，其余 → `MediaType.APPLICATION_JSON`；
  - `Content-Disposition`：`ContentDisposition.inline().filename(fileName).build().toString()`；文件名取 `path.getFileName()`（DB 列值）。
- `@Tag` / `@Operation` 描述：只给当前有效版本；404 与 400 的分工写清。

**`TaskController` 一行不改**：不注入 `SubtitleArtifactQueryService`，不出现 `/artifacts` 映射。任务生命周期与产物文件是两种关注点，产物端点整体独立。

### 5.3 `ApiExceptionHandler`

在 `TaskNotFoundException` 处理器旁新增 `ArtifactNotFoundException` → 404，日志 WARN。

### 5.4 测试（`ArtifactControllerTest`，新）

`@WebMvcTest(ArtifactController.class)` + `@MockitoBean SubtitleArtifactQueryService`：

- `GET /artifacts`：形状、排序、`keywordsFingerprint`、`files` 键与 URL、空列表；
- 下载：200 的 Content-Type 与字节、404 映射、服务层抛 IAE 时的 400；
- **不要**把产物用例塞进 `TaskControllerTest`，后者保持原样。

**判据**：`clean test-compile` 过 + 全量测试绿。

---

## 步骤 6：前置判据换表 + keywords 注释

### 6.1 `SubtitleTaskService`

- 删 `validateArtifactPresent(Workspace, SubtitleArtifactSpec, String)`。
- 新增：

```java
private void validateReadableArtifact(SubtitleTask task, Workspace workspace,
                                      ArtifactType type, String description) {
    Path workDir = finder.workspaceDir(workspace);
    if (artifactStore.findReadableReady(task.getId(), type, "", workDir).isEmpty()) {
        throw new IllegalArgumentException("字幕尚未就绪：该任务还没有可读的 " + description
            + "；请先等主管线跑完（可查 GET /api/tasks/{taskNo} 或订阅其 SSE 事件）再试");
    }
}
```

- `regenerate` → `ORIGINAL`；`translate` → `CLEANED`。
- 调用位置不变：**先判前置、后插 run**；不满足仍是 400，且不落库、不重跑。
- 不再 import `SubtitleArtifactSpec`。

### 6.2 `SubtitleKeywordsResponse`

只改 Javadoc：比对来源从 `segments-*-clean.json` 改为 `/artifacts` 里 `CLEANED` 行的 `keywordsFingerprint`。**字段、算法、行为一律不动**。

### 6.3 测试

- `SubtitleTaskServiceTest`：
  - 无可读 ORIGINAL → `regenerate` 抛 IAE 且**不插 run**（verify `supervisor.start` 从未调用）；
  - 无可读 CLEANED → `translate` 同上；
  - 有可读产物 → 照常插 run。
- 现有「文件放好就通过」的用例改成 mock store 返回值。

**判据**：`clean test-compile` 过 + 全量测试绿。

---

## 步骤 7：文档与规范同步

| 文件 | 改什么 |
|---|---|
| 父任务 `design.md` §6 | C55 补 `file` 取值（四个 token）与「参数不合法 400 / 产物不可用 404」的分工；**只写终态，不搬理由** |
| `application/subtitle/package-info.java` | 根包地图补 `SubtitleArtifactStore` / `SubtitleArtifactQueryService` / `query/` 的三个读模型 |
| `web/dto/SubtitleKeywordsResponse.java` | 见 6.2 |

**不写**：`database-guidelines.md`（不改表结构）、`directory-structure.md`（新构件都落在既有规则内）。

**判据**：文档与代码自洽；`grep` 确认没有残留「`segments-*-clean.json` 的 keywordsFingerprint」这类旧说法。

---

## 步骤 8：全量验证 + 欠账核对

### 8.1 命令

```bash
# 1) 判编译 —— 增量编译会给假绿，一律 clean
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 2) 全量测试 —— 先删陈旧报告
cd backend && rm -rf target/surefire-reports && \
  JAVA_HOME=/f/java_soft/java21 mvn -q clean test
grep -h -o 'tests="[0-9]*"' target/surefire-reports/*.xml | awk -F'"' '{s+=$2} END {print "tests:", s}'
grep -h -o 'failures="[0-9]*"' target/surefire-reports/*.xml | awk -F'"' '{s+=$2} END {print "failures:", s}'
grep -h -o 'errors="[0-9]*"' target/surefire-reports/*.xml | awk -F'"' '{s+=$2} END {print "errors:", s}'

# 3) 起服务（真库跑 schema.sql）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q -Dmaven.test.skip=true spring-boot:run
```

`spring-boot:run` 绑 `test-compile`，测试源码红着起不来；只跑 main 必须 `-Dmaven.test.skip=true`。收工按 PID `taskkill`（`TaskStop` 杀不掉子 JVM）。

### 8.2 从零准备

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

### 8.3 手工验收（对应 prd 的 AC）

```bash
T=$(curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
      -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}' | sed -E 's/.*"taskNo":"([^"]+)".*/\1/')
# 轮询到 lastRun.status=SUCCEEDED
curl -s localhost:8080/api/tasks/$T

# AC1 落表
#   SELECT ... FROM subtitle_artifact WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T');
#   → 两条 READY、无 CREATING

# AC4 清单
curl -s localhost:8080/api/tasks/$T/artifacts
#   → ORIGINAL / CLEANED；CLEANED 的 keywordsFingerprint 与下面相等
curl -s localhost:8080/api/tasks/$T/keywords

# AC5 下载逐字节比对
curl -s -o /tmp/orig-seg.json localhost:8080/api/tasks/$T/artifacts/ORIGINAL/segments.json
cmp /tmp/orig-seg.json backend/data/works/BV11QMd6WEJp_p1/original-segments.json
curl -s -o /tmp/clean.srt localhost:8080/api/tasks/$T/artifacts/CLEANED/subtitles.srt
cmp /tmp/clean.srt backend/data/works/BV11QMd6WEJp_p1/clean-subtitles.srt

# 400 / 404
curl -i localhost:8080/api/tasks/$T/artifacts/ORIGINAL/context.json          # 400
curl -i localhost:8080/api/tasks/$T/artifacts/TRANSLATED/segments.json       # 400（缺 lang）
curl -i localhost:8080/api/tasks/$T/artifacts/TRANSLATED/segments.json?lang=en  # 404（还没有译文）
curl -i localhost:8080/api/tasks/$T/artifacts/NOPE/segments.json             # 400

# AC6 C25 自愈
mv backend/data/works/BV11QMd6WEJp_p1/context.json /tmp/context.json
curl -s localhost:8080/api/tasks/$T/artifacts        # CLEANED 整项消失
curl -i localhost:8080/api/tasks/$T/artifacts/CLEANED/segments.json   # 404
#   DB 里 CLEANED 仍 READY
mv /tmp/context.json backend/data/works/BV11QMd6WEJp_p1/context.json
curl -s localhost:8080/api/tasks/$T/artifacts        # CLEANED 回来

# AC7 前置判据
mv backend/data/works/BV11QMd6WEJp_p1/clean-segments.json /tmp/
curl -i -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"zh-Hant"}'    # 400，且 task_run 没多一行
mv /tmp/clean-segments.json backend/data/works/BV11QMd6WEJp_p1/

# AC2 换版
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'      # 同一 taskNo，新 runNo
#   → ORIGINAL / CLEANED 各 rev 2 READY；rev 1 SUPERSEDED 且八个文件/哈希列全 NULL
```

> `translated-*` 与 `terms-*` 在 S3 还没有 artifact 行，下载返回 404 是预期（S5）。

### 8.4 收尾

逐条核对下面的欠账表 → 报告（含测试条数基线变化账）→ **停等审查，不自行提交/合并**。

---

## 边界矩阵

| 场景 | 表现 |
|---|---|
| coordinator 在占位之后抛错 | 该 run 的 `CREATING` → `FAILED`；旧 `READY` 原样可用 |
| `settleArtifact` 算哈希时文件不在 | 抛 ISE → run `FAILED`；旧 `READY` 未动 |
| 进程在 settleArtifact 两次 UPDATE 之间被 kill | 旧行 `SUPERSEDED` + 新行 `CREATING`；启动收口把新行标 `FAILED`；重跑收敛 |
| 手工删掉一个产物文件 | 该版从 `/artifacts` 消失、下载 404、前置判据 400；DB 状态不变；文件放回后恢复 |
| 0 字节 SRT（空转写） | 视为存在，正常列出与下载 |
| 断点复用命中（文件已存在） | 仍插新 revision 并落定，指向同名文件（新一次 MAIN run = 新版本） |
| 列表读取与文件删除竞态 | `Files.size` 失败 → 该版从本次列表去掉 + WARN（不 500） |
| 两个并发 MAIN | 互斥键保证不可能；`settleArtifact` 不处理并发 |

---

## 回滚点

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| **步骤 2 之后** | 文件位映射与 store 全在但零调用点 | 零代价 |
| **步骤 3 之后** | 主管线开始写 artifact 表；无读端点 | 零代价；多出的 DB 行不影响任何现有行为，回滚后可手工清掉 |
| **步骤 5 之后** | 读端点全在；前置判据仍是文件 | 零代价；可单独回滚端点 |
| **步骤 6 之后** | 前置判据换表 | 零代价；回滚只退服务层 |
| **步骤 7** | 只动文档 | 单独可回滚 |

**彻底回退**：`git revert` 本段提交；无表结构变更，不需要 DROP / 重建。已写入的 `subtitle_artifact` 行可留（旧代码不读）或手工清空。

---

## 欠账清单（本段结束时哪些东西还没到位、交给哪一段）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | **RECLEAN 不动 artifact 表**：新清洗产物落盘后，`CLEANED` 行仍指旧 revision / 旧指纹 / 旧哈希；`/artifacts` 显示的元信息与文件内容暂时对不上 | **S4** | W5 换版 + 级联 + 两种终态 |
| 2 | **TRANSLATE 不动 artifact 表**：译文文件在盘上，但 `/artifacts` 不列、下载 404 | **S5** | 版本落定 + 复用判据 |
| 3 | 重新生成时 coordinator 会删掉旧的 `translated-*` / `terms-*` 文件，但 DB 里若已有 `TRANSLATED` 行不会被标 `SUPERSEDED` / `DELETED` | **S4** | 级联承担；S3 本身不产生 `TRANSLATED` 行，故无可见影响 |
| 4 | 复用判据仍是「文件在不在」，`intermediate_file` 零登记 | **S6** | C62 改造 |
| 5 | 内容指纹两列仍恒为 NULL；内容变更级联未做 | **S6** | 指纹探测阶段 |
| 6 | 哈希列只写不校；对账端点不存在 | **S7** | 只读对账 + 哈希比对 |
| 7 | 网络调用无超时 | **S8** | C46 订正 |
| 8 | 存量工作区没有 artifact 行（S2 及以前） | **不做** | 本地数据可弃；重新提交即产出 |
| 9 | 前端一定点不通（新端点未接） | **另开任务**（C60） | 后端全部段做完再动 |
| 10 | `/artifacts` 每次请求都 stat 文件、不做缓存 | **未排期** | 单实例本地盘，量级不构成问题；记账即可 |
| 11 | 下载端点整文件流式返回，不支持 Range / 断点续传 | **未排期** | 当前前端只下载整份；真需要再说 |

**收尾动作**：跑完步骤 8 → 逐条核对上表 → 报告（含测试基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。
