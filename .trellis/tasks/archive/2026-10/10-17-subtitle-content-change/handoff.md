# S6 会话移交：代码审查（新会话从这里开始）

> 任务：`.trellis/tasks/10-17-subtitle-content-change`（字幕模型重构 6/8：内容变更）
> 日期：2026-10-19
> 状态：**实现完成、自动化与真库手工验收都过、停在审查门；未提交、未合并、未归档。**
> 本文件是给新会话的交接，不替代任务口径。先读本文件，再按「审查前必读」列出的文件读全文。

---

## 0. 新会话的第一条指令

**只做代码审查，不要提交 / 推送 / 合并 / 归档。** 用户明确要求开新会话审查代码；审查意见回到 2.1 修复后，再走新的 3.4 提交门。

当前工作区：

```bash
cd f:/personal_project/vidSage
git branch --show-current     # feature/subtitle-content-change
git status --short            # 全部改动未提交
```

- 分支从 `dev` 切出，`task.json` 已由 `task.py start` 记为 `in_progress` + 该分支。
- 无任何 commit；不要 `git add` / `commit` / `push` / `merge` / `task.py archive`。
- 推送须走本机代理（`https_proxy=http://127.0.0.1:7897`），但**本批不需要推送**。
- 冒烟服务已停（`taskkill` 掉了 PID 37252），端口 8080 空闲。

**注意一处与 S6 无关的用户改动**：`backend/src/main/java/com/vidsage/application/task/SubtitleTaskService.java` 在 `handleSubmit` 事务里多了一行注释 `// 数据库创建记录`。这不是 S6 的改动，审查时别把它算进本段、也别擅自回退。

---

## 1. 一句话现状

S6 把「复用判据」从**文件在不在**换成**表里 `READY` 且文件在**，并补上**内容指纹探测 → 不一致级联作废**：

1. `intermediate_file` 登记媒体源 / info json / 音频（产出后写表；复用前查表）。
2. 主管线最前新增 `PROBE_FINGERPRINT` 阶段：yt-dlp 只读元数据取 `duration` + `timestamp`，与 `workspace` 两列比对。
3. 内容变了 → W5′：旧 artifact 按类型处理 + 中间产物批量作废 + 写新指纹，**同一事务**；事务提交后删译文 / 中间产物文件并标 `DELETED`。
4. `ORIGINAL` / `CLEANED` 不完好时，准备 / 清洗阶段完全忽略盘上文件检查点（含清洗的上下文缓存）。

---

## 2. 审查前必读（按顺序，全文读）

| # | 文件 | 读什么 |
|---|---|---|
| 1 | `.trellis/tasks/10-17-subtitle-content-change/prd.md` | 范围、T1–T10 取舍、AC1–AC10、范围外表 |
| 2 | `.trellis/tasks/10-17-subtitle-content-change/implement.md` | §0 与 S5 代码的 12 条校准、五步顺序、方法形状、边界矩阵、回滚点、9 条欠账 |
| 3 | `.trellis/tasks/10-15-subtitle-task-model-refactor/design.md` | **权威终态**；**>32KB，必须用 Read 读全文**。重点：§1.6 intermediate_file、§4.3 状态机、§5 W5′、§7.3 主管线、§8.2/8.3/8.4 |
| 4 | `.trellis/spec/backend/directory-structure.md` | **>32KB，读全文**。重点：分层、端口归属、`@Component`/`@Service`、方法名、`-Store` 后缀登记 |
| 5 | `.trellis/spec/backend/database-guidelines.md`、`error-handling.md`、`quality-guidelines.md`、`logging-guidelines.md`、`provider-pattern.md` | 事务选型、异常类型、注释/测试、脱敏红线、provider 边界 |
| 6 | `.trellis/tasks/10-17-subtitle-content-change/check.jsonl` | 检查清单（与上面 spec 对应） |

---

## 3. 改动清单（`git status` 的真实状态）

### 3.1 新增（主代码）

