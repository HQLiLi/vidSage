# S8 设计：网络超时收口 + spec 落笔 + 旧件清查

> 父终态设计：[../10-15-subtitle-task-model-refactor/design.md](../10-15-subtitle-task-model-refactor/design.md)（权威）。
> 本文只写**本段的技术取舍**：为什么这么落、落在哪、代价是什么。
> 范围与验收见 [prd.md](./prd.md)；执行顺序见 [implement.md](./implement.md)。

---

## 0. 与现状代码的校准（2026-10-20 实测）

父设计与交接对 C46 的原有判断是「**入口只有一个：网络调用没有超时**」。实测下来这句**部分失准**，
本段按实测口径做，并把两处订正回写父文档（§4）。

### 0.1 「网络调用没有超时」只对一条路线成立

| 调用 | 客户端 | 现状 |
|---|---|---|
| LLM 清洗 / 翻译 / 摘要（**DeepSeek**） | Spring AI `DeepSeekChatAutoConfiguration` → **Boot 自动配置的 `RestClient.Builder`** | **无超时**。`DeepSeekChatProperties` 根本没有 timeout 字段；Boot 的 `spring.http.clients.read-timeout` 默认 `null` = 不限 |
| LLM（**Mimo / OpenAI**） | 官方 OpenAI Java SDK + OkHttp | **已有 60s**（`spring.ai.openai.chat.timeout` 默认值，`AbstractOpenAiProperties.getTimeout()` 已确认接线） |
| 术语查证 | Boot 的 `RestClient.Builder` | 无超时 |
| ASR 结果 JSON 下载 | Boot 的 `RestClient.Builder` | 无超时 |
| 短链跳转（`b23.tv`） | 自建 `JdkClientHttpRequestFactory` | **已有 2s**（`vidsage.identity.bilibili.short-link-timeout`） |
| ASR 主等待 | DashScope SDK 内部 OkHttp | 有 **per-request** 默认（connect 120s / write 60s / read 300s），但**管不住轮询循环**（§2） |
| 外部进程 | `ExternalProcessRunner` | **已有**，强制正数超时、默认 300s |

**结论**：真正需要补的只有「走 Boot `RestClient.Builder` 的那四条」，而它们能被**同一处配置**覆盖
（`spring.http.clients.*`，见 §1.1 的废弃提醒）。

### 0.2 真正的硬缺口不是"没设超时"，是"循环没有出口"

`DashScopeAsrProvider:99` 调的 `transcription.wait(...)`，反编译 SDK 2.20.6 的 `AsynchronousApi.wait` 确认：

```
轮询 GET /tasks/{id}
  → FAILED / CANCELED / UNKNOWN / SUCCEEDED  → 返回
  → 其它（PENDING / RUNNING）                → sleep(1s→5s 退避) → goto 轮询
  → ApiException 且 503/504                  → goto 轮询（不计数、不封顶）
```

**没有任何累计上限。** 所以：

- 它**不是**"没设超时"能解释的问题 —— 每次 HTTP 请求都有超时，问题在"问多少次"没有上限；
- **配置解决不了**：`spring.http.clients.*` 与 `Constants.connectionConfigurations` 都只管单次请求；
- **中断也解决不了**：`wait` 内部 `catch (InterruptedException)` 后**吞掉继续循环**
  （异常表 `245 → 254`，`254: goto 96`）。所以「丢进 `Future` + `cancel(true)`」这条路**无效**。

C46 记的两条后果就是从这里来的：① 那条 `RUNNING` 行永久占锁，只有重启能解；
② 并发闸门许可泄漏（`SemaphoreTaskConcurrencyGate` 的 `finally` 根本跑不到），
攒够 `max-concurrent` 后**整个队列永久堵死**。

---

## 1. 超时：一处配置，两处配套

### 1.1 落法：全局配置，不各 provider 自持

```yaml
spring:
  http:
    clients:
      connect-timeout: 10s
      read-timeout: 120s
```

