# 字幕模型重构 6/8：内容变更（中间产物登记 + 指纹探测）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），本文只写本段的范围、取舍与验收，不复述终态、不另开一份设计。
> 段级技术落点与执行顺序见 [implement.md](./implement.md)。
> 前置 S4（`10-17-subtitle-reclean-run`）与 S5（`10-17-subtitle-translate-run`）已归档合并；S6 从 `dev` 切分支。

## Goal

把「复用判据」从**文件在不在**改成**表里 `READY` 且文件在**，并补上**内容指纹探测 → 不一致级联作废**：

1. **C62 中间产物登记**：`source.<ext>` / `source.info.json` / `audio-*` 每次真正产出后登记 `intermediate_file`；复用前查表。
2. **C50 原子替换**：中间产物「文件存在 = 文件完整」的前提落实（音频已实现；媒体源与 info json 逐项核验，见「现状与欠账」）。
3. **C47–C49 指纹探测阶段**：主管线在媒体获取之前，用 yt-dlp 只取元数据，比对 `workspace.duration_ms` + `published_at`；不一致时走 **W5′**：旧 artifact 与中间产物全部作废 + 写新指纹，**同一事务**。
4. **C31 级联**：指纹变了 → `workspace` 身份不动，旧 artifact 全部 `SUPERSEDED`，跑当前这条新 `MAIN` run。

**一句话判据**：改内容重提交后，`/artifacts` 全空（没有 `READY`），旧中间产物行不再 `READY`、文件被删；主管线真的重下载 / 重抽音频 / 重转写 / 重清洗（日志与 mtime 都能看出来），而不是靠盘上旧文件「断点复用」跳过。

## 现状与欠账（代码实证）

S1 已把 `intermediate_file` 的五件套（实体 / 状态机 / 端口 / Mapper / 仓储）建好，但**至今零生产调用点**（`grep IntermediateFileRepository src/main` 只命中自身包）。S5 收尾的欠账 #1 / #2 正是本段：

| 欠账 | 现状实证 | 本段动作 |
|---|---|---|
| **中间产物零登记** | `IntermediateFileRepository` 无任何 application 调用；`AudioPreparationCoordinator` 判媒体复用直接调 `MediaAcquirerDispatcher#reuseExisting`，判音频复用只看 `Files.size > 0` | 产出后登记；复用前查表 |
| **转写 / 清洗的复用判据还是文件** | `SubtitlePreparationCoordinator#readCheckpoint` 只看 `original-segments.json` 大小；`SubtitleCleanupCoordinator#doClean` 只看 `clean-segments.json` + 文件内关键词指纹 | 先查 `ORIGINAL` / `CLEANED` 的 `findIntactArtifact`，查不到就不许走文件断点 |
| **指纹两列恒为 NULL** | `Workspace#updateFingerprint` 零生产调用点；`YtDlpInfoJson` 只映射 `ext/title/duration`，没有 `timestamp` | 新增探测阶段 + 级联写入 |
| **内容变更级联不存在** | 全仓没有任何代码把 artifact 从 `READY` 批量打成 `SUPERSEDED`（除了换版那一行） | W5′ 事务 + 事务后回收 |
| **`intermediate_file` 仓储缺口** | 只有 `findReadyByTaskIdAndFileName`，没有「按文件名取任意状态」；`markReady` 不更新 `derived_from_run_id` | 补端口方法；重产出时更新审计列 |

### C50 原子替换：现状核验（本段不改 provider 输出路径）

逐项对照父设计 §8.3：

| 中间产物 | 现状 | 结论 |
|---|---|---|
| `audio-*` | `FfmpegAudioExtractor` 先写 `<output>.part`，成功后 `ArtifactFileUtils.moveAtomically`（`ATOMIC_MOVE`），失败删 `.part` | **已满足**，不改 |
| `source.<ext>` | yt-dlp 输出模板 `source.%(ext)s`，下载期写 `.part`、完成后自行改名；`NON_PRODUCT_SUFFIXES` 已排除 `.part` / `.ytdl` | **已满足**（依赖 yt-dlp 的落盘协议），不改 |
| `source.info.json` | yt-dlp 直接写最终名；半截 JSON 解析必失败，`reuseExisting` 会判不可复用并重下 | **已满足**（完整性由「可解析」守卫），不改 |

> 如果项目所有者要求 info json 也严格走「临时文件 → 原子替换」，需要在 yt-dlp 输出后做一次 staging 改名，属额外范围。**本段默认不做**，因为现有守卫已经把「半成品被复用」堵死，没有可复现的错交付路径。

## 本段范围

