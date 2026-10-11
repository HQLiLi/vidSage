# 执行计划：状态迁移 × 广播收口

> 需求见 [prd.md](./prd.md)，设计与保真清单见 [design.md](./design.md)。**开工前先把这两份读完。**

## 执行前须知

- **分工（沿用既有口径）**：批次 1–3 是**主源码，项目所有者写**（每步先问再动，示例代码就在本文，不要贴会话）；
  批次 0 / 4 / 5 是**文档与测试，AI 写**。
- **门**：每批做完立刻停，等审查。提交 / 推送 / 合并 / 归档各是一道独立的门，沉默 ≠ 批准。
- **环境坑**：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn ...`（Git Bash 下必须显式给 JDK 21）；
  量测试前先 `rm -rf backend/target/surefire-reports`（陈旧报告会冒充本次结果）；
  **改结构 / 改名后必须 `mvn clean`**；证据目录用本任务下的 `evidence/`，不要用 `/tmp`。
- **中间态**：批次 3 结束时**主源码可编译**、测试会编译不过（测试夹具还按旧构造传参）——**属预期，别顺手改**，
  批次 4 统一处理（与 10-09 同一处置）。

## 步骤 0 — 记录基线（执行前必做）

1. `git rev-parse HEAD` 记进本文「实测记录」。
2. `rm -rf backend/target/surefire-reports && cd backend && JAVA_HOME=/f/java_soft/java21 mvn clean test`
   → 记用例总数与失败数（**这是 AC6 的基线**）。
3. **SSE 基线**：本任务改动前，在 `dev` 当前 HEAD 上抓一次参照侧。
   - 冒烟：`JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`
     → `POST /api/tasks {"url": …}` → 立刻 `curl -sN /api/tasks/{taskNo}/events` 抓 SSE → 归一化
     （剥掉 taskNo 与本地路径）落 `evidence/ref-sse.txt`。
   - 可先核实历史基线是否仍等价（`10-08-pipeline-executor-split/evidence/ac4-comparison.md` 与
     `10-09-workspace-port-inversion/evidence/behavior-fidelity-2026-10-09.md` 都记着零差异）；
     **但本次仍要自己抓一次参照侧**——同机同轮抓的两个文件才排得掉环境差异。
   - 收工按端口 PID `taskkill //PID <pid> //F`（`TaskStop` 杀不掉子 JVM）。

## 批次 0 — 判据先行（纯文档，不碰代码）

设计 §七 的 **#1–#4** 四处先落地，理由同 10-09：先定判据再搬代码，否则代码会被旧判据评判。

1. `spec/backend/directory-structure.md` 二级包表：`application/<上下文>/` 的桶列加
   `message/`（应用消息）与 `notify/`（进度通知协作件）；「根留什么」列改成**只留 `@Service` 服务门面**。
2. 同表·构件说明：「机制/协作件」档的现行成员补 `TaskProgressNotifier`。
3. 同表·命名约定：登记 `-Notifier`（本仓首例）与桶名 `notify/`，写明角色词含义。
4. 同表·契约类的位置：`TaskProgressMessage` 的位置改为 `application/task/message/`。

**做完停下交审查**——这是本次唯一一处**改动既有判据**的地方（根包的构成规则收窄），改错了后面会松一片。

## 批次 1 — 消息搬家（纯搬运，零行为变化）

搬完必须**全绿**——本批不含任何行为改动。

1. `git mv backend/src/main/java/com/vidsage/application/task/TaskProgressMessage.java \
        backend/src/main/java/com/vidsage/application/task/message/TaskProgressMessage.java`
