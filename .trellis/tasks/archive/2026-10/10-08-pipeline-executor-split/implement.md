# SubtitlePipeline 执行模型拆分 — 执行步骤

前置：`dev` 干净；`task.json` 的 `base_branch = dev`（已核对）。所有命令在 Git Bash 下、工作目录 `f:/personal_project/vidSage`。

产物证据统一落 `.trellis/tasks/10-08-pipeline-executor-split/evidence/`（**别用 `/tmp`**——Git Bash 的 `/tmp` 与 Windows 程序不通用）。

---

## 实际落地与本文示例的差异（2026-10-08，写成现状）

步骤 0–7 已执行完。**下面步骤 2–5 里的示例代码以实际落地为准**，差异如下（正文不逐处回改，避免同一份代码出现两个版本）：

| 处 | 本文示例 | 实际落地 |
|---|---|---|
| 步骤 3 字段名 | `executor` | `executorService` |
| 步骤 3 虚拟线程执行器 | 抽成 `private static virtualThreadExecutor()` | **内联**在委派实参里（`this(properties.maxConcurrent(), Executors.newThreadPerTaskExecutor(...))`） |
| 步骤 3 生产构造器 | `public SemaphoreTaskConcurrencyGate(TaskProperties)`（无注解） | `public` + **`@Autowired`**——两个构造器时 Spring 必须能被指定用哪个，否则启动期 `BeanInstantiationException: No default constructor found`（`VidSageApplicationTests` 会当场红）；**必须 `public`**，跨包的 `SubtitlePipelineTest` 要构造真闸门 |
| 步骤 4 管线字段名 | `gate` | `concurrencyGate` |
| 步骤 4.4 | `long start` | `startTimeMs`，且**必须在 `gate.submit` 之前**求值（写进 lambda 里 = 拿到许可后才求值 = 翻译 cost 丢掉排队时间） |
| 步骤 5 | 管线侧 2 条 + 实现侧 2 条 | 管线侧 **3** 条（多一条 `execute` 经闸门跑通管线）+ 实现侧 **3** 条（多一条 `maxConcurrent < 1` 校验文案，该校验原零覆盖）；实现侧修正见下文步骤 5 |

---

## 步骤 0 — 开分支

```bash
git checkout -b feature/pipeline-executor-split   # 从 dev 起
git branch --show-current                         # 期望 feature/pipeline-executor-split
```

## 步骤 1 — 先改判据（不动代码）

改文档，落 design §六 的口径。这一步单独提交，后面搬代码时不会出现「中途既不符旧规范也不符新规范」。

**`.trellis/spec/backend/directory-structure.md`**

1. 二级包表加一行（放在 `infrastructure/fs/` 行之后）：

```
| `infrastructure/concurrency/` | 进程内执行机制的通用机制类 | 任务并发闸这类**有状态、不面向业务契约**的机制类 | 出站端口适配（那属于 `provider/`）、业务编排 |
```

2. 「端口归属判据」节末尾补一个本例：

```
- 例（2026-10-08）：`TaskConcurrencyGate` → application 端口。判据：技术词汇（并发配额），且兜底问
  「删掉它业务规则还成立吗」→ 成立。
```

**`.trellis/spec/backend/` 不动依赖表**（层名与方向都没变）。

## 步骤 2 — 建端口

新建 `backend/src/main/java/com/vidsage/application/task/port/TaskConcurrencyGate.java`：

```java
package com.vidsage.application.task.port;

/**
 * 任务执行闸门：把一段活提交到别的线程去跑，同时只放行 vidsage.task.max-concurrent 个。
 *
 * <p>拿到执行许可之前不跑 body——所以「排队中」这件事是有保证的（任务停在 PENDING，不会被提前
 * 迁到 RUNNING）。等许可时被打断该怎么办，两个调用方各有各的答案（主管线保持 PENDING 静默退出、
 * 翻译要落失败并收口广播），所以那件事不做在实现里，由调用方通过 onQueueInterrupted 传进来。
 */
public interface TaskConcurrencyGate {

    /**
     * 提交一段活。
     *
     * @param body               要跑的活；拿到许可之后才跑
     * @param onQueueInterrupted 等许可被打断时的收尾动作（此时 body 一定没跑过）；语义由调用方定
     */
    void submit(Runnable body, Runnable onQueueInterrupted);
}
```