> ⚠️ **是 `spring.http.clients.*`（复数），不是 `spring.http.client.*`。**
> 后者在 Boot **4.0.0 起已废弃**（`spring-boot-http-client` 的配置元数据里记着
> `deprecation.replacement = spring.http.clients.*`）。两者描述文字一模一样，抄错了不报错、只是不生效 ——
> **别改回旧名**。

**为什么不按 `BilibiliIdentityProperties.shortLinkTimeout` 那个先例给每个 provider 各加一个字段**：
那个先例存在的理由是「短链要的是**特别短**的 2s，且要自建 request factory 禁自动跳转」——
是**特殊性**逼出来的，不是「每个 provider 都该自持超时」这条通则。
本段这四条调用是同一类外部依赖、要的是同一个量级，逐个配等于把一份判断抄四遍，
且**新增调用点必漏**（新人加一个 provider 不会想起来去配）。

**代价（诚实记账）**：粒度为 0，四条调用共用一个数。将来若某条确实需要不同的量级，
再从全局里把它拆出去 —— 到那时"要不要拆"有真实依据，现在没有。

### 1.2 配套一：收 Spring AI 重试次数

`spring.ai.retry.max-attempts` 默认 **10**，退避 2s → 10s → 50s → 封顶 180s。
而**读超时抛出的 `ResourceAccessException` 在重试白名单里**（另含 `TransientAiException`），
会被老老实实重试到底。只设超时不收重试，算下来最坏是：

```
11 次 × 120s + 退避 1322s ≈ 44 分钟
```

44 分钟才 `FAILED` —— 那条 `RUNNING` 行和闸门许可就这么被占着。比"永久"好，但离 C46 要的"收敛"太远。

```yaml
spring:
  ai:
    retry:
      max-attempts: 3
```

**理由**：重试 10 次是给「偶发 5xx」设计的；对「超时」这类错误重试 10 次基本没有意义 ——
服务真挂了，第 10 次也不会好。

> ### ✅ 实测结论（2026-10-20，本段动手时跑出来的）
>
> **`max-attempts: 3` ⇒ 3 次重试 ⇒ 每次调用最多 4 次尝试。** 单次调用最坏
> `4 × 120s + (2 + 10 + 50)s ≈ 542s ≈ 9 分钟`。
>
> 两条证据：
> 1. **接线**：`SpringAiRetryAutoConfiguration` 把 `getMaxAttempts()` 传进
>    `RetryPolicy.Builder.maxRetries(...)` —— 属性叫 attempts，语义是 **retries**。
> 2. **跑出来**：把 `spring.http.clients.read-timeout` 临时压到 `1ms`、触发一次翻译，
>    每次 LLM 调用恰好打 3 条 `Retry error. Retry count:N`，退避间隔 `2s / 10s / 50s`，
>    然后退避序列**从 2s 重新开始**（= 上一个调用已放弃、下一个批次开始了）。
>
> ⚠️ **做这个探针时别被计数器的数字骗了**：那条 WARN 里的 `Retry count` 来自
> `SpringAiRetryAutoConfiguration$1` 里一个 **`AtomicInteger` 实例字段**，挂在单例 `RetryTemplate` 的
> listener 上，**跨调用累加、从不归零**。判断"一次调用重试几次"要看**退避间隔什么时候重置**，
> 不是看计数器的绝对值。
>
> 对照默认值 10：`11 × 120s + (2+10+50+180×7)s ≈ 2642s ≈ 44 分钟`。

### 1.3 配套二：ASR 那条不走这里（见 §2）

`transcription.wait` 的循环不受 `spring.http.clients.*` 影响 —— 它是 SDK 自己的 OkHttp 客户端。
而且就算给它设了超时，问题（循环无出口）依旧。

### 1.4 取值依据