2. 改它的 `package` 声明为 `com.vidsage.application.task.message;`（**类体一字不改**）。
3. 改 import 路径（**只改路径，不改代码**）：
   - main：`application/task/pipeline/SubtitlePipeline.java`、`application/task/subscribe/TaskSubscriptionRegistry.java`、
     `application/task/VideoTaskService.java`、`web/controller/TaskController.java`、`web/sse/TaskSseHub.java`
   - test：`application/task/pipeline/SubtitlePipelineTest.java`、`application/task/VideoTaskServiceTest.java`、
     `web/controller/TaskControllerTest.java`、`web/sse/TaskSseHubTest.java`
   > **别误读**：本批是**纯搬运**，`SubtitlePipeline` 此刻**仍持有** `TaskSubscriptionRegistry` 字段——
   > 所以它的 import 路径也得跟着改。**批 3 才把那个字段连同构造参数一起换成 `TaskProgressNotifier`**
   > （design §一「之后」那棵树才是终态）。
4. 新增 `application/task/message/package-info.java`（判据见 spec，口径：装 application → web 的进度消息；
   禁 HTTP 类型、禁编排）。
5. 验证：`mvn clean test` 全绿，用例数与步骤 0 相同。

## 批次 2 — 新增 `TaskProgressNotifier`（只建不接）

`application/task/notify/TaskProgressNotifier.java`（`@Component`）。**本批不接任何调用点**，
所以行为零变化，测试应仍全绿。

```java
package com.vidsage.application.task.notify;

import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.policy.TaskStageEvent;
import com.vidsage.domain.task.port.VideoTaskRepository;
import org.springframework.stereotype.Component;

/**
 * 任务进度通知器
 */
@Component
public class TaskProgressNotifier {
    private final VideoTaskRepository videoTaskRepository;

    private final TaskSubscriptionRegistry taskSubscriptionRegistry;

    public TaskProgressNotifier(VideoTaskRepository videoTaskRepository, TaskSubscriptionRegistry taskSubscriptionRegistry) {
        this.videoTaskRepository = videoTaskRepository;
        this.taskSubscriptionRegistry = taskSubscriptionRegistry;
    }

    /**
     * 推进任务，先将任务状态落库再广播
     *
     * @param videoTask 任务
     * @param event     阶段事件
     */
    public void saveCurrentStageAndBroadcast(VideoTask videoTask, StageEvent event) {
        videoTask.updateStage(event.stage());
        videoTaskRepository.update(videoTask);
        broadcast(videoTask, event);
    }

    /**
     * 翻译阶段推进，只广播
     *
     * @param videoTask 任务
     * @param event     阶段事件
     */
    public void broadcastTranslationStage(VideoTask videoTask, StageEvent event) {
        broadcast(videoTask, event);
    }

    /**
     * 任务完成
     */
    public void completeTask(VideoTask task) {
        videoTaskRepository.update(task);
        broadcastTerminal(task, TaskStageEvent.from(task));
    }

    /**
     * 完成翻译任务
     *
     * @param task 任务
     */
    public void completeTranslation(VideoTask task) {
        videoTaskRepository.update(task);
        broadcastTerminal(task, TaskStageEvent.fromTranslation(task));
    }

    /**
     * 组装 SSE 连接建立时的<b>快照事件</b>（design.md「SSE 契约」：DB 快照即权威，事件不落库）：
     * 只组装不发送——发送由 web 层决定，本方法不碰注册表、不落库。
     *
     * @param task 任务实例
     * @return 快照事件
     */
    public TaskProgressMessage createSnapshot(VideoTask task) {
        return new TaskProgressMessage(task.getTaskNo().value(), task.getStatus(),
                TaskStageEvent.from(task), task.getTranslationStatus());
    }


    /**
     * 广播指定任务的状态
     *
     * @param task  任务
     * @param event 阶段进度
     */
    private void broadcast(VideoTask task, StageEvent event) {
        String taskNo = task.getTaskNo().value();
        taskSubscriptionRegistry.broadcast(taskNo,
                new TaskProgressMessage(taskNo, task.getStatus(), event, task.getTranslationStatus())
        );
    }

    /**
     * 终态广播：告诉每个订阅者订阅结束。
     * 在广播结束后清除该任务的所有订阅关系
     *
     * @param task  任务
     * @param event 阶段进度
     */
    private void broadcastTerminal(VideoTask task, StageEvent event) {
        try {
            broadcast(task, event);
        } finally {
            taskSubscriptionRegistry.complete(task.getTaskNo().value());
        }
    }


}
```

