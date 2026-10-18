# 字幕任务数据模型重构（父任务）

> **本任务不直接实施。** 它拥有源需求集、任务地图、跨子验收标准与最终集成评审。
> 技术终态设计在 [design.md](./design.md)；每段的具体做法写在各子任务的 `implement.md`。
> 讨论共识全文在 [handoff.md](./handoff.md)（**它记录了"为什么这么定"，不是最终设计**）。

## Goal

把现状「一张 `task` 表扛三种生命周期 + 产物事实在文件系统里」重构成六张表：
`workspace`（媒体身份）/ `subtitle_task`（字幕任务）/ `task_run`（每次执行）/ `subtitle_artifact`（产物版本）/ `intermediate_file`（中间产物缓存）/ `subtitle_keyword`（关键词，保留）。

一次做完，分 8 段递进实施；**不保旧 API**（前端另开任务接接口）。

## 背景：六个问题，六条需求

每条都有代码实证，出处见 handoff §1 / §1.3。

| # | 现状问题 | 实证 | 需求 |
|---|---|---|---|
| R1 | **粒度错位**：真实资源是「工作区」（目录 + key），DB 的行却是「一次提交」；同一个工作区可以有多行 `task` | `schema.sql` 里 `workspace_key` 无唯一约束；`rejectIfActive` 只拦 PENDING/RUNNING | 媒体身份独立成实体，按**源 ID**（B 站 `bvid`，上传取 sha256）唯一，与字幕任务 1:1 拆开 |
| R1′ | **身份键是 URL 原文哈希、零规范化** → 同一视频因跟踪参数重复下载 / 重复 ASR | `backend/data/works-before/` 里同一个 BV 号有 2 个目录，差异全在 `spm_id_from` / `vd_source` / `trackid` | 规范化剥跟踪参数、`/video/` 段取 `BV...`、短链跟一次跳转 |
| R2 | **三种生命周期压在一行**：主管线 / 重洗 / 翻译共用 `task` 行，`status` 只有主管线那台状态机；在途状态只能进内存表 | `TaskSessionRegistry` 不落库、不跨重启；三种失败共用 `error_message`，靠中文前缀字符串区分 | 每次执行落一条 `task_run`（`MAIN`/`RECLEAN`/`TRANSLATE`），状态、阶段、失败原因归 run |
| R3 | **产物「文件系统即事实」**：清单靠扫目录 + 白名单过滤，DB 里零记录 | `VideoTaskService.scanArtifacts` 的 `.filter(SubtitleArtifactSpec::isDownloadable)` | 产物按**逻辑版本**进 `subtitle_artifact`，DB 是权威、文件是载体；`READY` 是唯一有效态 |
| R4 | **中间产物零登记**，复用判据是「文件在不在」 | `SubtitlePreparationCoordinator` 原话「已有 segments 产物，跳过转写」 | 中间产物进 `intermediate_file` 登记，复用判据改成「表里 `READY` 且文件在」 |
| R5 | **互斥有两套**：DB `rejectIfActive` + 内存 `tryStart`，两套都是锁又是状态源 | — | 互斥单源 = DB 生成列 + 唯一索引（`uk_active_run`），内存登记表整块退场 |
| R6 | **内容变更无判据**：换了源但同一个 bvid，旧产物会被当当前版用 | — | 内容指纹 = `duration` + `timestamp`（来自 yt-dlp 元数据），变了则级联作废旧产物 |
| R7 | **主管线 `RUNNING` 落库但无任何启动收口**，进程被 kill 后那行永远 RUNNING | 全仓 grep `CommandLineRunner`/`ApplicationRunner`/`@PostConstruct` 为空 | 补一次启动收口，把残留 `RUNNING` 收成 `INTERRUPTED` |
| R8 | **提交不拦后置会话**：翻译正在读 `clean-segments` 时，新提交的主管线 clean 会把它重写掉 | `submitUrl` 只走 `rejectIfActive`（查 DB 的 PENDING/RUNNING），而后置会话期间主状态已是 SUCCEEDED | 三种 run 共用一条互斥（`uk_active_run`），撞键一律 409 |

