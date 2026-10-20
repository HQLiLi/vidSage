# S8 执行计划：网络超时收口 + spec 落笔 + 旧件清查

> 范围与验收见 [prd.md](./prd.md)；技术取舍见 [design.md](./design.md)。
> 环境命令（判编译、跑测试、起服务、推送代理）见父 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。

---

## 0. 开工前的三件事

1. **重量测试基线**（父 prd 要求每段动手前重量一次）：

   ```bash
   cd backend
   grep -rn "@Test\|@ParameterizedTest" src/test --include=*.java | wc -l
   ```

   S7 归档时的值是 **708**，对不上就先查清楚再动手。

2. **切分支**：从 `dev` 切 `feature/subtitle-hardening-wrapup`（`dev` 当前领先 `origin/dev` 7 笔，
   不影响切分支）。**动任何代码之前先切**。

3. **`mvn clean test-compile` 先跑一次**（`mvn compile` 增量编译会假绿，判编译一律带 `clean`）。

---

## 段内顺序（五步）

| 步 | 内容 | 依赖 |
|---|---|---|
| 0 | 预期文件树与关键形状过审（**门**） | — |
| 1 | 全局超时配置 + 重试收窄 + **实测钉死** | — |
| 2 | ASR 有界轮询（代码 + 单测） | — |
| 3 | spec 落笔（新文档 + 索引 + 指针） | — |
| 4 | spec 旧名清理 | — |
| 5 | 父文档同步 + 全量回归 + 验收 | 1–4 |

**步骤 1 / 2 / 3 / 4 互相独立**，可各自单独回滚、单独审。**步骤 5 必须最后**。

---

## 步骤 0：预期文件树与关键形状过审（**门**）

动手前先把下面这份清单给项目所有者过一眼 —— 尤其步骤 2 那个方法的形状。

**新增文件**：`.trellis/spec/backend/state-machine-guidelines.md`

**改动文件**：

| 文件 | 改什么 |
|---|---|
| `backend/src/main/resources/application.yml` | 增 `spring.http.clients.connect-timeout` / `read-timeout`、`spring.ai.retry.max-attempts`；`vidsage.asr.dashscope` 增 `transcription-timeout` |
| `backend/src/main/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java` | `transcription.wait(...)` → 自建 `awaitTranscription(...)`；类注释里「轮询由 SDK 完成」那句改准 |
| `backend/src/main/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeProperties.java` | 加 `transcriptionTimeout` 字段；类注释改准 |
| `backend/src/test/.../DashScopeAsrProviderTest.java` | 补有界轮询的三个分支用例 |
| `.trellis/spec/backend/index.md` | 索引表补两行；「已删除规范的重建记录」删 `database-guidelines.md` 条目 |
| `.trellis/spec/backend/database-guidelines.md` | 规则 3 末加指针 |
| `.trellis/spec/backend/directory-structure.md` | 状态机归属那句加指针 |
| `.trellis/spec/backend/provider-pattern.md`、`quality-guidelines.md`、`.trellis/spec/guides/comment-guidelines.md` | 旧名换现存名字（8 处） |
| 父 `design.md`、父 `handoff.md` | C46 订正 |

**关键形状**（步骤 2）：

```java
/**
 * 轮询等待转写任务结束，带总时长上限。
 * ...
 */
private TranscriptionResult awaitTranscription(TranscriptionQueryParam query, String taskId) { ... }
```

**过审点**：① 轮询间隔固定 5 秒（design D4）；② 出口集合与 SDK 的 `wait` 逐字一致；
③ 超时抛 `IllegalStateException` 并带 `taskId` 与已等时长。

---

## 步骤 1：全局超时配置 + 重试收窄 + 实测钉死

### 1.1 改配置

`backend/src/main/resources/application.yml`：

```yaml
spring:
  http:
    clients:                 # ⚠️ 复数。单数 spring.http.client.* 在 Boot 4.0.0 起已废弃，抄错了不报错、只是不生效
      # 建连失败要快速暴露
      connect-timeout: 10s
      # LLM 清洗 / 翻译 / 摘要、术语查证、ASR 结果下载共用；见 tasks/10-17-subtitle-hardening-wrapup/design.md §1
      read-timeout: 120s
  ai:
    retry:
      # 默认 10 会把 120s 放大成约 44 分钟才收敛
      max-attempts: 3
```