```
backend/src/main/java/com/vidsage/domain/workspace/model/ContentFingerprint.java
backend/src/main/java/com/vidsage/domain/workspace/port/ContentFingerprintProbe.java
backend/src/main/java/com/vidsage/application/media/IntermediateFileOwner.java
backend/src/main/java/com/vidsage/application/media/IntermediateFileStore.java
backend/src/main/java/com/vidsage/application/media/pipeline/ContentFingerprintProbeDispatcher.java
backend/src/main/java/com/vidsage/application/workspace/WorkspaceFingerprintStore.java
backend/src/main/java/com/vidsage/application/subtitle/pipeline/SubtitleFingerprintCoordinator.java
```

### 3.2 修改（主代码）

| 文件 | 改了什么 |
|---|---|
| `domain/workspace/Workspace.java` | `hasUnknownFingerprint()` / `matchesFingerprint(ContentFingerprint)` |
| `domain/intermediate/port/IntermediateFileRepository.java` | + `findByTaskIdAndFileName`；`markReady` 扩成 `(id, fileType, derivedFromRunId, at)` |
| `domain/subtitle/model/common/SubtitleStageName.java` | + `PROBE_FINGERPRINT("probe-fingerprint", 5)`（最前） |
| `infrastructure/persistence/mapper/IntermediateFileMapper.java` | `markReady` 同句刷 `file_type` / `derived_from_run_id` / `updated_at` |
| `infrastructure/persistence/repository/MybatisIntermediateFileRepository.java` | 两处委派 |
| `infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java` | 实现 `ContentFingerprintProbe`（`--skip-download --quiet --print "%(duration)s|%(timestamp)s"`）；`acquire` 加 `--force-overwrites`；抽 `requireRemoteUrl` |
| `application/media/pipeline/AudioPreparationCoordinator.java` | 新签名带 `IntermediateFileOwner`；媒体 / 音频按「表 READY + 文件在」复用；表拒绝时清旧文件；产出后登记 |
| `application/subtitle/pipeline/SubtitlePreparationCoordinator.java` | 新签名带 `reuseCompletedOriginal` + owner；`false` 时忽略文件检查点 |
| `application/subtitle/pipeline/SubtitleCleanupCoordinator.java` | `clean` 必带 `allowCheckpointReuse`；内部拆成 `allowCheckpointReuse / forceReClean / requireContextSummary` 三轴 |
| `application/subtitle/pipeline/SubtitlePipeline.java` | 探测阶段接线；算 `originalIntact` / `cleanedIntact`；构造 owner |
| `application/subtitle/SubtitleArtifactStore.java` | 抽 `public reclaimSuperseded(...)`，settle 复用它 |

### 3.3 新增 / 修改（测试）

新增：`ContentFingerprintTest`、`WorkspaceTest`、`IntermediateFileStoreTest`、`ContentFingerprintProbeDispatcherTest`、`WorkspaceFingerprintStoreTest`、`SubtitleFingerprintCoordinatorTest`。
修改：`AudioPreparationCoordinatorTest`、`SubtitlePreparationCoordinatorTest`、`SubtitleCleanupCoordinatorTest`、`SubtitlePipelineTest`、`SubtitleStageNameTest`、`MybatisIntermediateFileRepositoryTest`、`YtDlpMediaAcquirerTest`。

### 3.4 文档

- `.trellis/tasks/10-15-subtitle-task-model-refactor/design.md`：§7.3 补探测命令 / UTC / 上传跳过 / 「两道门」分工；§1.6 补 `derived_from_run_id` 重产出更新。
- `.trellis/spec/backend/directory-structure.md`：组件后缀登记补 `-Store`。
- 任务目录：`prd.md` / `implement.md` / `implement.jsonl` / `check.jsonl` / 本文件。

---

## 4. 验证证据（可直接复现）

### 4.1 自动化

```bash
cd backend
JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile          # EXIT=0
rm -rf target/surefire-reports
JAVA_HOME=/f/java_soft/java21 mvn test                           # BUILD SUCCESS
```

- **开工前基线：633 tests / 0 failures / 0 errors / 1 skipped**
- **本段结束：680 tests / 0 failures / 0 errors / 1 skipped**（静态 `@Test` 数也是 680）
- 增量：+47 条测试（新增 27 + 门禁 / 级联 / 边界的补充用例）
- `VidSageApplicationTests` 随全量跑过，说明新 bean 装配无环、上下文能起。

