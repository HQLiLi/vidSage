# 状态机规范

> 本项目有**三张状态列**，每张是一台小状态机：`task_run.status` / `subtitle_artifact.status` /
> `intermediate_file.status`。本文写它们的**值是什么意思、谁能写、什么时候写**。
> 列类型与索引规则不在这里 —— 见 [database-guidelines.md](./database-guidelines.md)
> （状态列一律 `VARCHAR(32)`、部分唯一用生成列 + 唯一索引）。

---

## 两条不变量

**1. 每个值恰好一条写入路径。**

问一个值「谁写它」，答案只能有一个。出现两条路径时，先问是不是把两件事挤进了一个值
（那就该拆成两个值），而不是把两条路径都记下来。

**2. 零路径的死值不许留。**

一个状态值如果没有任何代码会写它，它就不是"预留"，是**死值** ——
读代码的人会以为存在这条路径，去猜它什么时候发生。删掉它。

> **实例**：`intermediate_file` 为什么只有三个值、没有 `CREATING` 和 `FAILED`？
> 它走「先落盘、后登记」，压根不存在「登记了但文件还没写」这个窗口，所以 `CREATING` 是硬套；
> 而 `FAILED` **没有任何代码会写它** —— 对纯缓存来说「没写成」就等于「没写过」，重来一次即可。
> 两条不变量各否掉一个值。

---

## 1. `task_run.status`

```
（无） ──插行──▶ RUNNING ──▶ SUCCEEDED
                   │
                   └────▶ FAILED          （无条件收口）
                   │
        （进程重启，启动收口补记）
                   └────▶ INTERRUPTED
```

| 值 | 意味着什么 | 写入路径（全部） | 路径类型 |
|---|---|---|---|
| `RUNNING` | 这条 run 正在跑。**一个 task 同一时刻只允许有一条** —— 所以这行能插进去本身就说明抢到了执行权（插入即抢锁） | 三条路都是**先插一行 `RUNNING` 才开始干活**：提交 / 重新生成 / 翻译。同一 task 已有 `RUNNING` 行时插不进去 → 409 | 正常 |
| `SUCCEEDED` | 跑完了，产物齐了 | run 体正常走到底，由 `TaskRunSupervisor` 调 `TaskRun.succeed()` | 正常 |
| `FAILED` | 跑了，**知道它没成** | **无条件收口**：执行体退出时还没落终态，一律置 `FAILED`（`TaskRunSupervisor` 调 `TaskRun.fail(原因)`） | 正常 |
| `INTERRUPTED` | **不知道**跑成没跑成（进程没了） | **启动收口**：进程启动时发现残留 `RUNNING`，一条 UPDATE 直接改（`TaskRunMapper.interruptRunning`，**不经领域对象**） | 恢复 |

**为什么 `INTERRUPTED` 不是崩溃时写的**：崩溃时什么代码都跑不了，是下次启动由新进程补记的。
所以它的写入路径天然只有「启动收口」一条，且**只能**是恢复路径。

**互斥靠什么**：`uk_task_run_task_id_active_flag`（`active_flag` 生成列只在 `status = 'RUNNING'` 时非 NULL）。
没有「先查一遍再插」这个分支 —— 那是两套判据，并发下照样漏。

---

## 2. `subtitle_artifact.status`

```
(无) ──插行──▶ CREATING ──换版落定──▶ READY ──┬──▶ SUPERSEDED ──▶ DELETED
                  │                            │  （不再是当前版）  （文件确认回收）
                  └──收口置位──▶ FAILED        │
                                               └── 覆盖型：SUPERSEDED 与文件列清空同一刻
```

