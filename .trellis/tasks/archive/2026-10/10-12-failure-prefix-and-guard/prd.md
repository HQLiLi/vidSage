# 失败前缀常量化 + 后置生命周期守卫改名

> 出自 `10-12-comment-domain-pilot` 的审查（2026-10-12），是注释收口时**发现的、但按「只改注释」
> 的边界没动的**两笔债。**纯代码改动，行为必须逐字等价。**
>
> 依赖：等 `10-12-comment-consolidation` 的 domain 试点合并、归档后开工——两笔都改到试点动过的文件。
> **2026-10-13：该依赖已满足**——`10-12-comment-consolidation` 与 `10-12-comment-domain-pilot`
> 均已在 `archive/2026-10/`（`8734aeb` 归档、`0f0a173` 合并进 dev）。
>
> **2026-10-13 事实核查订正**：本 prd 初稿的行号与计数取自注释收口**前**的快照，与现状不符（偏差见
> 各处 ~~删除线~~ 标注）。已订正，并补入两处范围决定与一条反向验收门。

## Goal

消掉两处「命名 / 口径与事实不符」的债：

### 1. 三处失败前缀口径不齐

| 前缀 | 现在长什么样 |
|---|---|
| `重新生成失败：` | ✅ 常量 `VideoTask.REGENERATE_FAILURE_PREFIX`（`VideoTask.java:281`），且 `TaskProgressNotifier:98` 真在用 |
| `翻译失败：` | ❌ ~~`SubtitlePipeline:335`~~ **`SubtitlePipeline:323`** 里的硬编码字面量 |
| `任务执行失败：` | ❌ ~~`SubtitlePipeline:314`~~ **`SubtitlePipeline:302`** 里的硬编码字面量，**连常量都没有** |

> 行号订正说明（2026-10-13）：初稿的两个行号来自注释收口前的版本，两个差 12 行——收口给
> `failTask` / `failTranslation` 补 Javadoc 时把代码整体后移了 12 行（`:294-298`、`:314-321`）。
> 真实字面量在 `:302`（`"任务执行失败：" + reason`）与 `:323`（`"翻译失败：" + reason`）。

**后果**：`VideoTask.errorMessage` 的注释现在只能写「各自带前缀区分」——写不了「见 X 常量」，
因为写了就是假话。而且光加常量不改生产者**会让「下次改前缀会漏」更容易发生**：
常量看着像权威单源，实际 `SubtitlePipeline` 仍是另一处真相。

### 2. 守卫命名与作用域不符

`VideoTask.requireMainSucceededForTranslation` 的名字只说「翻译」，实际也被
`recordRegenerateFailure`（重新生成）在用——它真正守的是「主状态 SUCCEEDED 才能进第二生命周期」。

> 位置核对（2026-10-13）：声明在 `VideoTask.java:320`，调用在 `:219`（启动翻译）、`:234`（收敛翻译为
> SUCCEEDED）、`:251`（翻译标记失败）、`:271`（记录重新生成失败）。

## 范围

**做**

- `VideoTask` 增两个常量：`TASK_FAILURE_PREFIX = "任务执行失败："`、`TRANSLATION_FAILURE_PREFIX = "翻译失败："`；
- **常量聚簇**（2026-10-13 决定）：两个新常量与既有 `REGENERATE_FAILURE_PREFIX` 摆在一处
  （即 `REGENERATE_FAILURE_PREFIX` 现在的位置，`:281` 区块），三个前缀一处看全，以后加第四个不会散；
- `SubtitlePipeline` 的两处字面量改为引用常量（`:302`、`:323`）；
- `requireMainSucceededForTranslation` → `requireMainSucceededForPostLifecycle`（1 处声明 + 4 处调用）；
- 改完把注释里的前缀引用补/改成指常量（2026-10-13 决定的精确清单）：
  - `VideoTask` — `errorMessage` 字段注释（`:82-83`）、`fail` 的 `@param`（`:188`，**新增条目**）、
    `translationFail` 的 Javadoc 正文与 `@param`（`:244`、`:247`）、`clearRegenerateFailure`
    的 Javadoc（`:285`）；
  - `SubtitlePipeline:315` 的 Javadoc（「错误信息带「翻译失败：」前缀」）；
  - `TranslationStatus:24` 的枚举常量注释。

