# SubtitlePipeline 三条 run 流程拆分 — 技术设计

> 范围与验收见 [prd.md](./prd.md)；执行顺序与验证命令见 [implement.md](./implement.md)。
> **本文件是执行参考稿，不是契约**：与代码冲突时以代码为准并回来改这里。

---

## 1. 目标形状

```
application/task/
  SubtitleMainService.java        @Service     MAIN 的完整流程（提交 + 执行）
  SubtitleRecleanService.java     @Service     RECLEAN 的完整流程
  SubtitleTranslateService.java   @Service     TRANSLATE 的完整流程
  SubtitleTaskFinder.java         @Component   （不动）
  TaskRunSupervisor.java          @Component   （不动）
  SubtitleTaskQueryService.java   @Service     （不动，读路径）
  port/TaskConcurrencyGate.java               （不动）

application/subtitle/pipeline/
  SubtitlePreparationCoordinator.java  @Component  （不动）
  SubtitleCleanupCoordinator.java      @Component  （不动）
  SubtitleTranslationCoordinator.java  @Component  （不动）
  SubtitleFingerprintCoordinator.java  @Component  （不动）
  ✗ SubtitlePipeline.java                          删除

application/task/
  ✗ SubtitleTaskService.java                       删除

web/controller/
  TaskController.java             注入从 1 个用例服务变 3 个
```

**每个类收自己那条 run 的同步守卫段与异步执行段**，连骨架各写一份。三个类之间不互相引用，也不引用对方才需要的协作者。

## 2. 三个类的公开面与依赖账

### 2.1 公开面（web 调用面）

```java
@Service
public class SubtitleMainService {
    public TaskRunRef submitUrl(String url, List<String> keywords);
    public TaskRunRef submitUpload(InputStream content, String originalFileName, List<String> keywords);
}

@Service
public class SubtitleRecleanService {
    public TaskRunRef regenerate(String taskNo, List<String> keywords);
}

@Service
public class SubtitleTranslateService {
    public TaskRunRef translate(String taskNo, String sourceLanguage, String targetLanguage);
}
```

签名与今天的 `SubtitleTaskService` **逐字相同** —— 这是 AC4（HTTP 面逐字节不变）的结构保证：`TaskController` 只换注入目标与调用对象，方法体一行不改。

**三个类的内部形状也一致**（2026-10-21 审查轮定）：公开方法只做到「守卫 + 抢互斥 + 落库 + 派发」，执行体一律抽成 `runPipeline(...)`，由 `concurrencyGate.submit(() -> runPipeline(...), 中断回调)` 派发。三边的差异只在 `runPipeline` 的参数——MAIN 不带上下文（从 `Submission` 里取）、RECLEAN 带原始产物与生效关键词、TRANSLATE 带两个语种与清洗产物。

**可见性按调用点定，不按对称性定**（2026-10-21 审查订正）：

| 类 | 修饰符 | 为什么 |
|---|---|---|
| `SubtitleMainService.runPipeline` | 包内可见 | `SubtitleMainServiceTest` 有 13 个执行段用例**直接调它**绕开虚拟线程的时序（与拆分前 `SubtitlePipeline.runPipeline` 的角色相同） |
| `SubtitleRecleanService.runPipeline` | `private` | 只有自己 `submit` 的 lambda 在调；测试走公开签名 + `inlineGate()`，顺带把守卫段也跑到 |
| `SubtitleTranslateService.runPipeline` | `private` | 同上 |

**教训**：为「三个类长得一样」而放宽可见性，等于凭空多出一个包内 API 面。修饰符要跟着真实调用点走。

### 2.2 依赖账