| 值 | 取法 |
|---|---|
| `connect-timeout: 10s` | 建连失败（DNS / TCP 握手）要快速暴露，不该耗。常见取值 5–10s |
| `read-timeout: 120s` | 比 Spring AI 给 OpenAI 的默认 60s 宽一档（清洗/摘要是长 prompt 长输出，高峰期首字延迟几十秒），比 DashScope SDK 的 300s 紧。常见区间 60–180s，取中 |
| `max-attempts: 3` | 见 §1.2（实测：3 次重试 = 4 次尝试，最坏 ≈9 分钟） |

**这三个数是工程取值，不是产品指标。** 判据是「比正常最坏耗时宽一档、又能收敛」——
别拿它当 SLA 论证（与「进度百分比那类展示用数值不承担产品意义」同一口径）。

---

## 2. ASR 轮询改成有界

### 2.1 形状：自己拿 `fetch()` 写循环

`DashScopeAsrProvider` 里 `transcription.wait(queryParam)` 换成私有方法
`awaitTranscription(queryParam, taskId)`，内部自己轮询：

```
deadline = now + properties.transcriptionTimeout()
loop:
  result = transcription.fetch(query)          // 单次 GET，SDK 不检查状态、不循环
  status = result.getTaskStatus()
  if status ∈ {SUCCEEDED, FAILED, CANCELED, UNKNOWN}: return result
  if now >= deadline: 抛 IllegalStateException（带 taskId 与已等时长）
  sleep 5s
```

**出口集合与 SDK 的 `wait` 逐字一致**（`FAILED` / `CANCELED` / `UNKNOWN` / `SUCCEEDED`）——
这样下游那套「无语音降级」「子任务失败」的分支**一行都不用改**。

### 2.2 三条要点

**① 用 `fetch` 不用 `wait`。** `Transcription.fetch(queryParam)` 返回的与 `wait` **同一个类型**
（`com.alibaba.dashscope.audio.asr.transcription.TranscriptionResult`），只是它是**单次查询、不循环**
（`AsynchronousApi.fetch` 字节码确认：一次 GET 就返回）。所以这是**替换调用形态**，不是重写映射逻辑。

**② 轮询间隔固定 5 秒，不搬 SDK 那套 1s→5s 退避。** 理由：ASR 是**分钟级**任务，
5 秒粒度足够；SDK 那套「1s 起步、每 3 次翻倍」是为了让**秒级**任务快速返回，
而我们第一次查询发生在提交后 0 秒（先查再睡），本来就没有那个优化空间。
固定间隔的代码里没有退避状态要维护，读起来是一眼的事。
总请求数 = deadline ÷ 5s（15 分钟 → 约 180 次），量级不构成问题。

**③ 异常分类要跟 SDK 对齐，但受 deadline 约束。**

| 异常 | 处理 |
|---|---|
| `ApiException` 且 status 503 / 504 | 当「本次查询没成」→ 受 deadline 约束地继续轮询（SDK 原来是无条件 `goto`，我们加上上限） |
| 其它 `ApiException` | 直接抛（鉴权错、参数错，重试没意义） |
| `InterruptedException` | **恢复中断位**并抛 —— 跑在虚拟线程上，中断通常来自进程关闭，吞掉它会重演 SDK 那个坑 |

### 2.3 参数落位与取值

`vidsage.asr.dashscope.transcription-timeout`，默认 **15m**，加进 `DashScopeProperties`。

**为什么是 provider 自己的属性、而不是复用 §1 那个全局值**：这两个是**不同种类**的旋钮 ——
全局那个是「**单次 HTTP 请求**多久算超时」，这个是「**整个转写操作**多久算超时」。
后者天然要长得多（分钟级 vs 秒级），混成一个值必然错。

**为什么 15 分钟**：DashScope filetrans 是异步批处理，2 小时音频大致 5–10 分钟。
15 分钟 = 比正常最坏宽一档。**可配置**，素材普遍更长时调大即可。

`DashScopeProperties` 的类注释现在写着「提交与轮询由官方 SDK 内部处理，这里只关心鉴权与模型选择」——
本段之后**轮询归我们管**，这句要跟着改。