`vidsage.asr.dashscope` 下加：

```yaml
      # 单次转写操作的总时长上限（不是单次 HTTP 请求的超时；那个走 spring.http.clients.read-timeout）
      transcription-timeout: 15m
```

**注释按 [comment-guidelines](../../.trellis/spec/guides/comment-guidelines.md) 写**：
留「为什么是这个值 / 这个值跟谁是一对」，不留变更史。

### 1.2 实测 A：证明 `spring.http.clients.read-timeout` 真的管到 LLM（**这条是本段最吃重的假设**）

临时把 `application-local.yml`（git 不跟踪）里的读超时压到 `1ms`：

```yaml
spring:
  http:
    clients:
      read-timeout: 1ms
```

起服务、跑一次翻译（或清洗）。**预期**：LLM 调用快速失败，日志里出现读超时异常。
→ 证明这条配置确实到达了 DeepSeek 的 `RestClient`（design §0.1 的判断成立）。

**复原**：删掉这段临时覆盖，`application-local.yml` 不留痕。

### 1.3 实测 B：钉死「`max-attempts: 3` 对应几次尝试」（**AC2**）

> **✅ 2026-10-20 已做完，结论已回写 [design.md](./design.md) §1.2**：
> **3 次重试 = 4 次尝试**，最坏 ≈9 分钟。做法与踩到的坑记在下面，供复现。

**做法**：实测 A 那次失败本身就走到了重试路径（读超时是**可重试**的），所以不用另起一次探针 ——
从同一份日志里读退避间隔即可。若要单独复现：把 `application-local.yml` 的
`spring.ai.deepseek.base-url` 临时指到必然连不上的地址（如 `http://127.0.0.1:9/`），跑一次翻译，
看 `SpringAiRetryAutoConfiguration` 的 WARN。

**判读（这步有个坑）**：那条 WARN 里的 `Retry count` 数字**不能直接读** ——
它来自 `SpringAiRetryAutoConfiguration$1` 的一个 `AtomicInteger` **实例字段**，挂在单例
`RetryTemplate` 的 listener 上，**跨调用累加、从不归零**。
判"一次调用重试几次"要看**退避间隔什么时候重置回 2s**，不是看计数器的绝对值。

> 两条实测都用 `application-local.yml`（已在 `.git/info/exclude` 里），**不进仓库**；
> 跑完把临时覆盖删干净（`grep -c 'S8-PROBE' backend/src/main/resources/application-local.yml` 应为 0）。

### 1.4 验证

- `mvn clean test-compile` 过。
- 全量测试绿（本步不增删用例，基线应**不变**）。
- 起服务后跑一次真实翻译**正常走完**（AC1 的后半句）。

### 回滚

纯配置，`git checkout -- backend/src/main/resources/application.yml` 即可。**零代码依赖。**

---

## 步骤 2：ASR 轮询改成有界

### 2.1 改 `DashScopeProperties`

加 `@DefaultValue("15m") Duration transcriptionTimeout`。
**类注释要改** —— 现在写着「提交与轮询由官方 SDK 内部处理」，本步之后轮询归我们管。

**连带：测试里 10 处 `new DashScopeProperties(apiKey, MODEL)` 要跟着加第三个实参**
（先例：`BilibiliMediaIdentityResolverTest:41` 就是显式传 `Duration.ofSeconds(2)`）。
重复太多就在测试类里加一个私有工厂方法收口。

> **别在 record 上加一个 2 参便利构造器**：那会让默认值出现第二个来源
> （一个在 `@DefaultValue`，一个在便利构造器），迟早漂移。

### 2.2 改 `DashScopeAsrProvider`

`transcribe()` 里这段：

```java
var finished = transcription.wait(
        TranscriptionQueryParam.FromTranscriptionParam(param, submitted.getTaskId()));
```

换成：

```java
var finished = awaitTranscription(
        TranscriptionQueryParam.FromTranscriptionParam(param, submitted.getTaskId()),
        submitted.getTaskId());
```

新增私有方法（形状见步骤 0，逻辑见 [design.md](./design.md) §2.1）：

