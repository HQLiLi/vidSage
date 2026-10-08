# 完成报告：SubtitlePipeline 执行模型拆分（2026-10-08）

## AC 逐条结论

| AC | 结论 | 证据 |
|---|---|---|
| **AC1 结构** | ✅ | `SubtitlePipeline` 里 `java.util.concurrent` / `Semaphore` / `ExecutorService` / `TaskProperties` 的 grep **全空**；端口落 `application/task/port/TaskConcurrencyGate`，实现落 `infrastructure/concurrency/SemaphoreTaskConcurrencyGate`（`@Component` + `@Autowired`） |
| **AC2 既有测试** | ✅ | 既有 8 个同步用例的调用方式与断言**一字未动**（仍直调包内 `runPipeline`）；唯一改动是 `service(int)` 由「传 `TaskProperties`」改为「注入真闸门」，并抽出 `service(TaskConcurrencyGate)` 重载 |
| **AC3 新增测试** | ✅ | 管线侧 3 条（主管线排队中断保持 PENDING 且零装载/零落库/零编排、翻译排队中断 `failTranslation` 收口、`execute` 经闸门跑通管线）+ 实现侧 3 条（body 抛异常仍释放、排队被打断不释放、`maxConcurrent < 1` 校验文案）；**命门①做过变异验证**：临时错放许可 → 0.011s 即红 |
| **AC4 行为对照** | ✅ 零差异 | 见 `ac4-comparison.md`：14 格事件流逐格相同，归一化 taskNo 后两边等长 2635 字节，唯一差异是 `:ping` 心跳落点；R3 四格未触达 → 按 AC 文字为零差异 |
| **AC5 规范订正** | ✅ | R9 四处 + **第六处**（`application.yml` 的 `max-concurrent` 注释，同为「SubtitlePipeline 的 Semaphore 限流」失实）；`infrastructure` / `shared` 两条 `package-info` 的口径不再自相矛盾（机制类进 `infrastructure`、无状态纯函数进 `shared`，界线=有没有状态） |
| **AC6 依赖规则** | ✅ | 三条 grep 均为期望值（`application/` 无 `infrastructure` import、`application/task/` 无 `config` import、`Semaphore`/`newThreadPerTaskExecutor` 只剩 `concurrency/` 一处）；`mvn clean test` **482 用例 0 失败 0 错误 1 skip** |
| **AC7 逐字保留** | ✅ | 字符串字面量集合 diff（dev vs 本分支）：管线里**唯一**消失的字面量是搬走的两处（`vidsage-task-` 前缀、`max-concurrent` 校验文案），**没有任何日志文案被增删改**；三段搬走的字面量在闸门类**逐字命中**；两条排队中断 warn 逐字（含翻译那条的**半角冒号** `…被中断: taskNo={}`） |

## 差异栏（与改造前的有意差异）

| # | 差异 | 触发条件 | 实测 |
|---|---|---|---|
| 1 | 幽灵任务（装载返回空）会先排队、拿到许可后再放掉 | 需要任务记录缺失——全仓无删除路径，**实践不可达** | 未触达 |
| 2 | 「任务记录缺失」ERROR 日志在有排队时推迟 | 同上 | 未触达 |
| 3 | 翻译 cost 的起点时刻从「闸门线程、进方法时」前移到「请求线程、提交前」→ 数字多算一次线程派发延迟 | 每次翻译触发 | 翻译链路未跑真实运行，此格由代码位置 + 翻译排队中断用例锁住 |
| 4 | 同一 taskNo 被 `execute` 两次时，基线的**陈旧 PENDING 快照**会让 `start()` 二次成功 → 整条管线跑两遍（重复 ASR / LLM 成本）；装载后移后第二次必然读到已推进的状态 → 确定放弃 | 需要同一 taskNo 被重复 `execute` | 未触达（现有调用方不会这么调）。方向是**改进** |
| 5 | 幽灵任务**排队时被打断**：那条「任务记录缺失」ERROR 不是推迟，而是**一次都不出现**（回调只记 warn 就 return） | 需要任务记录缺失 + 排队被打断 | 未触达（任务表无删除路径） |

第 4、5 格是 2026-10-08 检查面（逻辑关）复核补入的——它们由同一个「装载挪进 body」带出，方向分别是改进与丢失，都落在实践不可达的路径上，故 AC4 的真实链路对照覆盖不到，靠上表在案。四格均已登记进 `prd.md` 的 R3。

本次真实运行为**零差异**（R3 四格全部未触达）。

## 已接受的边界

1. **翻译链路不做真实运行**（省外部配额），由单测覆盖。→ `ac4-comparison.md`、prd Out of scope。
2. **不给 executor 加 `@PreDestroy` shutdown**（行为变更：关闭时打断进行中的任务 → FAILED）。design §七。
3. R5「终态广播仍在配额内」是结构性保证（`succeed`/`update`/`broadcastTerminal` 都在 body 内，即 `release()` 之前），非运行时可观测项。

## 遗留（不影响 AC 判定，留给审查决定）

| # | 项 | 位置 | 归属 |
|---|---|---|---|
| 1 | 实现类**没有类注释**：非公平信号量（不保证先进先跑）、body 抛异常仍释放、中断不释放——三条命门未写下 | `SemaphoreTaskConcurrencyGate` | 主源码（用户） |
| 2 | 端口首句仍写「提交任务到**虚拟线程**排队执行」「将媒体转字幕任务放到其他线程」——机制词进了契约；且缺「拿到许可才跑 body」这条保证 | `TaskConcurrencyGate` | 主源码（用户） |
| 3 | `runTranslationPipeline` 的 Javadoc 末句以逗号结尾，且未点出「为的是让成本日志含排队时间」 | `SubtitlePipeline` | 主源码（用户） |
| 4 | 暂存区里躺着两份**空占位**（`AM` 状态），提交时必须重新 `git add` | git index | 提交门处理 |

## 未走完的门

提交 → 推送 → 合并 → 归档：**四道门都未走**。本报告与全部改动停在审查窗口等批准。
