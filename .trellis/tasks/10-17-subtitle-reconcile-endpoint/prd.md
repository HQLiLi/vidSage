# 字幕模型重构 7/8：对账（只读对账端点 + 哈希检测）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威，尤其 §7.9 与 §8.4），
> 本文只写本段的范围、取舍与验收，不复述终态、不另开一份设计。
> 段级技术落点与执行顺序见 [implement.md](./implement.md)。
> **前置 S6（`10-17-subtitle-content-change`）与 S6.5（`10-20-subtitle-reuse-versioning`），两者均已归档合并；本段从 `dev` 切分支。**
> 约束定义见父 [handoff.md](../10-15-subtitle-task-model-refactor/handoff.md) §3.4 的 **C26 / C64 / C65**（handoff 已降为论证留档）。

## Goal

实现 `POST /api/maintenance/reconcile` —— **只读对账**（C65）：全量扫工作区目录与两张文件表，
把不一致**四件事全报**（条数 + 明细），**② 只报不修**（不删文件、不改状态），**不加鉴权**。

四类：

| 类 | 报什么 | 报告行带什么 |
|---|---|---|
| ① | 表 `READY` 但文件不在 | `task_no` + `file_name` |
| ② | 表 `SUPERSEDED` / `DELETED` 但登记的文件还在盘上 | `task_no` + `file_name` |
| ③ | 盘上有文件、表里没有任何登记、后缀不在白名单 | **目录名** + `file_name`（有意的例外） |
| ④ | 表 `READY` 但文件哈希对不上（C64） | `task_no` + `file_name` |

附带两件：

1. **临时文件后缀白名单提到全局** —— 现状是 `YtDlpMediaAcquirer` 的 private 常量
   `{.info.json, .part, .ytdl}`；改成全局共用、**补上 `.tmp`**、**拆掉 `.info.json`**
   （它现在入 `intermediate_file` 表了，留着会让对账把真孤儿漏报）。
2. **成为四列 `*_sha256` 的读取方** —— S6 已把这四列落库并随换版同写，但至今**零读取方**；
   对账的 ④ 就是它存在的理由（C64 详解：「哈希列一旦产出就不能补，所以列本次就加；
   若对账不做，那四列只写不读，C64 的 ④ 就是空话」）。

## 现状与欠账（代码实证）