- `deadline = Instant.now().plus(properties.transcriptionTimeout())`
- 循环：`transcription.fetch(query)` → `getTaskStatus()`
  - `SUCCEEDED` / `FAILED` / `CANCELED` / `UNKNOWN` → 返回
  - 其它 → 过 deadline 就抛，否则 `Thread.sleep(5s)` 继续
- `ApiException` 且 status 503/504 → 同"未完成"处理；其它 `ApiException` → 直接抛
- `InterruptedException` → **恢复中断位** + 抛

**下游一行都不改**：出口集合与 SDK 的 `wait` 逐字一致，所以「无语音降级」「子任务失败」
「`transcriptionUrl` 缺失」那几个分支原样成立。

**`TranscriptionResult` 的类型要留意**：SDK 与项目各有一个同名类
（`com.alibaba.dashscope.audio.asr.transcription.TranscriptionResult` vs
`com.vidsage.domain.transcription.model.TranscriptionResult`）。现有代码用 `var` 回避了这个冲突，
**新方法照旧用 `var` 或全限定名，不要为此加 import**（会跟项目那个撞名）。

### 2.3 补单测

`DashScopeAsrProviderTest` 补三个用例（现有构造器已收 `TemporaryFileUploader` 替身，可复用）：

| 用例 | 断言 |
|---|---|
| **deadline 到点** | 替身让 `fetch` 永远返回 `RUNNING` → 抛 `IllegalStateException`，**且方法确实返回了**（不是挂住） |
| **正常成功** | `fetch` 返回 `SUCCEEDED` → 返回结果，不抛 |
| **503 不致命** | `fetch` 先抛 503、再返回 `SUCCEEDED` → 最终成功 |

> **替身怎么塞**：`Transcription` 是 `DashScopeAsrProvider` 的私有 final 字段。
> 若现有测试已有注入缝就复用；**没有就加一个包私有构造器重载**（现有代码已有
> `DashScopeAsrProvider(RestClient.Builder, DashScopeProperties, TemporaryFileUploader)` 这个包私有重载先例，
> 照它再加一层即可）。**别为此把字段改成非 final**。

**deadline 测试要能秒级跑完**：把 `transcriptionTimeout` 传成 `100ms` 这类极小值，
别让单测真等 15 分钟。轮询间隔若写死 5 秒会让这个用例至少睡 5 秒 ——
**把间隔也做成可注入的常量/字段**，或者让循环"先判 deadline 再 sleep"使得极小 deadline 在第一轮就命中。
**取后者**（不加参数）：循环体是「查 → 判终态 → 判 deadline → 睡」，deadline 极小则第一轮就抛。

### 2.4 验证

- `mvn clean test-compile` 过；全量测试绿（基线 **+3**）。
- **判据（AC3）**：`grep -rn "transcription.wait\|\.wait(" backend/src/main` 在 ASR provider 内**归零**。
- **AC4 真库**：起服务，跑一次真实的完整流程（提交一个 URL → 走完转写），确认转写结果正常落盘。

### 回滚

`git checkout` 这三个文件即可。**无表结构变更、无数据迁移。**

---

## 步骤 3：spec 落笔

### 3.1 新建 `.trellis/spec/backend/state-machine-guidelines.md`

内容**照父 `design.md` §4 搬，不新增判断**。四块：

1. 三张状态机的图（`task_run.status` / `subtitle_artifact.status` / `intermediate_file.status`）
2. 每个值的语义 + **全部**写入路径 + 路径类型（正常 / 恢复）
3. 两条不变量：每个值恰好一条写入路径；零路径的死值不许留
4. 使用说明：加状态值前先回答「谁写它？什么时候写？」

**搬的时候要核**：父 `design.md` §4 是终态口径，但 S4–S6.5 落地时有过订正
（级联从"比哈希"改成"无条件"、复用命中不落版）。**逐条对着代码核一遍**，
父文档与代码不一致的地方以**代码**为准（这是父 prd 自己定的口径）。

### 3.2 改 `.trellis/spec/backend/index.md`

- 「规范索引」表补两行：`database-guidelines.md`、`state-machine-guidelines.md`（各带「何时读」）
- 「已删除规范的重建记录」一节：**删掉 `database-guidelines.md` 那条** —— 它 2026-09-30 就重建了，
  留在"待重建"列表里是失实（废弃的直接删）