| Service | 注入 | 个数 |
|---|---|---|
| `SubtitleMainService` | `WorkspaceRepository`、`SubtitleTaskRepository`、`SubtitleKeywordRepository`、`MediaIdentityResolver`、`TaskRunSupervisor`、`TaskConcurrencyGate`、`SubtitlePreparationCoordinator`、`SubtitleCleanupCoordinator`、`SubtitleArtifactStore`、`SubtitleFingerprintCoordinator`、`WorkspaceDirectoryManager`、`PlatformTransactionManager` | **12** |
| `SubtitleRecleanService` | `SubtitleTaskFinder`、`SubtitleKeywordRepository`、`TaskRunSupervisor`、`TaskConcurrencyGate`、`SubtitleCleanupCoordinator`、`SubtitleArtifactStore`、`WorkspaceDirectoryManager` | **7** |
| `SubtitleTranslateService` | `SubtitleTaskFinder`、`TaskRunSupervisor`、`TaskConcurrencyGate`、`SubtitleTranslationCoordinator`、`SubtitleArtifactStore`、`WorkspaceDirectoryManager` | **6** |

**合计 25，对比拆分前的 19**（`SubtitleTaskService` 10 个真依赖 + `SubtitlePipeline` 9 个）。**+6 是三条流程彻底分家的价格**，不是失误：分家就意味着不再共享字段。换来的是每个类的依赖都是它真用的那几个，且没有一个类同时认识三条 run。

`SubtitleMainService` 的 12 个是本任务留下的最大单点，记账（prd §5 风险表）。

> **2026-10-21 审查轮**：三个服务各去掉了 `TaskRunRepository`——它唯一的用途是 `conflictFor`，而那个方法已经收进 `TaskRunSupervisor.buildTaskConflictException(...)`（supervisor 本来就持有 `TaskRunRepository`）。合计由 28 降到 25。

### 2.3 私有 helper 的重复清单

「不抽共用」的直接后果，**有意为之**，逐条登记以便日后判断该不该抽。**2026-10-21 审查轮已收掉四件**，右列是现状：

| helper | 来源 | 现在在哪 | 份数 |
|---|---|---|---|
| `conflictFor(SubtitleTask)` | `SubtitleTaskService` | **`TaskRunSupervisor.buildTaskConflictException(...)`** | **1**（单源） |
| `requireIntactArtifact(...)` | `SubtitleTaskService` | RECLEAN（要 ORIGINAL）、TRANSLATE（要 CLEANED）各一份 | 2 |
| `workDir(Workspace)` | `SubtitlePipeline` | **删除**——调用点就是 `workspaceDirectoryManager.ensureWorkspaceDirectory(...)` 一行 | **0** |
| `toMediaInput(Workspace, Path)` | `SubtitlePipeline` | **`shared/media/MediaInputUtils.from(...)`** | **1**（单源） |
| `progressListener(RunRef, TaskRun)` | `SubtitlePipeline` | **`TaskRunSupervisor.listener(ref, run)`** | **1**（单源） |
| `stackOf(Throwable)` | `SubtitlePipeline` | **`shared/error/ThrowableUtils.stackOf(...)`** | **1**（单源） |
| `reason(Throwable)` | `SubtitlePipeline` | **`TaskRunSupervisor.fail(ref, run, Throwable)` 内部** | **1**（单源） |

`findOrCreateWorkspace` / `findOrCreateTask` / `streamToTemp` / `settleUpload` / `Submission`（record）只服务 MAIN，**原样搬进 `SubtitleMainService`，不成份**。

**剩下唯一的多份 helper 是 `requireIntactArtifact`（×2）**——两份只有 `ArtifactType` 与描述文案不同。没有收上去，因为它的组合（`finder.workspaceDir` + `artifactStore.findIntactArtifact` + 一句 400 文案）横跨两个协作件，收进任何一个都得给那个类加依赖，或把组合拆散。

## 3. 为什么是 `@Service`（判据与出处）

`.trellis/spec/backend/directory-structure.md` 的「构件说明」表写死了判据：

| 档 | stereotype | 判据 |
|---|---|---|
| 用例服务 | `@Service` | **有 web 调用面**，或被多处方共用的横切编排能力 |
| 管线构件 | `@Component` | **没有 web 调用面**，只被上级管线按顺序调用 |
| 机制/协作件 | `@Component` | 不干编排的机械件 |

三个新类被 `TaskController` **直接注入** → 有 web 调用面 → 用例服务 → `@Service` + `XxxService` 命名。

