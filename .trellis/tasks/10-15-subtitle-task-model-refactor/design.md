# 目标终态设计（权威版）

> **这份文档是权威。** 与 [handoff.md](./handoff.md) 冲突时以本文为准。
> handoff 保留**为什么这么定**（判据 `D-*`、决策 `C*`、命名 `N*`、写集 `W*` 的完整论证）；
> 本文只写**终态长什么样**，理由给一行 + 编号引用，需要展开时翻 handoff 对应编号。
> 分阶段落点见 [prd.md](./prd.md) 的任务地图；每段的具体做法写在各子任务的 `implement.md`。

---

## 0. 一张图

```
  workspace (媒体身份)                ← uk(source_type, source_key)
       │ 1:1  (W1：同生，显式事务)
       ▼
  subtitle_task (对外 task_no 稳定)   ← 无状态列，状态全在 run
       │
       ├── 1:N ──▶ task_run          ← 每次执行；uk(task_id, active_flag) 保证互斥
       │                │
       │                │ 1:N  (derived_from_run_id，审计 + 收口定位)
       │                ▼
       ├── 1:N ──▶ subtitle_artifact ← 版本化产物；READY 是唯一有效态
       │                └── derived_from_artifact_id 自引用（纯审计）
       │
       ├── 1:N ──▶ intermediate_file ← 中间产物（task 级缓存，跨 run 存活）
       │
       └── 1:1 ──▶ subtitle_keyword
```

**六张表**。MySQL 8，**只写逻辑外键**（不写 `FOREIGN KEY` 约束，C6）。

---

## 1. 六张表（权威 DDL）

> handoff §4 的六个 DDL 块是**定案前的草稿**，四处未订正（索引名 / 枚举列宽 / `_url` 四列 / 缺 sha256 四列）。
> 本节是订正后的权威版，`implement.md` 照抄这里。

### 1.1 workspace —— 媒体身份

```sql
CREATE TABLE IF NOT EXISTS workspace
(
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    source_type   VARCHAR(32)   NOT NULL COMMENT 'URL / UPLOAD',
    source_key    VARCHAR(512)  NOT NULL COMMENT 'URL 路线：bvid|p；上传路线：内容 sha256',
    source_uri    VARCHAR(2048) NOT NULL COMMENT 'URL 原文 / 上传文件原始名。红线：不得写日志',
    canonical_url VARCHAR(1024) NULL     COMMENT '规范化后的 URL，只给人看',
    duration_ms   INT           NULL     COMMENT '内容指纹之一（URL 路线）；UPLOAD 留空',
    published_at  DATETIME      NULL     COMMENT '内容指纹之二，来自 yt-dlp 的 timestamp',
    created_at    DATETIME      NOT NULL COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL COMMENT '最近变更时间',
    CONSTRAINT pk_workspace PRIMARY KEY (id),
    CONSTRAINT uk_workspace_source_type_source_key UNIQUE (source_type, source_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='媒体身份';
```

- **没有 `workspace_dir` 列**（§9.4j 乙2）：目录名 = `sanitizeName(source_key)`，是 `source_key` 的纯函数。
- **没有 `file_name` / `size_bytes`**（§9.4c）：前者已有两处落点（`intermediate_file` 的 `SOURCE_MEDIA` / `workspace.source_uri`），后者是读时 stat。
- 内容指纹**不另拼一列串**，就是 `duration_ms` + `published_at` 两个可读列的组合（C27）。
- 指纹两列的写入时机由**指纹探测阶段**决定（C47/C48），**不是提交时写** —— 提交路径手里只有 URL，拿不到指纹（C29）。

### 1.2 subtitle_task —— 字幕任务

```sql
CREATE TABLE IF NOT EXISTS subtitle_task
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_no      VARCHAR(32) NOT NULL COMMENT '对外稳定号（32 位短 UUID）',
    workspace_id BIGINT      NOT NULL COMMENT '逻辑外键 → workspace.id',
    created_at   DATETIME    NOT NULL COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL COMMENT '最近一次 run 收尾的时刻（C45）',
    CONSTRAINT pk_subtitle_task PRIMARY KEY (id),
    CONSTRAINT uk_subtitle_task_task_no UNIQUE (task_no),
    CONSTRAINT uk_subtitle_task_workspace_id UNIQUE (workspace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='字幕任务';
```

**没有状态列。** 主状态、失败原因、阶段全部从 `task_run` 派生（C5 砍 `latest_*` 的同一条理由）。

### 1.3 task_run —— 每次执行

```sql
CREATE TABLE IF NOT EXISTS task_run
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_id         BIGINT      NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
    run_no          INT         NOT NULL COMMENT '任务内递增（MAX+1，不需要事务）',
    run_type        VARCHAR(32) NOT NULL COMMENT 'MAIN / RECLEAN / TRANSLATE',
    target_language VARCHAR(16) NULL     COMMENT '只有 TRANSLATE 有值',
    status          VARCHAR(32) NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED / INTERRUPTED',
    current_stage   VARCHAR(64) NULL     COMMENT '前端「卡在哪一步」',
    error_message   TEXT        NULL     COMMENT '失败原因（不用中文前缀区分来源）',
    started_at      DATETIME    NULL     COMMENT '插 RUNNING 行时填 now()',
    finished_at     DATETIME    NULL     COMMENT '落终态时填 now()；INTERRUPTED 填启动收口的时刻',
    created_at      DATETIME    NOT NULL COMMENT '创建时间',
    active_flag     VARCHAR(32) GENERATED ALWAYS AS (IF(status = 'RUNNING', 'RUNNING', NULL)) STORED,
    CONSTRAINT pk_task_run PRIMARY KEY (id),
    CONSTRAINT uk_task_run_task_id_run_no UNIQUE (task_id, run_no),
    CONSTRAINT uk_task_run_task_id_active_flag UNIQUE (task_id, active_flag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='每次执行';
```

**`uk_task_run_task_id_active_flag` 是互斥的全部机制**（C14）：NULL 不参与唯一比较，只有 `RUNNING` 的行被约束住。
于是**抢互斥退化成一句 INSERT** —— 撞键就是抢失败（409），不需要事务锁、不需要 CAS 重试、不需要内存登记表。

**没有 `PENDING`**：加它就变成「插 PENDING（不占锁）→ UPDATE RUNNING（才占锁）」两步，锁与状态分家 —— 正是 C14 要消灭的分裂。

### 1.4 subtitle_artifact —— 产物版本