外加 `application/task/notify/package-info.java`（写明与 `subscribe/` 的分工：一个管「谁能收」，
一个管「什么时候送、送之前先落库」）。

验证：`mvn clean test` 仍全绿（本类暂无调用者，只是多了一个 Bean）。

## 批次 3 — 调用点改造（本批改行为路径，逐字保真）

### 3.1 `SubtitlePipeline`：六个调用点 + 删两个私有方法

- 字段与构造参数：`TaskSubscriptionRegistry subscriptionRegistry` → **`TaskProgressNotifier taskProgressNotifier`**
  （grep 核实：注册表字段只服务那 5 处广播，没有别的用途）。
- 两个 listener：**日志原样留在调用点**，三行/一行换成一次调用：

```java
    private ProgressListener progressListener(VideoTask task) {
        return event -> {
            log.debug("任务阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo().value(), event.stage(), event.progress(), event.state(), event.message());
            taskProgressNotifier.saveCurrentStageAndBroadcast(task, event);
        };
    }

    private ProgressListener translationProgressListener(VideoTask task) {
        return event -> {
            log.debug("翻译阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo().value(), event.stage(), event.progress(), event.state(), event.message());
            taskProgressNotifier.broadcastTranslationStage(task, event);
        };
    }
```

- `runPipeline` 成功段：

```java
            prepareAndClean(task);
            task.succeed();
            taskProgressNotifier.completeTask(task);
            log.info("任务执行成功: taskNo={}, cost={}ms", task.getTaskNo().value(), System.currentTimeMillis() - start);
```

- `runTranslationPipeline` 成功段：

```java
            translationStage.translate(input, translationProgressListener(task));
            task.translationSucceed();
            taskProgressNotifier.completeTranslation(task);
            log.info("任务翻译完成: taskNo={}, cost={}ms", task.getTaskNo().value(), System.currentTimeMillis() - startTimeMs);
```

- `failTask` / `failTranslation`：**`task.fail(...)` / `task.translationFail(...)` 必须留在 try 内**
  （design §五 第 4 条：它抛 ISE 被同一条 catch 吞掉是既有语义），日志与 catch 文案一字不改：

```java
    private void failTask(VideoTask task, Exception cause) {
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        // 文案不带阶段名：事件与快照的 stage 字段已经给了，中文句子里再嵌一遍标识符没有必要
        String message = "任务执行失败：" + reason;
        try {
            task.fail(message);
            taskProgressNotifier.completeTask(task);
            log.error("任务执行失败: taskNo={}, status={}, message={}", task.getTaskNo().value(), task.getStatus(),
                    message, cause);
        } catch (RuntimeException e) {
            log.error("任务失败状态落库失败，任务可能停留在 RUNNING: taskNo={}, reason={}",
                    task.getTaskNo().value(), e.getMessage(), e);
        }
    }
```

```java
    private void failTranslation(VideoTask task, String reason, Exception cause) {
        String message = "翻译失败：" + reason;
        try {
            task.translationFail(message);
            taskProgressNotifier.completeTranslation(task);
            if (cause == null) {
                log.warn("任务翻译失败: taskNo={}, message={}", task.getTaskNo().value(), message);
            } else {
                log.error("任务翻译失败: taskNo={}, status={}, message={}",
                        task.getTaskNo().value(), task.getStatus(), message, cause);
            }
        } catch (RuntimeException e) {
            log.error("翻译失败状态落库失败，任务可能停留在翻译 RUNNING: taskNo={}, reason={}",
                    task.getTaskNo().value(), e.getMessage(), e);
        }
    }
```

- **删掉** `broadcastTerminal` 与 `broadcastTranslationTerminal` 两个私有方法（已搬进 notifier）。
- 保留 `repository` 字段：`findByTaskNo` 与三处**无广播**的落库（`translateTask` 幂等收敛、
  `translationStart`、`start`）仍在用。