这条判据不是本任务新造的：spec 第 108 行记着它的来历——2026-10-06 那次命名对齐的理由是「旧口径下这两档都叫 `XxxService`，但全项目只有 `SubtitleTaskService` 有 web 调用面……名字在撒谎，故按『有没有 web 调用面』重划，并让 stereotype 跟着判据走」。本任务是那条判据的**正向应用**：现在有三个类有 web 调用面，所以有三个 `@Service`。

**副产品**：不需要登记新角色后缀。`-Service` 是既有档位名；若当初选「保留 `SubtitleTaskService` 做转调 + 三个类叫 `Flow`」，就得按 spec 第 302 行「新角色后缀用前先登记」在 spec 里新登记 `-Flow`，还要回答「`-Flow` 与 `-Pipeline` 的区别是什么」。

## 4. 搬迁映射（旧 → 新）

| 旧位置 | 新位置 | 备注 |
|---|---|---|
| `SubtitleTaskService.submitUrl` | `SubtitleMainService.submitUrl` | 方法体不变 |
| `SubtitleTaskService.submitUpload` | `SubtitleMainService.submitUpload` | 方法体不变 |
| `SubtitleTaskService.handleSubmit`（私有） | `SubtitleMainService.handleSubmit`（私有） | 尾部由 `pipeline.run(...)` 改为直接 `concurrencyGate.submit(...)` |
| `SubtitleTaskService.findOrCreateWorkspace`（私有） | `SubtitleMainService`（私有） | 原样 |
| `SubtitleTaskService.findOrCreateTask`（私有） | `SubtitleMainService`（私有） | 原样 |
| `SubtitleTaskService.streamToTemp`（私有） | `SubtitleMainService`（私有） | 原样 |
| `SubtitleTaskService.settleUpload`（私有） | `SubtitleMainService`（私有） | 原样 |
| `SubtitleTaskService.Submission`（私有 record） | `SubtitleMainService`（私有 record） | 原样 |
| `SubtitleTaskService.regenerate` | `SubtitleRecleanService.regenerate` | 公开签名不变；内部直连执行段 |
| `SubtitleTaskService.translate` | `SubtitleTranslateService.translate` | 公开签名不变；内部直连执行段 |
| `SubtitleTaskService.conflictFor`（私有） | `TaskRunSupervisor.buildTaskConflictException(...)` | 单源，见 §2.3 |
| `SubtitleTaskService.requireIntactArtifact`（私有） | RECLEAN / TRANSLATE 各一份 | 见 §2.3 |
| `SubtitlePipeline.run`（闸门外壳） | 并入 `SubtitleMainService.handleSubmit` 尾部 | 中断文案随 MAIN 走 |
| `SubtitlePipeline.regenerate`（含闸门外壳） | `SubtitleRecleanService` 私有执行段 | 中断文案随 RECLEAN 走 |
| `SubtitlePipeline.translate`（含闸门外壳） | `SubtitleTranslateService` 私有执行段 | 中断文案随 TRANSLATE 走 |
| `SubtitlePipeline.runPipeline` | `SubtitleMainService`（私有） | 原样 |
| `SubtitlePipeline.workDir` / `progressListener` / `stackOf` / `reason` | 三个类各一份 | 见 §2.3 |
| `SubtitlePipeline.toMediaInput` | `shared/media/MediaInputUtils.from(...)` | 单源，见 §5 T7 |
| `SubtitlePipeline.STAGE_DONE` | 只 `SubtitleTranslateService` 一份 | MAIN / RECLEAN 不用 |

**中断文案与失败日志前缀随流程走**（这正是 prd §1 代价 3 要消灭的散落）：

| run | 中断文案 | 失败日志前缀 |
|---|---|---|
| MAIN | 「任务在等待执行配额时被中断：请重新提交」 | 「任务执行失败」 |
| RECLEAN | 「重新生成在等待执行配额时被中断：请重新触发」 | 「字幕重新生成失败」 |
| TRANSLATE | 「翻译在等待执行配额时被中断：请重新触发翻译」 | 「翻译失败」 |

**这三个字符串一字不改** —— 它们经 `run.error_message` 对外可见（AC4）。

## 5. 技术取舍

### T1 不抽共用骨架（用户口径，2026-10-21）