```sql
CREATE TABLE IF NOT EXISTS subtitle_artifact
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_id                  BIGINT       NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
    derived_from_run_id      BIGINT       NOT NULL COMMENT '哪一次执行产出的（审计；C61 收口靠它定位）',
    artifact_type            VARCHAR(32)  NOT NULL COMMENT 'ORIGINAL / CLEANED / TRANSLATED',
    lang                     VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'TRANSLATED 才填；空串 = 无语言维度',
    revision                 INT          NOT NULL COMMENT '同 (task,type,lang) 递增（MAX+1）',
    status                   VARCHAR(32)  NOT NULL COMMENT 'CREATING / READY / SUPERSEDED / FAILED / DELETED',
    derived_from_artifact_id BIGINT       NULL     COMMENT '审计 / 溯源：内容继承自哪一版上游。不参与级联、不参与读取判据',
    keywords_fingerprint     VARCHAR(64)  NULL     COMMENT '只有 CLEANED 有值（C33）',
    segments_file_name       VARCHAR(255) NULL     COMMENT '检查点 JSON',
    subtitles_file_name      VARCHAR(255) NULL     COMMENT 'SRT 派生视图',
    context_file_name        VARCHAR(255) NULL     COMMENT '只有 CLEANED 有（摘要副产物）',
    terms_file_name          VARCHAR(255) NULL     COMMENT '只有 TRANSLATED 有（术语查证副产物）',
    segments_sha256          CHAR(64)     NULL     COMMENT '成品文件的 sha256（C64）；与文件列同生同灭',
    subtitles_sha256         CHAR(64)     NULL     COMMENT '同上',
    context_sha256           CHAR(64)     NULL     COMMENT '同上',
    terms_sha256             CHAR(64)     NULL     COMMENT '同上',
    created_at               DATETIME     NOT NULL COMMENT '创建时间',
    active_flag              VARCHAR(32) GENERATED ALWAYS AS (IF(status = 'READY', status, NULL)) STORED,
    CONSTRAINT pk_subtitle_artifact PRIMARY KEY (id),
    CONSTRAINT uk_subtitle_artifact_task_id_artifact_type_revision_lang UNIQUE (task_id, artifact_type, revision, lang),
    CONSTRAINT uk_subtitle_artifact_task_id_artifact_type_lang_active_flag UNIQUE (task_id, artifact_type, lang, active_flag),
    INDEX idx_subtitle_artifact_derived_from_run_id (derived_from_run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='字幕产物版本';
```

三个要点：

1. **`lang NOT NULL DEFAULT ''`** —— MySQL 里 NULL 不参与唯一约束，若允许 NULL，`(1,'ORIGINAL',1,NULL)` 能插两条。空串占位才对。
2. **一行 = 一个逻辑版本**，文件用固定四列表达。现状 8 类文件天然分成三代三组：

| 逻辑版本 | 文件列 | 备注 |
|---|---|---|
| `ORIGINAL` | `segments` + `subtitles` | 无语言维度 |
| `CLEANED` | `segments` + `subtitles` + `context` | `context` 是清洗的副产物 |
| `TRANSLATED(lang)` | `segments` + `subtitles` + `terms` | 术语表按语种 |

3. **`uk_..._active_flag` 逼出落定顺序**（C41），3 与 4 不可换（换了当场撞重复键）：

```
   1. INSERT 新行（CREATING）      active_flag 为 NULL，不撞
   2. 写文件（临时 → 原子替换）
   3. UPDATE 旧行 READY → SUPERSEDED（+ 清空它的文件列与哈希列）
   4. UPDATE 新行 CREATING → READY（+ 写文件列与哈希列）
```

**第 4 步把「写文件列」并进来**：否则「写列完成」到「换版」之间那一行的状态是「`CREATING` 但文件列已填」，而 `CREATING` 意味着「文件还没写完」—— 同一个值两种含义，违反 D-6。并进来之后 **`CREATING` 行永远是全 NULL**。

> **别**用「文件列非空 ⟺ `READY`」当不变量 —— 它**不成立**：下游译文是**孤儿型**，`SUPERSEDED` 时**必须留着文件列**，否则删文件时没有路径可删（C52：路径是 DB 的列，不是拼接公式）。

### 1.5 subtitle_keyword —— 关键词（语义不变，只换锚点）

```sql
CREATE TABLE IF NOT EXISTS subtitle_keyword
(
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_id    BIGINT        NOT NULL COMMENT '逻辑外键 → subtitle_task.id（原为 workspace_key）',
    keywords   VARCHAR(4096) NOT NULL COMMENT 'U+0001 连接，顺序即用户填写顺序',
    created_at DATETIME      NOT NULL COMMENT '首次写入时间（覆盖写不刷新）',
    updated_at DATETIME      NOT NULL COMMENT '最近一次覆盖写的时刻',
    CONSTRAINT pk_subtitle_keyword PRIMARY KEY (id),
    CONSTRAINT uk_subtitle_keyword_task_id UNIQUE (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='字幕关键词（当前生效一份）';
```

> **定案（2026-10-17）：`updated_at` 加，当审计列用。**
>
> handoff §4.5 写了这列却**没给写入时机**，且它那句「同现状」是笔误 —— 现状 `schema.sql` 这张表本来就只有 `created_at`，本列是本次新增的。所以定「加」不能只把列加回去，必须**同时定死写入点**：
>
> - **写入点 = 与 `keywords` 同一条 upsert 的 UPDATE 分支**（`ON DUPLICATE KEY UPDATE keywords = ?, updated_at = ?`），不另开写入路径。首插时 `created_at` 与 `updated_at` 同值。
> - **列与写入点一起落在 S2**（关键词换锚点那一段）。**S1 不动这张表** —— 本表本次既改列名又改类型，`CREATE TABLE IF NOT EXISTS` 不改已存在的表、MySQL 8 也没有 `DROP COLUMN IF EXISTS`，所以 S2 必须手工 `DROP` 后重建，`updated_at` 由那次重建一并带进来。S1 现在去 `ALTER` 加一列既拿不到效果（老表不走 `CREATE`）也没必要。
>   （2026-10-17 订正：初稿写的是「S1 只建列、不写它」，与 prd 需求 6「`subtitle_keyword` 本段一个字节不动」自相矛盾，实施时被子 agent 查出。）
> - 现状表没有 `updated_at`，所以这次是**净增一列**，不是「保留现状」。

> **通用口径（本次一并定，回答同族的四处）**：凡是带 `updated_at` 的表，写入点一律是「**在同一条 UPDATE 里一起刷**」，首插时与 `created_at` 同值。落到各表 —— `subtitle_task` = 每次 run 收尾（C45，它是该表唯一的 UPDATE）；`workspace` = 指纹写入那条 UPDATE（§7.3 步骤 2.4，它是 `workspace` 唯一的 UPDATE）；`intermediate_file` = 重新产出回 `READY` / 级联标 `SUPERSEDED` / 删完标 `DELETED` 三条；本表 = 覆盖写。`subtitle_artifact` 与 `task_run` 不带 `updated_at`，不在此列（`task_run` 的 `started_at` / `finished_at` 由 §1.3 单独定）。

> ⚠️ **本表本次既改列名又改类型**（`workspace_key CHAR(12)` → `task_id BIGINT`），而 `CREATE TABLE IF NOT EXISTS` 不改已存在的表、MySQL 8 也没有 `DROP COLUMN IF EXISTS` → **旧库必须手工 `DROP TABLE subtitle_keyword` 一次**（§9.4i：存量可弃）。这条要写进新 `schema.sql` 的头部注释。

### 1.6 intermediate_file —— 中间产物（task 级缓存）