## 步骤 3 — 建实现

新建 `backend/src/main/java/com/vidsage/infrastructure/concurrency/SemaphoreTaskConcurrencyGate.java`：

```java
package com.vidsage.infrastructure.concurrency;

import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.config.TaskProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * {@link TaskConcurrencyGate} 的进程内实现：虚拟线程 + 信号量。
 *
 * <p>每段活占一根虚拟线程（阻塞等待不占平台线程），{@link Semaphore} 按 vidsage.task.max-concurrent
 * 放行，超出者就阻塞在 acquire 上排队——排队的实体是那根虚拟线程本身，没有额外的队列数据结构。
 * 注意信号量是**非公平**的（JDK 默认），排队**不保证先来先跑**。
 *
 * <p>拿到许可才跑 body；body 无论正常结束还是抛异常，都要释放许可（漏了就把队列永久堵死）。
 * 等许可时被打断：恢复中断位、回调 onQueueInterrupted、**不释放**（本来就没拿到）。
 */
@Component
public class SemaphoreTaskConcurrencyGate implements TaskConcurrencyGate {

    private final Semaphore semaphore;
    private final ExecutorService executor;

    public SemaphoreTaskConcurrencyGate(TaskProperties properties) {
        this(properties.maxConcurrent(), virtualThreadExecutor());
    }

    /** 包内可见：测试注入自控执行器（带记录 ThreadFactory 的那种，好抓住排队线程去 interrupt）。 */
    SemaphoreTaskConcurrencyGate(int maxConcurrent, ExecutorService executor) {
        if (maxConcurrent < 1) {
            throw new IllegalStateException("vidsage.task.max-concurrent 必须 ≥ 1：当前为 "
                    + maxConcurrent + "；请修正配置");
        }
        this.semaphore = new Semaphore(maxConcurrent);
        this.executor = executor;
    }

    @Override
    public void submit(Runnable body, Runnable onQueueInterrupted) {
        executor.execute(() -> {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onQueueInterrupted.run();
                return;                     // 没拿到许可，绝不 release
            }
            try {
                body.run();
            } finally {
                semaphore.release();        // body 抛异常也必须放行
            }
        });
    }

    /** 命名虚拟线程：日志与线程转储里能认出「这是哪个任务的管线」。 */
    private static ExecutorService virtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("vidsage-task-", 0).factory());
    }
}
```

`maxConcurrent ≥ 1` 的校验文案与异常类型**逐字**从 `SubtitlePipeline` 搬过来。

## 步骤 4 — 管线接上

改 `backend/src/main/java/com/vidsage/application/task/pipeline/SubtitlePipeline.java`。

**4.1 字段与 import**

- 删 `private final Semaphore semaphore;`、`private final ExecutorService executor;`
- 加 `private final TaskConcurrencyGate gate;`
- 删 import：`java.util.concurrent.ExecutorService` / `Executors` / `Semaphore`、`com.vidsage.config.TaskProperties`
- 加 import：`com.vidsage.application.task.port.TaskConcurrencyGate`

**4.2 构造函数**（校验与 executor/semaphore 创建整段删掉）

```java
    public SubtitlePipeline(VideoTaskRepository repository, WorkspaceService workspaceService,
                            SubtitlePreparationCoordinator subtitlePreparationCoordinator, SubtitleCleanupCoordinator cleanupStage,
                            SubtitleTranslationCoordinator translationStage,
                            TaskSubscriptionRegistry subscriptionRegistry, TaskConcurrencyGate gate) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.subtitlePreparationCoordinator = subtitlePreparationCoordinator;
        this.cleanupStage = cleanupStage;
        this.translationStage = translationStage;
        this.subscriptionRegistry = subscriptionRegistry;
        this.gate = gate;
    }
```

Javadoc：`@param properties` 行换成
`@param gate 执行闸门（异步提交 + 并发配额放行；排队被打断的收尾语义由本类给）`。

**4.3 `execute`**

```java
    public void execute(TaskNo taskNo) {
        gate.submit(() -> runPipeline(taskNo),
                // 排队被打断：保持 PENDING，重新提交同一输入即可再入队（内容寻址，代价低）
                () -> log.warn("任务等待执行配额时被中断，保持 PENDING: taskNo={}", taskNo.value()));
    }
```