### 3.3 加指针

| 文件 | 位置 | 加什么 |
|---|---|---|
| `database-guidelines.md` | 规则 3（枚举列 `VARCHAR(32)`）末 | 「状态值怎么定、谁能写 → state-machine-guidelines.md」 |
| `directory-structure.md` | 讲状态机归属那句 | 同上 |

### 3.4 验证（**AC5 / AC6**）

- 新文档存在，四块齐全；**抽查** 3 条写入路径，都能在代码里指到落点
  （例：`INTERRUPTED` → 启动收口；`CREATING` → `SubtitleArtifactStore` 的占位插入；`DELETED` → C22 两阶段）。
- `backend/index.md` 的索引表含两行；「重建记录」里不再有 `database-guidelines.md`。

### 回滚

纯文档，单独 `git checkout` / `rm` 即可。

---

## 步骤 4：spec 旧名清理（8 处）

按 [design.md](./design.md) §3.3 的表逐处替换。

**每处替换前先 grep 算账**：确认候选新名字在仓库里的**实际语义**跟被替换的那个对得上
（例：`VideoTaskRepository` → `SubtitleTaskRepository` 是不是同类同落点；`WorkspaceKey` 的
「刻意不做 hex 正则校验」在 `SourceKey` 上还成不成立）。**别先挑名字再补理由。**

**假朋友不动**：`provider-pattern.md:159`、`error-handling.md:119` 的 `TaskStatus` 是阿里 SDK 类型。

### 验证（**AC7**）

```bash
grep -rn 'VideoTask\|WorkspaceKey\|TaskInputType\|TaskStatus' \
     .trellis/spec/backend .trellis/spec/guides
```

**归零**（`TaskStatus` 那两处 SDK 假朋友若仍命中，须**逐条确认是 SDK 类型**并在验收报告里点明，
不能只报一个数）。

**实测发现审计清单不全**（2026-10-20）：同一份 `comment-guidelines.md` 里另有 **5 处**
`SubtitleRoute` / `SourceUri`（审计只报了 6 处 `comment-guidelines` 的行号，漏了这几处）。

**处理口径（2026-10-20 审查轮定稿）**：**规范只写"现在是什么"，不写"什么已经退场"。**
读者不需要知道谁退过场，只需要知道现在该怎么做。所以**旧类名一律去掉**，做法二选一：
换成现存类名，或把例子改成不点名的说法。

| 处 | 做法 |
|---|---|
| `:85` 禁令标题 | **去掉三个类名**，只留「2026-10-12 定，三轮校准」 |
| `:110`（`SubtitleRoute` 的计数例） | **删掉这个例子**，保留同句里现存的 `TermVerification` 那个 |
| `:134`（`SubtitleRoute` 的边界句例） | 改成不点名：「一处是往枚举上贴的『不负责文件名派生』」 |
| `:149`（`SubtitleRoute` 的「四个服务合流于此」） | 改成不点名：「写成『四个服务合流于此』那种排场，是同一个毛病的重度版」 |
| `:168`（`SourceUri` 的类注释举例） | **换成 `SourceKey`**（当真的同类实例） |
| `:173`（`SourceUri` 的块行数举例） | 改成不点名 |

> **中途一度走了另一条路**：把 `SubtitleRoute` / `SourceUri` 当"历史校准例"保留、
> 靠禁令标题下一句「这三个类已退场」兜住。**已作废** —— 那句话本身就是"读者不需要知道的事"。

### 回滚

纯文档，单独回滚。

---

## 步骤 5：父文档同步 + 全量回归 + 验收

### 5.1 父文档同步（**AC9**）

按 [design.md](./design.md) §4 的表处理父 `design.md` §7.6 与父 `handoff.md` 的 C46。
订正惯例：**废弃的直接删；prd 用删除线 + 日期；design 直接重写为现状。**

### 5.2 全量回归（**AC10**）

```bash
cd backend
rm -rf target/surefire-reports          # 会留陈旧报告，量前先删
JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile
JAVA_HOME=/f/java_soft/java21 mvn -q clean test
```

