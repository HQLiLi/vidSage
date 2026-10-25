# SubtitlePipeline 三条 run 流程拆分 — 执行计划

> 需求见 [prd.md](./prd.md)；技术设计见 [design.md](./design.md)。
> 分支 `feature/subtitle-run-flow-split`｜工作树 `F:/personal_project/vidSage-subtitle-run-flow-split`

---

## 0. 环境准备与基线（开工前必做）

### 0.1 补入 gitignored 的本地配置

**新工作树不会带 gitignored 的文件**。`application-local.yml` 被 `.git/info/exclude` 排除，只在主目录存在；不补它 `VidSageApplicationTests.contextLoads` 必挂（根因是 `Failed to determine a suitable driver class`）。

```bash
cp /f/personal_project/vidSage/backend/src/main/resources/application-local.yml \
   f:/personal_project/vidSage-subtitle-run-flow-split/backend/src/main/resources/application-local.yml
```

### 0.2 量基线

```bash
cd f:/personal_project/vidSage-subtitle-run-flow-split/backend
export JAVA_HOME=/f/java_soft/java21
export PATH="/f/java_soft/java21/bin:$PATH"
rm -rf target/surefire-reports          # 陈旧报告会污染计数，量前必删
mvn clean test 2>&1 | tail -20
```

**期望**：`Tests run: 700, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`。

> 实测于 2026-10-21，本树，与历史口径（10-15 评审）逐项相同。把这段原始输出存进 `evidence/00-baseline.txt`。

### 0.3 记下开工前的 HTTP 面（AC4 的对照物）

起一个冒烟实例，对四个写端点各打一次成功与失败分支，存原始响应：

| 请求 | 期望 |
|---|---|
| `POST /api/tasks`（合法 URL） | 202 + `{taskNo, runNo}` |
| `POST /api/tasks`（url 空白） | 400 |
| `POST /api/tasks/{taskNo}/translate`（语种白名单外） | 400 |
| `POST /api/tasks/{taskNo}/translate`（清洗产物未就绪） | 400 |
| `POST /api/tasks/{未知taskNo}/translate` | 404 |
| 同一任务并发触发两次 | 1×202 + 1×409，409 体带 `{taskNo, runNo}` |

存进 `evidence/00-http-before/`。

> 冒烟实例的启停口径：`spring-boot:run` 绑 `test-compile`，测试红着要以 `-Dmaven.test.skip=true` 起（`-DskipTests` 不够）；`TaskStop` 杀不掉子 JVM，须按 PID `taskkill`。

---

## 1. 建三个新类（旧的两个类原封不动）

**本步结束时：新旧并存，编译通过，`mvn clean test` 仍是 700 / 0 / 0 / 1。** 三个新类此时无人调用——这是刻意的：先让它们各自完整地站起来，能编译能测，再切断旧路。

### 1.1 `SubtitleMainService`

- 新建 `backend/src/main/java/com/vidsage/application/task/SubtitleMainService.java`，标 `@Service`。
- 搬 `SubtitleTaskService` 的：`submitUrl`、`submitUpload`、`handleSubmit`、`findOrCreateWorkspace`、`findOrCreateTask`、`streamToTemp`、`settleUpload`、`Submission`（record）、`TEMP_FILE_PREFIX` / `TEMP_FILE_SUFFIX` 常量。
- 搬 `SubtitlePipeline` 的：`runPipeline`、`workDir`、`toMediaInput`、`progressListener`、`stackOf`、`reason`。
- `handleSubmit` 尾部：`pipeline.run(...)` 改为内联 `concurrencyGate.submit(() -> runPipeline(run, ref, workspace), () -> {...})`（design §6 MAIN）。
- 另写一份 `conflictFor`。
- 类 Javadoc 按现有风格重写（大白话首行 + 保留原有语义说明），**不要照抄旧类 Javadoc**（旧的是三条流程混着写的）。

**验证**：

```bash
cd f:/personal_project/vidSage-subtitle-run-flow-split/backend
mvn clean test-compile          # 判编译一律 clean，增量编译会假绿
```

### 1.2 `SubtitleRecleanService`

- 新建同包 `SubtitleRecleanService.java`，标 `@Service`。
- 搬 `SubtitleTaskService.regenerate` 的同步段 + `SubtitlePipeline.regenerate` 的异步段，合成一个完整流程（design §6 RECLEAN）。
- 另写一份 `conflictFor`、一份 `requireIntactArtifact`（要 `ORIGINAL`）、一份 `workDir` / `toMediaInput` / `progressListener` / `stackOf` / `reason`。