**4.4 `translateTask` 尾部**（`log.info("翻译已触发…")` 之后）

```java
        // 成本日志的口径是「含排队时间」（与改造前一致），所以时刻在排队前就取
        long start = System.currentTimeMillis();
        gate.submit(() -> runTranslationPipeline(task, input, start),
                () -> {
                    log.warn("翻译等待执行配额时被中断: taskNo={}", task.getTaskNo().value());
                    // 翻译已置 RUNNING 才排队，被打断不能吞状态：标记失败（可重新触发），广播终态收口
                    failTranslation(task, "翻译在等待执行配额时被中断：请重新触发翻译", null);
                });
```

**4.5 `runPipeline`**：删掉 `semaphore.acquire()` 那段 `try/catch` 与末尾的 `finally { semaphore.release(); }`，其余语句顺序**一字不动**（装载 → 判空 → `long start` → `task.start()` 守卫 → `repository.update` → 日志 → `prepareAndClean` → `succeed` → `update` → `broadcastTerminal` → 成功日志 → `catch → failTask`）。包内可见的口径写进 Javadoc：`单测直接调它绕开虚拟线程的时序与配额闸，生产路径走 execute`。

**4.6 `runTranslationPipeline`**：签名改 `(VideoTask task, MediaInput input, long start)`；删掉顶部 `long start = System.currentTimeMillis();`、`semaphore.acquire()` 那段 `try/catch`、末尾 `finally { semaphore.release(); }`——只剩 `try { translate → translationSucceed → update → broadcastTranslationTerminal → 成功日志 } catch { failTranslation }`。Javadoc 补 `@param start 排队前的时刻（成本日志口径含排队时间）`。

**4.7 类 Javadoc 的「执行模型」段**（原文在「终态收口」段之后）改成：

```
 * <p>执行模型：交给 {@link TaskConcurrencyGate}——每次执行占一根虚拟线程、按
 * {@code vidsage.task.max-concurrent} 限流（翻译执行与主管线共用同一配额），超出者保持 PENDING 排队；
 * 所以占用配额发生在 PENDING → RUNNING 迁移之前，排队中的任务状态保持诚实。
```

### 敲的时候四个要点（容易错的地方）

1. **两条中断语义不许合并**：主管线只记 warn 就结束；翻译要 warn **再** `failTranslation` 收口，两句的顺序和文案逐字保留（注意翻译那条是**半角冒号** `…被中断: taskNo={}`）。
2. **`translateTask` 的 `start` 必须在 `gate.submit` 之前取**，否则「含排队时间」的口径就丢了。
3. **`succeed` / `update` / `broadcastTerminal` 不许挪到放许可之后**（它们必须还在配额内）。
4. **不加 `@PreDestroy`**、不给 executor 加 shutdown——那是行为变更，本次刻意不做（design §七）。

## 步骤 5 — 测试

**5.1 `SubtitlePipelineTest.service(int)` 改一行**

```java
    private SubtitlePipeline service(int maxConcurrent) {
        return new SubtitlePipeline(repository,
                new WorkspaceService(new WorkspaceProperties(tempDir.resolve("works").toString()),
                        new NioFileStateReader()),
                subtitlePreparationCoordinator, cleanupStage, translationStage, registry,
                new SemaphoreTaskConcurrencyGate(new TaskProperties(maxConcurrent)));
    }
```

再补一个注入假闸门的重载（既有用例不动）：

```java
    private SubtitlePipeline service(TaskConcurrencyGate gate) {
        return new SubtitlePipeline(repository,
                new WorkspaceService(new WorkspaceProperties(tempDir.resolve("works").toString()),
                        new NioFileStateReader()),
                subtitlePreparationCoordinator, cleanupStage, translationStage, registry, gate);
    }

    /** 假闸门①：立刻同步跑 body（把线程与配额摘掉，只看管线自己的行为）。 */
    private static TaskConcurrencyGate inlineGate() {
        return (body, onQueueInterrupted) -> body.run();
    }

    /** 假闸门②：模拟「排队被打断」——body 不跑，直接回调。 */
    private static TaskConcurrencyGate interruptOnQueueGate() {
        return (body, onQueueInterrupted) -> onQueueInterrupted.run();
    }
```

