# S6 执行计划：内容变更（中间产物登记 + 指纹探测）

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。**
> 本文件写 S6 的**段级技术落点 + 执行顺序 + 验证方式**（父任务章程：每段具体做法写在各子任务的 `implement.md`）。
> **本文件是执行参考稿，不是契约**：发现与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。
> 范围与验收见 [prd.md](./prd.md)。

## 0. 与 S5 实际落地代码的校准

下表是 S6 开头必须按「实际代码」而不是「父设计措辞」来写的地方（2026-10-19 逐项对照 `backend/src/main` 核对）：

| # | 父设计 / 早期草稿的说法 | 实际代码（S5 落地后） | 对 S6 的影响 |
|---|---|---|---|
| 1 | `intermediate_file` 端口够用 | 只有 `findReadyByTaskIdAndFileName`，**没有**「按文件名取任意状态」；`markReady(id, at)` 不更新 `derived_from_run_id` | 补 `findByTaskIdAndFileName`；`markReady` 扩成 `(id, fileType, derivedFromRunId, at)` |
| 2 | 指纹来自 yt-dlp 元数据 | `YtDlpInfoJson` 只映射 `ext/title/duration`，**没有 `timestamp`**；但探测可以只跑 `--print` 取两个标量，不必解析整份 info json | 不给 `YtDlpInfoJson` 加字段；探测单独解析 stdout |
| 3 | 媒体复用由适配器回答 | `MediaAcquirerDispatcher#reuseExisting` 返回「文件存在且可解析」；`LocalFileMediaAcquirer#reuseExisting` **恒空**（本地 / 上传不下载） | DB 门禁只在 URL 路线实际生效；上传路线登记 `source.mp4`，但不需要「复用前查表」 |
| 4 | 转写断点看产物表 | `SubtitlePreparationCoordinator#readCheckpoint` 只看 `original-segments.json` 大小；`prepare` 拿不到 taskId | 管线算好 `ORIGINAL` 是否完好，作为参数传下去；coordinator 不再自行决定 |
| 5 | 清洗断点看产物表 | `SubtitleCleanupCoordinator#doClean` 的 `current` 只看 `clean-segments.json` + 文件内关键词指纹 | 加 `allowCheckpointReuse` 参数；`regenerate` 恒为 `false` |
| 6 | 完好版判定现成 | `SubtitleArtifactStore#findIntactArtifact(taskId, type, lang, workDir)` 已存在，缺文件时 WARN 并按没有处理 | 直接复用，不另写判据 |
| 7 | 音频重新抽取可以直接覆盖 | `FfmpegAudioExtractor#extract` 明确**拒绝已存在的输出**（`IllegalStateException`） | 表拒绝复用时，coordinator 必须先删旧音频文件，再抽 |
| 8 | yt-dlp 会重新下载 | `acquire` 的参数没有 `--force-overwrites`；盘上已有 `source.<ext>` 时 yt-dlp 可能认为「已下载」而跳过 | 表拒绝复用时尽力删旧文件；`acquire` 加 `--force-overwrites` 兜底 |
| 9 | 级联回收可以复用产物 store 的私有方法 | `SubtitleArtifactStore#reclaimFilesOf` 是 `private`；`settleCleanedAndSupersedeDownstream` 里内联了「删文件 → markDeleted」 | 抽成 `public void reclaimSuperseded(artifact, workDir)`，settle 与级联共用 |
| 10 | 指纹写入用领域方法 | `Workspace#updateFingerprint` 只改内存对象，零生产调用点；持久化入口是 `WorkspaceRepository#updateFingerprint` | 级联 store 直接调仓储，传同一个 `now()`；不依赖内存对象的 `updatedAt` |
| 11 | 阶段权重表只有五段 | `SubtitleStageNameTest` 钉死了 `{0,21},{21,42},{42,66},{66,70},{70,100}` | 新增 `PROBE_FINGERPRINT`（权重 5）后，区间变为 `{0,2},{2,22},{22,43},{43,67},{67,70},{70,100}`，测试同步更新 |
| 12 | 便捷重载可以留着 | `AudioPreparationCoordinator` / `SubtitlePreparationCoordinator` 的旧重载只被测试用；它们只做文件判据 | 本段把生产路径收成**必带门禁参数**的签名，旧重载删除、测试跟着改——不留能绕过数据库的入口 |

**结论**：S6 的改动面集中在五处 —— 新增探测端口与阶段；中间产物 store；两个 coordinator 的门禁与登记；W5′ 级联 store；管线接线。DDL / 六张表 / 端点 / DTO 都不动。

---

## 段内顺序（五步）

