# 移交文档：字幕任务数据模型重构

> **这份文档是讨论共识，不是最终设计。** 来源：2026-10-15 与项目所有者的逐轮设计 grill。
> `prd.md` / `design.md` / `implement.md` 都还没写，代码一行没动。
>
> **当前状态：planning（讨论已收完）。** 已定的记在第 3 节；**第 9 节已全部关闭**（2026-10-17 第七轮：D1/D2/D3 + §9.4 全表）—— 下一步是写 `prd.md` / `design.md` / `implement.md`。
>
> **进度（2026-10-15 第二轮）**：C35 作废，原替代 **C40–C42** —— **C40 现已被独立评审推翻，见 §3.7 的警示框与 `review-findings.md`**；C41 的立项理由已订正；C42 不变。
> ~~**§9.2 分期与切换、§9.3 对外 API 契约仍未谈**~~ —— **2026-10-17 第五轮已定，见 §3.9 / §3.10**。
> ~~§9.4 新增一条待决（`workspace_dir` 没有落点）~~ —— **2026-10-17 第七轮已定（§9.4j）**。
>
> **进度（2026-10-15/16 第三、四轮）**：
> - **C16 作废**，换成 run 级无条件收口（**C43–C46**）；
> - 指纹探测定为**一个阶段**、阶段内顺序写死（**C47–C49**）；
> - `subtitle_task.updated_at` 保留并定写入点（**C45**）；`PENDING` 删值（§6 末）；
> - **文件侧的规矩成条**：中间产物也走原子替换（**C50**）、文件与 DB 的写序纪律（**C51**）、本次不做版本回退（**C52**）；
> - **D-6 措辞与两张写入路径表重写**（§2 / §6）；`derived_from_artifact_id` 改名；§7.4 / §7.5 改成时序表。
>
> **进度（2026-10-17 第五轮）**：
> - **§9.2 分期与切换**、**§9.3 对外 API 契约** 两条**谈完定案**（**C53–C60**，正文见 **§3.9 / §3.10**）；
> - 新增判据 **D-7**（可以无视现有形状，直接建新类）；
> - §9.4e（前端是否同批改）随之定案。
>
> **进度（2026-10-17 第六轮 · B1 写集表逐行走查）**：
> - **C40 整块换成写集表**（正文 §3.7.3）；边界 = `{W1, W5, W5′}`，`TaskRun` / `SubtitleArtifact` / `Task` **都不是边界**；
> - **W1 是新查出的真边界**：`workspace` + `subtitle_task` 必须同生（显式事务，本次第一个 `@Transactional`）；
> - 新增 **C61**（收口点同时收 artifact）、**C62**（中间产物落库）；
> - 连带订正：**C1 / C5 / C26 / C50 / C51**；§7.1 / §7.2 补关键词步骤；§7.4 / §7.5 步骤 5/6 重写；§9.4a 关掉；§9.4b 四类降为两类；`run_id` 改名 `derived_from_run_id`（两张表一起）。
>
> **进度（2026-10-17 第七轮）**：
> - **D2 定案（C63）**：提交路径撞 `uk_source` 走**甲-2** —— 同一事务内**锁定读**（`FOR SHARE`）重查 `workspace` 与 `subtitle_task` 两行，不重试插入、不回滚重开事务；§7.1 步骤 3 补并发分支；
> - 连带记下两条落地约束（不许隔 `@Transactional` 边界、B 的事务不许放慢操作）与一条待实测（3 个并发请求复现 S→X 升级死锁）。
> - **§9.4h 命名整块定案（N1–N7）**：`SubtitleArtifact` 归新实体（旧 record → `SubtitleStageOutput`，`AudioArtifact` → `AudioStageOutput`）；`SubtitleTask` / `IntermediateFile` / `TaskRunStatus`；索引规则 `pk_`/`uk_`/`idx_` + 表名段；枚举列一律 `VARCHAR(32)`（不用 `ENUM`/`CHECK`）；检测出的源语种不提列。正文见 §9.4h。
> - **§9.4j 定案**：`workspace_dir` 走**乙2** —— 不落库、列删掉，目录名 = `sanitizeName(source_key)`（**新查出 `|` 坑**：key 必须也过 `sanitizeName`）；§4.4 四列 `_url` → `_file_name`、`VARCHAR(1024)` → `(255)`；读路径公式 `root / sanitizeName(source_key) / <用途>_file_name`。正文见 §9.4j。
> - **D1 定案（C64）**：文件系统残留窗口**三类全接受**；其中 ② 本次**加检测** —— `subtitle_artifact` 加四列 sha256、与换版落定同一条 UPDATE 写、C26 对账比对哈希报"需人工重跑"。正文见 §3.4 的 C64。
> - **§9.4d 定案（C65）**：手工对账本次实现为**只读对账**（四件事全报、② 只报不修），触发 = `POST /api/maintenance/reconcile`，**不加鉴权**（全站零鉴权另记缺口）。正文见 §3.4 的 C65。
> - **D3 定案（C46 订正）**：给**网络调用**（LLM / ASR / 术语查证）补超时，与外部进程同口径 —— C46 的缺口从"永久占锁"降成"超时 → FAILED → 重跑收敛"。**入口已点明**（只有网络调用没超时；外部进程早有）；**后果补上第二条**（闸门许可泄漏 → 队列永久堵死）。正文见 §3.3 的 C46。
> - **§9.4c 定案**：`workspace` **不落** `file_name` / `size_bytes` —— `file_name` 已有两处落点，`size_bytes` 是读时 stat。**至此 §9.4 全表关掉、D1/D2/D3 全部定案，规划阶段的讨论收完**；下一步写 `prd.md` / `design.md` / `implement.md`。
>
> **仍未定：无。** §9.4 全表关掉；D1 / D2 / D3 三条待讨论全部定案（C64 / C63 / C46 订正）。**下一步是写 `prd.md` / `design.md` / `implement.md`** —— 三份都还没写，代码一行没动。

---

## 1. 为什么要动

项目所有者原话：「目前的 DB 已经需要重构了，我感觉很乱」。讨论中把"乱"落成了两类，都有代码实证：

### 1.1 粒度错位 —— 一个物理目录，两种粒度

- 真实资源是**工作区**（一个目录 + `workspace_key`）：关键词表按它唯一、产物按它命名、互斥按它上锁
- DB 的行是**一次提交**（`task`）：状态、时间戳、失败原因挂在行上，`workspace_key` 是 1:N
- 实证：`schema.sql` 里 `workspace_key` 无唯一约束，`rejectIfActive` 只拦 PENDING/RUNNING，所以**同一个工作区可以有多行 task**

**更糟的一条实证**：`MediaInput.RemoteUrl.getContentKey` 是对 **URL 原文**哈希、**零规范化**：

```java
// domain/media/model/MediaInput.java，RemoteUrl 分支
return WorkspaceKey.ofHash(value == null ? "" : value);
```

而 `LocalPath` 分支是规范化过的（`SourceUri.ofLocalFile`）。后果在 `backend/data/works-before/` 里能直接看到：

```
https___www.bilibili.com_video_BV11QMd6WEJp_-5eacccf6e669
https___www.bilibili.com_video_BV11QMd6WEJp__spm_id_from=333.1387.homepage.video-f8646aeee60a   ← 同一个视频
```

`BV11QMd6WEJp` / `BV1Tk3Q69Erd` / `BV1Lx4y1s7Xi` 各有 2 个目录，差异全在 `spm_id_from` / `vd_source` / `trackid` 这类跟踪参数上 —— 重复下载 + 重复 ASR + 重复清洗，而用户以为在看同一个视频。

（`trackid` 里还带**毫秒时间戳**，所以从首页点两次就是两个工作区。）

### 1.2 生命周期压在一行 —— 三种生命周期共用一条 task 行

- 主管线 / 重新生成字幕 / 翻译，三种生命周期都寄生在同一个 `task` 行上
- `status` 只有主管线那台状态机（`PENDING→RUNNING→SUCCEEDED/FAILED`），后置会话**用不了它** → 在途状态只能进内存表 `TaskSessionRegistry`（不落库、不跨重启）
- 三种失败**共用 `error_message` 一列**，靠中文前缀字符串区分来源（`任务执行失败：` / `重新生成失败：` / `翻译失败：`），还配了按前缀"认领"的清除方法（[VideoTask.java](../../../backend/src/main/java/com/vidsage/domain/task/VideoTask.java) 的 `clearRegenerateFailure` / `clearTranslationFailure`）
- 一个 taskNo 下可以翻十次、重洗十次，**DB 里零记录**：没有历史，也没有"这个工作区翻过哪些语种"

### 1.3 顺带查出来的既有洞

| 洞 | 证据 |
|---|---|
| 主管线 `RUNNING` 落库，但**没有任何启动收口** —— 进程被 kill 后那行永远 RUNNING，重启后前端一直转圈 | 全仓 grep 无 `CommandLineRunner` / `ApplicationRunner` / `@PostConstruct`；`task.status` 只在 `start()` 里置 RUNNING |
| 提交不拦后置会话 —— 翻译正在读 `clean-segments` 时，新提交的主管线 clean 会把它重写掉 | `submitUrl` 只走 `rejectIfActive`（查 DB 的 PENDING/RUNNING），而后置会话期间主状态已是 SUCCEEDED。已记进 `.trellis/spec/backend/error-handling.md` 的「已知缺口」 |
| 译文/术语表**没有过时判据** —— 只在 regenerate 那条路删，普通主管线一个都不删 | `SubtitleCleanupCoordinator` 的 `deleteTranslatedArtifacts` 被 `if (regenerate)` 包着 |

### 1.4 关于父任务 prd 里"明确不做"的两条

`.trellis/tasks/10-11-subtitle-keywords-multilang/prd.md` 的 F 节写着当初不做：

- **F1** 提交路径复用旧任务 —— 理由"与本次功能无关"（**时机论，不反驳**）
- **F2** 建工作区表 —— 理由"工作区的实体在磁盘上，建表等于给磁盘事实做一份没有同步机制的 DB 影子"

**F2 的理由本次要推翻**：那个"影子"其实**早就存在**了 —— `subtitle_keyword` 表就是按 `workspace_key` 唯一的，`task` 行也各自带着 `workspace_key` / `workspace_dir`。所以问题不是"要不要建影子"，而是"影子现在以两列 + 一张邻表的散装形式存在，要不要收成实体"。

---

## 2. 讨论中确立的判据

这几条是后面所有决策的依据，引用时直接用编号。

- **D-1｜身份与版本分两层**：身份回答"是不是同一个东西"（稳定），版本回答"内容变了吗"（会变）。
- **D-2｜聚合 = 一致性边界**：聚合的判据不是"归类"，而是"哪些东西必须在一个事务里一起对"。
- **D-3｜DB 是权威，文件是容器**：有 DB 参与状态管理时，主流一律让元数据当真相；文件系统没有事务，没法原子地"改状态 + 改内容"。
- **D-4｜产物能重新生成 → 走缓存语义**：读不到就重新生成，不当错误；只有当产物是**唯一副本**时才报错（数据湖的立场）。
- **D-5｜读取路径不许改状态**：一次瞬时故障（目录没挂上）不该把权威表污染成"全部产物已删除"。
- **D-6｜每个状态值必须有可枚举的写入路径，且每一条都列在文档里。**
  同一个值的所有路径，"**这个值意味着什么**"必须一致 —— 不一致就得**拆值**（像 `FAILED` / `INTERRUPTED` 那样），不许靠约定俗成去猜。不允许死值（零路径）。表里每条路径标**路径类型**（正常 / 恢复）—— 只作可读性标注。
  **适用范围**：状态机的取值（不含 `error_message` 这类诊断文本列）。
  三条配套口径：
  - **【恢复路径】的定义**：① 只在**进程启动时、早于接受请求**时跑；② 只能把状态推向**终态/清理态**（`INTERRUPTED` / `FAILED`），不能推回活跃态（`RUNNING` / `READY` / `CREATING`）；③ **只写 DB 状态列**，不写文件、不调外部服务（否则"恢复"会变成"隐蔽重试"，与 C17 相抵）。
  - **拆不拆值的判据**：两条路径**语义是否相同**。`run.status` 的 `FAILED`（知道没成）/ `INTERRUPTED`（不知道成没成）语义不同 → 拆；`artifact.status` 的两个 `FAILED` 语义相同 → 合。
  - **表里的决策编号（`C43` 等）与章节号是文档内部引用**；写进代码注释时必须把理由展开，不许只留编号 —— 读代码的人手上没有这份文档。
- **D-7｜可以无视现有形状，直接建新类。**
  本次重构**不受既有类 / 字段 / 对外契约的形状约束** —— 新模型的类与对外契约按新模型重新定义，旧类该删就删、该改名就改名，**不为迁就既有形状买单**。
  前提：个人项目、无外部消费方、前端另开任务（**C60**）、存量数据可弃（§9.4i）。
  这条与 §9.4h（命名）合起来用：`SubtitleArtifact` 这类名字被旧类占了，不是"得换个名"的问题，而是"旧类在新模型里还该不该存在"的问题。

#### 术语（讨论里临时起的标签 —— 正文直接用，这里给定义）

> ⚠️ **「甲 / 乙 / 丙」在这份文档里有**两套**含义，别混**：
> - **§9 的三条话题**：甲 = 领域模型（§9.1）／**丙 = 分期与切换（§9.2）**／乙 = 对外 API 契约（§9.3）。**三条都已关闭**，只在标题里留个痕。
> - **「当前上游是哪一版由谁回答」的三个候选**（见下）。**正文里单独出现的「丙」一律指这一个** —— §3.7.3 的 W5 行、§7.3 末、§7.5、C62 都是。

**丙（当前上游三候选）** —— 甲 = 由 `status` 回答；乙 = 由 `lineage` 回答（读时比 `译文.derived_from_artifact_id == 当前 READY 的 CLEANED.id`）；**丙 = 甲 + 级联标记进事务**（把"下游全标 `SUPERSEDED`"折进 `CLEANED` 换版的那个事务里）。**owner 拍的是丙**（2026-10-15）。一句话记法：**丙 的定义就是「`READY` ⟺ 基于当前 `CLEANED`」**。

**甲-1 / 甲-2**（**C63** 自己的一套，与上面那套无关）—— 撞 `uk_source` 后两条重查路线：**甲-1** = 回滚 + 新事务重查；**甲-2** = 同一事务内锁定读重查。**定在甲-2**。

**乙1 / 乙2**（**§9.4j** 自己的一套）—— `workspace_dir` 两条不落库路线：**乙1** = `WorkspaceDir.of(source_uri, source_key)`（保留 label）；**乙2** = `sanitizeName(source_key)`（丢掉 label）。**定在乙2**。

**主流五招**（文件与 DB 一致性，**C64** 引用第 ④⑤ 招）—— ① 原子替换 ② 顺序纪律 ③ 孤儿回收 ④ 内容寻址 / 不可变命名 ⑤ 原子换目录 / 换指针。**本项目选 ①②③、放弃 ④⑤**，理由就是 **C24**。完整要点见 `review-findings.md` §9.2 —— **这是 handoff 唯一还需要翻留档的地方**。

---

## 3. 已定决策

### 3.1 结构与关系

| # | 决策 | 关键理由 |
|---|---|---|
| **C1** | `workspace`（媒体身份）与 `subtitle_task`（字幕任务）**拆开两张** | 走主流后"媒体身份"正式确立（源 ID），该有自己的落点。<br>~~删媒体级联删任务，删任务不删媒体~~ —— **后半句已订正**（2026-10-17 第六轮）：当下是 **1:1 + 同生**（W1），"删了 task 留下 workspace" 正是 W1 要堵的空壳。该理由只在 C42 那个"一个媒体多个任务"的**未来**场景下才成立，不是当下的性质 |
| **C2** | 一个媒体一个 `subtitle_task`（`uk_workspace`），`task_no` 对外**稳定** | 消掉"一个工作区 N 行 task"；SSE 桶、产物归属、关键词归属同时有了唯一落点 |
| **C3** | 每次执行**一条 `task_run`**（`MAIN` / `RECLEAN` / `TRANSLATE`） | 三种生命周期收成同构的一行；三种失败天然分开，**中文前缀那套可以整块删掉** |
| **C4** | 产物进 `subtitle_artifact`，**一行 = 一个逻辑版本** | 现状 8 类文件天然分成三代三组（见 4.4） |
| **C5** | 砍掉 `task_stage`、~~`temp_file`~~、`latest_clean_artifact_id`、`latest_translate_map` | `latest_*` 两个是"查一句就有"的镜像，留两套权威正是"乱"的根源。`task_stage` 是范围外的能力。<br>**`temp_file` 那半已作废**（2026-10-17 第六轮）：原理由说"它要买的两样东西（完整性 + 对账）已经各被买走了"，**只数了两样，漏了第三样 —— 作废**。"内容变了要废掉中间产物"既不属于完整性、也不属于对账，它是级联；而 C31 / C47 只管 `subtitle_artifact`。缺了它 → **指纹变了等于没变**（详见 **C62**）。所以这次要把中间产物**落库**（新表），名字**不沿用 `temp_file`** |
| **C6** | **逻辑外键**（应用层保证，不写 DB `FOREIGN KEY`） | 全库现状零外键、口径一致；`spring.sql.init.mode: always` 下手改表已经靠手工，外键会让改表更难受 |

### 3.2 媒体身份与去重