**取**：三个类各写一份「`concurrencyGate.submit` + 中断回调 + `try` / `catch` + `supervisor.finish`」骨架，各写一份四个 helper（`toMediaInput` 后来抽到了 `shared/media/MediaInputUtils`，见 T7）。

**舍**：不新建骨架组件、不用抽象基类、不把骨架塞进 `TaskRunSupervisor`。

**理由**：先让三个类各自完整地存在、跑起来，看清真实的缝在哪，再决定抽不抽。先验地猜「哪些是共享的」容易猜错——今天 `SubtitlePipeline` 里那份「共享」，实际是三条流程被硬塞进一个类的产物，不是真的共享。

**代价**（登记，日后判断该不该抽时看这里）：

- 骨架 3 份、`requireIntactArtifact` 2 份（其余 helper 已在审查轮收掉，见 §2.3）；
- 依赖总数 19 → 28；
- 改一处骨架要改三处。

**后续抽离的第一顺位候选**（按收益排）：`stackOf` / `reason`（纯静态函数，零状态）→ `workDir`（纯函数，只依赖 `Workspace` 与 `WorkspaceDirectoryManager`）→ 闸门 + 收口骨架（依赖 `TaskConcurrencyGate` + `TaskRunSupervisor`，是三者唯一的共同状态）。**本任务一律不做**——`toMediaInput` 已在审查轮破例抽出，见 T7。

### T2 包落 `application/task/`（原地替换）

三个新类落在**被删掉的 `SubtitleTaskService` 所在的包**，不新开 `flow/` 包、不挪去 `application/subtitle/`。

**理由**：它们承接的是同一个角色（任务写侧的用例服务），依赖的是同一批东西（`SubtitleTaskFinder` / `TaskRunSupervisor` / `WorkspaceRepository` / `SubtitleTaskRepository`），调用的是同一个 web 入口。原地替换让 diff 最小、import 变动最少。

**代价**：`SubtitleMainService` 会 import `application/subtitle/pipeline/` 下的三个协调器与 `application/subtitle/SubtitleArtifactStore` —— 跨二级包，但这在今天是既成事实（`SubtitleTaskService` 已经 import `SubtitlePipeline`），本任务不改变这一点。

### T3 重清洗语义定稿：RECLEAN 恒真重跑 + 无条件作废下游译文

**终稿语义**（10-20 挂账、本任务定稿）：

> RECLEAN 是用户显式触发的「重新生成字幕」，语义上就是**真重跑**：不看复用判据、不比对产出，一律落一版新 CLEANED，并**无条件作废**该任务下全部译文产物（`settleCleanedAndSupersedeDownstream`）。产出与旧版逐字节相同也照样作废。

**为什么「内容没变就不作废」那条判据退场**（10-20 design §3.4 的论证，本任务沿用）：在新模型下「内容没变」这一格**由上游复用门禁接管**——MAIN 的 CLEANED 门禁命中时整条链零写入，根本走不到作废这一步；能走到作废的，按定义就是真重跑。判据留着反而与门禁重复，且要读旧行哈希才能判，是给两条独立门禁加耦合。

**行为一行不改**，本任务只把这条写进文档定稿。现有单测即钉法：`SubtitleCleanupCoordinatorTest` 里「旧行哈希与新版完全相同 → 下游仍被作废」那条（AC6 证据）。

### T4 三个私有 helper 各写各的，不抽静态工具类

`stackOf` / `reason` 是纯函数，理论上可以抽 `shared/` 下的静态工具。**本任务不抽**，理由同 T1：它们现在各只有 3 个调用点，抽出去要回答「放哪个包、叫什么名」两个问题，而这两个问题的答案取决于抽离后的形状，不取决于现在。等三个类跑起来再看。（`toMediaInput` 已在审查轮抽出，见 T7。）

### T5 包名 `pipeline/` 不动，只订正 spec 描述（2026-10-21 裁定）

**取**：`application/subtitle/pipeline/` 保持原名，只改 spec 里那两句失实描述。

