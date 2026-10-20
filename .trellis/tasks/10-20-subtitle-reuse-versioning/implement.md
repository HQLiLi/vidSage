# S6.5 执行计划：复用不落版（跳过即不换版）

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。**
> 本文件写本段的**段级技术落点 + 执行顺序 + 验证方式**（父任务章程：每段具体做法写在各子任务的 `implement.md`）。
> **本文件是执行参考稿，不是契约**：发现与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。
> 范围与验收见 [prd.md](./prd.md)；技术形状（契约、数据流、边界矩阵）见 [design.md](./design.md)。

---

## 0. 与现状代码的校准

下表是开工前必须按「实际代码」而不是「文档措辞」核对的地方（2026-10-20 逐项对照 `backend/src/main` 核对）：

| # | 文档的说法 | 实际代码 | 对本段的影响 |
|---|---|---|---|
| 1 | handoff §7：「S6 的 AC2 写的是『文件 mtime 不变 + `ORIGINAL` rev13 → rev14』」 | **失实**：S6 的 AC2 只断言 mtime 不变 + 中间产物仍 `READY`；`rev14` 在 AC1 的验收记录里 | 本段 AC 直接按代码事实重写，**不去改 S6 的 AC**（prd 已记订正） |
| 2 | handoff §5：跳过时 `original` 变量与 `derived_from_artifact_id` 都得改用已有的那条 READY 行 | 对。`original` 只在 `runPipeline` 内部用，唯一消费者是 `createCreatingPlaceholder(CLEANED, …, original.getId(), …)` | 命中时 `original` = `findIntactArtifact(ORIGINAL)` 交出的那条行；两者不一致（表说没有、协调器说可复用）→ 抛 ISE |
| 3 | handoff O4：「ORIGINAL 重跑、CLEANED 复用在内容变更级联下不可达」 | **只在级联那条路上不可达**。手工把 ORIGINAL 行打成 `SUPERSEDED`（S6 的 AC6a 就是这么验的）时**可达** | 记成**已知窗口**（design §5），本段不修 |
| 4 | 落定方法里「旧行必须在清列之前读」 | 理由会变（不再比哈希），但**读还在** —— `markSupersededAndClearFiles` 要旧行的 id | 保留读，改写注释 |
| 5 | `doClean` 的 `current` 只服务断点复用 | **不准确**：它还决定上下文缓存能不能读（`SubtitleContext cached = current ? readJsonOrNull(ctxFile) : null`） | 搬走 skip 分支后 `current` **仍要留**，判定条件变成 `mayUseContextCache && current` |
| 6 | `SubtitleTaskService` / `SubtitleArtifactQueryService` 大概率不用动 | 对：`requireIntactArtifact` 走 `findIntactArtifact`，读路径只认 `READY` | 确认不动 |
| 7 | 「跳过 ⇒ 零写入」 | 跳过那趟 run 仍会写：`task_run`（阶段推进 + 终态）与 `subtitle_task.updated_at`（`TaskRunSupervisor.finish` 里 `touchUpdatedAt`） | AC3 必须把这两处排除掉 |
| 8 | `derived_from_run_id` 是审计列 | 唯一消费者是 `markCreatingAsFailedByRunId`（SQL：`WHERE derived_from_run_id = ? AND status = 'CREATING'`），由 `TaskRunSupervisor:88` 在收口时调 | 跳过时不刷新该列**安全**：跳过没有新占位行，那句是空操作 |
| 9 | `SubtitlePipeline` 里阶段类的返回值 | `prepare(...)` / `clean(...)` 的返回值在 `runPipeline` 里**都被丢弃** | 改成 `reuseIfAvailable` 返回 `Optional<...>` 不改变调用点的使用习惯 |
| 10 | 阶段事件的文案与条数 | 复用命中时 `prepare` 发两条 SKIPPED（转写 / 写盘）、`clean` 发一条 SKIPPED | 搬进 `reuseIfAvailable` 后**照旧发**，条数与文案不变 → SSE / 前端表现不变（design D5） |