```
0  预期文件树与关键形状过审（门）
1  纯地基：内容指纹词汇 + 探测端口 + yt-dlp 实现 + dispatcher + 中间产物 store + 仓储补口（零调用点）
2  ★ 复用门禁换轨：中间产物（媒体 / 音频）+ artifact 断点（转写 / 清洗）（行为拐点）
3  ★ 指纹阶段 + W5′ 级联 + 管线接线（行为拐点，端到端可见）
4  文档同步（父 design + spec 的 -Store 后缀登记）
5  全量验证 + 手工验收 + 欠账核对
```

**顺序的理由**

- **步骤 1 是纯新增**：新端口与 store 没有任何生产调用点，`clean test-compile` 过、全量测试绿。
- **步骤 2 是第一个行为拐点**：复用判据换成「表里 `READY` 且文件在」。它不依赖指纹阶段就能独立验证 —— 用 SQL 把某行打成 `SUPERSEDED`、保留文件，重提交必须重新产出。先落门禁，步骤 3 的级联一落地就能看到真重跑，而不是被文件断点挡住。
- **步骤 3 是第二个行为拐点**：探测 + 级联 + 写指纹。用「改 `workspace.duration_ms` 再提交」就能独立验完整链路。
- **步骤 4 / 5 各自独立**，都绿。

---

## 步骤 0：预期文件树与关键形状过审（门）

**这是一道门，不是准备工作。** 先把下面的文件树、§0.2 的方法形状给项目所有者过一眼，**点头之后才开分支、才写第一行代码**（先例：S1–S5 的同一道门）。

过审时要特别确认的五件事：

1. **探测是独立端口** `ContentFingerprintProbe`，由 `YtDlpMediaAcquirer` 一并实现；上传 / 本地不探测。
2. **指纹两列都取到才算数**；时间戳按 UTC 落 `published_at`；首跑（两列全 NULL）只写指纹。
3. **门禁落点**：媒体 / 音频在 `AudioPreparationCoordinator`；`ORIGINAL` / `CLEANED` 在 `SubtitlePipeline` 算布尔值传下去。
4. **W5′ 的类型分流**：`ORIGINAL` / `CLEANED` 清列不删文件；`TRANSLATED` 与中间产物删文件后 `DELETED`；回收在事务提交后。
5. **C50 现状核验**：音频 / 媒体源 / info json 已满足「文件存在 = 完整」，本段**不改 provider 输出路径**；若要求 info json 也严格临时文件化，是额外范围。

### 0.1 预期文件树（S6 结束后，只列本段碰的）

```
backend/src/main/java/com/vidsage/
├── domain/workspace/
│   ├── Workspace.java                                      [改] + hasUnknownFingerprint / matchesFingerprint
│   ├── model/ContentFingerprint.java                       [新] durationMs + publishedAt（UTC）
│   └── port/ContentFingerprintProbe.java                   [新] supports + probe
├── domain/intermediate/port/
│   └── IntermediateFileRepository.java                     [改] + findByTaskIdAndFileName；markReady 扩参
├── domain/subtitle/model/common/
│   └── SubtitleStageName.java                              [改] + PROBE_FINGERPRINT（权重 5）
├── infrastructure/persistence/
│   ├── mapper/IntermediateFileMapper.java                  [改] markReady 更新 file_type / derived_from_run_id
│   └── repository/MybatisIntermediateFileRepository.java   [改] 两处委派
├── infrastructure/provider/download/ytdlp/
│   └── YtDlpMediaAcquirer.java                             [改] implements ContentFingerprintProbe；acquire 加 --force-overwrites
├── application/media/
│   ├── IntermediateFileOwner.java                          [新] (taskId, runId)
│   ├── IntermediateFileStore.java                          [新] 复用判据 / 登记 / 回收
│   └── pipeline/
│       ├── AudioPreparationCoordinator.java                [改] 中间产物门禁 + 登记 + 旧文件清理
│       └── ContentFingerprintProbeDispatcher.java          [新] 按 MediaInput 选探测实现
├── application/workspace/
│   └── WorkspaceFingerprintStore.java                      [新] 首跑写指纹；W5′ 事务 + 事务后回收
├── application/subtitle/
│   ├── SubtitleArtifactStore.java                          [改] + public reclaimSuperseded；settle 复用它
│   └── pipeline/
│       ├── SubtitleFingerprintCoordinator.java             [新] 认领 PROBE_FINGERPRINT 阶段
│       ├── SubtitlePreparationCoordinator.java             [改] ORIGINAL 门禁 + owner 参数
│       ├── SubtitleCleanupCoordinator.java                 [改] CLEANED 门禁参数
│       └── SubtitlePipeline.java                           [改] 探测阶段接线；两个门禁；owner

backend/src/test/java/com/vidsage/
├── domain/workspace/model/ContentFingerprintTest.java                       [新]
├── domain/workspace/WorkspaceTest.java                                     [新] 两个判据
├── domain/subtitle/model/common/SubtitleStageNameTest.java                 [改] 六阶段区间
├── infrastructure/provider/download/ytdlp/YtDlpMediaAcquirerTest.java      [改] 探测解析 / 失败降级 / force 参数
├── infrastructure/persistence/repository/MybatisIntermediateFileRepositoryTest.java [改] 新方法 / 扩参
├── application/media/IntermediateFileStoreTest.java                        [新]
├── application/media/pipeline/ContentFingerprintProbeDispatcherTest.java   [新]
├── application/media/pipeline/AudioPreparationCoordinatorTest.java        [改] 门禁 / 登记 / 旧文件清理
├── application/workspace/WorkspaceFingerprintStoreTest.java                [新] W5′ 事务与回收
├── application/subtitle/SubtitleArtifactStoreTest.java                     [改] reclaimSuperseded 复用
└── application/subtitle/pipeline/
    ├── SubtitleFingerprintCoordinatorTest.java                             [新]
    ├── SubtitlePreparationCoordinatorTest.java                             [改] 门禁参数
    ├── SubtitleCleanupCoordinatorTest.java                                 [改] 门禁参数
    └── SubtitlePipelineTest.java                                          [改] 探测接线 / 两个门禁

.trellis/tasks/10-15-subtitle-task-model-refactor/design.md                 [改] §7.3 步骤 2 补 UPLOAD 与探测命令；§1.6 补 derived_from_run_id 重产出更新
.trellis/spec/backend/directory-structure.md                               [改] 组件后缀登记补 `-Store`
```