- 全量绿；基线 = 步骤 0 的值 **+3**（步骤 2 的三个用例）。
- 起服务（测试红着时须 `-Dmaven.test.skip=true`，`-DskipTests` 不够）；
  **收工按 PID taskkill**（TaskStop 只杀 mvn 包装进程，子 JVM 继续占端口）。
- 一次完整流程：提交 → 产物 → 翻译 → 重新生成字幕。

### 5.3 父任务 AC1 / AC2 证据收齐（**AC11**）

**AC1（表与口径）** —— 出可核对的证据：

```sql
-- 六张表齐备
SELECT table_name FROM information_schema.tables WHERE table_schema = 'vidsage';
-- 索引与约束名（唯一约束与普通索引的名字 MySQL 照留，可以照名字核）
SELECT table_name, constraint_name, constraint_type FROM information_schema.table_constraints
 WHERE table_schema = 'vidsage';
-- 枚举列一律 VARCHAR(32)
SELECT table_name, column_name, column_type FROM information_schema.columns
 WHERE table_schema = 'vidsage' AND column_name IN
       ('source_type','run_type','status','artifact_type','file_type','active_flag');
```

外加：`schema.sql` 头部写明存量迁移步骤（`subtitle_keyword` 必须手工 `DROP` 一次）；
无 `FOREIGN KEY` 约束。

> ⚠️ **主键的约束名 MySQL 不保留** —— `information_schema` 里恒记 `PRIMARY`，
> 别拿 `pk_*` 去比，会误判成失败（`database-guidelines.md` 规则 1 的注已写明）。

**AC2（旧件零残留）** —— 复跑一遍清查（清单见 prd「现状与欠账」下方那段），确认仍全零命中。

### 5.4 收尾

- 逐条核对 prd 的 AC1–AC11。
- 报告（含基线变化账、实测 A/B 的结论、真库验收输出）→ **停等审查，不自行提交/合并**。

---

## 回滚点

| 落点 | 状态 | 停在这儿的代价 |
|---|---|---|
| **步骤 1 之后** | 超时生效、重试收窄 | 零代价；纯配置，可单独回滚 |
| **步骤 2 之后** | ASR 有界 | 零代价；可单独回滚，回到"无界但能跑" |
| **步骤 3 之后** | spec 有状态机文档 | 零代价；纯文档 |
| **步骤 4 之后** | spec 旧名清零 | 零代价；纯文档 |

**彻底回退**：`git revert` 本段提交；**无表结构变更、无数据迁移**，不需要 DROP / 重建。

---

## 欠账清单（本段结束时哪些东西还没到位、交给谁）

**实施完必须逐条核对，并原样写进收尾报告。**

| # | 欠的是什么 | 交给 | 具体动作 |
|---|---|---|---|
| 1 | **`.trellis/spec/frontend/` 里 7 处旧名**（`TaskConflictResponse` / `TaskArtifact` / `useVideoTask.ts` / `api.md:62` 的「翻译失败：」前缀约定） | **C60 前端任务** | 那几处会在前端接接口时整段重写；本段按 design D6 不改 |
| 2 | **`frontend/src` 按旧契约命名** | **C60** | 父 prd 明示 |
| 3 | **`SubtitlePipeline` 一个类扛三种 run** | **未排期** | S5/S6 欠账 #7；拆的时候「重新提交会不会作废译文」要重审 |
| 4 | **非网络的挂住仍没人兜** | **留档** | C46 残余风险（`destroyForcibly` 后仍不退出的进程） |
| 5 | **DashScope SDK 的 HTTP 层超时未动** | **不做** | 它自带默认（connect 120s / read 300s）；要改得走 `Constants.connectionConfigurations` 全局静态，副作用面大于收益 |
| 6 | **全局 `read-timeout` 一刀切** | **未排期** | 四条调用共用一个数；将来某条确实需要不同量级时再拆 |
| 7 | **超时后远端 DashScope 任务不会被取消** | **不做** | 它会自己跑完；调取消接口收益为零、多一条失败路径 |
| 8 | **失败信息里带工作区绝对路径**（既有缺陷） | **另开小修 / 择机** | S5 欠账 #9；本段新增日志同样不拼路径 |
| 9 | **`spring.ai.retry.*` 是全局的**，收窄同时作用于 OpenAI 路线 | **有意** | 两条路线的重试语义没有理由不同（design §6） |

