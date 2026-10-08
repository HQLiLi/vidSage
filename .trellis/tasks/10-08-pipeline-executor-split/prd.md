# SubtitlePipeline 执行模型拆分

## Goal

把 `SubtitlePipeline` 的**执行模型**（executor / semaphore / 排队语义）从**编排**（阶段顺序、进度回调组装）里拆出来，改造前后行为逐格不变；顺带给「有状态的进程内通用机制」立一条分层判例（端口 + 适配器）。

## 动机（2026-10-08 重审）

handoff 记的动机是「改执行要小心不碰进度、改进度要小心不碰执行」。**这条在本次拆分里不成立**——要拆走的 `executor` + `semaphore` + 两段 `acquire/release`（约 25 行）本来就和进度、状态迁移不纠缠，今天改 semaphore 也不用碰进度一行。真实动机三条：

1. **干净的一半先归位**：`SubtitlePipeline` 现在 364 行、身兼五职；执行模型是其中唯一不碰业务的那一块，先把它挪出去，后面每一刀都少一层牵连。
2. **资源配对单点化**：`acquire()` / `release()` 现在两条路径各手写一遍（`runPipeline` / `runTranslationPipeline`），每处还各带一个「acquire 失败提前 return、因此不 release」的分支——正确性靠人眼。抽出后配对只存在于一处。
3. **中断路径第一次变得可测**（本次唯一拿得到的硬收益）：端口一立，管线侧可注入假 gate，把两条「排队被打断」分支（主管线保持 PENDING 静默退出 / 翻译 `failTranslation` 收口）变成确定性单测——今天零覆盖。

**明确不做**：本次不动「状态迁移（DB）× 广播（SSE）」那个三角，也不动任何排队政策（FIFO / 排队超时 / 位置可观测 / 重启恢复）。见 Out of scope。

## 背景与已确认事实（2026-10-08 对照源码核实）

- `SubtitlePipeline.java` 一个类身兼五职：
  1. 异步提交（`execute` → `executor.execute`）；
  2. 并发闸 + 排队（`Semaphore`，主管线与翻译**共用同一配额**）；
  3. 阶段编排（`prepareAndClean` 的 prepare → clean 语句顺序）；
  4. 进度组装（`progressListener` 落库先广播后；`translationProgressListener` 只广播不落 current_stage）；
  5. 终态收口（`broadcastTerminal` / `broadcastTranslationTerminal` / `failTask` / `failTranslation`）。
- 两条排队路径的**顺序与中断语义不同**（拆分必须原样保留）：
  - 主管线（`runPipeline`）：先装载、再 `semaphore.acquire()`、后 `task.start()`——排队中保持 PENDING；中断 → 保持 PENDING 退出，不落失败。
  - 翻译（`translateTask` 同步段先 `translationStart()` 落库 RUNNING 再入队）；排队中断 → `failTranslation` 收口广播。
- 执行器：`newThreadPerTaskExecutor` 虚拟线程（线程名 `vidsage-task-`）；`Semaphore` 按 `TaskProperties.maxConcurrent()`（构造期校验 ≥ 1）。
- 调用方仅 `VideoTaskService`：`pipeline.execute`（L280）、`pipeline.translateTask`（L192）。
- 测试口子：`SubtitlePipelineTest.service(2)` 构造（`TaskProperties` 即并发度）；同步用例直调包内 `runPipeline`，并发用例走 `execute()` + latch/timeout。
- **「排队」这件事今天跨三处、且没有单一所有者**：① DB 的 `status = PENDING`（对外可见的排队状态，持久）；② `Semaphore`（内存里谁先跑）；③ 排队实体 = 阻塞在 `acquire()` 上的虚拟线程（没有 Queue 数据结构，故全仓库 grep 不到「队列」）。派生事实：`new Semaphore(n)` 默认**非公平 → 无 FIFO 保证**；**无排队超时**；**无位置可观测**；**无重启恢复**（main 里零 `ApplicationRunner` / `CommandLineRunner` / `@PostConstruct` / `@EventListener`，进程重启后遗留 PENDING 永不重跑）。
- **并发失控的真实代价分三档**（`Semaphore` 管的是资源过载，不是互斥）：① 全变慢且开始超时（外部进程 300s 闸 → `IllegalStateException` → FAILED）；② **静默降级**：LLM 限流 → 清洗层当「没拿到回复」重试、耗尽后保留原文（任务仍 SUCCEEDED）；翻译留 `untranslated` 段——且重试无退避（全仓库零 `Thread.sleep`/backoff），会自放大限流；③ 直接失败（DashScope ASR 账号配额、OOM）。
- 术语：「配额」在本项目 = **并发许可**（`vidsage.task.max-concurrent`），不是外部服务返回的额度（DashScope / LLM 的额度是**外部配额**，信号量只能间接缓和）。
- 分层现状：`infrastructure` 的散文口径写「只做出站适配、唯一实现端口的地方」，但 spec 二级包表里 `infrastructure/process/` 已收「不面向业务契约的机制类」——**两处口径不一致**；`shared/` 的准入判据是「纯静态方法工具、**无状态**、不依赖容器」。