**不碰**：`schema.sql`、`subtitle_artifact` / `task_run` / `subtitle_task` 的 DDL 与 Mapper、`SubtitleTaskService`（除测试签名外）、`TaskRunSupervisor`、`ArtifactController`、全部 DTO、`SupportedLanguage`、`ArtifactFileKind`、`SubtitleArtifactSpec`。

### 0.2 新增 / 改动的方法形状

**`ContentFingerprint`（新领域词汇）**

```java
/**
 * 媒体内容指纹：时长 + 发布时间。两列都必须有值才算探测成功。
 * publishedAt 按 UTC 落库——它不对外展示，只用于跨 run 比对，UTC 不会因机器时区变化制造假变更。
 */
public record ContentFingerprint(int durationMs, LocalDateTime publishedAt) {
    public ContentFingerprint {
        if (durationMs < 0) throw new IllegalArgumentException("时长不能为负：" + durationMs);
        if (publishedAt == null) throw new IllegalArgumentException("发布时间不能为空：取不到就返回 Optional.empty()");
    }
}
```

**`ContentFingerprintProbe`（域端口）**

```java
public interface ContentFingerprintProbe {
    boolean supports(MediaInput input);
    /**
     * 只读元数据，不下载、不落任何文件。
     * 取不到（超时 / 非零退出 / 缺字段 / 解析失败）返回 Optional.empty()——这是降级路径，不是失败路径（C49）。
     */
    Optional<ContentFingerprint> probe(MediaInput input);
}
```

**`Workspace`（加两个判据）**

```java
/** 两列都空 = 首跑：只写指纹、不级联（C48）。 */
public boolean hasUnknownFingerprint() {
    return durationMs == null && publishedAt == null;
}

/** 两列都非空且逐列相等才是「内容没变」；只空一列按变了处理（安全方向）。 */
public boolean matchesFingerprint(ContentFingerprint fingerprint) {
    return durationMs != null && publishedAt != null
            && durationMs == fingerprint.durationMs()
            && publishedAt.equals(fingerprint.publishedAt());
}
```

**`IntermediateFileStore`（application/media 的机械件）**

```java
@Component
public class IntermediateFileStore {

    /** 复用判据前半句查表、后半句查盘：READY 行在、且 workDir 里文件是普通文件。 */
    public boolean isReusable(long taskId, String fileName, Path workDir);

    /** 真正产出之后调用：没有行就插 READY，有行就复活（同句刷 file_type / derived_from_run_id / updated_at）。 */
    public void recordProduced(long taskId, long runId, IntermediateFileType type, Path workDir, Path producedFile);

    /** 级联前取当前 READY 行（事务内调用，供事务外回收）。 */
    public List<IntermediateFile> findReady(long taskId);

    /** 级联：该 task 当前 READY 的中间产物全标 SUPERSEDED（一条批量 UPDATE）。 */
    public int supersedeAll(long taskId, LocalDateTime at);

    /** 两阶段回收：删文件成功才标 DELETED；删不动只 WARN，留在 SUPERSEDED。 */
    public void reclaimSuperseded(IntermediateFile file, Path workDir);
}
```