| 值 | 意味着什么 | 写入路径（全部） | 路径类型 |
|---|---|---|---|
| `CREATING` | 占位：行在，文件还没写完。**该行永远全 NULL** | 开始产生产物时插行（`SubtitleArtifactStore.createCreatingPlaceholder`） | 正常 |
| `READY` | **当前有效版**（业务查询只认它） | 换版落定：写文件列 + 哈希列 + 置 `READY` 是**同一条 UPDATE**（`SubtitleArtifact.completeCreation` → `SubtitleArtifactStore.settleArtifact`） | 正常 |
| `SUPERSEDED` | **不再是当前版**（三种原因，同一个意思） | ① **换版降级**：同 `(task, type, lang)` 出现更高 `revision`，`markSupersededAndClearFiles`（**覆盖型**：顺带清空八个文件/哈希列）<br>② **下游级联**：上游 `CLEANED` 换版 → 下游全部作废，`markSupersededByTaskIdAndType`（**孤儿型**：文件列留着，回收时要用它定位文件）<br>③ **指纹级联**：内容变了 → 旧产物全作废（`WorkspaceFingerprintStore.supersedeOnSourceMediaChange`） | 正常 · 正常 · 正常 |
| `FAILED` | **这一版产物没成**（两种原因，同一个意思） | ① **收口置位**：本次 run 插的、仍停在 `CREATING` 的行（`markCreatingAsFailedByRunId`）<br>② **启动收口**：进程启动时把残留 `CREATING` 落下来（`markCreatingAsFailed`） | 正常 · **恢复** |
| `DELETED` | 文件已确认回收 | **两阶段第二步**：删完文件之后才标（`SubtitleArtifactStore.reclaimSuperseded` → `markDeleted`） | 正常 |

**`CREATING` 行永远全 NULL 是硬要求**：否则「`CREATING` 但文件列已填」这一行的状态会有两种含义
（"文件还没写完" 和 "文件写完了还没换版"），读它的人没法判断。

**`READY` ⟺ 基于当前版上游**：译文是否过时**不在读路径上比**，是级联在**写入时就标掉了** ——
新 `CLEANED` 落定那一刻，所有下游译文在同一事务里标 `SUPERSEDED`。
所以判据里**不带** `derived_from_artifact_id`：它只表达"派生自哪一版"，不表达"是不是当前版"。

**每 `(task_id, artifact_type, lang)` 至多一条 `READY`** —— 由 `uk_..._lang_active_flag` 兜住。

---

## 3. `intermediate_file.status`

```
（无）──INSERT READY──▶ READY ⇄ SUPERSEDED ──▶ DELETED
                        ▲       （已作废，文件待删）  （文件确认回收）
                        └─────────────────────────────┘
                            重新产出时 UPDATE 回 READY
```

| 值 | 意味着什么 | 写入路径（全部） | 路径类型 |
|---|---|---|---|
| `READY` | 文件在、是**有效缓存** | ① **首次产出**：INSERT（`IntermediateFile.ofReady(...)`）<br>② **重新产出**：已作废的行复活（`IntermediateFileStore.recordProduced` → `repository.markReady(...)`） | 正常 · 正常 |
| `SUPERSEDED` | **已作废，文件待删** | **指纹级联**：内容变了 → 全部作废（`WorkspaceFingerprintStore` → `IntermediateFileStore.supersedeAll` → `markSupersededByTaskId`） | 正常 |
| `DELETED` | **文件已确认回收** | **两阶段第二步**：删完文件之后才标（`IntermediateFileStore.reclaimSuperseded` → `markDeleted`） | 正常 |

**`READY` 是唯一允许"复活"的值** —— 重新产出时复用同一行（`uk_..._task_id_file_name` 唯一）。
`SUPERSEDED` / `DELETED` 都是「文件待删 / 已删」的待办标记，**重启后依然成立**，所以启动收口对这张表无动作。

**复用判据**：`status = 'READY'` **且**文件在盘上（`IntermediateFileStore.isReusable`）。
只看文件在不在是不够的 —— 指纹级联只动 DB、盘上文件没动。

---

## 4. 守卫落在哪（别以为领域方法挡住了所有路径）

