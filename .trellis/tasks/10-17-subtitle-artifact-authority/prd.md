# 字幕模型重构 3/8：产物权威（artifact 表读写 + 下载契约）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），本文只写本段的范围、取舍与验收，不复述终态、不另开一份设计。
> 段级技术落点与执行顺序见 [implement.md](./implement.md)。
> 前置 S2（`10-17-subtitle-model-submit-cutover`）已归档合并：新栈在跑，但**产物事实源仍是文件系统**。

## Goal

让数据库成为产物的权威：主管线（MAIN run）的两个产物 **ORIGINAL / CLEANED** 按「CREATING 占位 → 换版落定」写进 `subtitle_artifact`；新增 `GET /api/tasks/{taskNo}/artifacts` 清单端点与按 `type` + `file` + `?lang=` 定位的下载端点；所有产物读路径只认 `READY`，文件不在时按 C25 当没有、不把状态改坏。

**一句话判据**：跑完一次主管线后，`subtitle_artifact` 里恰好两条 `READY`（ORIGINAL / CLEANED），`/artifacts` 能把它们连同文件大小列出来、能逐个下载到与工作区逐字节相同的文件；把某个文件手工删掉后，清单里对应产物消失、下载返回 404，但 DB 状态不变。

## 现状与欠账（代码实证）

S2 结束时的 14 条欠账里，本段关掉 **#1 / #2 / #4** 三条：

| 欠账 | 现状实证 | 本段动作 |
|---|---|---|
| #1 产物不落表 | `SubtitlePreparationCoordinator` / `SubtitleCleanupCoordinator` 只写盘；`SubtitleArtifactRepository` 的全部方法（S1 已实现）**零生产调用点** | 主管线接入 createCreatingPlaceholder / settle；补收口置位 |
| #2 `/keywords` 的 fingerprint | 端点本身是关键词清单的纯函数（`SubtitleKeywords#computeFingerprint`），**不需要改算法**；C33 要的是「前端比对的第二值改从表里读」 | 让 `/artifacts` 暴露 `CLEANED.keywordsFingerprint`；端点形状不动，只订正 DTO 注释 |
| #4 后置会话前置判据 | `SubtitleTaskService#validateArtifactPresent` 查的是 `original-segments.json` / `clean-subtitles.srt` 在不在 | 改成「有 `READY` 的 ORIGINAL / CLEANED，且文件在」 |

另外两处**本段必须补上、S2 没做的**：

- `TaskRunSupervisor#finish` 尚未调用 `SubtitleArtifactRepository#markCreatingAsFailedByRunId` —— S2 没有任何 CREATING 行，所以悬空无影响；S3 一开始插占位行，不收口就会留下永远 `CREATING` 的空壳（只有进程重启的启动收口能兜）。
- `TaskController` 目前没有任何产物端点（旧下载端点已随 S2 旧栈删除）。

## 本段定下的取舍