| # | 决策 | 关键理由 |
|---|---|---|
| **C7** | 站点头**只做 B 站**，规则做成策略，加站是加一条 | 测试用例全是 B 站；不为泛化买单 |
| **C8** | URL 路线身份 = **`bvid`（+ `p`）**；`av...` 换算成 bvid；`p` 缺省 1 | 主流：源系统 ID 做身份，不是 URL 原文 |
| **C9** | 规范化规则：剥 `trackid` / `spm_id_from` / `vd_source` 这类跟踪参数；`/video/` 段取 `BV...` | 见 1.1 的实证 |
| **C10** | 抽不到就**跟一次跳转**拿真实 URL（`b23.tv` 短链）；展开仍拿不到 → 回落规范化 URL + 记日志 | 短链只占少数；长链**零网络请求** |
| **C11** | 短链解析加**超时（如 2 秒）+ 缓存映射**（短链→长链稳定不变） | 主流给这个"被迫放在接入层"的网络请求的补偿 |
| **C12** | 上传路线身份 = **内容 sha256**（现状 `ofDigest` 不变） | 内容即身份，**天然没有"内容变更"这个问题** |
| **C63** | 提交路径撞 `uk_source`（两个请求**同时**提交同一个**新** URL）**不报 409**：捕获重复键 → **同一事务内**用**锁定读**重查两行 → 照常进入 §7.1 步骤 4。**B 的退出点在步骤 4**（撞 `uk_active_run` → 409），不在这一步 | 详见下 |

#### C63 详解（2026-10-17 第七轮）

两条候选，**定在甲-2**：

- **甲-1** = 撞键后**回滚 + 新事务重查**；
- **甲-2** = **同一事务内锁定读重查**（不回滚、不重开）。

**为什么不是 409**：并发提交同一个新 URL **不是错误，是并发** —— 同一个视频就是同一个工作区，两个请求应当收敛到同一条 `subtitle_task` 上。409 在新模型里只剩"正在跑"一种语义（C56），塞进"撞了并发插入"会把它弄脏。

**为什么必须锁定读，而且两行都要**：

| # | B 的动作 | 用哪份快照 |
|---|---|---|
| 1 | `SELECT workspace WHERE source_type+source_key` → **未命中** | **← RR 一致性快照在这一刻建立（早于 A 提交）** |
| 2 | `INSERT workspace` → 撞 A 未提交的行 → 阻塞等 A | — |
| 3 | A 提交 | — |
| 4 | B 拿到 duplicate-key → 在索引记录上留下 S 锁 | — |
| 5 | `SELECT workspace … FOR SHARE` | 当前读，绕过快照 → **看到 A 的行** ✅ |
| 6 | `SELECT subtitle_task WHERE workspace_id = ?` | 若用**普通 SELECT** → 还是第 1 步那份旧快照 → **看不到 A 的行** ❌ |

第 6 步读空之后，B 只能去 `INSERT subtitle_task` → 撞 `uk_workspace` → **再撞一次键**。所以**两行都读，且都必须是锁定读**。

**为什么是 `FOR SHARE` 不是 `FOR UPDATE`**：我们只要"绕过快照看到最新已提交行"，**不写 workspace 行**，共享锁足够。而 duplicate-key 失败会在索引记录上**留下一个 S 锁**（MySQL 文档明载，并点名了死锁场景）—— `FOR UPDATE` 要 X 锁，就变成 **S→X 锁升级**：两个并发撞键者互相等 → 死锁。S 与 S 相容，`FOR SHARE` 没有这条路。

**不重试插入 `subtitle_task`**：W1 保证它与 `workspace` 同生，而撞键即意味着对方**已提交**，所以那行**必在**；重试必撞 `uk_workspace`。**也不写"查不到"分支** —— 不可达。

**B 只有一条事务，横跨 W1 与 W2**（§3.7.3 写集表里没有这个形状，**写明是有意为之**）：W1 对 B 来说一个字没写（`INSERT workspace` 是那条分支的唯一语句，失败即整条没写成），W2 本来就是单语句自足。

**两条落地约束**：

1. 撞键的 INSERT 与捕获它的 catch，必须在**同一事务方法、同一类内直接调用**，中间**不隔任何 `@Transactional` 边界** —— 否则 Spring 在代理边界就把事务标成 rollback-only，甲-2 **静默退化成甲-1**（外层 catch 救不回来，commit 时抛 `UnexpectedRollbackException`）。现状 repository 是裸 `@Repository`、事务新加在 application 层，天然满足 —— **只要别给 repository 的 insert 加注解**。
2. **B 的事务里不许放慢操作**（网络 / 文件 IO）：B 在事务结束前一直持有 workspace 行的 S 锁，而 A 的指纹写入（§7.3 步骤 2 ④ `UPDATE workspace`）要 X 锁，会等它。B 剩下的动作全是 DB、微秒级 → **接受**。

**~~待实测确认~~（2026-10-20 订正：已实测通过）**：上表第 4 步"duplicate-key 失败留下 S 锁"来自 MySQL 文档，~~**本项目尚未实测**~~。复现要 **3 个并发请求**打同一个**全新** URL（A 建行 + B、C 同时撞键）—— 2 个复现不出来（只有一个 S 持有者，升级 X 无人争），只测 2 个会误判 `FOR UPDATE` 安全。

> **2026-10-20 订正**：父任务集成评审轮按上述方法实测（3 并发打全新 URL），**无死锁**。
> 两个输家确实各撞了一次键（workspace 与 subtitle_task 两层），随后都走 `FOR SHARE` 锁定读重查成功，
> 最后撞 `uk_active_run` 得 409。结论与设计一致，`FOR SHARE` 的选择被验证是对的。
> 实测记录见 `evidence/2026-10-20/ac4-concurrency.txt`；`design.md` §7.1 已按现状重写。

**已知成本（留档，不翻案）**：甲-1 天然没有这个快照坑 —— 新事务的第一读就是新快照，普通 SELECT 就够。代价是控制流不线性 + 多一次事务。本次选甲-2，并接受"多出来的锁定读要求"。

### 3.3 状态、互斥、收口

| # | 决策 | 关键理由 |
|---|---|---|
| **C13** | `task_run.status` **落库**（`RUNNING/SUCCEEDED/FAILED/INTERRUPTED`），并补一次**启动收口** | 主管线本来就在库里落 RUNNING 且无人收口（1.3）；run 统一后**收口只写一次**，顺带修既有洞 |
| **C14** | 互斥判据 = **DB 单源**：生成列 + 唯一索引（见 4.3），`tryStart` 内存表**整块退场** | 它现在同时是锁又是状态源，这才是乱的根 |
| **C15** | 启动收口**三条** UPDATE：残留 `RUNNING` → `INTERRUPTED`（连 `finished_at`）；残留 `CREATING` 产物 → `FAILED`；被收掉的 task 更新 `updated_at`。<br>（2026-10-17 第六轮补第三条 —— C45 说 `subtitle_task.updated_at` 的写入点是"同一个收口点"，而启动收口也是一次 run 收尾，原文漏了。详见 §7.6） | 与 C13 同一批 |
| ~~**C16**~~ | ~~run 上记 `owner_instance`（进程启动时生成的 UUID）；**抢锁时**撞到的占位行 owner 不是自己 → 先清掉再抢~~ | **已作废**（2026-10-15 第三轮），见 **C43**。作废理由：`owner_instance` 是**进程级** UUID，所以"本进程里线程死了"那一行的 owner **就是本进程** → 落进"是本进程 → 409"分支，**它自称要堵的缝，正是它唯一堵不住的那格**；另一半（owner ≠ 自己）在 C39 之下**不可达**（上个进程的残留已被 C15 在"早于接受请求"时清掉）。净零 |
| **C43** | run 的异步执行体外面套一层**无条件收口**：除非已落终态，一律置 `FAILED`（`error_message` 记最后一次 stage）。流程内的各 catch **只负责丰富 `error_message` / `current_stage`**，不决定终态 | 替代 C16。理由不是账面合规：`uk_active_run` 挂在 `status='RUNNING'` 上，**一条收不了口的 RUNNING 行会永久占住该 task 的互斥直到进程重启**（mutex 泄漏）。`catch (Exception)` 接不住 `Error`，收口接得住；**代码自己早退**（漏了置状态的分支）也一并兜住 |
| **C44** | 收口点是**所有 `run_type` 共用的一个点**，落在异步执行体那一层 —— 不是 §7.3 / §7.4 / §7.5 各写一遍 | 这样 `run.status` 才满足 D-6（每个值可枚举的写入路径，见 §6 的两张表 —— `FAILED` 恰好一条路径）。§7.3 / §7.4 / §7.5 里那三句"失败 → FAILED"因此退成**描述**，不再是三处实现 |
| **C45** | `subtitle_task.updated_at` **保留**（日志/诊断用，不是前端展示），写入点 = **同一个收口点**（每次 run 收尾 UPDATE 一次）；`INSERT subtitle_task` 时 `created_at` / `updated_at` 同值 | 原方案只有列没有写入时机（D-6 病）。定在共用的收口点上，才能覆盖 MAIN / RECLEAN / TRANSLATE 三种 run |
| **C46** | 线程**卡死**（挂住不动，执行体不退出 → 收口跑不到）。**入口只有一个：网络调用没有超时**（2026-10-17 第七轮点明，grep 实测）。后果**两条**：① 那条 RUNNING 行永久占锁，只有重启能解；② **闸门许可泄漏** —— `SemaphoreTaskConcurrencyGate` 的 `finally` 保证释放，但"挂住"让 `finally` **根本跑不到**，累积到 `max-concurrent` 后**整个队列永久堵死**（第七轮新查出）。<br>**本次走 甲：给网络调用补超时**（LLM / ASR / 术语查证），与外部进程**同口径** —— 缺口从"永久占锁"降成"**超时 → FAILED → 重跑收敛**"。**闸门本身不改**（根因修掉后许可不会漏）。**残余风险**：非网络的挂住（如 `destroyForcibly` 后仍不退出的进程）仍没人兜。 | 与 C39 同取向（不做续跑 / 不做定时对账）。C16 时代撞到它同样解不了（owner 就是本进程），**不是新引入的缺口**。<br>**为什么外部进程不用补**：`ExternalProcessRunner` 早就**强制要求正数超时**（null / 0 / 负直接抛），默认 300s、可配 `vidsage.tools.default-timeout-seconds`；消费者 yt-dlp / ffmpeg / ffprobe 全覆盖。网络调用是**同一类外部依赖**（会挂、会无限等），不该只有一半有。<br>**不违反 C39**：超时是**调用级配置**，不是调度机制（C39 排除的是定时任务 / 续跑）。<br>**~~实现时要查：DashScope SDK 的 `transcription.wait(...)` 有没有带超时的重载~~** —— **2026-10-20 S8 已查清**：**没有带超时的重载**，而且问题比"无限等"更硬 —— `AsynchronousApi.wait` 内部是个**没有出口的循环**（只在任务结束时返回，否则 1s→5s 退避后无限重试，503/504 也无限重试），并且它**捕获 `InterruptedException` 后吞掉继续循环**，所以从外面打断也没用。另外「入口只有一个」这句**部分失准**：Mimo / OpenAI 路线本来就有 60s（`spring.ai.openai.chat.timeout` 默认值），短链跳转本来就有 2s，外部进程本来就有 300s —— 真正缺的只有走 Boot `RestClient.Builder` 的那几条。落地形态见 [design.md](./design.md) §7.6 与 S8 的 design。 |
| **C17** | `INTERRUPTED` 之后**不支持续跑**，用户重新触发即可 | 主流（转码/CI/数据管道全是重新提交）；断点复用让重触发天然≈续跑 |
| **C18** | `SUCCEEDED` 的 task 再次提交 → **新插一条 MAIN run**，主状态随最新 main run 走（可回退） | 现状重提交就是这个行为；顺带覆盖"FAILED 后重提交" |
| **C19** | `artifact.status` 走**先占位、后落定**：插 `CREATING` → 写文件 → `READY` / `FAILED` | 崩溃时能留下痕迹，而不是"DB 里什么都没有" |
| **C20** | `artifact.status` 五个值全留（`CREATING/READY/SUPERSEDED/FAILED/DELETED`） | 用"文件列是否为空"隐含表达状态太脆（四列，"全空才算回收"很容易漏同步）。**每个值都要有唯一写入时机**（D-6） |
| **C61** | **收口点同时收 run 与 artifact**（扩展 C43 / C44 的范围）：除了把没收口的 run 置 `FAILED`，**顺带把本次 run 插的、仍停在 `CREATING` 的 artifact 行也置 `FAILED`** —— `UPDATE subtitle_artifact SET status='FAILED' WHERE derived_from_run_id = ? AND status='CREATING'`。§7.3 / §7.4 / §7.5 里那些"写文件失败 → FAILED"因此退成**描述** | 2026-10-17 第六轮查出：C43 / C44 只写了 run，**范围漏了半边**。后果是 `artifact.status` 的 `FAILED` 少一条路径（"run 异常 / 早退"），进程不重启就永久 `CREATING` —— **违反 D-6**。而且 §6 表里那条"写文件失败，改行"**没写明落在哪**：若落在各流程的 local catch，就正好违反 C44 自己的精神（"一个点，不是各写一遍"）。收口点本来就拿着本次 run 的 id，一句 SQL 的事 |

### 3.4 产物权威与作废

| # | 决策 | 关键理由 |
|---|---|---|
| **C21** | `artifact.status` 是权威，`READY` 是**唯一有效态**；业务查询一律只认它 | D-3 |
| **C22** | 作废顺序：**先 DB 标记、后删文件**（同 Delta/Iceberg 的 `VACUUM` 模式） | 反过来的话，标记失败 → DB 说 READY 而文件没了。先标后删，最坏只留孤儿文件 |
| **C23** | 但**能覆盖的那类是一步到位**：同语种重新翻译 = 新文件**原子替换**同名文件 + 旧行标 `SUPERSEDED` | 只有"上游变更要删别人的文件"那类才真有两阶段（先标 → 删 → 标 `DELETED`） |
| **C24** | 文件名**不带 revision**，物理上每 `(type, lang)` 只留一份；旧版本作废时**清空它的文件列** | 历史记录保留、物理文件只留当前版本。用户下载走的产物在**他自己机器上**，本地回收无损 |
| **C25** | **自愈**：查到 `READY` 但文件不在（手工清了工作区）→ **当没有 + WARN + 重新生成，不改状态** | D-5。改状态的话，一次"目录没挂上"会把全部产物标成已删除 |
| **C26** | 手工触发的**对账**（扫目录 vs 两张文件表），**不做定时任务**。职责**四件事**：① 表 `READY` 但文件不在 → 报悬空（或按 C25 自愈）；② **表 `SUPERSEDED` / `DELETED` 但文件还在 → 报出来**（设计目标是"补删文件 + 标 `DELETED`"，C65 本次实现为**只读、只报不修**；2026-10-20 S7 段把报告口径从 `SUPERSEDED` 扩到 `DELETED`）；③ 盘上有文件、表里没有、且后缀不在 `{.tmp, .part, .ytdl}` → 报孤儿；④ **表 `READY` 但文件哈希对不上 → 报"需人工重跑"**（2026-10-17 第七轮补，见 **C64**）。<br>（2026-10-17 第六轮重写：中间产物落库（**C62**）之后，原那条"**中间产物文件名白名单**"**取消** —— 登记在案的不再会被误报成孤儿；但**临时文件后缀**的忽略规则要留着，且要提到全局） | 原理由随 C62 消失。②是 C22 两阶段的闭环：崩在删文件中途，行停在 `SUPERSEDED`，靠对账补删。③那三个后缀现状已有一份**局部**版（`YtDlpMediaAcquirer.NON_PRODUCT_SUFFIXES`，只有 `.info.json` / `.part` / `.ytdl`），要提到全局、**补上 `.tmp`**，并**拆掉 `.info.json`**（它现在入表了，留着会让对账漏报真孤儿） |
| **C50** | **中间产物（`source.<ext>`、`source.info.json`、`audio-*`）按"临时文件 → 原子替换"产出**；~~且不落库~~ —— **2026-10-17 第六轮改口径：要落库了**（**C62**）。原理由里那句"ASR 原始输出"是**没验过的话**，已删 —— 代码里**不存在**这个文件（ASR 结果直接写成 `original-segments.json`，中间没有落盘环节） | 崩在写文件中途会留下**半成品**，而"断点复用"的判据是"文件在不在" → 半成品会被当成成品复用，**这是唯一一类不能靠重跑收敛的错误**（重跑会跳过它）。原子替换之后"文件存在"就等于"文件完整"，零成本 —— **这条仍然成立**，C62 只是在它之上加了一层"表里 `READY`"的登记 |
| **C51** | **文件与 DB 的写序纪律**：写产物 → **先写文件（临时 + 原子替换）、后写 DB 登记**；删产物 → **先标 DB、后删文件**（C22）。<br>（2026-10-17 第六轮：原文写的是"后提交 DB **事务**"，改成"后写 DB 登记"，以覆盖**没有事务的中间产物**） | 写反了（先写 DB）崩溃后 DB 说新版、盘上是旧文件 → 读路径拿到旧内容而 DB 说是新版，**永久错**。按这个顺序，最坏的中间态是"盘上是新内容、DB 说旧版" —— 内容完整、重跑收敛。<br>**顺序纪律不能替代事务，它只是让"不需要事务"变得安全。** 对中间产物而言：崩在落盘前 → 表里没有 `READY` 行 → 不复用、重跑 ✓；崩在落盘后、写表前 → 表里还是没有 `READY` 行 → 不复用、重跑，盘上那个文件由 C26 当孤儿回收 ✓ |
| **C52** | **本次不做"回到上一版"**（版本回退）。位置已经留好：`revision` 列已在、**文件路径是 DB 的一列**（`segments_file_name` 等，不是拼接公式），所以将来加版号只改"产出时写什么路径 + 换版时别清空旧行、别删旧文件"，读路径与表结构都不用动 | C24 已明确放弃留旧版（"已交付的那一份在用户自己机器上"）。而普通失败（LLM 报错/超时）发生在**算的阶段**，那时文件根本没被碰 —— 所以"备份"唯一能多救的是**反悔**，那是产品功能（版本回退），不是恢复机制 |
| **C64** | **文件系统残留窗口：三类全接受**（诊断见 `review-findings.md` §9）。逐类写明「最坏后果 / 会不会被自动发现 / 靠什么收敛 / 放弃的是哪一招」，**别让后人当 bug 修**：<br>**① 文件已替换、DB 事务未提交** —— 读路径拿到**新内容**、DB 记旧版；**新内容比 DB 记的更对**（§7.5 步骤 6 已论证），重跑 / 下次命中复用自动纠正。**它不是静默的**（见 ②）。<br>**② 同代多文件不同源** —— 崩在逐个原子替换的中途，三个文件可能不同源（但**每个文件本身完整合法**，C50 保证）。**本次加检测**：`subtitle_artifact` 加**四列** `segments_sha256` / `subtitles_sha256` / `context_sha256` / `terms_sha256`（`CHAR(64)` 十六进制），**与「换版落定」同一条 UPDATE 写**；C26 对账**比对哈希**，不一致报"需人工重跑"。**不根治**（根治要放弃 C24），但把"零自动发现"变成"能报出来"。<br>**③ 孤儿文件** —— C22 明确接受（先标 DB、后删文件，最坏只留孤儿），C26 对账回收。 | ①② **是同一个事件的两端**（都是"文件已替换、DB 事务未提交"，区别只在替换了几个文件）—— 所以哈希比对**同时覆盖 ① 和 ②**，① 不再是"静默"的。<br>**放弃的是主流第 ④ 招（内容寻址 / 不可变命名）与第 ⑤ 招（原子换目录）** —— 理由就是 **C24**。**这个窗口是买 C24 付的钱，不是疏漏。**<br>**哈希列的三条纪律**（详见下）：① 列数必须**镜像四个文件列**；② **跟着文件列同生同灭**；③ 写入点 = 换版落定那条 UPDATE。<br>**耦合已解**：读取方 = **C65** 的只读对账（2026-10-17 第七轮定案）。 |
| **C65** | **手工对账（C26）本次实现为「只读对账」**：四件事全**报**，**② 只报不修**（不删文件、不标 `DELETED`）。触发 = **`POST /api/maintenance/reconcile`**（全量扫）。**不加鉴权**。 | 见下 |

