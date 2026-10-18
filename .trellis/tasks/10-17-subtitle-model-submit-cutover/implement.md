# S2 执行计划：换血

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。** 本文件只写「按什么顺序做、每步的判据在哪、怎么验、崩了怎么退」。
> **本文件是执行参考稿，不是契约**：判据以 design.md 与代码为准；发现本文件与 design 冲突时，以 design 为准并回来改这里。
> 范围与验收见 [prd.md](./prd.md)。**本段是全流程唯一一次「换血」**：前面每一步都是增量，到步骤 5 一次性把旧栈换掉。

## 段内顺序（十步）

```
0  预期目录结构与退场清单过审（门）
1  身份键：MediaIdentityResolver（端口 + 实现 + 单测）
2  SSE 全套：RunRef / TaskRunProgress / TaskProgressBroadcaster / SseTaskProgressBroadcaster
3  TaskRunSupervisor（机制类 + 单测）          ← 必须在 2 之后：它注入 2 的端口
4  四个 coordinator 收 workDir（老栈行为一字不变；原 4.1–4.3 已移入步骤 5）
5  ★ 换血主步（一步做完才绿）：schema.sql + 关键词换锚点 + SubtitleTaskService
     + SubtitlePipeline 换锚 + TaskController 切换 + 删旧一整批
6  改名让名 + 包结构重排（T8）
7  启动收口 ApplicationRunner
8  文档与规范同步（design.md / directory-structure.md / database-guidelines.md）
9  全量验证 + 欠账核对
```

**顺序的理由**

- **步骤 1–3 全是纯新增**，谁都不碰 → 每一步结束都能 `clean test-compile` 过、全量测试绿。这是本段唯一的「从容窗口」，把三块新东西（身份键、SSE、run 机制）先做扎实。
  **2 与 3 的先后是有约束的**：`TaskRunSupervisor` 构造注入 `TaskProgressBroadcaster`，而端口的实现（`web/sse/SseTaskProgressBroadcaster`）在步骤 2 —— 反了的话，加载上下文的冒烟测试（`VidSageApplicationTests`）会因为找不到 bean 而红。
- **步骤 4 是「让老栈给新模型让路」**：coordinator 不再自己算 key 与目录（改由调用方传 `workDir`），老 pipeline 跟着传新参数 —— **老栈仍然能跑**，所以这一步也绿。
- **步骤 5 是不可再分的一步**（下面单列理由）。它中间必然是红的，这是换血的固有形状，不是安排失误。
- **步骤 6–8 各自独立**，都绿。
- **步骤 9 是收尾**：跑全量 + 逐条核欠账。

### 为什么步骤 5 拆不开

四个东西互锁，任何一个单独动都会留下「编译过但语义半截」的状态：

| 锁 | 谁锁谁 |
|---|---|
| `subtitle_keyword` 换锚点（`workspace_key` → `task_id`） | 需要 `subtitle_task.id`，而**只有新提交路径**产出它 → 老服务改不动 |
| `TaskRunSupervisor.finish` 的广播出口 | `TaskProgressNotifier` 同时是「落库」与「广播」的出口，落库那半被 supervisor 拿走，广播那半必须同时改 |
| `SubtitlePipeline` 换锚 | 它的三个执行体吃 `VideoTask`，而 `VideoTask` 在本步被删 |
| `TaskController` 切换 | 老 `TaskResponse` 的 `artifacts` / 扁平五字段与新契约不兼容，切换即老服务失去唯一调用方 |

**「先留个兼容层」不是选项**：`TaskStageEvent` 与 `TaskSessionRegistry` 是 `TaskProgressNotifier` 快照与广播的输入，它们在本步退场 —— 兼容层等于把要删的东西再写一遍。

---

## 步骤 0：预期目录结构与退场清单过审（门）

**这是一道门，不是准备工作。** 本段动的文件数是 S1 的两倍（新增约 12 个、改约 25 个、删约 35 个），形状定错了是全场返工。先把下面两棵树给项目所有者过一眼，**点头之后才开分支、才进步骤 1**（先例：S1 的同一道门）。

### 预期目录结构（S2 结束后）

