# 字幕模型重构 2/8：换血（身份键 + 提交路径 + run 生命周期）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），本文只写本段的范围与验收，不复述终态、不另开一份设计。
> 前置 [S1](../../archive/2026-10/10-17-subtitle-model-foundation/prd.md) 已归档合并（`dbdcacb` 已进 `dev`）：五张新表 + 领域类 + 六个端口 + MyBatis 实现全在，**纯增量、零调用方**。
> **本段是全流程最大的一段**：它把「旧栈还在跑」切换成「新栈在跑」。做完之后 `VideoTask` / `task` 表 / `TaskSessionRegistry` 全部不存在。

## Goal

把运行中的那套换掉：**身份键**（`MediaIdentityResolver`：剥跟踪参数 / 取 `BV` / `av` 换算 / 短链跟跳转）、**提交路径**（W1 事务 + C63 撞键锁定读 + 插 `MAIN` run）、**run 生命周期**（`TaskRunSupervisor`：抢互斥 / 收口 / 启动收口）、**新对外契约**（`GET` 身份 + `lastRun`；三个 POST 统一 202·409 `{taskNo, runNo}`；SSE 一条 run 一条流）、**`subtitle_keyword` 换锚点**（手工 `DROP` 重建）、**旧栈整块删**。

**一句话判据**：本段结束时，全仓 grep 不到 `VideoTask` / `TaskSessionRegistry` / `TaskStageEvent` / `TaskSubscriptionRegistry` / `Subscription` / `TaskProgressNotifier` / `WorkspaceKey` / `WorkspaceKeyGenerator` / `TaskStatus`（阿里 SDK 那个同名枚举除外），`schema.sql` 里没有 `task` 表。

## 本段定下的取舍（design 没写、本轮 grill 定的九条）