---

## 3. spec 落笔

### 3.1 新建 `backend/state-machine-guidelines.md`（D-6 口径）

**内容照父 `design.md` §4 搬，不新增判断**（避免"新造规范"）：

1. 三张状态机的图：`task_run.status` / `subtitle_artifact.status` / `intermediate_file.status`
2. 每个值的语义 + **全部**写入路径 + 路径类型（正常 / 恢复）
3. 两条不变量：**每个值恰好一条写入路径**；**零路径的死值不许留**
4. 一条使用说明：**加状态值前先回答两个问题** —— 谁写它？什么时候写？答不上来就是死值

**为什么不塞进 `database-guidelines.md`**（尽管它的建表契约表**已经逐表列了状态列**，
挨着放读者能一次读完 —— 这是甲方案最强的论据）：
那份文档第 5 行自述「**本文只管数据库侧契约**」，而 D-6 的核心不是"列是什么类型"，
是"这个值代表什么、谁允许写它"——那是**领域语义**，Java 侧也真的在抛 `IllegalStateException`，
DB 只是它的投影。硬塞进去会撞它自己声明的边界。

**为什么不塞进 `directory-structure.md`**：那份 503 行、讲"东西放哪个包、怎么命名"，跟状态机不同轴。

**代价**：spec 目录多一份文档。换来的是"改状态机的人不用去翻数据库文档"。

**边界**：本文档**不重复** `database-guidelines.md` 规则 3 的「状态列一律 `VARCHAR(32)`」——
那是列类型规则，留在原处；两处各加一个指针（§3.4）。

### 3.2 `backend/index.md` 索引失实订正

| 动作 | 内容 |
|---|---|
| **补** | 「规范索引」表加两行：`database-guidelines.md`（它 2026-09-30 就重建了，却只在下面那节里出现）、`state-machine-guidelines.md` |
| **删** | 「已删除规范的重建记录」一节里的 `database-guidelines.md` 条目 —— 它已经重建，留在"待重建"列表里读起来像还没做 |

### 3.3 spec 里拿已删类当先例（8 处）

判据：**换成现存名字，不删段落**（这些段落在讲别的规则，只是例子过期了）。

| 文件:行 | 现文 | 换成 |
|---|---|---|
| `backend/provider-pattern.md:30` | `VideoTaskRepository` → `domain/task/port/` | `SubtitleTaskRepository`（同类同落点） |
| `backend/quality-guidelines.md:159` | 同上 | 同上 |
| `guides/comment-guidelines.md:85` | `SubtitleRoute` / `SourceUri` / `VideoTask` 三轮校准 | 现存类的同类先例 |
| `guides/comment-guidelines.md:123` | `WorkspaceKey`「刻意不做 hex 正则校验」 | `SourceKey`（同一件事的现任承担者） |
| `guides/comment-guidelines.md:138` | `SubtitleKeywordRepository` / `VideoTaskRepository` | `SubtitleKeywordRepository` / `SubtitleTaskRepository` |
| `guides/comment-guidelines.md:141` | `WorkspaceKey` / `TaskNo` | `SourceKey` / `TaskNo` |
| `guides/comment-guidelines.md:151` | `VideoTaskServiceTest` | 现存的同类测试类 |
| `guides/comment-guidelines.md:191` | `isEventTerminal(TaskStatus)` | 现存的同类方法 |

**改之前先 grep 算账**：每个候选新名字都要先确认它在仓库里的**实际语义**跟被替换的那个对得上
（先算账、再给名字，别先挑名字再补理由）。
**阿里 SDK 的 `TaskStatus` 是假朋友**（`provider-pattern.md:159` 已明写它是 SDK 类型、
`error-handling.md:119` 的 `finished.getTaskStatus()` 是 SDK 调用），**这两处不动**。