## Requirements

**功能性**

1. 六张表齐备，口径一致（命名规则、枚举列类型、外键走逻辑外键）。
2. 三条 run_type（`MAIN` / `RECLEAN` / `TRANSLATE`）都能跑通，各自的状态与失败原因互不干扰。
3. 提交一个做过的 URL **不冲突**：命中 `workspace` → 拿它的 `subtitle_task` → 插新 `MAIN` run（可回退主状态）。
4. 并发提交同一个**新** URL 收敛到同一条 `subtitle_task`，不报 409。
5. 互斥、`run_no`、`revision` 的并发正确性由 DB 唯一键兜住，不靠内存或重试。
6. 产物读路径只认 `READY`；文件不在时**不改状态**、当没有并重新生成。
7. 中间产物复用判据含表登记，内容变更后主管线**真的重跑**。
8. 内容指纹变了 → 旧 artifact 与中间产物全部作废，并跑一条新 `MAIN` run。
9. 进程重启后无残留 `RUNNING`：启动收口把它收成 `INTERRUPTED`，残留 `CREATING` 收成 `FAILED`。
10. 提供只读对账端点，能报出表/盘不一致的四种情况。
11. 网络调用（LLM / ASR / 术语查证）有超时，与外部进程同口径。

**非功能性**

12. **每段验收线**：`clean test-compile` 过 + 全量测试绿 + 后端能起服务 + 能用 HTTP 手动验证（前端点不通是预期内的）。
13. 脱敏红线不变：`source_uri`（URL 原文 / 上传原始文件名）**不得写日志**。
14. 对外只暴露 `task_no`，内部自增 id 不出境（对账报告里唯一的例外是孤儿行的**目录名**，因为孤儿不在表里、没有 `task_no` 可带）。

## 约束（已定决策，直接引用编号）

判据 `D-1`–`D-7`、决策 `C1`–`C65`、命名 `N1`–`N7`、写集表 `W1`–`W8` 的定义见 [handoff.md](./handoff.md)。
实施时**以 `design.md` 的收敛版为准**；handoff 是讨论记录。

三条最容易被后人当 bug 改掉的**有意为之**，在此点名：

- **两条级联的时序不同**：重洗时旧产物在整个运行期间仍可下载（内容还是同一个视频，旧版是合理兜底）；指纹变了时该 task 在整个主管线期间零 `READY`（旧产物是**别的内容的**，留着反而是错的）。
- **文件系统残留窗口三类全接受**（文件已替换但 DB 未提交 / 同代多文件不同源 / 孤儿文件）。这是买「文件名不带 revision、物理上每 `(type,lang)` 只留一份」付的钱，不是疏漏；第 ② 类本次加**检测**（四列 sha256），不根治。
- **不做版本回退**（`revision` 列与「路径是 DB 的一列」已经把位置留好）。

## 范围外

| 不做 | 原因 |
|---|---|
| 前端接接口 | 另开任务（C60）；后端全部段做完再动 |
| 删除功能（删媒体级联全删） | 移出范围（C38），所以也不需要 `lifecycle_status` |
| 续跑 / 断点续传 | 重新触发即可（C17） |
| 定时对账 / 定时任务 | C39；对账是手工触发的端点 |
| 多实例改造 | 单实例假设（C39）；启动收口的正确性建立在「单实例 + 跑的线程与进程同生共死」 |
| 全站鉴权 | 记成已知缺口；真要加是整站引 Spring Security，另开任务 |
| 版本回退 | C52；位置已留好 |

## 任务地图（8 段，顺序即依赖）