| # | 取舍 | 理由 | 备选与否掉的原因 |
|---|---|---|---|
| **T1** | **RECLEAN / TRANSLATE 的执行体一并换锚，保持能跑** | 本段删掉 `VideoTask` 与 `TaskSessionRegistry` 后，两个 coordinator **本来就必须改才能编译**；只做「插了 run 没人跑」的骨架会留两个死端点，且那段代码 S4/S5 还得推翻 | 否掉「只做骨架、插完即收 FAILED」与「两个端点下线」——都违反父 prd 的「一套接口只增不改、不造临时件」 |
| **T2** | **启动收口挂 `ApplicationRunner`** | Spring Boot 标准做法，启动日志里看得见收掉几行 | 否掉 `SmartLifecycle`（相位是隐式契约）与 `@PostConstruct`（在 bean 初始化里写库非常规）。**接受**的代价：连接器此刻已监听，窗口毫秒级 —— 撞上的请求会看到一行其实已死的 `RUNNING`，重试即可 |
| **T3** | **`VideoTaskService` 拆成「用例 + 机制」两类**：`SubtitleTaskService`（提交 / 上传 / 查询 / keywords / 触发）+ `TaskRunSupervisor`（插 run 抢互斥 / 推进 / 收口 / 启动收口） | 机制必须收在一处：`uk_active_run` 让「插 run」同时是抢互斥与状态写入。不收在一处就是「谁都能改 run 状态」——`TaskSessionRegistry` 当年正是这么错的（同时是锁又是状态源，还被三个类分别调用） | 否掉「单类换名」（机制与用例仍挤一个类）与「按读 / 写拆」（拆出来的两个都要各自碰 run 机制） |
| **T4** | **SSE 广播改成端口，并整块删掉「中立发布-订阅」那套**：新增 `application/task/port/TaskProgressBroadcaster` + `web/sse/SseTaskProgressBroadcaster`；退场 `TaskSubscriptionRegistry` / `Subscription` / `TaskProgressNotifier` / `TaskSseConnectionManager` | **这是在推翻 2026-09-30 项目所有者自己定的一条**（`09-24-vidsage-mvp1-submit/design.md:15`：「订阅机制独立为顶层包 `subscribe/`——中立、无 HTTP 类型，service 与 web 都可依赖」）。推翻的理由是实测的：**全仓生产代码里 `subscribe()` 只有一个调用点**（`TaskSseConnectionManager.open`），那层中立从没兑现；而它换来两张表（订阅表 + 连接表）+ 两个实体（订阅关系 / 连接）要同步 —— 死订阅①的根源。换成端口后模型变成「**连接表 + 直接广播**」，订阅与连接合一，`Subscription`（运行时回调的退订凭据）没有存在理由。**中立性没真丢**：真要加第二个消费者，注入 `List<TaskProgressBroadcaster>` 即可 | 否掉「保持中立注册表」（绕度与两张表都在）与「照主流把 emitter 放进 application」（破「HTTP 类型只在 `web/sse/`」红线）。**连带 spec 更新**见需求 45 |
| **T5** | **SSE 消息形状改成 sealed 三变体**（`TaskRunProgress.Main` / `.Reclean` / `.Translate`），每个变体带 `runType` 组件 | **语义优先**：`MAIN` 与 `RECLEAN` 是两个不同的任务，只是流程恰好同形；`TRANSLATE` 是第三个，流程不重合。做成三变体后 `targetLanguage` 不再是「一个可空字段两种含义」，只存在于 `Translate` 变体上 | 已知代价：`Main` 与 `Reclean` 两个 record 形状相同（各带恒定的 `runType`），靠紧凑构造器守卫挡住「手构出 `Main` 却填 `RECLEAN`」。备选「单 record + `RunRef` 值对象」形状更省，但把两种任务压成一个类型，与 T5 的语义取向相反 |
| **T6** | **SSE 路径改成 `GET /api/tasks/{taskNo}/runs/{runNo}/events`** | 地址里是「哪一次执行」；`run` 自带 `runType`，「哪个任务」隐含其中；同一 task 跑两次翻译是两个不同地址，不歧义。**连带解决了「任务在但没 run 时推什么」这个边角** —— 客户端拿不到 `runNo` 就不会开流，服务端只需处理「runNo 不存在 → 404」 | 否掉「路径不变、服务端解析当前 run」（客户端无法指认绑的是哪条流）与「三条固定地址」（同类型跑第二次时地址不变、语义要写死进文档） |
| **T7** | **run 已终态时再开流 → `204 No Content`**（不推终态快照） | `EventSource` 在服务端正常关流后会**自动重连**（约 3 秒一次）；推快照再关会让不主动 `close()` 的客户端**永久循环重连**，而 **204 是规范里明确「告诉浏览器别再重连」的状态码**。语义上也更干净：快照的作用是「补上错过的进度」，run 已终态就没有进度可补，客户端直接 `GET /{taskNo}` 看 `lastRun` | 否掉「推一条终态快照后关」（循环重连）。前端 `useTaskEvents.ts` 已有 `fatal` 分支（`readyState === CLOSED` → 消费方重拉快照），正好接住 |
| **T8** | **包结构按「任务与执行 / 内容加工」两条轴重排**：`domain/` 平铺成 `task` / `subtitle` / `intermediate` / `workspace` / `media` / `transcription`；`application/` 把 `SubtitlePipeline` 移到它的三个被编排件旁边（`application/subtitle/pipeline/`），`application/task/pipeline/` 消失 | 三处不对称，都可验证：① 两层用不同的词指同一个上下文（`application/task/` ↔ `domain/subtitle/`）；② `domain/subtitle/` **一个包 40 个文件**、混着任务轴与内容轴（`domain/workspace/` 5 个、`domain/media/` 9 个）；③ `application/` 下**四个同名的 `pipeline/` 子包**，同一条字幕管线被劈到两个父包（编排在 `task/`、三个被编排件在 `subtitle/`，全都带 `Subtitle` 前缀） | 否掉「把三条内容域收进 `application/task/` 下」—— `domain/media` 与 `domain/workspace` 是**跨任务种类复用**的（N2：将来配音任务照样要取媒体、抽音频、转写），收进 `task/` 下等于在包结构上宣称「它属于字幕任务」。`IntermediateFile` **单列 `domain/intermediate/`**：它缓存的是 `source.mp4` / `audio-*` / `source.info.json` 这类**素材**，两条轴都不属于，硬塞进任一条会让那条轴的名字说谎 |
| **T9** | **`taskNo` 由 `RunRef` 带，不放进 `TaskRun`**：`RunRef.of(TaskNo, TaskRun)`、`TaskRunProgress.of(RunRef, TaskRun, StageProgress)`；`SubtitleTaskService` 造一次 ref 随 `pipeline.run(run, ref, workspace)` 一路传；`advance` / `finish` 收 ref；SSE 端点用路径上的 taskNo 直接拼 ref，不回查。`TaskRun` **只保留内部 `taskId`** | ① `RunRef(TaskNo, runNo)` 本来就是 run 的对外地址，在实体上再放一个是**第二个家**；② 那个家**落不了库**（`task_run` 没有 `task_no` 列），只能读回时补查 `subtitle_task`；③ 那三次补查**是纯浪费** —— 三处读调用方（`handleSubmit` 的 409 路径、`resolveRun`、`findByTaskNo` 拼 `lastRun`）手里都已经有 `SubtitleTask`；④ `restore(id, taskId, taskNo, …)` 收一对裸参数，配对只靠「仓储是唯一调用方」兜着；⑤ DB 层定死单来源、实体层带两个，与 implement.md 自相矛盾 | 否掉原定（2026-10-18 早）的「`TaskRun` 加 `TaskNo` 字段」—— 上面五条；也否掉「只传裸 `TaskNo` 不传 `RunRef`」—— 同一个东西传两遍，`RunRef` 就是那个载体。**连带**：`TaskRunRepository` 补 `findByTaskIdAndRunNo(long, int)`（`resolveRun` 要用，S1 漏了） |