**结论**：改动面集中在四处 —— 两个阶段类（判据上移 + 签名收窄）、管线（条件创建）、`SubtitleArtifactStore`（删判据）、存量测试。DDL / 端点 / DTO / SSE 全不动。

---

## 段内顺序（四步）

```
0  预期文件树与关键形状过审（门）
1  ★ 复用判据上移 + 占位条件创建（行为拐点，端到端可见）
2  ★ 删 isContentChanged（行为拐点，只影响 RECLEAN）
3  文档同步
4  全量验证 + 手工验收 + 欠账核对
```

**顺序的理由**

- **步骤 1 与 2 不能拆成两个提交**：步骤 1 改完不落版之后，`isContentChanged` 那条判据就失去了输入（它比的「旧行 vs 新产出版本」只在「每次都落版」的世界里才成立）。反过来先删步骤 2 也不行 —— 跳过还在落版时，删掉判据会让「复用命中」把下游译文白作废一遍（每次提交都烧一次模型）。
- **两步各自是独立的行为拐点**，都能用 SQL + curl 单独验：
  - 步骤 1 验「同 URL 再提交 → 库里逐行不变」
  - 步骤 2 验「RECLEAN → 译文被作废」
- **步骤 3 / 4 各自独立**，都绿。

---

## 步骤 0：预期文件树与关键形状过审（门）

**这是一道门，不是准备工作。** 先把下面的文件树、§0.2 的方法形状给项目所有者过一眼，**点头之后才开分支、才写第一行代码**（先例：S1–S6 的同一道门）。

过审时要特别确认的五件事：

1. **复用判据的形状**：`reuseIfAvailable(...)` 一次调用同时回答「能不能复用」并交出复用结果（design D1），而不是「纯谓词 + 真跑入口再读一遍」。
2. **`prepare` / `clean` 的复用参数删掉**；`clean` 保留的那个布尔值**改名** `mayUseContextCache`（design D4）。
3. **命中时取已有的 READY 行**当 `original`；判据不一致抛 ISE。
4. **`ensureUsableContext` 整个删掉**（prd T5）—— 复用命中不再补摘上下文。
5. **`isContentChanged` 整个删掉**（prd T6）—— 清洗真重跑 ⇒ 无条件作废下游；四个 `sha256` 列照旧写。

### 0.1 预期文件树（本段碰的）

```
backend/src/main/java/com/vidsage/
├── application/subtitle/
│   ├── SubtitleArtifactStore.java                    ← 删 isContentChanged；settleCleanedAndSupersedeDownstream 改无条件级联
│   │                                                     settleArtifact 只改 Javadoc
│   └── pipeline/
│       ├── SubtitlePipeline.java                     ← runPipeline：两个占位改条件创建；命中时取已有 READY 行
│       ├── SubtitlePreparationCoordinator.java       ← 新增 reuseIfAvailable；prepare 删复用参数
│       └── SubtitleCleanupCoordinator.java           ← 新增 reuseIfAvailable；clean 参数改名；删 ensureUsableContext
│                                                        doClean 三布尔收成两布尔
└── （其余一律不动：domain/、infrastructure/、web/、resources/schema.sql）

backend/src/test/java/com/vidsage/
├── application/subtitle/
│   ├── SubtitleArtifactStoreTest.java                ← 删 2 个用例；加 1 个「哈希相同也作废」
│   └── pipeline/
│       ├── SubtitlePipelineTest.java                 ← 改 2 个用例；加 4 个（命中不落版 ×2、独立判断、判据不一致）
│       ├── SubtitlePreparationCoordinatorTest.java  ← 改 4 个用例；加 0 个（判据上移，用例跟着搬）
│       └── SubtitleCleanupCoordinatorTest.java       ← 删 2 个用例；改 6 个；加 1 个「context.json 不动」

文档（步骤 3）
├── .trellis/tasks/10-15-subtitle-task-model-refactor/design.md      ← 六处
├── .trellis/tasks/archive/2026-10/10-17-subtitle-content-change/prd.md / implement.md
├── .trellis/workspace/HeQiu/handoff-reuse-versioning.md             ← 加抬头指向本任务
└── .trellis/spec/backend/directory-structure.md                     ← 仅当阶段事件表需要措辞对齐
```