> 测试里 import `com.vidsage.infrastructure.concurrency.SemaphoreTaskConcurrencyGate` 与既有的
> `com.vidsage.infrastructure.fs.NioFileStateReader` 同性质：都是为了造夹具，不算破依赖规则。

**5.2 新增两条管线侧用例**

```java
    @Test
    void queuedMainTaskInterruptKeepsPendingAndRunsNothing() {
        VideoTask task = urlTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);

        service(interruptOnQueueGate()).execute(task.getTaskNo());

        assertEquals(TaskStatus.PENDING, task.getStatus(), "排队被打断必须保持 PENDING");
        assertNull(task.getErrorMessage());
        assertTrue(received.isEmpty(), "排队被打断不广播任何事件");
        verify(repository, never()).update(any());
        verify(subtitlePreparationCoordinator, never()).prepare(any(), any());
    }

    @Test
    void queuedTranslationInterruptFailsTranslationAndBroadcastsTerminal() {
        VideoTask task = succeededTask("https://example.com/v");
        when(repository.findByTaskNo(task.getTaskNo())).thenReturn(Optional.of(task));
        when(translationStage.hasTranslationArtifact(any(MediaInput.class))).thenReturn(false);
        List<TaskProgressMessage> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task.getTaskNo().value(), received::add);

        service(interruptOnQueueGate()).translateTask(task.getTaskNo());

        assertEquals(TranslationStatus.FAILED, task.getTranslationStatus());
        assertEquals("翻译失败：翻译在等待执行配额时被中断：请重新触发翻译", task.getErrorMessage());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus(), "翻译失败不得回退主状态");
        assertEquals(1, received.size(), "失败终态广播一条");
        assertEquals(TranslationStatus.FAILED, received.get(0).translationStatus());
        verify(translationStage, never()).translate(any(), any());
    }
```

> 这两条测的是**管线对「被告知排队被打断」的反应**，所以用假闸门回调即可（回调在测试线程上同步发生，
> 与真实场景里发生在闸门线程上不影响这两条的断言对象）。真实闸门的线程与配额行为由 5.3 单独测。

**5.3 新建 `backend/src/test/java/com/vidsage/infrastructure/concurrency/SemaphoreTaskConcurrencyGateTest.java`**

```java
class SemaphoreTaskConcurrencyGateTest {

    /** 线程池按任务建虚拟线程，并把每根线程记下来——测试要靠它抓住「排队中的那根」去 interrupt。 */
    private static ExecutorService recordingExecutor(List<Thread> created) {
        return Executors.newThreadPerTaskExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            created.add(t);
            return t;
        });
    }

    @Test
    void bodyExceptionStillReleasesThePermit() throws Exception {        // 命门②
        SemaphoreTaskConcurrencyGate gate =
                new SemaphoreTaskConcurrencyGate(1, recordingExecutor(new CopyOnWriteArrayList<>()));

        gate.submit(() -> { throw new IllegalStateException("boom"); }, () -> fail("不该被打断"));

        CountDownLatch second = new CountDownLatch(1);
        gate.submit(second::countDown, () -> fail("不该被打断"));
        assertTrue(second.await(5, TimeUnit.SECONDS), "body 抛异常后许可必须已释放，否则队列永久堵死");
    }

    @Test
    void interruptedQueueWaitCallsBackAndDoesNotReleaseThePermit() throws Exception {   // 命门①
        List<Thread> created = new CopyOnWriteArrayList<>();
        SemaphoreTaskConcurrencyGate gate = new SemaphoreTaskConcurrencyGate(1, recordingExecutor(created));

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        gate.submit(() -> { firstStarted.countDown(); releaseFirst.await(5, TimeUnit.SECONDS); },
                () -> fail("占着配额的那个不该被打断"));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        assertEquals(1, created.size());

        CountDownLatch interrupted = new CountDownLatch(1);
        gate.submit(() -> fail("排队被打断的 body 不该跑"), interrupted::countDown);
        while (created.size() < 2) {
            Thread.sleep(5);                     // 等第二根虚拟线程建出来（它随即阻塞在 acquire 上）
        }
        created.get(1).interrupt();

        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "中断应触发回调");

        CountDownLatch third = new CountDownLatch(1);
        gate.submit(third::countDown, () -> fail("不该被打断"));
        assertEquals(1, third.getCount(), "中断路径没释放许可 → 上限仍是 1，第三个必须排队");

        releaseFirst.countDown();
        assertTrue(third.await(5, TimeUnit.SECONDS), "第一个跑完释放后，第三个才该跑");
    }
}
```