- 新增内容指纹领域词汇与探测端口；yt-dlp 适配器只取元数据（不下载）。
- 新增 `PROBE_FINGERPRINT` 阶段（主管线最前，媒体获取之前）。
- W5′：artifact 按类型处理 + 中间产物级联 + 指纹写入，同一事务；事务提交后回收文件。
- 中间产物登记与复用门禁：媒体源、info json、音频。
- artifact 断点门禁：`ORIGINAL` 不完好 → 不许跳过转写；`CLEANED` 不完好 → 不许跳过清洗。
- 上传路线照常（不探测指纹；内容身份是 sha256），但中间产物同样登记。

## 本段不做

| 不做 | 原因 |
|---|---|
| 只读对账端点 | S7（`10-17-subtitle-reconcile-endpoint`） |
| 网络调用统一超时 | S8（`10-17-subtitle-hardening-wrapup`）；本段探测复用 `YtDlpRunner` 现有超时 |
| 前端接新接口 | 另开任务（C60） |
| 版本回退 | C52 |
| 定时对账 / 定时任务 | C65 |
| 严格把 info json 包成临时文件 | 见上方 C50 核验；现有解析守卫已堵住半成品复用 |
| 删除功能 | C38，范围外 |

## 本段定下的取舍

| # | 取舍 | 理由 | 备选与为什么否掉 |
|---|---|---|---|
| **T1** | 指纹探测是**独立领域端口**（`ContentFingerprintProbe`），由 `YtDlpMediaAcquirer` 一并实现；application 侧用 dispatcher 按 `MediaInput` 选实现 | 探测的能力和 `MediaAcquirer` 不是一回事：前者只读元数据、不产出文件；后者产出媒体文件。同一适配器实现两个端口，避免复制 URL 校验与 `supports` | ① 把 `probe` 塞进 `MediaAcquirer`：本地实现要返回空，端口语义变浑；② 另起一个 yt-dlp 适配器：URL 校验与「谁支持什么输入」要复制一份 |
| **T2** | 指纹 = `duration` + `timestamp`，**两者都取到才算数**；缺一个就按「取不到」处理（C49） | 父设计 R6 / C48 明确是两列组合；只拿一半无法可靠比对，写半截会让「首次 vs 变更」的判据失真 | 只比 duration：同一视频换清晰度 / 重编码会误判为同内容；只比 timestamp：改标题 / 封面不会变 timestamp，但本项目只关心媒体内容，可接受——不过父设计已经定死两列，不另起 |
| **T3** | 传输时间戳按 **UTC** 转 `LocalDateTime` 落 `published_at` | 与机器时区无关，跨时区重启不会把同一内容判成变了；该列不对外展示，只做指纹 | 系统时区：换时区 / 夏令时会制造假变更 |
| **T4** | 「首跑」= 两列都 `NULL` → **只写指纹**，不级联（C48）；只要有一列非空且不同（含一列为 `NULL`）→ 按「变了」处理 | 首跑没有旧产物可作废；一列为空说明状态不完整，安全方向是按变了处理 | 把「一列为空」也当首跑：会让不完整的旧指纹永久跳过级联，安全方向反了 |
| **T5** | 中间产物复用门禁放在 `AudioPreparationCoordinator`；artifact 断点门禁放在 `SubtitlePipeline` 算好布尔值传下去 | 媒体 / 音频的「表 + 文件」判据天然属于音频准备阶段；`ORIGINAL` / `CLEANED` 的完好版是产物门的职责，管线本来就负责建占位与落定 | 让 coordinator 直接注入 `SubtitleArtifactStore`：阶段类会同时管两张表，职责交叉；把媒体门禁搬进管线：管线要理解适配器的文件名规则 |
| **T6** | 复用被表拒绝、但盘上旧文件还在时：**先尽力删旧文件，再重新产出**；yt-dlp 另加 `--force-overwrites` 兜底 | 新的判据是「表拒绝 = 不能复用」；若不清掉旧文件，yt-dlp 可能认为「已下载」而跳过，ffmpeg 则会直接拒绝覆盖。两道保险保证「拒绝复用」真的等于「重新产出」 | 只靠 yt-dlp 默认行为：已存在文件可能被跳过，静默拿到旧内容 |
| **T7** | W5′ 级联后：`ORIGINAL` / `CLEANED` 清空文件列、**不删文件**；`TRANSLATED` 与中间产物**删文件 → 标 `DELETED`** | 父设计 §7.3 ③：前两者会被新 MAIN run 同名覆盖，文件列是「我不再管这个文件」的开关；译文与中间产物没有新版本会写它们的路径，不删就是长期孤儿 | 统一都删：会在新 run 失败时把旧版兜底也删掉，而旧版是「别的内容」，本来就不能当兜底；统一都不删：译文与中间产物留下永久孤儿 |
| **T8** | 级联的文件回收在**事务提交之后**做；单行删不干净就停在 `SUPERSEDED`，只 WARN，不让 run 失败 | C22 两阶段；新版已经跑起来了，回收没做完是「待人工处理」，不是本次执行失败 | 放进事务：文件 IO 会拖长事务，且失败会回滚掉「作废」——旧内容又变回当前版，更糟 |
| **T9** | 重产出中间产物时**更新 `derived_from_run_id`** | 该列的注释是「产出它的那条 run」；重产出复用同一行（`uk(task_id, file_name)`），不更新就一直在撒谎 | 不更新：审计列失真；插新行：唯一键不允许，且丢 `created_at` |
| **T10** | 新增 `PROBE_FINGERPRINT` 阶段，权重取 **5** | 父设计 C47 要求它成为一个阶段（`current_stage` 有值）；进度百分比是展示占位，取一个能整除的最小值，不为产品意义论证 | 塞进 `ACQUIRE_MEDIA`：`current_stage` 看不出「正在探测指纹」 |