#### C64 详解（2026-10-17 第七轮）

**为什么是四列不是三列**：各类型最多用到 3 个文件列（`ORIGINAL` 2 / `CLEANED` 3 / `TRANSLATED` 3），但 `context` 与 `terms` **不能挤一列** —— 所以哈希列必须**镜像全部四个文件列**。

**哈希列的三条纪律**：

1. **列数镜像四个文件列**（`segments_sha256` / `subtitles_sha256` / `context_sha256` / `terms_sha256`）；
2. **跟着文件列同生同灭** —— C24 清空旧行文件列时（§7.4 步骤 6 ①、§7.3 指纹级联），**必须同时清哈希列**，否则留下"有哈希、没文件"的行让对账误报。规则一句话：**凡是 SET 文件列的地方都同时 SET 哈希列**；
3. **写入点 = 换版落定那条 UPDATE** —— C51 保证"先落盘、后写 DB"，所以此刻算的哈希就是**成品文件**的哈希。

**要挡的质疑（写在这里，免得被拿 §9.4j 否掉）**：§9.4j 刚否掉 `workspace_dir` 列，理由是「DB 存镜像就有不一致风险」。判据是**这份镜像有没有独立价值**：

| | `workspace_dir`（否掉） | 文件哈希（加） |
|---|---|---|
| 能不能从别处算出来？ | **能** —— `sanitizeName(source_key)` 就是公式 | **不能** —— 它就是文件内容的指纹 |
| 有没有独立用途？ | **没有** —— 公式已经回答了 | **有，而且是唯一用途**：比对 |

> **留档：`manifest` 清单 = ② 的根治方向**（2026-10-17 第七轮记）
> 项目所有者提过"后面可能会改成 manifest 清单的方式"。它的性质是**主流第 ⑤ 招「原子换指针」的变体** —— 清单本身就是那个指针，一次原子替换即让整代文件同时生效。**本次不做**（C24 已放弃第 ④⑤ 招），但**方向已识别**，免得将来有人重新推一遍。

#### C65 详解（2026-10-17 第七轮）

**为什么是乙（只读）不是甲（读写）**

- **C64 的 ④ 必须有读取方** —— 哈希列一旦产出就**不能补**（事后补算出来的是当时盘上那份，可能已经是错源的），所以列本次就加；若对账不做（丙），那四列**只写不读**，C64 的 ④ 就是空话。乙把读取方补上。
- **只读把最大成本项拿掉** —— 不写 DB、不删文件，就不用定「删文件失败怎么办」「删了一半怎么报」「重复调用会不会重复删」。**甲不首选不是因为它增量大，是因为它的增量全是需要回答的问题**；甲可以等对账跑一段时间、确认报告质量后再升级。
- **② 只报不修可接受** —— `SUPERSEDED` 行**无害**（没有任何读路径认它），文件只是没人引用的垃圾；本来就落在 D1（C64）的"接受"范围内。乙只是把"自动回收"**降级成"报出来人工回收"**。
- **本次不做的代价不小** —— 对账是本重构的**天然一致性检查**：C59 要求每段"能用 HTTP 手动验证"，对账端点正好回答"表和盘对得上吗"。

**端点：`POST /api/maintenance/reconcile`**

- **为什么是端点不是启动参数**：C39 不做定时任务、全仓无 `CommandLineRunner`；与 **C15**「每次启动自动跑」**语义不同，不该共用钩子**；C59 要求 HTTP 手动验证，端点正好符合。
- **扫描范围 = 全量**（本地项目量小）；将来要限范围再加参数。
- **不加鉴权**：全仓**零鉴权**（`pom.xml` 无 security starter、`backend/src` 零 `SecurityFilterChain` / `@EnableWebSecurity` / `@PreAuthorize` —— 已 grep 实测）。只给**只读**端点加锁、却让 `/api/tasks` 那些**会真干活**的端点（提交会去下载 + 跑 ffmpeg/LLM）全开，是**倒挂**。真要加应该**整站一起加**，那是**另一个任务**。

**报告形状：两种行，不是一种**

| 类 | 行带什么 |
|---|---|
| ①②④ | **`task_no`** + `file_name` + 问题类别 |
| ③ 孤儿 | **目录名**（`sanitizeName(source_key)`）+ `file_name` + 问题类别 |

- **用 `task_no` 不用 `task_id`** —— 仓库明确规则：内部自增 id **不出境**（`TaskSnapshot`「刻意不含数据库自增 `id`」、`TaskArtifact`「id / workspace_key / workspace_dir 等内部字段不出境」）；对外稳定号是 `task_no`（C53）。
- **③ 必须带目录名**：孤儿**不在表里**（这就是它的定义），没有 `task_no` 可带。目录名是内部派生值，但不带它就**定位不到**孤儿 —— 这是**有意的例外**。
- 四类各给**条数 + 明细列表**，不能只回"成功"。

**幂等**：乙只读，**天然幂等** —— 重复调用不改任何东西。

**一条边界（运维必须知道，否则会以为对账坏了）**：**②③④ 都是"待人工处理清单"，同一行在人工处理之前每次都会重复出现**，处理完才消失。**只有 ① 例外** —— C25 的自愈会在读路径重新生成文件，所以 ① 下次自己就没了。

**C26 的职责措辞**：C26 那张表里 ② 写的是"**补删文件 + 标 `DELETED`**" —— 那是**设计目标**；**本次实现是只读的**，② 只报。写与不写的分界记在这里，别当成 C26 写错了。

**另记（不属于本重构）**：**全站零鉴权** —— 记成已知缺口；真要加是整站引 Spring Security，**另开任务**。

### 3.5 版本与内容变更

| # | 决策 | 关键理由 |
|---|---|---|
| **C27** | 内容指纹 = **`duration` + `timestamp`（发布时刻）**，从 yt-dlp 的 `source.info.json` 取 | 实测：yt-dlp **拿不到 `cid`**（顶层 45 个键里没有，`formats[]` 里也没有） |
| **C28** | 指纹**不含** `title` / `view_count` / `like_count` / `format_id` / `filesize_approx` | 前三个会随时变 → 每次都误判"变了"；`format_id` 受我们选的清晰度影响；`filesize_approx` 平台重压就会变 |
| **C29** | 指纹探测放在**异步管线里**（媒体获取阶段之前），不放在提交路径上 | 提交路径只做"廉价、可失败面小"的事；`metadata-first probe` 是主流顺序 |
| **C30** | 指纹取不到 → **跳过比对**，回落"有产物就复用"，记 WARN | 不能因为指纹机制把正常流程卡死 |
| **C31** | 指纹变了 → `workspace` **不动**，下游 artifact 全部标 `SUPERSEDED`，跑一条新 `MAIN` run | 身份是 bvid，内容变还是同一个媒体。版本在 artifact 层表达 |
| **C47** | 指纹探测做成**一个阶段**（媒体获取之前），阶段内顺序写死：**① 探测 → ② 比对 → ③ 级联作废 → ④ 写指纹**；**③ 与 ④ 在同一事务**里 | 阶段边界给了 `current_stage` 一个值（前端能显示"正在探测指纹"），也给了顺序一个落点。**③④ 同事务**是关键：崩了就是回滚，不靠"写指纹必须排在级联之后"这种要人守的顺序纪律。事务内先后自由 —— 那些行是 `READY → SUPERSEDED`，`active_flag` 都变 NULL，不参与唯一比较 |
| **C48** | 指纹写入点 = **级联落定之后**（同事务内），**不是探测时就落库**；内容没变则 ③④ 都不做；首跑（两列 NULL）只做 ④ | 若指纹先落库、崩在级联中间 → 下次提交看到"指纹一致" → 跳过级联 → 捡到还没标掉的旧 READY artifact 当当前版用 → **静默交付错内容、且没人会来修**。C48 与 C47 的"同事务"合起来把这个窗口彻底关掉 |
| **C49** | 指纹**取不到 → 跳过比对**，回落"有产物就复用"，记 WARN（**这是降级路径，不是失败路径**） | C30 原意不变，但 C43 之后更关键：收口会"没落终态就置 FAILED"，所以探测阶段绝不能把"取不到"当异常抛出去，否则一次元数据风控就会把整条 run 判死 |

**已知局限（要说清）**：`duration + timestamp` 覆盖不了"换了源、但时长和发布时间都没变"（同长度重新压制）。要精确就得走 B 站 API 拿 `cid` —— 本次不划算（要多一个风控依赖）。

### 3.6 关键词

| # | 决策 | 关键理由 |
|---|---|---|
| **C32** | `subtitle_keyword` 表保持"**当前生效一份**"（覆盖写），挂 `task_id` | 现状语义不变（A3：不做历史快照） |
| **C33** | 把现状埋在 `clean-segments.json` **文件内部**的 `keywordsFingerprint` **提到 `artifact` 表的一列** | 现状已有这个概念（`SubtitleTrack.keywordsFingerprint` + `CheckpointPolicy.isCurrent`），只是埋在 JSON 里；提到表上，判过时一句 SQL |
| **C34** | `task_run` 上**不加**关键词相关列 | artifact 的指纹已经够判过时；RECLEAN 请求带的关键词照现状落 `subtitle_keyword` 表 |

### 3.7 领域模型（2026-10-15 第二轮定案，**2026-10-17 第六轮换记法**）

> **C40 已被替换**（2026-10-17 第六轮，owner 已拍）。原来的"聚合根 = `TaskRun` + `SubtitleArtifact`"这组**类级聚合标签整体作废**，改用 **§3.7.3 的写集表**。理由见下。
> **记法之争已合拢**：候选 (a)「不用类级聚合标签、只用写集表」与 (b)「一个聚合根 `Workspace`」中，**(b) 被撤回**，定在 **(a)**。§9.1 那条"`Workspace` 算不算聚合根"随之消解 —— **不再问这个问题**。

**C35 已作废** —— 原文「聚合根 = `Task`（含 `Run` 实体）；`Artifact` / `Workspace` 独立」不成立。作废过程见 3.7.1。

#### 3.7.1 不变量台账（本轮的核心论证）

拿 D-2（聚合 = 一致性边界）去算账，新模型里真实存在的不变量一共五条：

| # | 不变量 | 跨几行 | 归属 |
|---|---|---|---|
| V1 | run 状态迁移合法 | 单行 | `TaskRun` 自己的方法（C36） |
| V2 | 一 task 最多一条 RUNNING run | 跨 run 行 | **DB 唯一索引 `uk_active_run`**（C14） |
| V3 | 一 `(task, type, lang)` 最多一条 READY artifact，且新 READY 与旧 SUPERSEDED **同一刻** | 跨 artifact 行 | **拆两半**（2026-10-17 订正，见 C41）：`V3a`（至多一条 READY）归 **DB 唯一索引**；`V3b`（同一刻）归 **事务**。原文把两半都记在索引名下，是**超额记账** |
| V4 | lineage 同 task | 跨行 | 一条 `task_id` 列（不必保护） |
| V5 | 状态不倒退 | — | 插新 revision 就不会发生（不必保护） |

两条关键判断：

1. **V2 与 V3 都是"唯一性"，一律由 DB 兜。** C14 已定"互斥判据 = DB 单源"，C41 把同一条口径延伸到 artifact —— 口径单源，不留"一处归 DB、一处归领域"的分裂。
2. **V2 与 V3 的"难"处不同，但都能被唯一索引兜住。** V2 是并发唯一性（两个请求抢，内存里的检查拦不住任何一次）；V3 是**顺序执行下也会写错**的逻辑不变量，更接近 DDD 的经典聚合场景。但 V3 同样能用生成列 + 唯一索引表达，且索引还能把"先降级旧的、再升新的"这个顺序**逼出来**（反了直接撞重复键，不会静默出现两条 READY）。

**结论：没有任何一条不变量需要聚合保护。** `Task` 没有内部实体、没有内部不变量，不具备聚合根资格。

（2026-10-17 第六轮补：**结论仍成立，但理由要换**成"两个不可恢复写集之外无边界"—— 见 §3.7.3。上面这套论证的问题是**双尺子**：拿"状态机该由自己的方法守"（封装判据）把 `TaskRun` / `SubtitleArtifact` 提成聚合根，又拿"约束在 DB"（边界判据）把 `Task` 降级。同一个论证里两把尺子，§9.1 那条"弱聚合根自相矛盾"就是它的必然产物。）

（C35 的原文说"只有 I1 需要聚合保护"，两处问题：I1 = V2 已经判给 DB；且漏了 V3。）

#### 3.7.2 定案

| # | 决策 | 关键理由 |
|---|---|---|
| ~~**C35**~~ | ~~聚合根 = `Task`（含 `Run`）；`Artifact` / `Workspace` 独立~~ | **已作废**，见 3.7.1 |
| **C36** | 状态机从 `VideoTask` **搬到 `TaskRun`** | 三种生命周期各自的状态归各自管（不变） |
| **C37** | **本次不把编排收进领域**（`application` 的 Coordinator 格局不动） | 不变；且本轮成了 C40 的支撑理由 |
| ~~**C40**~~ | ~~聚合根 = **`TaskRun`** + **`SubtitleArtifact`**（各自单行状态机）；`Task` 是**身份实体**；`SubtitleKeywords` 保持值对象~~ | **已作废**（2026-10-17 第六轮），换成 **§3.7.3 的写集表**。`TaskRun` / `SubtitleArtifact` / `Task` **都不是边界**，是带行为的领域实体 / 身份实体 |
| **C41** | `subtitle_artifact` 加**生成列 + 唯一索引** `uk_active_artifact` | **`V3a`（一 `(task,type,lang)` 至多一条 `READY`）归索引** —— 它是**状态不变量**、由索引持续兜，价值是不依赖"写入方枚举完整"这个前提；**`V3b`（新 `READY` 与旧 `SUPERSEDED` 同一刻）归事务** —— 唯一索引是约束不是原子单位，表达不了"同一刻"；索引顺带把顺序逼出来（先降旧的、再升新的，反了当场撞键）。C19 的 `CREATING` 占位不受影响 —— 它不是 READY，`active_flag` 为 NULL。<br>（2026-10-15 订正：原理由写作"V3 归 DB"，是**超额记账** —— 把 V3b 也记到了索引名下） |
| **C42** | `Task` **保留表和类**（不合并进 `workspace`） | taskNo 是对外契约（用户看到、SSE 订阅用），workspaceId 是内部主键，性质不同，混一行别扭；未来若加"一个媒体多个任务"（字幕 / 配音 / 审核），`Task` 已经在了 |

**C37 与 C40 的关系**：V3 的落点（"新 CLEANED 取代旧 CLEANED + 下游 TRANSLATED 作废"）是一个**编排**动作 —— 它跨 `artifact_type`（CLEANED 变更触发 TRANSLATED 作废）。把它塞进 `Task` 聚合等于把编排收进领域，与 C37 相抵。**这条理由本身仍然成立**，只是承载它的 C40 换了记法（见 §3.7.3）。

#### 3.7.3 写集表（2026-10-17 第六轮定案，替换 C40）

问的不再是"哪些东西该归一类"，而是 **"哪些写必须一起对"**。