### 0.2 新增 / 改动的方法形状

```java
// SubtitlePreparationCoordinator
public Optional<SubtitleStageOutput> reuseIfAvailable(Path workDir, boolean artifactIntact,
                                                      ProgressListener listener);
public SubtitleStageOutput prepare(MediaInput input, Path workDir, ProgressListener listener,
                                   IntermediateFileOwner owner);
// 删：prepare(input, workDir, boolean reuseCompletedOriginal, owner) 及其无-listener 重载

// SubtitleCleanupCoordinator
public Optional<CleanedSubtitle> reuseIfAvailable(Path workDir, SubtitleKeywords keywords,
                                                  boolean artifactIntact, ProgressListener listener);
public CleanedSubtitle clean(MediaInput input, Path workDir, SubtitleKeywords keywords,
                             ProgressListener listener, boolean mayUseContextCache);
public CleanedSubtitle regenerate(MediaInput input, Path workDir, SubtitleKeywords keywords, ProgressListener listener);
// 删：ensureUsableContext(Path, Path, SubtitleKeywords)
// 改：doClean(input, workDir, keywords, listener, mayUseContextCache, requireContextSummary)  ← 三布尔收成两布尔
//     开始事件的文案改判 requireContextSummary（forceReClean 消失）

// SubtitleArtifactStore
// 删：isContentChanged(SubtitleArtifact, ArtifactFiles)
// 改：settleCleanedAndSupersedeDownstream 的 body（下游作废无条件）
```

> **`reuseIfAvailable` 收 `ProgressListener`** 是过审门定的（design §3 开头）：命中时那几条 SKIPPED 走 `StageEvents.emit`，
> 而 listener 是每 run 一份、由管线传下来，阶段类自己拿不到。

管线的改造后形状见 [design.md §3.3](./design.md)。

---

## 步骤 1：★ 复用判据上移 + 占位条件创建（行为拐点）

**改什么**（一次改完，中间不留编译不过的状态）：

1. `SubtitlePreparationCoordinator`：把 `prepare` 里的 skip 分支搬进新的 `reuseIfAvailable(workDir, artifactIntact, listener)`；
   `prepare` 删掉 `reuseCompletedOriginal` 参数，**不再读文件检查点**。
   - skip 分支里的两条 SKIPPED 事件、`toArtifact(...)` 的返回值一起搬过去。
   - 参数校验（`input` / `listener` / `owner` 非空）留在 `prepare`；`reuseIfAvailable` 只校验 `workDir` 与 `listener`。
2. `SubtitleCleanupCoordinator`：
   - 新增 `reuseIfAvailable(workDir, keywords, artifactIntact, listener)` —— 搬走 `doClean` 里 `current && !forceReClean` 那一段（含那条 SKIPPED 事件与 `toCleaned(..., 0,0,0,0)`）。
   - **删 `ensureUsableContext`** 及其调用点。
   - `doClean` 的三布尔收成两布尔：`forceReClean` 消失（skip 分支已搬走），`allowCheckpointReuse` 改名 `mayUseContextCache`；`current` 保留，缓存判定改成 `mayUseContextCache && current`。
   - `regenerate` 传 `mayUseContextCache = true`。
   - 开始事件的文案改判 `requireContextSummary`（原判 `forceReClean`），文案与条数不变。
3. `SubtitlePipeline.runPipeline`：按 design §3.3 改两个阶段。

**验证**：`clean test-compile` 过 → 改存量测试（见 design §7 清单）→ 全量测试绿。

**行为拐点**：同 URL 再提交、内容与关键词都没变 → 库里 `subtitle_artifact` 逐行不变（手工验收 AC1–AC3）。

---

## 步骤 2：★ 删 isContentChanged（行为拐点）

**改什么**：

1. 删 `SubtitleArtifactStore.isContentChanged`。
2. `settleCleanedAndSupersedeDownstream`：下游作废改成无条件（形状见 [design.md §3.4](./design.md)）；
   改掉「旧行必须在清列之前读：…再也比不出内容变了没有」那条注释（读还在，理由换成「要 id」）。