> `recordProduced` 只登记 `workDir` 内的文件：本地 `MediaInput.LocalPath` 可能指向工作区外，那种文件不属 task 级缓存（不登记、不参与复用判据）。

**`WorkspaceFingerprintStore`（application/workspace）**

```java
@Component
public class WorkspaceFingerprintStore {

    /** 首跑：一条 UPDATE 写两列指纹。 */
    public void writeFingerprint(long workspaceId, ContentFingerprint fingerprint);

    /**
     * W5′：同一事务里
     *   ① READY artifact 按类型处理（ORIGINAL/CLEANED 清文件列；TRANSLATED 留文件列待回收）
     *   ② READY 中间产物全标 SUPERSEDED
     *   ③ 写新指纹
     * 事务提交后再删译文与中间产物的文件并标 DELETED（C22 两阶段）。
     * 回收失败只 WARN，不把 run 判失败（留 `SUPERSEDED` 给 S7 对账）。
     */
    public void supersedeOnSourceMediaChange(long taskId, long workspaceId, ContentFingerprint fingerprint,
                                            Path workDir);
}
```

**`SubtitleArtifactStore`（抽一个可复用回收方法）**

```java
/** 两阶段回收：删干净才标 DELETED；任何一步失败只 WARN，那行留在 SUPERSEDED。 */
public void reclaimSuperseded(SubtitleArtifact artifact, Path workDir)
```

`settleCleanedAndSupersedeDownstream` 里现有的内联回收循环改成调用它，行为不变。

**`AudioPreparationCoordinator`（门禁 + 登记）**

```java
// 生产入口：多一个 owner；旧的 3/4 参重载删除，测试跟着改
public AudioStageOutput prepare(MediaInput input, Path workDir, AudioSpec spec,
                                ProgressListener listener, IntermediateFileOwner owner)
```

获取媒体阶段：

```java
Optional<AcquiredMedia> existing = acquisitionStage.reuseExisting(input, workDir);
if (existing.isPresent() && allReusable(owner.taskId(), existing.get(), workDir)) {
    // 表 READY + 文件在 → 跳过，登记不重写
    return existing.get();
}
existing.ifPresent(this::deleteStaleMedia);   // 表拒绝复用：旧文件不能留给 yt-dlp 跳过
AcquiredMedia media = acquisitionStage.acquire(input, workDir);
intermediateFileStore.recordProduced(owner.taskId(), owner.runId(),
        IntermediateFileType.SOURCE_MEDIA, workDir, media.mediaPath());
media.infoJsonPath().ifPresent(info -> intermediateFileStore.recordProduced(owner.taskId(), owner.runId(),
        IntermediateFileType.INFO_JSON, workDir, info));
```

抽取音频阶段：

```java
Path audioPath = workDir.resolve(AudioStageOutput.fileName(spec));
boolean fileComplete = CheckpointPolicy.isCompleted(ArtifactFileUtils.sizeOrZero(audioPath));
if (fileComplete && intermediateFileStore.isReusable(owner.taskId(), audioPath.getFileName().toString(), workDir)) {
    return new AudioStageOutput(audioPath, spec, size);
}
if (Files.exists(audioPath)) {
    // 表拒绝复用（或文件是 0 字节残骸），但 extractor 拒绝覆盖：先删掉旧文件
    Files.deleteIfExists(audioPath);   // 删不动就抛，让本趟失败，而不是拿旧音频冒充新产物
}
AudioStageOutput audio = audioExtractor.extract(media.mediaPath(), audioPath, spec);
intermediateFileStore.recordProduced(owner.taskId(), owner.runId(),
        IntermediateFileType.AUDIO, workDir, audio.audioPath());
return audio;
```

**`SubtitlePreparationCoordinator`（ORIGINAL 门禁 + 下传 owner）**

```java
public SubtitleStageOutput prepare(MediaInput input, Path workDir, ProgressListener listener,
                                   boolean reuseCompletedOriginal, IntermediateFileOwner owner)
```

- `reuseCompletedOriginal == false` 时**完全忽略**文件检查点（`existing = null`），必须走完整准备。
- `true` 时仍读文件检查点；JSON 解析失败（`readCheckpoint` 返回 null）就回落完整准备。
- 音频准备调用 `audioPreparationCoordinator.prepare(..., owner)`。

**`SubtitleCleanupCoordinator`（CLEANED 门禁）**