## Requirements

### 身份键（C7–C12）

1. 新增 `domain/workspace/port/MediaIdentityResolver`（纯契约）+ 实现落 `infrastructure/provider/identity/bilibili/`。**只做 B 站**（C7），规则做成策略。
2. URL 路线身份 = **`bvid` + `p`**（C8）：`av...` 换算成 `bvid`、`p` 缺省 1；`SourceKey.value()` = `bvid|p`。
3. 规范化（C9）：剥 `trackid` / `spm_id_from` / `vd_source` 这类跟踪参数；`/video/` 段取 `BV...`。**长链零网络请求**。
4. 抽不到才跟一次跳转（C10/C11）：**超时 2 秒 + 进程内有界缓存**（短链 → 长链稳定不变）；展开仍拿不到 → 回落规范化 URL 当 `source_key` + 记日志。**这是降级路径不是失败路径**，不得把「抽不到」当异常抛出去。
5. 规范化后的 URL 写 `workspace.canonical_url`（只给人看）；`workspace.source_uri` 仍存 URL 原文，**脱敏红线不变**。
6. 上传路线身份 = **内容 sha256 全 64 位十六进制**（C12）。**订正现状**：`WorkspaceKey.ofDigest` 现在截前 12 位，本段起用全 64 位（`workspace.source_key` 是 `VARCHAR(512)`，装得下）。

### 提交路径（§7.1 / §7.2 / W1 / C63）

7. **W1 事务**：`workspace` 与 `subtitle_task` 同插、同事务（~~`@Transactional`，本仓首次引入~~，2026-10-18 改：**用 `TransactionTemplate` 显式圈边界**）。理由：身份键计算（短链跟跳转 / 流式算 sha256）必须留在事务外，于是事务方法只能被**同类内**调用 —— 而同类自调用会绕过 Spring 代理、`@Transactional` **静默失效**，那比显式模板危险。**这不是「本仓禁用 `@Transactional`」**：别处该用照用，只是本处不适用。
8. 命中分支：`SELECT workspace WHERE source_type + source_key` 命中 → 拿它的 `subtitle_task` → 进第 10 步。
9. **C63 并发分支**：未命中 → 同事务内插两行；撞 `uk_source` → **不报 409、不重试插入、不回滚重开**，在同一事务内用**锁定读**（`FOR SHARE`）重查 `workspace`，再用**锁定读**按 `uk_workspace` 重查 `subtitle_task`，两行到手后照常进第 10 步。**两行都要锁定读**（`subtitle_task` 用普通 SELECT 读不到对方刚提交的行 → 只能去 INSERT → 再撞一次键）。
10. **W2**：`TaskRunSupervisor.start(taskId, MAIN, null)` 插 `RUNNING` run；撞 `uk_active_run` → 409 + `{taskNo, runNo}`。
11. **身份键计算必须在事务外**：短链跟跳转是网络调用，而 C63 的落地约束② 写明「B 的事务里不许放慢操作」（事务期间持有 workspace 行的 S 锁）。上传路线同理：**流式写盘 + 算 sha256 也在事务外**。
12. C63 落地约束①：**撞键的 INSERT 与捕获它的 catch 必须在同一事务方法、同一类内直接调用**，中间不隔任何 `@Transactional` 边界；仓储方法保持裸 `@Repository`（S1 已如此，**别给仓储的 insert 加注解**）。
13. 关键词覆盖写（§7.1 第 5 步）：只有请求带了 `keywords` 才写（`null` = 不动库里那份，`[]` = 写空清单），在**同一事务**里。
14. 提交返回 **202 + `{taskNo, runNo}`**，异步执行体在事务提交后派发。

### run 生命周期（§4.1 / §7.6 / §7.7 / C43 / C15）