```sql
CREATE TABLE IF NOT EXISTS intermediate_file
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
    task_id             BIGINT       NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
    file_name           VARCHAR(255) NOT NULL COMMENT '工作区内文件名；文件系统上限即 255',
    file_type           VARCHAR(32)  NOT NULL COMMENT 'SOURCE_MEDIA / INFO_JSON / AUDIO',
    status              VARCHAR(32)  NOT NULL COMMENT 'READY / SUPERSEDED / DELETED',
    derived_from_run_id BIGINT       NULL     COMMENT '产出它的那条 run（审计，无承重用途）',
    created_at          DATETIME     NOT NULL COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL COMMENT '最近变更时间',
    CONSTRAINT pk_intermediate_file PRIMARY KEY (id),
    CONSTRAINT uk_intermediate_file_task_id_file_name UNIQUE (task_id, file_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='中间产物（task 级缓存）';
```

三个要点：

1. **`uk(task_id, file_name)` 决定了状态机的形状** —— 一个文件在一个 task 里**只有一行**，所以「重新产出」是 `UPDATE` 那行回 `READY`，不是插新行。这跟 `subtitle_artifact` **本质不同**：那边有版本交替，这边没有版本、一行到底 —— 所以不需要 `revision`，也不需要 `CREATING`。
2. **`file_name` 是 255 不是 1024** —— 它是**文件名**不是路径，文件系统本身就卡在 255。
3. **`file_type` 三个值，每个都有代码常量背书**：

| file_type | 文件名 | 常量 |
|---|---|---|
| `SOURCE_MEDIA` | `source.<ext>` | `MediaInput.SOURCE_FILE_PREFIX` |
| `INFO_JSON` | `source.info.json` | `MediaInput.SOURCE_INFO_JSON_NAME` |
| `AUDIO` | `audio-<规格>-t<n>.<ext>` | `AudioArtifact.AUDIO_PREFIX` |

**没有 `ASR_RAW`** —— ASR 结果直接写成 `original-segments.json`，中间没有落盘环节（C50 订正）。

---

## 2. 索引与命名规则（N4）

```sql
CONSTRAINT pk_<表名>            PRIMARY KEY (id)
CONSTRAINT uk_<表名>_<列名...>  UNIQUE (...)
INDEX      idx_<表名>_<列名...> (...)
```

- **带表名段**；**前缀统一 `uk_`**（现状 `task` 用 `uq_`，而 `uq_` 只出现在要删的表上，收口成本 0）；**主键具名**；**唯一约束用 `CONSTRAINT ... UNIQUE`**，不用 `UNIQUE KEY` / `UNIQUE INDEX`。
- **不开宽松档** —— 机械规则的价值就是不用判断。
- **预防条款**：索引名超 64 字符时在**列名边界**截断（不留悬空 `_`）。当前最长 **59**（`uk_subtitle_artifact_task_id_artifact_type_lang_active_flag`），未触发。

**本次 DDL 范围**：

| 动作 | 条目 |
|---|---|
| **建 pk_**（6） | `pk_workspace` / `pk_subtitle_task` / `pk_task_run` / `pk_subtitle_artifact` / `pk_intermediate_file`（新建）+ `pk_subtitle_keyword`（名不变，已存在） |
| **建 uk_**（9） | `uk_workspace_source_type_source_key` / `uk_subtitle_task_task_no` / `uk_subtitle_task_workspace_id` / `uk_task_run_task_id_run_no` / `uk_task_run_task_id_active_flag` / `uk_subtitle_artifact_task_id_artifact_type_revision_lang` / `uk_subtitle_artifact_task_id_artifact_type_lang_active_flag` / `uk_subtitle_keyword_task_id`（原 `uk_subtitle_keyword_workspace_key` 改名）/ `uk_intermediate_file_task_id_file_name` |
| **建 idx_**（1） | `idx_subtitle_artifact_derived_from_run_id` —— 消费者是**收口**（每次 run 都跑，热路径） |
| **不建** | `idx_subtitle_artifact_task_id_artifact_type` —— 被 `uk_..._revision_lang` 的**最左前缀**覆盖，冗余 |
| **暂缓 3 条** | `idx_task_run_task_id_run_type_status` / `idx_intermediate_file_task_id_status` / `idx_subtitle_artifact_derived_from_artifact_id`。**触发条件写成「出现按 <列> 过滤的查询时」**，不是表行数阈值 |

### 2.1 枚举列一律 `VARCHAR(32)`（N6）

不用 `ENUM`、也不用 `CHECK`。适用范围：`source_type` / `run_type` / `task_run.status` / `artifact_type` / `subtitle_artifact.status` / `file_type` / `intermediate_file.status` + 两个 `active_flag`。

- **与 C6 同一口径**：ENUM 是 DB 层约束，而本项目「手改表已经靠手工」，约束会让改表更难受。
- **加值零 DDL**：本次就要加 `INTERRUPTED`；ENUM 下每次加值都要手工 `ALTER TABLE`。
- **宽度 32 不是 16**：现有最长值 `SOURCE_MEDIA` = 12，取 32 换「加值不用先改列宽」，成本为 0（VARCHAR 按实际长度存）。
- **不要用 `CHECK` 把约束搬回 DB** —— 加值一样要 `ALTER TABLE`，「零 DDL」的好处立刻没了。
- **`lang VARCHAR(16)` 不用动** —— `SupportedLanguage` 白名单里最长是 `zh-Hans` / `zh-Hant` = 7 字符。

---

## 3. 领域模型与包结构

> handoff §9.4h 把「任务域拆包」留给本文定（N5 末）。本节是那个决定。

**结论：删 `domain/task/` 整个包，新建 `domain/workspace/`。**

| 类 | 落点 | 说明 |
|---|---|---|
| `Workspace` | `domain/workspace/` | 媒体身份实体 |
| `SourceType` | `domain/workspace/model/` | **原 `TaskInputType` 改名** —— 对齐 `workspace.source_type` 列（同 `TaskRunStatus` ↔ `task_run.status` 的模式） |
| `SourceKey` | `domain/workspace/model/` | 值对象：URL 路线 `bvid\|p`、上传路线 sha256 |
| `MediaIdentityResolver` | `domain/workspace/port/` | URL 规范化策略（C7–C12：剥跟踪参数、取 `BV...`、`av` 换算、短链跟跳转） |
| `WorkspaceRepository` | `domain/workspace/port/` | |
| `SubtitleTask` | `domain/subtitle/` | 身份实体 |
| `TaskNo` | `domain/subtitle/model/` | 从 `domain/task/model/` 搬来 |
| `TaskRun` | `domain/subtitle/` | 状态机在这里（C36） |
| `TaskRunStatus` | `domain/subtitle/model/` | **原 `TaskStatus` 改名**（N5） |
| `SubtitleArtifact` | `domain/subtitle/` | **旧 record 让名**（N1） |
| `IntermediateFile` | `domain/subtitle/` | 枚举 `IntermediateFileType` |
| 五个 Repository port | `domain/subtitle/port/` | 见下 |
| `TaskStageEvent` | **删** | 见下 |

**port 清单**：`WorkspaceRepository` / `SubtitleTaskRepository` / `TaskRunRepository` / `SubtitleArtifactRepository` / `IntermediateFileRepository` / `SubtitleKeywordRepository`（已有，换锚点）。