### 4.2 真库手工验收（已跑完，服务已停）

样本：`BV11QMd6WEJp`（workspace id=1，已有 task `df52406708914c099100db031624ea31`）；上传路线用同一条视频的 `source.mp4`（task `1470a57845c84ba68bf1a3b757ffdf70`）。

| AC | 结果 |
|---|---|
| AC1 首次探测 | `workspace.duration_ms=67766`、`published_at=2026-08-03 18:52:18`；三条中间产物 `READY`、`derived_from_run_id=96`；新 `ORIGINAL` rev14 / `CLEANED` rev9 `READY` |
| AC2 内容未变 | 再次提交 2 秒完成；`source.mp4` / `audio-*` / `original-segments.json` / `clean-segments.json` 的 mtime **完全不变**；中间产物仍指向 run 96；日志有「内容未变化」「字幕断点复用跳过」「字幕清洗断点复用跳过」 |
| AC3 内容变更 | 手工把 `workspace.duration_ms` 改成 999999 → 提交；指纹写回 67766；旧 `ORIGINAL` rev15 / `CLEANED` rev10 `SUPERSEDED` 且八个文件 / 哈希列全 `NULL`；中间产物在级联时作废、随后**复活 READY**（同名列一行到底）`derived_from_run_id=98` |
| AC4 真重跑 | 四个文件 mtime 从 `13:36` 更新到 `13:39`（下载 / 抽音频 / 转写 / 清洗都重跑）；日志有「内容已变化，旧产物已作废」 |
| AC5 中间产物门禁 | 手工把 `ORIGINAL` 置 `SUPERSEDED`、`AUDIO` 行置 `SUPERSEDED`（文件保留）→ 重提交：音频 mtime `13:39:06 → 13:40:55`（重抽），`source.mp4` mtime `13:39:04` **不变**（媒体行仍 `READY`，复用） |
| AC6 artifact 门禁 | 6a：`ORIGINAL` 置 `SUPERSEDED` → `original-segments.json` mtime `13:39:22 → 13:41:02`（ASR 重跑）；6b：`CLEANED` 置 `SUPERSEDED` → `clean-segments.json` mtime `13:39:22 → 13:41:48`（清洗重跑），而音频 / 原始字幕 mtime 不变 |
| AC7 探测失败降级 | 单测：`SubtitleFingerprintCoordinatorTest#degradesWhenProbeThrows` / `#degradesWhenProbeReturnsEmpty`；`YtDlpMediaAcquirerTest#probeReturnsEmptyOnNonZeroExitOrTimeout` / `#probeTreatsNaFieldsAsUnavailable` |
| AC8 上传路线 | 上传 task：workspace 指纹两列保持 `NULL`；中间产物只有 `source.mp4` + `audio-*`（无 `INFO_JSON`）`READY`；日志 `probe-fingerprint/SKIPPED`「上传路线按内容摘要识别」 |
| AC9 事务 / 回收边界 | `WorkspaceFingerprintStoreTest#supersedeOnSourceMediaChangeRollsBackWhenAnyWriteFails`（任一步失败 → `begin/rollback`，指纹不写）；`#reclaimFailureAfterCommitDoesNotFailTheCall`（提交后回收抛异常 → 调用不抛，只 WARN） |

关键日志（服务输出文件里的原文）：

```
run 119 probe-fingerprint SUCCEEDED 首次探测，已记录内容指纹
run 120 probe-fingerprint SUCCEEDED 内容未变化
run 121 probe-fingerprint SUCCEEDED 内容已变化，旧产物已作废，将重新产出
run 122 probe-fingerprint SUCCEEDED 内容未变化
run 122 获取媒体阶段跳过（断点复用）: media=source.mp4
run 123 probe-fingerprint SUCCEEDED 内容未变化
run 123 字幕断点复用跳过: segments=original-segments.json
upload  probe-fingerprint SKIPPED   上传路线按内容摘要识别，无需探测内容指纹
```

