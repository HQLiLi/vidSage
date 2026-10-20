# S7 执行计划：只读对账端点 + 哈希检测

> **终态设计在父任务 [design.md](../10-15-subtitle-task-model-refactor/design.md)，它是权威。**
> 本文件写本段的**段级技术落点 + 执行顺序 + 验证方式**（父任务章程：每段具体做法写在各子任务的 `implement.md`）。
> **本文件是执行参考稿，不是契约**：发现与父设计或代码冲突时，以父设计 / 代码为准并回来改这里。
> 范围与验收见 [prd.md](./prd.md)；技术形状（契约、判据、边界）见 [design.md](./design.md)。

---

## 0. 开工前的三件事

1. **读 design.md §0 的校准表**（8 条文档与代码的差异）——它是本段所有判断的依据。
2. **重量测试基线**：静态数 `@Test` / `@ParameterizedTest`（S6.5 归档时的值是 683，须重量确认）：

   ```bash
   cd f:/personal_project/vidSage/backend
   grep -rc "@Test\|@ParameterizedTest" src/test --include=*.java | awk -F: '{s+=$2} END {print s}'
   ```
3. **判编译一律 `clean test-compile`**（`mvn compile` 增量编译会假绿）；跑全量测试前**先删 `target/surefire-reports`**（陈旧报告会冒充本轮结果）。
   完整环境命令（JDK 21 路径、起服务、推送代理）见父 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。

---

## 段内顺序（七步）

```
0  门：预期文件树与关键形状过审
1  ★ 后缀白名单提取 + scanProducts 修正（独立，单测可验）
2  ★ 读取端口 + Mybatis 实现（对账的数据来源）
3  ★ 对账服务 + 报告出参（四类判据，本段核心）
4  端点接线（MaintenanceController）
5  测试补齐（四类各一场景 + 边界）
6  真库验收（AC2–AC9：构造异常 → 跑端点 → 复原）
7  文档同步 + 全量回归
```

**顺序的理由**

- **步骤 1 独立且最先做**：它不依赖对账的任何东西，做完单测就能验（AC10）。而且它是**修 bug**——
  先做掉，后面的对账判据才能假定「`.tmp` 不会被误报」。
- **步骤 2 是 3 的地基**：判据要吃三份输入，端口没通就没法验判据。
- **步骤 3 与 4 分开**：服务可以脱离 HTTP 层单测（四类判据各造场景），端点只是接线。
  分开之后，判据错了不用起服务就能发现。
- **步骤 5 与 6 分开**：单测覆盖判据的每个分支；真库验收覆盖「真实文件系统 + 真实 MySQL」这一层。
  两边都会发现对方发现不了的问题（单测碰不到真索引与真盘）。
- **每步完成后停下等审查**（分支工作流：改完即停，提交逐门批准）。

---

## 步骤 0：预期文件树与关键形状过审（**门**）

**这是一道门，不是准备工作。** 先把 [design.md](./design.md) §5 的文件树、§3.2 的端口形状、§1.2 的响应体
给项目所有者过一眼，**点头之后才写第一行代码**（先例：S1–S6.5 的同一道门）。

过审时要特别确认的四件事：

1. **响应体形状**（design §1.2）：四类直接当顶层字段、每类 `{count, items[]}`、`checkedAt` + `summary`。
2. **端口形状**（design §3.2）：`ReconcileReader` 三个方法，复用领域对象而非另造扁平 record。
3. **② 扩到 `DELETED`**（design D2）——这一条**要回写父 design §7.9**。
4. **`scanProducts` 保留本地排除**（design §4.2）——拆 `.info.json` 的连带修正，是修 bug。

---

## 步骤 1：后缀白名单提取 + `scanProducts` 修正

**落点**

| 文件 | 动作 |
|---|---|
| `shared/fs/TransientFiles.java` | 新增：`{.tmp, .part, .ytdl}` + `isTransient(String)` |
| `infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java` | 改：删 `NON_PRODUCT_SUFFIXES` 与 `isNonProduct`；`scanProducts` 的过滤条件换成 `!TransientFiles.isTransient(name) && !name.endsWith(INFO_JSON_NAME)` |

**注意**

- `.part` **不能省**：yt-dlp 的未完成文件是 `source.mp4.part`，带 `source.` 前缀，只靠 `TransientFiles` 拦不住 `source.info.json`，两个条件缺一不可。
- `INFO_JSON_NAME` 保持为 `YtDlpMediaAcquirer` 的私有常量（它描述的是「媒体获取的副产物」，与「过渡态」是两回事，不并进共用白名单）。
- 类名 `TransientFiles` 若在过审时被改，同步改 design D8 与本节。