## Acceptance Criteria

> 每条都要能机器判或亲手贴输出；「文件 mtime 没变」这类断言按 S5 的做法直接 `ls --time-style=full-iso`。

- [ ] **AC1 首次探测**：新 URL 跑完一次 MAIN 后，`workspace.duration_ms` / `published_at` 两列非空；`intermediate_file` 里 `source.<ext>` / `source.info.json` / `audio-*` 三行 `READY`，`derived_from_run_id` 指向本次 MAIN run。
- [ ] **AC2 内容未变**：同一 URL 再提交（不带关键词），探测结果与两列一致 → **不级联**；`intermediate_file` 三行仍 `READY`、文件 mtime 不变；日志出现「内容未变化」以及转写 / 写盘 / 清洗的断点复用跳过（准备阶段在 segments 检查点整体短路，媒体 / 音频准备不会被调用，但它们的文件同样一个字节没动）。
- [ ] **AC3 内容变更级联**：把 `workspace.duration_ms` 手工改掉后重提交 → 探测判定不一致；**同一事务**里旧 artifact 全 `SUPERSEDED`、中间产物全 `SUPERSEDED`、指纹写回真实值；事务后译文与中间产物文件被删并标 `DELETED`；`ORIGINAL` / `CLEANED` 停在 `SUPERSEDED` 且八个文件 / 哈希列全 `NULL`。本次 run 重新产出之后，同名的中间产物行**复活为 `READY`**（`uk(task_id, file_name)` 决定一行到底），`derived_from_run_id` 指向本次 run。
- [ ] **AC4 真重跑**：AC3 之后，本次 MAIN run 日志出现「开始获取媒体」「开始抽取音频」「开始转写」「开始清洗」，而不是对应的「跳过」；`original-segments.json` / `clean-segments.json` 的 mtime 更新。
- [ ] **AC5 中间产物表门禁**：手工把某条中间产物行改成 `SUPERSEDED`（文件保留），并让 `ORIGINAL` 不完好后重提交 → 该文件对应的阶段重新执行（媒体重新下载 / 音频重新抽取），不被旧文件骗过。
- [ ] **AC6 artifact 门禁**：手工把 `ORIGINAL` 行改成 `SUPERSEDED`（文件保留）→ 重提交后转写真的重跑；把 `CLEANED` 行改成 `SUPERSEDED`（文件保留、关键词不变）→ 清洗真的重跑。
- [ ] **AC7 取不到指纹降级**：探测失败 / 超时 / 缺 `duration` 或 `timestamp` → 不抛异常、run 照常继续；日志 WARN 且复用判据回落「有产物就复用」（由单测覆盖）。
- [ ] **AC8 上传路线**：上传文件跑完 MAIN 后，`source.mp4` 与 `audio-*` 登记 `READY`；`workspace` 两列保持 `NULL`；再次上传同一内容不触发级联。
- [ ] **AC9 事务与回收失败边界**：W5′ 任一步失败 → 整批回滚，旧 artifact 仍 `READY`；事务提交后删文件失败 → 该行停在 `SUPERSEDED` + WARN，run 不因此失败（单测 + 边界矩阵）。
- [ ] **AC10 回归**：`clean test-compile` 过；全量测试绿；后端能起服务；一次完整的 URL 流程（提交 → 产物 → 翻译 → 重提交）能走通。

## Notes

- **硬顺序**：C62 必须与指纹同段（父 prd 已写死）；本段内部先落「复用门禁」，再做「指纹级联」，这样级联一落地就能看到真重跑，而不是被文件断点挡住。
- **失败信息里的绝对路径**是既有缺陷（S5 欠账 #9），本段不改；新增日志同样遵守「不写 `source_uri` / 目录名」的红线。
- 手工验收用的 SQL / curl 只进 `implement.md` 的验收段，**不进代码库**。