### 4.3 独立核查

`trellis-check` 子代理结论：**通过**（T1–T10 / AC1–AC10 与父设计一致）。它修了 1 处 Javadoc 误述（`ContentFingerprintProbeDispatcher#probe` 不会抛 IAE）；主会话随后删掉该类未使用的 `@Slf4j`，并按核查意见订正了 AC2 的日志措辞（内容未变时准备阶段整体短路，不会出现媒体 / 音频的跳过日志，但文件确实没动）。

---

## 5. 审查重点（最可能藏问题的地方）

按这个顺序读 diff：**domain → infrastructure → application/media → application/workspace → application/subtitle/pipeline → tests**。

1. **`AudioPreparationCoordinator` 的「表拒绝 = 真重跑」**
   - `isMediaReusable` 对工作区外文件返回 `true`（没有登记可查）；工作区内要求 media 与 info json 都 `READY` + 文件在。
   - 表拒绝时 `deleteStaleMedia` 只删工作区内文件；yt-dlp 另有 `--force-overwrites`。
   - 音频表拒绝时 `deleteStaleAudio` **删不动就抛**（extractor 拒绝覆盖），别改成静默。
2. **`SubtitleCleanupCoordinator` 的三轴语义**
   - `allowCheckpointReuse=false`：不读文件检查点、也**不读上下文缓存**（内容可能变了）。
   - `regenerate`：`allowCheckpointReuse=true, forceReClean=true, requireContextSummary=true`——仍然允许复用**关键词指纹一致**的上下文缓存（`contextCacheHitSkipsSummarize` 守着），但一定重跑清洗。
   - 别把 `forceReClean` 与 `requireContextSummary` 又合并成一个布尔。
3. **`WorkspaceFingerprintStore` 的事务与分流**
   - `ORIGINAL` / `CLEANED` → `markSupersededAndClearFiles`（清列、文件留给新版本覆盖）。
   - `TRANSLATED` → 先收集行、批量 `markSupersededByTaskIdAndType`（保留文件列），提交后按列删文件 → `markDeleted`。
   - 中间产物 → 先 `findReady` 收集、`supersedeAll`，提交后逐行删文件 → `markDeleted`。
   - 指纹写入在同一事务；任一步失败整批回滚。
4. **`SubtitlePreparationCoordinator` 的门禁**
   - `reuseCompletedOriginal=false` 时 `existing=null`，完全忽略盘上 segments JSON。
   - `true` 时仍读文件；JSON 解析失败（`readCheckpoint` 返回 null）→ 回落完整准备。
5. **`YtDlpMediaAcquirer.parseFingerprint`**
   - 只认整行 `\d+(\.\d+)?|NA` / `\d+|NA`；`NA`、负数、非数字、时长毫秒溢出 `int` 都返回空。
   - 时间戳按 **UTC** 转 `LocalDateTime`（与首次写入一致，跨时区不制造假变更）。
   - `probe` 只跑 `--skip-download --quiet --no-warnings --print`，**不落文件**（测试断言 info json 不存在）。
6. **`IntermediateFileStore.recordProduced`**
   - 没有行 → `insertReady`；有行（任何状态）→ `markReady` 复活并刷新类型 / run / `updated_at`。
   - 工作区外文件不登记（本地 `LocalPath` 可能指向工作区外）。
7. **`SubtitlePipeline` 顺序**
   - `detectContentChange` 必须在算 `originalIntact` / `cleanedIntact` 之前（级联会改变完好性）；也必须在两个占位之前。
   - `IntermediateFileOwner(run.getTaskId(), run.getId())` 在两个阶段共用。
8. **`PROBE_FINGERPRINT` 权重**
   - 权重 5；主管线区间 `{0,2},{2,22},{22,43},{43,67},{67,70},{70,100}`；`SubtitleStageNameTest` 已同步。

---

## 6. 本段有意为之、别当 bug 修