**验证**

- `mvn clean test-compile` 过。
- 新增 `scanProducts` 单测（AC10）：目录里同时有 `source.mp4` + `source.info.json` → 候选恰 1 个；
  再加一个 `source.mp4.part` → 仍恰 1 个。
- `TransientFiles` 单测：三个后缀各命中一次；`source.info.json` **不**命中（它已不在白名单里）。

**回滚点**：本步独立成一个提交，回滚不影响后面。

---

## 步骤 2：读取端口 + Mybatis 实现

**落点**

| 文件 | 动作 |
|---|---|
| `application/maintenance/port/ReconcileReader.java` | 新增：三个方法（design §3.2） |
| `application/maintenance/port/TaskScopedArtifact.java` | 新增：`(taskNo, dirName, SubtitleArtifact)` |
| `application/maintenance/port/TaskScopedIntermediateFile.java` | 新增：`(taskNo, dirName, IntermediateFile)` |
| `infrastructure/persistence/MybatisReconcileReader.java` | 新增：三张表全表读 + 内存 join；盘上清单用 `Files.list` |

**注意**

- **全表读用 `selectList(null)`**，不写新 SQL（design §3.3 的理由）。
- 目录名由 `WorkspaceDir.of(SourceKey.of(sourceKey))` 现算——**与写路径同一公式**，别自己拼字符串。
- 根路径从 `WorkspaceDirectoryManager.getWorkspaceRoot()` 来，**不要**再注入 `WorkspaceProperties`（两处算根会漂）。
- **根目录不存在时返回空 map**，不抛异常（design §6）。
- `SubtitleArtifact` / `IntermediateFile` 的还原走既有的 `restore` 工厂（若仓储里有私有映射方法，照它的样子写）。

**验证**

- `clean test-compile` 过。
- 单测（若能脱离 Spring）：给假的 mapper 数据，验三份输出形状对得上。
- 起服务后真库跑一次：`listWorkspaceFilesByDirName()` 的 key 集合 = `ls backend/data/works` 的结果。

---

## 步骤 3：对账服务 + 报告出参

**落点**

| 文件 | 动作 |
|---|---|
| `application/maintenance/ReconcileReport.java` | 新增：出参 record（`@Schema`） |
| `application/maintenance/ReconcileIssue.java` | 新增：单条问题行（`taskNo` 或 `dirName` + `fileName`） |
| `application/maintenance/ReconcileService.java` | 新增：四类判据各一个私有方法 |
| `application/maintenance/package-info.java` | 新增：包说明（仿 `diagnostics` 的写法） |

**注意**

- 四类判据**严格照 design §2 的口径**，尤其：
  - ① 只查 `READY`；必需位与已登记的可选位同口径（登记了就得在）。
  - ② 查 `SUPERSEDED` **与** `DELETED`；只报**登记里写着**的文件（列 `NULL` 就不报）。
  - ③ 的已登记集合含**全部状态**；后缀过 `TransientFiles`。
  - ④ 只比对**文件列与哈希列都非 `NULL`** 的位。
- 文件存在判定用 `ArtifactFileUtils.isRegularFile`（与 `isIntact` 同口径：**不看大小**，0 字节 SRT 是合法产物）。
- 哈希计算用 `ArtifactFileUtils.sha256`（已存在）。
- **日志脱敏**：`ReconcileService` 只记汇总（四类条数 + 耗时），**不打目录名、不打文件名、不打路径**。
- 报告里的 `checkedAt` 用一次 `LocalDateTime.now()`，四处别各取各的。

**验证**

- `clean test-compile` 过。
- 单测：给假 `ReconcileReader`（内存里造三份输入），四类各造一个命中场景 + 一个不命中场景。
  重点造这几条边界：
  - 可选位没登记（列 `NULL`）→ ①④ 都不报；
  - 换版的旧 `CLEANED`（列已清空）→ ② 不报；
  - 级联译文（`SUPERSEDED` 但列有值、文件在）→ ② 报；
  - `DELETED` 且文件在 → ② 报；
  - 同一个文件既在已登记集合里、又不在盘上 → 只进 ①，不进 ③。

---

## 步骤 4：端点接线

**落点**

| 文件 | 动作 |
|---|---|
| `web/controller/MaintenanceController.java` | 新增：`@RequestMapping("/api/maintenance")` + `POST /reconcile` |

**注意**

- 类只做「接收 → 委托 → 返回」，与 `TaskController` 同风格；**不写业务逻辑**。
- 带 `@Tag` / `@Operation`（swagger 注释写清「只读、全量扫、不加鉴权」）。
- **无请求体**：方法签名不接 `@RequestBody`。
- 与 `MediaController.tools()` 一样记一条耗时日志（只记条数与耗时，**不打目录名**）。