| # | 写集 | 必须一起对的是什么 | 机制 | 原子性必需？ |
|---|---|---|---|---|
| **W1** | `workspace` + `subtitle_task` 同插 | 一个媒体必有一个任务、至多一个 | **显式事务**（2 行 2 表） | **必需**（R：不可恢复） |
| W2 | 插 `RUNNING` run | 一 task 至多一条 `RUNNING` | `uk_active_run`（一句 INSERT） | 必需（单语句即满足） |
| W3 | artifact 占位（`CREATING`） | 占位先落 | 单语句 | 必需（单语句即满足） |
| W4 | artifact 换版（同 `(type,lang)`） | 至多一条 `READY` + 旧降级与新升级同刻 | **按序两次 UPDATE**（索引逼出顺序：先降旧的、再升新的，反了当场撞键） | **非必需**（R：重跑可恢复） |
| **W5** | 换版 + 下游级联标记 | 新 `CLEANED` READY ⇒ 下游全部 `SUPERSEDED` | **显式事务**（N+2 行） | **必需**（丙 已定；R：不可恢复） |
| **W5′** | 指纹级联（artifact + 中间产物全标）+ `workspace` 指纹写入 | 内容变了 ⇒ 旧产物全作废 | **显式事务** | **必需**（C47 / C48） |
| W6 | 文件删除 + 标 `DELETED` | 不删任何 `READY` 行指着的文件 | C22 两阶段（文件操作在事务外） | 无原子单位，是**协议** |
| W7 | 启动收口 | 残留 `RUNNING` → `INTERRUPTED`；残留 `CREATING` → `FAILED` | 三条 UPDATE | 单语句 ×3 |
| W8 | 关键词覆盖写 | 一 task 一份 | `uk_task` | 单语句 |

**边界 = {W1, W5, W5′}。**

> 2026-10-17 订正：评审原句写的是 `{W1, W5}`，**漏了 W5′** —— 它同样标了"必需"，而且 C47 / C48 已经把"同一事务"定死。这跟评审自己批 §3.7.1 的"超额记账"是同一类毛病，只是方向相反（那边多记、这边少记）。

`TaskRun` / `SubtitleArtifact` / `Task` **都不是边界**，是带行为的领域实体 / 身份实体。

**判据：D-2 不是唯一尺子。** 判断一个写集要不要原子单位，至少还要看两件事：

- 违规之后**能不能恢复**（新判据 **R**）；
- **机制够不够便宜**（唯一索引能把"查 + 写"压成一条语句）。

> **R｜写集的原子性是否必需，取决于违规状态有没有恢复机制。**

本案的恢复机制**只有四个**：C15 启动收口、C25 读路径自愈、C17 人工重新触发、C26 手工对账（可选、未定）。它是真分水岭 —— **W1 不可恢复 → 必需原子；W4 可恢复（重跑一次 RECLEAN / TRANSLATE）→ 非必需；W5 / W5′ 不可恢复 → 必需**。

**为什么"两个聚合根"和"一个聚合根"都不成立**（记法之争的结论）：

- **两个**不成立：`Task` 就是 `subtitle_task`，而一个实体不能既在 A 聚合内、又是 B 聚合的根。
- **一个（`Workspace` 含全部）**不成立：那是 **god aggregate**（形状同被作废的 C35，只是换个根）；**根上没有乐观锁版本列**，而并发控制实际由 `uk_active_run`（**子行**唯一键）承担 → **标签与实际机制不配对**；再加上 §8.1 已写定"每表一个 port"、§7 全篇查询都是 `WHERE task_id=?`（按身份引用）—— 那正是"它们不在同一个聚合里"的写法。

**留档：将来若有人想引入聚合标签，先读这三条** —— ① god aggregate（与"聚合要小"相抵）；② 根上无 version 列，而并发控制实际在子行唯一键，标签与机制不配对；③ §8.1 那几个 port 要重写。

### 3.8 范围

| # | 决策 |
|---|---|
| **C38** | 删除功能（删媒体级联全删）**移出范围**，记成待做 → 所以也不需要 `lifecycle_status` |
| **C39** | 本次**不做**续跑、不做定时对账、不做多实例改造 |

### 3.9 对外契约（2026-10-17 第五轮定案）

> **前提**：本次**不保旧 API**，前端**不参与分期**（C60）—— 契约按新模型自由设计，不背兼容包袱。
> 四个形状在本节末。
> **本节范围**：`/api/tasks/**` 与 `/api/languages` 的契约。**另有一条不在本节形状里的端点**：`POST /api/maintenance/reconcile`（只读对账，**C65**）—— 它是运维端点，不属于 task API。

| # | 决策 | 关键理由 |
|---|---|---|
| **C53** | 端点路径不变（`/api/tasks/{taskNo}/...`）；`GET /api/tasks/{taskNo}` = **身份 + `lastRun`（单值）**；扁平的 `status` / `currentStage` / `errorMessage` / `startedAt` / `finishedAt` **五个字段全删** | `subtitle_task` 没有状态列，任何扁平状态都是派生量。**压平是渲染层的事** —— 让后端压平等于把"判据单源"重新拆散（C14 要消灭的那种分裂）。`lastRun` 是单值、且是页面打开后第一个要知道的东西，跟身份一起给省一次往返 |
| **C54** | 新增 `GET /api/tasks/{taskNo}/artifacts` = **当前有效产物**（只有 `READY`），按**版本行**给（`type` / `lang` / `revision` / `files`），每个 file 自带 url；**不给历史** | 分界线是**单值 vs 集合**：artifacts 是集合、且按需（显示字幕 / 列下载项时才要）。历史行是**空壳** —— C24 作废时清空旧行文件列，给了也没文件可下 |
| **C55** | 下载端点从 `/{fileName}` 改成按 `type` + `file` + `?lang=` 定位 | 现状按文件名定位是"文件系统即事实"的直接产物；新模型里文件由 `(type, lang)` 唯一确定 |
| **C56** | 提交 / 翻译 / 重生成三个 POST 统一返回 **202 或 409 + `{ taskNo, runNo }`**；`TaskConflictResponse.existingTask` 删掉 | POST 成功 = 刚插一条 RUNNING run（插入即抢锁），所以 202 里的 `lastRun` 四个字段**全是常量、零信息**，而且可能已被异步体推进 → **不可依赖**（不可依赖的字段比没有更糟）。409 只剩"正在跑"一种语义，前端只要 `taskNo` 就能跳转 + 订阅 |
| **C57** | SSE = **一条 run 一条流**，run 终态即关；事件带 `runNo` / `runType` / `targetLanguage` / `status` / `stage` / `terminal`；`terminal` 语义从"流结束"**降为"这条 run 结束了"** | 现状 `terminal` 靠**隐式编码**（`主状态终态 && stage.state != RUNNING`）把后置会话也算进流里 —— 其实已经是"一个 task 一条长流"，只是藏在 stage 的状态位里。新模型 `uk_active_run` 保证一个 task 同时只有一条 RUNNING run → 判据变直白。丢事件的窗口由"连接建立即推 DB 快照"兜底（现状已有） |
| **C58** | **不做 run 历史端点** | §1.2 的痛点（"DB 里零记录"）已由落库本身解决；纯读端点、不动表，将来加的成本≈0。§1.2 另一条"翻过哪些语种"由 C54 的 artifacts 清单回答 |

**`GET /api/tasks/{taskNo}`**

```jsonc
{
  "taskNo": "3f2a9c1e...",
  "inputType": "URL",
  "sourceUri": "https://www.bilibili.com/video/BV11QMd6WEJp",
  "createdAt": "2026-10-17T10:00:00",
  "lastRun": {                        // 最近一条执行，含正在跑的那条；从未执行 → null
    "runNo": 3,
    "runType": "TRANSLATE",           // MAIN / RECLEAN / TRANSLATE
    "targetLanguage": "zh-Hans",      // 只有 TRANSLATE 有值
    "status": "RUNNING",              // RUNNING / SUCCEEDED / FAILED / INTERRUPTED
    "currentStage": "translate-llm",
    "errorMessage": null,
    "startedAt": "2026-10-17T10:05:00",
    "finishedAt": null
  }
}
```

**`GET /api/tasks/{taskNo}/artifacts`**

```jsonc
[
  { "type": "ORIGINAL", "lang": "", "revision": 1,
    "files": { "segments":  { "url": "/api/tasks/3f2a.../artifacts/ORIGINAL/segments.json",  "sizeBytes": 10240 },
               "subtitles": { "url": "/api/tasks/3f2a.../artifacts/ORIGINAL/subtitles.srt",  "sizeBytes": 4096 } } },
  { "type": "CLEANED", "lang": "", "revision": 2, "keywordsFingerprint": "ab12...",
    "files": { "segments": {...}, "subtitles": {...}, "context": {...} } },
  { "type": "TRANSLATED", "lang": "zh-Hans", "revision": 1,
    "files": { "segments":  { "url": "/api/tasks/3f2a.../artifacts/TRANSLATED/segments.json?lang=zh-Hans", ... },
               "subtitles": {...}, "terms": {...} } }
]
```

**提交 / 翻译 / 重生成（三个 POST）**

```jsonc
// 202 Accepted 与 409 Conflict 同形状
{ "taskNo": "3f2a9c1e...", "runNo": 3 }
```

**SSE（事件名 `progress`）**

```jsonc
{ "taskNo": "3f2a9c1e...",
  "runNo": 3, "runType": "TRANSLATE", "targetLanguage": "zh-Hans",
  "status": "RUNNING",
  "stage": { "stage": "translate-llm", "progress": 40, "message": "...", "state": "RUNNING" },
  "terminal": false }
```

**前端需要的每件事，各自有唯一来源**（这是 C53 的验算）：

| 前端要问的 | 判据 |
|---|---|
| 忙不忙 | `lastRun?.status === 'RUNNING'` |
| 卡在哪一步 | `lastRun.currentStage` + SSE 的 `stage` |
| 刚才怎么败的 | `lastRun.errorMessage`（翻译失败也在这里，不会被主状态的 `SUCCEEDED` 盖掉） |
| 字幕好了吗 / 能不能翻译 | `/artifacts` 里有 `type=CLEANED` 的项 |
| 下载什么 | `/artifacts` 的 `files` |

**注意：别拿 `lastRun.status === 'SUCCEEDED'` 当"能不能翻译"的判据** —— RECLEAN 失败时 `lastRun` 是 `FAILED`，可上一版 `CLEANED` 还是好的、翻译照样能点。

**两条连带**（新模型逼出来的，不是自由选择）：

1. **409 的语义整个变了。** 现状 `TaskConflictResponse` 是**去重语义**（同 URL / 同内容冲突，带 `existingTask` 让前端跳过去）；新模型里提交一个做过的 URL **不冲突** —— 命中 `workspace` → 拿它的 `subtitle_task` → 插新 MAIN run（C18）。409 只剩 `uk_active_run` 撞键一种。注意 `POST /api/tasks` 那一路的 409 **必须带 `taskNo`**：翻译 / 重生成时 taskNo 在路径里，但提交一个正在跑的 URL 时前端自己算不出来。
2. **`INTERRUPTED` 第一次露到前端。** 现状状态机没有这个值；新模型里进程被 kill 后那行是 `INTERRUPTED`，`lastRun.status` 会把它送出去 —— 前端要能显示"上次被中断了，重新提交即可"（C17：不续跑）。这是**新增的第五态**。

### 3.10 分期与切换（2026-10-17 第五轮定案）

| # | 决策 | 关键理由 |
|---|---|---|
| **C59** | **分阶段做完**：`design.md` 写**目标终态**，`implement.md` 才切段（阶段边界留到文档齐了再定）。每段验收线 = **编译过 + 测试绿 + 后端能起服务、能用 HTTP 手动验证**（前端点不通是预期内的） | 大工程，风险全在"四层贯穿"；切成能亲眼验证的小块，把风险摊开。不要求"能上线"—— 本地项目没有灰度需求 |
| **C60** | **不保旧 API**；**前端不参与分期** —— 等所有后端阶段做完，另开任务接接口 | 个人项目、无外部消费方、前端同仓库随时改；买兼容层是纯浪费。与父任务"前端另开任务"的原始定位一致 |

### 3.11 中间产物（2026-10-17 第六轮定案）

| # | 决策 | 关键理由 |
|---|---|---|
| **C62** | **中间产物落库**（新表 `intermediate_file`，DDL 见 §4.6）：`(task_id, file_name)` 唯一、**三值**状态机 `READY / SUPERSEDED / DELETED`、**复用判据从"文件在不在"改成"表里 `READY` 且文件在"** | 缺它就会出「**指纹变了等于没变**」：级联只写 `subtitle_artifact` 表，盘上中间产物一个没动，而"跳过转写"的判据是"文件在不在"（`SubtitlePreparationCoordinator` 的原话就是"**已有 segments 产物，跳过转写**"）→ 主管线"重跑"全部跳过 → **产出旧内容而 DB 说是新的，静默交付**。跟 丙 那个代价同一档，甚至更糟：丙 那个靠"折进事务"能堵住，这个堵不住，因为**盘上的东西根本没参与那次事务** |

**为什么不是"塞进 `subtitle_artifact`"**（排查过，六处冲突，两处是硬的）：

| 冲突 | 说明 |
|---|---|
| ⛔ `uk_active_artifact (task_id, type, lang, active_flag)` | 语义是"一 `(task,type,lang)` 至多一条 `READY`"，而音频**可能多轨**（文件名里的 `-t<轨号>`）→ 两行 → 撞索引 |
| ⛔ `uk_task_type_rev_lang (task_id, type, revision, lang)` | 中间产物没有版本概念，`revision` 固定 1 的话，内容变了重跑时旧行还在 → **撞键**；于是被迫给一个本无版本概念的东西维护递增 |
| 四个文件列 | 中间产物只有一个文件，得硬挑一列装，另三列恒 NULL |
| `derived_from_artifact_id` | 中间产物的 lineage 是"**管线步骤**"，跟"产物版本派生"不是一回事，混一列 |
| 对外可见性 | `/artifacts` 及所有读路径都要**记得过滤**中间产物 —— C20 否掉"用文件列表达状态"的理由就是"很容易漏同步"，这里同类且更分散 |
| `status` 五值 | `CREATING` / `FAILED` 对中间产物是硬套上去的 |

**净账：6 处污染，换掉一条文件名白名单。**

**为什么状态是三个值、不是五个**（D-6 口径）：

| 值 | 意味着什么 | 写入路径 |
|---|---|---|
| `READY` | 文件在、是有效缓存 | ① 首次产出（INSERT）② 重新产出（`SUPERSEDED` / `DELETED` → `READY`） |
| `SUPERSEDED` | **已作废，文件待删** | ① 内容变更级联（指纹变了） |
| `DELETED` | **文件已确认回收** | ① 当场删完标 ② C26 对账补删完标 |

- **`SUPERSEDED` 必需**：`DELETED` 的语义必须跟 artifact 表一致（"文件已确认回收"）。两值之下 `DELETED` 得同时表示"待删"和"已删" → **违反 D-6**（同一个值两种意思）。C22 的"先标 DB、后删文件"本来就要求两个不同的标记。
- **`CREATING` 不要**：它是 C19「先占位、后落定」的产物，买的是"留版本痕迹 / 给 C15 一个可收对象 / 占住 revision"。中间产物是**纯缓存**（D-4），"没写成" = "没写过"，三样都不要；而且它走 C51 的「**先落盘、后登记**」，**压根没有"登记了但文件还没写"这个窗口**。
- **`FAILED` 不要**：**零写入路径 = 死值**，D-6 明确不允许。写文件失败时原子替换保证正式路径原封不动，没有"写坏的行"。

**`READY` 是唯一允许"复活"的值**（`SUPERSEDED` / `DELETED` → `READY`）—— 内容变了重新产出时**复用同一行**（`uk_file` 唯一），不像 artifact 那样插新版本。

---

## 4. 表设计

**六张表**。MySQL 8，**只写逻辑外键**（不写 `FOREIGN KEY` 约束）。

> ⚠️ **本节 6 个 DDL 块是 §9.4h / §9.4j / C64 定案（2026-10-17 第七轮）之前的草稿。** 四处未订正：**索引/约束名**（没带表名段、主键没具名）、**枚举列宽**（`VARCHAR(16)` → `(32)`）、**§4.4 那四列**（`segments_url` 等 → `segments_file_name` 等，`VARCHAR(1024)` → `(255)`）、**§4.4 未加 C64 的四列 sha256**。读的时候以 **§9.4h / §9.4j / C64** 为准；权威 DDL 归 `design.md` 统一重写。
> **本节不就地重写**（2026-10-17 第七轮定）—— handoff 是**讨论共识、不是最终设计**，就地重写等于维护一份会与 `design.md` 冲突的副本。

### 4.1 workspace —— 媒体身份

```sql
CREATE TABLE workspace (
  id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
  source_type    VARCHAR(16)  NOT NULL COMMENT 'URL / UPLOAD',
  source_key     VARCHAR(512) NOT NULL COMMENT 'URL: bvid|p   UPLOAD: 内容 sha256',
  source_uri     VARCHAR(2048) NOT NULL COMMENT 'URL 原文 / 上传文件原始名。红线：不得写日志',
  canonical_url  VARCHAR(1024) NULL     COMMENT '规范化后的 URL，只给人看',
  duration_ms    INT          NULL      COMMENT '内容指纹之一（URL 路线）；UPLOAD 留空',
  published_at   DATETIME     NULL      COMMENT '内容指纹之二，来自 yt-dlp 的 timestamp',
  created_at     DATETIME     NOT NULL,
  updated_at     DATETIME     NOT NULL,
  UNIQUE KEY uk_source (source_type, source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体身份';
```