**理由**：`pipeline/` 在 spec 第 82 行登记的是**按角色落位的子包**，对应「管线构件」这一档（第 91 行列出成员：`XxxPipeline` / `XxxCoordinator` / `MediaAcquirerDispatcher` / `Transcriber`），**不是「叫 Pipeline 的类」**。全仓三个 `pipeline/` 包形状本就一致（`media/pipeline/`、`transcription/pipeline/`、`subtitle/pipeline/`），且只有 subtitle 这个曾经装过「管线的所有者」；删掉 `SubtitlePipeline` 之后它是变得**与另外两个一致**，不是不一致。

**改名候选都不成立**：8 个成员里 5 个认领阶段事件、3 个不认领（`ContentFingerprintProbeDispatcher` / `MediaAcquirerDispatcher` / `Transcriber` 一次 `emit` 都没有），所以 `stage/` 与 `coordinator/` 都盖不住全部成员——而 `pipeline` 盖得住。

**失实的是描述，不是名字**：spec 第 213 行「管线构件：管线的所有者与各阶段类」、第 468 行「`application/subtitle/pipeline/`（编排件与被编排件同包）」——这两处的「所有者 / 编排件」在拆分后消失，归 AC7 订正。

### T6 清洗意图从布尔代理变成显式枚举（2026-10-21 审查轮追加）

**背景**：10-20 `subtitle-reuse-versioning` 的 design §3.2 挂了一条账——

> 真判据（「是不是重清洗」）在本类里根本不存在，本段也不造 —— 等管线拆成三条时，重清洗是独立管线，
> 「我是谁」由管线身份自带，这个参数会变成多余的一层。

拆分完成后核账：这个预测**只对了一半**。`requireContextSummary` 在 `doClean` 里有两处用途——

| 用途 | 性质 |
|---|---|
| 决定开始事件的文案（`"开始重新生成字幕"` / `"开始清洗"`） | **代理**：拿「摘要失败要不要整体失败」去猜「我是谁」 |
| 摘要降级时抛 `IllegalStateException` | **真规则**：重新生成的语义不允许空上下文 |

所以这个布尔**不会**变成多余的一层，但它的一半职责确实在说谎。正确收法不是删掉它，而是**把两份职责分开**。

**取**：`doClean` 的 `boolean requireContextSummary` 换成独立枚举 `domain/subtitle/model/cleanup/CleanMode { FIRST_CLEAN, RECLEAN }`，枚举自带 `startMessage()` 与 `requireContextSummary()`；两个公开入口各传自己的值。

**落位**：独立枚举归 `domain/<上下文>/model/`（spec `directory-structure.md` 第 213 行）。**没做成 `SubtitleCleanupCoordinator` 的私有嵌套**——那样只是换个地方写，它仍然是那个类的内部细节，而且 application 层没有独立 enum 的先例。`CleanMode` 是清洗操作的词汇，两个字段里「摘要降级是否致命」本来就是业务规则；中文用户可见文案放 domain 也有先例（`AudioSpec` / `ArtifactFileKind` 的异常消息）。

**舍**：
- ① 只把文案提成独立参数——那样两个参数永远成对出现，是同一个信息的两种写法；
- ② 保持原样——代理留着，将来改策略会静默改掉用户看到的文案。

**公开 API 零变化**：`clean(...)` 的三个重载与 `regenerate(...)` 的签名一字未动。

**顺带补的断言**：`"开始重新生成字幕"` 原先**没有任何测试钉它**（只有 `"开始清洗"` 被钉住），在 `SubtitleCleanupCoordinatorTest.regenerateRerunsEvenWhenFingerprintUnchanged` 里补了一条。

### T7 `toMediaInput` 抽到 `shared/media/MediaInputUtils`（2026-10-21 审查轮追加）

**背景**：T1 定的是「本任务不抽共用」，`toMediaInput` 属于清单里的「后续第一顺位候选」。审查轮用户点名要求移出去——它在三个类里是**逐字相同的人肉拷贝**，纯映射、零状态。

**取**：`shared/media/MediaInputUtils.from(Workspace, Path) -> MediaInput`，三个类统一调用。

**为什么是工具类，不是领域模型方法**：