> **订正 handoff §8.1**：那里写「`VideoTaskRepository` 拆成 **4 个** port」—— 那是 C62 加 `intermediate_file` 之前的数，实际是 **5 个**。

**`TaskStageEvent` 删除（N5）**：它是纯函数政策类（`final class` + 私有构造 + `static from(VideoTask, String runningStage)`），入参 `VideoTask` 要删、`switch` 里有 `case PENDING` 要删值；而 `runningStage` 这个参数存在的**唯一理由**就是把后置会话算进流里的那处隐式编码，新模型 `uk_active_run` 让它整块消失。
**它的替代者** = 「连接建立即推 DB 快照」（C57 的兜底），现状已有雏形在 `TaskProgressNotifier.createSnapshot`，落位到 application 的 SSE 侧。

**让名的旧类**（N1，D-7：不问"换个什么名"，问"旧类还该不该存在"）：

| 旧类 | 去向 | 成本 |
|---|---|---|
| `SubtitleArtifact`（record） | 改名 `SubtitleStageOutput` | 2 个源文件 |
| `AudioArtifact`（record） | 改名 `AudioStageOutput` | 11 文件 / 41 处 |
| `SubtitleArtifactSpec`（enum） | 文件名模板那半**要留**（C24 文件名不变）；`downloadable` 白名单那半被 `/artifacts` 端点取代 | — |
| `TaskArtifact` / `TaskArtifactFile` / `TaskArtifactResponse` | 被 `/artifacts` 与下载端点取代 | — |
| `WorkspaceKey` / `WorkspaceDir` | 退场（`source_key` + `sanitizeName` 取代） | — |

**改名顺序要求**（N5）：**先落定删除集 → 删 → 再对存活集改名**。`TaskStatus` 的 165 处 / 17 文件里大部分落在本次要删/改的文件上，混着做会把成本算错。
**假朋友**：`DashScopeAsrProvider` 那 4 处 `TaskStatus` 是 `com.alibaba.dashscope.common.TaskStatus`（阿里 SDK 同名枚举）→ **显式排除，改名走 IDE rename symbol，不做文本替换**。

---

## 4. 状态机与写入路径（D-6）

**三处**状态，各管各的。

### 4.1 `task_run.status`

```
（无） ──插行──▶ RUNNING ──▶ SUCCEEDED
                   │
                   └────▶ FAILED          （无条件收口，C43）
                   │
        （进程重启，启动收口补记，C15）
                   └────▶ INTERRUPTED
```

| 值 | 意味着什么 | 写入路径（全部） | 路径类型 |
|---|---|---|---|
| `RUNNING` | 这条 run 正在跑。**一个 task 同一时刻只允许有一条** —— 所以这行能插进去，本身就说明抢到了执行权（**插入即抢锁**） | 三条路都是**先插一行 RUNNING 才开始干活**：提交 / 重新生成 / 翻译。同一 task 已有 RUNNING 行时插不进去 → 409 | 正常 |
| `SUCCEEDED` | 跑完了，产物齐了 | run 体正常走到底 | 正常 |
| `FAILED` | 跑了，**知道它没成** | **收口置位**（C43）：执行体退出时还没落终态，一律置 `FAILED` | 正常 |
| `INTERRUPTED` | **不知道**跑成没跑成（进程没了） | **启动收口**（C15）：进程启动时发现残留 `RUNNING` | 恢复 |

**每个值恰好一条路径** —— 这张表不需要「正常/恢复共享同一个值」的情形。

### 4.2 `subtitle_artifact.status`

```
(无) ──插行──▶ CREATING ──换版落定──▶ READY ──┬──▶ SUPERSEDED ──▶ DELETED
                  │                            │  （不再是当前版）  （文件确认回收）
                  └──收口置位──▶ FAILED        │
                                               └── 覆盖型：SUPERSEDED 与文件列清空同一刻
```

| 值 | 意味着什么 | 写入路径（全部） | 路径类型 |
|---|---|---|---|
| `CREATING` | 占位：行在，文件还没写完 | 开始产生产物时插行（C19）。**该行永远全 NULL** | 正常 |
| `READY` | **当前有效版**（业务查询只认它） | 换版落定（写文件列 + 哈希列 + 置 READY，同一条 UPDATE） | 正常 |
| `SUPERSEDED` | **不再是当前版**（两种原因，同一个意思） | ① 同 `(task, type, lang)` 出现更高 `revision`（换版）<br>② 上游 `CLEANED` 换版 → 下游全部（级联，W5） | 正常 · 正常 |
| `FAILED` | **这一版产物没成**（两种原因，同一个意思） | ① **收口置位**（C61）：本次 run 插的、仍停在 `CREATING` 的行<br>② 启动收口把残留 `CREATING` 落下来（C15） | 正常 · **恢复** |
| `DELETED` | 文件已确认回收 | 删完文件之后标（C22 两阶段） | 正常 |

### 4.3 `intermediate_file.status`

```
（无）──INSERT READY──▶ READY ⇄ SUPERSEDED ──▶ DELETED
                        ▲       （已作废，文件待删）  （文件确认回收）
                        └─────────────────────────────┘
                            重新产出时 UPDATE 回 READY
```

| 值 | 意味着什么 | 写入路径（全部） | 路径类型 |
|---|---|---|---|
| `READY` | 文件在、是**有效缓存** | ① 首次产出（INSERT）<br>② 重新产出（`SUPERSEDED` / `DELETED` → `READY`） | 正常 · 正常 |
| `SUPERSEDED` | **已作废，文件待删** | ① 内容变更级联（指纹变了，W5′） | 正常 |
| `DELETED` | **文件已确认回收** | ① 当场删完标（C22 两阶段）<br>② 对账补删完标 —— **本次不实现**（C65 把对账做成只读，② 只报不修） | 正常 · **恢复** |

**为什么只有三个值**：`CREATING` / `FAILED` 对中间产物是硬套（它是纯缓存，"没写成" = "没写过"，且走「先落盘、后登记」，**压根没有"登记了但文件还没写"这个窗口**）；`FAILED` 若留就是**零路径的死值**（D-6 不允许）。
**`SUPERSEDED` 必需**：否则 `DELETED` 得同时表示「待删」和「已删」→ 同一个值两种意思。
**`READY` 是唯一允许"复活"的值** —— 重新产出时复用同一行（`uk_file` 唯一）。

---

## 5. 写集与事务边界（W1–W8）

问的不是「哪些东西该归一类」，而是 **「哪些写必须一起对」**。