| # | 子任务 | 交付 | 前置 |
|---|---|---|---|
| **S1** | `10-17-subtitle-model-foundation` | 五张新表 DDL + 五个领域类 + **五个**新 port（~~四个~~，2026-10-17 订正：那是 C62 加 `intermediate_file` 之前的数，见 design §3）+ MyBatis 实现。**纯增量**：不动任何调用方 | — |
| **S2** | `10-17-subtitle-model-submit-cutover` | 身份键、提交路径（W1 事务 + C63）、run 生命周期（收口 / 启动收口 / 互斥）、新契约骨架、SSE、`subtitle_keyword` 换锚点、旧栈整块删 | S1 |
| **S3** | `10-17-subtitle-artifact-authority` | artifact 表读写与换版落定、`/artifacts` 端点、下载端点改按 `type`+`file`+`?lang=` | S2 |
| **S4** | `10-17-subtitle-reclean-run` | RECLEAN run、W5 换版 + 下游级联、两种终态 | S3 |
| **S5** | `10-17-subtitle-translate-run` | TRANSLATE run、W4 两行换版、复用判据只认 `status` | S4 |
| **S6** | `10-17-subtitle-content-change` | `intermediate_file` 登记、复用判据改造、指纹探测阶段、C31 级联 | S4 |
| **S7** | `10-17-subtitle-reconcile-endpoint` | `POST /api/maintenance/reconcile`（只读）、哈希比对、临时后缀白名单全局化 | S6 |
| **S8** | `10-17-subtitle-hardening-wrapup` | 网络调用超时、spec 更新、旧件残留清查、全量回归 | S7 |

**两处硬顺序**（不是自由选择）：

- **S6 必须含 C62（中间产物登记）** —— 指纹级联只动 DB、盘上中间产物没动，而复用判据看的是「文件在不在」→ 少了它，S6 自己就制造「指纹变了等于没变」的**静默错交付**。
- **S4 必须在 S5 之前** —— 「`READY` ⟺ 基于当前 `CLEANED`」这个口径靠 S4 的级联成立，S5 的译文复用判据建立在这上面。

## 每段 HTTP 面与验证手段

**两条原则**：

1. **一套接口，只增不改，不造临时件。** 每段引入的都是**最终形状**（见 [design.md §6](./design.md)），后续段只让它从半可用变全可用、或补上新端点。**不写任何"只为某一段验收、后面必须删掉"的代码** —— 造临时件等于把 C60 买来的自由度还回去，还附送一个"靠人守的记得删"待办。
2. **C59 的验收线不是「每段都能完整走一遍用户流程」**，而是「每段都有一个能亲手确认的断言」—— 目的是别在大爆炸之后才第一次看到东西跑。

| 段 | 本段结束时的 HTTP 面 | 验证手段 | 已知缺口 |
|---|---|---|---|
| **S1** | **旧接口原样不动**（唯一的"新旧并存"窗口，且是单向的：新代码还没接上任何调用方） | 旧契约回归：提交 URL → 202 → 旧形状 GET → 跑完 | 无 |
| **S2** | `GET /{taskNo}` 换最终形状（身份 + `lastRun`）；三个 POST 换 202/409 `{taskNo,runNo}`；SSE 换 run 级；`/keywords` 换锚点。**旧下载端点随旧栈一起删** | 提交 / 查询 / SSE / 并发 409 / 重启 `INTERRUPTED` / 同 URL 二次提交插新 run | ⚠️ **产物取不到** → **绕道看目录**（见下） |
| **S3** | 新增 `GET /{taskNo}/artifacts`；下载端点按 `type`+`file`+`?lang=` 重建 | 产物清单 / 下载 / 手工删文件看自愈 | 无 |
| **S4** | 无新端点 | `POST /{taskNo}/subtitles/regenerate` → 看 `/artifacts` 的 `revision` 与 `status` 变化 | 无 |
| **S5** | 无新端点 | `POST /{taskNo}/translate` → `/artifacts` 多一条 `TRANSLATED(lang)`；再翻同语种**不发 LLM** | 无 |
| **S6** | 无新端点 | 改内容重提交 → `/artifacts` 全空 + 主管线真重跑（日志 / 文件 mtime） | ⚠️ **中间产物表无对外读端点**（C54 只给 artifact）→ **绕道 SQL 直查**（见下） |
| **S7** | 新增 `POST /api/maintenance/reconcile` | 手工造四类不一致，各报出来 | 无 |
| **S8** | 无新端点 | 全量回归 | 无 |