15. 新增 `application/task/TaskRunSupervisor`，四个动作、没有第五个：
    - `Optional<TaskRun> start(SubtitleTask task, TaskRunType type, String targetLanguage)`（~~`start(long taskId, …)`~~，2026-10-18 改：收实体，调用方不必再单独取 id）—— 插 `RUNNING` run，**插入即抢互斥**（撞 `uk_active_run` 返回空，由调用方翻 409）；`run_no` 由仓储按 `MAX+1` 生成回填（S1 已落）。
    - `void advance(RunRef ref, TaskRun run, StageProgress stage)`（~~`advance(TaskRun, StageProgress)`~~，2026-10-18 改：**T9** —— taskNo 由 ref 带，`TaskRun` 不带对外号）—— 落 `current_stage` + 广播。
    - `void finish(RunRef ref, TaskRun run, String errorMessageOrNull)` —— 落终态（`SUCCEEDED` / `FAILED`）+ **无条件广播终态 + 无条件清该 run 的流**。
    - `void recoverInterruptedRuns()` —— C15 三条 UPDATE。
16. **`finish` 是无条件收口（C43）**：先尝试落库（失败只记 ERROR），然后**无论落库成败**都广播终态、再清该 run 的流。顺序写死：**落库 → 广播 → 清流**，且后两步在 `try` 之外。
    > 这是「死订阅②」的正解：现状 `TaskProgressNotifier.failRegenerate` / `failTranslation` 把广播包在 `try` 里，落库一抛就**既没终态也不清订阅**，订阅者挂到 30 分钟空闲超时。**「收不了口」不能变成「订阅者死等」。**
17. **`INTERRUPTED` 的唯一写入路径是启动收口**（§4.1 的 D-6）—— `TaskRun` 实体**不给 `interrupt()`**（S1 已守）。
18. 启动收口落 `ApplicationRunner`（T2），**早于接受请求**这件事按「毫秒级窗口 + 单实例假设」接受并写进注释。三条 UPDATE + **打日志**（「上个进程有 N 个任务没跑完」的唯一痕迹）。
19. **`SemaphoreTaskConcurrencyGate` 保留**（限流 ≠ 互斥，C14 / §9.4g）。**语义变化要记账**：新模型里 run 在提交事务里就已是 `RUNNING`，排队期间 `current_stage` 为 `null` —— 前端看到「运行中但没有阶段」，本段接受。

### SSE（C57 / T4–T7）

20. **端口**：`application/task/port/TaskProgressBroadcaster`：
    ```java
    void publish(RunRef run, TaskRunProgress event);   // 发给这条 run 的全部连接；事件带 terminal 时发完即关
    void complete(RunRef run);                          // 无条件兜底：关掉这条 run 的全部连接 + 摘表
    ```
    实现落 `web/sse/SseTaskProgressBroadcaster`（持连接表 + 心跳 + 空闲超时）。**`open` 不上端口** —— 它返回 `SseEmitter`（HTTP 类型），由 controller 直接调实现类。
21. **`RunRef`**（`record RunRef(TaskNo taskNo, int runNo)`，落 `domain/task/model/`）：订阅与连接**共用这一个键**，**两张表变一张**。`Subscription` / `TaskSubscriptionRegistry` 整类退场。**它就是 `taskNo` 的家**（**T9**）：`RunRef.of(TaskNo, TaskRun)` 由调用方拼（它手里有 `SubtitleTask`），`TaskRun` 不带对外号；`TaskRunProgress` 的 `taskNo` / `runNo` 都从 ref 取。
22. **消息形状**：`TaskRunProgress` sealed 三变体（T5），公共字段 `taskNo` / `runNo` / `runType` / `status` / `stage` / `terminal`（`Translate` 另有 `targetLanguage`）。**`terminal` 的派生简化成 `status != RUNNING`** —— 在途会话那套隐式编码（`TaskStageEvent` + `TaskSessionRegistry.findRunningStage`）整块消失。
23. **路径**：`GET /api/tasks/{taskNo}/runs/{runNo}/events`（T6）。三种结果写死：**runNo 不存在 → 404**；**run 是 `RUNNING` → 开流**（连接建立即推一条 DB 快照）；**run 已终态 → 204 No Content**（T7）。
24. **连接结束 → 关流** 与 **run 终态 → 清流** 是**两个独立入口**，互不依赖、任一先到都成立。连接结束的收口回调**必须写成一个**：`SseEmitter` 的 `onCompletion` 是**单回调槽**（`ResponseBodyEmitter` 里 `completionCallback` 是单个字段，`onCompletion()` 是赋值不是追加，后者覆盖前者）。
    > **本段要修掉现状这个 bug**：`register()` 注册的摘表回调被 `open()` 的退订回调覆盖，导致客户端断连时 emitter 摘不出连接表（现在靠心跳 ≤30s 兜底）。