| # | 写集 | 必须一起对的是什么 | 机制 | 原子性必需？ |
|---|---|---|---|---|
| **W1** | `workspace` + `subtitle_task` 同插 | 一个媒体必有一个任务、至多一个 | **显式事务**（2 行 2 表） | **必需**（R：不可恢复） |
| W2 | 插 `RUNNING` run | 一 task 至多一条 `RUNNING` | `uk_active_run`（一句 INSERT） | 必需（单语句即满足） |
| W3 | artifact 占位（`CREATING`） | 占位先落 | 单语句 | 必需（单语句即满足） |
| W4 | artifact 换版（同 `(type,lang)`） | 至多一条 `READY` + 旧降级与新升级同刻 | **按序两次 UPDATE**（索引逼出顺序） | **非必需**（R：重跑可恢复） |
| **W5** | 换版 + 下游级联标记 | 新 `CLEANED` READY ⇒ 下游全部 `SUPERSEDED` | **显式事务**（N+2 行） | **必需**（R：不可恢复） |
| **W5′** | 指纹级联（artifact + 中间产物全标）+ `workspace` 指纹写入 | 内容变了 ⇒ 旧产物全作废 | **显式事务** | **必需**（C47/C48） |
| W6 | 文件删除 + 标 `DELETED` | 不删任何 `READY` 行指着的文件 | C22 两阶段（文件操作在事务外） | 无原子单位，是**协议** |
| W7 | 启动收口 | 残留 `RUNNING` → `INTERRUPTED`；残留 `CREATING` → `FAILED`；被收掉的 task 更新 `updated_at` | 三条 UPDATE | 单语句 ×3 |
| W8 | 关键词覆盖写 | 一 task 一份 | `uk_task_id` | 单语句 |

**边界 = `{W1, W5, W5′}`。** `TaskRun` / `SubtitleArtifact` / `Task` **都不是边界**，是带行为的领域实体 / 身份实体（C40 已作废，不用类级聚合标签）。

**判据：D-2 不是唯一尺子。** 判断一个写集要不要原子单位，至少还要看：违规之后**能不能恢复**（判据 **R**）、机制够不够便宜。
本案的恢复机制**只有四个**：启动收口（C15）、读路径自愈（C25）、人工重新触发（C17）、手工对账（C26/C65）。
它是真分水岭 —— **W1 不可恢复 → 必需原子；W4 可恢复（重跑一次 RECLEAN / TRANSLATE）→ 非必需；W5 / W5′ 不可恢复 → 必需。**

**实现层**：现状全仓 `@Transactional` = 0、持久化栈是 **MyBatis-Plus**（不是 JPA）。MyBatis 的 `SqlSession` 不像 Hibernate 的 Session 会因语句失败而失效，`DataSourceTransactionManager` 也不会因一条 SQL 撞唯一键就把事务标脏 —— **rollback-only 只发生在 Spring 事务代理边界**。这条是 C63「同一事务内 catch 重复键继续用」可行的依据。

---

## 6. 对外契约（C53–C58）

> **前提**：本次**不保旧 API**，前端**不参与分期**（C60）—— 契约按新模型自由设计，不背兼容包袱。
> 本节范围：`/api/tasks/**` 与 `/api/languages`。**另有一条不在本节形状里的端点**：`POST /api/maintenance/reconcile`（只读对账，C65）—— 它是运维端点，不属于 task API。

| # | 决策 |
|---|---|
| **C53** | 端点路径不变（`/api/tasks/{taskNo}/...`）；`GET /api/tasks/{taskNo}` = **身份 + `lastRun`（单值）**；扁平的 `status` / `currentStage` / `errorMessage` / `startedAt` / `finishedAt` **五个字段全删**（`subtitle_task` 没有状态列，任何扁平状态都是派生量；**压平是渲染层的事**） |
| **C54** | 新增 `GET /api/tasks/{taskNo}/artifacts` = **当前有效产物**（只有 `READY`），按**版本行**给；**不给历史**（历史行是空壳 —— C24 作废时清空旧行文件列） |
| **C55** | 下载端点从 `/{fileName}` 改成按 `type` + `file` + `?lang=` 定位 |
| **C56** | 提交 / 翻译 / 重生成三个 POST 统一返回 **202 或 409 + `{ taskNo, runNo }`**；`TaskConflictResponse.existingTask` 删掉（202 里的 `lastRun` 四个字段全是常量、零信息、且可能已被异步体推进 → **不可依赖**；不可依赖的字段比没有更糟） |
| **C57** | SSE = **一条 run 一条流**，run 终态即关；事件带 `runNo` / `runType` / `targetLanguage` / `status` / `stage` / `terminal`；`terminal` 语义从「流结束」**降为「这条 run 结束了」** |
| **C58** | **不做 run 历史端点**（痛点已由落库本身解决；纯读端点将来加的成本≈0） |

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

**提交 / 翻译 / 重生成（三个 POST）** —— 202 与 409 同形状：