| # | 取舍 | 理由 | 备选与为什么否掉 |
|---|---|---|---|
| **T1** | **artifact 生命周期由 `SubtitlePipeline` 在调用 coordinator 前后包住**（createCreatingPlaceholder → coordinator 写文件 → settle），三个 coordinator 一行不改 | coordinator 是纯文件生产者；版本生命周期属于管线（S4 / S5 会围绕各自的执行体套同一形状）。改 coordinator 会让「写文件」与「记版本」两个职责搅在一起 | 否掉「coordinator 自己写 artifact 表」：S4 的 RECLEAN 与 S5 的 TRANSLATE 会各写一份时序，且 coordinator 的单元测试要开始 mock 仓储 |
| **T2** | **写路径与「可读 READY」读路径收进同一个 `SubtitleArtifactStore`**（`@Component`） | settleArtifact 要先查旧 READY、读路径要按 C25 校验文件；两处共用同一套文件列解析与自愈判据，分开必然分叉 | 否掉「仓储直调 + 单独 Reader」：文件列名、类型→文件集的映射会出现两个版本 |
| **T3** | **C25 的「当没有」按整版产物算**：一版产物只要有一个应带文件不在，整版从清单里去掉、下载一律 404，**不改 DB 状态** | 一版产物的文件集是一个整体；只给半版产物等于让调用方拿到残缺版本 | 否掉「逐文件过滤」：清单里会出现只有 segments 没有 subtitles 的 CLEANED，前端无法判断可不可用 |
| **T4** | **下载 `file` 段取文件 token**：`segments.json` / `subtitles.srt` / `context.json` / `terms.json`；`/artifacts` 的 `files` 键取短名：`segments` / `subtitles` / `context` / `terms` | 完全照父设计 §6 的示例形状，不另立一套 | 否掉「两处都用短名」：父设计示例的 URL 已带扩展名，改它等于改终态契约 |
| **T5** | **新增 `domain/subtitle/model/ArtifactFileKind`**：四个逻辑文件位，携带 `key`（JSON 键）、`token`（URL 段）、`belongsTo(type)`、`specFor(type)`（→ `SubtitleArtifactSpec`） | 「一版产物有哪几个文件位」是领域词汇，而且类型→文件名模板的映射必须单源；放 web 层会让下载与 settleArtifact 各拿一份映射 | 否掉「映射表放 store 里」：store 会同时知道 HTTP token 与文件名模板，两件事混在一处 |
| **T6** | **换版落定不包事务**（W4 非必需）：哈希先算完 → 旧 READY 降级并清列 → 新行置 READY | 崩在两次 UPDATE 之间只会让该 `(type,lang)` 暂时没有 READY；收口把占位行标 `FAILED`，重跑一次即收敛（父设计 §5 已判 W4 非必需） | 否掉「加 TransactionTemplate」：不是正确性问题，只是少一个可恢复的中间窗口；与父设计口径不一致 |
| **T7** | **收口的产物置位单独一段 try**：`markCreatingAsFailedByRunId` 与「run 终态 + task `updated_at`」各自 best-effort | `finish` 的第一条落库若抛异常，不能连带跳过占位行收口；反之占位行收口失败也不能挡住 run 终态（C43） | 否掉「塞进现有同一个 try」：一条失败会跳过其余所有落库 |
| **T8** | **`/keywords` 端点与形状都不动** | 它的 fingerprint 是当前关键词清单的纯函数，本来就只会与 `CLEANED` 行在「清洗之后没改过关键词」时相等 —— 这正是前端要的比对语义 | 否掉「让 `/keywords` 去查 CLEANED 行」：那会把「当前关键词的指纹」换成「上次清洗时的指纹」，改了关键词反而看不出来 |
| **T9** | **新增 `ArtifactNotFoundException`（404）**，与 `TaskNotFoundException` 分开 | 「任务不存在」与「任务在、这一版产物不可用」是两种错，复用前者会让错误文案说假话 | 否掉「复用 TaskNotFoundException」与「用 IAE 翻 400」：前者文案错，后者状态码错（产物可能只是还没跑完，但不是参数问题） |
| **T10** | **排序在应用层做**（ORIGINAL → CLEANED → TRANSLATED，同类型按 `lang` 字典序）；SRT 的 Content-Type 用 `text/plain;charset=UTF-8`，JSON 用 `application/json` | 类型顺序是枚举语义，不是字符串字典序（DB 排序会得到 CLEANED / ORIGINAL / TRANSLATED）；SRT 本身就是文本，`application/x-subrip` 不带 charset 语义 | 否掉「DB ORDER BY artifact_type」：字典序与语义顺序不一致 |
| **T11** | **产物两个端点独立成 `ArtifactController`**，清单与下载都放这里；`TaskController` 一行不改 | 任务生命周期（提交 / 查询 / SSE / 重生成 / 翻译）与产物文件（清单 / 下载）是两种关注点；`TaskController` 已经有六类端点，再塞产物会继续膨胀 | 否掉「都放 TaskController」（与拆分要求相反）与「只拆下载、清单留 TaskController」（同一资源的两个端点分居两处，测试与阅读都要跳文件） |
| **T12** | **降级上下文也落盘，用字段标记**：`SubtitleContext` 增加 `degraded` 组件；摘要失败时写一份带标记的空上下文，读回时带标记的缓存一律当作未命中、下次重新摘要 | 反复失败的根因是「降级不落盘 → 断点复用 → 永远不补写 context」，而 `settleArtifact` 要求 CLEANED 三件套齐全；标记把「这次试过了」与「这是有效缓存」分开 | 否掉「降级不落盘」：下次 MAIN 复用 clean 检查点就永远不会补写 `context.json`，CLEANED 三件套永远不齐、run 永远 FAILED |

