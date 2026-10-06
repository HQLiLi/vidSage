# 执行清单：命名与构件语义整理

> 设计见 `design.md`；需求与决策见 `prd.md`（含 2026-10-06 对 R2 的追加修订：`TaskPipelineService` → `VideoTaskPipeline`）。
> **门禁**：本清单跑完「停下汇报」即止 —— 提交 / 合并 / 归档逐门等用户批准，不得在同一次执行里连做（见 `.trellis/spec/guides/git-workflow.md`）。

## 开工基线（2026-10-06，dev @ 9a31912 实测）

```
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn clean test -q
→ 45 个测试类 / 465 用例 / errors=0 / failures=0 / skipped=1
```

**AC2 的比数基准：465 用例、0 failures。** 类名会变，只比数量。

---

## 步骤 0 · 前置

- [x] 0.1 确认在 `dev` 且工作区干净（除本任务目录未跟踪）—— 已确认。
- [x] 0.2 PRD 追加修订已落（R2 的 `VideoTaskPipelineService` 加删除线 + 修订说明；R3 加同判据说明）。
- [ ] 0.3 `git checkout -b feature/naming-alignment`（从当前 `dev` 开；R6 要求基于最新 dev）。
- [ ] 0.4 记录开工前 `git status --porcelain` 输出备查。

## 步骤 1 · 代码改名（主代码）

- [ ] 1.1 **文件级改名（`git mv`，保历史）**
  - `application/task/TaskPipelineService.java` → `VideoTaskPipeline.java`
  - `application/task/TaskService.java` → `VideoTaskService.java`
  - `application/subtitle/SubtitleService.java` → `SubtitlePreparationStage.java`
  - `application/subtitle/SubtitleCleanupService.java` → `SubtitleCleanupStage.java`
  - `application/subtitle/SubtitleTranslationService.java` → `SubtitleTranslationStage.java`
  - `application/media/AudioPreparationService.java` → `AudioPreparationStage.java`
  - `application/media/MediaAcquisitionService.java` → `MediaAcquisitionStage.java`
  - `application/transcription/TranscriptionService.java` → `TranscriptionStage.java`

- [ ] 1.2 **符号替换（`backend/src/main/`）**

  | # | 旧 | 新 |
  |---|---|---|
  | 1 | `SubtitleTranslationService` | `SubtitleTranslationStage` |
  | 2 | `SubtitleCleanupService` | `SubtitleCleanupStage` |
  | 3 | `SubtitleService` | `SubtitlePreparationStage` |
  | 4 | `AudioPreparationService` | `AudioPreparationStage` |
  | 5 | `MediaAcquisitionService` | `MediaAcquisitionStage` |
  | 6 | `TranscriptionService` | `TranscriptionStage` |
  | 7 | `TaskPipelineService` | `VideoTaskPipeline` |
  | 8 | `TaskService` | `VideoTaskService` |

  > 已核对：`SubtitleService` **不是** `SubtitleCleanupService` 的子串，`TaskService` **不是** `TaskPipelineService` 的子串（类名都是「词根 + 词根 + Service」，不是「短类名 + 后缀」）。故 7 与 8 之间**顺序无关**，无需词边界。1–3 仍按长名先排是保险起见。

- [ ] 1.3 **方法名（只在 `application/task/VideoTaskPipeline.java`）** —— 按 R1 原样落地，**不回调**
  - `void run(TaskNo taskNo)` → `void runPipeline(TaskNo taskNo)`
  - `private void runStages(VideoTask task)` → `private void prepareAndClean(VideoTask task)`
  - `private void runTranslation(VideoTask task, MediaInput input)` → `private void runTranslationPipeline(VideoTask task, MediaInput input)`
  - 内部调用点：`executor.execute(() -> run(taskNo))` → `runPipeline`；`runStages(task)` → `prepareAndClean(task)`；`executor.execute(() -> runTranslation(task, input))` → `runTranslationPipeline`
  - **不动**：`execute` / `translateTask`