```java
public CleanedSubtitle clean(MediaInput input, Path workDir, SubtitleKeywords keywords,
                             ProgressListener listener, boolean allowCheckpointReuse)
```

`current = allowCheckpointReuse && existing != null && CheckpointPolicy.isCurrent(...)`。
`regenerate(...)` 保持无条件跳过检查点，内部传 `allowCheckpointReuse=false`。

**`SubtitleFingerprintCoordinator`（认领 PROBE_FINGERPRINT 阶段）**

```java
@Component
public class SubtitleFingerprintCoordinator {
    public void detectContentChange(Workspace workspace, MediaInput input, Path workDir, ProgressListener listener)
}
```

分支：

| 条件 | 动作 | 事件 |
|---|---|---|
| dispatcher 不支持该输入（上传 / 本地） | 直接返回 | `SKIPPED`「上传路线按内容摘要识别，无需探测内容指纹」 |
| `probe` 抛异常 | 记 WARN，直接返回 | `SKIPPED`「取不到内容指纹，跳过变更检测」 |
| `probe` 返回空 | 记 WARN，直接返回 | 同上 |
| `workspace.hasUnknownFingerprint()` | `writeFingerprint` | `SUCCEEDED`「首次探测，已记录内容指纹」 |
| `workspace.matchesFingerprint(probed)` | 不写不级联 | `SUCCEEDED`「内容未变化」 |
| 其余（含只空一列） | `supersedeOnSourceMediaChange` | `SUCCEEDED`「内容已变化，旧产物已作废，将重新产出」 |

> 级联异常**不捕获**——W5′ 失败必须让本次 run `FAILED`，否则会出现「内容变了但旧产物还是当前版」。

**`SubtitlePipeline`（接线）**

```java
public SubtitlePipeline(..., SubtitleFingerprintCoordinator fingerprintCoordinator, ...)

void runPipeline(TaskRun run, RunRef ref, Workspace workspace) {
    Path workDir = workDir(workspace);
    MediaInput input = toMediaInput(workspace, workDir);
    ProgressListener listener = progressListener(ref, run);

    // 指纹探测在媒体获取之前（C47）；级联会把旧产物打成非 READY
    fingerprintCoordinator.detectContentChange(workspace, input, workDir, listener);

    boolean originalIntact = artifactStore
            .findIntactArtifact(run.getTaskId(), ArtifactType.ORIGINAL, "", workDir).isPresent();
    SubtitleArtifact original = artifactStore.createCreatingPlaceholder(...);
    subtitlePreparationCoordinator.prepare(input, workDir, listener, originalIntact,
            new IntermediateFileOwner(run.getTaskId(), run.getId()));
    original = artifactStore.settleArtifact(original, workDir);

    SubtitleKeywords keywords = keywordRepository.findByTaskId(run.getTaskId());
    boolean cleanedIntact = artifactStore
            .findIntactArtifact(run.getTaskId(), ArtifactType.CLEANED, "", workDir).isPresent();
    SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(...);
    cleanupStage.clean(input, workDir, keywords, listener, cleanedIntact);
    artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
    supervisor.finish(ref, run, null);
}
```

**`YtDlpMediaAcquirer`（探测实现 + force 覆盖）**

```java
@Override
public Optional<ContentFingerprint> probe(MediaInput input) {
    // 入参校验同 acquire；只接受 RemoteUrl
    List<String> args = List.of(
            "--no-playlist", "--skip-download", "--quiet", "--no-warnings",
            "--print", "%(duration)s|%(timestamp)s",
            remote.value());
    ProcessOutcome outcome = ytDlp.run(args, null);
    if (outcome.timedOut() || outcome.exitCode() != 0) {
        log.warn("内容指纹探测失败，按取不到处理: timedOut={}, exit={}", outcome.timedOut(), outcome.exitCode());
        return Optional.empty();
    }
    return parseFingerprint(outcome.stdout());
}

/** 只认「duration|timestamp」这一行；任一为 NA / 非数字 / 负数 / 溢出 → 取不到。 */
static Optional<ContentFingerprint> parseFingerprint(String stdout)
```

`acquire` 的参数列表加 `--force-overwrites`：`acquire` 的语义就是「真的获取一次」，盘上残留不能让它静默跳过。

**`SubtitleStageName`**

```java
PROBE_FINGERPRINT("probe-fingerprint", 5),
ACQUIRE_MEDIA(...),
...
```

---

## 步骤 1：纯地基（零调用点）

**动作**