**5.4 既有并发用例**（`secondTaskQueuesWhileConcurrencyLimitIsHeld`）不动——它走 `execute()`，天然穿过真闸门。

**5.5 落地时改掉本文示例的两处（2026-10-08，理由记此）**

1. **实现侧测试不用 `fail(...)` 断言**：虚拟线程上的 `fail(...)` 抛出的 AssertionError **不会让 JUnit 变红**（JUnit 只看测试线程）——原示例里那几处等于假断言，「body 明明跑了测试照样绿」。实际做法：自控执行器把逃出线程的异常收进 `failures` 列表，收尾 `assertTrue(failures.isEmpty())`；命门②另断言「只该有那一个 boom」，反过来钉住「闸门不吞 body 的异常」。
2. **命门①不用「提交后立刻读计数」**：`assertEquals(1, third.getCount())` 有调度竞态——许可真被错放时也可能读不到 0，**漏判**。改用否定等待：`assertFalse(third.await(300, MILLISECONDS), "中断路径不该释放许可…")`，错放会在微秒级现形。
3. 另加两条：`executeRunsPipelineThroughTheGate`（用 `inlineGate` 钉住「execute → 闸门 → 管线」这条接线）、`rejectsMaxConcurrentBelowOne`（校验文案逐字，随校验从管线搬来）。

## 步骤 6 — 失实订正（改完代码才做）

| 文件 | 改什么 |
|---|---|
| `infrastructure/package-info.java` | 散文补成两类居民：① 出站适配（实现 domain / application 端口）；② **通用技术机制类**（不面向业务契约、但有状态或需要容器——`process/`、`concurrency/`）。再加一句与 `shared/` 的分界：**看有没有状态**。依赖规则那段原样保留 |
| `shared/package-info.java` | 补一句：有状态或需要 Spring 容器的机制不归本层，落 `infrastructure/`（如 `concurrency/` 的任务并发闸） |
| `.trellis/spec/backend/directory-structure.md` | 「`application/` 五类构件」那条里 `SubtitlePipeline` 的括注删掉「**配额、虚拟线程**」（现文写「管线的所有者：配额、虚拟线程、阶段顺序、进度广播、终态收口」）——按订正惯例直接重写为现状 |
| `config/TaskProperties.java` | Javadoc 里「（`SubtitlePipeline` 的 Semaphore 限流）」→「（`SemaphoreTaskConcurrencyGate` 的 Semaphore 限流）」 |
| `application/task/package-info.java` | 顺带确认那段对 `pipeline/` 的描述有无失实（没有就不动） |

**步骤 6 落地记录（2026-10-08）**：上表五处全做。其中 `application/task/package-info.java` 的 `pipeline/` 描述本身无误，但该段的**子包枚举漏了新建的 `port/`**（枚举型文档漏一项即是失实），补了一句 `port/` 装本层出站端口（`TaskConcurrencyGate`，实现在 `infrastructure/concurrency`）。另扫了一遍 spec 全库：「配额 / 虚拟线程 / Semaphore」只剩两处合法提及——步骤 1 加的端口判据例子、`quality-guidelines` 的「虚拟线程已全局开启」。

**第六处失实（上表漏了，扫描面扩到 yml 才发现）**：`backend/src/main/resources/application.yml` 的 `vidsage.task.max-concurrent` 行注释原文同样写「（SubtitlePipeline 的 Semaphore 限流）」——已改成 `SemaphoreTaskConcurrencyGate`。教训：失实扫描不能只看 `.java` 与 `.trellis/spec/`，配置文件的注释是同一类载体。

## 步骤 7 — 全量回归

```bash
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml
```

`clean` 必须带（`target/classes/` 与 `surefire-reports/` 都会留陈旧产物）。逐用例结论看 `<failure` 元素，不看是否自闭合。