**全程不动的端点**：`GET /api/languages`、`GET /api/media/tools`（与任务模型无关）。

**两处绕道，明确记账**（不是"少验了东西"，只是那一条断言不走 HTTP）：

```bash
# S2：产物 —— /artifacts 未建（S3）、下载端点已随旧栈删
ls backend/data/works/BV11QMd6WEJp_p1/
#   source.mp4  audio-*.m4a  original-segments.json  original-subtitles.srt
#   clean-segments.json  clean-subtitles.srt  context.json

# S6：中间产物表的状态 —— 它是对内表，设计上就没有对外读端点
SELECT file_name, status FROM intermediate_file WHERE task_id = ?;
```

**为什么不把对账端点（S7）提前**，让它给前面几段当验证工具：**提前做会需要临时件**。S6 之前中间产物没登记，对账的 ③（孤儿）会把盘上所有 `source.mp4` / `audio-*.m4a` 报成孤儿，于是又得临时挂一份「中间产物文件名白名单」—— 而 C26 刚刚才因为 C62 把那份白名单**取消**掉。为了早点拿到验证工具，重新装回一个马上要删的白名单，不划算。

## 跨子验收标准（全部段做完后，父任务评审）

- [ ] **AC1 表与口径**：六张表齐备；索引/约束名符合 `N4`；枚举列一律 `VARCHAR(32)`；无 DB 外键约束；`schema.sql` 头部写明存量迁移步骤（`subtitle_keyword` 必须手工 DROP 一次）。
- [ ] **AC2 旧件零残留**：`VideoTask` / `TaskSessionRegistry` / 三个中文失败前缀常量与两个「按前缀认领」的清除方法 / `latest_*` 指针类字段 / `TaskConflictResponse.existingTask` / `WorkspaceKey` 全部退场；`TaskStatus` 已改名 `TaskRunStatus`（阿里 SDK 的同名枚举不受影响）。
- [ ] **AC3 三种 run 跑通**：`MAIN` / `RECLEAN` / `TRANSLATE` 各跑一次，产物状态与失败原因互不串台。
- [ ] **AC4 并发**：同一 URL 并发提交收敛到同一条 `subtitle_task`；同一 task 并发触发 → 一方 409 且响应体带 `taskNo`；互斥不泄漏（`uk_active_run` 无残留）。
- [ ] **AC5 崩溃恢复**：kill 进程后重启 → 残留 run 变 `INTERRUPTED`、残留 artifact 变 `FAILED`、被收掉的 task `updated_at` 更新。
- [ ] **AC6 内容变更**：改内容重提交 → 旧 artifact 与中间产物全 `SUPERSEDED`、指纹写入、主管线真的重跑（不再跳过转写）。
- [ ] **AC7 对账**：手工造出四种不一致，端点各报出来；② 只报不修；重复调用结果不变（只读幂等）。
- [ ] **AC8 契约**：`GET /api/tasks/{taskNo}` 只有身份 + `lastRun`（无扁平状态五字段）；`/artifacts` 只有 `READY`；三个 POST 统一 202/409 `{taskNo, runNo}`；SSE 一条 run 一条流、事件带 `runNo`/`runType`/`targetLanguage`。
- [ ] **AC9 回归**：全量测试绿；后端能起服务；`backend/data/works*` 从零重建后能跑通一次完整流程。

## Notes

- 测试基线：本任务开始时静态数 `@Test`/`@ParameterizedTest` = **569**（与 handoff §9.4f 记的 569 一致；570 那个数来自 10-11 归档、口径不同）。**每段动手前重量一次**。
- 环境命令（判编译、跑测试、起服务、推送代理）见 [handoff.md §10](./handoff.md)。
- 每段的 `implement.md` 必须写明：段内顺序、验证命令、回滚点、以及**本段结束时哪些东西还是"欠账"**（交给后面哪一段）。