```
backend/src/main/resources/
└── schema.sql                                [改] 删 task 表；subtitle_keyword 重建；头部补迁移说明

backend/src/main/java/com/vidsage/
├── application/task/                         [改] 只留用例与机制
│   ├── package-info.java                     [改] 根包不再「只留 VideoTaskService」
│   ├── SubtitleTaskService.java              [新] 原 VideoTaskService 换血（提交/上传/查询/keywords/触发）
│   ├── TaskRunSupervisor.java                [新] 机制类：start / advance / finish / recoverInterruptedRuns
│   ├── TaskRunStartupRecovery.java           [新] ApplicationRunner，调 recoverInterruptedRuns
│   ├── exception/                            [不动] TaskConflictException / TaskNotFoundException
│   ├── message/
│   │   ├── package-info.java                 [改]
│   │   ├── TaskRunProgress.java              [新] sealed 三变体 + 静态工厂
│   │   └── TaskProgressMessage.java          [删]
│   ├── notify/                               [整包删] TaskProgressNotifier
│   ├── port/
│   │   ├── TaskConcurrencyGate.java          [不动]
│   │   └── TaskProgressBroadcaster.java      [新] 端口
│   ├── query/
│   │   ├── package-info.java                 [改]
│   │   ├── TaskSnapshot.java                 [改] 身份 + lastRun
│   │   ├── RunSummary.java                   [新] lastRun 的应用层读模型
│   │   ├── KeywordsSnapshot.java             [不动]
│   │   ├── TaskArtifact.java                 [删]
│   │   └── TaskArtifactFile.java             [删]
│   └── session/                              [整包删] TaskSessionRegistry
│       （application/task/pipeline/ 随 T8 消失 —— 子包移去 application/subtitle/pipeline/）
├── application/media/
│   ├── package-info.java                     [改]
│   ├── WorkspaceKeyGenerator.java            [删]
│   ├── pipeline/AudioPreparationCoordinator.java [改] 收 workDir
│   ├── pipeline/MediaAcquirerDispatcher.java [不动]
│   └── port/WorkspaceDirectoryManager.java   [改] ensureWorkspaceDirectory(SourceKey)
├── application/subtitle/
│   ├── SubtitleLanguageService.java          [不动]
│   ├── query/LanguageOptionSnapshot.java     [不动]
│   └── pipeline/                             [T8] 编排件与被编排件同包
│       ├── SubtitlePipeline.java             [移入 + 改] 从 application/task/pipeline/ 移来；
│       │                                      三条执行体换锚到 (TaskRun, Workspace)
│       ├── SubtitlePreparationCoordinator.java   [改] 收 workDir
│       ├── SubtitleCleanupCoordinator.java       [改] 收 workDir
│       └── SubtitleTranslationCoordinator.java   [改] 收 workDir
├── application/workspace/                    [整包删] SubtitleWorkspaceFilePaths / TranslationFilePaths
├── application/exception/InvalidWorkDirectoryException.java [删]
├── domain/task/                              [整包重写] 旧的 VideoTask / TaskStatus / TaskInputType /
│                                              TaskStageEvent / VideoTaskRepository 整块删；
│                                              新的「任务与执行」轴搬入：
│                                              SubtitleTask / TaskRun /
│                                              model/{TaskNo(原地不动), RunRef(新), TaskRunType, TaskRunStatus} /
│                                              port/{SubtitleTaskRepository, TaskRunRepository}
├── domain/subtitle/                          [瘦身] 只留「字幕内容」轴
│   ├── SubtitleArtifact.java / SubtitleKeywords.java     [不动]
│   ├── model/{SubtitleStageName, SubtitleArtifactSpec, SubtitleContext,
│   │          ArtifactType, SubtitleArtifactStatus, cleanup/, translation/, term/, common/}
│   ├── model/common/SubtitleStageOutput.java [改名] 原 SubtitleArtifact（record）
│   ├── model/common/SubtitleArtifactSpec.java [改] 删 downloadable / isDownloadable
│   ├── policy/                               [不动]
│   └── port/                                 [改] SubtitleKeywordRepository 换锚点到 taskId；
│                                              SubtitleTaskRepository / TaskRunRepository 搬去 domain/task/
├── domain/intermediate/                      [新包] 从 domain/subtitle/ 搬出、独立成包：
│                                              IntermediateFile / model/{IntermediateFileType,
│                                              IntermediateFileStatus} / port/IntermediateFileRepository
├── domain/workspace/
│   ├── model/WorkspaceDir.java               [搬入] 原 domain/shared/model/，工厂改 of(SourceKey)
│   ├── model/ResolvedMediaIdentity.java      [新] record(SourceKey, String canonicalUrl)
│   └── port/MediaIdentityResolver.java       [新]
├── domain/shared/model/WorkspaceKey.java     [删]
├── domain/shared/model/WorkspaceDir.java     [搬出]
├── domain/media/model/
│   ├── MediaInput.java                       [改] 删 getContentKey / dirLabel / ofTaskInput
│   └── AudioStageOutput.java                 [改名] 原 AudioArtifact（record）
├── infrastructure/fs/NioWorkspaceDirectoryManager.java [改]
├── infrastructure/provider/identity/bilibili/
│   ├── BilibiliMediaIdentityResolver.java    [新]
│   └── BilibiliIdentityProperties.java       [新] 短链超时 / 缓存上限
├── infrastructure/persistence/
│   ├── entity/VideoTaskEntity.java           [删]
│   ├── entity/SubtitleKeywordEntity.java     [改] workspaceKey → taskId + updatedAt
│   ├── mapper/VideoTaskMapper.java           [删]
│   ├── mapper/SubtitleKeywordMapper.java     [改] upsert 换锚点 + 刷 updated_at
│   └── repository/
│       ├── MybatisVideoTaskRepository.java   [删]
│       └── MybatisSubtitleKeywordRepository.java [改]
├── web/controller/TaskController.java        [改] 新契约 + 新 SSE 路径
├── web/dto/
│   ├── TaskResponse.java                     [改] 身份 + lastRun（无 artifacts、无扁平五字段）
│   ├── RunResponse.java                      [新] lastRun 的 web 镜像
│   ├── TaskRunRefResponse.java               [新] 202 / 409 同形状 {taskNo, runNo}
│   ├── TaskConflictResponse.java             [删]
│   ├── TaskArtifactResponse.java             [删]
│   └── （其余不动）
└── web/sse/
    ├── SseTaskProgressBroadcaster.java       [新] 端口实现（连接表 + 心跳 + 超时）
    └── TaskSseConnectionManager.java         [删] 内容折进上面那个

backend/src/test/java/com/vidsage/                     （测试包镜像主包）
```

**这棵树要对照的五条**

1. **`domain/task/` 是「整包重写」，不是「整包消失」**：旧的三合一（`VideoTask` 那一批）整块删，新的「任务与执行」轴（`SubtitleTask` / `TaskRun` / `TaskNo` / `RunRef` / 两个 port）搬进来。`domain/subtitle/` 与 `domain/intermediate/` 各管一条轴。
2. **`application/task/` 从七个包缩到四个**：`subscribe/` / `notify/` / `session/` 三包整包删，**`pipeline/` 移去 `application/subtitle/pipeline/`**（T8），留下 `message/` / `port/` / `query/` / `exception/`。
3. **`application/X` 与 `domain/X` 必须一一对应**（`task` / `subtitle` / `media` / `transcription`）—— 这是 T8 的目的，验收时逐对核（AC12）。
4. **`web/sse/` 只留一个类** —— 它是本仓第一个「实现在 web 层的端口」，`directory-structure.md` 的新规则（需求 48）与它同时落地。
5. **测试包严格镜像主包**，没有「测试专用」的额外层级。

### 退场清单（步骤 5 一次性删，实施时逐条核）

```
domain/task/                                    整包
domain/shared/model/WorkspaceKey.java
application/media/WorkspaceKeyGenerator.java
application/task/session/                       整包
application/task/notify/                        整包
application/task/message/TaskProgressMessage.java
application/task/query/TaskArtifact.java
application/task/query/TaskArtifactFile.java
application/workspace/                          整包
application/exception/InvalidWorkDirectoryException.java
infrastructure/persistence/{entity,mapper,repository}/VideoTask*
web/dto/{TaskConflictResponse,TaskArtifactResponse}.java
web/sse/TaskSseConnectionManager.java
```

### 过审之后

当前分支是 `dev`。本段既碰代码、又碰文档与规范（步骤 8），按 [git-workflow](../../../.trellis/spec/guides/git-workflow.md) **都要开 feature 分支**，从 `dev` 切 `feature/subtitle-model-submit-cutover` —— 不能直接在 `dev` 上做。

---

## 步骤 1：身份键（`MediaIdentityResolver`）

**全部新增，零调用方**，所以这一步结束时全绿。

| 文件 | 内容 |
|---|---|
| `domain/workspace/model/ResolvedMediaIdentity.java` | `record ResolvedMediaIdentity(SourceKey sourceKey, String canonicalUrl)` |
| `domain/workspace/port/MediaIdentityResolver.java` | `ResolvedMediaIdentity resolve(String sourceUri)`（纯契约，无框架注解） |
| `infrastructure/provider/identity/bilibili/BilibiliMediaIdentityResolver.java` | 实现 |
| `infrastructure/provider/identity/bilibili/BilibiliIdentityProperties.java` | `@ConfigurationProperties`：短链超时（默认 2s）、缓存上限 |

**实现顺序（写在 `resolve` 里，一条直线）**