### 3.2 `VideoTaskService`：快照改走 notifier

```java
    private final VideoTaskRepository repository;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;
    private final SubtitlePipeline pipeline;
    private final TaskProgressNotifier taskProgressNotifier;   // 新增（构造参数追加在最后）

    public TaskProgressMessage snapshot(String taskNo) {
        VideoTask task = loadTask(taskNo);
        return taskProgressNotifier.createSnapshot(task);
    }
```

`snapshot` 的 Javadoc 保留，只把「组装」那句的主语改成 notifier；`TaskStageEvent` 的 import 删掉。

### 3.3 本批的验证

- `mvn clean compile` 应通过；`mvn clean test` **会编译不过**（测试夹具仍按旧构造传参）——**属预期，停在这里交审查**。

## 批次 4 — 测试（AI 执行，按批次 3 写出来的代码改）

- `SubtitlePipelineTest`：fixture 加**一个真 notifier**（喂**同一个** `repository` mock 与**同一个**
  `TaskSubscriptionRegistry` 实例）并换构造参数 → `inOrder` / `verify` 的落库与广播断言应原样继续成立；
  **断言一个字不放宽**。
- `VideoTaskServiceTest`：`new VideoTaskService(...)` 补第 4 个参数（真 notifier：repository mock + 新 registry）。
- 新增 `application/task/notify/TaskProgressNotifierTest`（design §六 列的五个用例）。
- import 改包类：`TaskSubscriptionRegistryTest` / `TaskControllerTest` / `TaskSseHubTest`。
- 验证：`rm -rf backend/target/surefire-reports && mvn clean test` 全绿，用例数 = 步骤 0 基线 + 新增用例数。

## 批次 5 — 收尾：规范订正 + 证据 + 全量验证

1. spec 订正清单 §七 的 **#5–#9**（`application/task/package-info.java`、两个新 `package-info`、
   `TaskStageEvent` 类注释里的调用者改名、`TaskResponse:12` 的路径）。
2. **AC2/AC3 的 grep 证据**落 `evidence/grep-<日期>.txt`：`new TaskProgressMessage(` 命中清单
   （含测试夹具豁免说明）、`SubtitlePipeline` 零 `TaskSubscriptionRegistry`、`broadcast(`/`complete(` 只在 notifier。
3. **AC5 双侧对照**：同机两轮——`dev` 基线侧（步骤 0 抓的 `ref-sse.txt`）与分支侧
   （同命令抓 `branch-sse.txt`，归一化后逐字节 `diff`）。覆盖两个场景：
   - **场景 A 在线**：提交后立刻订阅，抓全程 14 格事件流；
   - **场景 B 重连快照**：终态后**重新订阅**，抓首条快照——至少覆盖 `SUCCEEDED 无翻译`、
     `翻译 RUNNING`、`翻译 SUCCEEDED` 三条（这三条正是"在线与快照同源"的命门），
     `FAILED` 可选。差异落差异栏，格式照 `10-08-pipeline-executor-split/evidence/ac4-comparison.md`。
4. 写 `evidence/completion-report.md` + 更新本文「实测记录」。
5. 全量：`mvn clean test` 全绿；AC1–AC7 逐条对账打勾（表述过严的按既有惯例加删除线 + 日期修正）。

## 回滚点

| 到哪 | 回滚方式 |
|---|---|
| 批次 0 后 | `git checkout -- .trellis/`（只动了 spec 与任务工件） |
| 批次 1 后 | `git checkout -- backend/ && git status` 确认消息类回到原路径（纯搬运，回滚无损） |
| 批次 2 后 | 删 `notify/` 两个文件即可（无调用者） |
| 批次 3 后 | `git checkout -- backend/`（本批是唯一改行为路径的批次，回滚即回到批次 2 的状态） |

## 实测记录

（执行期填：每批完成后记 `mvn` 结果、用例数、证据文件名；收尾时补完成报告链接）