- `Workspace` 是**媒体身份聚合**，它的业务规则是 `updateFingerprint` / `hasUnknownFingerprint` / `matchesFingerprint` 那几条；「怎么变成下载器的输入」是对消费者的投影，不是它自己的行为。
- 映射还要吃调用方定位好的 `workDir`（上传路线拿它拼 `source.mp4`），而那个目录由 `application/media/port/WorkspaceDirectoryManager` 算——领域层拿不到。
- `shared/` 的准入判据原文就是「纯静态方法工具，无状态……可引用 `domain` 的领域词汇」，且它已经大量 import domain 类型（`StageProgress` / `TranscriptionSegment` / `SubtitleContext`）。

（反方向并非不可行：`domain/workspace/port/ContentFingerprintProbe` 早就 import 了 `MediaInput`，所以领域方法在依赖上不违规。选工具类是因为上面第一条——投影不是聚合的行为。）

**这不是「违反 T1」**：T1 的口径是「先不预抽，等看到真实的缝」。这处正是审查轮看出来的缝。其余四件 helper（`workDir` / `progressListener` / `stackOf` / `reason`）维持各写一份。

**证据**：`grep -rn toMediaInput src/` 零命中；三个调用点统一为 `MediaInputUtils.from(workspace, workDir)`；`mvn clean test` 全绿。

### T8 审查轮把四件 helper 与 `conflictFor` 收进归属（2026-10-21）

T1 的口径是「先不预抽」。审查轮逐条复核后，把有明确归属的五件收掉：

| helper | 去向 | 判据 |
|---|---|---|
| `progressListener` | `TaskRunSupervisor.listener(ref, run)` | supervisor 就是「推进阶段 + 收口」的机制类，`advance` 在它身上 |
| `reason` | `TaskRunSupervisor.fail(ref, run, Throwable)` | 它唯一的用途是喂 `finish` 写 `run.error_message`，而写那一列的就是 supervisor |
| `stackOf` | `shared/error/ThrowableUtils` | 纯静态、零依赖，与 `MediaInputUtils` 同性质 |
| `workDir` | 删除 | 只是 `WorkspaceDirectoryManager.ensureWorkspaceDirectory` 的一行包装。**别跟 `SubtitleTaskFinder.workspaceDir` 合并**——后者是「只定位、不创建」，给前置守卫用；合并会丢掉「守卫不建目录」这条语义 |
| `conflictFor` | `TaskRunSupervisor.buildTaskConflictException(task)` | supervisor 已持 `TaskRunRepository`，撞互斥的成因（`start` 返回空）就在它旁边；顺带三个服务各少一个依赖 |

**代价**：`supervisor` 在服务单测里是 mock，`listener` 变成需要 stub 的协作点、`fail` 取代了原先 `finish(..., reason(t))` 的断言——21 条用例的观察点跟着搬，并由 `TaskRunSupervisorTest` 新增两条覆盖（`listener` / `buildTaskConflictException`）。

**这之后**：三个服务的私有方法只剩各自流程自己的东西；跨类重复只剩 `requireIntactArtifact`（×2，理由见 §2.3）。

## 6. 改造后每条流程的完整路径

### MAIN

```
TaskController.submitUrl
└─ SubtitleMainService.submitUrl(url, keywords)
   ├─ url 空白 → IAE(400)
   ├─ SubtitleKeywords 构造（超限 → IAE 400，早于一切 I/O）
   ├─ mediaIdentityResolver.resolve(url)          ← 事务外（走网络，可能跟短链跳转）
   └─ handleSubmit(URL, sourceKey, url, canonicalUrl, keywords)
      ├─【TransactionTemplate 内】
      │   findOrCreateWorkspace（撞唯一键 → FOR SHARE 锁定读重查）
      │   findOrCreateTask（同上）
      │   supervisor.start(task, MAIN, null) → 空则 buildTaskConflictException → 409
      │   keywords != null 时 keywordRepository.replace
      ├─ log「任务已创建: taskNo, runNo, sourceType」
      └─ concurrencyGate.submit(
             () -> runPipeline(run, ref, workspace),
             () -> { log.warn; supervisor.finish(ref, run, "任务在等待执行配额时被中断：请重新提交"); })

   runPipeline（私有，异步）：
      fingerprintCoordinator.detectContentChange(...)   ← 内容变了先作废旧产物
      阶段 1：preparation.reuseIfAvailable(...) 命中 → 用既有 READY 行；未命中 → 占位 → prepare → settleArtifact
      阶段 2：cleanup.reuseIfAvailable(...) 命中 → 什么都不做；
              未命中 → 占位 → clean → settleCleanedAndSupersedeDownstream
      supervisor.finish(ref, run, null)
      catch(Throwable) → log.error("任务执行失败…") + supervisor.finish(ref, run, reason(t))
```