1. **剥跟踪参数 + 取 BV**：解析 URL → 丢 `trackid` / `spm_id_from` / `vd_source` 这类跟踪参数 → 取 `/video/` 段的 `BV...`；`av...` 换算成 `BV...`（换算规则写成本类的一个私有静态方法，注释里写明换算的进制依据）。
2. **拼 `p`**：查询串里的 `p`，缺省 1 → `SourceKey.value()` = `bvid|p`；`canonicalUrl` = 去掉跟踪参数后的 URL。
3. **第 1 步抽不到才跟一次跳转**：`RestClient` + `JdkClientHttpRequestFactory`（`HttpClient.followRedirects(NEVER)` + `connectTimeout(2s)` + `readTimeout(2s)`）打一次，**只读 `Location` 头**，拿到的 URL 回到第 1 步再解析一遍。**只跟一次**，不递归。
4. **缓存**：短链 → 长链映射落进程内**有界 LRU**（`LinkedHashMap` + `removeEldestEntry`，`Collections.synchronizedMap` 包裹，上限取 properties 的值）。**不做无界 map**（内存泄漏），**也不做「满了就 clear」**（批量提交时会抖动）。
5. **仍抽不到**：回落「规范化后的 URL 原文」当 `source_key` + `log.warn`（**不打印 `source_uri` 原值**，脱敏红线）。**这是降级路径，不是失败路径** —— 不得抛异常。

**单测**（`src/test/java/com/vidsage/infrastructure/provider/identity/bilibili/`）

- 跟踪参数各形态 → 同一个 `SourceKey`（父 prd AC1 的第一条）。
- `av...` 换算、`p` 缺省 / 显式、`/video/BV...` 各种 URL 形态。
- 短链：`MockRestServiceServer.bindTo(RestClient.Builder)` 打桩 302 → 断言拿到 `bvid`；**再调一次断言不打桩也能过**（缓存命中）；**长链断言零请求**（`server.verify()` 或请求计数）。
- 抽不到 → 返回回落值、不抛异常。

> **打桩为什么用 `MockRestServiceServer`**：它是 Spring 给 `RestClient` 的官方打桩器（`bindTo(RestClient.Builder)`），比 mock 链式调用干净。

---

## 步骤 2：SSE 全套

**四个新文件**，落点见步骤 0 的树。

**前置：无 S1 回填**（2026-10-18 定 **T9**）—— **`taskNo` 由 `RunRef` 带，不放进 `TaskRun`**：

- `RunRef(TaskNo, int runNo)` 本来就是 run 的对外地址，在实体上再放一个是**第二个家**；而那个家**落不了库**（`task_run` 没有 `task_no` 列），只能靠读回时补查 `subtitle_task` —— 可三处读调用方（`handleSubmit` 的 409 路径、`resolveRun`、`findByTaskNo` 拼 `lastRun`）手里本来就有 `SubtitleTask`，那次补查是纯浪费。DB 层定死单来源、实体层带两个，也自相矛盾。
- 所以 `TaskRun` **只保留内部 `taskId`**（`of(long taskId, …)`、`restore` 不带 taskNo）；`MybatisTaskRunRepository` **不注入 `SubtitleTaskMapper`、不补查**。
- `RunRef.of(TaskNo taskNo, TaskRun run)`、`TaskRunProgress.of(RunRef ref, TaskRun run, StageProgress stage)` —— taskNo 由 ref 带。
- **唯一保留的 S1 回填**：`TaskRunRepository` 补 `Optional<TaskRun> findByTaskIdAndRunNo(long taskId, int runNo)`（`resolveRun` 要用，S1 漏了）。
- **撤回记录**：本节先前（同日早）曾按旧口径给 `TaskRun` 加过 `TaskNo` 字段、让仓储读回补查、并把 `of` 改成收 `SubtitleTask` —— 现已全部撤回。

四个新文件：

### 2.1 `domain/task/model/RunRef.java`

```java
public record RunRef(TaskNo taskNo, int runNo) {
    public static RunRef of(TaskNo taskNo, TaskRun run) { ... }   // taskNo 由调用方给（它手里有 SubtitleTask），runNo 从 run 取
}
```
不变量：`runNo >= 1`（非正数抛 `IllegalStateException`，消息写清「runNo 由 task_run.run_no 生成，从 1 起」）。

### 2.2 `application/task/message/TaskRunProgress.java`

```java
public sealed interface TaskRunProgress
        permits TaskRunProgress.Main, TaskRunProgress.Reclean, TaskRunProgress.Translate {

    String taskNo();
    int runNo();
    TaskRunType runType();
    TaskRunStatus status();
    StageProgress stage();
    boolean terminal();                 // = status != TaskRunStatus.RUNNING

    static TaskRunProgress of(RunRef ref, TaskRun run, StageProgress stage) { ... }   // 唯一的组装入口
}
```

- 三个变体各带 `runType` 组件，紧凑构造器守卫挡住「手构出 `Main` 却填 `RECLEAN`」；`Translate` 多带 `targetLanguage`。
- **`stage` 允许为 `null`**（run 还没进第一个阶段 / 排队中），形状仍合法。
- 静态工厂 `of` 是**唯一**的组装入口：按 `run.getRunType()` 分派变体、按 `run.getStatus()` 派生 `terminal`，`taskNo` / `runNo` **取自 `ref`**（`TaskRun` 不带对外号）。**别在别处 `new` 变体**。

**快照 / 终态事件的渲染**（照现状 `TaskStageEvent.from` 的形状搬过来，只是状态源从 `VideoTask` 换成 `TaskRun`）：

| run 状态 | `stage` 的渲染 |
|---|---|
| `RUNNING` | `current_stage` 为 `null` → `null`；否则 `(stage, 0, "任务执行中", RUNNING)` |
| `SUCCEEDED` | `current_stage` 为 `null` → `null`；否则 `(stage, 100, "任务已完成", SUCCEEDED)` |
| `FAILED` | `(current_stage, 0, errorMessage 或 "任务已失败", FAILED)` |
| `INTERRUPTED` | `(current_stage, 0, errorMessage 或 "上次被中断了，重新提交即可", FAILED)` |

> `INTERRUPTED` 那一臂**实际到不了**（启动收口发生时进程刚起、没有任何连接；`resolveRun` 拿到 `INTERRUPTED` 会走 204 不建快照）。留着是为了 switch 穷尽，**不是**为了渲染。
> **`"任务排队中"` 那条没了**：新模型没有 `PENDING`，排队期间 run 已是 `RUNNING`、`current_stage` 为 `null` —— 前端看到「运行中但没有阶段」（需求 19 已记账）。

### 2.3 `application/task/port/TaskProgressBroadcaster.java`

```java
public interface TaskProgressBroadcaster {
    void publish(RunRef run, TaskRunProgress event);   // 发给这条 run 的全部连接；事件带 terminal 时发完即关
    void complete(RunRef run);                          // 无条件兜底：关掉这条 run 的全部连接 + 摘表
}
```

**`open` 不上端口** —— 它返回 `SseEmitter`（HTTP 类型），由 controller 直接调实现类。

### 2.4 `web/sse/SseTaskProgressBroadcaster.java`

把 `TaskSseConnectionManager` 的内容折进来，**连接表只有一张**：

```java
private final ConcurrentHashMap<RunRef, CopyOnWriteArrayList<SseEmitter>> emitters;
```

| 成员 | 要点 |
|---|---|
| `SseEmitter open(RunRef run, TaskRunProgress snapshot)` | 建 emitter → 入表 → **注册一个 `onCompletion`**（见下）→ 推快照 → 若快照 `terminal` 则关 |
| `publish(RunRef, TaskRunProgress)` | 遍历该 run 的桶逐个 `send`；**单条 try/catch，失败只摘它自己**；事件 `terminal` 为真时 `target.complete()` + 摘表 |
| `complete(RunRef)` | 无条件：把该 run 桶里**每条** `complete()` + 摘表；桶空了删桶（`computeIfPresent` 返回 `null`） |
| 心跳 / 空闲超时 / `@PreDestroy` | 照现状原样搬（30s 注释行 / 30 分钟 / 停调度器） |

