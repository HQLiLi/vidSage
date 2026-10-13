# 构件改名 — 执行步骤

> **执行者是项目所有者本人。** main 代码由他写；测试由 AI 改；AI 跑验证并报结果。
> 本文的落点全部按**类名 grep** 得出，逐个核过。

## 前置

**顺序约束**：本任务排在 `10-07-shape-refactor` **批次 2 之后**（理由见 `prd.md` 末节）。
**分支**：`feature/pipeline-stage-rename`，从批次 2 落地后的新 HEAD 切出。**尚未创建。**

**开工前跑一次基线**（会改类名 → 必须 `clean`）：

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

基线：**45 类 / 469 用例 / 0 失败 / 1 跳过**（批次 2 完成后若数字有变，以那次的报数为准；对不上先查基线，不要往下走）。

**一个已核实的前提**：`@Component` 的默认 bean 名会随类名变（`subtitlePreparationStage` → `subtitlePreparationCoordinator`）。项目里**没有按名注入**这四个类——所有 `@Qualifier` 都只用于 `ChatClient`。改名安全，不需要额外处理。

**变量名**（字段 / 构造参数的小驼峰）：**以 main 为准，测试逐字对齐**——main 怎么命名，测试跟着来（见 `prd.md` 决定 3）。

**本轮待处理（不做，只记着）**：`TranscriptionStage` 已被调用方绕过一半（调用方同时持有它和它包装的 `ASRProvider`），用户定「先放着」，以后连同管线一起改 → 见 `prd.md` 决定 1。

---

# 步骤 1 — main 改名（项目所有者）

## 1.1 `VideoTaskPipeline` → `SubtitlePipeline`

**本体（1 文件）**
- `application/task/pipeline/VideoTaskPipeline.java` —— 文件名、`:58` 类声明、`:87` 构造器
  - ⚠️ 类 Javadoc `:34-36` 里点名了 `SubtitlePreparationStage` / `SubtitleCleanupStage`——**这两个类名本任务改**；同一段里的六个阶段名（acquire / extract-audio / transcribe / write-subtitles）**留给 10-07 批次 2**（见 `prd.md` 决定 2）

**代码引用（1 文件）**
- `application/task/VideoTaskService.java` —— `:6` import、`:70` 字段、`:80` 构造参数

**注释 / 散文（6 处，编译器不管，最容易漏）**

| 文件 | 行 | 形态 |
|---|---|---|
| `application/task/VideoTaskService.java` | `:38` | `{@link VideoTaskPipeline#execute}` |
| `application/task/VideoTaskService.java` | `:174` | `{@link VideoTaskPipeline#translateTask}` |
| `application/task/package-info.java` | `:6` | `{@code VideoTaskPipeline}` |
| `config/TaskProperties.java` | `:11` | `{@code VideoTaskPipeline}` |
| `domain/media/model/MediaInput.java` | `:34` | `{@code VideoTaskPipeline}` |
| `domain/task/policy/TaskStageEvent.java` | `:23` | `{@code VideoTaskPipeline}` |
| `src/main/resources/application.yml` | `:40` | **YAML 注释里**（`VideoTaskPipeline 的 Semaphore 限流`） |

**不要误伤**：`VideoTaskService` / `VideoTask` / `VideoTaskRepository` / `VideoTaskMapper` / `TaskStageEvent` 一律不动（`VideoTask` 会出现 6 次，别用 `VideoTask` 当替换串——**只替换 `VideoTaskPipeline` 全词**）。

## 1.2 `SubtitlePreparationStage` → `SubtitlePreparationCoordinator`

**本体（1 文件）**
- `application/subtitle/pipeline/SubtitlePreparationStage.java` —— 文件名、`:52` 类声明、`:99` 构造器

**代码引用**

| 文件 | 行 |
|---|---|
| `application/task/pipeline/VideoTaskPipeline.java` | `:5` import、`:69` 字段类型、`:88` 构造参数 |
| `application/subtitle/pipeline/SubtitleCleanupStage.java` | `:41` `{@link SubtitlePreparationStage}` |
| `application/subtitle/pipeline/SubtitleCleanupStage.java` | `:202` `{@link SubtitlePreparationStage}` |
| `application/subtitle/pipeline/SubtitleTranslationStage.java` | `:51` `{@link SubtitlePreparationStage}` |

**散文**
- `application/task/pipeline/VideoTaskPipeline.java:34`（与批次 2 的「文字复述」重叠 → 见 `prd.md` Open Question 2）
- `application/subtitle/package-info.java:4`
- `domain/subtitle/policy/CheckpointPolicy.java:10`、`:18`（`:18` 点名的是**测试类** `SubtitlePreparationStageTest`）
- `shared/fs/ArtifactFiles.java:122`
- `shared/progress/ProgressMapper.java:10`（历史说明：`remap` 上提自本类）

## 1.3 `SubtitleCleanupStage` → `SubtitleCleanupCoordinator`

**本体（1 文件）**
- `application/subtitle/pipeline/SubtitleCleanupStage.java` —— 文件名、`:47` 类声明、`:78` 构造器

**代码引用（4 处）**
- `application/task/pipeline/VideoTaskPipeline.java` —— `:4` import、`:70` 字段、`:88` 构造参数
- `application/subtitle/pipeline/SubtitleTranslationStage.java:51` `{@link SubtitleCleanupStage}`

**散文**
- `application/subtitle/pipeline/SubtitleTranslationStage.java:204`（「见 SubtitleCleanupStage」）
- `application/task/pipeline/VideoTaskPipeline.java:36`
- `application/subtitle/package-info.java:5`
- `domain/subtitle/policy/CheckpointPolicy.java:10`、`:23`
- `shared/fs/ArtifactFiles.java:122`、`:125`