**实测结果（2026-10-08）**：`mvn clean test` 全绿——**482 用例 / 0 失败 / 0 错误 / 1 skip**（唯一 skip 是 `ModelProbeTest` 缺 `MODEL_PROBE` 环境变量，既存、与本次无关）。三条 grep 证据均为期望值（前两条空、第三条只剩 `concurrency/` 一处）。

**命门①做过变异验证**：给中断分支临时加一句 `semaphore.release()` → `SemaphoreTaskConcurrencyGateTest.interruptedQueueWaitCallsBackAndDoesNotReleaseThePermit` **0.011s 即红**，报的正是那条断言；撤销后复查无残留。这条证明该用例不是空转。

依赖规则的两条 grep 证据：

```bash
grep -rn "com\.vidsage\.infrastructure" backend/src/main/java/com/vidsage/application   # 期望：空
grep -rn "com\.vidsage\.config" backend/src/main/java/com/vidsage/application/task     # 期望：空（本任务清掉本上下文唯一一处；application 其它地方仍有 3 处收 *Properties，不属本任务范围）
grep -rn "Semaphore\|newThreadPerTaskExecutor" backend/src/main --include=*.java       # 期望：只剩 concurrency/ 一处
```

## 步骤 8 — 行为对照（AC4）

沿用 10-08 的双实例套路（worktree 对照点用 dev 当前位）：

1. `git worktree add ../vidsage-before <dev 当前 sha>`，起两个实例（本分支 `18080`、对照 `18081`）。
2. **优先提交「产物已在工作区」的同一份输入**（内容寻址 + 断点复用），把外部配额消耗压到最低；抓两边的 SSE 事件流存进 `evidence/`。
3. 逐格比对，**期望：零差异**。若触达了 R3 那两格（幽灵任务路径），必须恰好只有那两格，并在 `evidence/` 写明。
4. 顺带确认日志里线程名仍是 `vidsage-task-`、`任务执行成功: taskNo=…, cost=…ms` 照旧。
5. **翻译链路不做真实运行**（省配额）——以 5.2 的单测覆盖。这是一条已接受的边界，写进完成报告。
6. 收工杀实例（`TaskStop` 杀不掉 `spring-boot:run` 的子 JVM）：

```bash
netstat -ano | grep LISTENING | grep -E ":1808[01]"
taskkill //PID <pid> //F
```

## 步骤 9 — 收口

- 完成报告写进 `evidence/`：AC 逐条结论 + 差异栏（R3 两格 + 翻译 cost 数值的微秒级来源变化）+ 已接受边界（翻译不做真实运行、`@PreDestroy` 不做）。
- workspace journal 记一笔（Trellis 的 `finish-work` 会带）。

---

---

## 遗留 Javadoc 草稿（2026-10-08）

**落地情况**：草稿 A 已落地（用户版略有删减：段落间未加 `<p>`；「绝不放许可 + 后果」那句削成「只回调 onQueueInterrupted」）。草稿 B **部分落地**——接口首句改了，`submit` 方法那段与方法参数没动（「提交任务到虚拟线程排队执行」仍在）；另接口首句第 5 行行首多出一个 `*`（会当字面星号渲染）。草稿 C **部分落地**——`@param startTimeMs` 已加，首句「同一信号量配额」未改成「同一份执行配额」（本类已无信号量）。三处均为注释层，不影响任何 AC。

审查后确认的四处注释缺口里第 1、2 处的草稿，照抄即可（正文之外的取舍理由见每条末尾）。

### 草稿 A — 实现类 `SemaphoreTaskConcurrencyGate` 的类 javadoc

```java
/**
 * {@link TaskConcurrencyGate} 的进程内实现：一段活占一根虚拟线程，用信号量把同时在跑的限到
 * vidsage.task.max-concurrent 个。
 *
 * <p>超出配额的就阻塞在 acquire 上排队——排队的实体就是那根虚拟线程本身，没有额外的队列结构；
 * 信号量是非公平的（JDK 默认），所以排队不保证先来先跑。
 *
 * <p>两条容易写错的地方：body 不管正常结束还是抛异常，都要放掉许可（漏了就把队列永久堵死）；
 * 排队期间被打断时只回调 onQueueInterrupted，<b>绝不放许可</b>——本来就没拿到，放掉等于把上限悄悄调大。
 */
```