---

## 验收清单（对应 prd 的 AC）

| AC | 在哪验 |
|---|---|
| AC1 全局超时生效 | 步骤 1.2（实测 A）+ 1.4 |
| AC2 重试次数收窄 | 步骤 1.3（实测 B，结论回写 design §1.2） |
| AC3 ASR 不再无界 | 步骤 2.3（单测）+ 2.4（grep 归零） |
| AC4 ASR 正常路径不回归 | 步骤 2.3（三个分支）+ 2.4（真库转写） |
| AC5 状态机口径进 spec | 步骤 3.4 |
| AC6 索引失实订正 | 步骤 3.4 |
| AC7 spec 旧名清零 | 步骤 4 验证 |
| AC8 旧件残留复查 | 步骤 5.3（AC2 部分） |
| AC9 父文档同步 | 步骤 5.1 |
| AC10 全量回归 | 步骤 5.2 |
| AC11 父 AC1 / AC2 证据收齐 | 步骤 5.3 |

---

# 收尾报告（2026-10-20）

## 1. 基线账

**708 → 711**（+3：`stopsPollingWhenTaskNeverFinishes` / `returnsAsSoonAsTaskSucceeds` /
`keepsPollingThroughTransientServerError`）。全量 **711 / 0 failures / 0 errors / 1 skipped**（存量 `@Disabled`）。
`mvn clean test-compile` 过。

## 2. 两处实测的结论（都回写进 [design.md](./design.md)）

| 实测 | 做法 | 结论 |
|---|---|---|
| **A · 读超时真的到达 LLM 客户端** | `application-local.yml` 临时压 `spring.http.clients.read-timeout: 1ms`，触发一次翻译 | 立刻失败：`ResourceAccessException: I/O error on POST request for "https://api.deepseek.com/chat/completions"`。**design §0.1 的判断成立** |
| **B · 重试语义** | 从实测 A 那份日志读退避间隔（读超时是可重试异常，天然走到重试路径） | **`max-attempts: 3` ⇒ 3 次重试 ⇒ 每次调用最多 4 次尝试**，单次最坏 `4×120s + (2+10+50)s ≈ 542s ≈ 9 分钟` |

**踩到的坑（已写进 design §1.2）**：那条 `Retry error. Retry count:N` 的计数器挂在单例
`RetryTemplate` 的 listener 上、**跨调用累加不归零**；判"一次调用重试几次"要看**退避间隔什么时候重置**。

## 3. 真库验收

| AC | 结果 | 证据 |
|---|---|---|
| AC3 ASR 不再无界 | PASS | `grep -rnE "transcription\.wait\(" backend/src/main` **只命中解释为什么不用它的注释**；三个单测覆盖终态/超时/503 |
| AC4 真转写不回归 | PASS | run 125（MAIN）：`DashScope 转写任务已提交 taskId=d727880d-…` → `已结束 status=SUCCEEDED cost=10399ms` → `segments=12`；`ORIGINAL rev19 READY`（`derived_from_run_id=111` = run 125 的行 id） |
| AC1 全局超时 | PASS | 同一趟流程里 **翻译（run 126，真 LLM 两批）** 与 **重新生成（run 127）** 都正常走完，**全程零重试、零超时误杀** |
| AC10 完整流程 | PASS | 提交 → 产物（`/artifacts` 返回 ORIGINAL rev19 / CLEANED rev13）→ 翻译（`TRANSLATED(en) rev4 READY`）→ 重新生成（`CLEANED rev14 READY`，旧 rev13 `SUPERSEDED`），三个 run 全 `SUCCEEDED` |
| AC8 旧件残留复查 | PASS | 24 个旧名/旧字段/旧表名 grep **全零**；`FROM task` / `uq_` / `temp_file` 零 |
| AC11 父 AC1 证据 | PASS | 六张表齐备；索引名 10 条全 `uk_`/`idx_` 前缀且带表名段（合 N4）；9 个枚举列全 `varchar(32)`；`FOREIGN KEY` 计数 0；`schema.sql` 头部写明两条手工迁移 |
| AC7 spec 旧名 | PASS（带口径） | 见下 §4 |