```jsonc
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

> **注意：别拿 `lastRun.status === 'SUCCEEDED'` 当「能不能翻译」的判据** —— RECLEAN 失败时 `lastRun` 是 `FAILED`，可上一版 `CLEANED` 还是好的、翻译照样能点。

**两条连带**（新模型逼出来的）：

1. **409 的语义整个变了。** 现状是**去重语义**（带 `existingTask`）；新模型里提交一个做过的 URL **不冲突**。409 只剩 `uk_active_run` 撞键一种。`POST /api/tasks` 那一路的 409 **必须带 `taskNo`** —— 翻译 / 重生成时 taskNo 在路径里，但提交一个正在跑的 URL 时前端自己算不出来。
2. **`INTERRUPTED` 第一次露到前端**（新增的第五态）—— 前端要能显示「上次被中断了，重新提交即可」（C17：不续跑）。

**`GET /api/tasks/{taskNo}/keywords`** 形状照搬现状 `{ keywords: [...], fingerprint: "..." }`，唯一变化是前端比对用的**第二个值**改从 `/artifacts` 里 `CLEANED` 行的 `keywordsFingerprint` 读，不再从 `clean-segments.json` 的文件内容里读（C33）。

---

## 7. 业务流程

### 7.1 提交（URL）—— 接入层，同步，秒回 202

1. 校验入参（关键词上限等）
2. **算身份键**：剥跟踪参数 → 取 `/video/` 段的 `BV...`（`av...` 换算）→ 拼 `p`（缺省 1）。抽不到就跟一次跳转（**超时 2 秒 + 缓存映射**，C11）；仍抽不到回落规范化 URL + 记日志
3. `SELECT workspace WHERE source_type + source_key`
   - **命中** → 拿它的 `subtitle_task`
   - **未命中** → **`INSERT workspace` + `INSERT subtitle_task`（同一事务 = W1）**（生成 `task_no`）
   - **撞 `uk_source`**（两个请求**同时**提交同一个**新** URL）→ **C63**：**不报 409、不重试插入、不回滚重开事务**，在**同一事务**里用**锁定读**（`FOR SHARE`）重查 workspace 拿 `workspace_id`，再用**锁定读**按 `uk_workspace` 查 `subtitle_task`；两行到手后照常进入第 4 步
4. `INSERT task_run`（`run_type=MAIN`，`status=RUNNING`；`started_at` = `now()`）
   - **撞 `uk_active_run`** → 409，响应体带 `{ taskNo, runNo }` 供订阅（C56）
5. **关键词覆盖写（可选）** —— 只有请求带了 `keywords` 才写（`null` = 不动库里那份，`[]` = 写空清单）
6. 返回 **202 + `{ taskNo, runNo }`**

> **第 3 步的并发分支（C63）** —— 为什么是**锁定读**且**两行都要**：RR 一致性快照在本次 `SELECT` 那一刻就建立了（早于对方提交），普通 `SELECT` 读不到对方刚提交的行；`subtitle_task` 若用普通 SELECT 读空，只能去 `INSERT` → 撞 `uk_workspace` → **再撞一次键**。
> **为什么是 `FOR SHARE` 不是 `FOR UPDATE`**：我们只要「绕过快照看到最新已提交行」，不写 workspace 行，共享锁足够。而 duplicate-key 失败会在索引记录上**留下一个 S 锁** → `FOR UPDATE` 要 X 锁，就变成 **S→X 锁升级**：两个并发撞键者互相等 → 死锁。
> **两条落地约束**：① 撞键的 INSERT 与捕获它的 catch 必须在**同一事务方法、同一类内直接调用**，中间**不隔任何 `@Transactional` 边界**（否则 Spring 在代理边界把事务标成 rollback-only，甲-2 静默退化成甲-1）；② **B 的事务里不许放慢操作**（网络 / 文件 IO）。
> **待实测确认**：duplicate-key 失败留下 S 锁来自 MySQL 文档，**本项目尚未实测**。复现要 **3 个并发请求**打同一个全新 URL（2 个复现不出来，只测 2 个会误判 `FOR UPDATE` 安全）。

### 7.2 提交（上传）

同上，只是第 2 步换成「流式写盘边算 sha256」（**免费**拿到内容身份），第 3 步改用 sha256 做 `source_key`。

### 7.3 主管线（MAIN run）

1. 阶段推进 → `UPDATE task_run SET current_stage=?` + 广播 SSE
2. **指纹探测阶段（媒体获取之前，C29 / C47）** —— 阶段内顺序写死：
   1. **探测**：yt-dlp 只取元数据（不下载媒体）→ `duration` / `timestamp`
   2. **比对**：与 `workspace` 记的两列比
   3. **级联**：不一致 → 旧 artifact **和中间产物**全部标 `SUPERSEDED`；随后删文件（C22，**在事务提交之后**）
   4. **写指纹**：`UPDATE workspace SET duration_ms=?, published_at=?`

   - **3 与 4 在同一事务**（W5′，C47）。事务内先后自由 —— 那些行是 `READY → SUPERSEDED`，`active_flag` 都变 NULL，不参与唯一比较
   - 一致 → 3、4 都不做；首跑（两列 `NULL`）→ 只做 4（C48）
   - **取不到指纹 → 跳过比对**，回落「有产物就复用」，记 WARN（C49）。**这是降级路径，不是失败路径** —— 不能把「取不到」当异常抛出去，否则一次元数据风控会把整条 run 判死

   **③ 之后「删文件」删哪些**：

   | 被作废的 | 终态 |
   |---|---|
   | **清空文件列、不删文件** —— `ORIGINAL` / `CLEANED` | 停在 `SUPERSEDED`。理由：新 MAIN run 会重写同名文件；且它们**有「文件列」这个开关**来表达「我不再管这个文件」（C24） |
   | **删文件 → `DELETED`** —— `TRANSLATED(*)` + **中间产物** | 新 MAIN run 不产出译文；中间产物**没有「文件列」可清空**（只有一个 `file_name`，还是唯一键的一部分），不删就会长期挂着「表说作废、文件还在」的行 |

3. `ORIGINAL` 产物：插 `CREATING` → 写文件 → **换版落定**（写文件列 + 哈希列 + `READY`）
4. `CLEANED` 产物：同上，并记 `keywords_fingerprint`

   > **每一步产出之前都要先查表**（C62）—— **复用判据从「文件在不在」改成「表里 `READY` 且文件在」**。
   > 否则指纹级联只动 DB、盘上文件一个没动 → 主管线「重跑」全部跳过 → **产出旧内容而 DB 说是新的，静默交付**。

5. 成功 → `task_run.status = SUCCEEDED`
6. 失败 → **收口统一置 `FAILED`**（C43）；流程内的 catch 只负责丰富 `error_message` / `current_stage`（**不再有中文前缀**）

#### 两条级联的时序差异是**有意为之**（别当 bug 修）

| | 重洗（RECLEAN） | 指纹变了（本节步骤 2） |
|---|---|---|
| 什么时候作废旧的 | **新的 `READY` 那一刻** | **跑主管线之前**（先把旧的全标掉） |
| 期间旧产物 | **整个运行期间仍可下载** | **整个主管线期间该 task 零 `READY`**（前端一片空白） |
| 为什么不一样 | 关键词变了，**内容还是同一个视频** —— 旧版是合理的降级兜底 | **内容变了** —— 旧产物是**别的内容的**，不能当兜底，留着反而是错的 |

### 7.4 重新生成字幕（RECLEAN run）

**前置**：该 task 存在 `READY` 的 `ORIGINAL`。

| # | 步骤 | DB 写什么 | 文件写什么 | 崩在这会怎样 |
|---|---|---|---|---|
| 1 | 校验关键词 | **不写**（超限 → 400，不落库、不重跑） | **不动** | 无 |
| 2 | **抢互斥** | `INSERT task_run (RECLEAN, RUNNING)` —— 撞 `uk_active_run` → 409 | **不动** | 无（还没开跑） |
| 3 | 关键词落库 | `subtitle_keyword` **覆盖写**（`uk_task_id`） | **不动** | 无 |
| 4 | **重摘要 → 重洗** | 只推进 `current_stage` + 广播 SSE | **不动** | ✅ **最常见的失败点**（LLM 报错 / 超时）：旧 `CLEANED` 和它的文件**一点没动**，收口置 `FAILED` 就完了 |
| 5 | 落新产物 | 插 `CREATING`（`revision+1`、`derived_from_artifact_id` = `ORIGINAL` 当前版、`keywords_fingerprint`）—— **只插行，不写文件列** | 写 3 个**临时文件** → **逐个原子替换**到正式路径（同名覆盖 → 旧 `CLEANED` 的文件此刻被顶掉） | 旧内容丢了但 DB 还说它是当前版 → **重跑收敛**。半成品不会留下（原子替换，C50）。残留的 `CREATING` 行由收口置 `FAILED`（C61） |
| 6 | **换版（同一事务 = W5）** | ① 旧 `CLEANED` → `SUPERSEDED` + **清空它的文件列与哈希列**<br>② 所有下游 `TRANSLATED(*)` → `SUPERSEDED`（级联）<br>③ 新 `CLEANED` → `READY` **+ 写 `*_file_name` 与 `*_sha256`** | **不动**（文件在步骤 5 已就位，C51） | 事务回滚 → 状态全是旧的、盘上是新内容 → **重跑收敛** |
| 7 | **删下游译文的文件**（事务外） | 删完再把那些行标 `DELETED` | 删 `translated-segments-*.json` / `translated-subtitles-*.srt` / `terms-*.json` | 只留孤儿文件（C22 明确接受） |
| 8 | **收尾** | `task_run.status = SUCCEEDED` + `subtitle_task.updated_at = now()` | **不动** | —— |

**两种终态要分清**（C23）：

| 谁被取代 | 它有没有自己的文件 | 终态 |
|---|---|---|
| **旧 `CLEANED`** | **没有** —— 文件名不带 `revision`，同名文件此刻已归新行（**覆盖型**） | 停在 `SUPERSEDED`，**不到 `DELETED`** |
| **下游 `TRANSLATED(*)`** | **有** —— 没有任何新版本会去写它们的路径，成了孤儿 | `SUPERSEDED` → 删文件 → `DELETED` |

**不重跑 ASR、不重抽音频。**

### 7.5 翻译（TRANSLATE run）

**前置**：存在 `READY` 的 `CLEANED`；语种在白名单。

| # | 步骤 | DB 写什么 | 文件写什么 | 崩在这会怎样 |
|---|---|---|---|---|
| 1 | 校验 | **不写** | **不动** | 无 |
| 2 | **抢互斥** | `INSERT task_run (TRANSLATE, RUNNING, target_language)` | **不动** | 无 |
| 3 | **复用判据** | **只读**：`TRANSLATED(lang, status=READY)` 存在？**命中 → 直接跳到第 7 步** | **不动** | 无 |
| 4 | **翻译** | 只推进 `current_stage` + 广播 | **不动** | ✅ **最常见的失败点**：旧译文和它的文件一点没动 |
| 5 | 落新产物 | 插 `CREATING`（`revision+1`、`derived_from_artifact_id` = 当前 `CLEANED.id`）—— **只插行，不写文件列** | 写 3 个**临时文件** → **原子替换**（同名 → 旧译文被顶掉） | 同 §7.4 步骤 5 |
| 6 | **换版（同一事务 = W4）** | ① 旧 `TRANSLATED(lang)` → `SUPERSEDED` + 清空文件列与哈希列<br>② 新行 → `READY` **+ 写 `*_file_name` 与 `*_sha256`** | **不动** | 同 §7.4 步骤 6 |
| 7 | **收尾** | `task_run.status = SUCCEEDED` + `subtitle_task.updated_at = now()` | **不动** | —— |

**跟 §7.4 的两个差别**：**没有级联**（译文下面没有别的产物）；**没有「删文件」这一步**（旧译文是**覆盖型**，同名被新译文顶掉，停在 `SUPERSEDED` 就到头）。

**第 3 步判据为什么只认 `status`：**
- **「译文过时」不在这里比，是级联在写入时就标掉了**（§7.4 步骤 6）：新 `CLEANED` 落定那一刻，所有下游译文在**同一事务**里标 `SUPERSEDED`。所以 `READY` 就等于「基于当前版清洗」。
- 判据里**不带** `derived_from_artifact_id`：它只表达「派生自哪一版」，不表达「是不是当前版」。
- **级联也不带它** —— 级联只需要 `WHERE task_id=? AND artifact_type='TRANSLATED' AND status='READY'`，**更简单也更健壮**（不依赖 lineage 被正确维护）。找「旧 `CLEANED`」也是 `status='READY'`（**不能**用 lineage：新 `CLEANED` 行的 `derived_from` 指向 `ORIGINAL`，不是旧 `CLEANED`）。

### 7.6 启动收口（进程启动时，早于接受请求）

```sql
UPDATE task_run          SET status='INTERRUPTED', finished_at=now() WHERE status = 'RUNNING';
UPDATE subtitle_artifact SET status='FAILED'                          WHERE status = 'CREATING';
UPDATE subtitle_task     SET updated_at=now()  WHERE id IN (被上面第一条收掉的那些 task_id);
```

**三条，不是两条** —— 第三条是 C45 要求的（`subtitle_task.updated_at` 的写入点是「每次 run 收尾」，启动收口**也是一次 run 收尾**）。它不违反【恢复路径】的定义（`updated_at` 是 DB 状态列）。

**`intermediate_file` 表在启动收口里无动作** —— 它的三个值没有一个是「进程活着才有意义」的活跃态；`SUPERSEDED` 只是「待办」标记，重启后依然成立，留给对账。启动收口**也不能**去补删 `SUPERSEDED` 的文件 —— 【恢复路径】定义写着「**只写 DB 状态列**，不写文件」，否则「恢复」会变成「隐蔽重试」。

**收掉了几行要打日志** —— 那是「上个进程有 N 个任务没跑完」的唯一痕迹。

**分工**（收口与启动收口各管一格，合起来覆盖四种情况）：

| 情况 | 谁兜 | 结果 |
|---|---|---|
| 执行体退出（正常 / 异常 / `Error` / 早退） | **无条件收口**（C43） | 没到终态 → `FAILED` |
| 真有人在跑 | `uk_active_run` 撞键 | 409，响应体带既有 run 的 taskNo 供订阅 |
| 进程整体没了（被 kill / 掉电） | **启动收口**（C15） | run → `INTERRUPTED`，残留 artifact → `FAILED` |
| 线程卡死（挂住不动） | **靠网络调用超时收敛**（C46） | 超时 → `FAILED` → 重跑 |

> **注意 `INTERRUPTED` 不是崩溃时写的** —— 崩溃时什么代码都跑不了，是下次启动由新进程补记的。
> **残余风险（留档）**：非网络的挂住（如 `destroyForcibly` 后仍不退出的进程）仍没人兜。

### 7.7 抢互斥

插 `RUNNING` run 撞唯一键 → **一律 409**（响应体带既有 run 的 taskNo 供订阅）。**没有「先清掉再抢」这个分支**。
语义为什么变干净了：无条件收口保证「收不了口的行」会被收掉，启动收口保证跨进程残留不会有 —— 于是**撞键 = 真有人在跑**。

### 7.8 产物读取与自愈（C25）

```
查 artifact WHERE task_id=? AND status='READY'
  → 逐个 Files.exists
     不存在 → 当没有 + WARN + 重新生成（不改状态）