- [ ] 1.4 **字段名（7 个）**
  - `VideoTaskPipeline`：`subtitleService`→`subtitlePreparationStage`、`cleanupService`→`cleanupStage`、`translationService`→`translationStage`
  - `SubtitlePreparationStage`：`audioPreparationService`→`audioPreparationStage`、`transcriptionService`→`transcriptionStage`
  - `AudioPreparationStage`：`acquisitionService`→`acquisitionStage`
  - `VideoTaskService`：`pipelineService`→`pipeline`（含 `@param pipelineService` 的 Javadoc、构造器形参、赋值、两处调用）
  - **不动**：`TaskController.taskService`、各 Stage 里的 `workspaceService` / `repository` 等。

- [ ] 1.5 **stereotype（7 个 Stage/Pipeline 类）**：`import org.springframework.stereotype.Service;` → `Component`，注解 `@Service` → `@Component`。改动文件：`VideoTaskPipeline` + 六个 `XxxStage`。
  - **不动**：`VideoTaskService` / `WorkspaceService` 保持 `@Service`。

- [ ] 1.6 **Javadoc / 注释交叉引用**（`design.md` §2.6 清单逐条过）

- [ ] 1.7 **package-info（4 个）**：`application/task/`（「`TaskPipelineService`（异步阶段编排）」→「`VideoTaskPipeline`（异步管线本体）」）、`application/subtitle/`、`application/media/`、`application/transcription/`。

## 步骤 2 · 代码改名（测试）

- [ ] 2.1 **文件级改名**
  - `application/task/TaskPipelineServiceTest.java` → `VideoTaskPipelineTest.java`
  - `application/task/TaskServiceTest.java` → `VideoTaskServiceTest.java`
  - `application/subtitle/SubtitleServiceTest.java` → `SubtitlePreparationStageTest.java`
  - `application/subtitle/SubtitleCleanupServiceTest.java` → `SubtitleCleanupStageTest.java`
  - `application/subtitle/SubtitleTranslationServiceTest.java` → `SubtitleTranslationStageTest.java`
  - `application/media/AudioPreparationServiceTest.java` → `AudioPreparationStageTest.java`
  - `application/media/MediaAcquisitionServiceTest.java` → `MediaAcquisitionStageTest.java`
  - （`TranscriptionService` 无测试类）

- [ ] 2.2 **符号替换（`backend/src/test/`）** —— 同 §1.2 的表。**测试类名会被同一条替换自动覆盖**（`TaskPipelineServiceTest` → `VideoTaskPipelineTest`、`TaskServiceTest` → `VideoTaskServiceTest`，因为模式不含末尾的 `Test`），无需单列规则。

- [ ] 2.3 **测试内的 mock 字段名**
  - `VideoTaskPipelineTest`：`subtitleService`/`cleanupService`/`translationService` → `subtitlePreparationStage`/`cleanupStage`/`translationStage`
  - `VideoTaskServiceTest`：`pipelineService` → `pipeline`（含 9 处 `verify(...)`）
  - **不动**：`SubtitlePreparationStageTest` 的局部变量 `audioPrep` / `transcribe`、`AudioPreparationStageTest` 的 `service()` 工厂方法。

- [ ] 2.4 **测试 Javadoc / 注释里的类名引用**（`design.md` §2.6 末尾几条）。

## 步骤 3 · 验证门禁 ①（代码）

- [ ] 3.1 `cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn clean test -q`
  - **必须 `clean`**：改名 = 删旧文件 + 建新文件，`target/classes/` 与 `surefire-reports/` 都会留旧物（两个坑都实测过）。
- [ ] 3.2 读 surefire 汇总，断言 **45 个类 / 465 用例 / failures=0**（`design.md` §5）。
- [ ] 3.3 若数字不符：先查是不是残留报告（`ls -la --time-style=+%H:%M:%S target/surefire-reports/` 看时间戳），再查是不是真失败。
- [ ] 3.4 `git diff --stat` 过一眼：应只有 `.java` 文件的改名 + 名字行改动。

## 步骤 4 · spec 对账