**旁证**：run 124（实测 A 留下的 `RUNNING`）被下一次启动的启动收口收成 `INTERRUPTED`（`finished_at` 20:08:11）——
C15 的恢复路径照旧工作。

## 4. AC7 的口径订正（审计清单不全）

审计报的 `comment-guidelines.md` 6 处不全，**AC7 判据又抓出 5 处** `SubtitleRoute` / `SourceUri`。处理：

- **能换成现存名字的就换**：`:168` 的 `SourceUri` → `SourceKey`（讲"类注释只写什么、公式下沉到哪"，`SourceKey` 是当真的同类实例）；`WorkspaceKey` → `SourceKey`（后者带着同一句「刻意不做正则强校验」）；`VideoTaskRepository` → `SubtitleTaskRepository`。
- **换了会变假话的就标注**：`:85` 的禁令标题**保留三个类名并加一句「这三个类已退场」的声明**，
  `:110` / `:134` / `:149` 三处 `SubtitleRoute` 由那句声明兜住 —— 它们是"当年在这类注释上抓到了什么毛病"的记录。
- **找不到同形实例的改写为不点名**：`:173` 的块行数举例。

**残留两处 SDK 假朋友**（按计划显式排除）：`error-handling.md:119` 的 `finished.getTaskStatus()`、
`provider-pattern.md:159`（该行自己就写明 `TaskStatus` 是阿里 SDK 类型）。

## 5. 顺带查出、本段不改的两件事（交项目所有者裁定）

1. **领域实体的迁移方法在生产代码里没有调用点**：`SubtitleArtifact.supersede()` / `confirmDeletion()`、
   `IntermediateFile.supersede()` / `confirmDeletion()` / `revive()` 只有单测在用；生产写这些状态走的是
   仓储/映射器的批量 UPDATE。已作为**事实**写进 `state-machine-guidelines.md` §4，未动代码。
2. **部分 UPDATE 不带 `status` 守卫**：`markSupersededAndClearFiles(artifactId)` 与两个 `markDeleted(id)`
   的 `WHERE` 只有 `id = ?`，安全前提是**上游那条选取该行的查询**限定了状态。同样只记事实，未动代码。

## 6. 欠账核对

见上面「欠账清单」九条，逐条状态：**#1 / #2**（frontend spec 与 `frontend/src` 的旧名）仍欠，交 C60；
**#3**（`SubtitlePipeline` 三条线）仍欠，未排期；**#4–#7 / #9** 按计划不做或有意为之；**#8** 仍欠，另开小修。
本段**新增一条**：`DashScopeProperties` 加字段会打断测试里 10 处 `new DashScopeProperties(...)` ——
本段用测试类内的 `props(...)` 工厂收口，**没在 record 上加便利构造器**（那会让默认值出现第二个来源）。

## 7. 对本地 dev 库的影响（验收留下的）

task 1（`BV11QMd6WEJp|1`）：`ORIGINAL rev18` 被手工打成 `SUPERSEDED`（逼出真转写），新增
`ORIGINAL rev19` / `CLEANED rev14` / `TRANSLATED(en) rev4` 三条 `READY`；`run 124` 由启动收口收成 `INTERRUPTED`。
**都属正常验收痕迹，不是脏数据。**

---

# 审查轮追加：删掉零生产调用点的领域迁移方法（2026-10-20）

> 审查意见：「没有用的调用点可以删了」。范围与理由见 [design.md](./design.md) §5 **D8**、prd 的 AC12。

## 步骤 6：删迁移方法 + 夹具改走 `restore`

**`src/main`（3 个文件）**

| 文件 | 动作 |
|---|---|
| `domain/intermediate/IntermediateFile.java` | 删 `revive()` / `supersede()` / `confirmDeletion()` 与只被它们用的私有 `validateStatus(...)`；`status` / `updatedAt` 已无变更点 → 收成 `final`；类注释改准 |
| `domain/subtitle/SubtitleArtifact.java` | 删 `supersede()` / `confirmDeletion()`；**保留** `validateStatus`（`completeCreation` 还在用）；类注释改准 |
| `.trellis/spec/backend/state-machine-guidelines.md` §4 | 从"它们现在的价值是把合法迁移写成可执行的文档"改写为"实体只承载出生与换版落定，其余守卫全在 SQL 侧"，并写明**测试要那些状态的行用 `restore(...)` 造** |