**`onCompletion` 只注册一个**（需求 24 —— 本段要修掉现状的覆盖 bug）：

```java
emitter.onCompletion(() -> remove(run, emitter));      // 就这一件事，没有第二个回调要跟它抢槽位
```
`SseEmitter` 的 `completionCallback` 是**单字段**（`ResponseBodyEmitter` 里 `onCompletion()` 是赋值不是追加），现状注册了两次、后者覆盖前者，导致客户端断连时 emitter 摘不出表。**别在别处再注册一次。**

**单测**（`web/sse/SseTaskProgressBroadcasterTest.java`）

- 同一条 run 两个连接 → `publish` 两个都收到。
- **不同 run 的连接互不波及**（AC6）。
- 一条连接断开 → 只摘它自己，另一条继续收。
- `publish` 一条失败 → 只摘失败的那条。
- `complete(run)` → 该 run 的连接全关、桶消失；**别的 run 的桶不动**。
- **`onCompletion` 覆盖 bug 的回归**：模拟客户端断连（触发 completion 回调），断言 emitter 从表里摘除。

---

## 步骤 3：`TaskRunSupervisor`（机制类）

**全部新增。落 `application/task/TaskRunSupervisor.java`。**
**它必须排在步骤 2 之后**：本类构造注入 `TaskProgressBroadcaster`，而那个端口的实现（`web/sse/SseTaskProgressBroadcaster`）在步骤 2 —— 顺序反了，加载上下文的冒烟测试（`VidSageApplicationTests`）会红。

**依赖**：`TaskRunRepository` / `SubtitleTaskRepository` / `SubtitleArtifactRepository` / `TaskProgressBroadcaster`。

**四个方法 + 一条纪律**

```java
Optional<TaskRun> start(SubtitleTask task, TaskRunType type, String targetLanguage)
void              advance(RunRef ref, TaskRun run, StageProgress stage)
void              finish(RunRef ref, TaskRun run, String errorMessageOrNull)
void              recoverInterruptedRuns()
```

| 方法 | 内部步骤 |
|---|---|
| `start` | `taskRunRepository.insertRunning(TaskRun.of(task.getId(), type, targetLanguage))` —— 撞 `uk_active_run` 时仓储已返回空（S1 已落），**本类不外抛、不重试、不清旧行**，直接返回空 |
| `advance` | `run.updateStage(stage.stage())` → `taskRunRepository.update(run)` → `broadcaster.publish(ref, TaskRunProgress.of(ref, run, stage))` |
| `finish` | 见下（顺序写死） |
| `recoverInterruptedRuns` | ① `List<Long> taskIds = taskRunRepository.interruptAllRunning(now)`<br>② `subtitleArtifactRepository.markCreatingAsFailed()`<br>③ `subtitleTaskRepository.touchUpdatedAt(taskIds, now)`（空集合是安全的，S1 已处理）<br>④ `log.warn` 打「收掉 N 条残留 run、涉及 M 个 task」 |

**`finish` 的三段，后两段必须在 `try` 之外**（需求 16，这是死订阅②的正解）：

```java
void finish(RunRef ref, TaskRun run, String errorMessageOrNull) {
    try {                                        // ① 落终态，失败只记 ERROR
        if (errorMessageOrNull == null) run.succeed(); else run.fail(errorMessageOrNull);
        taskRunRepository.update(run);
        subtitleTaskRepository.touchUpdatedAt(List.of(run.getTaskId()), now());   // C45
    } catch (RuntimeException e) {
        log.error("run 终态落库失败，仍会广播终态并清流: runNo={}", run.getRunNo(), e);
    }
    try { broadcaster.publish(ref, TaskRunProgress.of(ref, run, terminalStage(run))); }  // ② 无条件广播
    catch (RuntimeException e) { log.error(...); }
    broadcaster.complete(ref);                    // ③ 无条件清流（兜底）
}
```

> **`TaskRun` 不给 `interrupt()`**（S1 已守，`INTERRUPTED` 的唯一写入路径是启动收口的批量 UPDATE）。别在 `finish` 里加这个分支。

**快照与终态事件的组装归 `TaskRunProgress` 的静态工厂，不归本类** —— `TaskRunSupervisor` 只负责「落库 + 广播 + 清流」这一串，消息形状只在一处定义。

**单测**：四个方法各覆盖正常 / 异常路径；重点两条 ——
- **`finish` 落库抛异常时，广播与 `complete` 仍然发生**（AC6 后半条，用 mock 让 `TaskRunRepository.update` 抛）。
- **`start` 撞键返回空**（mock 仓储返回 `Optional.empty()`），且**没有**任何「清旧行再插」的调用（用 `verify(..., never())` 钉住）。

---

## 步骤 4：四个 coordinator 收 `workDir`

**这一步让老栈给新模型让路，但老栈行为一字不变**（老 pipeline 传进去的正是 coordinator 原先自己算出来的那个路径），所以结束时全绿。

> **2026-10-18 订正：步骤 4 的边界收窄。** 原 4.1（`WorkspaceDir` 搬家 + 工厂换 `of(SourceKey)`）、4.2（`MediaInput` 瘦身）、4.3（`WorkspaceDirectoryManager` 换签名）**移入步骤 5**（见 5.7）。
> 原因：这三件与 `VideoTaskService` 互锁 —— 它手里只有老的 `WorkspaceKey`（12 位哈希），而新工厂要 `SourceKey`。照原计划在步骤 4 做，老栈的目录名会从 `<标签>-<12位哈希>` 变成 `sanitize(source_key)`（老目录失联），且要在 `VideoTaskService` 里加一段「老 key → 新 key」的桥接 —— 那正是 prd 禁止的「只为这一段验收、后面必删」的代码。这三件事的最后住户就是 `VideoTaskService` 与老 `SubtitlePipeline`，跟它们一起在步骤 5 走最干净。
> **收益**：回滚点表里「步骤 4 之后 = 零代价」这句话仍然成立。

### 4.4 四个 coordinator 收 `workDir`

签名一律从 `(MediaInput input, …)` 变成 `(MediaInput input, Path workDir, …)`：

```
SubtitlePreparationCoordinator.prepare(MediaInput, Path workDir, ProgressListener)
SubtitleCleanupCoordinator.clean(MediaInput, Path workDir, SubtitleKeywords, ProgressListener)
SubtitleCleanupCoordinator.regenerate(MediaInput, Path workDir, SubtitleKeywords, ProgressListener)
SubtitleTranslationCoordinator.translate(MediaInput, Path workDir, SupportedLanguage, SupportedLanguage, ProgressListener)
AudioPreparationCoordinator.prepare(MediaInput, Path workDir, AudioSpec, ProgressListener)
```

**删掉每个 coordinator 里那两行**：`workspaceKeyGenerator.parseMediaToWorkspaceKey(input)` 与 `ensureWorkspaceDirectory(input.dirLabel(), key)` —— 改成用传进来的 `workDir`。