- [ ] 4.1 `directory-structure.md`：按 `design.md` §4.1 的表逐行改，**含新增「构件说明」与 stereotype 四档判据**（R5 + AC4）。
- [ ] 4.2 `error-handling.md`：`TaskService.loadTask` → `VideoTaskService.loadTask`。
- [ ] 4.3 `logging-guidelines.md`：两处 `TranscriptionService` → `TranscriptionStage`（含文件路径）。
- [ ] 4.4 扫一遍其余 spec 文件（`provider-pattern.md` / `quality-guidelines.md` / `index.md` / `database-guidelines.md` / `guides/*`）确认没有漏网引用 —— 侦察阶段已 grep 过，预期为零，此处是复核。

## 步骤 5 · 删除失实文档

- [ ] 5.1 `git rm docs/ddd-refactor-handoff.md`（Q3 决策：已 100% 执行完，头部状态失实，三处结论被 10-06 端口倒置推翻）。

## 步骤 6 · 验证门禁 ②（AC1 grep 复查）

- [ ] 6.1 ```bash
  grep -rn "TaskPipelineService\|\bTaskService\b\|SubtitleCleanupService\|SubtitleTranslationService\|\bSubtitleService\b\|AudioPreparationService\|MediaAcquisitionService\|TranscriptionService" backend/src .trellis/spec
  ```
  期望：**空**。
- [ ] 6.2 **方法名（2026-10-06 修正后的门禁 —— 原写法有恒真盲区，见下）**
  ```bash
  # 正向断言：新名必须在
  grep -rn "runPipeline\|prepareAndClean\|runTranslationPipeline" backend/src
  # 反向断言：旧名必须空（不带点前缀！）
  grep -rnE "\brunStages\b|\brunTranslation\b" backend/src .trellis/spec
  ```
  > **原写法 `grep "\.runStages(\|\.runTranslation("` 是恒真的**：三个方法的调用点除测试外**全是类内非限定调用**（`runStages(task);` —— 名字前面没有点），且 `run` → `runPipeline` 压根不在模式里。于是门禁永远返回空、永远「通过」，R1 整个漏做却报了 AC1 ✅（2026-10-06 实测踩中，由 `trellis-check` 复核抓出）。
  >
  > **教训**：改名的门禁不能只做「反向 grep 旧名」，因为(a)限定调用与非限定调用的写法不同、模式容易只覆盖一种；(b)漏改的表现是「新名 0 命中」而非「旧名有残留」。**必须同时做正向断言（新名命中数 > 0）**——正向断言对漏改免疫。
- [ ] 6.3 确认预期命中项都在排除清单内（`.trellis/tasks/archive/**`、`.trellis/workspace/**`、`.zcode/plans/**`、本任务 `prd.md`/`design.md`/`implement.md`）。
- [ ] 6.4 `mvn clean test` 再跑一次（spec 不影响构建，此处是终态确认，同时拿到最终数字）。

## 步骤 7 · 停下汇报（门禁）

- [ ] 7.1 汇报：改了哪些文件、测试数字对照基线、AC1–AC4 逐条自评、`git diff` 的关键片段。
- [ ] 7.2 **停住等用户审查**。审查期间用户提修改 → 原地改 → 重新验证 → 再停。
- [ ] 7.3 用户批准后才 `git add` + 提交，按 `design.md` §3-5 切两个 commit：
  - `refactor(backend): 命名对齐——服务前缀对齐聚合根、管线去 Service 后缀、阶段类改 XxxStage`
  - `docs(spec): 对账管线构件与聚合根前缀命名，删除已执行完的 DDD 移交文档`
- [ ] 7.4 用户批准后才 `git push -u origin feature/naming-alignment`，再批准才合并回 `dev`。
- [ ] 7.5 **不自行归档** —— 等用户跑 `/trellis:finish-work` 或明说「归档」。

## 验收对照（AC1–AC4）

| # | 判据 | 落在哪一步 |
|---|---|---|
| AC1 | R1–R3 名字全部落地，grep 无旧名残留 | 步骤 6.1 / 6.2 |
| AC2 | 全量测试通过，用例数与基线一致（465） | 步骤 3.2 / 6.4 |
| AC3 | 零行为变化：diff 无方法体 / 日志 / 异常 / SQL / JSON 改动 | 步骤 3.4 / 7.1 |
| AC4 | `directory-structure.md` 写明 `XxxStage` 与 `XxxService` 的区别 + 服务前缀约定 | 步骤 4.1 |