**`src/test`（3 个文件）**

| 文件 | 动作 |
|---|---|
| `domain/intermediate/IntermediateFileTest.java` | 删 7 个迁移用例，只留 `ofReadyStartsReady` / `restoreRebuildsAnyStatus`；类注释改准 |
| `domain/subtitle/SubtitleArtifactTest.java` | 删 4 个迁移用例；`throwsWhenCompletingCreationFromNonCreatingStates` 的 SUPERSEDED 夹具改走新增的 `superseded()` 助手（内部 `restore`）；类注释改准 |
| `application/maintenance/ReconcileServiceTest.java` | 5 处「调迁移方法造夹具」改成新增的 `artifactIn(artifact, status)` / `fileIn(file, status)` 助手（内部 `restore`） |

**判据**：`grep -rn 'supersede()\|confirmDeletion()\|revive()' backend/src` **归零**。

## 基线账（第二轮）

**711 → 700**（删 11 个迁移用例，不增）。全量 **700 / 0 failures / 0 errors / 1 skipped**；
`mvn clean test-compile` 过。

## 仍欠（留给项目所有者裁定）

**那两条不带状态守卫的 UPDATE 要不要补 `AND status = ...`**：
`SubtitleArtifactMapper.markSupersededAndClearFiles(artifactId)`、两个 `markDeleted(id)`。
删掉 Java 守卫之后，`READY → SUPERSEDED` 与 `SUPERSEDED → DELETED` 的合法前提只剩 SQL 与文档。
补上是**严格收紧**（调用点都在"先按状态查出这一行"之后，happy path 零变化），但属**行为改动**，
不在本次审查意见的范围内，**等指示**。

---

# 审查轮追加（二）：状态守卫补进 SQL（2026-10-20）

> 审查意见：「补一下」。理由与取舍见 [design.md](./design.md) §5 **D9**、prd 的 AC13。

## 步骤 7：三条 UPDATE 加 `AND status = ...`

| 文件 | 改动 |
|---|---|
| `infrastructure/persistence/mapper/SubtitleArtifactMapper.java` | `markSupersededAndClearFiles` → `WHERE id = ? AND status = 'READY'`；`markDeleted` → `WHERE id = ? AND status = 'SUPERSEDED'`；两处 javadoc 写明**为什么这条守卫不是冗余条件** |
| `infrastructure/persistence/mapper/IntermediateFileMapper.java` | `markDeleted` → `WHERE id = ? AND status = 'SUPERSEDED'`；同样补 javadoc |
| `.trellis/spec/backend/state-machine-guidelines.md` §4 | 改成一张「守卫在哪」的全表（每条改状态的写入恰好一处守卫，位置二选一）；补两条说明：`markReady` 不需要守卫、`updateReady` 的条件在领域侧 |

## 真库验证（单测测不到 SQL —— mapper 是 mock 的）

| 守卫 | 怎么触发 | 结果 |
|---|---|---|
| `markSupersededAndClearFiles`（产物） | 改 `workspace.duration_ms` 造内容变更 → 重提交触发指纹级联 | `Updates: 1` **×2**（ORIGINAL + CLEANED） |
| `markDeleted`（产物） | 级联后翻译一次（建出 `READY` 的译文）→ 重新生成字幕把它级联掉并回收 | `Updates: 1` **×1** |
| `markDeleted`（中间产物） | 同上那次指纹级联作废 3 条中间产物后逐个回收 | `Updates: 1` **×3** |

**run 128 MAIN / 129 TRANSLATE / 130 RECLEAN 全 `SUCCEEDED`**；`回收失败` WARN **0** 条；
`Retry error` / `ResourceAccessException` **0** 条。基线仍 **700 / 0 / 0 / 1**（只改 SQL 文本，不增删用例）。

> **一处口径更正**：spec §4 上一版写「守卫落在两处之一：UPDATE 自己的 `WHERE`，或上游查询」——
> 补完之后**不再是二选一**：每条改状态的写入都有自己的一处守卫，位置在领域实体或 SQL。
> `updateReady` 是唯一由领域侧守的（`completeCreation`），其余全在 SQL。
