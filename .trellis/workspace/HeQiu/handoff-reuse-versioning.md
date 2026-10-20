# 复用时的落版语义 —— 待开修复任务的输入（2026-10-20）

> **本任务已开，本文降为论证留档。** 承接它的是
> [10-20-subtitle-reuse-versioning](../../tasks/10-20-subtitle-reuse-versioning/prd.md)：
> 范围、取舍与验收口径**一律以那份 `prd.md` 为准**，本文只保留论证过程。
> 本文 §6 的四个未定项（O1–O4）已在那份 `prd.md` 的 T1–T4 里逐条定案。
>
> **现状不是 bug。** 它是 design.md 正面写过的决定（§3 引了原文）。**在修复任务立项之前，别顺手改它。**
>
> 来源：2026-10-20 S6 代码审查中，owner 读 `SubtitlePipeline.runPipeline` 时提出的疑问。
> 当时 S6 停在审查门、一行未提交，双方同意**不在 S6 里改**（S6 是「门禁接线 + 指纹探测 + 级联」，
> 这是「版本模型 + 换版语义」，混一笔提交审不清）。

---

## 1. 一句话

现在**每次 MAIN run 都无条件插一版新的 `ORIGINAL` / `CLEANED`**，并把旧版标 `SUPERSEDED`；
哪怕这一趟什么都没重算（断点复用命中、文件一个字节没动）也照样落版。
owner 拍的方向是：**先判是否复用，不复用才插新行 + 标旧行；复用则整条链一个字节都不动。**

---

## 2. 现场：复用命中时的库状态（可达）

Run N+1、内容未变、关键词未变：

| 行 | status | 文件列 | `derived_from_artifact_id` |
|---|---|---|---|
| `ORIGINAL` rev1 | `SUPERSEDED` | 全 NULL | — |
| `ORIGINAL` rev2 | `READY` | 指向 `original-segments.json` | — |
| `CLEANED` rev1 | `SUPERSEDED` | 全 NULL | → ORIGINAL rev2 |
| `CLEANED` rev2 | `READY` | 指向 `clean-*` | → ORIGINAL rev2 |
| `TRANSLATED` rev1 | **`READY`** | 不动 | → **`CLEANED` rev1（`SUPERSEDED`）** |

最后一行就是问题所在：**一条 `READY` 的译文，血缘指向一条 `SUPERSEDED` 的清洗行。**

副作用还有两条：

- **垃圾行累积**：用户反复点提交 N 次 → 库里 2N 条 `SUPERSEDED` 且文件列全 NULL 的行，没有任何消费者。
- **`revision` 语义变浑**：它现在是「第几次执行产出的」，不是「内容第几版」。而 design §8.5 说将来加版本回退要拿 `revision` 当版号 —— 那它必须是你说的那个语义（内容第几版）才有意义。

---

## 3. 现状为什么是这样（design 的成文理由，不是漏想）

两条原文，专门为这个状态写的：

> **§615**：「`READY` 就等于『基于当前版清洗**的内容**』——注意是内容，不是那一行：清洗命中检查点跳过重跑时会落一版新 revision 但内容没变，那种情况下译文**不**被作废，因为它是对的。」
>
> **§616**：「判据里**不带** `derived_from_artifact_id`：它只表达『派生自哪一版』，不表达『是不是当前版』。」

还有一条 2026-10-19 的**订正**（§396）也是为解释它而挂的：

> 「W5 里的『内容变了』是判据，不是修饰语：清洗命中断点复用时不会真重跑、文件一个字节没动，但**照样会落一版新 revision** —— 落新版本 ≠ 内容变了。」

**判据**：需要一条订正来解释的状态，通常就是设计债。

出处：`settleArtifact` 出自 **S3**（产物权威），`settleCleanedAndSupersedeDownstream` 出自 **S4**（重洗 W5 换版级联）。

---

## 4. 改法的收益（四条）

1. **去掉「READY 行的血缘指向 SUPERSEDED 上游」这个状态** —— 不用读文档就知道它不是 bug。
2. **不再累积垃圾版本行** —— 跳过不落版，`SUPERSEDED` 行只在内容真变时产生。
3. **`revision` 变成「内容第几版」** —— 正是 §8.5 的版本回退需要的语义。
4. **净简化**：跳过不落版之后，`settleCleanedAndSupersedeDownstream` 里的 `isContentChanged` **条件作废可以整段删掉**，变成「清洗真重跑 → 无条件作废下游」。少一个判据就少一个错口。
   （注意：四个 `sha256` 列**仍要写**，那是 §8.4 ② 的对账检测用的，与「比哈希决定要不要作废下游」是两件事。）

---

## 5. 成本在哪

**核心难点：「会不会复用」这个判据现在藏在阶段类内部，而且回答它要真读盘。**

- `SubtitlePreparationCoordinator`：`reuseCompletedOriginal && readCheckpoint(segmentsPath) != null`
  （读 `original-segments.json` + 反序列化）
- `SubtitleCleanupCoordinator`：`allowCheckpointReuse && readCheckpoint(cleanSeg) != null && 关键词指纹一致`