**老 `SubtitlePipeline` 的调用点**：算一次 `workspaceDirectoryManager.ensureWorkspaceDirectory(task.getSourceUri(), task.getWorkspaceKey())`，传给各阶段（`prepareAndClean` 算一次复用；`regenerateSubtitles` / `runTranslationPipeline` 在**异步体 try 内**调，保证建目录失败仍落到原来的失败收口）。

> **2026-10-18 订正**：原写的是 `getWorkspaceRoot().resolve(task.getWorkspaceDir())` —— **错**。全仓 `Files.createDirectories` 只出现在 `NioWorkspaceDirectoryManager` 与 `VideoTaskService` 的临时目录里，**URL 路线的建目录唯一就发生在 coordinator 那一行**；照原写法，URL 提交的工作目录无人创建，写产物直接失败。`task.getSourceUri()` 恰是当年那两行的 label（URL 路线 = URL 原文，UPLOAD 路线 = 上传原始名），`task.getWorkspaceKey()` 恰是 `parseMediaToWorkspaceKey(input)` 的结果，故这一句**逐字复现**旧结果与建目录副作用。
> `WorkspaceKeyGenerator` 在本步之后**零调用方**（原写「只剩 `VideoTaskService` 用它」不准 —— 它的调用点全在四个 coordinator 里），随老服务在步骤 5 一起删。
> 同理 **`MediaInput.dirLabel()` 在本步之后也零调用方**，步骤 5 的 `MediaInput` 瘦身（5.7）删它时别漏。

**单测**：四个 coordinator 的测试各加一个 `workDir` 参数（多为机械改动）；`WorkspaceDirTest` 重写（工厂换了）；`WorkspaceKeyGeneratorTest` 留到步骤 5 删。

---

## 步骤 5：★ 换血主步

> **这一步中间编译是红的，是预期的。** 回滚点只有「步骤 5 之前」与「步骤 5 之后」两处，**不许停在中间**。内部按 5.1 → 5.6 一条直线走完。

### 5.1 `schema.sql`

1. **删 `task` 表 DDL**（整块）。
2. **`subtitle_keyword` 重建**：`workspace_key CHAR(12)` → `task_id BIGINT`；**净增 `updated_at DATETIME NOT NULL`**；索引改名 `uk_subtitle_keyword_task_id`。列注释照 design §1.5 逐字。
3. **头部补存量迁移说明**（S1 只写了预告，这里补全）：本版删了 `task` 表、重建了 `subtitle_keyword`，而 `CREATE TABLE IF NOT EXISTS` **不改已存在的表**、MySQL 8 也没有 `DROP COLUMN IF EXISTS` → **旧库必须手工执行一次**：
   ```sql
   DROP TABLE IF EXISTS subtitle_keyword;   -- 列名与类型都变了，只能重建
   DROP TABLE IF EXISTS task;               -- 旧表已无代码引用
   ```

### 5.2 `subtitle_keyword` 换锚点

| 层 | 改动 |
|---|---|
| 端口 `SubtitleKeywordRepository` | `findByTaskId(long taskId)` / `replace(long taskId, SubtitleKeywords keywords)`（原 `findByWorkspaceKey` / `replace(WorkspaceKey, …)`） |
| `SubtitleKeywordEntity` | `workspaceKey` → `taskId`（`Long`）+ 新增 `updatedAt` |
| `SubtitleKeywordMapper` | `replaceByTaskId(taskId, keywords, now)`：`INSERT INTO subtitle_keyword (task_id, keywords, created_at, updated_at) VALUES (…) ON DUPLICATE KEY UPDATE keywords = #{keywords}, updated_at = #{now}` —— **首插时 `created_at` 与 `updated_at` 同值**（同一个 `now` 参数） |
| `MybatisSubtitleKeywordRepository` | 换锚点；`joinValues` / `splitToValues` 不动 |

### 5.3 `SubtitleTaskService`（新增）

落 `application/task/SubtitleTaskService.java`。方法集与老服务一一对应，只换形状：

```java
TaskRunRef   submitUrl(String url, List<String> keywords)                  // 202 / 409 的体
TaskRunRef   submitUpload(InputStream content, String originalFileName, List<String> keywords)
TaskRunRef   regenerate(String taskNo, List<String> keywords)
TaskRunRef   translate(String taskNo, String sourceLanguage, String targetLanguage)
TaskSnapshot findByTaskNo(String taskNo)
KeywordsSnapshot findKeywords(String taskNo)
TaskRun      resolveRun(String taskNo, int runNo)                          // SSE 端点用；查不到抛 TaskNotFoundException
```

（`TaskRunRef` 是 application 层的小 record：`record TaskRunRef(String taskNo, int runNo)`。）

**`resolveRun` 两步查**：`subtitleTaskRepository.findByTaskNo(taskNo)` 拿 id → `taskRunRepository.findByTaskIdAndRunNo(taskId, runNo)`（T9 补的那个方法）；查不到抛 `TaskNotFoundException`。

**提交路径**（`submitUrl` / `submitUpload` 合流到私有 `handleSubmit`）：

```
① 事务外：算身份键
   URL 路线    → mediaIdentityResolver.resolve(url)          ← 网络，绝不能在事务里
   上传路线    → 流式写盘边算 sha256（全 64 位）→ SourceKey   ← 文件 IO，同样在事务外
② 事务内（一个 @Transactional 方法，同类内直接调用，不隔代理边界）：
   a. 关键词校验（超限 → IAE/400），早于一切 I/O
   b. workspace 命中？未命中就 INSERT；撞 uk_source → 锁定读重查（findForShareBySourceTypeAndSourceKey）
   c. subtitle_task 命中？未命中就 INSERT；撞 uk_workspace → 锁定读重查（findForShareByWorkspaceId）
   d. supervisor.start(taskId, MAIN, null) → 空 → 抛 TaskConflictException（带 taskNo + 既有 runNo）
   e. 关键词覆盖写（只有请求带 keywords 才写）
③ 事务提交后：造一次 ref = RunRef.of(task.getTaskNo(), run)，pipeline.run(run, ref, workspace) 派发执行体
④ 返回 TaskRunRef
```

**四条纪律**

1. **身份键计算与上传落盘在事务外**（C63 落地约束②：事务期间持 workspace 行的 S 锁，不许放慢操作）。
2. **撞键的 INSERT 与捕获它的 catch 在同一事务方法、同一类内直接调用**（C63 落地约束①）；仓储保持裸 `@Repository`，**别给它加 `@Transactional`**。
3. **`TaskConflictException` 要带上既有 run 的 `runNo`** —— 409 的响应体是 `{taskNo, runNo}`，而 `start` 撞键时只返回空，所以 catch 之后要 `findLatestByTaskId` 取一次（同一事务内）。
4. **「未就绪」抛 `IllegalArgumentException`（400），不是冲突** —— 新模型里 409 只剩 `uk_active_run` 撞键一种语义。

**`regenerate` / `translate` 的前置判据**：本段**仍是文件判据**（`original-segments.json` / `clean-subtitles.srt` 在不在），因为产物还没进 `subtitle_artifact` 表（S3 才接）。**这是欠账，记进清单**，别在这里提前查 artifact 表。