25. **多页面 / 刷新**：连接表是 `Map<RunRef, List<SseEmitter>>` —— 多个页面看同一条 run 各收一份；刷新时新旧两条短时并存、旧连接断开只摘它自己；`publish` 对每条连接单独 try/catch，单条失败不影响其余；桶空了就删桶。**键换成 `RunRef` 后，不同 run 的流互不波及**（今天按 `taskNo` 分桶，看 run 2 的页面会被 run 1 的终态波及）。
26. 心跳（30s 注释行）与空闲超时（30 分钟）**原样保留**。
27. 前端 `useTaskEvents.ts` **一个字不改**（它只认 `terminal` 就 `close()`，新字段不读）。

### 对外契约（C53–C58）

28. `GET /api/tasks/{taskNo}` = **身份 + `lastRun`（单值）**；扁平的 `status` / `currentStage` / `errorMessage` / `startedAt` / `finishedAt` **五个字段全删**；`artifacts` 字段**也删**（产物清单归 S3 的 `/artifacts`）。从未执行 → `lastRun` 为 `null`（契约允许，虽然按 W1+W2 同事务它到不了）。
29. 三个 POST（提交 URL / 上传 / 重生成 / 翻译 —— 共四个触发点、三类响应）统一 **202 或 409 + `{taskNo, runNo}`**，**同形状**；`TaskConflictResponse.existingTask` 删掉。**409 响应体不含文案**（design C56 的「同形状」），前端靠状态码判。
30. **「未就绪」仍是 400**，不是 409 —— 新模型里 409 只剩 `uk_active_run` 撞键一种语义。
31. `GET /api/tasks/{taskNo}/keywords` 形状不变（`{keywords, fingerprint}`）；**本段 fingerprint 仍由 `SubtitleKeywords.computeFingerprint()` 现算**（~~本段 fingerprint 仍从 `clean-segments.json` 读~~，2026-10-18 订正：**现状就不读文件** —— 它是同一份清单的纯函数，清洗阶段写进 `clean-segments.json` 的就是同一个值）；S3 起改从 `CLEANED` 行的 `keywordsFingerprint` 读 —— 记成欠账。
32. **下载端点 `GET /api/tasks/{taskNo}/artifacts/{fileName}` 随旧栈一起删**（S3 按 `type` + `file` + `?lang=` 重建）。本段**没有**任何取产物的 HTTP 手段，验收走绕道 `ls backend/data/works/<dir>/`（父 prd 已记账）。

### `subtitle_keyword` 换锚点（§1.5）

33. 该表**手工 `DROP` 后重建**：`workspace_key CHAR(12)` → `task_id BIGINT`、索引改名 `uk_subtitle_keyword_task_id`、**净增 `updated_at` 列**。三件事同一批做，写入点 = upsert 的 `ON DUPLICATE KEY UPDATE keywords = ?, updated_at = ?`（首插时两列同值）。
34. 端口换锚点：`findByWorkspaceKey` / `replace(workspaceKey, …)` → 按 `taskId`。值对象 `SubtitleKeywords` 与分隔符不动。
35. `schema.sql` 头部补**真正的手工 `DROP TABLE subtitle_keyword` 说明**（S1 只写了预告）+ `task` 表 DDL 整块删。

### 退场与改名（N1 / N5 / §9）

36. **顺序写死：先落定删除集 → 删 → 再对存活集改名**（N5）。混着做会把成本算错（`TaskStatus` 165 处 / 17 文件里大部分落在要删的文件上）。
37. **整块删**：`domain/task/` 整包（`VideoTask` / `TaskStatus` / `TaskInputType` / `TaskStageEvent` / `VideoTaskRepository`）、`MybatisVideoTaskRepository` / `VideoTaskEntity` / `VideoTaskMapper`、`TaskSessionRegistry`、`TaskArtifact` / `TaskArtifactFile` / `TaskArtifactResponse`、`TaskSnapshot` 的旧形状、`WorkspaceKey`、`WorkspaceKeyGenerator`、`scanArtifacts` / `openArtifact`、以及 T4 点名的 SSE 那四个类。
38. **改名让名**（N1）：`SubtitleArtifact`（record）→ `SubtitleStageOutput`；`AudioArtifact`（record）→ `AudioStageOutput`。**假朋友**：`DashScopeAsrProvider` 那 4 处 `TaskStatus` 是 `com.alibaba.dashscope.common.TaskStatus`（阿里 SDK 同名枚举）→ **显式排除，改名走 IDE rename symbol，不做文本替换**。
39. **`TaskNo` 不搬** —— T8 之后 `domain/task/` 就是「任务与执行」轴，它本来就在 `domain/task/model/`，原地不动（S1 欠账 2 就此关掉）。随旧类一起删的只有 `VideoTask.newTaskNo()`。
40. `SubtitleArtifactSpec` **保留文件名模板那半**（`getFileName` / `getLanguage` / `matches`，C24 文件名不变）；**删 `downloadable` 字段与 `isDownloadable`** —— 它唯一的消费者 `scanArtifacts` 在本段被删，而白名单那半由 S3 的 `/artifacts` 端点取代。
41. `MediaInput` 保留三个变体，**删 `getContentKey` / `dirLabel` / `ofTaskInput`**（身份键在提交时算完、目录名由 `source_key` 推导，管线不再自己算）。
42. `WorkspaceDir` 搬到 `domain/workspace/model/`（它本来就是工作区概念），工厂改 **`of(SourceKey)`** = `sanitizeName(source_key)`；`sanitizeName` 原地保留。**`of(label, key)` 退场**。
43. 四个 coordinator（`SubtitlePreparationCoordinator` / `SubtitleCleanupCoordinator` / `SubtitleTranslationCoordinator` / `AudioPreparationCoordinator`）**不再自己算 key 与目录**：签名收一个 `Path workDir`，由调用方（管线）从 `workspace.source_key` 推出后传入。