> **T12 说明**：`SubtitleContext.degraded()` 与组件访问器同名，Java record 里不能共存；组件名保留 `degraded`（访问器 `degraded()`、落盘键 `degraded`），静态工厂命名为 `degradedEmpty()`。`empty()` 仍是「摘要成功但内容为空」，不带标记、可当缓存。

## Requirements

### 写路径（§7.3 步骤 3–4 / C41 / C51 / C61）

1. 新增 `ArtifactFileKind`（T5）：`SEGMENTS` / `SUBTITLES` / `CONTEXT` / `TERMS`，各自带 JSON 键、URL token、`belongsTo(ArtifactType)`、`specFor(ArtifactType)`；组合规则照父设计 §1.4 的表。
2. 主管线在调用 `SubtitlePreparationCoordinator#prepare` **之前**插 ORIGINAL 的 `CREATING` 占位（`derived_from_run_id` = 本次 run，`derived_from_artifact_id` = null）。
3. `prepare` 返回后 `settleArtifact` ORIGINAL：写 `segments_file_name` / `subtitles_file_name` 与两列 sha256，置 `READY`。
4. 在调用 `SubtitleCleanupCoordinator#clean` **之前**插 CLEANED 的 `CREATING` 占位（`derived_from_artifact_id` = 刚落定的 ORIGINAL 行 id，`keywords_fingerprint` = 本次关键词的指纹）。
5. `clean` 返回后 `settleArtifact` CLEANED：写 `segments` / `subtitles` / `context` 三列文件名与三列 sha256，置 `READY`。
6. `settleArtifact` 的顺序写死（C41）：先算文件名与哈希（此时文件已由 coordinator 原子替换落盘）→ 旧 `READY` 同 `(task,type,lang)` 标 `SUPERSEDED` **并清空文件列与哈希列** → 新行写文件列与哈希列并置 `READY`。旧行不存在时跳过降级。
7. `CREATING` 行**永远全 NULL**；`settleArtifact` 之前不得写任何文件列或哈希列。
8. 哈希与文件列**同生同灭**：清空旧行时八列一起清；写新行时按类型只写该有的列，其余保持 null。
9. `TaskRunSupervisor#finish` 收口时把**本次 run 插的、仍停在 `CREATING` 的行**标 `FAILED`（C61），与 run 终态落库互不阻断（T7）。
10. **版本号 `revision` 不在本段重新实现** —— `MybatisSubtitleArtifactRepository#insertCreating` 已按 `MAX+1` 生成并回填。

### 读路径与 HTTP 契约（C21 / C25 / C54 / C55）