- 内容指纹**不另拼一列串**，就是 `duration_ms` + `published_at` 两个可读列的组合（C27）
- **这两列的写入时机**：由【指纹探测阶段】写（C47 / C48）—— **不是提交时写**。创建工作区记录发生在提交路径上（§7.1 步骤 3，同步、秒回 202），那一刻手里只有 URL 剥出来的 bvid，**拿不到指纹**；`duration` / `timestamp` 要 yt-dlp 取元数据，是网络活，所以按 C29 放在管线里。首跑 insert 时这两列就是 `NULL`
- `UPLOAD` 路线的身份即内容 hash（C12），所以指纹两列留空
- 原方案里的 `name` / `content_hash` / `storage_url` / `file_name` / `size_bytes` / `status` **都不引入**（无来源或无场景）；`file_name` / `size_bytes` 若要落，来源是 yt-dlp 白给的元数据，**属待定项**（见 9.4）

### 4.2 subtitle_task —— 字幕任务

```sql
CREATE TABLE subtitle_task (
  id           BIGINT      PRIMARY KEY AUTO_INCREMENT,
  task_no      VARCHAR(32) NOT NULL COMMENT '对外稳定号（32 位短 UUID）',
  workspace_id BIGINT      NOT NULL,
  created_at   DATETIME    NOT NULL,
  updated_at   DATETIME    NOT NULL,
  UNIQUE KEY uk_task_no (task_no),
  UNIQUE KEY uk_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字幕任务';
```

**注意：没有状态列。** 主状态、失败原因、阶段全部从 `task_run` 派生（与砍 `latest_*` 同一条理由）。

### 4.3 task_run —— 每次执行

```sql
CREATE TABLE task_run (
  id              BIGINT      PRIMARY KEY AUTO_INCREMENT,
  task_id         BIGINT      NOT NULL,
  run_no          INT         NOT NULL COMMENT '任务内递增',
  run_type        VARCHAR(16) NOT NULL COMMENT 'MAIN / RECLEAN / TRANSLATE',
  target_language VARCHAR(16) NULL     COMMENT '只有 TRANSLATE 有值',
  status          VARCHAR(16) NOT NULL COMMENT 'RUNNING/SUCCEEDED/FAILED/INTERRUPTED',
  current_stage   VARCHAR(64) NULL     COMMENT '前端“卡在哪一步”',
  error_message   TEXT        NULL     COMMENT '失败原因（不再需要中文前缀区分来源）',
  started_at      DATETIME    NULL     COMMENT '开始执行：插 RUNNING 行时填 now()',
  finished_at     DATETIME    NULL     COMMENT '落终态时填 now()；INTERRUPTED 填的是启动收口的时刻',
  created_at      DATETIME    NOT NULL,

  active_flag VARCHAR(16) GENERATED ALWAYS AS (IF(status='RUNNING','RUNNING',NULL)) STORED,
  UNIQUE KEY uk_task_run_no (task_id, run_no),
  UNIQUE KEY uk_active_run (task_id, active_flag),   -- 互斥：一个 task 同时最多一条 RUNNING
  KEY idx_task_type_status (task_id, run_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每次执行';
```

`uk_active_run` 是 MySQL 里做"部分唯一索引"的标准技巧：**NULL 不参与唯一比较**，所以只有 `status='RUNNING'` 的行会被约束住，其余全为 NULL 互不冲突。

于是**抢互斥退化成一句 INSERT**：插一条 RUNNING 的 run，撞唯一键就是抢失败（409）。不需要事务锁、不需要 CAS 重试、不需要内存登记表。

**两条补充**（2026-10-17 第六轮）：

- **`run_no` 用 `MAX+1`，不需要事务。** 实际是"`SELECT MAX(run_no)` + `INSERT`"两句，但并发下两个插入者会撞 `uk_task_run_no` 或 `uk_active_run`，失败方拿 409 —— **行为正确**。因为每次插的都是 `RUNNING` 行，而 RUNNING 互斥，`MAX+1` 的竞态天然被兜住。（`revision` 同理，见 §4.4。）
  *残留一条理论上的缝*：B 撞的是 `uk_task_run_no`（不是 `uk_active_run`）时，理论上会出现"对方的 run 已跑完、B 却仍被拒"的**假 409** —— 窗口是"A commit"到"B 的 INSERT 解除阻塞"之间，**微秒级**，A 的异步体跑不完。**接受，不修**（与 C46 同取向）。
- **时间列的写入点**（原文只有列、没有时机，跟 C45 当初同款病）：`started_at` = 插 `RUNNING` 行时填 `now()`；`finished_at` = **落终态时**填 `now()`。`INTERRUPTED` 那条填的是**启动收口的时刻**，不是崩溃时刻 —— **那是记账时刻**，别当"结束时刻"用。

### 4.4 subtitle_artifact —— 产物版本

```sql
CREATE TABLE subtitle_artifact (
  id                 BIGINT      PRIMARY KEY AUTO_INCREMENT,
  task_id            BIGINT      NOT NULL,
  derived_from_run_id BIGINT     NOT NULL COMMENT '哪一次执行产出的（审计；C61 的收口靠它定位）',
  artifact_type      VARCHAR(16) NOT NULL COMMENT 'ORIGINAL / CLEANED / TRANSLATED',
  lang               VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'TRANSLATED 才填；空串=无语言维度',
  revision           INT         NOT NULL COMMENT '同 (task,type,lang) 递增',
  status             VARCHAR(16) NOT NULL COMMENT 'CREATING/READY/SUPERSEDED/FAILED/DELETED',
  derived_from_artifact_id BIGINT NULL   COMMENT '审计 / 溯源用：内容继承自哪一版上游产物。不参与级联、不参与读取判据（两者都由 status 决定）',
  keywords_fingerprint VARCHAR(64) NULL   COMMENT '只有 CLEANED 有值（C33）',
  segments_url       VARCHAR(1024) NULL COMMENT '检查点 JSON',
  subtitles_url      VARCHAR(1024) NULL COMMENT 'SRT 派生视图',
  context_url        VARCHAR(1024) NULL COMMENT '只有 CLEANED 有（摘要副产物）',
  terms_url          VARCHAR(1024) NULL COMMENT '只有 TRANSLATED 有（术语查证副产物）',
  created_at         DATETIME    NOT NULL,

  active_flag VARCHAR(16) GENERATED ALWAYS AS (IF(status='READY', status, NULL)) STORED,
  UNIQUE KEY uk_task_type_rev_lang (task_id, artifact_type, revision, lang),
  UNIQUE KEY uk_active_artifact (task_id, artifact_type, lang, active_flag),  -- 一 (task,type,lang) 最多一条 READY（C41）
  KEY idx_task_type (task_id, artifact_type),
  KEY idx_run (derived_from_run_id),
  KEY idx_derived_from (derived_from_artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字幕产物版本';
```

三个要点：

1. **`lang NOT NULL DEFAULT ''`** —— MySQL 里 NULL 不参与唯一约束，若允许 NULL，`(1,'ORIGINAL',1,NULL)` 能插两条。空串占位才对。（这一条原 6 表方案已经写对了。）
2. **一行 = 一个逻辑版本**，文件用固定几列表达。现状 8 类文件天然分成三代三组：

| 逻辑版本 | 文件 | 备注 |
|---|---|---|
| `ORIGINAL` | `original-segments.json` + `original-subtitles.srt` | 无语言维度 |
| `CLEANED` | `clean-segments.json` + `clean-subtitles.srt` + `context.json` | `context` 是清洗的副产物 |
| `TRANSLATED(lang)` | `translated-segments-{lang}.json` + `translated-subtitles-{lang}.srt` + `terms-{lang}.json` | 术语表按语种 |

（原方案里的 `bilingual_srt_url` 砍掉 —— 这个功能现状不存在。）

3. **`uk_active_artifact` 与 `uk_active_run` 是同一个技巧**（生成列 + NULL 不参与唯一比较），语义是"一 `(task, type, lang)` 最多一条 `READY`"：`lang` 对 `ORIGINAL` / `CLEANED` 是 `''`、对 `TRANSLATED` 是语种，正好落成"每语种一条当前译文"。

   它还**逼出落定顺序**（C41）：

   1. INSERT 新行（`CREATING`）—— active_flag 为 NULL，不撞
   2. 写文件（临时 → 原子替换）
   3. UPDATE 旧行 `READY` → `SUPERSEDED`（+ 清空它的文件列）
   4. UPDATE 新行 `CREATING` → `READY` **+ 写文件列**

   3 与 4 的顺序不可换（换了直接撞重复键）。

   **第 4 步为什么把"写文件列"并进来**（2026-10-17 第六轮定）：原文把"写文件列"放在**产出那一步**（§7.4 / §7.5 步骤 5 写的是"插 `CREATING`；**随后写** `segments_url` …"），于是从"写列完成"到"换版"之间，那一行的状态是「`CREATING` **但文件列已填、盘上文件已完整**」—— 而 §6 说 `CREATING` 意味着"文件还没写完"。**同一个值两种含义，违反 D-6。** 并进第 4 步之后，`CREATING` 行**永远是全 NULL**，含义干净。

   （**别**指望用"文件列非空 ⟺ `READY`"这条不变量兜底 —— 它**不成立**：下游译文是**孤儿型**，`SUPERSEDED` 时**必须留着文件列**，否则 §7.4 步骤 7 删文件时没有路径可删，而 C52 明确路径是 DB 的列、不是拼接公式。）

### 4.5 subtitle_keyword —— 同现状

```sql
CREATE TABLE subtitle_keyword (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  task_id       BIGINT       NOT NULL COMMENT '外键指向 subtitle_task.id（原为 workspace_key）',
  keywords      VARCHAR(4096) NOT NULL COMMENT 'U+0001 连接，顺序即用户填写顺序',
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL,
  UNIQUE KEY uk_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字幕关键词（当前生效一份）';
```

### 4.6 intermediate_file —— 中间产物（task 级缓存）

```sql
CREATE TABLE intermediate_file (          -- 名字待 §9.4h
  id                   BIGINT        PRIMARY KEY AUTO_INCREMENT,
  task_id              BIGINT        NOT NULL,
  file_name            VARCHAR(255)  NOT NULL COMMENT '工作区内文件名；文件系统上限即 255',
  kind                 VARCHAR(16)   NOT NULL COMMENT 'SOURCE_MEDIA / INFO_JSON / AUDIO',
  status               VARCHAR(16)   NOT NULL COMMENT 'READY / SUPERSEDED / DELETED',
  derived_from_run_id  BIGINT        NULL     COMMENT '产出它的那条 run（审计）',
  created_at           DATETIME      NOT NULL,
  updated_at           DATETIME      NOT NULL,
  UNIQUE KEY uk_file (task_id, file_name),
  KEY idx_task_status (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中间产物（task 级缓存）';
```

三个要点：

1. **`UNIQUE KEY (task_id, file_name)` 决定了状态机的形状** —— 一个文件在一个 task 里**只有一行**，所以"重新产出"是 `UPDATE` 那行回 `READY`，不是插新行。这跟 `subtitle_artifact` **本质不同**：那边有版本交替（插新行 + 旧行 `SUPERSEDED`），这边没有版本、一行到底 —— 所以它不需要 `revision`，也不需要 `CREATING`。
2. **`file_name` 是 255 不是 1024** —— 它是**文件名**不是路径。文件系统本身就卡在 255（ext4 按字节、NTFS 按字符），不可能溢出。（`segments_url` 那四列的 1024 也待复核，见 §9.4j。）
3. **`kind` 只有三个值，每个都有代码常量背书**：

| kind | 文件名 | 常量 |
|---|---|---|
| `SOURCE_MEDIA` | `source.<ext>` | `MediaInput.SOURCE_FILE_PREFIX` |
| `INFO_JSON` | `source.info.json` | `MediaInput.SOURCE_INFO_JSON_NAME` |
| `AUDIO` | `audio-<规格>-t<n>.<ext>` | `AudioArtifact.AUDIO_PREFIX` |

**没有 `ASR_RAW`** —— C50 里那句"ASR 原始输出"是没验过的话（见 C50 的订正）。

**`derived_from_run_id` 在本表里是审计列、没有承重用途**（复用判据不看它、级联不看它、对账不看它、收口不需要它）—— 跟 artifact 表**不对称**（那边 C61 的收口靠它定位）。留着是因为口径跟 `derived_from_artifact_id` 一致：**有独有信息、没有承重使用者**。

---

## 5. 关系图

```
  workspace (媒体身份)                ← source_key 去重（URL: bvid|p / UPLOAD: sha256）
       │
       │ 1:1
       ▼
  subtitle_task (对外 task_no 稳定)   ← 无状态列，状态全在 run
       │
       ├── 1:N ──▶ task_run          ← 每次执行；uk_active_run 保证互斥
       │                │
       │                │ 1:N
       │                ▼
       ├── 1:N ──▶ subtitle_artifact ← 版本化产物；READY 是唯一有效态
       │                │
       │                └── derived_from_artifact_id 自引用（审计 / 溯源）
       │
       ├── 1:N ──▶ intermediate_file ← 中间产物（task 级缓存，跨 run 存活）；见 §4.6
       │
       └── 1:1 ──▶ subtitle_keyword
```

外键走向（**逻辑外键，应用层保证**）：

```
workspace.id         ← subtitle_task.workspace_id
subtitle_task.id     ← task_run.task_id
subtitle_task.id     ← subtitle_artifact.task_id
subtitle_task.id     ← intermediate_file.task_id
subtitle_task.id     ← subtitle_keyword.task_id
task_run.id          ← subtitle_artifact.derived_from_run_id
task_run.id          ← intermediate_file.derived_from_run_id
subtitle_artifact.id ← subtitle_artifact.derived_from_artifact_id（自引用）
```

**边界（C40 已被替换，2026-10-17 第六轮）**：**不按类划**，按 **§3.7.3 的写集表** —— 边界 = `{W1, W5, W5′}`。`TaskRun` / `SubtitleArtifact` / `Task` **都不是边界**，是带行为的领域实体 / 身份实体。

> 原来那段"聚合根 / 实体 / 值对象"的**类级标签整体作废**（C40 作废，见 §3.7）。V2 / V3 两条唯一性由 DB 唯一索引兜（C14 / C41）；W1 / W5 / W5′ 三条由**显式事务**兜。

---

## 6. 状态机

**三处**状态，各管各的。

```
task_run.status
  （无） ──插行──▶ RUNNING ──▶ SUCCEEDED
                     │
                     └────▶ FAILED          （无条件收口，C43）
                     │
          （进程重启，启动收口补记，C15）
                     └────▶ INTERRUPTED
```

```
subtitle_artifact.status
  (无) ──插行──▶ CREATING ──换版落定──▶ READY ──┬──▶ SUPERSEDED ──▶ DELETED
                    │                           │  （不再是当前版）   （文件确认回收）
                    └──收口置位──▶ FAILED       │
                                                └── 覆盖型：SUPERSEDED 与文件列清空同一刻
```

（`CREATING` 的出口写的是「**换版落定**」不是"写文件成功" —— 写文件列并进了换版那一步，见 §4.4 第 3 点。）

写入路径（D-6）—— 每个值的**全部**路径逐条列出，且同一个值的所有路径"**这个值意味着什么**"必须一致：

**`task_run.status`**

| 值 | 意味着什么 | 写入路径（全部，逐条列） | 路径类型 |
|---|---|---|---|
| `RUNNING` | 这条 run 正在跑。**一个 task 同一时刻只允许有一条这样的记录** —— 所以这一行能插进去，本身就说明抢到了执行权（**插入即抢锁**） | 提交 / 重新生成字幕 / 翻译这三条路，都是**先插一行 RUNNING 才开始干活**（§7.1 步骤 4、§7.4 步骤 2、§7.5 步骤 2）。同一个 task 已有 RUNNING 行时会插不进去 → 返回 409 | 正常 |
| `SUCCEEDED` | 跑完了，产物齐了 | run 体正常走到底（§7.3 步骤 5 等） | 正常 |
| `FAILED` | 跑了，**知道它没成** | **收口置位**（C43）：执行体退出时还没落终态，一律置 `FAILED` | 正常 |
| `INTERRUPTED` | **不知道**跑成没跑成（进程没了） | **启动收口**（C15）：进程启动时发现残留 `RUNNING` | 恢复 |

**每个值恰好一条路径** —— 这张表不需要"正常/恢复共享同一个值"那种情形。

**`subtitle_artifact.status`**

| 值 | 意味着什么 | 写入路径（全部，逐条列） | 路径类型 |
|---|---|---|---|
| `CREATING` | 占位：行在，文件还没写完 | 开始产生产物时插行（C19） | 正常 |
| `READY` | **当前有效版**（业务查询只认它） | 文件写完，改行 | 正常 |
| `SUPERSEDED` | **不再是当前版**（两种原因，同一个意思） | ① 同 `(task, type, lang)` 出现更高 `revision`（换版）<br>② 上游 `CLEANED` 换版 → 下游全部（级联，C47） | 正常 · 正常 |
| `FAILED` | **这一版产物没成**（两种原因，同一个意思） | ① **收口置位**（**C61**）：本次 run 插的、仍停在 `CREATING` 的行 → `FAILED`（这一条**同时覆盖**"写文件失败"和"run 异常 / 早退"）<br>② 启动收口把残留 `CREATING` 落下来（C15） | 正常 · **恢复** |
| `DELETED` | 文件已确认回收 | 删完文件之后标（C22 两阶段） | 正常 |

两个"有两条路径"的值都过一遍自检：`SUPERSEDED` 两条都是"不再是当前版" ✅；`FAILED` 两条都是"这一版产物没成" ✅。**语义相同才允许合；不同就得拆值**（`FAILED` / `INTERRUPTED` 就是这么拆出来的）。