**`findKeywords`**：形状不变；`fingerprint` **仍从 `clean-segments.json` 的文件内容算**（S3 起改从 `CLEANED` 行的 `keywordsFingerprint` 读）—— 同样是欠账。

### 5.4 `SubtitlePipeline` 换锚

三个执行体的签名从 `(VideoTask, …)` 换成 `(TaskRun, Workspace, …)`：

```java
void run(TaskRun run, RunRef ref, Workspace workspace)                                       // MAIN
void regenerate(TaskRun run, RunRef ref, Workspace workspace, SubtitleKeywords keywords)     // RECLEAN
void translate(TaskRun run, RunRef ref, Workspace workspace, SupportedLanguage source, SupportedLanguage target)
```

（`ref` 由 `SubtitleTaskService` 造好传进来 —— 见 T9；`advance` / `finish` 都收它。）

- `workDir` 由 `getWorkspaceRoot().resolve(WorkspaceDir.of(workspace.getSourceKey()).value())` 推出，传给各 coordinator。
- **所有阶段推进与终态走 `TaskRunSupervisor`**：`advance` / `finish`。`taskProgressNotifier.*` 与 `taskSessionRegistry.*` 的调用点整块消失。
- **异步体必须 `try { … } catch (Throwable t) { supervisor.finish(run, message) }`**（C43 无条件收口）；正常路径结尾也调一次 `finish(run, null)`。
- **闸门的中断回调**（`onQueueInterrupted`）同样落到 `finish(run, "排队被打断")`。
- 三条执行体共用同一个 `SemaphoreTaskConcurrencyGate`（保留，限流 ≠ 互斥）。

### 5.5 `TaskController` 切换

| 端点 | 新形状 |
|---|---|
| `POST /api/tasks` | 202 / 409 → `TaskRunRefResponse{taskNo, runNo}` |
| `POST /api/tasks/upload` | 同上（multipart 契约不变） |
| `GET /api/tasks/{taskNo}` | `TaskResponse`：身份（`taskNo` / `inputType` / `sourceUri` / `createdAt`）+ `lastRun`（`RunResponse` 或 `null`）；**无扁平五字段、无 `artifacts`** |
| `GET /api/tasks/{taskNo}/runs/{runNo}/events` | **新路径**。三种结果：runNo 不存在 → 404；`RUNNING` → `broadcaster.open(runRef, TaskRunProgress.of(runRef, run, null))`；已终态 → **204 No Content**。**taskNo 在路径上，直接拼 `RunRef`，不回查** |
| `POST /api/tasks/{taskNo}/translate` | 202 / 409 → `TaskRunRefResponse` |
| `POST /api/tasks/{taskNo}/subtitles/regenerate` | 同上 |
| `GET /api/tasks/{taskNo}/keywords` | 不变 |
| `GET /api/tasks/{taskNo}/artifacts/{fileName}` | **删**（S3 重建） |

- `ApiExceptionHandler`：`TaskConflictException` 的响应体从 `TaskConflictResponse{error, existingTask}` 换成 `TaskRunRefResponse{taskNo, runNo}`（**同形状、无文案**，C56）。
- **SSE 端点返回类型写成 `Object`**，同时能吐 `SseEmitter` 与 `ResponseEntity.noContent()` —— 实施时**实测一次** Spring MVC 的两个返回值处理器（`ResponseBodyEmitterReturnValueHandler` / `HttpEntityMethodProcessor`）能共存。若不行，退路是给「已终态」单独一个路径或返回空体 `SseEmitter`（**退路要回写本文件**）。
- **SSE 端点的快照**：`RUNNING` 且 `current_stage` 为 `null` 时 `stage` 传 `null`（形状合法）。

### 5.6 删旧一整批

按步骤 0 的退场清单逐条删，外加：

- `SubtitleArtifactSpec` 的 `downloadable` 字段与 `isDownloadable`（唯一消费者 `scanArtifacts` 随老服务删）。
- 老服务里的 `scanArtifacts` / `openArtifact` / `TEMP_FILE_PREFIX` 相关的临时文件处理（上传落盘那套**保留**，它搬到新服务里）。
- 老测试类：`VideoTaskTest` / `TaskSessionRegistryTest` / `MybatisVideoTaskRepositoryTest` / `TaskProgressNotifierTest` / `TaskProgressMessageTest` / `TaskSseConnectionManagerTest` / `WorkspaceKeyTest` / `WorkspaceKeyGeneratorTest` / `SubtitleWorkspaceFilePathsTest` / `VideoTaskServiceTest`（→ 新写 `SubtitleTaskServiceTest`）。

**本步收尾判据**：`clean test-compile` 过 + 全量测试绿 + `grep -rn "VideoTask\|TaskSessionRegistry\|TaskStageEvent\|WorkspaceKey\|TaskProgressNotifier"` 在 `src/main` 为空。

### 5.7 `WorkspaceDir` / `MediaInput` / 目录管理器换形（原 4.1–4.3 移入）

**与 `VideoTaskService` / 老 `SubtitlePipeline` 的死同步发生**：它们的调用点在本步消失，换形才不需要写桥接。

1. **`WorkspaceDir` 搬去 `domain/workspace/model/`**（原 4.1）：工厂 `of(label, key)` → **`of(SourceKey)`**，值 = `sanitizeName(sourceKey.value())`；`sanitizeName` **原地保留**。**javadoc 补两条**（design §8.1）：① `sanitizeName` 在 `source_key` 值域上**单射** —— `|` 被换成 `_`，而 B 站 `bvid` 的字符集是 `[0-9A-Za-z]`（不含 `_`）、`p` 是纯数字，上传路线是 64 位 sha256 十六进制，所以 `BV11QMd6WEJp|1` → `BV11QMd6WEJp_1` 唯一；② **80 字符截断会破坏单射** —— 当前两种 `source_key` 都 ≤ 64（bvid|p 约 14、sha256 = 64），不会触发，将来加更长的 `source_key` 格式时**先解决截断**。老 `domain/shared/model/WorkspaceDir.java` 删。
2. **`MediaInput` 瘦身**（原 4.2）：删 `getContentKey` / `dirLabel` / `ofTaskInput`；`InWorkspace` 去掉 `key` 与 `dirLabel` 两个组件（变体成 `RemoteUrl(String)` / `LocalPath(Path)` / `InWorkspace(Path)`）。**`LocalPath` 保留**（`LocalFileMediaAcquirer` 支持它），它没有生产构造点这件事记进欠账（既存事实，本段不扩范围去删）。
3. **`WorkspaceDirectoryManager` 换签名**（原 4.3）：`Path ensureWorkspaceDirectory(SourceKey key)`（原 `(String label, WorkspaceKey key)`）；`NioWorkspaceDirectoryManager` 内部改 `getWorkspaceRoot().resolve(WorkspaceDir.of(key).value())`。
4. **连带**：`WorkspaceKeyGenerator` 在本步随老服务一起删（原 4.4 说「先留着」，但它在本步已零调用方）；`WorkspaceKeyGeneratorTest` 一并删。

---