> **口径（2026-10-20 审查轮定稿，覆盖本节原先的"换成现存名字，不删段落"）**：
> **规范只写"现在是什么"，不写"什么已经退场"。** 读者不需要知道谁退过场，只需要知道现在该怎么做。
> 所以旧类名一律去掉，做法二选一：**换成现存类名**，或**把例子改成不点名**。
> 实施中途曾把 `SubtitleRoute` / `SourceUri` 当"历史校准例"保留、靠一句"这三个类已退场"兜住 ——
> **作废**：那句话本身就是"读者不需要知道的事"，等于在规范里塞变更史。

### 3.4 指针

| 文件 | 加什么 |
|---|---|
| `database-guidelines.md` 规则 3 末 | 「状态值怎么定、谁能写 → state-machine-guidelines.md」 |
| `directory-structure.md` 讲状态机归属那句 | 同上 |

理由：同一件事现在分居两份文档，读者从任一处进来都该被送到另一处，而不是以为"就这些"。

---

## 4. 父文档同步

| 文件 | 位置 | 动作 |
|---|---|---|
| 父 `design.md` | §7.6 分工表末行「线程卡死（挂住不动） → 靠网络调用超时收敛（C46）」 | 更新为现状：LLM 走全局读超时 + 重试收窄；**ASR 那条是循环无出口，已改有界轮询** |
| 父 `design.md` | §7.6 「残余风险（留档）」 | 保留（非网络的挂住仍没人兜），核对措辞仍准 |
| 父 `handoff.md` | C46 的「**实现时要查**：DashScope SDK 的 `transcription.wait(...)` 有没有带超时的重载」 | 有答案了：**没有带超时的重载，且内部循环无出口、中断被吞**。按订正惯例处理（废弃的直接删；prd 用删除线 + 日期） |

---

## 5. 决策记录

| # | 决策 | 备选与否决理由 |
|---|---|---|
| **D1** | 超时落**全局** `spring.http.clients.*` | 否决「各 provider 自持 `*Properties` 字段」：四条调用同类同量级，逐个配等于把一份判断抄四遍，且新增调用点必漏 |
| **D2** | **连重试次数一起收**（`max-attempts: 3`） | 否决「只设超时」：重试把 120s 放大成 44 分钟，那不算收敛 |
| **D3** | ASR **自己写有界轮询**（`fetch` + deadline） | 否决「`Future.get(timeout)` + `cancel(true)`」：**实测无效** —— SDK 吞掉 `InterruptedException` 继续循环。否决「不修只记账」：那是 C46 记的"队列永久堵死" |
| **D4** | 轮询**固定 5 秒** | 否决「搬 SDK 的 1s→5s 退避」：那是给秒级任务做的优化，我们的任务分钟级、且先查再睡，没有优化空间 |
| **D5** | 状态机口径**新开一份文档** | 否决「塞进 `database-guidelines.md`」：撞它自述的边界（只管数据库侧契约）。否决「塞进 `directory-structure.md`」：不同轴 |
| **D6** | spec 旧名清理**只做 backend + guides** | 否决「连 `.trellis/spec/frontend/` 一起改」：那几处会在 C60 前端任务落地时整段重写，现在改是白工。**记一条待办** |
| **D7** | 父任务 **AC1 / AC2** 由本段收证据 | 这两条正好是本段第 2、3 件事的产出；AC3–AC9 留给父任务的集成评审那轮 |
| **D8** | **（审查轮追加）删掉零生产调用点的领域迁移方法** | 见下 |
| **D9** | **（审查轮追加）把状态守卫补进 SQL** | 见下 |

### D9：三条 UPDATE 补 `AND status = ...`

D8 删掉 Java 守卫之后，`READY → SUPERSEDED` 与 `SUPERSEDED → DELETED` 的合法前提只剩文档。
而这三条对应的 UPDATE 恰好**只带 `WHERE id = ?`**：

| UPDATE | 补的守卫 |
|---|---|
| `SubtitleArtifactMapper.markSupersededAndClearFiles` | `AND status = 'READY'` |
| `SubtitleArtifactMapper.markDeleted` | `AND status = 'SUPERSEDED'` |
| `IntermediateFileMapper.markDeleted` | `AND status = 'SUPERSEDED'` |