11. 新增 `GET /api/tasks/{taskNo}/artifacts`：只列 `READY`；每一项给 `type` / `lang` / `revision` / `keywordsFingerprint`（只有 CLEANED 非空）/ `files`；`files` 的每个值给 `url` 与 `sizeBytes`。
12. 新增下载端点 `GET /api/tasks/{taskNo}/artifacts/{type}/{file}`，可选 `?lang=`：按 DB 里的文件列定位文件；`type` / `file` / `lang` 的合法性在入口判（400），产物不存在或文件不在返回 404；返回文件字节本身。**清单与下载两个端点都落在新建的 `ArtifactController`；`TaskController` 不新增任何产物映射、不注入产物查询服务**（T11）。
13. `TRANSLATED` 的 `lang` 必填；非 `TRANSLATED` 不允许带 `lang`（带了一律 400）。`lang` 先按 `SupportedLanguage.parse` 归一（入参 `zh` 与 `zh-Hans` 等价）。
14. **C25 自愈**：所有「可读 READY」的判定 = 行 `READY` **且**该类型应带的每个文件都存在。文件缺失 → 记 WARN、按没有处理、**不改状态**；不把 0 字节文件当缺失（空转写合法产出 0 字节 SRT）。
15. `/artifacts` 与下载都不给历史：`SUPERSEDED` / `FAILED` / `CREATING` 行不出现在清单里，也不能下载。
16. `/artifacts` 的产物排序确定（T10）；`files` 的键顺序固定为 `segments` → `subtitles` →（`context` 或 `terms`）。
17. `GET /{taskNo}/keywords` **形状与行为不变**；只订正 `SubtitleKeywordsResponse` 的注释：前端比对的第二值来源改为 `/artifacts` 里 CLEANED 行的 `keywordsFingerprint`。

### 后置会话前置判据（S2 欠账 #4）

18. `POST /{taskNo}/subtitles/regenerate` 的前置从「`original-segments.json` 文件在」改成「存在**可读的** `READY` ORIGINAL」；不满足仍是 **400** 且不落库、不重跑。
19. `POST /{taskNo}/translate` 的前置从「`clean-subtitles.srt` 文件在」改成「存在**可读的** `READY` CLEANED」；不满足仍是 **400**。
20. 前置检查**不做自愈重跑**：读路径只把缺文件的产物当没有，重新生成由用户重新触发（C17 / C25 的「不改状态」）。

### 非功能性

21. **不做存量回填**：S2 及以前留下的工作区没有 artifact 行。本地数据可弃（父设计 §9.4i），重新提交一次即可产出；不写迁移脚本。
22. **脱敏红线不变**：新增日志只记 `taskNo` / `taskId` / `type` / `lang` / 文件位，不记 `source_uri`，也不打印工作区绝对路径。
23. **代码注释不写 `C*` / `W*` / `§` / 段编号 / 日期**（`comment-guidelines.md` 的内容分流表 + pre-commit 钩子）；理由写在本任务文档里。
24. 判编译一律 `clean test-compile`；测试基线开工前重量（S2 收尾静态数 **555**）。

## 约束

- 编号引用（`C*` / `N*` / `W*` / `D-*`）的定义见父任务 [handoff.md](../10-15-subtitle-task-model-refactor/handoff.md)；实施以父 `design.md` 的收敛版为准。
- **本段不写任何「只为这一段验收、后面必须删掉」的代码**（父 prd 两条原则之一）。
- 三个 coordinator **本段不改行为**；S4 / S5 才在它们的调用点加 artifact 逻辑。
- 对外只暴露 `taskNo`；内部自增 id 不出现在任何响应里。

## 范围外

| 不做 | 交给 | 为什么 |
|---|---|---|
| RECLEAN 的 `W5` 换版、下游 `TRANSLATED(*)` 级联、两种终态（覆盖型 / 孤儿型）、删下游文件 | **S4** | artifact 表侧的动作，S3 之后才有落点 |
| TRANSLATE 的版本落定与复用判据 | **S5** | 同上 |
| `intermediate_file` 登记、复用判据从「文件在」改成「表里 READY 且文件在」、指纹探测、内容变更级联 | **S6** | 与 artifact 表是两张表、两类判据 |
| `POST /api/maintenance/reconcile` 与哈希比对 | **S7** | 需要 S6 的中间产物登记；S3 只负责把哈希列按纪律写对 |
| 网络调用超时 | **S8** | 与产物读写无关 |
| 前端接新接口 | 另开任务（C60） | 本段结束时前端仍点不通 |
| 历史版本 / 版本回退 | — | C52 / C54 明确不做 |
| 存量数据回填 | — | 本地存量可弃 |