1. 新增 `ContentFingerprint` + `ContentFingerprintProbe`；`Workspace` 加两个判据。
2. `YtDlpMediaAcquirer` 实现 `ContentFingerprintProbe`；新增 `parseFingerprint`；`acquire` 加 `--force-overwrites`。
3. 新增 `ContentFingerprintProbeDispatcher`。
4. `IntermediateFileRepository` 加 `findByTaskIdAndFileName`；`markReady` 扩成 `(id, fileType, derivedFromRunId, at)`；Mapper / 仓储 / 测试同步。
5. 新增 `IntermediateFileStore`。
6. `SubtitleStageName` 加 `PROBE_FINGERPRINT`，更新区间测试。

**验证线**：`clean test-compile` 过；全量测试绿；grep 确认新类**没有生产调用点**（除了端口装配）。

## 步骤 2：复用门禁换轨（行为拐点）

**动作**

1. `AudioPreparationCoordinator`：删旧重载，生产签名带 `IntermediateFileOwner`；媒体与音频按「表 READY + 文件在」复用；表拒绝时清理旧文件；产出后登记。
2. `SubtitlePreparationCoordinator`：生产签名带 `reuseCompletedOriginal` + owner；`false` 时忽略文件检查点。
3. `SubtitleCleanupCoordinator`：`clean` 加 `allowCheckpointReuse`；`regenerate` 传 `false`。
4. `SubtitlePipeline`：算 `originalIntact` / `cleanedIntact`，传下去；构造 owner。
5. 全部相关测试改签名；新增门禁用例。

**验证线**：`clean test-compile` 过；全量测试绿；手工（AC5 / AC6）能证明「表拒绝 = 真重跑」。

## 步骤 3：指纹阶段 + W5′ 级联（行为拐点）

**动作**

1. 新增 `WorkspaceFingerprintStore`：首跑写指纹；W5′ 事务（artifact 分流 + 中间产物批量 + 指纹）；事务后回收。
2. `SubtitleArtifactStore` 抽 `public reclaimSuperseded`，settle 改用它。
3. 新增 `SubtitleFingerprintCoordinator`；管线在最前面调用。
4. 单元测试：首次 / 未变 / 变更 / 只空一列 / 探测失败 / 上传跳过 / 事务与回收边界。

**验证线**：`clean test-compile` 过；全量测试绿；手工（AC1–AC4、AC8）端到端过。

## 步骤 4：文档同步

- 父 `design.md`：
  - §7.3 步骤 2 补两句：UPLOAD 路线不探测；探测命令只读元数据、不落文件（`--print`）。
  - §1.6 补一句：重产出时 `derived_from_run_id` 跟着更新（审计列说真话）。
- `.trellis/spec/backend/directory-structure.md`：组件后缀登记表补 `-Store`（已有 `SubtitleArtifactStore`，本段新增 `IntermediateFileStore` / `WorkspaceFingerprintStore`）。
- 若实现与父设计有其它出入，按「以代码为准」回来改文档，并在收尾报告里点名。

## 步骤 5：全量验证 + 手工验收 + 欠账核对

### 5.1 命令

```bash
cd backend

# 1) 判编译 —— 增量编译会给假绿，一律 clean
JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 2) 全量测试 —— 先删陈旧报告，免得读到上一轮的 XML
rm -rf target/surefire-reports
JAVA_HOME=/f/java_soft/java21 mvn test
grep -rc '@Test\|@ParameterizedTest' src/test --include=*.java | awk -F: '{s+=$2} END {print s}'   # 静态数（开工前 633）

# 3) 起服务（真库跑 schema.sql；本段无表结构变更）
JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true
# 冒烟完按 PID taskkill（TaskStop 杀不掉子 JVM）
```

> 退出码会被管道吃掉；判绿看 `[ERROR]` 段与 `BUILD SUCCESS/FAILURE`。

### 5.2 从零准备

```sql
-- 无 DB 外键，删除顺序无所谓
DELETE FROM intermediate_file;
DELETE FROM subtitle_artifact;
DELETE FROM task_run;
DELETE FROM subtitle_keyword;
DELETE FROM subtitle_task;
DELETE FROM workspace;
```

```bash
rm -rf backend/data/works
```

### 5.3 手工验收（对应 prd 的 AC）