## 1.4 `SubtitleTranslationStage` → `SubtitleTranslationCoordinator`

**本体（1 文件）**
- `application/subtitle/pipeline/SubtitleTranslationStage.java` —— 文件名、`:59` 类声明、`:95` 构造器

**代码引用（5 处）**
- `application/task/pipeline/VideoTaskPipeline.java` —— `:6` import、`:71` 字段、`:89` 构造参数
- `domain/subtitle/model/term/TermLookupCommand.java:9` `{@code SubtitleTranslationStage#verifyTermsIfNeeded}`
- `domain/subtitle/policy/Bcp47Language.java:34`（本会话新补的 Javadoc：「见 SubtitleTranslationStage 的卫语句」）

**散文**
- `application/subtitle/package-info.java:6`
- `domain/subtitle/policy/CheckpointPolicy.java:10`、`:23`

## 1.5 变量名（可选，Open Question 3）

字段 / 构造参数小驼峰，跟不跟改由项目所有者定：
- `VideoTaskPipeline:69` `subtitlePreparationStage`、`:70` `cleanupStage`、`:71` `translationStage`
- `VideoTaskService:70` `pipeline`
- 构造参数见各构造器签名

---

# 步骤 2 — 测试改名（AI 做）

**三个测试类要连文件名一起改**：`SubtitlePreparationStageTest` / `SubtitleCleanupStageTest` / `SubtitleTranslationStageTest` → `*CoordinatorTest`；`VideoTaskPipelineTest` → `SubtitlePipelineTest`。

| 文件 | 命中数 | 说明 |
|---|---|---|
| `src/test/.../task/pipeline/VideoTaskPipelineTest.java` | **21** | 类名 `:60`、Javadoc `:51`、`service()` `:71` `:72`、约 17 处 `VideoTaskPipeline pipeline = service(...)` |
| `src/test/.../task/VideoTaskServiceTest.java` | **2** | `:56` mock |
| `src/test/.../subtitle/pipeline/SubtitlePreparationStageTest.java` | **10** | 类名、Javadoc、4 处 `new SubtitlePreparationStage(...)` |
| `src/test/.../subtitle/pipeline/SubtitleCleanupStageTest.java` | **17** | 类名、Javadoc、12 处 `SubtitleCleanupStage s = …` |
| `src/test/.../subtitle/pipeline/SubtitleTranslationStageTest.java` | **30** | 类名、Javadoc、约 27 处 `SubtitleTranslationStage s = service(...)` |

**散文里点名的测试类**（改测试类名时一起扫）：

| 文件 | 行 | 点的是 |
|---|---|---|
| `domain/subtitle/policy/CheckpointPolicy.java` | `:18` | `SubtitlePreparationStageTest` |
| `domain/subtitle/policy/CheckpointPolicyTest.java` | `:15` `:16` | 三个测试类 |
| `domain/subtitle/policy/Bcp47LanguageTest.java` | `:17` | `SubtitleTranslationStageTest` |
| `domain/subtitle/policy/TermVerificationTest.java` | `:21` | `SubtitleTranslationStageTest` |
| `shared/progress/ProgressMapperTest.java` | `:13` | `SubtitlePreparationStage` |

---

# 步骤 3 — spec 判据（改名落地时一起写）

`spec/backend/directory-structure.md` 现在那条是 2026-10-07 的**暂停标记**（原文：「管线构件后缀判据暂停中…新判据待定；定下之前不要往 `*Stage` 家族加新类」）。

改写为（正文见 `prd.md` 的「命名判据」节）：

> **`*er` / `*or` = 做事的人**（domain 端口，或端口的实现、分派）。**`*Coordinator` = 认领进度阶段、驱动若干协作者的那一步**。管线的所有者不带后缀，前缀说产出什么。

连带要改的点名处：`directory-structure.md:257`（stereotype 表格的示例列里写着 `VideoTaskPipeline` / `SubtitlePreparationStage`）、`:85`（「六个 `XxxStage`」）、`:71`（同上）、`:168`。

---

# 验证

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

断言：

```bash
# ① 四个旧名清零（main + test + spec）
grep -rn "VideoTaskPipeline\|SubtitlePreparationStage\|SubtitleCleanupStage\|SubtitleTranslationStage" \
  backend/src .trellis/spec          # 期望：无命中

# ② *Stage 家族只剩未纳入本任务的那三个
find backend/src/main -name '*Stage.java' | sed 's|.*/||' | sort
# 期望：AudioPreparationStage / MediaAcquisitionStage / TranscriptionStage（+ StageEvent）

# ③ 未误伤 VideoTask 全族
git diff --stat -- backend/src/main/java/com/vidsage/domain/task/VideoTask.java \
  backend/src/main/java/com/vidsage/application/task/VideoTaskService.java
# 期望：VideoTask.java 零改动；VideoTaskService.java 只有 import/字段/构造参数/两处 {@link} 的行
```

**最重要的一条：SSE 事件流逐条比对**

类名不影响事件内容，所以这条**理论上必过**——但它是最便宜的一道保险（防手工替换串写错）：

```bash
diff <(sed 's/"taskNo":"[^"]*"/"taskNo":"X"/' before.txt) \
     <(sed 's/"taskNo":"[^"]*"/"taskNo":"X"/' after.txt)
# 期望：除 taskNo 外零差异
```

`before.txt` / `after.txt` 的取法见 `10-07-shape-refactor/implement.md` 的批次 2 验证节（含起实例的两个坑：`TaskStop` 杀不掉子 JVM 要按 PID `taskkill`；Git Bash 的 `/tmp` 与 Windows 程序不通用）。

**回滚点**：`git reset --hard` 到本任务开工前的提交。纯改名，不碰 HTTP 契约与数据库，回滚零副作用。