**为什么不只是"整齐"**：`markSupersededAndClearFiles` 清掉的文件列**不可逆** ——
下游译文是孤儿型，文件列是回收时定位文件的唯一线索；传错一个已经作废过的 id 就会把它再清一次，
之后那份文件永远没人认领。`markDeleted` 反过来：把还 `READY` 的行标成 `DELETED`，
而它登记的文件可能刚被当成当前版发出去。

**为什么是严格收紧**：三个调用点的行都是「先按状态查出来、再拿 id 过来」
（`findReadyByTaskIdAndTypeAndLang` / `findReadyByTaskId` / 级联后回收），happy path 零变化。

**验证**：单测里 mapper 是 mock 的，测不到 SQL —— 所以**在真库上跑**：
一次指纹级联 + 一次翻译 + 一次重新生成，三条守卫各自命中 `Updates: 1`（产物取代 ×2、
产物标回收 ×1、中间产物标回收 ×3），四个 run 全 `SUCCEEDED`，零回收失败、零重试。

**顺带写进 spec 的两条**（`state-machine-guidelines.md` §4 改成一张"守卫在哪"的全表）：
`markReady` 不需要守卫（`READY` 从三个值都能到，条件恒真）；`updateReady` 自己不带条件是因为
守卫在它前面的 `completeCreation` —— **别为了整齐给它加条件然后删掉领域的守卫**。

### D8：为什么删 `supersede()` / `confirmDeletion()` / `revive()`

**问题不是"有几个没用的方法"，是状态机被建模了两遍**：

| | 谁在写 | 有没有守卫 |
|---|---|---|
| **Java 侧**：领域实体的迁移方法 | **生产零调用**，只有单测在调 | 有（`validateStatus`） |
| **SQL 侧**：仓储/映射器的批量 UPDATE | 生产全走这里 | 部分有（见 `state-machine-guidelines.md` §4） |

后果三条：① 读代码的人以为"作废必须过那道校验"，生产路径整个绕过它 —— **守卫是假的**；
② 那些单测绿着，但覆盖的是一段永不执行的生产代码；③ 同一件事两种表达，改状态机要改两处、只有一处有效。

**为什么删得掉**：`restore(...)` 是公开的、注释明写「接受任何状态，不做迁移校验」，
所以测试要造 `SUPERSEDED` / `DELETED` 的夹具走它就行 —— 迁移方法不是夹具的唯一来源。

**否决的备选**：「反过来让生产走领域方法」（读出来 → 调方法 → 写回）。作废是**集合式**操作
（一次级联一整批、回收按行删完文件再标），逐个实体加载再存回比一句 UPDATE 差得多。

**代价（诚实记账）**：Java 侧对 `READY → SUPERSEDED` 与 `SUPERSEDED → DELETED` 的守卫**没了**，
这两条迁移的合法前提从此只在 SQL 与文档里 —— 所以**同轮把守卫补进了 SQL**（见 D9）。

---

## 6. 边界与已知窗口

- **超时后 DashScope 服务端任务不会被取消** —— 我们只是不再等它。远端任务会自己跑完（或自己失败），
  不会因为我们不等了而泄漏资源。**不为此调 SDK 的取消接口**（收益为零，多一条失败路径）。
- **15 分钟的 deadline 对超长素材可能偏紧** —— 它是配置项，调大即可。取值判据写在 §2.3。
- **全局 `read-timeout` 是一刀切** —— 见 §1.1 的代价记账。将来要拆，依据是"某条调用确实需要不同量级"。
- **非网络的挂住仍没人兜**（C46 残余风险，本段不扩范围）。
- **`spring.ai.retry.*` 是全局的** —— 它同时管 OpenAI 路线。收窄到 3 次对那条路同样生效，
  这是**有意的**（两条路线的重试语义没有理由不同）。