**`intermediate_file.status`**（**C62**，2026-10-17 第六轮）

```
（无）──INSERT READY──▶ READY ⇄ SUPERSEDED ──▶ DELETED
                        ▲       （已作废，文件待删）  （文件确认回收）
                        └─────────────────────────────┘
                            重新产出时 UPDATE 回 READY
```

| 值 | 意味着什么 | 写入路径（全部，逐条列） | 路径类型 |
|---|---|---|---|
| `READY` | 文件在、是**有效缓存** | ① 首次产出（INSERT）<br>② 重新产出（`SUPERSEDED` / `DELETED` → `READY`） | 正常 · 正常 |
| `SUPERSEDED` | **已作废，文件待删** | ① 内容变更级联（指纹变了，C31 / W5′） | 正常 |
| `DELETED` | **文件已确认回收** | ① 当场删完标（C22 两阶段）<br>② C26 对账补删完标 —— **本次不实现**（**C65** 把对账做成**只读**，② 只报不修，见 §3.4） | 正常 · **恢复** |

**为什么只有三个值**（跟 artifact 的五个值对照）：

- `CREATING` / `FAILED` **没有**：中间产物是纯缓存（D-4），"没写成" = "没写过"，不需要留痕迹；写坏了临时文件被丢弃、正式路径原封不动，没有"写坏的行"。`FAILED` 若留就是**零路径的死值**（D-6 不允许）。
- `SUPERSEDED` **必需**：`DELETED` 的语义必须跟 artifact 一致（"文件已确认回收"）。若只有两值，`DELETED` 得同时表示"待删"和"已删" → **同一个值两种意思，违反 D-6**。
- **`READY` 是唯一允许"复活"的值** —— 重新产出时复用同一行（`uk_file` 唯一），不像 artifact 那样插新版本。

### 为什么没有 `PENDING`

`task_run.status` 只有四个值，**没有 `PENDING`**。它的缺席是"一句 INSERT 抢锁"的**结构性要求**，不是疏漏：

```
现在：INSERT task_run(status='RUNNING')   ── 撞 uk_active_run 就是抢锁失败
      ⇒ 锁和状态是同一件事，一次写完成

若加 PENDING：INSERT(status='PENDING')   ← active_flag 为 NULL，不占锁
              UPDATE status='RUNNING'    ← 这一步才占锁
              ⇒ 变两步；崩在中间就留下一条永远 PENDING 的行，
                而且"锁"与"状态"分了家 —— 正是 C14 要消灭的那种分裂
```

将来真要排队（提交即返回、稍后开跑），`PENDING` 得**带着一个明确的写入时机**一起回来。

---

## 7. 业务流程

### 7.1 提交（URL）—— 接入层，同步，秒回 202

1. 校验入参（关键词上限等）
2. **算身份键**：剥跟踪参数 → 取 `/video/` 段的 `BV...`（`av...` 换算）→ 拼 `p`（缺省 1）。抽不到就跟一次跳转（**超时 2 秒 + 缓存映射**，C11）；仍抽不到回落规范化 URL + 记日志
3. `SELECT workspace WHERE source_type+source_key`
   - **命中** → 拿它的 `subtitle_task`
   - **未命中** → **`INSERT workspace` + `INSERT subtitle_task`（同一事务 = W1）**（生成 task_no）
   - **撞 `uk_source`**（两个请求**同时**提交同一个新 URL）→ **C63**：**不重试插入、不回滚重开事务**，在**同一事务**里用**锁定读**（`FOR SHARE`）重查 workspace 拿 `workspace_id`，再用**锁定读**按 `uk_workspace` 查 `subtitle_task`；两行到手后照常进入第 4 步
4. `INSERT task_run`（`run_type=MAIN`，`status=RUNNING`；`started_at` = `now()`）
   - **撞 `uk_active_run`** → 409，响应体带 `{ taskNo, runNo }` 供订阅（C56）
5. **关键词覆盖写（可选）** —— 只有请求带了 `keywords` 才写（`null` = 不动库里那份，`[]` = 写空清单）；位置与 §7.4 对齐（**抢互斥之后**）
6. 返回 **202 + `{ taskNo, runNo }`**

> **第 3 步的并发分支（C63，2026-10-17 第七轮）** —— 完整理由见 §3.2 的 C63 详解。**一句话**：撞 `uk_source` 不是错误，是"同一个视频被同时提交"，所以两个请求收敛到同一条 task 上；重查**必须用锁定读**（RR 快照在本次 `SELECT` 就固定了，普通 SELECT 读不到对方刚提交的行）。**注意 B 的退出点不在这一步** —— B 拿到两行后照常进第 4 步，撞 `uk_active_run` 才 409。

> **第 5 步是 2026-10-17 第六轮补的** —— 原文五步里**一个字都没提关键词**，但提交路径**确实**可以带（现状签名 `submitUrl(String url, List<String> keywords)` 就有），§7.4 那边也是有的（步骤 3「关键词落库」），只有提交这两条路漏了。
> **不并进 W1 那个事务**：关键词是**可选**的、且**可恢复**（崩了重提交就覆盖上了），不符合 W1 那条"不可恢复"的判据；而且命中既有 workspace 时不走 W1 事务，并进去会让两条路线的形状不一致。

### 7.2 提交（上传）

同上，只是第 2 步换成"流式写盘边算 sha256"（**免费**拿到内容身份），第 3 步改用 sha256 做 `source_key`。

### 7.3 主管线（MAIN run）

1. 阶段推进 → `UPDATE task_run SET current_stage=?` + 广播 SSE
2. **指纹探测阶段（媒体获取之前，C29 / C47）** —— 阶段内顺序写死：

   1. **探测**：yt-dlp 只取元数据（不下载媒体）→ `duration` / `timestamp`
   2. **比对**：与 `workspace` 记的两列比
   3. **级联**：不一致 → 旧 artifact **和中间产物**全部标 `SUPERSEDED`；随后删文件（C22，**在事务提交之后**）
   4. **写指纹**：`UPDATE workspace SET duration_ms=?, published_at=?`

   - **3 与 4 在同一事务**里（C47）。事务内先后自由 —— 那些行是 `READY → SUPERSEDED`，`active_flag` 都变 NULL，不参与唯一比较，撞不上
   - 一致 → 3、4 都不做；首跑（两列 `NULL`）→ 只做 4（C48）
   - **取不到指纹 → 跳过比对**，回落"有产物就复用"，记 WARN（C49）。**这是降级路径，不是失败路径** —— 不能把"取不到"当异常抛出去，否则一次元数据风控会把整条 run 判死

   **③ 之后"删文件"删哪些**（2026-10-17 第六轮补 —— 原文笼统写"随后删文件"、没分类，跟 §7.4 已经分清的两种终态不一致）：

   | 被作废的 | 终态 |
   |---|---|
   | **清空文件列、不删文件** —— `ORIGINAL` / `CLEANED` | 停在 `SUPERSEDED`。两条理由：新 MAIN run 会重写同名文件；而且它们**有"文件列"这个开关**来表达"我不再管这个文件"（C24 清空） |
   | **删文件 → `DELETED`** —— `TRANSLATED(*)` + **中间产物** | `SUPERSEDED` → 删 → `DELETED`。新 MAIN run 不产出译文；中间产物**没有"文件列"可清空**（只有一个 `file_name`，还不能清 —— 它是唯一键的一部分），不删就会长期挂着一条"表说作废、文件还在"的行 |

3. `ORIGINAL` 产物：插 `CREATING` → 写文件 → **换版落定**（写文件列 + `READY`）
4. `CLEANED` 产物：同上，并记 `keywords_fingerprint`

   > **每一步产出之前都要先查表**（**C62**）—— **复用判据从"文件在不在"改成"表里 `READY` 且文件在"**。
   > 现状是纯看文件：`SubtitlePreparationCoordinator` 的原话就是"**已有 segments 产物，跳过转写**"，判据是 `CheckpointPolicy.isCompleted(ArtifactFiles.sizeOrZero(path))`。指纹级联只动 DB、盘上文件一个没动 → 主管线"重跑"全部跳过 → **产出旧内容**。查表之后，级联一标 `SUPERSEDED`，复用立刻失效。
   > （现状已经有半个雏形：CLEANED 的复用用的是 `CheckpointPolicy.isCurrent(..., keywordsFingerprint, fingerprint)`，**带指纹比对** —— C33 把它提到表上，正是同一模式的先例。）

5. 成功 → `task_run.status = SUCCEEDED`（**描述**，实现见 C44）
6. 失败 → **收口统一置 `FAILED`**（C43）；流程内的 catch 只负责丰富 `error_message` / `current_stage`（`error_message` **不再有中文前缀**）

（现状"删译文"那个动作在新模型里不存在了：`CLEANED` 一变，下游译文在**同一个事务**里按 `status='READY'` 全标 `SUPERSEDED`，**触发点是唯一事件**，1.3 那条裂缝自然闭合。）

#### 两条级联的时序差异是**有意为之**

| | §7.4（重洗） | 本节步骤 2（指纹变了） |
|---|---|---|
| 什么时候作废旧的 | **新的 `READY` 那一刻** | **跑主管线之前**（先把旧的全标掉） |
| 期间旧产物 | **整个运行期间仍可下载** | **整个主管线期间该 task 零 `READY`**（前端一片空白） |
| 为什么不一样 | 关键词变了，**内容还是同一个视频** —— 旧版是个合理的降级兜底 | **内容变了** —— 旧产物是**别的内容的**，不能当兜底，留着反而是错的 |

丙 之后"当前版"完全由 `status` 说了算，所以这个差异变成**用户可见**的。**写明是有意为之，别让后人当 bug 修。**

### 7.4 重新生成字幕（RECLEAN run）

**前置**：该 task 存在 `READY` 的 `ORIGINAL`。

| # | 步骤 | DB 写什么 | 文件写什么 | 崩在这会怎样 |
|---|---|---|---|---|
| 1 | 校验关键词 | **不写**（超限 → 400，不落库、不重跑） | **不动** | 无 |
| 2 | **抢互斥** | `INSERT task_run (RECLEAN, RUNNING)` —— 撞 `uk_active_run` → 409 | **不动** | 无（还没开跑） |
| 3 | 关键词落库 | `subtitle_keyword` **覆盖写**（`uk_task`：一 task 一份） | **不动** | 无 |
| 4 | **重摘要 → 重洗** | 只推进 `current_stage` + 广播 SSE | **不动** | ✅ **最常见的失败点**（LLM 报错 / 超时）：旧 `CLEANED` 和它的文件**一点没动**，收口置 `FAILED` 就完了 |
| 5 | 落新产物 | 插 `CREATING`（`revision+1`、`derived_from_artifact_id` = `ORIGINAL` 当前版、`keywords_fingerprint`）—— **只插行，不写文件列** | 写 3 个**临时文件** → **逐个原子替换**到正式路径（同名覆盖 → 旧 `CLEANED` 的文件此刻被顶掉） | 旧内容丢了但 DB 还说它是当前版 → **重跑收敛**。半成品不会留下（原子替换，C50）。残留的 `CREATING` 行由收口置 `FAILED`（C61） |
| 6 | **换版（同一事务）** | ① 旧 `CLEANED` → `SUPERSEDED` + **清空它的文件列**<br>② 所有下游 `TRANSLATED(*)` → `SUPERSEDED`（级联）<br>③ 新 `CLEANED` → `READY` **+ 写 `segments_url` / `subtitles_url` / `context_url`** | **不动**（文件在步骤 5 已就位，见 C51） | 事务回滚 → 状态全是旧的、盘上是新内容 → **重跑收敛** |
| 7 | **删下游译文的文件**（事务外） | 删完再把那些行标 `DELETED` | 删 `translated-segments-*.json` / `translated-subtitles-*.srt` / `terms-*.json` | 只留孤儿文件（C22 明确接受） |
| 8 | **收尾** | `task_run.status = SUCCEEDED` + `subtitle_task.updated_at = now()` | **不动** | —— |

**两种终态要分清**（C23）：

| 谁被取代 | 它有没有自己的文件 | 终态 |
|---|---|---|
| **旧 `CLEANED`** | **没有** —— 文件名不带 `revision`，同名文件此刻已归新行（**覆盖型**） | 停在 `SUPERSEDED`，**不到 `DELETED`** |
| **下游 `TRANSLATED(*)`** | **有** —— 没有任何新版本会去写它们的路径，成了孤儿 | `SUPERSEDED` → 删文件 → `DELETED` |

**不重跑 ASR、不重抽音频**（现状 A1 的结论，不变）。

### 7.5 翻译（TRANSLATE run）

**前置**：存在 `READY` 的 `CLEANED`；语种在白名单（`GET /api/languages`）。

| # | 步骤 | DB 写什么 | 文件写什么 | 崩在这会怎样 |
|---|---|---|---|---|
| 1 | 校验 | **不写** | **不动** | 无 |
| 2 | **抢互斥** | `INSERT task_run (TRANSLATE, RUNNING, target_language)` | **不动** | 无 |
| 3 | **复用判据** | **只读**：`TRANSLATED(lang, status=READY)` 存在？**命中 → 直接跳到第 7 步** | **不动** | 无 |
| 4 | **翻译** | 只推进 `current_stage` + 广播 | **不动** | ✅ **最常见的失败点**：旧译文和它的文件一点没动 |
| 5 | 落新产物 | 插 `CREATING`（`revision+1`、`derived_from_artifact_id` = 当前 `CLEANED.id`）—— **只插行，不写文件列** | 写 3 个**临时文件** → **原子替换**（同名 → 旧译文被顶掉） | 同 §7.4 步骤 5 |
| 6 | **换版（同一事务）** | ① 旧 `TRANSLATED(lang)` → `SUPERSEDED` + 清空文件列<br>② 新行 → `READY` **+ 写 `segments_url` / `subtitles_url` / `terms_url`** | **不动** | 同 §7.4 步骤 6 |
| 7 | **收尾** | `task_run.status = SUCCEEDED` + `subtitle_task.updated_at = now()` | **不动** | —— |

**跟 §7.4 的两个差别**：**没有级联**（译文下面没有别的产物，步骤 6 只有两行）；**没有"删文件"这一步**（旧译文是**覆盖型**，同名被新译文顶掉，停在 `SUPERSEDED` 就到头）。一次一个语种、一条 run、一条流（现状 C2 不变）。

**步骤 6 那句"同一事务"留着，但理由要升级**（2026-10-17 第六轮）。评审当初说的是"**顺手**"（因为 W4 被判定"非必需原子"），其实它**更优**：

- **无事务**：崩在两步之间 → 旧行 `SUPERSEDED` + 文件列已清空、新行 `CREATING` → **零 `READY`**，用户看得见"译文没了"，重翻收敛；
- **有事务**：崩 → 回滚 → 旧行**仍是 `READY`** → 下次翻译命中复用判据、**跳过**。盘上虽然已被新译文替换（C51：写文件在事务前），但新译文是基于**当前** `CLEANED` 翻的，内容比旧版更对 —— 交付新内容是**对的**，只是 `revision` 号没跟上。

两种都收敛，但"有事务"那条**不制造可见的空窗**。所以留着。

**第 3 步判据为什么只认 `status`：**

- **"译文过时"不在这里比，是级联在写入时就标掉了**（§7.4 步骤 6）：新 `CLEANED` 落定那一刻，所有下游译文在**同一事务**里标 `SUPERSEDED`。所以 `READY` 就等于"基于当前版清洗"。
- 判据里**不带** `derived_from_artifact_id`：那个字段只表达"派生自哪一版"，不表达"是不是当前版"（列注释同此口径）。拿它判当前性既是死条件（丙 之下永远为真），也会让人误以为读路径依赖 lineage。
- **级联也不带它**（2026-10-17 第六轮查出）：`review-findings.md` §0.1 说 `derived_from_artifact_id` 的写入侧用途是"给级联用（`WHERE source_artifact_id = 旧 CLEANED.id`，**没有它级联找不到要作废的行**）"—— **这句在 丙 之下不成立**。因为 丙 的定义就是"`READY` ⟺ 基于当前 `CLEANED`"，所以级联只需要
  ```sql
  WHERE task_id = ? AND artifact_type = 'TRANSLATED' AND status = 'READY'
  ```
  **更简单也更健壮**（不依赖 `derived_from` 被正确维护）。同理，找"旧 `CLEANED`"也是 `status='READY'` —— **不能**用 lineage，因为新 `CLEANED` 行的 `derived_from` 指向的是 `ORIGINAL`，不是旧 `CLEANED`。
  （**所以 `derived_from_artifact_id` 的用途收敛为纯审计 / 溯源**，见 §4.4 的列注释。）

### 7.6 启动收口（进程启动时，早于接受请求）

```sql
UPDATE task_run         SET status='INTERRUPTED', finished_at=now() WHERE status = 'RUNNING';
UPDATE subtitle_artifact SET status='FAILED'                          WHERE status = 'CREATING';
UPDATE subtitle_task     SET updated_at=now()  WHERE id IN (被上面第一条收掉的那些 task_id);
```

**三条，不是两条**（2026-10-17 第六轮补）：第三条是 C45 要求的 —— `subtitle_task.updated_at` 的写入点是"**同一个收口点**（每次 run 收尾 UPDATE 一次）"，而启动收口**也是一次 run 收尾**，原文漏了。它不违反恢复路径定义（`updated_at` 是 DB 状态列）。