## 步骤 6：改名让名 + 包结构重排（T8）

**两件都是纯机械、不碰行为的活**，合并成一步：它们碰的是同一批文件，分开做等于走两遍。

**顺序纪律**（N5）：删除集已在步骤 5 落定，这一步只碰存活集。

### 6.1 改名让名

| 旧名 | 新名 | 成本 |
|---|---|---|
| `SubtitleArtifact`（record，`domain/subtitle/model/common/`） | `SubtitleStageOutput` | 2 个源文件（`SubtitlePreparationCoordinator` + 它的测试） |
| `AudioArtifact`（record，`domain/media/model/`） | `AudioStageOutput` | 11 文件 / 41 处（7 main + 4 test） |

**假朋友**：`DashScopeAsrProvider` 那 4 处 `TaskStatus` 是 `com.alibaba.dashscope.common.TaskStatus`（阿里 SDK 同名枚举）—— **显式排除，改名走 IDE rename symbol，不做文本替换**。

`TaskStatus` / `TaskInputType` 的退场已在步骤 5 随 `domain/task/` 整包完成，这一步**不再有** `TaskStatus` → `TaskRunStatus` 的机械改名要算账（存活集里那 165 处大部分落在已删文件上）。

**判据**：`clean test-compile` 过 + 全量测试绿 + `grep -rn "AudioArtifact\b" src/main` 为空。

### 6.2 包结构重排（T8）

**domain：拆成六条轴平铺**

| 动作 | 内容 |
|---|---|
| `domain/task/` 补充 | 从 `domain/subtitle/` 搬入 `SubtitleTask` / `TaskRun` / `TaskRunType` / `TaskRunStatus` / `SubtitleTaskRepository` / `TaskRunRepository`（注意 `TaskNo` **不用搬** —— 它本来就在 `domain/task/model/`，T8 之后那个包就是任务轴） |
| `domain/subtitle/` 瘦身 | 只留字幕内容轴（`SubtitleArtifact` / `SubtitleKeywords` / `SubtitleStageOutput` / `SubtitleArtifactSpec` / `SubtitleStageName` / `SubtitleContext` / `cleanup/` `translation/` `term/` `common/` / `policy/` / 字幕侧 port） |
| `domain/intermediate/` 新建 | 从 `domain/subtitle/` 搬出 `IntermediateFile` + `model/{IntermediateFileType, IntermediateFileStatus}` + `port/IntermediateFileRepository` |

**application：编排件移到被编排件旁边**

| 动作 | 内容 |
|---|---|
| `SubtitlePipeline` 移包 | `application/task/pipeline/` → `application/subtitle/pipeline/`（与三个 Coordinator 同包）；`application/task/pipeline/` 随之消失 |

**三条纪律**

1. **`package-info.java` 跟着走**：搬包时它必须一起搬，内容按新包重写（`domain/task/` 的旧 `package-info` 是照 `VideoTask` 写的，别留）。
2. **不是文本替换**：搬包靠 IDE 的 move/rename（它会把引用一起改）。手工改 import 时**逐文件核对** —— 尤其 `SubtitleStageOutput` 与 `AudioStageOutput` 这两个**形状几乎一样**的 record（都是「阶段产出描述」，6.1 刚改完名），别把两者的引用改串。
3. **判据是「一一对应」**：`application/X` 与 `domain/X` 逐对核（`task` / `subtitle` / `media` / `transcription`），这条是 AC12。

**一条明确不做**：**别顺手把异常类「统一」到一个 `application/exception/` 里。**

- 现状是**定义就近、处理集中**：异常类跟着**抛它的那条轴**走（`domain/exception/UnsupportedLanguageException` 由 `SupportedLanguage.parse` 抛、`application/task/exception/` 两个由任务用例抛），处理集中在 `web/controller/ApiExceptionHandler`。这是主流形状，不是「没统一」。
- **`application/exception/` 在本段会自己消失**：它唯一的住户 `InvalidWorkDirectoryException` 只被 `SubtitleWorkspaceFilePaths` 抛，而后者零生产调用点、随旧栈一起删（退场清单里已记）。别为它保留空包。
- **上提的判据是「跨轴复用」**：只有某个异常同时被两条轴抛时才上提。今天没有这种。集中放会让那个包看不出「哪条轴抛哪些」，且与 T8 的「按轴分」相抵。

**判据**：`clean test-compile` 过 + 全量测试绿 + `find src/main -type d -name pipeline` 只剩三个（`application/{media,subtitle,transcription}/pipeline`）。

---

## 步骤 7：启动收口

新增 `application/task/TaskRunStartupRecovery.java`：

```java
@Component
public class TaskRunStartupRecovery implements ApplicationRunner {
    // 构造注入 TaskRunSupervisor
    @Override public void run(ApplicationArguments args) { supervisor.recoverInterruptedRuns(); }
}
```

- **为什么是 `ApplicationRunner`**：Spring Boot 标准做法，启动日志里看得见。**代价写进类注释**：连接器此刻已在监听，窗口毫秒级 —— 撞上的请求会看到一行其实已死的 `RUNNING`（提交拿到 409，重试即可）；单实例假设下接受。
- **不把它塞进 `TaskRunSupervisor`**：机制类不该兼做引导。
- **冒烟**（步骤 9 一起做）：手工把一条 `task_run` 的 `status` 改成 `RUNNING`、一条 `subtitle_artifact` 改成 `CREATING`，重启 → 断言两者分别变 `INTERRUPTED` / `FAILED`、对应 `subtitle_task.updated_at` 变了、日志有「收掉 N 行」。

---

## 步骤 8：文档与规范同步

### 8.1 父任务 `design.md`（需求 47）

**它是权威，S2 的实施者会照它写代码**，所以必须与本次讨论对齐。按本仓订正惯例**直接重写为现状、不留旧说法**：

| 位置 | 改什么 |
|---|---|
| §3 让名旧类表下面那句「`TaskStageEvent` 的替代者 = 连接建立即推 DB 快照，落位到 application 的 SSE 侧」 | 补实际落点：快照组装 = `TaskRunProgress.of` 静态工厂；广播 = `TaskProgressBroadcaster` 端口；并写明**中立注册表退场**（`TaskSubscriptionRegistry` / `Subscription` / `TaskProgressNotifier` / `TaskSseConnectionManager`） |
| §6 C57 | 补端点形状 `GET /api/tasks/{taskNo}/runs/{runNo}/events`、三种结果（404 / RUNNING 开流 / 已终态 204）、消息为 `TaskRunProgress` sealed 三变体、订阅键 `RunRef` |
| §6 C53 | 补「`artifacts` 字段随扁平五字段一起删」（产物清单归 S3 的 `/artifacts`） |
| §6 C56 | 补「409 响应体不含文案，与 202 同形状」 |
| §9 退场清单 | 补四个类（`TaskSubscriptionRegistry` / `Subscription` / `TaskProgressNotifier` / `TaskSseConnectionManager`），并把 `TaskStageEvent` 那行的「被谁取代」改成实际落点 |

**只改终态描述，不搬理由** —— design 自己的章程是「理由给一行 + 编号引用」，T4–T7 的论证留在本任务的 `prd.md`。