`submitUpload` 与 `submitUrl` 收敛到同一个 `handleSubmit`，差别只在身份键怎么算（上传路线流式落盘边写边算 SHA-256 → `settleUpload` 归位）。

### RECLEAN

```
TaskController.regenerate
└─ SubtitleRecleanService.regenerate(taskNo, keywords)
   ├─ SubtitleKeywords 构造（超限 → IAE 400）
   ├─ finder.require(taskNo)                      → 无 → TaskNotFoundException(404)
   ├─ finder.requireWorkspace(task)
   ├─ requireIntactArtifact(ORIGINAL)             → 无完好原始产物 → IAE(400)
   ├─ supervisor.start(task, RECLEAN, null) → 空则 buildTaskConflictException → 409
   ├─ keywords != null 时 keywordRepository.replace
   ├─ effectiveKeywords = 请求带的 ?: keywordRepository.findByTaskId
   ├─ log「字幕重新生成已触发: taskNo, runNo, keywordsSupplied」
   └─ concurrencyGate.submit(
          () -> {
             workDir = ensureWorkspaceDirectory(sourceKey)
             cleaned = artifactStore.createCreatingPlaceholder(CLEANED, "", originalArtifactId, keywords.computeFingerprint())
             cleanupStage.regenerate(mediaInput, workDir, keywords, listener)
             artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir)   ← 无条件作废下游（T3）
             supervisor.finish(ref, run, null)
             log「字幕重新生成完成」
          },
          () -> { log.warn; supervisor.finish(ref, run, "重新生成在等待执行配额时被中断：请重新触发"); })
      catch(Throwable) → log.error("字幕重新生成失败…") + supervisor.finish(ref, run, reason(t))
```

### TRANSLATE

```
TaskController.translate
└─ SubtitleTranslateService.translate(taskNo, sourceLanguage, targetLanguage)
   ├─ SupportedLanguage.parse × 2                 → 白名单外 → IAE(400)
   ├─ finder.require(taskNo)                      → 404
   ├─ finder.requireWorkspace(task)
   ├─ requireIntactArtifact(CLEANED)              → 无完好清洗产物 → IAE(400)
   ├─ supervisor.start(task, TRANSLATE, target.getCode()) → 空则 buildTaskConflictException → 409
   ├─ log「字幕翻译已触发: taskNo, runNo, source, target」
   └─ concurrencyGate.submit(
          () -> {
             workDir = ensureWorkspaceDirectory(sourceKey)
             listener = progressListener(ref, run)
             findIntactArtifact(TRANSLATED, target.getCode(), workDir) 命中
                → StageEvents.emit(SKIPPED, "已有当前译文，跳过翻译") + log + return（由骨架收口为 SUCCEEDED）
             translationStage.translate(mediaInput, workDir, source, target, listener)
             translated = createCreatingPlaceholder(TRANSLATED, target.getCode(), cleanedArtifactId, null)  ← 成功之后才落占位
             artifactStore.settleArtifact(translated, workDir)
             supervisor.finish(ref, run, null)
             log「任务翻译完成」
          },
          () -> { log.warn; supervisor.finish(ref, run, "翻译在等待执行配额时被中断：请重新触发翻译"); })
      catch(Throwable) → log.error("翻译失败…") + supervisor.finish(ref, run, reason(t))
```

## 7. 兼容与回滚

- **对外契约零变化**：端点路径、请求 / 响应 DTO、SSE 事件形状、异常 → 状态码映射全不动。`TaskController` 只换注入与调用对象。
- **数据库零变化**：六张表 DDL、`schema.sql`、产物模型、复用判据全不动。
- **不做灰度、不留开关**：纯结构搬迁，一次切干净。旧类直接删，不留兼容壳。
- **回滚**：本任务全部改动在 `feature/subtitle-run-flow-split` 一条分支上，回滚即丢弃该分支。没有数据迁移，回滚无残留。