| 欠账 | 现状实证 | 本段动作 |
|---|---|---|
| **没有对账端点** | `web/controller/` 只有 `Task` / `Artifact` / `Media` / `Language` 四个（运维端点 `GET /api/media/tools` 挂在 `MediaController` 上） | 新建 `MaintenanceController`（`/api/maintenance`） |
| **后缀白名单是局部的、且含 `.info.json`** | [YtDlpMediaAcquirer.java:66](../../../backend/src/main/java/com/vidsage/infrastructure/provider/download/ytdlp/YtDlpMediaAcquirer.java#L66) `private static final Set<String> NON_PRODUCT_SUFFIXES = Set.of(".info.json", ".part", ".ytdl")` | 提到 `shared/fs/` 共用类；补 `.tmp`、拆 `.info.json`；`scanProducts` 保留本地排除（**只涉及 yt-dlp 远程路线**，本地 / 上传路线不产 info json；见 design D5） |
| **四列哈希只写不读** | `subtitle_artifact` 的四列 `*_sha256` 已在 schema 与实体里、随换版同写；全仓库无读取方 | 本段补上读取方（④） |
| **「删不动留给对账」是句空头支票** | [SubtitleArtifactStore.java:151-157](../../../backend/src/main/java/com/vidsage/application/subtitle/SubtitleArtifactStore.java#L151-L157) 注释写着「删不动…那一行停在 `SUPERSEDED` **留给对账**」 | 对账 ② 兑现它 |
| **跨任务全量读没有出口** | 三个仓储端口全是「按 taskId 查」，没有全表扫 | 新增对账专用读取端口（见 design §4） |

## 本段范围

- **端点**：`POST /api/maintenance/reconcile`，同步返回完整报告（四类各 `{count, items[]}`）。
- **四类判据**：按 design §2 的精确口径实现（含 ② 覆盖 `DELETED`、③ 的参照集含全部状态）。
- **报告出参**：新建 `application/maintenance/` 包，出参 record 带 `@Schema`（形状即对外契约，仿 `ToolDiagnosticReport`）。
- **后缀白名单**：提到 `shared/fs/`，两个消费者（yt-dlp 兜底扫描 / 对账 ③）共用。
- **读取端口**：对账需要跨任务全量读（workspace + subtitle_task + subtitle_artifact + intermediate_file）。

## 本段不做

| 不做 | 原因 |
|---|---|
| **自动修复**（删文件、标 `DELETED`、标 `SUPERSEDED`） | C65 定案只读：② 只报不修。自动修判断错就是二次伤害，个人项目人工看一眼更稳 |
| **加鉴权** | C65 明确不加（**全站零鉴权**，另记缺口，不在本段） |
| **定时任务 / 后台巡检** | C26：只做手工触发 |
| **清理存量垃圾行** | 6.5 已定「只保证不再新增」；只读端点不动库 |
| **版本回退** | C52 本次不做（`revision` 语义与文件路径已留好位置） |
| **SSE 进度流** | 本段定案同步返回（决策见 design D1）：运维端点人工触发，实测量级秒级以内 |

## Acceptance Criteria

> 验收在真库 + 真工作区上做（构造异常 → 跑端点 → 看报告 → 复原），
> 构造用的 SQL / curl 只进 `implement.md` 的验收段，**不进代码库**。

- [ ] **AC1 端点存在且只读**：`POST /api/maintenance/reconcile` 返回 200 与完整报告体（`checkedAt` + `summary` + 四类）；
      调用前后 `subtitle_artifact` / `intermediate_file` / `subtitle_task` / `workspace` **逐行不变**，
      工作区所有文件 **mtime 不变**。
- [ ] **AC2 ① 报得出来**：手工删掉某 `READY` 行登记的一个必需位文件 → 报告 `danglingFiles` 含该行 `taskNo` + `fileName`；
      恢复文件后重跑 → 该条消失。
- [ ] **AC3 ② 报得出来（`SUPERSEDED`）**：手工造一行「`SUPERSEDED` 且文件列有值、文件在盘上」→ 报告 `reclaimPending` 含该行。
- [ ] **AC4 ② 覆盖 `DELETED` 残留**：手工造一行「`DELETED` 且登记的文件还在盘上」→ 同样被 `reclaimPending` 报出。
- [ ] **AC5 ③ 报得出来**：往工作区手工塞一个后缀不在白名单的文件（表里无登记）→ 报告 `orphanFiles` 含**目录名** + `fileName`。
- [ ] **AC6 ③ 不误报临时文件**：塞 `.tmp` / `.part` / `.ytdl` 后缀的文件 → 三类都不报（既非孤儿，也不算 ①②）。
- [ ] **AC7 ②③ 不重叠**：AC3/AC4 构造的那些文件**不出现在** `orphanFiles` 里（有登记的就不算孤儿）。
- [ ] **AC8 ④ 报得出来**：手工改掉某 `READY` 行登记文件的内容（哈希随之改变）→ 报告 `hashMismatch` 含该行 `taskNo` + `fileName`。
- [ ] **AC9 正常态零误报**：一个干净工作区（跑完一次完整流程）→ 四类**全为 0**，`summary` 报出扫描到的目录数与文件数。
- [ ] **AC10 yt-dlp 兜底扫描回归**：拆掉 `.info.json` 后，`source.info.json` **不再**被 `scanProducts` 当成媒体候选
      （由单测覆盖：一个目录里同时有 `source.mp4` 与 `source.info.json` → 候选恰 1 个）。
- [ ] **AC11 回归**：`mvn clean test-compile` 过；全量测试绿；后端能起服务。
- [ ] **AC12 文档一致**：父 `design.md` §7.9 与 §8.4 的表述与本段定案一致（尤其 ② 扩到 `DELETED` 这条要回写）；
      父 `handoff.md` 的 C26/C65 表述按订正惯例处理。

## Notes

- **硬前置：S6.5（`10-20-subtitle-reuse-versioning`）**。6.5 把「跳过也落一版新产物」改成「不复用才落版」，
  `revision` 语义回归「内容第几版」。S7 的对账判据（尤其 ④「`READY` 但哈希对不上」与「每趟执行至少一版」的隐含假设）
  **建立在 6.5 的口径上** —— 6.5 先落地，S7 才不会把正常情况报成缺口。6.5 的 `prd.md` 里已写明它是本段的前置。
- **测试基线**：动手前静态数一次 `@Test` / `@ParameterizedTest`（S6.5 归档时的值是 **683**，须重量确认）。
- **脱敏红线**：`source_uri`（URL 原文 / 上传原始文件名）不得写日志；**目录名内嵌身份键，日志里不打目录名与完整路径**。
  报告 JSON 里**要**带目录名（③ 必须带，design 明确是有意的例外）—— 这是「对外响应」与「日志」两条不同的边界，别混。
- **`summary` 里的目录数与文件数是给验收用的实测值**，不是产品指标。
- 环境命令（判编译、跑测试、起服务、推送代理）见父 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。
