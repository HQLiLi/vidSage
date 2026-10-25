# 父任务集成评审轮：执行计划（AC1–AC9）

> **本轮性质**：8 段（S1–S8 + S6.5）已全部归档，父任务剩下的唯一实质工作是
> [prd.md](./prd.md#L127) 的**跨子验收标准**。本文件只覆盖这一轮。
>
> **不写生产代码。** 评审发现缺陷 → 停下、记进 §6 的「发现」表、回 Phase 2 另开修复段，
> 不在本轮顺手改（顺手改 = 评审结论与被评对象同时变，等于没评）。
>
> 技术终态在 [design.md](./design.md)；跨子 AC 原文在 [prd.md](./prd.md)。
> 本文件是执行参考稿，不是契约；与 design / 代码冲突时以它们为准并回来改这里。

---

## 0. 现场基线（2026-10-20 实测）

| 项 | 实测值 |
|---|---|
| MySQL | `127.0.0.1:3306` 在跑（`vidsage` 库） |
| 后端 | **未启动**（8080 无监听） |
| 六张表 | 齐备：`workspace` / `subtitle_task` / `task_run` / `subtitle_artifact` / `subtitle_keyword` / `intermediate_file` |
| 行数 | workspace 15 / subtitle_task 15 / task_run 61 / subtitle_artifact 51 / intermediate_file 8 / subtitle_keyword 3 |
| `backend/data/works/` | 3 个目录：`9466c90f…`（UPLOAD 路线）/ `BV11QMd6WEJp_1`（task 1）/ `BV1Va7V66Ebp_1`（task 57） |
| 关键 workspace | task 1 = `BV11QMd6WEJp_1`，ORIGINAL rev20 `READY` + CLEANED rev16 `READY`；task 57 = `BV1Va7V66Ebp_1`，ORIGINAL rev3 + CLEANED rev4 均 `READY` |
| 残留活跃态 | run 124 `TRANSLATE INTERRUPTED`（S8 时代留下，可作为 AC5 的旁证但不是证据） |
| API key | `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 已在 shell 环境；`MIMO_API_KEY` 空（`spring.ai.model.chat=deepseek`，不影响） |
| 本地方言 | `application-local.yml` 的 `subtitle.translate.target-language: ja` |
| 测试基线 | 开工前实测见 §4 AC9（`mvn clean test`） |

**环境命令**（出处 [handoff.md §10](./handoff.md)）：

```bash
# 判编译：一律 clean（增量编译给假绿）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 全量测试：量前先删 surefire-reports（会留陈旧报告）
cd backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q clean test

# 起服务：测试红着时必须跳过测试编译（spring-boot:run 绑 test-compile，-DskipTests 不够）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q spring-boot:run

# 收工：TaskStop 只杀 mvn 包装进程，子 JVM 继续占端口 → 必须按 PID 杀
netstat -ano | grep :8080     # 取 PID
taskkill //F //PID <pid>

# 查库
mysql -h127.0.0.1 -uroot -pzwl61758157 vidsage --default-character-set=utf8mb4 -e "..."
```

> **退出码会被管道吃掉**：`mvn … | tail -60` 回显的是 `tail` 的退出码。判绿一律看 `[ERROR]` 段与 `BUILD SUCCESS/FAILURE`。

---

## 1. 执行顺序与理由

> **重置已裁定为「先做」**（2026-10-20，项目所有者选 A：清库 + 清盘）。
> 原始草案把「从零重建」放在最后，是为了保住 S2–S8 的现场证据；既然裁定全量重置、
> 且那些证据在 9 个已归档的子任务工件里已有完整记录，**重置前置**更优 ——
> 之后每条 AC 都在干净态上跑，AC7「正常态零误报」与 AC4「全新 URL」自然成立。

```
0  基线确认（测试 700 绿 ✓）+ 现场快照留档 + 全量重置            [破坏，已裁定 A]
1  AC1 表与口径 + AC2 旧件零残留（纯 SQL / grep）                [非破坏]
2  AC8 契约（起服务，纯读）                                      [非破坏]
3  AC3 三种 run 跑通（真 ASR + 真 LLM，用 BV1Va7V66Ebp）         [新增行]
4  AC4 并发（用 BV11QMd6WEJp —— 重置后它天然是全新 URL）          [新增行]
5  AC5 崩溃恢复（kill + 重启）                                   [只动状态列]
6  AC6 内容变更（改 duration_ms）                                [作废旧版，不可逆]
7  AC7 对账（手工造四类不一致 + 复原）                            [可复原]
8  AC9 全量回归 + 完整流程（「从零」已由步骤 0 满足）              [非破坏]
```

**理由**

- **重置先做**：它是全轮唯一不可逆的一步，前置之后所有后续步骤都能在确定的起点上重跑；
  若放最后，中间任何一条 AC 失败都会让「到底该不该重置」重新变成悬案。
- **AC1 / AC2 紧跟重置**：S8 的收尾报告（AC11）已收过这两条的证据，本轮是复核 —— 成本近零，
  且不依赖服务与数据。先做能把「地基对不对」这个前置问题关掉。
- **AC8 在 AC3 之前**：契约是后面所有手工验收的**工具**（要读 `/artifacts` 才知道产物状态）。
  先验契约，后面的 AC 才有可信的观测面。
- **AC3 在 AC4/AC5/AC6 之前**：三条 run_type 能跑通是所有后续构造的前提。
- **AC4 用另一个 BV 号**：重置后 `BV1Va7V66Ebp` 与 `BV11QMd6WEJp` 都是全新的，
  两条 AC 各用一个，互不干扰（原草案的「删掉一行造全新 URL」因此不再需要）。
- **AC7 放在 AC3–AC6 之后**：那几条跑完正好留下「一个跑过完整流程的工作区」，
  直接就是 AC7 第 7 条「正常态零误报」的观测面。

---

## 2. 数据处置（**已裁定：A. 全量重置**）

**裁定（2026-10-20，项目所有者）**：清库 + 清盘，之后全轮在干净态上跑。

| AC | 动作 | 影响面 | 可复原？ |
|---|---|---|---|
| AC3 | 用 `BV1Va7V66Ebp` 跑 MAIN / RECLEAN / TRANSLATE | 该 task 的 artifact / intermediate_file / task_run 新增行；文件被覆盖 | 不需要复原（新增版本，旧版本本来就作废） |
| AC4 | 用 `BV11QMd6WEJp` 3 并发提交（重置后天然是全新 URL） | 新增 workspace / task / run 行 | 不需要复原 |
| AC5 | kill 进程 + 重启 | 残留 run → `INTERRUPTED`、残留 artifact → `FAILED` | 是（状态列，本来就该被收口） |
| AC6 | 改 `workspace.duration_ms` 造内容变更 | 该 task 全部旧 artifact → `SUPERSEDED`、中间产物作废、真重跑 | **否** —— C24 作废时清空了文件列，改回 `duration_ms` 也回不去 |
| AC9 | 「从零重建」已由步骤 0 的重置满足 | — | — |

**重置的具体动作**（步骤 0 的下半段）：

```bash
# 1) 快照（先做，见 §5）—— mysqldump 全库 + works 改名备份
# 2) 清库：TRUNCATE 六张表（顺序无所谓，无 DB 外键约束；但保留表结构与生成列）
# 3) 清盘：rm -rf backend/data/works/*
# 4) 复核：SHOW TABLES 仍六张；SELECT COUNT(*) 全 0；ls backend/data/works 为空
```

> **不 DROP TABLE**：`schema.sql` 是 `CREATE TABLE IF NOT EXISTS`，DROP 之后再起服务虽然会重建，
> 但会丢掉「表结构是服务自己建出来的」这条验证 —— 而 AC1 正是要验它建出来的结构对不对。
> 所以**只清数据、留结构**。
>
> **`works/` 用改名备份不用 `rm`**：`mv backend/data/works backend/data/works.bak-2026-10-20`，
> 再 `mkdir` 空目录。备份目录不在 `works/` 下，不会被应用的目录扫描扫到。

---

## 3. 逐条执行清单

### AC1 表与口径

| # | 判据 | 命令 / 手段 | 期望 |
|---|---|---|---|
| 1 | 六张表齐备 | `SHOW TABLES` | 恰好六张（`subtitle_keyword` 在内） |
| 2 | 索引 / 约束名符合 `N4` | `SHOW CREATE TABLE` ×6，抄出全部索引名 | 一律 `uk_` / `idx_` 前缀且带表名段（如 `uk_active_run`） |
| 3 | 枚举列一律 `VARCHAR(32)` | `SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='vidsage' AND COLUMN_NAME IN ('status','run_type','artifact_type','source_type')` | 全是 `varchar(32)` |
| 4 | 无 DB 外键约束 | `SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='vidsage' AND CONSTRAINT_TYPE='FOREIGN KEY'` | `0` |
| 5 | `schema.sql` 头部写明存量迁移步骤 | 读 `backend/src/main/resources/schema.sql` 头部 | 写明 `subtitle_keyword` 必须手工 DROP 一次 |

> S8 的 AC11 已报过这五条的结论（PASS）。本轮是**复核**，判据照抄，不重新定义。

### AC2 旧件零残留

清单出处 [prd.md AC2](./prd.md#L130)。逐项 grep，**范围限 `backend/src/`**（归档与 journal 里有旧名史实，不能全仓扫）：

| 旧件 | 判据 |
|---|---|
| `VideoTask` | `backend/src` 零命中 |
| `TaskSessionRegistry` | 零命中 |
| 三个中文失败前缀常量 + 两个「按前缀认领」的清除方法 | 零命中 |
| `latest_*` 指针类字段 | 零命中 |
| `TaskConflictResponse.existingTask` | 零命中 |
| `WorkspaceKey` | 零命中 |
| `TaskStatus` | 只剩阿里 SDK 同名枚举（`com.alibaba.dashscope.common.TaskStatus`，4 处，**正常**）；项目自己的枚举已叫 `TaskRunStatus` |

### AC3 三种 run 跑通

在选定 workspace 上依次跑，**每跑一次立刻查库**：

```bash
# ① MAIN：全新提交（或复用命中后触发真跑）
curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
     -d '{"url":"https://www.bilibili.com/video/BV1Va7V66Ebp"}' -i
# ② RECLEAN
curl -s -X POST localhost:8080/api/tasks/$T/subtitles/regenerate -H 'Content-Type: application/json' -d '{"keywords":["影视飓风"]}' -i
# ③ TRANSLATE（本地 target-language=ja）
curl -s -X POST localhost:8080/api/tasks/$T/translate -H 'Content-Type: application/json' \
     -d '{"sourceLanguage":"zh","targetLanguage":"ja"}' -i
```

| 判据 | 期望 |
|---|---|
| 三条 run 各落一行 `task_run`，`run_type` 分别为 `MAIN` / `RECLEAN` / `TRANSLATE` | `SELECT run_no, run_type, status, target_language FROM task_run WHERE task_id=? ORDER BY run_no` |
| 三者终态 `SUCCEEDED` | 同上 |
| **失败原因互不串台** | 故意让 TRANSLATE 失败一次（如传不支持的 targetLanguage → 400 不插 run；或断网），确认 `error_message` 只落在那条 run 上，MAIN 的 `SUCCEEDED` 不被盖掉 |

> 真 ASR（DashScope）与真 LLM（DeepSeek）都要走，是本条的核心 —— 不许用已有 run 记录代替。

### AC4 并发

| # | 判据 | 手段 |
|---|---|---|
| 1 | 同一**全新** URL 并发提交收敛到同一条 `subtitle_task`，**不报 409** | 先让该 URL 在库里变「全新」（见 §2 的数据处置），再 `for i in 1 2 3; do curl … & done; wait` —— 要 **3 个**并发（2 个复现不出 duplicate-key 留 S 锁，见 design §525 的「待实测确认」） |
| 2 | 并发后 `workspace` 只有一行、`subtitle_task` 只有一行 | SQL 计数 |
| 3 | 同一 task 并发触发 → 一方 409，**响应体带 `taskNo`** | 同 task 上并发两个 `regenerate`，`-i` 看状态码与 body |
| 4 | 互斥不泄漏 | `SELECT * FROM task_run WHERE active_flag IS NOT NULL` 在全部 run 终态后应为空 |

> 第 1 条同时是 design §525 那条「**待实测确认**」的收口机会：3 并发若真留下 S 锁，
> 本轮会实测到，据此把 design 的「尚未实测」改掉。

### AC5 崩溃恢复

| # | 判据 | 手段 |
|---|---|---|
| 1 | kill 进程后重启 → 残留 run 变 `INTERRUPTED` | 起一条长 run（MAIN 或 TRANSLATE），跑到中途 `taskkill //F //PID <pid>`，重启服务，查 `task_run` |
| 2 | 残留 artifact 变 `FAILED` | `SELECT id, artifact_type, status FROM subtitle_artifact WHERE status='CREATING'` → 重启后应无 `CREATING` |
| 3 | 被收掉的 task `updated_at` 更新 | 重启前后各查一次 `subtitle_task.updated_at` |

### AC6 内容变更

| # | 判据 | 手段 |
|---|---|---|
| 1 | 改内容重提交 → 旧 artifact **全** `SUPERSEDED` | `UPDATE workspace SET duration_ms = duration_ms + 1000 WHERE id=?` → 重提交 → 查 artifact |
| 2 | 中间产物全作废 | `SELECT file_name, status FROM intermediate_file WHERE task_id=?` |
| 3 | 指纹写入 | `workspace.duration_ms` / `timestamp` 被回写为真值 |
| 4 | **主管线真的重跑**（不再跳过转写） | 日志出现「内容已变化，旧产物已作废」+「获取媒体 / 抽取音频 / 转写 / 清洗」全走 |

### AC7 对账

端点 `POST /api/maintenance/reconcile`（只读，同步返回）。

| # | 判据 | 手段 |
|---|---|---|
| 1 | ① 报得出来 | 手工删一个 `READY` 行登记的文件 → `danglingFiles` 含该 `taskNo` + `fileName`；放回后消失 |
| 2 | ② 报得出来（`SUPERSEDED` / `DELETED` 残留） | 手工造「`SUPERSEDED` 且文件列有值、文件在盘上」与「`DELETED` 且文件还在盘上」各一行 → `reclaimPending` |
| 3 | ③ 报得出来 | 塞一个后缀不在白名单的文件（表里无登记）→ `orphanFiles` 含**目录名** + `fileName` |
| 4 | ④ 报得出来 | 改掉某 `READY` 行登记文件的内容 → `hashMismatch` 含 `taskNo` + `fileName` |
| 5 | ② **只报不修** | 调用前后 `subtitle_artifact` / `intermediate_file` / `subtitle_task` / `workspace` 逐行不变；文件 mtime 不变 |
| 6 | 只读幂等 | 连调两次，报告逐字相同 |
| 7 | 正常态零误报 | 干净工作区跑完一次完整流程 → 四类全 0 |
| 8 | ③ 不误报临时文件 | `.tmp` / `.part` / `.ytdl` 后缀三类都不报 |

> 第 7 条必须在 §2 选 A（全量重置）之后的干净态上做；选 B 时库里有一堆指向空气的行，做不了。

### AC8 契约

| # | 判据 | 手段 |
|---|---|---|
| 1 | `GET /api/tasks/{taskNo}` **只有身份 + `lastRun`** | 逐字看 JSON：**无**扁平 `status` / `currentStage` / `errorMessage` / `startedAt` / `finishedAt`，**无** `artifacts` 字段 |
| 2 | `/artifacts` 只有 `READY` | 库里造一条非 `READY` 的同类型行，确认不泄露；顺序 ORIGINAL → CLEANED → TRANSLATED；CLEANED 的 `keywordsFingerprint` == `/keywords` 的 `fingerprint` |
| 3 | 三个 POST 统一 202/409 `{taskNo, runNo}` | 202 与 409 **同形状**，409 体不含文案、不含 `existingTask` |
| 4 | 下载端点按 `type` + `file` + `?lang=` | 未知 `type` / 未知 `file` / `context.json` 用在 ORIGINAL 上 / TRANSLATED 缺 `lang` → **400**；未知 taskNo、无 `READY`、文件被删 → **404** |
| 5 | SSE 一条 run 一条流 | `curl -N localhost:8080/api/tasks/$T/runs/$R/events` —— **提交与订阅必须在同一个 shell 里**（先后台提交、拿 runNo、立刻订阅，否则流已关）；runNo 不存在 → 404；run 已终态 → 204；事件带 `runNo` / `runType` / `targetLanguage` |

### AC9 回归

| # | 判据 | 手段 |
|---|---|---|
| 1 | 全量测试绿 | `rm -rf target/surefire-reports && mvn clean test`，记条数（开工基线 **700 / 0 / 0 / 1**，比它**只增不减、零失败**） |
| 2 | `clean test-compile` 过 | 见 §0 命令 |
| 3 | 后端能起服务 | `spring-boot:run`，看 `Started` 行 |
| 4 | `backend/data/works*` **从零重建**后跑通一次完整流程 | 「从零」已由步骤 0 满足（库空 + 盘空）→ 提交 → 等完成 → `/artifacts` → 下载 → 翻译 → 重新生成 |

> 第 4 条在重置**之后**的任意时点都成立（起点就是零）；推荐放在 AC7 之后，
> 用同一趟流程同时给 AC7 第 7 条（正常态零误报）当观测面。

---

## 4. 回滚点

| 步骤 | 回滚方式 |
|---|---|
| **步骤 0 全量重置** | **不可逆**（本轮唯一）。先 `mysqldump` 全库 + `mv works works.bak-<ts>`；要回退就停服、`mysql < dump`、把备份目录改回 `works/` |
| AC5 kill | 无需回滚（收口本来就是目标状态） |
| AC6 改 `duration_ms` | `UPDATE workspace SET duration_ms = <原值>` 能改回列值，但旧 artifact 的 `SUPERSEDED` **不可逆**（C24 作废时清空了文件列） |
| AC7 手工造的不一致 | 逐条复原（删掉的文件放回、塞进去的孤儿删掉、改过的文件内容还原） |
| 服务起不来 | `taskkill //F //PID <pid>`，重来 |

---

## 5. 动手前的现场快照（必做）

```bash
SNAP=.trellis/tasks/10-15-subtitle-task-model-refactor/evidence/2026-10-20
mkdir -p $SNAP
mysqldump -h127.0.0.1 -uroot -pzwl61758157 --default-character-set=utf8mb4 \
          --databases vidsage > $SNAP/vidsage-before.sql
mysql … -e "SELECT * FROM task_run; SELECT * FROM subtitle_artifact; …" > $SNAP/before-tables.txt
ls -la backend/data/works/*/ > $SNAP/before-works.txt
```

> `mysqldump` 必须带 `--default-character-set=utf8mb4`，否则中文列注释静默乱码
> （终端显示乱码 ≠ 库里乱码，别拿肉眼当判据）。

---

## 6. 本轮的产出

1. **证据目录** `evidence/2026-10-20/`：before dump、每条 AC 的命令与原始输出、关键 SQL 结果。
2. **父 `prd.md` 的 AC1–AC9 回勾**：PASS 的勾上并在括号里写证据位置；FAIL 的**不勾**，
   转 §6.3。
3. **发现表**（本轮新增，落在本文件末尾）：评审发现的缺陷 → 各自记「现象 / 复现 / 归属哪一段 /
   建议动作」，**不在本轮修**。
4. **design / handoff 的订正**：本轮实测到的与文档不符处（尤其 design §525 那条「待实测确认」）。
5. **journal 一轮** + 父任务归档（`set-meta` + `--skip-branch-validation`，父任务无分支）。

---

## 7. 欠账（不在本轮范围）

| # | 欠账 | 归属 |
|---|---|---|
| 1 | 前端接接口（C60） | 另开任务 |
| 2 | `SubtitleCleanupCoordinator` 一个类扛三种 run | 未排期（10-20 欠账 #4）；交接细节在该任务 `implement.md` 末尾 |
| 3 | `.trellis/spec/frontend/` 旧名清理 | S8 决策 D6 记的待办，随 C60 一起做 |
| 4 | 版本回退（C52）/ 删除功能（C38）/ 多实例（C39）/ 全站鉴权 | 各按原决策不做 |
| 5 | `dev` 领先 `origin/dev` 16 笔未推送 | 归档前确认是否推（走代理） |

---

## 8. 发现表（本轮填）

> 评审发现的问题记这里，**本轮不修**。格式：现象 / 复现 / 期望 vs 实际 / 归属 / 建议动作。

### F1 · 翻译侧 LLM 全不可用时「静默交付未翻译内容」

| 项 | 内容 |
|---|---|
| **现象** | LLM 完全不可达时，TRANSLATE run 仍 **SUCCEEDED**、artifact 仍 **READY**、`error_message` 为 NULL，而产物与源文**逐字节相同**（整份 0% 被翻译）。用户要繁体中文，拿到的是简体，系统报「成功」。 |
| **复现** | 以 `--spring.ai.deepseek.base-url=http://127.0.0.1:9/` 起服务 → 对某 task 触发一个**没翻过**的语种 → `diff clean-subtitles.srt translated-subtitles-<lang>.srt` 无输出；日志见 `DEBUG LlmSubtitleTranslator: 字幕翻译批次重试用尽，整批回退原文`。证据：`evidence/2026-10-20/ac3-three-runs.txt` §3。 |
| **期望** | 兜底本身是有意的（类注释：「宁可没译，不可乱译」），但**必须浮到用户可见面**。作者意图也已写明「静默产出未翻译的行是本条最需要防的事，所以让它可观测」。 |
| **实际** | 可观测面只有 `SubtitleTranslationCoordinator:163` 的 **`log.debug`**（生产 INFO 级别不输出）与 `TranslatedSubtitle.untranslatedCount` —— 后者**全仓没有任何读取方**。用户可见面全是成功信号。 |
| **归属** | **不是本次重构引入**（`LlmSubtitleTranslator` 的兜底早于本任务，8 段未改其语义）。但新模型把产物权威搬进 DB 后，这一行在库里与正常译文**完全同形**（READY / 有文件 / 有 sha256），比旧模型更难被发现。 |
| **建议动作** | 另开小修：把 `untranslatedCount > 0` 提升为**可观测的降级标记** —— 至少 `log.warn`；理想是落进 artifact 行或 run 的 `error_message`（语义上更像「部分降级」而非失败），并在 `/artifacts` 里可见。**需项目所有者先定口径**（降级算成功还是算失败）。 |

### F2 ·（观察，非缺陷）`revision` 会被 `FAILED` 占位行消耗

| 项 | 内容 |
|---|---|
| **现象** | task 6 上 `CLEANED` 出现过 `rev1 = FAILED`（被 kill 的残留占位）、`rev2 = READY`。即「第 1 版」从未有过内容，可见的第一版是 `rev2`。 |
| **判据** | design §1.4 明写 `revision` = 「同 (task,type,lang) 递增（**MAX+1**）」，与状态无关。**所以这是照设计实现的，不是缺陷**。 |
| **为什么要记** | 与 S6.5 定的「`revision` 语义回归**内容第几版**」在字面上有一点张力：`FAILED` 占位行没有内容，却占掉一个版本号。`/artifacts` 只暴露 `READY`，所以前端可能看到版本号跳号（如只有 2、3 而没有 1）。 |
| **建议动作** | 不改（改动会牵连唯一键与换版顺序）。仅确认前端展示「第 N 版」时不做连续性假设。 |

---

## 9. 评审结论（2026-10-20）

**AC1–AC9 全部 PASS。** 逐条实测量与证据文件：

| AC | 结论 | 证据 |
|---|---|---|
| AC1 表与口径 | PASS | `evidence/2026-10-20/ac1-ac2.txt` |
| AC2 旧件零残留 | PASS | 同上 |
| AC3 三种 run 跑通 | PASS | `ac3-three-runs.txt`（同 task 不串台见 `ac5-crash-recovery.txt` §4） |
| AC4 并发 | PASS | `ac4-concurrency.txt` |
| AC5 崩溃恢复 | PASS | `ac5-crash-recovery.txt` |
| AC6 内容变更 | PASS | `ac6-content-change.txt` |
| AC7 对账 | PASS | `ac7-reconcile.txt` |
| AC8 契约 | PASS | `ac8-contract.txt` |
| AC9 回归 | PASS | `ac9-regression.txt` |

**本轮顺带关掉的两条文档欠账**（都已按订正惯例回写）：

1. **design §7.1「duplicate-key 留 S 锁、本项目尚未实测」** —— 本轮 3 并发打全新 URL 把它测了：
   C63 的「撞键 → 锁定读重查」在 **workspace 与 subtitle_task 两层**都真实走到，无死锁。
   `design.md` 已按现状重写，`handoff.md` 加了带日期的订正注。
2. **父 prd 需求 4 / AC4 的「不报 409」** —— 该措辞与 design §7.1 第 4 步冲突（S2 已于 2026-10-18
   订正并实测，本轮独立复现 1×202 + 2×409）。已在 prd 上按惯例加删除线 + 日期。

**发现**：F1（翻译静默降级，真缺陷、非本次引入）、F2（`revision` 被 FAILED 占位消耗，观察项）。
**两条都不在本轮修** —— 评审结论与被评对象不能同时变。

**未推送**：`dev` 仍领先 `origin/dev` 16 笔（项目所有者裁定「等评审结束一起推」）。
本轮只改任务工件与文档，未改生产代码，故不产生新的代码提交。

**收工状态**：服务已停（8080 无监听）；`backend/data/works.bak-2026-10-20`（40 MB）**保留**，
它是步骤 0 全量重置的回滚路径，确认无误后再删。