要改成「不复用才插行」，管线就得在**建占位之前**先拿到这个答案。而占位现在建在 `prepare` / `clean` **之前**，
`settleArtifact` 的入参就是那个占位对象 —— 跳过时没有占位，`original` 变量与 CLEANED 的
`derived_from_artifact_id` 都得改用**已有的那条 READY 行**。

---

## 6. 必须先定的四件事（我的推荐）

### O1 「会不会复用」的判据落在哪一层？

- **A（推荐）**：阶段类暴露一个公开判据（如 `canReuse(...)` / `hasReusableCheckpoint(...)`），
  管线先问 → 再决定插不插 → 再让它干活。
  代价：检查点 JSON 会被读两遍；或者把解析结果顺着签名传下去（签名变长，但只读一次）。
- **B**：管线照旧先插占位，阶段类确认复用时回头把那条 `CREATING` 删掉。
  **否** —— 更脏：`revision` 已经 +1 了，删行会在 `uk(task_id, artifact_type, revision, lang)` 上留洞。
- 另注：prd T5 明确否过「让 coordinator 直接注入 `SubtitleArtifactStore`」（阶段类会同时管两张表），
  所以别走「让阶段类自己插占位」那条路。

### O2 跳过时那条 READY 行的 `derived_from_run_id` 记谁？

- **A（推荐）**：不动 —— 保持「**最后一条真正产出它的** run」。这比现在更诚实
  （现在每次复用都把新版本刷成本次 run，而文件其实是更早那条 run 写的）。
  连带：把该列的注释从「哪一次执行产出的」改准，写明「跳过复用不刷新它」。
- **B**：跳过时 UPDATE 成本次 run（与 `intermediate_file.derived_from_run_id` 的「跟着最近一次产出走」同口径）。
  代价：要单独开一条 UPDATE，且要重新定义「复用算不算产出」。

### O3 跳过的 run 在产物表里零痕迹，要不要紧？

- **A（推荐）**：不要紧 —— `task_run` 已有终态；「这次跑交付了什么」的答案是「没交付新的，沿用上一版」。
  但**必须写进 design**，免得 S7 对账按「每 run 至少一版」写，把正常情况报成缺口。
- 这条直接决定 S7 对账的判据，所以是「先开修复任务、再开 S7」的理由。

### O4 ORIGINAL 与 CLEANED 独立判断（边界）

关键词改了 → 清洗必须重跑，但 ORIGINAL 可以复用。此时 CLEANED 插新行、`derived_from_artifact_id`
指向**旧的** ORIGINAL id（那条仍是 READY）。反过来（ORIGINAL 重跑、CLEANED 复用）在内容变更级联下不可达，
因为级联已把 CLEANED 标 `SUPERSEDED`。

- **推荐**：允许两个门禁各自独立判断，design 里写明「血缘指向的是 READY 行；跳过时指向没变的那条」。

---

## 7. 要动的清单（供那个任务估量）

**代码（main）**

| 文件 | 动什么 |
|---|---|
| `SubtitlePipeline.runPipeline` | 两个占位改成条件创建；跳过时取已有 READY 行 |
| `SubtitlePreparationCoordinator` | 复用判据提出来（O1）；签名跟着变 |
| `SubtitleCleanupCoordinator` | 同上（还要带关键词指纹那一半） |
| `SubtitleArtifactStore.settleCleanedAndSupersedeDownstream` | 删条件作废（`isContentChanged` 那条路径） |
| `SubtitleArtifactStore.settleArtifact` | 语义从「每次落版」变成「只在真产出时落版」 |
| `SubtitleTaskService.requireIntactArtifact` / `SubtitleArtifactQueryService` | 大概率不用动（读路径只认 READY） |

**文档**

| 文件 | 动什么 |
|---|---|
| `10-15-.../design.md` | §7.4 步骤 3/4（主流程的「插 CREATING → 换版落定」）、§5 W5 注、§396 那条订正、§615/§616、§8.5（`revision` 语义） |
| `10-17-subtitle-content-change/prd.md` / `implement.md` | 受影响的表述（S6 的 AC2/AC4/AC5/AC6 引用过落版行为） |

**验收口径**

- **AC2**：现在写的是「文件 mtime 不变 + `ORIGINAL` rev13 → rev14」。改后 `rev` **不再 +1**，要重写。
- **AC3**：内容变更时仍然是「旧版 `SUPERSEDED` + 新版 `READY`」，不受影响。
- 真库验收要**全部重跑**（S6 的 AC2/AC4/AC5/AC6 都碰过落版行为）。

---

## 8. 与现状**不冲突**的部分（别一起改了）

- **内容变更级联**（W5′）的语义不变：指纹变了 → 旧产物全 `SUPERSEDED`。它本来就不是「跳过」。
- **`markSupersededAndClearFiles` 清空八个列**这件事不变 —— 文件名不带 `revision`、物理上每
  `(type, lang)` 只留一份（C24），旧行留着文件列就是在指向「别人的文件」。
- **首跑**不变：没有旧版可复用，必然插新行。

---

## 9. 下一步

1. S6（`10-17-subtitle-content-change`）按现状走完审查门 → 提交 → 归档。
2. 开修复任务（建议排在 S7 之前），规划时从 §6 的 O1–O4 接。
3. 修复任务归档后再开 S7 —— **O3 直接决定 S7 对账的判据**。