### 包结构重排（T8）

44. **`domain/` 平铺成六条轴**：
    - `domain/task/` —— 任务与执行：`SubtitleTask` / `TaskRun` / `model/{TaskNo, RunRef, TaskRunType, TaskRunStatus}` / `port/{SubtitleTaskRepository, TaskRunRepository}` / `package-info`。
    - `domain/subtitle/` —— 字幕内容：`SubtitleArtifact` / `SubtitleKeywords` / `model/{SubtitleStageName, SubtitleArtifactSpec, SubtitleContext, ArtifactType, SubtitleArtifactStatus, cleanup/, translation/, term/, common/}` / `policy/` / `port/{SubtitleArtifactRepository, SubtitleKeywordRepository, SubtitleCleanser, SubtitleTranslator, SubtitleContextSummarizer, TermLookupProvider}`。
    - `domain/intermediate/` —— **独立成包**：`IntermediateFile` / `model/{IntermediateFileType, IntermediateFileStatus}` / `port/IntermediateFileRepository` / `package-info`。
    - `domain/workspace/` 已存在；`domain/media/` / `domain/transcription/` 只接收本段搬入或新增的东西，**本身不动**。
45. **`application/` 把编排件移到被编排件旁边**：`SubtitlePipeline` 从 `application/task/pipeline/` 移到 `application/subtitle/pipeline/`（与 `SubtitlePreparationCoordinator` / `SubtitleCleanupCoordinator` / `SubtitleTranslationCoordinator` 同包），`application/task/pipeline/` 随之消失。`application/task/` 只剩用例与机制：`SubtitleTaskService` / `TaskRunSupervisor` / `TaskRunStartupRecovery` / `message/` / `port/` / `query/` / `exception/`。
46. **判据**：`application/X` 与 `domain/X` **一一对应**（`task` ↔ `task`、`subtitle` ↔ `subtitle`、`media` ↔ `media`、`transcription` ↔ `transcription`）—— 这条判据本身就是这次重排的目的，验收时逐对核。

### 文档与规范同步

47. **订正父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md) 的四处** —— 它落后于本轮的 T4–T7，而它是全任务树的权威，S2 的实施者会照它写代码。按本仓订正惯例，**design 直接重写为现状，不留旧说法**：
    - §3「`TaskStageEvent` 的替代者 = 连接建立即推 DB 快照」→ 补上替代者的实际落点（`TaskRunSupervisor.snapshot` + 端口实现），并写明中立注册表退场；
    - §6 C57 → 补端点形状（`/runs/{runNo}/events`）、终态 204、消息 sealed 三变体、订阅键 `RunRef`；
    - §6 C53 / C56 → 补「`artifacts` 字段随扁平五字段一起删」与「409 响应体不含文案」；
    - §9 退场清单 → 补 `TaskSubscriptionRegistry` / `Subscription` / `TaskProgressNotifier` / `TaskSseConnectionManager` 四个类，并把 `TaskStageEvent` 那行的「被谁取代」改成实际落点。
    **只改终态描述，不搬理由**（design 自己的章程：理由给一行 + 编号引用；T4–T7 的论证留在本 prd）。

48. 改写 [directory-structure.md](../../../.trellis/spec/backend/directory-structure.md)：**端口实现落 `infrastructure/`；签名需要 HTTP 类型时落 `web/<构件桶>/`**（理由：地基「HTTP 类型只在 web」优先，统一性「端口落点」让一步）。这条由 T4 逼出来，落 `web/sse/` 是本仓第一个。同时把 `subscribe/` 这个「中立机制包」的旧说法按现状订正。
49. 同步 [database-guidelines.md](../../../.trellis/spec/backend/database-guidelines.md)：表清单从 ~~六张~~ **七张**（`task` 由 S2 删）改成 ~~五张~~ **六张**（2026-10-18 订正：原写的「六→五」**少数了一张** —— 删 `task` 前是 7 张、删后 6 张；以 `schema.sql` 里 `CREATE TABLE` 的条数为准），`subtitle_keyword` 的锚点列与新增 `updated_at` 一并订正。**spec 是现状文档，直接重写为现状**，不留旧说法。

