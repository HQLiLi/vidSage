# S7 设计：只读对账（`POST /api/maintenance/reconcile`）

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威**（尤其 §7.9 手工对账、§8.4 残留窗口、§5 写集表）。
> 本文件只写**本段的技术形状**：对外契约、四类判据的精确口径、数据怎么读、落位与边界。
> 范围与验收见 [prd.md](./prd.md)；执行顺序见 [implement.md](./implement.md)。
> **本文件是执行参考稿，不是契约**：发现与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。

---

## 0. 与现状代码的校准

开工前按「实际代码」而不是「文档措辞」核对的地方（2026-10-20 逐项对照 `backend/src/main`）：

| # | 文档的说法 | 实际代码 | 对本段的影响 |
|---|---|---|---|
| 1 | 父 design §7.9 ②：「表 `SUPERSEDED` 但文件还在」 | [SubtitleArtifact.java:217-223](../../../backend/src/main/java/com/vidsage/domain/subtitle/SubtitleArtifact.java#L217-L223) 的 `confirmDeletion` **只改状态、不清空文件列** | **缺口**：`DELETED` 行登记的文件若残留，四类里没人报。本段把 ② 扩到 `DELETED`（D2），并回写父 design |
| 2 | handoff C26：后缀白名单「现状已有一份局部版，要提到全局、补 `.tmp`、拆掉 `.info.json`」 | [YtDlpMediaAcquirer.java:66](../../../backend/src/main/java/com/vidsage/infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java#L66) 确认；但 [同文件 L393-403](../../../backend/src/main/java/com/vidsage/infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java#L393-L403) 的 `scanProducts` **靠这份白名单排除 `source.info.json`** | 直接拆会**弄坏兜底扫描**（候选变 2 个 → 抛异常）。`scanProducts` 保留**本地**排除（D5） |
| 3 | 两类「作废」的语义差异 | `markSupersededAndClearFiles`（换版旧行）**清空**文件列；`markSupersededByTaskIdAndType`（级联译文）**保留**文件列 | 决定 ② 的靶子：换版的旧 `ORIGINAL`/`CLEANED` **天然不报**（列已空），报的是级联译文与 `DELETED` 残留 |
| 4 | 父 design §7.9 表格：「报告行带 `task_no` + `file_name` + **问题类别**」 | —— | 解读为「**类别由所属数组表达**」，不再冗余一个 `category` 字段（D4） |
| 5 | S6 落的四列 `*_sha256`「本次加检测」 | [schema.sql:117-120](../../../backend/src/main/resources/schema.sql#L117-L120) 四列已在、随换版同写 | 本段补上**读取方**（④）；不新增列 |
| 6 | 父 design §7.9 ① 的注：「C25 的自愈会在读路径重新生成，下次自己就没了」 | [SubtitleArtifactStore.java:314-320](../../../backend/src/main/java/com/vidsage/application/subtitle/SubtitleArtifactStore.java#L314-L320) `isIntact` 文件不在 → 按没有处理 → 调用方重新生成 | ① 是**唯一会自愈**的一类，报告里不必标注「待人工」；其余三类都会重复出现 |
| 7 | 三个仓储端口 | 全是「按 `taskId` 查」，**没有全表扫** | 对账需要跨任务全量读，新增专用端口（§3） |
| 8 | 工作区根 | [WorkspaceProperties](../../../backend/src/main/java/com/vidsage/config/WorkspaceProperties.java) `vidsage.workspace.root`，默认 `./data/works`；`WorkspaceDirectoryManager.getWorkspaceRoot()` 可取绝对路径 | ③ 的扫描根与判据里的目录名都从它来 |

**结论**：改动面 = 新增一个运维端点 + 一个对账服务 + 一个读取端口 + 一个后缀助手类；改 `YtDlpMediaAcquirer` 一处（换用共用白名单 + 本地排除）。**DDL 不动、领域模型不动、既有写路径不动。**

---

## 1. 对外契约

### 1.1 端点

```
POST /api/maintenance/reconcile
```

- **无请求体、无查询参数**。全量扫，不接受范围筛选（C65：「全量扫」）。
- **不加鉴权**（C65 定案；全站零鉴权是既有缺口，另记，不在本段）。
- 成功 → **200** + 报告体。只读、幂等，重复调用结果相同（除并发写导致的自然漂移）。
- 用 **POST 而非 GET**：父 design §7.9 定案就是 POST。它是「触发一次全量扫」的运维动作，
  不是可缓存、可预取的资源读取；不改这个定案。

### 1.2 响应体

```jsonc
{
  "checkedAt": "2026-10-20T15:30:00",     // 本次扫描时刻（服务器本地时间）
  "summary": { "dirs": 3, "files": 23 },  // 扫到的工作区目录数 / 普通文件总数
  "danglingFiles":  { "count": 1, "items": [ { "taskNo": "3f2a9c1e…", "fileName": "cleaned-segments.json" } ] },
  "reclaimPending": { "count": 0, "items": [] },
  "orphanFiles":    { "count": 2, "items": [ { "dirName": "BV11QMd6WEJp_p1", "fileName": "junk.srt" } ] },
  "hashMismatch":   { "count": 0, "items": [] }
}
```

| 字段 | 类 | 一行带什么 |
|---|---|---|
| `danglingFiles` | ① | `taskNo` + `fileName` |
| `reclaimPending` | ② | `taskNo` + `fileName` |
| `orphanFiles` | ③ | **`dirName`** + `fileName`（有意的例外：孤儿不在表里，没有 `taskNo` 可带） |
| `hashMismatch` | ④ | `taskNo` + `fileName` |

- **四类恒在**，空的一类给 `{"count": 0, "items": []}`，不是 `null`、也不是省略。
- **`count` 与 `items.length` 相等**（冗余是为了 curl 一眼看数）。
- **不返回「成功 / 失败」这种总状态**：父 design §7.9 明说「不能只回『成功』」。
- **不返回文件内容、不返回完整路径**（`fileName` 是工作区内文件名，不是绝对路径）。

> **可选的扩展，本次不加**：①②④ 的行只带 `taskNo` + `fileName`，人工要靠 `taskNo` 自己去查是哪一版。
> 带上 `artifactType` / `lang` 会更好定位，但那超出父 design 列的最少集，本次不扩。

### 1.3 出参的形状与落位

**照判据 2（新端点一律走它）**：读模型放 `application/maintenance/query/`（**零 web 注解**），
对外响应 `web/dto/ReconcileResponse`（带 `@Schema` + 静态工厂 `from(读模型)` 纯映射），
`MaintenanceController` 返回 `ReconcileResponse.from(service.reconcile())`。
响应内嵌的元素类型同理（`ReconcileIssueGroupResponse` / `ReconcileIssueResponse` / `ReconcileSummaryResponse`）。

> **订正（2026-10-20，项目所有者审查提出）**：本节初稿写的是「仿 `ToolDiagnosticReport` 先例，
> 出参带 `@Schema` 直接放 `application/maintenance/` 包根、不套 `web/dto` 映射」——**那是错的**。
> directory-structure.md 的判据 2 明说它「**对未来新增端点生效**」，且判据 1 的「直接对外」分支
> 「对**既有落位类型**（`ToolDiagnosticReport` / `TranscriptionResult`）**维持原状，不据此新造**」。
> `ReconcileReport` 是新端点的新类型，只能走判据 2。
> **分离也不是可选项**：`application` 不依赖 `web`（HTTP 类型只在 `web/`），服务不可能直接返回 `web/dto` 的类型。

---

## 2. 四类判据的精确口径

判据的输入有三份：**库里的登记**（两张表）+ **盘上的实际**（目录 → 文件名清单）+ **工作区根路径**。

### 2.0 三个公共前提

1. **目录名 = `WorkspaceDir.of(SourceKey.of(source_key)).value()`** —— 不落库，由 `workspace.source_key` 现算。
   全项目只有这一条公式（[WorkspaceDir.java:66-70](../../../backend/src/main/java/com/vidsage/domain/workspace/model/WorkspaceDir.java#L66-L70)）。
2. **一个工作区目录 ↔ 一个 `workspace` 行**（`uk(workspace.source_type, source_key)`）。
   一个 workspace 下**只有一行** `subtitle_task`（`uk_subtitle_task_workspace_id`）。
3. **已登记文件集合**（③ 的参照）—— 按目录名分组，成员是：
   - `subtitle_artifact` **任何状态**行的**四个文件列里非 `NULL`** 的值；
   - `intermediate_file` **任何状态**行的 `file_name`（这列是唯一键的一部分，永远有值）。

> 前提 3 的「任何状态」是 **D3** 的定案：只要表里还登记着，这个文件就有主，
> 残留归 ② 报，不算孤儿。否则同一个文件会在 ② 和 ③ 各报一次。

### 2.1 ① `danglingFiles` —— 表 `READY` 但文件不在

- **只查 `status = 'READY'` 的行**（`CREATING` / `FAILED` 行的文件列本来就全 `NULL`；
  `SUPERSEDED` 行换版时列已清空；`DELETED` 行走 ②）。
- 对它**每个 `belongsTo(type)` 且文件列非 `NULL`** 的文件位：算 `workDir.resolve(fileName)`，**不是普通文件**就报一行。
- **必需位与可选位同口径**：可选位没登记（列是 `NULL`）不算缺；**登记了却不在盘上就是异常**。
  这与 `isIntact` 完全一致（[SubtitleArtifactStore.java:301-321](../../../backend/src/main/java/com/vidsage/application/subtitle/SubtitleArtifactStore.java#L301-L321)）。
- **一个文件位一行**：一版产物的三个文件位都不在，就报三行（`fileName` 不同）。

> ① 是**唯一会自愈**的一类：读路径（C25）发现文件不在会重新生成，下次对账自己就没了。
> 报告里不额外标注这一点，靠父 design §7.9 那句注兜着。

### 2.2 ② `reclaimPending` —— 表说文件该没了，盘上还在

**靶子有两个来源**（都进同一个数组）：

| 来源 | 判据 |
|---|---|
| `subtitle_artifact`，`status IN ('SUPERSEDED','DELETED')` | 对它每个 `belongsTo(type)` 且**文件列非 `NULL`** 的位，盘上**存在**就报一行 |
| `intermediate_file`，`status IN ('SUPERSEDED','DELETED')` | 它的 `file_name` 盘上**存在**就报一行 |

- **为什么是这两个状态**（D2）：
  - `SUPERSEDED` —— 两阶段回收卡在中间（[SubtitleArtifactStore.java:151-157](../../../backend/src/main/java/com/vidsage/application/subtitle/SubtitleArtifactStore.java#L151-L157) 注释原话「删不动…那一行停在 `SUPERSEDED` **留给对账**」）。这是 ② 的主战场。
  - `DELETED` —— 代码保证「全删干净才标 `DELETED`」，所以这格理论上不该出现；但**手工放回文件、从备份还原**会让它出现，而「表说已回收、盘上还有」是货真价实的矛盾态。零成本兜底。
- **换版的旧 `ORIGINAL` / `CLEANED` 天然不报**：`markSupersededAndClearFiles` 已把它们的文件列清空
  （同名文件此刻归新行，[SubtitleArtifactRepository](../../../backend/src/main/java/com/vidsage/domain/subtitle/port/SubtitleArtifactRepository.java) 的注释写明）。
  所以 ② 实际报出来的是**级联译文**与 `DELETED` 残留。
- **不推导文件名**：只报**登记里写着**的文件。行没有登记（列是 `NULL`）就不报 —— 这正是 ② 与 ③ 的分工边界。
- **已经被当前有效版本占着的文件名不报**（2026-10-20 真库验收后补，**D11**）：
  文件名不带版本号、盘上每个 `(类型, 语言)` 只有一份（C24），所以**旧行登记的名字和新版是同一个**。
  只要该 `(目录名, 文件名)` 被一条 `READY` 行登记着，盘上那份就归它 —— 旧行喊「待回收」是**撞车**，
  照着报告去删会删掉当前版本。判据：`(dirName, fileName) ∉ READY 行登记的文件集合`。
  - 这条只对**产物表**需要：中间产物的 `file_name` 是唯一键的一部分，一个文件一个 task 只有一行，
    不存在「旧行与新版登记同一个名字」。
  - 它挡掉的是**违反写入纪律的行**（换版时该清空文件列却没清空）。那种行是「表的内部不一致」，
    不是「表 ↔ 盘不一致」，本就不该由对账报。**该报的照报**：两阶段回收卡住的译文行没有新版本会写同名文件。

### 2.3 ③ `orphanFiles` —— 盘上有、库里没人认领

- **扫描范围**：工作区根下**所有一层子目录**（含 `workspace` 表里**完全没有登记**的目录，D6）。
  只扫一层，不递归（工作区是扁平结构，见父 design §8.1）。
- 对每个子目录下的**每个普通文件**：若 `(目录名, fileName)` **不在已登记集合**里（前提 3），
  且 `fileName` **后缀不在临时白名单**（§4）→ 报一行，带 `dirName` + `fileName`。
- **不报子目录本身**（报告的是文件）。
- **不报白名单后缀**（`.tmp` / `.part` / `.ytdl`）—— 那些是产出过程中的过渡态，是正常的。
- **`summary.files` 计的是扫到的全部普通文件数**（含白名单后缀的那些）；`orphanFiles` 只是其中被判定为孤儿的部分。

### 2.4 ④ `hashMismatch` —— 表 `READY` 但文件哈希对不上（C64）

- **只查 `status = 'READY'` 的行**。
- 对它每个 `belongsTo(type)`、**文件列非 `NULL` 且哈希列也非 `NULL`** 的位：
  算 `ArtifactFileUtils.sha256(file)`，与登记值**不等**就报一行。
- **哈希列为 `NULL` 就跳过**（D7）：可选位没登记文件时，文件名与哈希**同时**为空（父 design §8.4 纪律 2「同生同灭」）。
  硬比会把「这一版本来就没这个文件位」误报成「哈希对不上」。
- **文件不在的位不在这里报** —— 那是 ① 的活。④ 只管「文件在、内容不对」。

### 2.5 四类的互斥关系

```
盘上文件
├── 在已登记集合里
│   ├── 登记行 READY ──── 文件不在 → ①   ／ 文件在但哈希不符 → ④   ／ 文件在且相符 → 正常
│   └── 登记行 SUPERSEDED/DELETED ── 文件在 → ②   ／ 文件不在 → 正常（回收完成）
└── 不在已登记集合里
    ├── 后缀在白名单 → 忽略
    └── 否则 → ③
```

**同一个文件不会同时进两个数组**（②③ 由「在不在已登记集合里」切开；①④ 由「文件在不在」切开）。

### 2.6 判据的输入汇总

| 输入 | 从哪来 | 谁提供 |
|---|---|---|
| 工作区根绝对路径 | `WorkspaceDirectoryManager.getWorkspaceRoot()` | 既有端口 |
| 全部产物登记行（带 `taskNo` / `dirName`） | 新端口 | §3 |
| 全部中间产物登记行（带 `taskNo` / `dirName`） | 新端口 | §3 |
| 盘上「目录名 → 文件名清单」 | 新端口 | §3 |

---

## 3. 对账怎么拿数据

### 3.1 为什么新开一个端口

对账要的是**跨任务全量读**，而现有三个仓储端口全是「按 `taskId` 查」——那是**领域行为的出口**
（「这个 task 当前有效产物是什么」），没有「把全表捞出来」这种领域语义。

三个候选：

| 方案 | 评价 |
|---|---|
| 甲：给三个领域端口各加一个 `findAll()` | **否**。为运维需求污染领域端口，将来每个领域端口都长出一个「给对账用」的方法 |
| **乙：新建 `application/maintenance/port/ReconcileReader`**（**选它**） | 对账的读取需求集中一处；领域端口不动。先例：`application/diagnostics/port/` |
| 丙：`application` 直接注入 MyBatis mapper | **否**。跨层依赖，且 `application` 不该认识 `infrastructure` 的 mapper |

> 与 `diagnostics` 的差别：那个端口的理由是「契约里没有一个领域词汇」；本端口**有**领域词汇（`taskNo` / `fileName`），
> 但它同样**不是领域行为**。端口放 `application` 的理由是「这是运维能力」，不是「没有领域词汇」。

### 3.2 端口形状

```java
public interface ReconcileReader {

    /** 全部产物登记行，带 taskNo 与目录名。任何状态都读。 */
    List<TaskScopedArtifact> readAllArtifacts();

    /** 全部中间产物登记行，带 taskNo 与目录名。任何状态都读。 */
    List<TaskScopedIntermediateFile> readAllIntermediateFiles();

    /** 工作区根下每个一层子目录名 → 该目录下的普通文件名清单（不含子目录、不递归）。 */
    Map<String, List<String>> listWorkspaceFilesByDirName();
}
```

- `TaskScopedArtifact` = `(String taskNo, String dirName, SubtitleArtifact artifact)`，`TaskScopedIntermediateFile` 同理。
  **复用领域对象**（`SubtitleArtifact` / `IntermediateFile`）而不是另造扁平 record：
  判据要读文件列与哈希列，领域对象已经有 `getFileName(kind)` / `getSha256(kind)` 这套访问器，另造一份等于把映射抄一遍。
- `listWorkspaceFilesByDirName()` **一次给全景**，不按目录反复回调（避免端口长出「列目录」这种太细的粒度）。

### 3.3 实现落位

`infrastructure/persistence/MybatisReconcileReader`：

- 四张表（`workspace` / `subtitle_task` / `subtitle_artifact` / `intermediate_file`）各一次 `selectList(null)` 全表读，**在内存里 join**（个人项目数据量小：几万行级别）。
  理由：不写新 SQL 就少一处维护；真要写 join 也只是省一次内存组装，收益不抵复杂度。
- 目录名由 `WorkspaceDir.of(SourceKey.of(sourceKey))` 现算，与写路径同一公式。
- 盘上清单用 `Files.list`（只一层，`isRegularFile` 过滤），根路径来自 `WorkspaceDirectoryManager.getWorkspaceRoot()`。
- **根目录不存在时返回空 map**，不抛异常（工作区还没建过是合法状态，报告该给「四类全 0 + `dirs: 0`」）。

> `MybatisReconcileReader` 是 `infrastructure → application` 的依赖（实现 application 端口），
> 与 `ToolDiagnostics` 的实现同款——全项目仅此两处。

---

## 4. 后缀白名单：落位与拆 `.info.json` 的连带修正

### 4.1 白名单提到全局

新建 `shared/fs/TransientFiles`（名字候选见 D8）：

```java
/** 产出过程中的过渡态文件后缀：临时写盘、原子替换的中途、下载器的进行中标记。 */
public final class TransientFiles {
    private static final Set<String> TRANSIENT_SUFFIXES = Set.of(".tmp", ".part", ".ytdl");

    /** 文件名后缀是否属于过渡态（扫盘时该忽略）。 */
    public static boolean isTransient(String fileName) { … }
}
```

- **补 `.tmp`**：`ArtifactFileUtils.writeAtomically` 会留下 `<name>.tmp`，现状白名单里没有它。
- **拆 `.info.json`**：它现在入 `intermediate_file` 表（`INFO_JSON` 类型），留着会让对账把**真孤儿漏报**
  ——一个残留的 `source.info.json` 会因为是「非产物后缀」被静默跳过，哪怕表里已经把它标成 `DELETED`。
- 落 `shared/fs/` 的理由：两个消费者分处 `infrastructure`（yt-dlp）与 `application`（对账），
  放 `shared` 两边都能用，不产生反向依赖。

### 4.2 `scanProducts` 必须保留本地排除（**这是修 bug，不是取舍**）

> **只涉及 yt-dlp（远程 URL）路线，与本地 / 上传路线无关。**
> 本地路线（[LocalFileMediaAcquirer](../../../backend/src/main/java/com/vidsage/infrastructure/provider/download/local/LocalFileMediaAcquirer.java)）压根不产 info json
> ——它的注释写明「本地路线没有 info json，故 `infoJsonPath()` 恒为 `Optional.empty()`」。
> 下面说的是 `YtDlpMediaAcquirer` 内部那个「产物定位失败时的兜底扫描」。

[YtDlpMediaAcquirer.java:393-403](../../../backend/src/main/java/com/vidsage/infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java#L393-L403) 的兜底扫描：

```java
.filter(path -> path.getFileName().toString().startsWith(PRODUCT_PREFIX))   // "source."
.filter(path -> !isNonProduct(path.getFileName().toString()))
```

`source.info.json` 也以 `source.` 开头，**现在完全靠 `isNonProduct` 把它挡掉**。
白名单里一拆 `.info.json`，它就成了候选 → 目录里同时有 `source.mp4` 和 `source.info.json` 时，
`candidates.size()` 变成 2 → 抛「应恰有 1 个 source.*，实际 2 个」，**兜底路径直接坏掉**。

修法：`scanProducts` 的排除条件改成**本地**的两件事——
`TransientFiles.isTransient(name)` **或** `name.endsWith(INFO_JSON_NAME)`。
`INFO_JSON_NAME` 本来就是这个类里的常量（[L57](../../../backend/src/main/java/com/vidsage/infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java#L57)），语义是「媒体获取的副产物，不是媒体本身」，与「过渡态」是两回事，所以不并入共用白名单。

> 顺带删掉 `NON_PRODUCT_SUFFIXES` 常量与 `isNonProduct` 方法（换成上面那句组合判断）。
> 注意：**`.part` 也以 `.` 开头但不在 `source.` 前缀里**——yt-dlp 的未完成文件是 `source.mp4.part`，
> 确实带 `source.` 前缀，所以这条排除不能省。

---

## 5. 落位与文件树

```
backend/src/main/java/com/vidsage/
├── application/maintenance/                    ★ 新增包
│   ├── package-info.java                       包说明（仿 diagnostics 的写法）
│   ├── ReconcileService.java                   判据编排（四类各一个私有方法）
│   ├── query/                                  ★ 读模型（零 web 注解）
│   │   ├── ReconcileReport.java                对账报告
│   │   ├── ReconcileIssue.java                 单条问题行（taskNo 或 dirName + fileName）
│   │   ├── ReconcileIssueGroup.java            一类问题的 count + items
│   │   └── ReconcileSummary.java               扫到的目录/文件数
│   └── port/
│       ├── ReconcileReader.java                对账的数据来源端口
│       ├── TaskScopedArtifact.java             产物登记行 + 身份
│       └── TaskScopedIntermediateFile.java     中间产物登记行 + 身份
├── web/dto/                                    （既有包，本段新增四个响应类）
│   ├── ReconcileResponse.java                  ★ 对外响应 + from(读模型)
│   ├── ReconcileIssueGroupResponse.java        ★
│   ├── ReconcileIssueResponse.java             ★（@JsonInclude(NON_NULL) 在这里）
│   └── ReconcileSummaryResponse.java           ★
├── shared/fs/
│   └── TransientFiles.java                     ★ 新增：过渡态后缀白名单
├── infrastructure/persistence/
│   └── MybatisReconcileReader.java             ★ 新增：端口实现
├── infrastructure/provider/download/ytdlp/
│   └── YtDlpMediaAcquirer.java                 改：用共用白名单 + 本地排除 info json
└── web/controller/
    └── MaintenanceController.java              ★ 新增：POST /api/maintenance/reconcile
```

**测试**（`backend/src/test/`）：对账服务单测（四类判据各造场景）、`TransientFiles` 单测、
`scanProducts` 回归单测（AC10）、端点层的形状测试（若有 web 层测试先例）。

---

## 6. 边界与已知窗口

| 窗口 | 说明 | 处置 |
|---|---|---|
| **不是一致性快照** | 扫描期间有 run 在跑（写文件 / 改状态），可能报出「刚被修好的问题」或「刚产生的问题」 | 接受：只读端点，不做加锁、不阻塞写。人工处理前重跑一次即可 |
| **① 会自愈，②③④ 会重复出现** | 父 design §7.9 已写明 | 报告不标注，靠文档 |
| **根目录不存在 / 为空** | 工作区还没建过 | 返回四类全 0 + `dirs: 0`，不抛异常 |
| **`workspace` 表有行、目录不在盘上** | 整个目录被手工删了 | 该 task 的 `READY` 产物全部落进 ①（文件不在），不需要单开一类 |
| **盘上有目录、`workspace` 表没有** | 目录整体是孤儿 | 该目录下所有文件（非白名单后缀）落进 ③，`dirName` 就是那个目录名 |
| **哈希列与文件列不同步**（不该发生） | 父 design §8.4 纪律 2「同生同灭」 | ④ 跳过哈希为 `NULL` 的位（D7）；文件列 `NULL` 而哈希非 `NULL` 时同样跳过（不误报） |
| **报告很大** | ③ 可能一次报出成百上千个孤儿 | 接受：运维端点，全量给。不加分页（C58 同款理由：纯读端点将来加的成本≈0） |

---

## 7. 决策记录

| # | 决策 | 理由 |
|---|---|---|
| **D1** | **同步 POST 一次返回完整报告**，不上 SSE | 运维端点人工触发；实测量级：当前工作区（3 目录 / 23 文件 / 40 MB）几十毫秒，几百任务量级 1–3 秒（要算哈希的只有 KB 级字幕产物，37 MB 媒体文件不进哈希函数）。且现有 SSE 基础设施按 `(taskNo, runNo)` 分桶，对账没有 task / run，另开形状会与它平行打架 |
| **D2** | **② 扩到 `DELETED`** | 代码保证「全删干净才标 `DELETED`」，但手工放回文件 / 备份还原会破坏它；零成本兜底。父 design §7.9 原文只写 `SUPERSEDED`，本段回写 |
| **D3** | **③ 的已登记集合含全部状态**（含 `DELETED`） | 有登记就有主，残留归 ② 报；避免同一文件被 ②③ 各报一次 |
| **D4** | **四类直接当顶层字段**，每类 `{count, items[]}` | curl 一眼看懂哪类几行；「问题类别」由所属数组表达，不再冗余 `category` 字段 |
| **D5** | **`scanProducts` 保留本地排除 info json** | 拆掉全局白名单里的 `.info.json` 会让兜底扫描把 `source.info.json` 当媒体候选（见 §4.2）。这是修 bug |
| **D6** | **③ 扫根下所有一层子目录**（含 DB 未登记的） | 「整个目录都没登记」这类孤儿只有扫盘才发现得了；父 design §7.9 说 ③ 报「目录名」正说明扫的是盘上目录 |
| **D7** | **④ 只比对「文件列与哈希列都非 `NULL`」的位** | 可选位没登记时两列同时为空（同生同灭）；硬比会把正常形态误报成哈希不符 |
| **D8** | **后缀助手类名 `TransientFiles`**（方法 `isTransient(String)`） | 仓库里没有 `*Suffix` 类名先例（`PART_SUFFIX` / `TEMP_FILE_SUFFIX` 是常量名）；「过渡态文件」比「非产物」更贴它的两个消费者（`.part` / `.ytdl` 是下载器进行中标记，`.tmp` 是临时写盘）。**候选备选**：`TempFileSuffixes` / `WorkspaceScanRules` |
| **D9** | ~~出参不套 `web/dto` 映射~~ → **照判据 2 分离**：读模型 `application/maintenance/query/`（零注解）+ 响应 `web/dto/ReconcileResponse`（`@Schema` + `from`） | **订正（2026-10-20，审查提出）**：初稿拿 `ToolDiagnosticReport` 当先例是错的——判据 2 明说「对未来新增端点生效」，判据 1 的「直接对外」分支只对既有类型维持原状、不据此新造。且 `application` 不能依赖 `web`，分离不是可选项 |
| **D10** | **只读到底**：不删文件、不改状态、不清理存量垃圾行 | C65 定案；只读天然幂等 |
| **D11** | **② 排除「已被 `READY` 行占着的文件名」**（2026-10-20 真库验收后补） | 验收时发现 ② 会把「已归新版本的文件」报成「旧版残留」——C24 下文件名不带版本号，旧行登记的名字和新版是同一个。**照着报告去删会删掉当前版本**。判据加 `(dirName, fileName) ∉ READY 行登记的文件集合`，见 §2.2 |