### 草稿 B — 端口 `TaskConcurrencyGate` 的接口与方法 javadoc

```java
/**
 * 任务执行闸门：把一段活交出去跑，同时最多放行 vidsage.task.max-concurrent 个
 * （翻译执行与主管线共用这一份配额）。
 */
public interface TaskConcurrencyGate {

    /**
     * 提交一段活。拿到许可之前不跑 body——所以「还在排队」这件事是有保证的（任务停在 PENDING，
     * 不会提前变成 RUNNING）。
     *
     * <p>等许可时被打断该怎么办，两个调用方各有各的答案（主管线保持 PENDING 静默退出、翻译要落失败
     * 并广播终态），所以那不写在实现里，由调用方通过 onQueueInterrupted 传进来。
     *
     * @param body               要跑的活；拿到许可之后才跑
     * @param onQueueInterrupted 等许可被打断时的收尾动作（此时 body 一次都没跑过）；语义由调用方定
     */
    void submit(Runnable body, Runnable onQueueInterrupted);
}
```

**草稿 B 改掉的三点**（相对现文）：①「提交任务到**虚拟线程**排队执行」——虚拟线程是本实现的选型，端口不该承诺（换实现就失实）；②「将媒体转字幕任务放到其他线程」——把手段当成了契约，且说窄了（端口服务的是两条链路，不限转字幕）；③ 缺「拿到许可才跑 body」这条保证 + `onQueueInterrupted` 的确切时点（**body 没跑过**）。

### 草稿 C — `SubtitlePipeline.runTranslationPipeline` 的 javadoc（第 3 处）

```java
    /**
     * 按需翻译的异步执行体：与主管线共用同一份执行配额（LLM 翻译同样吃外部配额）。
     * 翻译阶段事件自成 0..100 段直通广播（无区间映射——主任务的 0..100 已在主管线跑完，
     * 翻译是独立的第二段）；翻译事件的 taskStatus 恒为主状态 SUCCEEDED。
     *
     * @param startTimeMs 排队前的时刻——成本日志的口径是「含排队时间」，所以要由调用方在提交前取好传进来
     *                    （进到本方法时排队已经结束，在这里取就晚了）
     */
```

**草稿 C 改掉的两点**：① 首句「共用同一**信号量**配额」——本类已经没有信号量了，配额现在由闸门放行，改成「同一份执行配额」；② 末句从「说机制」改成 `@param` 并补上**为什么**（成本日志口径含排队时间）——只看签名的人原先根本看不到这条。

---

## 评审门与回退点

- **门的纪律**：步骤 0–9 全部做完、测试绿、对照结果出来、**spec 订正落地**后**停下等审查**——不在同一次执行里 commit / push / merge / 归档。逐门批准：提交 → 推送 → 合并 → 归档各是一道门，沉默 ≠ 批准。
- **建议的提交切分**（审查通过后）：① 步骤 1（判据先行）；② 步骤 2–4（端口 + 实现 + 管线接线）；③ 步骤 5（测试）；④ 步骤 6（失实订正）。
- **回退点**：步骤 1 之后（规范已更新，代码未动，可独立成立）、步骤 4 之后（编译与测试绿，测试面尚未补）、步骤 7 之后（全量绿）。
- **最可能的返工点**：步骤 5.3 命门①用例的线程抓取（依赖 `ThreadFactory` 记录 + 轮询等第二根线程建出来）。若它不稳，退到「只留命门② + 用 `availablePermits()` 类的可见性断言」——但**别删掉这条测试**，它是本次唯一新增的覆盖能力。

## 环境坑（memory 里有，别重踩）

- `mvn` 在 Git Bash 下要显式 `JAVA_HOME=/f/java_soft/java21`。
- 量测试前删 `target/surefire-reports`（残留旧报告会让数字骗人）；改名/改结构后必须 `mvn clean`。
- 起冒烟实例后 `TaskStop` 杀不掉子 JVM，按端口 PID `taskkill //PID <pid> //F`。
- 证据目录用任务下的 `evidence/`，不要用 `/tmp`。