**验证**

- `clean test-compile` 过；起服务。
- `curl -s -X POST http://localhost:8080/api/maintenance/reconcile` 返回 200 与四类字段。
- swagger UI（`/swagger-ui.html`）能看到这个端点。

---

## 步骤 5：测试补齐

- 四类判据的单测（步骤 3 已列边界）。
- `TransientFiles` 单测、`scanProducts` 回归单测（步骤 1）。
- 报告形状的序列化测试（四类恒在、空类是 `count: 0` + `items: []` 而非 `null`）。
- 端点层测试：若仓库里有 web 层测试先例（`@WebMvcTest` 之类），照它加一条；没有就靠步骤 6 的真库验收。

---

## 步骤 6：真库验收（AC2–AC9）

> **构造用的 SQL / curl 只在本节用，不进代码库。**
> 构造之前先跑一次基线（应四类全 0），每构造一类跑一次，**验完立刻复原**。

**基线**

```bash
curl -s -X POST http://localhost:8080/api/maintenance/reconcile | python -m json.tool
```

**AC2（①）**——挑一行 `READY`，删掉它登记的某个必需位文件：

```sql
SELECT t.task_no, w.source_key, a.segments_file_name
FROM subtitle_artifact a
JOIN subtitle_task t ON a.task_id = t.id
JOIN workspace w ON t.workspace_id = w.id
WHERE a.status = 'READY' AND a.segments_file_name IS NOT NULL LIMIT 1;
```

按 `source_key` 算出目录名（`|` → `_`），删掉该文件 → 报告 `danglingFiles.count` ≥ 1 且含该 `taskNo` + `fileName`。
**复原**：跑一次读路径（重新生成字幕），或从备份把文件放回。

**AC3（② `SUPERSEDED`）**——把一行改成 `SUPERSEDED`（**不动文件列**）：

```sql
UPDATE subtitle_artifact SET status = 'SUPERSEDED' WHERE id = <某 READY 行 id>;
```

→ `reclaimPending` 含该行。**复原**：`UPDATE ... SET status = 'READY' WHERE id = ...`。

**AC4（② `DELETED`）**——同上，`status = 'DELETED'`。**复原**：改回 `READY`。

**AC5（③）**——往工作区塞一个表里没有的文件：

```bash
echo x > f:/personal_project/vidSage/backend/data/works/<目录名>/junk.srt
```

→ `orphanFiles` 含该 `dirName` + `junk.srt`。**复原**：删掉。

**AC6（③ 不误报）**——塞三个白名单后缀的文件：

```bash
cd f:/personal_project/vidSage/backend/data/works/<目录名>
echo x > a.tmp; echo x > b.part; echo x > c.ytdl
```

→ 三类都不报（`orphanFiles` 里没有它们）。**复原**：删掉。

**AC7（②③ 不重叠）**——AC3/AC4 构造期间，那些 `fileName` **不出现**在 `orphanFiles` 里。

**AC8（④）**——改掉某个 `READY` 行登记文件的内容：

```bash
echo ' ' >> f:/personal_project/vidSage/backend/data/works/<目录名>/<某 READY 登记的文件>
```

→ `hashMismatch` 含该行。**复原**：跑一次真重跑（或从备份放回原文件）。

**AC9（正常态零误报）**——全部复原后重跑：四类全 0，`summary.dirs` = 3、`summary.files` = 实际文件数。

---

## 步骤 7：文档同步 + 全量回归

**文档**

- 父 `design.md` §7.9 的 ② 行 → 改成「`SUPERSEDED` / `DELETED` 但登记的文件还在」（**D2**）。
- 父 `design.md` §8.4 若有引用对账措辞处，一并核对。
- 父 `handoff.md` 的 C26 / C65 表述按订正惯例处理（废弃的直接删；prd 用删除线 + 日期）。
- 若本段落地时对判据有微调，**先改 design.md 再改代码**。

**回归**

- `mvn clean test-compile` 过；全量测试绿（先删 `target/surefire-reports`）。
- 后端能起服务；一次完整流程（提交 → 产物 → 翻译 → 重新生成字幕）走通。
- 端点连续调两次，结果一致（幂等）。

---

## 验收清单（对应 prd 的 AC）

| AC | 在哪验 |
|---|---|
| AC1 只读 | 步骤 6 基线：调用前后查四张表 + 工作区文件 mtime |
| AC2–AC9 | 步骤 6 |
| AC10 兜底扫描回归 | 步骤 1 单测 |
| AC11 回归 | 步骤 7 |
| AC12 文档一致 | 步骤 7 |