### 步骤 0 基线（2026-10-10）

- HEAD：`dd015fd3aaee6371c1e4e94965f18be537c36947`（dev，与移交文档一致）
- `mvn clean test`：**482 用例 / 0 失败 / 0 错误 / 1 跳过，BUILD SUCCESS**（AC6 基线）
- SSE 参照侧：`evidence/ref-capture-2026-10-10.md`——场景 A 14 格与归档基线剥行尾后逐字节零差异；
  场景 B 三条命门快照（SUCCEEDED 无翻译 / 翻译 RUNNING / 翻译 SUCCEEDED）全齐，翻译为真实调用。
  **注意**：抓取时树上已有批 1/2（纯搬运 + 无调用者 Bean），批 3 未接线，行为等价性论证见该文件。

### 批次 1–3（2026-10-10/11，项目所有者手写）

- 批 3 审查通过（2026-10-11）：保真清单 10/10；主源码编译零错误；测试编译挂 2 处预期夹具错误（属计划内中间态）。
- 方法名拍板以代码为准：`saveCurrentStageAndBroadcast` / `broadcastTranslationStage` / `completeTask` / `completeTranslation` / `createSnapshot`——design/implement 已同步，旧提案名作废。
- P1/P2 Javadoc 过时问题项目所有者已修；P3 判据注释按所有者意见不加。

### 批次 4 测试（2026-10-11，派发 trellis-implement）

- `mvn clean test`：**488 用例 / 0 失败 / 0 错误 / 1 跳过，BUILD SUCCESS**（= 基线 482 + 新增 6，跳过 1 同基线）。
- 新增 `application/task/notify/TaskProgressNotifierTest` 6 用例（design §六 五口径全覆盖，`completeTask`/`completeTranslation` 分列）。
- 夹具改动仅 2 处：`SubtitlePipelineTest` 第 5 参换真 notifier（复用同 registry/repo 实例）、`VideoTaskServiceTest` 补第 4 参。
- 断言零改动零删除；`TaskControllerTest` AC2 豁免构造未触碰。
- 台账：`research/execution-log.md` #1（423 s / 84474 tokens / 26 tool_uses）。
- 检查（trellis-check 三读面 + 命令关）：**全 PASS、零改动**；红线核验既有断言 diff 干净（改动行只有 import/构造参数/注释）；
  50 个 surefire 报告 mtime 全部晚于源码最后改动，无陈旧混入。台账 #2（512 s / 69078 tokens / 28 tool_uses）。
- **批 5 备料（check 上报，非本批问题）**：① AC2 豁免比 §九.5 记的宽——测试侧 `new TaskProgressMessage(` 共
  17 处 / 5 文件（不只 TaskControllerTest:183），evidence 要全列；② AC3 grep 证据要区分
  `TaskSseHub:122 target.complete()`（SseEmitter 同名方法，非注册表）。

### 批次 5 收尾（2026-10-11，主线 + 派发 5a/5b）

- **spec §七 #5–#9 全部落地**：三个 package-info（5a，子代理 API 402 中止但文件已写完、主线逐字验收）
  + `TaskStageEvent` 调用者、`TaskResponse` 路径注释各 1 行（5b）。
- **AC2/AC3 grep 证据**：`evidence/grep-2026-10-11.txt`（豁免 17 处 / 5 文件全列；SseEmitter 同名注明）。
- **AC5 双侧对照 PASS**：`evidence/ac5-comparison.md`——场景 A 14 格 data 行逐字节全同（仅 `:ping` 落点差，
  ac4 同款裁定）；场景 B 三条首条快照全同；翻译增量补抓 7 格、重叠段全同。
  过程三次抓取事故（订阅晚到 / 工作区路径错致断点秒过 / target 竞态）与处置如实记在 completion-report。
- **AC6 最终轮**：`mvn clean test` **488 / 0 / 0 / 1，BUILD SUCCESS**。
- **AC1–AC7 对账全 PASS**：`evidence/completion-report.md`。