- **首跑**（`workspace` 两列全 `NULL`）→ 只写指纹、不级联（C48 字面口径）。只有「探测曾长期不可用、期间内容真的变了」才会踩到空窗，C49 已接受该降级。
- **只空一列** → 不算首跑、也不算没变，按「变了」级联（安全方向）。
- **取不到指纹** → WARN + `SKIPPED`，不抛异常；回落「有产物就复用」。
- **回收失败**（事务已提交）→ 只 WARN，行停在 `SUPERSEDED` 留给 S7 对账；run 不失败。
- **`ORIGINAL` / `CLEANED` 级联不删文件**：新 MAIN run 会同名覆盖；文件列清空就是「我不再管这个文件」的开关。
- **中间产物一行到底**：`uk(task_id, file_name)`，重新产出是复活同一行，不插新行、不增 `revision`。
- **上传 / 本地不探测**：没有绝对发布时间，内容身份由 sha256 回答。
- **`intermediate_file` 无对外读端点**：验收只能 SQL 直查（C54 只给 artifact）。

---

## 7. 复现命令

```bash
# 编译（增量会给假绿，必须 clean）
cd f:/personal_project/vidSage/backend
JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 全量测试（先删陈旧报告）
rm -rf target/surefire-reports
JAVA_HOME=/f/java_soft/java21 mvn test 2>&1 | grep -E 'Tests run: [0-9]+, Failures|BUILD' | tail -5

# 静态测试数
grep -rc '@Test\|@ParameterizedTest' src/test --include=*.java | awk -F: '{s+=$2} END {print s}'   # 期望 680

# 起服务（真库；无表结构变更）
cd f:/personal_project/vidSage/backend
JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true
# 冒烟完按 PID 杀子 JVM：netstat -ano | grep ':8080 ' | grep LISTENING → taskkill //PID <pid> //F
```

只读核对（已跑过的库状态；**DELETE 会被权限分类器拦，UPDATE / SELECT 可用**）：

```bash
MYSQL_PWD=<本机密码> mysql -u root --default-character-set=utf8mb4 vidsage -N -B -e "..."
```

> 手工验收在本地 dev 库留下了一些人工 `SUPERSEDED`、文件列未清的行（AC5 / AC6 制造），以及一个 UPLOAD task。S7 手工验收前如需干净环境，先重建库里数据（DELETE 那条命令需要用户自己在终端跑）。

---

## 8. 已知欠账（详见 implement.md 末尾）

| # | 欠什么 | 交给 |
|---|---|---|
| 1 | 中间产物表无对外读端点 | 不做（SQL 直查） |
| 2 | 对账端点不存在；`SUPERSEDED` 但文件还在的行只能人工看 | **S7** |
| 3 | 网络调用统一超时 | **S8** |
| 4 | 首跑字面口径的空窗（探测曾不可用） | 不做 / 留档 |
| 5 | 存量工作区没有 `intermediate_file` 行 → 首次重提交会重产出一次 | 不做 |
| 6 | 失败信息里带工作区绝对路径（既有缺陷） | 另开小修 / 择机 |
| 7 | `SubtitlePipeline` 一个类扛三种 run | 未排期 |
| 8 | 前端接新接口 | 另开任务（C60） |
| 9 | 版本回退 / 历史版本 | 不做 |
| 10 | **跳过也落一版新产物** → 旧版 `SUPERSEDED`、而下游 `READY` 译文的血缘指向它；`SUPERSEDED` 行随每次提交累积；`revision` 语义变浑 | **修复任务，排在 S7 之前** —— 输入见 [`.trellis/workspace/HeQiu/handoff-reuse-versioning.md`](../../workspace/HeQiu/handoff-reuse-versioning.md)。**不是 bug，是 design 的成文决定**，别顺手改 |

---

## 9. 审查通过之后

1. 用户批准 → Phase 3.4 提交：**只提交本段工作**，建议一笔 `feat(task): 字幕模型重构 S6 内容指纹与中间产物登记`。
2. **不推送、不合并、不归档**，等用户逐门批准。
3. 归档 / 收口用 `/trellis:finish-work`。
4. 提交前确认那条与 S6 无关的 `SubtitleTaskService` 注释（如果用户不想带进本笔提交，先问）。

---

任务 subtitle-content-change 未归档 — 审查确认后跑 `/trellis:finish-work`。