## 已定边界（来自 10-08 handoff，brainstorm 不重开）

1. **档位 A**：阶段顺序只在编排语句顺序里，不做列表化/状态机。
2. **翻译不在主管线**：SUCCEEDED 后按需触发，0..100 直通，拆分不改这个结构。
3. **进度职责刚定型**：谁发事件谁取数、`ProgressMapper` 只剩 clean 批级一处、`SubtitleStageName` 区间单源——拆分不得搅回。
4. **零配置项约束**、技术栈不动（沿用 10-08 prd Constraints 口径）。

## Requirements

- **R1 执行模型抽出**：新增端口 `application/task/port/TaskConcurrencyGate`（`submit(body, onQueueInterrupted)`）与实现 `infrastructure/concurrency/SemaphoreTaskConcurrencyGate`（`@Component`）；`SubtitlePipeline` 不再持有 `Semaphore` / `ExecutorService` / `TaskProperties` 三个字段与 `maxConcurrent ≥ 1` 校验。
- **R2 行为逐格不变**（除 R3 声明的差异）：两条排队路径的语句顺序与中断语义原样保留；中断语义由管线侧以回调注入（主管线只记 warn 保持 PENDING、翻译记 warn + `failTranslation` 收口），**不得在实现类里合并成一种**。
- **R3 主管线装载时点后移**：装载随 body 移到「拿到许可之后」；由此产生的差异写进验证报告的差异栏。**共四格**——前两格为原声明，后两格 2026-10-08 由检查面复核补入：

  1. 幽灵任务（装载返回空）会先排队再放掉许可——基线里它不排队、不占许可；
  2. 「任务记录缺失」ERROR 日志在有排队时推迟；
  3. *（2026-10-08 补）* 同一 taskNo 被 `execute` 两次时，基线可能读到**陈旧的 PENDING 快照**（MyBatis 每次返回新实例），拿到许可后 `start()` 仍然成功 → **整条管线跑两遍**（重复的 ASR / LLM 成本）；装载后移后第二次必然读到已推进的状态 → 确定放弃。方向是**改进**，但基线那格行为确实变了；
  4. *（2026-10-08 补）* 幽灵任务恰好**排队时被打断**：那条 ERROR 不是「推迟」，而是**一次都不出现**（回调只记 warn 就 return）。

  四格都落在**实践不可达**的路径上（任务表全仓无删除路径；现有调用方不会对同一 taskNo 重复 `execute`），所以 AC4 的真实链路对照覆盖不到它们——只能靠这段文字在案。
- **R4 翻译成本日志语义保留**：「含排队时间」不变——`long start` 提到 `translateTask` 里捕获后传进 body。
- **R5 终态广播仍在配额内**：`succeed()` / `update` / `broadcastTerminal` 不得挪到 `release()` 之后。
- **R6 中断路径可测**：管线侧注入假 gate 覆盖两条中断分支；实现侧覆盖两条命门（中断不释放许可、body 抛异常仍释放）。
- **R7 分层判例落地**：`infrastructure` 范围放宽为「出站适配 + **有状态的通用机制**」，与 `shared/` 的界线改写为「**有没有状态**」；`shared` / `config` / `web` / 依赖表（层名与方向）**都不动**。
- **R8 刻意不做**：executor 的 `@PreDestroy` shutdown（行为变更，会让进行中的任务被打断成 FAILED）；除端口外不引入新抽象。
- **R9 规范订正随代码交付**：`infrastructure/package-info`、`shared/package-info`、spec 二级包表 + `SubtitlePipeline` 那句失实表述、`TaskProperties` Javadoc；顺序**先改判据、再搬代码**。

## Acceptance Criteria

- [ ] **AC1 结构**：`SubtitlePipeline` 零 `java.util.concurrent` import、零 `Semaphore`/`ExecutorService`/`TaskProperties` 字段；端口与实现按 design 落位命名。
- [ ] **AC2 既有测试**：`SubtitlePipelineTest` 除 `service(int)`（构造改注入 gate，一行）外不改——8 个同步用例的调用方式与断言原样保留；全绿。
- [ ] **AC3 新增测试**：管线侧 2 条中断分支 + 实现侧 2 条命门，全绿。
- [ ] **AC4 行为对照**：真实链路 SSE 事件流与 dev 对照点逐格比对，差异**恰好只有 R3 声明的那些格**（若不触达则为零差异）；比对结论落 `evidence/`。翻译链路不做真实运行（省配额），以单测覆盖——这条边界写进完成报告。
- [ ] **AC5 规范订正**：R9 四处全部落地；两条 `package-info` 的口径不再自相矛盾（机制类进 `infrastructure`、无状态纯函数进 `shared`）。
- [ ] **AC6 依赖规则**：`application` 无任何 `infrastructure` import（`grep` 证据）；`mvn clean test` 全绿。
- [ ] **AC7 逐字保留**：两条排队中断的 warn 文案、以及 `任务记录缺失…` / `任务开始执行…` / `任务执行成功…` / `翻译已触发…` / `任务翻译完成…` 的日志文案逐字不变。