**`intermediate_file` 表在启动收口里无动作**（C62）—— 它的三个值没有一个是"进程活着才有意义"的活跃态；`SUPERSEDED` 只是个"待办"标记，重启后依然成立，留给 C26 对账。启动收口**也不能**去补删 `SUPERSEDED` 的文件 —— D-6 的【恢复路径】定义写着"**只写 DB 状态列**，不写文件"，否则"恢复"会变成"隐蔽重试"。

**收掉了几行要打日志** —— 那是"上个进程有 N 个任务没跑完"的唯一痕迹。

**为什么可靠**：单实例 + 跑的线程与进程同生共死 → 启动时看到 `RUNNING`，只可能是上个进程遗留的。

**分工（C43 之后）**：收口与启动收口各管一格，合起来覆盖四种情况 ——

| 情况 | 谁兜 | 结果 |
|---|---|---|
| 执行体退出（正常 / 异常 / `Error` / 早退） | **C43 的无条件收口** | 没到终态 → `FAILED` |
| 真有人在跑 | `uk_active_run` 撞键 | 409，响应体带既有 run 的 taskNo 供订阅 |
| 进程整体没了（被 kill / 掉电） | **本节 C15** | run → `INTERRUPTED`，残留 artifact → `FAILED` |
| 线程卡死（挂住不动） | **没人兜**（C46 已知缺口） | 那条 RUNNING 行永久占锁，只有重启能解 |

**注意 `INTERRUPTED` 不是崩溃时写的** —— 崩溃时什么代码都跑不了，是下次启动由新进程补记的。

### 7.7 抢互斥（C16 已作废，见 C43）

插 `RUNNING` run 撞唯一键 → **一律 409**（响应体带既有 run 的 taskNo 供订阅）。**没有"先清掉再抢"这个分支** —— 那一段随 C16 一起去掉。

语义为什么变干净了：C43 的收口保证"收不了口的行"会被收掉，C15 保证跨进程残留不会有 —— 于是**撞键 = 真有人在跑**，中间那个"可能撞到残骸"的状态不存在了。唯一索引说有人占着，就是有人占着。

### 7.8 产物读取与自愈（C25）

```
查 artifact WHERE task_id=? AND status='READY'
  → 逐个 Files.exists
     不存在 → 当没有 + WARN + 重新生成（不改状态）
```

业务查询（能否翻译、译文是否可复用）一律只认 `status='READY'`。

---

## 8. 与现状的对照（改造清单）

| | 现状 | 新模型 |
|---|---|---|
| 表 | `task` + `subtitle_keyword` | `workspace` + `subtitle_task` + `task_run` + `subtitle_artifact` + `intermediate_file` + `subtitle_keyword` |
| 边界 | `VideoTask`（唯一个，扛三种生命周期） | **不按类划** —— 按 §3.7.3 写集表，边界 = `{W1, W5, W5′}`（**C40 已作废**） |
| 状态机 | `VideoTask.start/succeed/fail` | `TaskRun`（+ 新增 `INTERRUPTED`） |
| 在途状态 | 内存 `TaskSessionRegistry` | DB（`task_run` + `uk_active_run`） |
| 互斥 | 两套（DB `rejectIfActive` + 内存 `tryStart`） | 一套（`uk_active_run`） |
| 失败原因 | 共用 `error_message` + 中文前缀 | `task_run.error_message`，`run_type` 说明来源 |
| 产物 | 文件系统（文件即事实） | `subtitle_artifact`（DB 权威，文件是载体） |
| 中间产物 | 文件系统、**零登记**，复用判据 = "文件在不在" | `intermediate_file` **登记**（C62），复用判据 = "表里 `READY` 且文件在" |
| 关键词 | 值对象 + 独立仓储，挂 `workspace_key` | 不变，挂 `task_id` |
| 内容指纹 | 无 | `duration` + `timestamp` |
| 身份键 | URL 原文哈希（零规范化） | `bvid|p`（B 站）/ sha256（上传） |
| 启动收口 | **无** | **三条** UPDATE（run → `INTERRUPTED` / artifact → `FAILED` / task → `updated_at`） |
| 对外契约 | 扁平 `TaskResponse`（状态 / 阶段 / 错误 / 时间戳全压在一行派生） | 身份 + `lastRun`（单值）+ 独立 `/artifacts` 清单（C53–C58） |
| SSE 流边界 | 隐式（`terminal` 靠 `stage.state` 编码在途会话） | 一条 run 一条流，`uk_active_run` 直接判（C57） |

**要退场的东西**：`TaskSessionRegistry`、`error_message` 的三个中文前缀常量与两个"按前缀认领"的清除方法、`latest_*` 指针类字段。
（`TranslationStatus` 与 `translation_status` 列**已经**在 10-11 任务里删掉了。）

### 8.1 `VideoTask` 拆解对照（2026-10-15 第二轮补）

原 `VideoTask` = **13 字段 + 13 方法**，三合一压在一行（身份 + 状态机 + 输入描述）。拆解后：

| 现字段 | 去向 |
|---|---|
| `id` | 三张表各有各的 id —— 一个变三个 |
| `taskNo` | → `subtitle_task.task_no` |
| `inputType` | → `workspace.source_type` |
| `sourceUri` | → `workspace.source_uri`（脱敏红线原样保留） |
| `workspaceKey` | **消失**，被 `workspace.source_key` 取代；`WorkspaceKey` 类型退场 |
| `workspaceDir` | **⚠ 没有落点**，见 §9.4j |
| `status` | → `task_run.status`（+ `INTERRUPTED`） |
| `currentStage` | → `task_run.current_stage` |
| `errorMessage` | → `task_run.error_message`（去前缀） |
| `createdAt` | → `subtitle_task.created_at`（`workspace` / `task_run` 各有各的） |
| `updatedAt` | → `subtitle_task.updated_at` |
| `startedAt` | → `task_run.started_at` |
| `finishedAt` | → `task_run.finished_at` |

| 现方法 | 去向 |
|---|---|
| `start` / `succeed` / `fail` / `updateStage` | 整台搬到 `TaskRun`，守卫（`requireStatus`）跟着走 |
| `newPending` / `restore` | 拆成三个工厂（`Workspace` / `SubtitleTask` / `TaskRun` 各一） |
| `newTaskNo` | 跟 `subtitle_task` 走 |
| `recordRegenerateFailure` / `clearRegenerateFailure` | **删** —— RECLEAN 有自己的 run 行 |
| `recordTranslationFailure` / `clearTranslationFailure` | **删** —— TRANSLATE 有自己的 run 行 |
| `requireMainSucceededForPostLifecycle` | **删** —— 前置条件从"查主状态"变成"查有没有 READY 的 ORIGINAL / CLEANED" |
| 三个 `*_FAILURE_PREFIX` 常量 | **删** —— `run_type` 说明来源（C3） |
| `ACTIVE_STATUSES` | **删** —— 服务 `rejectIfActive`；新模型互斥 = 撞 `uk_active_run`（C14） |
| `isEventTerminal(mainStatus)` | **要重新落位** —— "终态"现在在 run 级、且多了 `INTERRUPTED`；被 web 收口引用，不能照搬 |

周边跟着动：`TaskStatus` 改名 + 加 `INTERRUPTED`；`TaskInputType` 挪去 `workspace` 侧；`TaskNo` 不动、只换锚点；`VideoTaskRepository` 拆成 4 个 port（`Workspace` / `SubtitleTask` / `TaskRun` / `SubtitleArtifact`），`SubtitleKeywordRepository` 只换锚点；`VideoTaskService` 那两道"主状态必须 SUCCEEDED"校验变成查 artifact。

**注意**：`subtitle_artifact` **不是从 `VideoTask` 分出来的，是新长出来的** —— 现状产物是"文件系统即事实"（`scanArtifacts` 扫目录 + 白名单过滤），DB 里零记录（见 §11.4）。

---

## 9. 还没定的（接着讨论从这里开始）

### 9.1 甲 · 领域模型 —— **已结**（2026-10-17 第六轮，本节不再有未定项）

定案见 **C36 / C37 / C41 / C42** + **§3.7.3 写集表**；C35、**C40 均已作废**。论证过程在 §3.7.1，拆解对照在 §8.1。

**原来的残留问题"`Workspace` 算不算聚合根"已消解** —— 随记法合拢在 (a)（不用类级聚合标签）而**不再问这个问题**。第二轮那个"**弱**聚合根"档次的矛盾，根源是 §3.7.1 的**双尺子**（见该节末尾的补注）。

### 9.2 丙 · 分期与切换 —— **已定**（2026-10-17 第五轮）

定案见 **C59 / C60**，正文在 **§3.10**：分阶段做完，不保旧 API，前端不参与分期。**阶段边界留到文档齐了再定** —— 那是 `implement.md` 的事。

### 9.3 乙 · 对外 API 契约 —— **已定**（2026-10-17 第五轮）

定案见 **C53–C58**，正文与四个 JSON 形状在 **§3.9**。原先列的五条小问的着落：

- 端点路径**不变**；
- 状态来源：`GET /api/tasks/{taskNo}` 给 `lastRun`（单值），**不再有扁平状态字段**；
- artifacts 清单：独立成 `GET /api/tasks/{taskNo}/artifacts`，按版本行给；
- run 历史端点：**不做**；
- SSE：一条 run 一条流，事件带 `runNo` / `runType` / `targetLanguage`；
- **`GET /api/tasks/{taskNo}/keywords`**（第六轮补确认）：形状**照搬现状** `{ keywords: [...], fingerprint: "..." }`，唯一变化是前端比对用的**第二个值**改从 `/artifacts` 里 CLEANED 行的 `keywords_fingerprint` 读（C33 已把它提到表上），不再从 `clean-segments.json` 的**文件内容**里读。

### 9.4 零碎但影响实现

| # | 待定 | 备注 |
|---|---|---|
| a | ~~`run_no` / `revision` 的递增~~ | **已定**（2026-10-17 第六轮）：`MAX+1` **够用**，不需要事务 —— 并发下会撞 `uk_task_run_no` / `uk_task_type_rev_lang` 或 `uk_active_run`，失败方拿 409，行为正确。理由见 §4.3 的补充 |
| b | 事务边界 | **已定**（2026-10-17 第六轮）。原文列了四类，**① 与 ② 该降级**（① 唯一键兜住、② 可重跑收敛），只剩两类：③「**换版 + N 行级联标记**」（§7.4 步骤 6，W5）；④「**指纹级联 + 指纹写入**」（§7.3 步骤 2，W5′）。**外加一条新查出的**：**W1「`workspace` + `subtitle_task` 同插」**（§7.1 步骤 3）。跨文件系统那半边的顺序已定（**C51**：写文件在事务前、删文件在事务后），剩下的是"用什么机制实现事务"（`@Transactional` / `TransactionTemplate`），属实现层 |
| c | ~~`workspace` 要不要落 `file_name` / `size_bytes`~~ | **已定**（2026-10-17 第七轮）：**两个都不落**。`file_name` **已有两处落点** —— 服务端源媒体名在 `intermediate_file` 的 `kind='SOURCE_MEDIA'`（§4.6）、上传时的用户原名在 `workspace.source_uri`（§4.1 列注释就是"上传文件原始名"），再加一列是**第三份**；`size_bytes` **不是身份、也不是指纹**（C28 已把 `filesize_approx` 排除在指纹外 —— "平台重压就会变"），对外要的 `sizeBytes` 是**读时 stat** 出来的（§3.9 的 `/artifacts`，现状 `scanArtifacts` 就是这么扫的） |
| d | ~~手工对账动作做不做~~ | **已定**（2026-10-17 第七轮）：走**乙** —— **只读对账**（四件事全报、② 只报不修），触发 = `POST /api/maintenance/reconcile`，**不加鉴权**。见 **C65** |
| e | ~~前端是否同批改~~ | **已定**（C60，2026-10-17 第五轮）：前端**不参与分期**，所有后端阶段做完后另开任务接接口 |
| f | 测试基线 | 现状记的是 569 个测试，重构后要重建。**此数待复核** —— 10-11 子任务 A 归档时记的基线是 570；动手前按 §10 的命令重量一次 |
| g | 并发闸门 `SemaphoreTaskConcurrencyGate` | **保留**（限流 ≠ 互斥），确认即可 |
| h | ~~命名~~ | **已定**（2026-10-17 第七轮，N1–N7）—— 见 9.4h。这是**动手前必须落**的一条 |
| i | 存量数据 | 本地可弃，`backend/data/works*` 直接重建 |
| j | ~~**`workspace_dir` 没有落点**~~ | **已定**（2026-10-17 第七轮）：走**乙2** —— 不落库、列删掉，目录名 = `sanitizeName(source_key)`。连带四列改名 `_url` → `_file_name` 与读路径公式，见 9.4j |

#### 9.4h 命名（2026-10-17 第七轮定案，N1–N7）

> **前提（D-7）**：问题不是"新类换个名"，而是"**旧类在新模型里还该不该存在**"。所以下面每条先给**被占名的旧类去向**，再给新名字。
> 第七轮原记的三条冲突（`SubtitleArtifact` 被占 / `*Runner` 含义 / `Workspace*` 并列）已被下面取代。

**N1｜产物版本实体 = `SubtitleArtifact`（旧 record 让名）**

| 旧类 | 去向 |
|---|---|
| `SubtitleArtifact`（record：`srtPath` + `segmentsPath` + 元数据，javadoc 自称「字幕阶段的产出描述」） | **改名 `SubtitleStageOutput`** —— 它是"这一步产出的文件"，新实体才是"字幕产物"。**改名只碰 2 个源文件**（`SubtitlePreparationCoordinator` + 它的测试），已 grep 核实 |
| `AudioArtifact`（record：`audioPath` + `spec` + `sizeBytes`，自称「音频抽取阶段的产物」） | **改名 `AudioStageOutput`** —— 同一形状、同一判据；不改就与上一条不对称。成本 11 文件 / 41 处（7 main + 4 test） |
| `SubtitleArtifactSpec`（enum：8 条文件名模板 + `downloadable`/`checkpoint` 两个 flag） | **文件名模板那半要留**（C24 文件名不变）；**`downloadable` 白名单那半被 C54 的 `/artifacts` 端点取代** |
| `TaskArtifact` / `TaskArtifactFile` / `TaskArtifactResponse` | 被 C54 / C55 取代 |

**N2｜`subtitle_task` 的类 = `SubtitleTask`**，落 `com.vidsage.domain.subtitle`

- **订正 C42**：它写的是「`Task` 保留**表和类**」，但 §4.2 的表名是 `subtitle_task` 且带 `uk_workspace` 唯一（C2：一个媒体一个任务）→ **C42 自己那条"未来若加配音，`Task` 已经在了"的理由已经失效**（配音只会是**新表** `dubbing_task`，不是复用）。
- §8.1 的 port 清单**早就在用 `SubtitleTask`**（`Workspace` / `SubtitleTask` / `TaskRun` / `SubtitleArtifact`）—— 不一致的是 C42 / §3.7.3 / §5 里那些 `Task` 指代，**要全篇扫一遍**。
- 包放 `domain/subtitle/`（放包根，对齐 `domain/task/VideoTask` 的先例）。

**N3｜中间产物 = `intermediate_file` / `IntermediateFile`；枚举 `IntermediateFileKind`**

- **不沿用 `temp_file`** —— 除了 C5 那条忌讳，本仓库 `temp` 已被占：`VideoTaskService` 的 `TEMP_FILE_PREFIX` / `TEMP_FILE_SUFFIX` 是**上传流式写盘的临时文件**（`Files.createTempFile`）。
- **不用 `workspace_file`** —— 会**指向错的实体**：这些文件挂 `task_id`，而新模型里 `workspace` 是**媒体身份**。
- **与 `subtitle_artifact` 的对立**：`artifact` = 版本化产物（对外、有版本、有 lineage）；`file` = 中间缓存（对内、无版本、`uk_file` 一行到底）。这正是 §4.6 第 1 点说的"本质不同"。
- **落地口径**：当前挂 `task_id`；若将来"一个媒体多个任务"落地、中间产物需要共享，**再评估**改 `workspace_id`。现在**不预留、不加列、不写兼容逻辑**。

**N4｜索引/约束命名规则**（写进 `schema.sql` 头部注释 + 收工时进 `.trellis/spec/backend/`）

```sql
CONSTRAINT pk_<表名>            PRIMARY KEY (id)
CONSTRAINT uk_<表名>_<列名...>  UNIQUE (...)
INDEX      idx_<表名>_<列名...> (...)
```

四条依据：① **带表名段**（现状已带；不带会出 `uk_task` / `uk_task_no` 这种分不清归属的名字）；② **前缀统一 `uk_`**（现状 `task` 用 `uq_`、`subtitle_keyword` 用 `uk_` —— 而 `uq_` 只出现在要删的 `task` 表上，**收口成本 0**）；③ **主键具名**（与现状 `CONSTRAINT pk_task` 对齐）；④ **唯一约束用 `CONSTRAINT ... UNIQUE`**，不用 `UNIQUE KEY` / `UNIQUE INDEX`（与主键同款写法）。

- **不开宽松档**（不给 `active_flag` 那两条起语义短名）—— 机械规则的价值就是**不用判断**。
- **预防条款**：若某索引名超 64 字符，在**列名边界**截断（不留悬空 `_`）。当前最长 **59**，未触发。
- **长度实测（20 个名字，最长 59）**：