```

业务查询（能否翻译、译文是否可复用）一律只认 `status='READY'`。

### 7.9 手工对账（只读，C65）

`POST /api/maintenance/reconcile` —— 全量扫，**四件事全报，② 只报不修**，**不加鉴权**。

| 类 | 报告行带什么 |
|---|---|
| ① 表 `READY` 但文件不在 | **`task_no`** + `file_name` + 问题类别 |
| ② 表 `SUPERSEDED` 但文件还在 | 同上 |
| ③ 盘上有文件、表里没有、且后缀不在 `{.tmp, .part, .ytdl}` | **目录名**（`sanitizeName(source_key)`）+ `file_name` + 问题类别 |
| ④ 表 `READY` 但文件哈希对不上（C64） | **`task_no`** + `file_name` + 问题类别 |

- 用 `task_no` 不用 `task_id` —— 内部自增 id **不出境**。③ 必须带**目录名**（孤儿不在表里，没有 `task_no` 可带）—— **有意的例外**。
- 四类各给**条数 + 明细列表**，不能只回「成功」。
- **幂等**：只读，天然幂等。
- **一条运维边界**：**②③④ 都是「待人工处理清单」，处理之前每次都会重复出现**；**只有 ① 例外**（C25 的自愈会在读路径重新生成，下次自己就没了）。

---

## 8. 文件系统约定

### 8.1 目录

- **目录名 = `sanitizeName(source_key)`**（§9.4j 乙2，**不落库**）。`workspace_dir` 列不存在。
- **读路径公式**（写进文档，否则实现会踩空）：

```
root / sanitizeName(workspace.source_key) / <用途>_file_name
```

**不是** `root / workspace_dir / file_name`。

- **`sanitizeName` 在 `source_key` 的值域上单射**（必须写进 `WorkspaceDir` javadoc）：
  1. `|` 会被换成 `_`，而 B 站 `bvid` 的字符集是 `[0-9A-Za-z]`（**不含 `_`**）、`p` 是纯数字 → `bvid|p` → `bvid_p` 唯一；
  2. 上传路线是 64 位 sha256 十六进制，原样唯一。
- **长度都 ≤ 80**（URL 约 14、sha256 = 64）→ **不会被截断**。这条必须一起写死：`sanitizeName` 的 80 字符截断**会破坏单射**，将来若加更长的 `source_key` 格式，得先解决它。
- ⚠️ **`source_key` 含 `|`**（Windows 禁用字符），而 `WorkspaceDir.of(label, key)` 只对 label 走 `sanitizeName`、**key 原样拼** → **必须对 key 也走 `sanitizeName`**，过了之后正好得到 `BV11QMd6WEJp_p1`。

### 8.2 文件与 DB 的写序纪律（C51）

- **写产物** → **先写文件（临时 + 原子替换）、后写 DB 登记**
- **删产物** → **先标 DB、后删文件**（C22）

写反了（先写 DB）崩溃后 DB 说新版、盘上是旧文件 → 读路径拿到旧内容而 DB 说是新版，**永久错**。
按这个顺序，最坏的中间态是「盘上是新内容、DB 说旧版」—— 内容完整、重跑收敛。
**顺序纪律不能替代事务，它只是让「不需要事务」变得安全。**

### 8.3 原子替换（C50）

**所有产物与中间产物都按「临时文件 → 原子替换」产出**（`Files.move(tmp, target, ATOMIC_MOVE)`）。

理由：崩在写文件中途会留下**半成品**，而「断点复用」的判据是「文件在不在」→ 半成品会被当成成品复用，**这是唯一一类不能靠重跑收敛的错误**（重跑会跳过它）。原子替换之后「文件存在」就等于「文件完整」。

### 8.4 残留窗口三类全接受（C64）

**别让后人当 bug 修。** 这是买 C24（文件名不带 revision、物理上每 `(type, lang)` 只留一份）付的钱。

| 类 | 最坏后果 | 会不会被自动发现 | 靠什么收敛 |
|---|---|---|---|
| ① 文件已替换、DB 事务未提交 | 读路径拿到**新内容**、DB 记旧版；**新内容比 DB 记的更对** | 由 ② 的哈希比对覆盖 | 重跑 / 下次命中复用自动纠正 |
| ② 同代多文件**不同源** | 崩在逐个原子替换的中途，三个文件可能不同源（但**每个文件本身完整合法**） | **本次加检测**：四列 sha256 与「换版落定」同一条 UPDATE 写；对账比对哈希，不一致报「需人工重跑」 | 人工重跑 |
| ③ 孤儿文件 | 盘上留着没人引用的文件 | 对账 ③ 报出来 | 人工回收 |

**①② 是同一个事件的两端**（都是「文件已替换、DB 事务未提交」，区别只在替换了几个文件）—— 所以哈希比对**同时覆盖 ① 和 ②**。

**哈希列的三条纪律**：
1. **列数镜像四个文件列**（`context` 与 `terms` 不能挤一列）；
2. **跟着文件列同生同灭** —— 凡是 SET 文件列的地方都同时 SET 哈希列，否则留下「有哈希、没文件」的行让对账误报；
3. **写入点 = 换版落定那条 UPDATE** —— 写序纪律（§8.2）保证「先落盘、后写 DB」，所以此刻算的哈希就是**成品文件**的哈希。

**放弃的是主流第 ④ 招（内容寻址 / 不可变命名）与第 ⑤ 招（原子换目录）**，理由就是 C24。

> **留档：`manifest` 清单 = ② 的根治方向** —— 它是第 ⑤ 招「原子换指针」的变体（清单本身就是那个指针，一次原子替换即让整代文件同时生效）。**本次不做**，但方向已识别。

### 8.5 本次不做版本回退（C52）

位置已经留好：`revision` 列已在、**文件路径是 DB 的一列**，将来加版号只改「产出时写什么路径 + 换版时别清空旧行、别删旧文件」，读路径与表结构都不用动。
普通失败（LLM 报错/超时）发生在**算的阶段**，那时文件根本没被碰 —— 所以「备份」唯一能多救的是**反悔**，那是产品功能，不是恢复机制。

---

## 9. 退场清单

| 退场 | 被谁取代 |
|---|---|
| `VideoTask`（13 字段 + 13 方法，三合一压在一行） | `Workspace` / `SubtitleTask` / `TaskRun` 三个实体 |
| `TaskSessionRegistry`（内存登记表，同时是锁又是状态源） | `uk_active_run`（C14） |
| 三个中文失败前缀常量 + `recordRegenerateFailure` / `clearRegenerateFailure` / `recordTranslationFailure` / `clearTranslationFailure` | `run_type` 说明来源（C3） |
| `requireMainSucceededForPostLifecycle` | 前置条件从「查主状态」变成「查有没有 `READY` 的 `ORIGINAL` / `CLEANED`」 |
| `ACTIVE_STATUSES` / `rejectIfActive` | 撞 `uk_active_run`（C14） |
| `latest_clean_artifact_id` / `latest_translate_map` | 「查一句就有」的镜像，砍（C5） |
| `task_stage`（表） | 范围外的能力（C5） |
| `TaskConflictResponse.existingTask` | C56 |
| `TaskStageEvent` | 「连接建立即推 DB 快照」（C57 的兜底） |
| `WorkspaceKey` / `WorkspaceDir.of(label,key)` | `source_key` + `sanitizeName` |
| `task` 表 | 六张新表 |
| `temp_file`（那个被砍的旧名字） | `intermediate_file`（**不沿用旧名**，N3） |
| `SubtitleArtifact`（record）/ `AudioArtifact`（record） | 改名让名（N1） |

**要保留的**：`SemaphoreTaskConcurrencyGate`（限流 ≠ 互斥，C14 / §9.4g）、`SubtitleKeywords` 值对象（只换锚点）、`SupportedLanguage` 白名单、外部进程的强制超时（`ExternalProcessRunner` 早就要求正数超时，默认 300s）。

**周边跟着动**：`TaskStatus` → `TaskRunStatus`（+ `INTERRUPTED`）；`TaskInputType` → `SourceType` 挪去 `workspace` 侧；`TaskNo` 不动、只换锚点；`VideoTaskRepository` 拆成 5 个 port。

**注意**：`subtitle_artifact` **不是从 `VideoTask` 分出来的，是新长出来的** —— 现状产物是「文件系统即事实」（扫目录 + 白名单过滤），DB 里零记录。

---

## 10. 分段落点

见 [prd.md](./prd.md) 的任务地图（8 段，顺序即依赖）。
每段的 `implement.md` 必须写明：段内顺序、验证命令、回滚点、以及**本段结束时哪些东西还是「欠账」**（交给后面哪一段）。