**领域实体只在两处管事**：出生（`SubtitleArtifact.createCreatingArtifact` / `IntermediateFile.ofReady`）
与**换版落定**（`SubtitleArtifact.completeCreation`，内部 `validateStatus` 拒绝非 `CREATING` 的迁移）。
**其余迁移实体一概不承载** —— 2026-10-20 把 `supersede()` / `confirmDeletion()` / `revive()` 删掉了：
它们在生产代码里**一个调用点都没有**，只在单测里被调，等于把状态机建模了两遍、
而生效的只有 SQL 那一遍。留着会让人以为"作废必须过那道校验"，实际生产路径整个绕过它。

**每条改状态的写入都恰好有一处守卫**，位置二选一：领域实体，或 SQL。

| 写入 | 守卫在哪 |
|---|---|
| `SubtitleArtifact.completeCreation`（换版落定，`CREATING → READY`） | **领域实体**（`validateStatus`）—— 唯一还由实体守的一条，也是唯一走"读出来 → 改 → 写回"的一条 |
| `markSupersededAndClearFiles`（产物 · 覆盖型取代） | SQL：`AND status = 'READY'` |
| `markSupersededByTaskIdAndType`（产物 · 下游级联） | SQL：`AND status = 'READY'` |
| `markDeleted`（产物 · 标已回收） | SQL：`AND status = 'SUPERSEDED'` |
| `markCreatingAsFailedByRunId` / `markCreatingAsFailed`（产物 · 收口置位） | SQL：`AND status = 'CREATING'` |
| `interruptRunning`（run · 启动收口） | SQL：`WHERE status = 'RUNNING'` |
| `markSupersededByTaskId`（中间产物 · 级联） | SQL：`AND status = 'READY'` |
| `markDeleted`（中间产物 · 标已回收） | SQL：`AND status = 'SUPERSEDED'` |
| `markReady`（中间产物 · 重新产出） | **不需要** —— 见下 |

**`markReady` 为什么不需要守卫**：`READY` 从三个值都能到（首次产出走 INSERT、已作废的复活、
以及给一条已经 `READY` 的行刷新类型与产出 run），`WHERE status IN (...)` 把三个值全列上就是恒真。
它不是漏了守卫，是这条迁移**本来就没有非法来源**。

**`updateReady`（换版落定的 SQL）自己不带 `status` 条件** —— 它前面就是 `completeCreation`，
非法迁移在那一步已经被拒了。别为了"整齐"给它加一个 `AND status = 'CREATING'` 然后**删掉领域的守卫**：
那样这条迁移的守卫就从"实体上看得见"变成"SQL 里一行条件"，而它恰恰是唯一一条需要读完整对象才能判的迁移。

> ⚠️ **`markDeleted` 与 `markSupersededAndClearFiles` 的守卫是 2026-10-20 补的**，
> 在那之前它们只有 `WHERE id = ?`，安全全靠"调用方先按状态查出这一行"。
> 补上之后，**「把守卫挪回调用方」不再是可接受的改法** —— 状态机的前提写在 SQL 里，
> 谁改这两条 UPDATE 都得先想清楚去掉守卫意味着什么。
> （`markSupersededAndClearFiles` 清掉的文件列**不可逆**：下游译文是孤儿型，文件列是回收时定位文件的唯一线索。）

**要那些状态的行来写测试时，用 `restore(...)` 造**（它公开、且注释写明「接受任何状态，不做迁移校验」），
不要为了测试再把迁移方法加回来。

---

## 5. 加一个新状态值之前

按顺序问三个问题，答不上来就别加：

1. **谁写它？** —— 具体到哪一个方法/哪一条 UPDATE。答不出来 = 死值。
2. **什么时候写？** —— 正常路径还是恢复路径？恢复路径还有一条额外要求：**只写 DB 状态列，不写文件**，
   否则「恢复」会变成「隐蔽重试」。
3. **它跟现有值是不是同一个意思？** —— 如果新值只是"另一个原因导致的同一件事"，用同一个值，
   原因记在别的列（如 `run_type` / `error_message`），别让一个值背上两种含义。

加完记得同步：`VARCHAR(32)` 列注释、Java enum、以及**这张表**。