```bash
DB='mysql -u root -p --default-character-set=utf8mb4 vidsage -N -B -e'   # 密码按本机配置

# ── 起步：提交 + 等完成 ───────────────────────────────────────────
T=$(curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
      -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}' | sed -E 's/.*"taskNo":"([^"]+)".*/\1/')
# 轮询 GET /api/tasks/$T 直到 lastRun.status=SUCCEEDED

# ── AC1：首次探测 + 中间产物登记 ─────────────────────────────────
$DB "SELECT source_type, duration_ms, published_at FROM workspace
     WHERE id=(SELECT workspace_id FROM subtitle_task WHERE task_no='$T');"
#   → URL / 两个值都非 NULL（published_at 是 UTC 墙钟）
$DB "SELECT file_name, file_type, status, derived_from_run_id FROM intermediate_file
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T') ORDER BY file_type;"
#   → source.<ext> SOURCE_MEDIA READY
#   → source.info.json INFO_JSON READY
#   → audio-*.m4a AUDIO READY
#   三行的 derived_from_run_id 都指向本次 MAIN run

# ── AC2：同 URL 再提交，内容未变 ─────────────────────────────────
ls -l --time-style=full-iso backend/data/works/BV11QMd6WEJp_p1/source.* \
      backend/data/works/BV11QMd6WEJp_p1/audio-*.m4a
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'
# 等 SUCCEEDED
ls -l --time-style=full-iso backend/data/works/BV11QMd6WEJp_p1/source.* \
      backend/data/works/BV11QMd6WEJp_p1/audio-*.m4a    # mtime 不变
# 日志：内容未变化 + 转写/写盘/清洗的「跳过」（准备阶段整体短路，媒体/音频准备不复用检查）
$DB "SELECT file_name, status FROM intermediate_file
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T');"   # 仍全 READY

# ── AC3 / AC4：内容变更 → 级联 + 真重跑 ─────────────────────────
$DB "UPDATE workspace SET duration_ms = duration_ms + 1000
     WHERE id=(SELECT workspace_id FROM subtitle_task WHERE task_no='$T');"
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'
# 等 SUCCEEDED
$DB "SELECT artifact_type, lang, revision, status,
            segments_file_name, subtitles_file_name, context_file_name, terms_file_name
     FROM subtitle_artifact
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T')
     ORDER BY artifact_type, lang, revision;"
#   → 旧 ORIGINAL / CLEANED：SUPERSEDED，八个文件/哈希列全 NULL
#   → 旧 TRANSLATED：走完 DELETED（文件已删）
#   → 新 ORIGINAL / CLEANED：READY，revision +1
$DB "SELECT file_name, status, derived_from_run_id FROM intermediate_file
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T');"
#   → 同名文件只有一行：级联瞬间 SUPERSEDED/DELETED，本次重新产出后复活为 READY，
#     derived_from_run_id 指向本次 run（不是旧 run）
ls -l --time-style=full-iso backend/data/works/BV11QMd6WEJp_p1/original-segments.json \
      backend/data/works/BV11QMd6WEJp_p1/clean-segments.json    # mtime 更新
# 日志：内容已变化 → 开始获取媒体 / 开始抽取音频 / 开始转写 / 开始清洗

# ── AC5：中间产物表门禁（文件在、表拒绝）────────────────────────
# 先让 ORIGINAL 不完好，逼准备阶段真的进来
$DB "UPDATE subtitle_artifact SET status='SUPERSEDED'
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T') AND artifact_type='ORIGINAL'
       AND status='READY';"
# 再把音频行打成 SUPERSEDED，文件不动
$DB "UPDATE intermediate_file SET status='SUPERSEDED'
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T') AND file_type='AUDIO';"
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'
# 日志：音频「开始抽取音频」而不是「跳过」；source 媒体仍可复用（它的行还是 READY）

# ── AC6：artifact 门禁 ──────────────────────────────────────────
# 6a：ORIGINAL 行 SUPERSEDED、文件保留 → 转写重跑
# 6b：CLEANED 行 SUPERSEDED、文件保留、关键词不变 → 清洗重跑
# 两条都看日志里的「开始转写」「开始清洗」，不是「跳过」

# ── AC8：上传路线 ──────────────────────────────────────────────
curl -s -X POST localhost:8080/api/tasks/upload -F 'file=@<本地视频文件>'
# 完成后：
$DB "SELECT file_name, file_type, status FROM intermediate_file
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T2');"
#   → source.mp4 SOURCE_MEDIA READY；audio-* AUDIO READY；没有 INFO_JSON
$DB "SELECT duration_ms, published_at FROM workspace
     WHERE id=(SELECT workspace_id FROM subtitle_task WHERE task_no='$T2');"
#   → 两个都 NULL
# 再次上传同一文件 → 不级联、产物仍 READY
```

> 手工验收里的 SQL / curl 都是**人跑的命令**，不进代码库。
> AC3 的「改 `duration_ms`」是在模拟源内容变化：探测会把真实值写回，级联看的是「探测值 ≠ 库里值」。
> AC5 / AC6 的 `UPDATE ... SET status='SUPERSEDED'` 不会清文件列，属于人工制造的异常形态；跑完可重建工作区收尾。

### 5.4 收尾