### 1.3 `SubtitleTranslateService`

- 新建同包 `SubtitleTranslateService.java`，标 `@Service`。
- 搬 `SubtitleTaskService.translate` 的同步段 + `SubtitlePipeline.translate` 的异步段（design §6 TRANSLATE）。
- 另写一份 `conflictFor`、一份 `requireIntactArtifact`（要 `CLEANED`）、一份 helper 五件套。
- `STAGE_DONE` 常量只落这一个类。

**验证**：同 1.1 的 `mvn clean test-compile`。

---

## 2. 改 `TaskController` 的注入

- 构造器参数：`SubtitleTaskService taskService` → `SubtitleMainService mainService, SubtitleRecleanService recleanService, SubtitleTranslateService translateService`。
- 四个写端点的调用对象分别改指：`submitUrl` / `submitUpload` → `mainService`；`translate` → `translateService`；`regenerate` → `recleanService`。
- **方法体其余一行不改**（含 Controller 侧的卫语句与日志）。读端点（`getTask` / `subscribeRunEvents` / `findKeywords`）仍走 `queryService`，不动。
- 类 Javadoc 里若提到「不等管线跑完」，按现状措辞保留即可。

**验证**：`mvn clean test-compile` + `mvn clean test`（此时旧的两个类还在，但已无人调用，测试应仍全绿）。

---

## 3. 删两个旧类

一次性删掉（`SubtitleTaskService` 引用了 `SubtitlePipeline`，必须同一步删）：

```bash
rm backend/src/main/java/com/vidsage/application/task/SubtitleTaskService.java
rm backend/src/main/java/com/vidsage/application/subtitle/pipeline/SubtitlePipeline.java
```

**验证（AC1 + AC2 的证据）**：

```bash
cd f:/personal_project/vidSage-subtitle-run-flow-split/backend/src/main

# AC1：旧类零命中
grep -rn "SubtitlePipeline\|SubtitleTaskService" . ; echo "exit=$?"   # 期望无输出

# AC2：无跨流程引用（逐个 grep，期望全空）
grep -n "SubtitleTranslationCoordinator" java/com/vidsage/application/task/SubtitleMainService.java
grep -n "SubtitlePreparationCoordinator\|SubtitleFingerprintCoordinator\|SubtitleTranslationCoordinator\|MediaIdentityResolver" java/com/vidsage/application/task/SubtitleRecleanService.java
grep -n "SubtitlePreparationCoordinator\|SubtitleCleanupCoordinator\|SubtitleFingerprintCoordinator\|SubtitleKeywordRepository\|MediaIdentityResolver" java/com/vidsage/application/task/SubtitleTranslateService.java

# 三个新类都是 @Service
grep -n "@Service" java/com/vidsage/application/task/Subtitle*Service.java
```

**注意**：AC1 的 grep 要覆盖注释与 Javadoc。`SubtitlePipeline` 这个词在 `application/subtitle/package-info.java`、`application/task/package-info.java`、`SubtitleStageName.java`、`SubtitleArtifactStore.java` 里可能出现在注释中——**这些注释也要一并订正**，否则 AC1 的 grep 不干净。

---

## 4. 测试重分

- `SubtitlePipelineTest`（22 个）拆三份：MAIN 14 → `SubtitleMainServiceTest`，RECLEAN 4 → `SubtitleRecleanServiceTest`，TRANSLATE 4 → `SubtitleTranslateServiceTest`。
- `SubtitleTaskServiceTest`（13 个）按 run 类型同样三分。
- **逐条保留**：产出「旧方法名 → 新方法名」对照表存 `evidence/`，一条不漏。
- `reason()` 兜底那条（`failureMessageFallsBackToExceptionClassNameWhenMessageNull`）**只保留一条**，放 `SubtitleMainServiceTest`。三份 `reason()` 是逐字相同的拷贝，RECLEAN / TRANSLATE 那两份由各自流程的失败用例覆盖；刻意加两条重复用例会把 AC5「总数与基线逐项相同」这个机器判据搅浑（design §9 已是此口径）。
- 测试类里的 mock 按各自类的新依赖裁剪（例如 `SubtitleRecleanServiceTest` 不该再 mock `SubtitleTranslationCoordinator`）。

**验证**：

```bash
cd f:/personal_project/vidSage-subtitle-run-flow-split/backend
rm -rf target/surefire-reports
mvn clean test 2>&1 | tail -20
```