## 验收标准

- [ ] **AC1 主管线落表**：一次 MAIN run 跑完，`SELECT artifact_type, status, revision, segments_file_name, subtitles_file_name, context_file_name, terms_file_name FROM subtitle_artifact WHERE task_id=?` 恰好两行 `READY`：ORIGINAL（rev 1，`context/terms` 为 NULL）、CLEANED（rev 1，`context` 有值、`terms` 为 NULL）；没有残留 `CREATING`。摘要失败时 `context.json` 带 `degraded=true` 落盘，同样计入 CLEANED 三件套（标记不影响三件套齐全）。
- [ ] **AC2 换版**：同一任务再跑一次 MAIN → ORIGINAL / CLEANED 各出现 rev 2 的 `READY`；rev 1 变 `SUPERSEDED` **且八个文件/哈希列全 NULL**（覆盖型；不出现 `DELETED`）。
- [ ] **AC3 失败收口**：让 prepare / clean 在占位之后抛错（可用打桩或断网），该 run 的 `CREATING` 行变 `FAILED`，且没有新的 `READY`；run 终态仍是 `FAILED`。
- [ ] **AC4 清单契约**：`GET /{taskNo}/artifacts` 只含 `READY`（超集在库里时也不泄露）；顺序 ORIGINAL → CLEANED → TRANSLATED；CLEANED 项 `keywordsFingerprint` 非空且等于同一时刻 `GET /{taskNo}/keywords` 的 `fingerprint`；`files` 的键与 URL 形状照父 `design.md` §6（TRANSLATED 的 URL 带 `?lang=`）。
- [ ] **AC5 下载**：下载到的字节与工作区文件逐字节相同（`cmp`）；`Content-Type` 对 JSON / SRT 正确；未知 `type` / 未知 `file` / `context.json` 用在 ORIGINAL 上 / TRANSLATED 缺 `lang` → 400；未知 taskNo、无 READY、文件被手工删掉 → 404。
- [ ] **AC6 C25 自愈**：手工移走 CLEANED 的任一个文件 → `/artifacts` 里 CLEANED 整项消失、下载该版任一文件 404、DB 里该行仍是 `READY`（状态未被改坏）；把文件放回后自动恢复可见。
- [ ] **AC7 前置判据**：无 READY ORIGINAL 时 `regenerate` → 400 且不插 run；无 READY CLEANED 时 `translate` → 400 且不插 run；有 READY 行但文件被移走时同样 400。
- [ ] **AC8 收口不互相拖累**：`finish` 里 run 终态落库抛异常时，占位行仍被标 `FAILED`（单测覆盖两条失败路径）。
- [ ] **AC9 回归**：`clean test-compile` 过；全量测试绿且条数 ≥ 555（新增测试只增不减）；后端能起服务；从零重建 `backend/data/works*` 后能跑通「提交 → 等完成 → `/artifacts` → 下载 → 二次提交看 revision 递增」。
- [ ] **AC10 控制器拆分**：产物清单与下载都只在 `ArtifactController`；`TaskController` 没有 `/artifacts` 映射、不注入产物查询服务；`TaskControllerTest` 保持原样，产物用例在 `ArtifactControllerTest`。

## Notes

- **分支**：按 [git-workflow](../../../.trellis/spec/guides/git-workflow.md) 开 feature 分支，从当前 `dev` 切 `feature/subtitle-artifact-authority`；**完成后合回 `dev`**。
- **测试基线**：S2 收尾静态数 555（`grep -c '@Test\|@ParameterizedTest'`）；开工前先 `rm -rf target/surefire-reports` 再跑全量，以实测数为准。
- **环境命令**：判编译 / 跑测试 / 起服务 / 推送代理见 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。起冒烟实例后按 PID `taskkill`（`TaskStop` 杀不掉子 JVM）。
- **本段结束时哪些东西还没到位**：见 [implement.md](./implement.md) 末尾的欠账清单，实施完必须逐条核对并原样写进收尾报告。