逐条核对下面的欠账表 → 报告（含测试条数基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。

---

## 边界矩阵

| 场景 | 表现 |
|---|---|
| 探测成功、内容未变 | 不写指纹、不级联；媒体 / 音频 / 转写 / 清洗按表 + 文件复用 |
| 探测成功、首次（两列 NULL） | 只写指纹；本次正常产出 |
| 探测成功、只空一列 | 按「变了」级联（安全方向） |
| 探测失败 / 超时 / 缺 `duration` 或 `timestamp` | WARN + `SKIPPED`；不抛异常；复用判据回落「有产物就复用」（C49） |
| 上传 / 本地路线 | 不探测、不级联；`source.mp4` 与音频登记 READY；指纹列保持 NULL |
| W5′ 事务任一步失败 | 全批回滚；旧 artifact 仍 READY；run FAILED（下次重试） |
| W5′ 提交后、删文件前崩 | 译文 / 中间产物停在 `SUPERSEDED`、文件列还在；留给 S7 对账报出 |
| 删某个文件抛异常 | 该行停在 `SUPERSEDED` + WARN；其余行照常回收；run 不因此失败 |
| 中间产物表 READY、文件不在 | 判不可复用；重新产出（登记复活） |
| 中间产物表 SUPERSEDED、文件还在 | 判不可复用；先尽力删旧文件再重新产出（yt-dlp 另有 `--force-overwrites`） |
| 中间产物没有行、文件还在 | 判不可复用；删旧文件后重新产出（存量工作区的一次性代价） |
| `ORIGINAL` READY 但 segments JSON 解析失败 | `readCheckpoint` 返回 null → 回落完整准备（不拿坏检查点当命中） |
| `ORIGINAL` SUPERSEDED、文件还在 | 准备阶段完整重跑（表门禁先于文件检查点） |
| `CLEANED` SUPERSEDED、文件还在、关键词没变 | 清洗完整重跑（表门禁先于文件检查点） |
| 同一 task 并发 MAIN | `uk_active_run` 撞键 → 409；级联与回收不处理并发 |
| 手工把 `workspace` 两列清成 NULL 但已有产物 | 按首跑处理：只写指纹、不级联（C48 的字面口径；已知窗口，见欠账） |
| 本地 `MediaInput.LocalPath` 指向工作区外 | 不登记、不参与「表 + 文件」判据；文件本身不属 task 级缓存 |

---

## 回滚点

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| **步骤 1 之后** | 新端口 / store / 仓储方法就位，零调用点 | 零代价；可单独回滚 |
| **步骤 2 之后** | 复用走「表 + 文件」，没有指纹阶段 | 行为拐点；存量工作区会重新产出一次。回滚即恢复文件判据 |
| **步骤 3 之后** | 指纹探测 + 级联生效 | 行为拐点；回滚后只剩表门禁，不再检测内容变更 |
| **步骤 4** | 只动文档 | 单独可回滚 |

**彻底回退**：`git revert` 本段提交；**无表结构变更**，不需要 DROP / 重建。已写入的 `intermediate_file` 行与 `workspace` 指纹列可留（旧代码不读它们）。

---

## 欠账清单（本段结束时哪些东西还没到位、交给哪一段）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | 中间产物表无对外读端点（C54 只给 artifact） | 不做 | 验收走 SQL 直查；前端不展示中间产物 |
| 2 | 对账端点不存在；`SUPERSEDED` 但文件还在的行只能靠人看 | **S7** | 只读对账四类；哈希比对；临时后缀白名单全局化 |
| 3 | 网络调用统一超时 | **S8** | 探测复用 `YtDlpRunner` 现有默认超时，本段不单独设短超时 |
| 4 | 首跑字面口径：两列 NULL 但已有产物时只写指纹、不级联 | **不做 / 留档** | C48 的字面口径；只有「探测曾长期不可用、期间内容变了」才会踩到，C49 已接受该降级 |
| 5 | 存量工作区没有 `intermediate_file` 行 | **不做** | 第一次重提交会重新下载 / 重抽一次；本地数据可弃 |
| 6 | 失败信息里带工作区绝对路径（既有缺陷） | **另开小修 / 择机** | S5 欠账 #9；本段新增日志同样不拼路径 |
| 7 | `SubtitlePipeline` 一个类扛三种 run | **未排期** | S5 欠账 #7；届时「重新提交会不会作废译文」要重审 |
| 8 | 前端一定点不通（新端点未接） | **另开任务**（C60） | 后端全部段做完再动 |
| 9 | 版本回退 / 历史版本 | **不做** | C52 / C58 |

**收尾动作**：跑完步骤 5 → 逐条核对上表 → 报告（含测试基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。