### 8.2 `directory-structure.md`（需求 48）

新增一条落点规则：**端口实现落 `infrastructure/`；签名需要 HTTP 类型时落 `web/<构件桶>/`**。理由写一行：地基「HTTP 类型只在 `web/`」优先，统一性「端口落点」让一步。同时把 `subscribe/` 那个「中立机制包」的旧说法按现状订正（该包已不存在）。

### 8.3 `database-guidelines.md`（需求 49）

表清单从六张改成**五张**（`task` 已删）；`subtitle_keyword` 的锚点列（`task_id`）与新增 `updated_at` 一并订正。**spec 是现状文档，直接重写为现状。**

> 步骤 8 与代码无耦合，**单独可回滚**（改了不合适就单独退这几个文件，代码不用动）。

---

## 步骤 9：全量验证 + 欠账核对

```bash
# 1) 判编译 —— 增量编译会给假绿，一律 clean
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 2) 全量测试 —— 先删陈旧报告，否则 surefire 会数进幽灵测试
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/target/surefire-reports/*.xml

# 3) 起服务（真库跑 schema.sql）；起两次，验幂等
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q -Dmaven.test.skip=true spring-boot:run
```

**注意**：`spring-boot:run` 绑 `test-compile`，测试源码红着根本起不来 —— 只跑 main 要加 `-Dmaven.test.skip=true`（`-DskipTests` **不够**）。收工按 PID `taskkill`（`TaskStop` 只杀 mvn 包装进程）。

**手工验收（AC1–AC11 逐条）**

```bash
# AC2 提交 / AC1 身份键去重
curl -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV11QMd6WEJp?spm_id_from=333.999"}'
#   → 202 {"taskNo":"…","runNo":1}
curl -X POST localhost:8080/api/tasks … -d '{"url":"…?vd_source=abc"}'   # 同一个视频 → 同一个 taskNo、runNo:2
# AC2 并发：3 个并发打同一个【全新】URL（2 个复现不出 C63 的锁定读需求）
# AC5 SSE：三种结果各打一次
curl -N localhost:8080/api/tasks/<taskNo>/runs/1/events     # RUNNING → 首条是快照
curl -i localhost:8080/api/tasks/<taskNo>/runs/999/events   # → 404
curl -i localhost:8080/api/tasks/<taskNo>/runs/1/events     # 跑完再打 → 204
# AC7 契约
curl localhost:8080/api/tasks/<taskNo>          # 身份 + lastRun，无 artifacts / 无扁平五字段
# 绕道看产物（本段没有取产物的 HTTP 手段，父 prd 已记账）
ls backend/data/works/BV11QMd6WEJp_1/
#   source.mp4  audio-*.m4a  original-segments.json  original-subtitles.srt
#   clean-segments.json  clean-subtitles.srt  context.json
# AC3 互斥不泄漏
#   SELECT count(*) FROM task_run WHERE status='RUNNING';   → 0
# AC8 表结构
#   SHOW CREATE TABLE subtitle_keyword;   → task_id BIGINT + updated_at + uk_subtitle_keyword_task_id
```

**然后逐条核对下面的欠账表 → 报告 → 停等审查，不自行提交/合并。**

---

## 回滚点

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| **步骤 4 之后** | 新件（身份键 / run 机制 / SSE）全在但未接线；coordinator 已收 `workDir`；**老栈照常跑** | 零代价 —— 这是最干净的落点 |
| **步骤 5 之后** | 新栈在跑，旧栈已删 | 零代价；但库里的 `task` / 旧 `subtitle_keyword` 要手工 `DROP`（见 5.1 的说明） |

**步骤 5 内部不许停**（中间是红的）。步骤 6 / 7 / 8 各自独立可回滚。

**彻底回退**：本段无数据迁移（本地存量可弃，§9.4i），所以回退 = `git revert` + 手工 `DROP TABLE workspace, subtitle_task, task_run, subtitle_artifact, intermediate_file, subtitle_keyword`，然后从零重建 `backend/data/works*`。

---

## 欠账清单（本段结束时哪些东西还没到位、交给哪一段）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | 产物**不落 `subtitle_artifact` 表**：主管线与后置会话照旧只写文件，DB 里零记录 | **S3** | 表读写 + 换版落定（C41 四步）+ `/artifacts` 端点 + 下载端点按 `type`+`file`+`?lang=` 重建 |
| 2 | `/keywords` 的 `fingerprint` **仍从 `clean-segments.json` 的文件内容算** | **S3** | 改从 `CLEANED` 行的 `keywordsFingerprint` 读（C33） |
| 3 | RECLEAN 的 artifact 换版（W5）+ 下游级联 + 两种终态；TRANSLATE 的 W4 两行换版 | **S4 / S5** | 那两段的核心交付 |
| 4 | 后置会话的前置判据**仍是文件判据**（`original-segments.json` / `clean-subtitles.srt` 在不在），不是「有 `READY` 的 `ORIGINAL` / `CLEANED`」 | **S3 起** | 等 artifact 表接上再换 |
| 5 | 复用判据仍是「文件在不在」，`intermediate_file` 零登记 | **S6** | C62 登记 + 判据改造 |
| 6 | 内容指纹两列（`workspace.duration_ms` / `published_at`）**恒为 `NULL`** —— 提交路径手里只有 URL，拿不到指纹（C29） | **S6** | 指纹探测阶段（C47/C48）+ C31 级联 |
| 7 | `POST /api/maintenance/reconcile` 不存在 | **S7** | 只读对账四类 |
| 8 | 网络调用（LLM / ASR / 术语查证）无超时 | **S8** | C46 订正 |
| 9 | 前端**一定点不通**（`TaskResponse` 形状变了、下载端点没了、SSE 路径变了） | **另开任务**（C60） | 后端全部段做完再动 |
| 10 | RECLEAN 的**进度百分比沿用主管线区间**（从 `CLEAN_SUBTITLES` 的中段起，不是 0→100）；TRANSLATE 的 progress 是裸 0→100（它不在主管线权重表里） | **未排期** | 展示用数值，不改；记在这里免得被当 bug |
| 11 | `MediaInput.LocalPath` **没有生产构造点**（既存事实，本段不扩范围去删） | **未排期** | 真要删是独立一件事 |
| 12 | 排队期间 run 已是 `RUNNING` 但 `current_stage` 为 `null`（前端看到「运行中但没有阶段」） | **未排期** | 限流 ≠ 互斥的必然结果，记账即可 |
| 13 | `SseTaskProgressBroadcaster` 是本仓**第一个实现在 `web/` 的端口** | **已记** | `directory-structure.md` 的规则与它同时落地（步骤 8.2） |
| 14 | `SubtitlePipeline` **一个类扛三种 run**（`run` / `regenerate` / `translate`） | **未排期** | 项目所有者已明确方向：将来按 run 类型拆成三条线。**本段不拆**（T8 只把它移到被编排件旁边）—— 拆的时候是同包内拉直四条线，成本比现在低。记在这里免得被当漏做 |

**收尾动作**：跑完步骤 9 → 逐条核对上表 → 报告（含「204 的返回类型怎么落地的」这个实施时定的选择）→ **停等审查，不自行提交/合并**。