3. `settleArtifact`：只改 Javadoc —— 语义从「每次落版」收窄成「只在真产出之后调用；跳过复用时不调用」。
4. 删 `SubtitleArtifactStoreTest` 的两个用例，加一个「旧行哈希与新版完全相同 → 下游仍被作废」。

**验证**：`clean test-compile` 过 → 全量测试绿 → 手工验收 AC6（RECLEAN 后译文被作废）。

**行为拐点**：RECLEAN 产出与旧版逐字节相同时，下游译文**也**被作废（现状会留着）。

---

## 步骤 3：文档同步

| 文件 | 动什么 |
|---|---|
| 父 `10-15/design.md` | §5 W5 的表行与注（级联改无条件、2026-10-19 那条订正重写为现状）、§7.3 步骤 3/4（占位改条件创建）+ C62 那条注（复用判据提到公开面、两门禁独立）、§7.4 步骤 6 ②（级联判据）、§7.5 第 3 步的注（§615 那句）、§8.5（`revision` 语义已是「内容第几版」）、§1.4 的 `derived_from_run_id` 列注释（跳过复用不刷新） |
| 父 `10-15/design.md` §8.3（**清单外**，实际改了） | 原子替换的理由句里「断点复用的判据是『文件在不在』」自 S6 起就失实，与 §7.3 那道注直接冲突 —— 改成「产物表那道门拦不住半截文件」 |
| S6 的 `prd.md` / `implement.md` | **核完，无需改**：AC2 只断言 mtime 与中间产物、AC5/AC6 的两条门禁在改造后仍逐条成立、`revision +1` 那处是内容真变的重跑（递增本来就对）。本任务 prd 里那条订正已记明「不必去改 S6 的 AC」 |
| `.trellis/workspace/HeQiu/handoff-reuse-versioning.md` | 抬头改成：本任务已开、验收口径以任务 `prd.md` 为准，本文降为论证留档；O1–O4 已定案为 T1–T4 |
| `.trellis/spec/backend/directory-structure.md` | **改了 3 处**（原估「预计不改」，估错）：阶段事件表那行确实不用动，但命名规范里三处拿被删/被改短的名字当先例 —— ① §「过去式动词短语」的先例 `SubtitleArtifactStore#isContentChanged` 已删；② 变量名表的 `previousReady`；③ 改名登记表里 `old → previousReady` 那行（中间变量没了，只剩一行 lambda 参数） |
| `.trellis/tasks/10-20-.../implement.md` §0.1 | 测试树里的用例计数与 design §7 对不上（估 8 个、实际 6 个），按实际改齐 |

**判据**：`grep -rn "落新版本\|照样会落一版\|每次落版" .trellis/tasks/10-15-subtitle-task-model-refactor/design.md` 无命中（或命中处都已带订正）。

---

## 步骤 4：全量验证 + 手工验收 + 欠账核对

### 4.1 命令

```bash
cd backend

# 1) 判编译 —— 增量编译会给假绿，一律 clean
JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 2) 全量测试 —— 先删陈旧报告，免得读到上一轮的 XML
rm -rf target/surefire-reports
JAVA_HOME=/f/java_soft/java21 mvn test
grep -rc '@Test\|@ParameterizedTest' src/test --include=*.java | awk -F: '{s+=$2} END {print s}'   # 静态数（开工前 681）

# 3) 起服务（真库跑 schema.sql；本段无表结构变更）
JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true
# 冒烟完按 PID taskkill（TaskStop 杀不掉子 JVM）
```

> 退出码会被管道吃掉；判绿看 `[ERROR]` 段与 `BUILD SUCCESS/FAILURE`。