## 8. 边界矩阵（改造后必须逐项与改造前相同）

| 场景 | 表现 |
|---|---|
| 提交全新 URL | 202 + `{taskNo, runNo}`；事务内落 workspace + subtitle_task + MAIN run |
| 提交已有任务的 URL（无 run 在跑） | 202 + **新 runNo**（不冲突） |
| 提交时该任务已有 run 在跑 | 409 + `{taskNo, runNo}`（既有 run 的序号，锁定读取得） |
| 并发提交同一个新来源 | 不报冲突；撞唯一键 → 同一事务内 FOR SHARE 锁定读重查 |
| 上传空文件 / 空内容流 | 400（Controller 卫语句 + Service 卫语句各一道，**两道都保留**） |
| 关键词超限 | 400，且早于一切 I/O，不落库 |
| `keywords` 字段缺席（null） | 不动该任务已有的关键词 |
| `keywords` 显式空数组（`[]`） | 落一份空清单（该任务此后按原样清洗） |
| 等待配额时被打断 | run 收 FAILED，原因文案与改造前逐字相同 |
| RECLEAN 时无完好 ORIGINAL | 400 |
| TRANSLATE 时无完好 CLEANED | 400 |
| TRANSLATE 时目标语种已有完好 READY 译文 | 广播 SKIPPED、不调 LLM、不落新版本、run 收 SUCCEEDED |
| 未知 taskNo | 404 |
| MAIN 阶段任一抛出 | run 收 FAILED，`error_message` = 异常消息（消息为 null 时退回类名） |
| MAIN 复用命中 | 两个门禁都命中 → 零落版；广播 SKIPPED |
| RECLEAN 完成 | 落一版新 CLEANED，**无条件**作废全部下游译文（T3） |
| 日志 | 失败日志只打异常类型与调用栈，**不打 message**（来源原文不得落盘的红线） |

## 9. 测试重分

| 旧文件 | 旧用例数 | 去向 |
|---|---|---|
| `SubtitlePipelineTest` | 22 | MAIN 14 → `SubtitleMainServiceTest`；RECLEAN 4 → `SubtitleRecleanServiceTest`；TRANSLATE 4 → `SubtitleTranslateServiceTest` |
| `SubtitleTaskServiceTest` | 13 | 按 run 类型同样三分 |

**逐条保留，不许静默丢用例**（AC5）——35 条一条不丢。

**审查轮之后总数是 702**（基线 700）：`failureMessageFallsBackToExceptionClassNameWhenMessageIsNull` 从 `SubtitleMainServiceTest` 搬到 `TaskRunSupervisorTest`（语义已随 `fail` 搬过去，净变化 0）；另给审查轮新增的两个公开方法 `TaskRunSupervisor.listener` 与 `buildTaskConflictException` 各补一条覆盖（+2）。`SubtitlePipelineTest` 里的 `failureMessageFallsBackToExceptionClassNameWhenMessageNull` 测的是 `reason()` 的兜底，**只保留一条**（放 `SubtitleMainServiceTest`）：三份 `reason()` 是逐字相同的拷贝，RECLEAN / TRANSLATE 那两份各由自己流程的失败用例（`regenerateFailureFinishesRunWithReason` / `translateFailureFinishesRunWithoutPlaceholder`）覆盖；刻意加两条重复用例会把 AC5「总数与基线逐项相同」这个机器判据搅浑。

**另外三个测试文件要跟着改**（开工前 grep 出来的全集，共 4 个文件引用旧类）：

| 文件 | 改什么 |
|---|---|
| `web/controller/TaskControllerTest` | `@MockitoBean` 从 `SubtitleTaskService` 换成三个新服务；`new TaskController(...)` 的实参跟着改 |
| `infrastructure/concurrency/SemaphoreTaskConcurrencyGateTest` | 1 处引用（注释），按现状订正 |

`SubtitlePipelineTest` 的包内可见入口 `runPipeline`（原为绕开虚拟线程时序）在 `SubtitleMainService` 里保持包内可见，测试调用方式不变——新测试类因此必须落在 `com.vidsage.application.task` 包下。