## 约束

- 编号引用（`C*` / `N*` / `W*` / `D-*`）的定义见父任务 [handoff.md](../10-15-subtitle-task-model-refactor/handoff.md)；实施以 design.md 的收敛版为准。
- **脱敏红线**：`source_uri`（URL 原文 / 上传文件原始名）不得写日志 —— 本段新增的日志（身份键回落、启动收口）与测试断言都不得打印它的值。
- **本段不写任何「只为这一段验收、后面必须删掉」的代码**（父 prd 两条原则之一）。
- 判编译一律 `clean test-compile`（`mvn compile` 的增量编译会给假绿）。
- **代码注释里不写 `C*` / `§` / `W*` / 段编号 / 日期**（`comment-guidelines.md` 的内容分流表 + pre-commit 钩子）—— 那些只写在本文档里。

## 范围外（明确交给后面哪一段）

| 不做 | 交给 | 为什么 |
|---|---|---|
| `subtitle_artifact` 的读写、换版落定（C41 四步）、`/artifacts` 端点、下载端点按 `type`+`file`+`?lang=` 重建 | **S3** | 本段主管线与后置会话**照旧只写文件、不落 artifact 表**；产物事实源仍是文件系统 |
| RECLEAN 的 W5 换版 + 下游级联 + 两种终态（覆盖型 / 孤儿型）、删下游文件 | **S4** | 那是 artifact 表侧的事，S3 之后才有落点 |
| TRANSLATE 的 W4 两行换版、复用判据只认 `status` | **S5** | 同上 |
| `intermediate_file` 登记、复用判据改造、指纹探测阶段、C31 级联 | **S6** | 本段**不做内容指纹**：提交路径手里只有 URL，拿不到指纹（C29） |
| `POST /api/maintenance/reconcile` | **S7** | 只读对账，要等 S6 的中间产物登记 |
| 网络调用超时（LLM / ASR / 术语查证） | **S8** | 本段**新引入**的短链跳转超时属于 C11，已在本段（需求 4） |
| 前端接新接口 | 另开任务（C60） | 本段结束时**前端一定点不通**（`TaskResponse` 形状变了、下载端点没了），这是预期内的 |
| 续跑 / 断点续传 | — | 重新触发即可（C17） |

## 验收标准