### 4.2 从零准备

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
rm -rf backend/data/works     # 用绝对路径
```

### 4.3 手工验收（对应 prd 的 AC）

```bash
DB='mysql -u root -p --default-character-set=utf8mb4 vidsage -N -B -e'   # 密码按本机配置
ART='SELECT id, artifact_type, lang, revision, status, derived_from_run_id, derived_from_artifact_id,
            segments_file_name, subtitles_file_name, context_file_name, terms_file_name,
            segments_sha256, subtitles_sha256, context_sha256, terms_sha256
     FROM subtitle_artifact WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no="'"$T"'") ORDER BY id;'
DIR=backend/data/works/BV11QMd6WEJp_p1

# ── 起步：首跑 ────────────────────────────────────────────────────
T=$(curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
      -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}' | sed -E 's/.*"taskNo":"([^"]+)".*/\1/')
# 轮询 GET /api/tasks/$T 直到 lastRun.status=SUCCEEDED

# ── AC7：首跑不受影响 ────────────────────────────────────────────
$DB "SELECT artifact_type, revision, status FROM subtitle_artifact
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T');"
#   → ORIGINAL rev1 READY / CLEANED rev1 READY

# 先造一份译文，好验 AC2 / AC6 的「下游动不动」
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"en"}'
# 等 SUCCEEDED → /artifacts 里多一条 TRANSLATED(en)