## Out of scope

- **「状态迁移（DB）× 广播（SSE）」三角**（本类后 150 行）：两个 `progressListener` + 两种终态收口 + `failTask`/`failTranslation`。用户 2026-10-08 定：排在 A 之后另开任务。
- **排队政策**：FIFO 保证、排队超时、排队位置可观测、重启后 PENDING 恢复（现状四项全缺）。
- **两处并发竞态**（2026-10-08 核查发现，均与本任务无关）：① 并发 `POST /translate` 会双跑同一任务（MyBatis 每次返回新实例 + `translationStart()` 只查内存副本 + `updateById` 无条件）；② 提交查重是 check-then-insert 且 schema 无唯一约束（`INDEX idx_task_workspace_key`），同一输入同时提交会双写同一工作区。
- **`shared` / `config` 并入 infrastructure**（分层搬家）：用户 2026-10-08 定不做；`web` 入站适配留独立层。
- Javadoc 全局重构（用户定另开）。
- `@Schema` 落位口径（用户自行推进）。
- **SSE/通知面落位与规则重构**（用户 2026-10-08 提出有重构意向）：本任务行为不变、通知面原样不动；如做只允许「搬家不动规则」，另开任务。
- **任务表字段重议**（用户 2026-10-08 质疑 `errorMessage` / `translationStatus` 去留）：查账结论——`errorMessage`/`currentStage` 在「快照即权威」下有实职；`translationStatus` 今日有防重入/幂等收敛/SSE 子状态三职，但**多语言选择面板落地时单枚举需演进为按语言状态**——此意见移交 `09-29-subtitle-translate-qwen-mt` 当设计输入。
- 其余 3 个 planning 任务（`09-24-vidsage` 系列、`09-29-subtitle-translate-qwen-mt`）。

## Key decisions

- **Q1（2026-10-08 重审后定稿）：方案 A「执行壳」**——端口 + 实现只拿「提交 → 排队拿配额 → 跑 body → 释放」骨架，中断处理以回调注入；状态迁移、编排、进度组装、终态收口全留在 `SubtitlePipeline`。否决 B（把状态迁移也搬进执行类）的理由：会迫使新类依赖 repository + registry，把 10-08 刚理干净的进度职责塞回去，且翻译路径要么吃下两套状态机、要么拆不彻底。
- **Q2：名字 `TaskConcurrencyGate`**。命名账（2026-10-08 grep）：`*Executor` 类名零占用但撞 `org.springframework.core.task.TaskExecutor`（且「异步执行器」整片命名空间被 Spring 占满：`AsyncTaskExecutor` 亦存在）；`*Runner` = 外部进程跑手（`YtDlpRunner` / `ExternalProcessRunner`）；`*Dispatcher` = 按输入类型选适配器（`MediaAcquirerDispatcher`）；`*Coordinator` = 认领阶段构件。「异步」不进类名——仓库零个类名含 `Async`，异步一律写在注释里（`SubtitlePipeline` 类注释首句即「异步任务管线」）。
- **Q3：装载挪进 body**（不提到调用线程）。理由：请求线程保持「落库 + 提交 + 返回」三件事；(b) 会让它多担一次同步装载，并改动 8 个同步用例的测试口子。为此换来的「逐格不变」只落在那条**实践不可达**的幽灵任务路径上（任务表全仓无删除路径），本末倒置。
- **Q4：D（依赖方向）保留**——端口在 application、实现在 infrastructure，`application → infrastructure` 依旧禁止。理由：2026-10-06 端口倒置刚把豁免清单清空；单向依赖是「后面重构顺利」的机制保证；且只有走端口，中断分支才可测。
- **Q5：infrastructure 范围放宽（用户圈的 A 行）**——补「有状态通用机制」进口径，判据改「有没有状态」；`shared` / `config` / `web` / 依赖表不动。
- **Q6：本次的收益定位**——承认轻（净 LOC 变多），价值在「干净的一半归位 + 资源配对单点化 + 中断路径可测 + 立一条分层判例」；真正的疼（状态 × 广播三角）另开任务。