**不做**

- **不动测试**。~~5 处断言用的是字面量~~ —— 2026-10-13 订正：实际是 **5 个测试文件、约 20 处断言点**
  用了**全角前缀字面量**（`SubtitlePipelineTest` 8 处、`VideoTaskTest` 2 处、`VideoTaskServiceTest` 4 处、
  `MybatisVideoTaskRepositoryTest` 2 处、`TaskControllerTest` 2 处）。它们断言的是**最终文案**，
  用字面量更贴合断言意图，也更能在常量被误改时报警。
  （附注：`VideoTaskTest:298` 与 `SubtitlePipelineTest:519` 已经是拿 `REGENERATE_FAILURE_PREFIX`
  组装入参的——**「测试里零命中字面量」不成立，也不能当验收门**。）
- **不动半角冒号的日志格式串**。`SubtitlePipeline` 里另有一批形近但**不是**用户可见前缀的日志串，
  一律逐字保留：`"任务执行失败: ..."`(1 处)、`"任务翻译失败: ..."`(2 处)、
  `"任务失败状态落库失败..."`(1 处)、`"翻译失败状态落库失败..."`(1 处)。**它们是半角 `:`**，
  与本次改的全角 `：` 字面量是两回事；朴素全文替换会误伤日志格式（= 行为改变）。
- **不动 `schema.sql:24` 的 DDL 注释**（选它）——那是数据库那侧的权威，另一套东西；
- **不动归档任务文档与 journal 里的历史记录**（它们提到旧常量名/旧守卫名是史实，改了才是造假）；
- 不做任何其他重构；不顺手改别的命名。

## 验收标准

- [ ] **行为逐字等价**：`mvn clean test` 全绿，**测试文件一行未改**（`git diff --name-only -- backend/src/test`
      为空）；三个前缀的字符串值不变。
- [ ] `SubtitlePipeline.java` 里不再出现**全角冒号的 Java 字符串字面量** `"任务执行失败："` 与 `"翻译失败："`
      （按含双引号的精确串 grep，零命中）。
- [ ] **反向门（新增）**：`SubtitlePipeline.java` 里**半角冒号**的 5 条日志格式串一个不少、逐字未变
      （见「不做」第二条清单）。
- [ ] 三个常量都带 Javadoc（spec：全大写常量一律留注释），且 `TASK_FAILURE_PREFIX` / `TRANSLATION_FAILURE_PREFIX`
      在 `backend/src/main/` 下**各至少被 1 处代码引用**（生产者 `SubtitlePipeline`）——
      防止「只加常量不接生产者」这种假单源。
- [ ] `requireMainSucceededForTranslation` 在 **`backend/src/` 下零命中**（2026-10-13 订正：初稿写的
      「全仓零命中」不成立——`archive/2026-10/10-12-comment-domain-pilot/implement.md` 有两处史实引用，
      不该动）；`requireMainSucceededForPostLifecycle` 命中 5 处（1 声明 + 4 调用）。
- [ ] `git diff --name-only` 只含三个文件：`domain/task/VideoTask.java`、
      `application/task/pipeline/SubtitlePipeline.java`、`domain/task/model/TranslationStatus.java`。

## 为什么单开

三处前缀 + 一处命名，跨 `domain` 与 `application` 两层。单开一笔好回滚、好 review——
混进那个 46 文件的注释 diff 里，reviewer 分不清哪行是注释、哪行是代码。

## 附：2026-10-13 的两处范围决定

| # | 问题 | 决定 | 代价（已接受 / 可回退） |
|---|---|---|---|
| 1 | 三个常量怎么摆 | **聚成一簇**（留在 `REGENERATE_FAILURE_PREFIX` 现位） | `fail` 与 `translationFail` 旁边不再就近有常量；靠字段注释与各方法 Javadoc 互相指认 |
| 2 | 注释散文里硬编码的「翻译失败：」要不要收到常量上 | **Java 注释都动**：`SubtitlePipeline:315` + `TranslationStatus:24` 改引常量；`schema.sql:24` 不动 | `TranslationStatus:24` 的全限定 `{@link}` 会让 `domain/task/model` 在 **Javadoc 层**反向指到 `domain/task`（不加 import）。若 review 认为不值，删这一条即可，不影响其余改动 |