**期望**：`Tests run: 700, Failures: 0, Errors: 0, Skipped: 1`（AC5 —— 与步骤 0.2 逐项相同）。存 `evidence/05-tests-after.txt`。

---

## 5. 行为验证（AC3 / AC4 / AC6）

### 5.1 三条 run 真链路（AC3）

起冒烟实例，三种 run 各跑一次真链路（真 yt-dlp + 真 ASR + 真 LLM）：

- MAIN：提交一个新 URL → 轮询到 SUCCEEDED
- RECLEAN：对该任务改关键词重跑 → SUCCEEDED
- TRANSLATE：对该任务触发翻译 → SUCCEEDED

核对：三条 run 的 `error_message` 互不串台；复用命中那一趟广播 `SKIPPED` 且产物表零写入。

存 `evidence/03-three-runs.txt`。

### 5.2 HTTP 面对照（AC4）

重跑步骤 0.3 的同一组请求，与 `evidence/00-http-before/` 逐项对照。存 `evidence/04-http-after/` + 对照表。

### 5.3 重清洗语义钉法（AC6）

跑 `SubtitleCleanupCoordinatorTest` 里那条「旧行哈希与新版完全相同 → 下游仍被作废」的用例，存用例名与输出到 `evidence/06-reclean-semantics.txt`。

---

## 6. spec 更新（AC7）

订正 `.trellis/spec/backend/directory-structure.md` 里因本任务失实的表述：

| 位置 | 现状（失实） | 订正为 |
|---|---|---|
| 第 76 行 | 「`SubtitleTaskService`——`TaskController` 唯一注入的应用服务」 | 三个用例服务 `SubtitleMainService` / `SubtitleRecleanService` / `SubtitleTranslateService` |
| 第 91 行 | 「管线构件」成员表含 `SubtitlePipeline`（管线的所有者） | 该行删除或改写为现状；`SubtitlePipeline` 已不存在 |
| 第 108 行 | 「全项目只有 `SubtitleTaskService` 有 web 调用面」 | 这是**历史注**，保留原文不动（讲的是 2026-10-06 当时的事实）；如与现状冲突，按现有惯例在该行末尾补一条日期注 |
| 第 213 行 | `application/<上下文>/` 目录说明 | 按现状更新 |
| 第 300 行 | stereotype 表里的 `SubtitlePipeline` 举例 | 换成现状举例 |
| 第 491 行 | 「`SubtitleTaskService` 用 `@Service`」 | 换成三个新类 |

**口径**：spec 只写**现在是什么**，不写什么已经退场（既有惯例）；历史注保留原文。

**验证**：`git diff .trellis/spec/` 存 `evidence/07-spec-diff.txt`。

---

## 7. 收尾

```bash
cd f:/personal_project/vidSage-subtitle-run-flow-split
git status
git diff --stat
```

自查清单：

- [ ] AC1 旧类 grep 零命中（含注释）
- [ ] AC2 三个类无跨流程引用
- [ ] AC3 三条 run 真链路全 SUCCEEDED
- [ ] AC4 HTTP 面与开工前逐项相同
- [ ] AC5 测试 700 / 0 / 0 / 1 + 用例对照表
- [ ] AC6 重清洗语义写进 design 且单测钉住
- [ ] AC7 spec 订正
- [ ] 证据齐备落在 `evidence/`

**门 1**：跑完上面全部验证后**停下等用户审查**，不得自行提交。用户批准后才 `git commit` + `git push -u origin feature/subtitle-run-flow-split`。

---

## 回滚点

| 回滚到 | 怎么做 |
|---|---|
| 步骤 1 之前 | `git checkout -- .`（此时只有新增文件，`git clean -fd` 即可） |
| 步骤 3 之前 | `git checkout -- .` + 删掉三个新文件 |
| 全部 | 丢弃整条 `feature/subtitle-run-flow-split` 分支；无数据迁移，无残留 |

---

## 审查门（逐门等用户明确表态，一次只推进一道）

```
步骤 0–6 修改 + 测试通过
  └─ 门 1：停下 → 用户审查（可提修改建议 → 原地循环）
       └─ 用户批准提交 → git commit + git push -u origin feature/subtitle-run-flow-split
            └─ 门 2：用户审查分支 + 明确批准合并 → 在主目录 git merge --no-ff 到 dev + 删分支
                 └─ 门 3：用户主动归档 → /trellis:finish-work
```

> **合并只能在主目录做**：`dev` 同一时刻只能被一个目录 checkout，本树站着 `feature/subtitle-run-flow-split`，`git merge` 写的是当前分支。