- [ ] **AC1 身份键**：同一个视频因跟踪参数不同的两个 URL（如 `?spm_id_from=` / `?vd_source=` 各一份）解析出**同一个 `SourceKey`** 与同一行 `workspace`；`av...` 换算成 `bvid`；`p` 缺省 1；`b23.tv` 短链跟一次跳转拿到 `bvid`（**长链零网络请求**用打桩计数验）；抽不到时回落规范化 URL 且**不抛异常**。
- [ ] **AC2 提交路径**：提交 URL → 202 `{taskNo, runNo}`；同 URL 二次提交 → **插新 run**（不是 409），`run_no` 递增；并发提交同一个**新** URL（**3 个并发请求**，2 个复现不出来）收敛到**同一条 `subtitle_task`**、~~不报 409~~（2026-10-18 订正：**这句与 design §7.1 第 5 步冲突** —— 三个请求收敛到同一个 task 之后各自都要插 run，`uk_active_run` 只放行一个，**另外两个必然 409**（响应体带同一个 `taskNo` 与 `runNo`）。实测：**1×202 + 2×409**，库里恰好 1 行 `workspace` + 1 行 `subtitle_task`，无重复键炸成 500。原意应是「不报**旧模型那种去重 409**」，那种 409 已随新模型消失）。
- [ ] **AC3 互斥**：同一 task 并发触发 → 一方 409 且响应体带 `taskNo`；`uk_active_run` 无残留（跑完后 `SELECT count(*) FROM task_run WHERE status='RUNNING'` 为 0）。
- [ ] **AC4 崩溃恢复**：`kill` 进程后重启 → 残留 run 变 `INTERRUPTED`、残留 artifact 变 `FAILED`、被收掉的 task `updated_at` 更新；启动日志有「收掉 N 行」。
- [ ] **AC5 SSE 端点**：`/runs/{runNo}/events` 三种结果各验一次（**404** / **RUNNING 开流**且首条是 DB 快照 / **已终态 204**）；事件是 `TaskRunProgress` 三变体之一、`runType` 与路径上的 run 一致；`terminal` 在 run 落终态时为真。
- [ ] **AC6 SSE 生命周期**：run 落终态时，**落库成功与落库失败两条路**都广播终态并清掉该 run 的连接（后一条用 mock 让仓储抛异常验）；**不同 run 的连接互不波及**；**客户端断连后 emitter 从连接表摘除**（现状那个 `onCompletion` 覆盖 bug 的回归测试）。
- [ ] **AC7 契约**：`GET /api/tasks/{taskNo}` 只有身份 + `lastRun`（无扁平五字段、无 `artifacts`）；三个 POST 的 202 与 409 同形状 `{taskNo, runNo}`；`/keywords` 锚点已换；旧下载端点已不存在。
- [ ] **AC8 关键词换锚点**：`SHOW CREATE TABLE subtitle_keyword` 是 `task_id BIGINT` + `updated_at` + `uk_subtitle_keyword_task_id`；覆盖写刷新 `updated_at`、首插两列同值。
- [ ] **AC9 旧件零残留**：Goal 里点名的那批类/表全仓 grep 为空；`TaskStatus` 的剩余命中只有阿里 SDK 那一处 import。
- [ ] **AC10 三种 run 跑通**：`MAIN` / `RECLEAN` / `TRANSLATE` 各跑一次，产物在盘上、run 状态与失败原因互不串台（`RECLEAN` 失败不把 `MAIN` 的记录改掉）。
- [ ] **AC11 回归**：`clean test-compile` 过；全量测试绿且**条数只增不减**（基线开工前重量）—— ~~2026-10-18 订正：本段是「换血」，老栈的测试随类一起删，条数字面必然下降~~，口径改为「**随删类走的除外**」，并须给出逐类账（本段实测 678 → 550：随类删除 −128、重写缩水 −20、新增 +20）；后端能起服务；`backend/data/works*` 从零重建后能跑通一次完整流程（提交 → 202 → SSE 跟到底 → `ls` 看到产物）。
- [ ] **AC12 包结构**：`domain/` 是 `task` / `subtitle` / `intermediate` / `workspace` / `media` / `transcription` 六包平铺，`domain/subtitle/` 里不再有任务轴的类；`application/` 下没有 `task/pipeline/`，`SubtitlePipeline` 与三个 Coordinator 同在 `application/subtitle/pipeline/`；**`application/X` 与 `domain/X` 逐对核，一一对应**。
- [ ] **AC13 文档与规范同步**：`design.md` 的四处已按现状重写（§3 替代者、§6 C57/C53/C56、§9 退场清单），且它不再说「保留中立注册表」；`directory-structure.md` 新增的端口实现落点规则已落，且 `web/sse/SseTaskProgressBroadcaster` 与该规则自洽；`database-guidelines.md` 表清单是五张。

## Notes

- **测试基线**：父任务记的 569 是静态数；S1 结束时静态数 **649**（`grep -c '@Test\|@ParameterizedTest'`）。**开工前重量一次**（先 `rm -rf backend/target/surefire-reports` 再跑全量），以实测数为准。
- **分支**：本段既碰代码、又碰文档与规范（需求 47/48/49），按 [git-workflow](../../../.trellis/spec/guides/git-workflow.md) **都要开 feature 分支**，从当前 `dev` 切 `feature/subtitle-model-submit-cutover`。
- **已知方向（本段不做）**：`SubtitlePipeline` 现在一个类扛三种 run（`run` / `regenerate` / `translate`），将来按 run 类型拆成三条线会更清晰。**本段不拆**（T8 只把它移到被编排件旁边）—— 那个拆分在 T8 之后是「把同一个包里的四条线各自拉直」，成本比现在低。
- **环境命令**（判编译、跑测试、起服务、推送代理）见 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。起冒烟实例后按 PID `taskkill`（`TaskStop` 杀不掉子 JVM）。
- **本段已知的两处「欠账在段内」**：`/keywords` 的 fingerprint 仍读文件（需求 31，S3 换表）；RECLEAN 的进度百分比沿用主管线区间（从 `CLEAN_SUBTITLES` 的中段起，不是 0→100）—— 两者都**不改**，记在 [implement.md](./implement.md) 的欠账清单里。
- **本段结束时哪些东西还没到位、交给哪一段**：见 [implement.md](./implement.md) 末尾的欠账清单，实施完必须逐条核对。
- **开工前有一道门**：`implement.md` **步骤 0** 要求先把预期目录结构（含退场清单）给项目所有者过一眼，点头之后才开分支、才写第一行代码。