# ── AC1 / AC2 / AC3：复用命中 → 不落版 + 零写入 ───────────────────
ls -l --time-style=full-iso $DIR/* > /tmp/mtime-before.txt
$DB "$ART" > /tmp/art-before.txt
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'      # 同一 URL、不带关键词
# 等 SUCCEEDED（预期 2 秒级完成）
ls -l --time-style=full-iso $DIR/* > /tmp/mtime-after.txt
$DB "$ART" > /tmp/art-after.txt

sed -i 's/\r$//' /tmp/art-before.txt /tmp/art-after.txt /tmp/mtime-before.txt /tmp/mtime-after.txt
diff /tmp/art-before.txt  /tmp/art-after.txt    # 必须无输出（AC1 / AC2）
diff /tmp/mtime-before.txt /tmp/mtime-after.txt # 必须无输出（AC3，含 context.json）
# 日志：两条「字幕断点复用跳过」「字幕清洗断点复用跳过」；无「开始转写」「开始清洗」

$DB "SELECT file_name, status, derived_from_run_id FROM intermediate_file
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T');"
#   → 三行仍 READY、derived_from_run_id 不变

# ── AC4：真重跑照旧落版 ──────────────────────────────────────────
$DB "UPDATE subtitle_artifact SET status='SUPERSEDED'
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T')
       AND artifact_type='ORIGINAL' AND status='READY';"
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'
# 等 SUCCEEDED
#   → 日志有「开始转写」；新 ORIGINAL 行 revision+1、derived_from_run_id = 本次 run；
#     旧 ORIGINAL 行仍是 SUPERSEDED（不被二次降级、不被删）
#   → 注意：这一趟 CLEANED 仍可能复用命中（检查点还在、关键词没变）→ 不落新版。
#     这是 design §5 记的「已知窗口」，不是缺陷。

# ── AC5：两个门禁独立判断 ────────────────────────────────────────
# 先恢复成一个干净状态：从零重跑一次首跑，然后改关键词重提交
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp","keywords":["影视飓风"]}'
# 等 SUCCEEDED
$DB "$ART"
#   → ORIGINAL 行：id / revision / status 与提交前一致（复用命中，不落版）
#   → CLEANED 行：revision+1、derived_from_artifact_id = 那条仍 READY 的旧 ORIGINAL 的 id
#   → 旧 TRANSLATED：SUPERSEDED → 文件删完 → DELETED

# ── AC6：级联判据已删 ────────────────────────────────────────────
# 先让该 task 有一份 READY 译文
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh-Hans","targetLanguage":"en"}'
# 等 SUCCEEDED → TRANSLATED(en) READY
curl -s -X POST localhost:8080/api/tasks/$T/subtitles/regenerate -H 'Content-Type: application/json' \
     -d '{"keywords":["影视飓风"]}'                                   # 关键词不变也照样重跑
# 等 SUCCEEDED
$DB "SELECT artifact_type, lang, revision, status FROM subtitle_artifact
     WHERE task_id=(SELECT id FROM subtitle_task WHERE task_no='$T') ORDER BY artifact_type, lang, revision;"
#   → 新 CLEANED READY（revision+1）
#   → 旧 TRANSLATED(en)：SUPERSEDED 或 DELETED（**一律被作废**，不再因「产出相同」而保留）

# ── AC8：内容变更回归（S6 的 AC3 / AC4）─────────────────────────
$DB "UPDATE workspace SET duration_ms = duration_ms + 1000
     WHERE id=(SELECT workspace_id FROM subtitle_task WHERE task_no='$T');"
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp"}'
# 等 SUCCEEDED
#   → 日志「内容已变化」+「开始获取媒体 / 开始抽取音频 / 开始转写 / 开始清洗」
#   → 旧 artifact 全 SUPERSEDED（八个文件/哈希列 NULL）、中间产物走 S6 的复活路径
```

### 4.4 收尾

逐条核对下面的欠账表 → 报告（含测试条数基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。

---

## 回滚点

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| **步骤 1 之后** | 跳过不再落版；`isContentChanged` 还在 | **不能停**：判据失去输入，RECLEAN 之外没有调用点，留着是死代码；若此时回滚步骤 1，判据恢复有效 —— 二者要么一起进，要么都不进 |
| **步骤 2 之后** | 两个行为拐点都生效 | 可停；功能完整 |
| **步骤 3** | 只动文档 | 单独可回滚 |

**彻底回退**：`git revert` 本段提交；**无表结构变更**，不需要 DROP / 重建。已按新口径产生的行（`revision` 不连续）在旧口径下仍合法。

---

## 欠账清单（本段结束时哪些东西还没到位、交给哪一段）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | **S7 的 `prd.md` 要补写「本段是它的前置」** | **S7 的规划** | 本段定下「跳过不落版」，S7 的对账判据（尤其 ④ 与「每 run 至少一版」的隐含假设）建立在这上面 |
| 2 | 只读对账端点不存在；`SUPERSEDED` 但文件还在的行只能靠人看 | **S7** | 四类全报、② 只报不修；哈希比对；临时后缀白名单全局化 |
| 3 | 网络调用统一超时 | **S8** | C46 |
| 4 | `SubtitlePipeline` 一个类扛三种 run | **未排期** | 拆成三条管线；届时「重清洗无条件作废下游」这条语义由它定稿（本段已把判据删掉，不为它预先造形状）。**另**：`doClean` 里那条「开始重新生成字幕」的文案现在挂在 `requireContextSummary` 上（`forceReClean` 消失后它成了唯一区分），是**代理**——拆成两条管线时改挂管线身份，`doClean` 这两个布尔一起重估（见 design §3.2 末） |
| 5 | 存量 `SUPERSEDED` 垃圾行（八个文件列全 NULL） | **不做** | 无消费者、对账 ② 也不报它；本段只保证不再新增。手工验收造的假行可在 S7 验收前重建库 |
| 6 | 「ORIGINAL 真重跑 + CLEANED 复用命中」的已知窗口 | **不做** | design §5；要修得给两个门禁加耦合，与 T2 相冲 |
| 7 | 失败信息里带工作区绝对路径（既有缺陷） | **另开小修 / 择机** | S5 欠账 #9 |
| 8 | 前端一定点不通（本段无新端点，但前端整体未接） | **另开任务**（C60） | 后端全部段做完再动 |
| 9 | 版本回退 / 历史版本 | **不做** | C52 / C58 |

**收尾动作**：跑完步骤 4 → 逐条核对上表 → 报告（含测试基线变化账、手工验收命令输出）→ **停等审查，不自行提交/合并**。

---

## 收尾报告（2026-10-20）

### 1. 手工验收（真库 + 真链路，`BV1Va7V66Ebp`，run 1–8）

| AC | 结果 | 证据 |
|---|---|---|
| AC7 首跑 | PASS | `ORIGINAL rev1 READY` / `CLEANED rev1 READY` |
| AC1 复用不落版（ORIGINAL） | PASS | 同 URL 重提交（run 3）前后 `SELECT … FROM subtitle_artifact` 的 `diff` **无输出** |
| AC2 复用不落版（CLEANED）+ 下游不动 | PASS | 同一份 diff 覆盖 CLEANED 与 `TRANSLATED(en)` 行；日志两条「断点复用跳过」，无任何「开始…」 |
| AC3 复用命中零写入 | PASS | 文件 mtime `diff` 无输出（含 `context.json`）；`intermediate_file` / `subtitle_keyword` / `workspace` 三表 diff 全空；只有 `subtitle_task.updated_at` 变（AC3 明确排除）。复用那趟 **2 秒**跑完 |
| AC4 真重跑照旧落版 | PASS | 手工把 ORIGINAL 打成 `SUPERSEDED` → 新 `ORIGINAL rev2`、`derived_from_run_id` = 本次 run；旧行仍 `SUPERSEDED`；日志「开始转写」。**CLEANED 仍命中跳过** —— design §5 记的已知窗口，与设计一致 |
| AC5 两门禁独立判断 | PASS | 改关键词后 `ORIGINAL rev2` 逐行不变；新 `CLEANED rev2` 的 `derived_from_artifact_id` = 那条**仍 READY** 的旧 ORIGINAL；旧译文 `DELETED` |
| AC6 级联判据已删 | PASS | RECLEAN → 新 `CLEANED rev3`；`READY` 译文**无条件**被作废成 `DELETED` |
| AC8 内容变更回归 | PASS | `duration_ms+1000` → 日志「内容已变化，旧产物已作废」；旧 artifact 全 `SUPERSEDED` + 八列清空；中间产物复活 `READY`；「获取媒体 / 抽取音频 / 转写 / 清洗」全走；指纹写回真值 |
| AC9 回归 | PASS | `clean test-compile` 过；683 / 0 失败 / 0 错误 / 1 跳过（存量 `@Disabled`）；服务起得来；完整流程走通 |
| AC10 文档一致 | PASS | 判据 grep 无命中 |

**基线账**：静态 `@Test`/`@ParameterizedTest` **681 → 683**（删 4 / 改 14 / 加 6）。

**真库列注释**：`schema.sql` 的 `derived_from_run_id` 注释已改；本机库另跑了一次 `ALTER TABLE … MODIFY COLUMN` 跟上（`CREATE TABLE IF NOT EXISTS` 不会改已存在的表），两侧逐字一致。

### 2. 欠账核对（上表九条，逐条）

- **#1（S7 的 prd 要补写「本段是它的前置」）** —— **仍欠，必须主动交接**。本段定下「跳过不落版」，S7 的对账判据（尤其第 ④ 类与「每 run 至少一版」的隐含假设）建立在这上面。
- **#2 / #3 / #7 / #8** —— 仍欠，分别交 S7 / S8 / 另开小修 / 另开任务（C60），本段未动。
- **#4（一个类扛三种 run）** —— 仍欠，未排期。**需交接的细节**：`doClean` 里「开始重新生成字幕」这条文案现在挂在 `requireContextSummary` 上（`forceReClean` 消失后它成了唯一区分），是**代理**；拆成两条管线时改挂管线身份，那两个布尔一起重估。
- **#5 / #6 / #9** —— 按计划**不做**，理由见上表。其中 #6（已知窗口）在 AC4 里**实测到了**，行为与 design §5 一致，不是缺陷。
- **本段新增的欠账**：`SubtitleCleanupCoordinator:51` 一族 `<route>` 占位失实引用（跨 9 个文件 15 处）**已在本段一并扫干净**（见下节），不再是欠账。

### 3. 清单外一并做的两件

1. **`<route>` 注释补扫**（9 文件 15 处）：去路线化那轮漏扫的 Javadoc 占位，真名单源在 `SubtitleArtifactSpec`。判据：`grep -rn '<route>' backend/src` 归零。
2. **spec 命名先例订正**（`directory-structure.md` 3 处）：原先把本段删掉/改短的 `isContentChanged` 与 `previousReady` 当先例，改用现存名字。