| 名字 | 长度 |
|---|---|
| `pk_workspace` | 12 |
| `uk_workspace_source_type_source_key` | 35 |
| `pk_subtitle_task` | 16 |
| `uk_subtitle_task_task_no` | 24 |
| `uk_subtitle_task_workspace_id` | 29 |
| `pk_task_run` | 11 |
| `uk_task_run_task_id_run_no` | 26 |
| `uk_task_run_task_id_active_flag` | 31 |
| `idx_task_run_task_id_run_type_status` | 36 |
| `pk_subtitle_artifact` | 20 |
| `uk_subtitle_artifact_task_id_artifact_type_revision_lang` | 56 |
| `uk_subtitle_artifact_task_id_artifact_type_lang_active_flag` | **59**（最长） |
| `idx_subtitle_artifact_task_id_artifact_type` | 43 |
| `idx_subtitle_artifact_derived_from_run_id` | 41 |
| `idx_subtitle_artifact_derived_from_artifact_id` | 46 |
| `pk_subtitle_keyword` | 19 |
| `uk_subtitle_keyword_task_id` | 27 |
| `pk_intermediate_file` | 20 |
| `uk_intermediate_file_task_id_file_name` | 38 |
| `idx_intermediate_file_task_id_status` | 36 |
- **DDL 范围（本次）**：
  - **建**：5 个 `pk_`（workspace / subtitle_task / task_run / subtitle_artifact / intermediate_file）+ 9 个 `uk_` + **1 个 `idx_`**（`idx_subtitle_artifact_derived_from_run_id` —— 消费者 = **C61 的收口**，每次 run 都跑，是热路径）
  - **改名**：`pk_subtitle_keyword`（名不变，已存在）+ `uk_subtitle_keyword_workspace_key` → `uk_subtitle_keyword_task_id`
  - **不建**：`idx_subtitle_artifact_task_id_artifact_type` —— 被 `uk_..._revision_lang` 的**最左前缀**覆盖，是**冗余**
  - **暂缓 3 条**，触发条件写成「**出现按 <列> 过滤的查询时**」——**不是**表行数阈值（没有查询就不会有慢查询，尺寸阈值是空触发条件）：`idx_task_run_task_id_run_type_status`、`idx_intermediate_file_task_id_status`、`idx_subtitle_artifact_derived_from_artifact_id`

**N5｜`TaskStatus` → `TaskRunStatus`**

- **值不动**（§6 已定：删 `PENDING`、加 `INTERRUPTED`）；与 `task_run` ↔ `TaskRun` 对齐，落在仓库 `<实体><侧面>` 模式上（`TaskStatus` / `TaskInputType`）。
- **假朋友**：`DashScopeAsrProvider` 那 4 处是 **`com.alibaba.dashscope.common.TaskStatus`**（阿里 SDK 的同名枚举，值也叫 `SUCCEEDED`）→ **显式排除，改名走 IDE rename symbol，不做文本替换**。
- **`TaskStageEvent` 随文件删除，不参与改名** —— 它是**纯函数政策类**（`final class` + 私有构造 + `static from(VideoTask, String runningStage)`）：入参 `VideoTask` 要删、`switch` 里有 `case PENDING` 要删值；而 `runningStage` 这个参数存在的**唯一理由**就是 §3.9 点名的那处隐式编码（"藏在 stage 状态位里"把后置会话算进流），新模型 `uk_active_run` 让它整块消失。
- **连带**：它是**被取代**不是"删了没事" —— 被取代的是「**连接建立即推 DB 快照**」（C57 的兜底）。**替代者要在 `design.md` 落位**。
- **改名范围要等 `design.md`** —— 165 处 / 17 文件里大部分落在本次要**删/改**的文件上，但"哪些删、哪些改"现在定不了。`implement.md` 落成顺序要求：**先落定删除集 → 删 → 再对存活集改名**。
- **包位置留到拆包一起定**，暂留 `domain/task/model/`（理由：`TaskRun` 自己落哪个包还没定，"跟着 `TaskRun` 走"这个前提不成立；接受动两次）。

**N6｜枚举列一律 `VARCHAR(32)`，不用 `ENUM`、也不用 `CHECK`**

- **与 C6 同一口径**：C6 选逻辑外键的理由是「`spring.sql.init.mode: always` 下**手改表已经靠手工**，约束会让改表更难受」—— ENUM 是**同一类 DB 层约束、同一个代价**。
- **加值零 DDL**：本次就要加 `INTERRUPTED`；ENUM 下每次加值都要手工 `ALTER TABLE`（`CREATE TABLE IF NOT EXISTS` 不改已存在的表 —— 文件头部 `translation_status` 那条说明就是同一个坑）。
- **切换成本 0**：现存 ENUM 列**只在 `task` 表**（本次删表），唯一存活的现状表 `subtitle_keyword` **一个枚举列都没有** → 切完整个 schema 口径统一，不留历史包袱。
- **宽度 32 不是 16**：现有最长值 `SOURCE_MEDIA` = 12。取 32 换"加值不用先改列宽"。**成本是 0**（VARCHAR 按实际长度存，加宽只改最大行长上限，存的每一行一个字节都不多）—— 别拿"多 16 字节"当成本。
- **不要用 `CHECK` 把约束搬回 DB**：MySQL 8.0.16+ 的 CHECK 是 DB 层约束，加值一样要 `ALTER TABLE`（`DROP CHECK` + `ADD CHECK`），"零 DDL"的好处立刻没了。**选了 VARCHAR 就别架空它。**
- **两个生成列 `active_flag` 的宽度跟 `status` 一致**（都 `VARCHAR(32)`）—— 功能无影响，但列宽不一致是噪音。
- **列宽即值长上限**，要在 `schema.sql` 注释里写明，让加值的人知道边界在哪。
- **适用范围**：`source_type` / `run_type` / `task_run.status` / `artifact_type` / `subtitle_artifact.status` / `kind` / `intermediate_file.status`（+ 两个 `active_flag`）。**`lang VARCHAR(16)` 不用动** —— `SupportedLanguage` 白名单里最长的 code 是 `zh-Hans` / `zh-Hant` = 7 字符，2 倍余量。

> **留档：将来若有人想改用 ENUM，先读这几条**
> 1. `ORDER BY` 按**定义顺序**排、不按字母 —— 容易出意外结果；
> 2. `ALTER TABLE ... MODIFY` 只有把新值**追加到末尾**才是 INSTANT；**插到中间要 COPY 锁表**（根因：ENUM 存的是**序号**，插中间会让已有行的语义移位）；
> 3. 加值要 DDL，与 C6「约束在应用层」的口径相抵。

**N7｜检测出的源语种不提列（撤回一条"缺口"）**

- 我一度把它列成"没有落点"的缺口（类比 §9.4j 的 `workspace_dir`）。**查实后撤回**：它一直落在**检查点 JSON 文件内部**（`SubtitleTrack.language` —— C33 已确认 `SubtitleTrack` 就是那个 JSON 的形状），新模型的 `segments_file_name` 指着同一个文件，**一个字没丢**。
- **不提列**：与 `keywordsFingerprint` 的关键区别是**它是不是判据** —— 后者是（`CheckpointPolicy.isCurrent` 拿它比，所以 C33 提列），检测语种**不是**（无任何按它查询的地方，前端也不消费 —— 全仓 `web/dto` 无此字段）。按 D-3 也不该给它一列。
- 若将来前端要显示"识别语种"，那是**新增读路径**（要开 JSON），届时再评估。

**顺带订正**

- §9.4j 那句「新模型**四张表**（`workspace` / `subtitle_task` / `task_run` / `subtitle_artifact`）没有任何一列装它」→ **六张表**（C62 加了 `intermediate_file`，且原句从没算上 `subtitle_keyword`）。
- `subtitle_keyword` 本次**既改列名又改类型**（`workspace_key CHAR(12)` → `task_id BIGINT`），而 `CREATE TABLE IF NOT EXISTS` 不改已存在的表、MySQL 8 也没有 `DROP COLUMN IF EXISTS` → **旧库必须手工 `DROP TABLE subtitle_keyword` 一次**（§9.4i：存量可弃）。写进新 `schema.sql` 头部注释。

**不在这轮定（留 `design.md`）**

- **任务域拆包**：N2 之后 `domain/task/` 只剩 `TaskNo` / `TaskRunStatus` / `TaskStageEvent`，而 `SubtitleTask` / `TaskRun` 在 `domain/subtitle/` —— 概念被拆到两个包。倾向**整体并入 `domain/subtitle/`、删 `domain/task/`**，但 `TaskInputType` 去哪（§8.1 说"挪去 `workspace` 侧"，而 `domain/workspace/` 不存在）、`TaskStageEvent` 死活、§8.1 那 4 个 port 落哪个 `port/` 包 —— **是同一个决定**，一起在 `design.md` 定。

#### 9.4j `workspace_dir` 落点 —— **已定**（2026-10-17 第七轮，见下）

现状它是**落库字段**，被三处用：

- `SubtitlePipeline.toMediaInput` —— 重建管线输入
- `VideoTaskService.openArtifact` —— 下载定位
- `VideoTaskService.scanArtifacts` —— 列产物

而新模型**六张表**（`workspace` / `subtitle_task` / `task_run` / `subtitle_artifact` / `subtitle_keyword` / `intermediate_file`）**没有任何一列装它**。
（2026-10-17 订正：原文写"四张表"，漏了 C62 新增的 `intermediate_file`，且从没算上 `subtitle_keyword`。）

更麻烦的是：目录名现在的公式是 `WorkspaceDir.of(sourceUri, workspaceKey)`，**依赖 `workspaceKey`，而 `workspaceKey` 正要退场**（C8 换成 `source_key`）。所以这条公式本来也得重写。

**定案（2026-10-17 第七轮）：走乙2 —— 不落库，`workspace_dir` 列删掉。**

- **目录名 = `sanitizeName(source_key)`**，是 `source_key` 的**纯函数** —— 有 `source_key` 就能算出目录名，DB 再存一份是**镜像**，镜像就有"DB 与磁盘不一致"的风险。**与 C5 砍 `latest_*` 是同一判据。**
- **甲否掉**：把磁盘事实抄进 DB；且 `VARCHAR(512)` 对 93 字符的实际上限（`MAX_NAME_LENGTH` 80 + `-` + key 12）严重超配。
- **乙1（保留 label）否掉**：URL 路线保留的是那个**丑标签**（`https___www.bilibili.com_video_...`），而 `sanitizeName` 把 label 截断到 80 字符后**标签本身已失去可读性**。（**订正**：截断**不会**造成目录名碰撞 —— `-<key>` 后缀保证目录名唯一；它只是让标签没用。）上传路线丢掉的"用户原名"是**展示信息**，`workspace.source_uri` 已经存着，不靠目录名承载。

**乙2 的硬前提（必须写进 javadoc）：`sanitizeName` 在 `source_key` 的值域上单射。** 两条依据：

1. `|` 会被换成 `_`，而 B 站 `bvid` 的字符集是 `[0-9A-Za-z]`（**不含 `_`**）、`p` 是纯数字 → `bvid|p` → `bvid_p` 唯一；
2. 上传路线是 64 位 sha256 十六进制，原样唯一。

**且长度都 ≤ 80**（URL 约 14、sha256 = 64）→ **不会被截断**。这一条必须一起写死：`sanitizeName` 的 80 字符截断**会破坏单射**，将来若加更长的 `source_key` 格式，得先解决它。

> ⚠️ **新查出的坑（会让目录建不出来）**：`source_key` 的格式是 `bvid|p`，**含 `|`**，而 `|` 在 `WorkspaceDir.ILLEGAL_CHARS` 里（Windows 禁用）。`WorkspaceDir.of(label, key)` 只对 **label** 走 `sanitizeName`、**key 原样拼**，所以换成 `source_key` 后必须**对 key 也走 `sanitizeName`** —— 过了之后正好得到本节示例里那个 `BV11QMd6WEJp_p1`。今天没炸，是因为 `WorkspaceKey` 是 12 位十六进制哈希。

**连带：§4.4 那四列改名 + 收窄**（2026-10-17 第七轮定案）

| 旧列名 | 新列名 | 类型 |
|---|---|---|
| `segments_url` | `segments_file_name` | `VARCHAR(255)` |
| `subtitles_url` | `subtitles_file_name` | `VARCHAR(255)` |
| `context_url` | `context_file_name` | `VARCHAR(255)` |
| `terms_url` | `terms_file_name` | `VARCHAR(255)` |

- **存工作区内文件名**，不存路径 —— 仓库既有模式就是"DB 存相对路径、读时 `resolve(root)`"，且**对外只给文件名**（`TaskArtifact` 刻意不带路径，带路径的 `TaskArtifactFile` 明确"不是对外契约"）。存绝对路径还有"根目录一挪、全表失效"的问题。
- **叫 `_file_name` 不是 `_file`** —— 明确"存的是**文件名**，不是路径、不是 URL"。§3.9 的 `/artifacts` 响应里那个 `"url"` 是**接口拼出来的**，不是这列的值。
- **为什么 `subtitle_artifact` 带用途前缀、`intermediate_file` 不带**：取决于"**一行几个文件**" —— `subtitle_artifact` 一行四个文件，要前缀区分；`intermediate_file` 一行一个，`file_name` 就够。语义一致。

> **耦合点（必须写进文档，否则实现时会踩空）**：`workspace_dir` 列不存在了，所以这四列的**读路径公式**是
> ```
> root / sanitizeName(workspace.source_key) / <用途>_file_name
> ```
> **不是** `root / workspace_dir / file_name`。

**落地清单**

1. `sanitizeName` 的单射前提写进 `WorkspaceDir` javadoc（含"**长度 ≤ 80、不被截断**"这条）；
2. 读路径公式写进文档（上面那条）；
3. `WorkspaceDirectoryManager.ensureWorkspaceDirectory(label, key)` 的**签名要跟着改**（`WorkspaceKey` 退场、`label` 消失）；
4. 四列改名要 **grep 全仓**（代码 / 注释 / 测试 / 文档一起改）；
5. 写入前确认文件名已 sanitize —— `SubtitleArtifactSpec` 的模板是**应用生成**的，`{lang}` 取自白名单，安全；
6. 四个用途别漏：`segments` / `subtitles` / `context` / `terms`。

---

## 10. 环境备忘

```bash
# 判编译：一律 clean（增量编译会给假绿）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 全量测试：量前先删 surefire-reports（会留陈旧报告）
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test

# 起服务：测试红着时必须跳过测试编译（spring-boot:run 绑 test-compile；-DskipTests 不够）
#   另：application-local.yml 在 .gitignore 里，worktree 新检出会缺它 → 启动失败
# 收工：按 PID 杀子 JVM（TaskStop 只杀 mvn 包装进程，子 JVM 继续占端口）

# 推送：直连 GitHub 必失败，走本机代理
https_proxy=http://127.0.0.1:7897 git push
```

> 退出码会被管道吃掉：`mvn ... | tail -60` 回显的是 `tail` 的退出码。判绿一律看 `[ERROR]` 段与 `BUILD SUCCESS/FAILURE`。

---

## 11. 本次讨论里实测过的事实

留档，免得后面重新查：

1. **`source.info.json` 里有**：`id`（= bvid）、`duration`、`upload_date`、`timestamp`、`title`、`uploader`、`webpage_url`、`filesize_approx`、`format_id`、`formats[]` 等，顶层 45 个键
2. **`source.info.json` 里没有 `cid`** —— 顶层没有，`formats[]` 里也没有。要 `cid` 必须调 B 站 API
3. **`data/works-before/` 里同一个 BV 号有 2 个目录** —— 三个视频各重复一次，差异只在跟踪参数
4. **产物清单是扫目录 + 白名单过滤得来的**（`VideoTaskService` 里 `.filter(SubtitleArtifactSpec::isDownloadable)`），不是查 DB；可下载的是 6 条模板，`audio-*.m4a` 与 `source.info.json` 都**不在**清单里（前端下不到）
5. **全仓没有任何启动收口** —— grep `CommandLineRunner` / `ApplicationRunner` / `@PostConstruct` 为空
6. **`SourceUri.ofRemoteUrl` 是原文返回**（注释里写着"URL 原文即规范化串"），而 `MediaInput.RemoteUrl` 连它都没走，直接 `WorkspaceKey.ofHash(原文)`
7. **全仓 `@Transactional` 数量为 0**，只有 2 个 repository port（`VideoTaskRepository` / `SubtitleKeywordRepository`）—— 现状**没有工作单元机制**，聚合改造要新建
8. **`VideoTask` 是 13 字段 + 13 方法的薄聚合**：状态机 4 个方法、两处寄生的失败记录（`record*Failure` / `clear*Failure` 各 2）、三个中文前缀常量、`ACTIVE_STATUSES` / `isEventTerminal` 两个静态谓词
9. **持久化栈 = MyBatis-Plus，不是 JPA/Hibernate**（`mybatis-plus-spring-boot4-starter` 3.5.17）；全仓 `@Transactional` / `TransactionTemplate` / 平台事务管理器 = **0**（第七轮实测，§11.7 那条仍成立）；repository 是**裸 `@Repository` 类包 mapper**，无任何事务注解。
   → **这是 C63 那条落地约束的依据**：MyBatis 的 `SqlSession` **不像 Hibernate 的 Session 会因语句失败而失效**，`DataSourceTransactionManager` 也不会因一条 SQL 撞唯一键就把事务标脏；**rollback-only 只发生在 Spring 事务代理边界**。所以"在同一个 `@Transactional` 方法里 catch 住重复键继续用这个事务"在 MyBatis 下**可行** —— 前提是不隔代理边界。
10. **改名成本账**（2026-10-17 第七轮实测）：`TaskStatus` **165 处 / 17 文件**（`DashScopeAsrProvider` 另有 4 处是**阿里 SDK 的同名枚举** `com.alibaba.dashscope.common.TaskStatus`，**不计入**）；`AudioArtifact` **11 文件 / 41 处**（7 main + 4 test）；`SubtitleArtifact` 4 文件 / 12 处（其中**只有 2 个真实使用点**）。
