# 已知问题登记

> 这份文件记录**已知但尚未处理**的缺陷与待裁口径。新会话进来先读它，别重新发现一遍。
> 立档：2026-10-20（字幕模型重构父任务集成评审轮的收尾）。
> 同时删掉了 5 份任务期交接文档（`handoff-*.md`）—— 它们对应的任务全部已归档，
> 里面仍有效的待办已逐条盘点并回填在本文 §3。

---

## 1. ★ F1：翻译侧 LLM 全不可用时「静默交付未翻译内容」

**状态**：已确认，**未修**。**需要先定口径**（见 §4）。

### 一句话

翻译没翻成，系统却说"翻好了"，交出去的"译文"其实就是原文。

### 现象（实测）

把 LLM 指向一个死地址（`--spring.ai.deepseek.base-url=http://127.0.0.1:9/`，
9 号端口没人监听，连接必被拒），然后让一个**没翻过**的语种（繁体中文）跑一遍：

| 用户可见面 | 实际值 |
|---|---|
| `task_run.status` | **`SUCCEEDED`** |
| `subtitle_artifact.status` | **`READY`** |
| `error_message` | **NULL** |
| SSE 进度事件 | 「翻译完成」，`state=SUCCEEDED` |
| `/artifacts` | 正常列出，可正常下载 |
| **产物内容** | **与简体源文逐字节相同**（`diff` 无输出，两份都是 3024 字节） |

整份字幕 **0% 被翻译**。用户要繁体，拿到简体，全程没有任何一处提示。

### 根因：一条有意的兜底，但「可观测」没做完

`LlmSubtitleTranslator`（`infrastructure/provider/llm/`）按批翻译，每批的回复要过 `validate`
（键集必须与原文完全一致、每条译文非空），不过就带着原因重试，最多 3 轮。**3 轮全败**之后：

```java
translated = safe(seg.text());   // 用原文顶上
untranslated++;                  // 记一笔
```

类注释原话：**「全败整批回退原文（宁可没译，不可乱译）」**。这个取舍本身是对的。

作者也写明了要让它可观测：

> 「静默产出未翻译的行是本条最需要防的事，所以让它可观测（调用方能看到 `untranslatedCount`）」

**但这条没落地。** `untranslatedCount` 确实一路传上去了，传到位之后没人用：

1. **一处 `log.debug`** —— `SubtitleTranslationCoordinator:163` 的
   `"字幕翻译完成: … retry={}, untranslated={}"`。生产日志级别是 root **INFO**，debug 不输出。
2. **`TranslatedSubtitle.untranslatedCount` 字段** —— 全仓 grep `untranslatedCount()`，
   只有「写」和「塞进上面那行 log」，**没有任何读取方**。死字段。

也就是说：写的人想让调用方看见，但调用方拿到的只有「翻译完成」。

### 复现

```bash
# 1) 以死地址起服务（只影响这一次启动，不改任何文件）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q spring-boot:run \
  -Dspring-boot.run.arguments=--spring.ai.deepseek.base-url=http://127.0.0.1:9/

# 2) 对一个已有 READY CLEANED、但某语种还没翻过的 task 触发翻译
curl -X POST localhost:8080/api/tasks/$T/translate \
     -H 'Content-Type: application/json' -d '{"sourceLanguage":"zh","targetLanguage":"zh-Hant"}'

# 3) 等它跑完（约 3-4 分钟：3 批 × 每批 3 轮 × 每轮 4 次尝试），然后比内容
diff backend/data/works/<目录>/clean-subtitles.srt \
     backend/data/works/<目录>/translated-subtitles-zh-hant.srt    # 无输出 = 中招
```

日志里会看到 `DEBUG LlmSubtitleTranslator: 字幕翻译批次重试用尽，整批回退原文: 序号=N, 句数=M`。

原始记录（含完整实测数据、51 次 `Connection refused` / 34 次 `ResourceAccessException` / 17 次 `Retry error`）：
`.trellis/tasks/archive/2026-10/10-15-subtitle-task-model-refactor/evidence/2026-10-20/ac3-three-runs.txt` §3

### 严重性判断（有数据，别拍脑袋）

集成评审轮里**正常跑的 6 趟翻译（约 17 个批次）`retry=0`、`untranslated=0`** ——
不只是没有整批回退，**连一次重试都没有**。所以**确实罕见**。

但三条理由让它仍然值得修：

1. **罕见 + 无声 = 最坏的组合。** 会自己喊出来的罕见故障无所谓；F1 不喊，产物与一份好译文**长得一模一样**，你没有任何理由去看它。
2. **触发条件不"外星"。** 全挂的现实成因：API 余额用完（预付制的经典死法，悄悄发生）、key 过期 / 被吊销、供应商故障。**本项目还有一个放大器**：`spring.ai.model.chat` 那个 `deepseek` / `openai`(Mimo) 切换开关，[application.yml](../../../backend/src/main/resources/application.yml) 的注释写着「高峰期切 mimo，低谷期切回 deepseek」——**这个开关设计上就是要人来手动拨的**，拨错一个字符就是全挂。
3. **部分失败比全挂更阴。** 只有中间一批灭了 → 交出去的文件里前 12 句是译文、中间 12 句是原文、后面又是译文。文件看起来"是翻过的"，得逐句读才发现。**这一形态本轮没有数据**（样本只有一个 134 秒 / 28 句的视频），真实素材更长 → 批次更多 → 机会更多。

### 归属

**不是字幕模型重构引入的。** `LlmSubtitleTranslator` 的兜底早于该任务（文件历史可追到 2026-10-07 之前），
那 8 段没改过它的语义。但重构把「产物权威」搬进 DB 之后，这一行在库里与一条正常译文**完全同形**
（`READY` / 有文件 / 有 sha256），比旧模型（扫目录、文件是新的）**更难被发现**。

---

## 2. F2：（观察，非缺陷）`revision` 会被 `FAILED` 占位行消耗

**状态**：确认**不是缺陷**，仅记录，供前端参考。

task 6 上出现过 `CLEANED rev1 = FAILED`（被 kill 的残留占位）+ `rev2 = READY`。
即「第 1 版」从未有过内容，可见的第一版是 `rev2`。

design §1.4 明写 `revision` = 「同 `(task,type,lang)` 递增（**MAX+1**）」，**与状态无关** ——
所以这是照设计实现的。

**唯一要留意的**：`/artifacts` 只暴露 `READY`，前端可能看到版本号跳号（如只有 2、3 而没有 1）。
**前端展示「第 N 版」时不要做连续性假设。** 不改代码（改动会牵连唯一键与换版顺序）。

---

## 3. 从已删除的 5 份交接文档里回填的盘点

2026-10-20 删掉的 5 份 `.trellis/workspace/HeQiu/handoff-*.md`，对应的任务**全部已归档**。
逐条盘过它们里面「下一步 / 遗留 / 待办」性质的段落，结论如下（**这是"为什么可以删"的账**）：

| 旧文档 | 对应任务 | 里面的待办 | 现状 |
|---|---|---|---|
| `handoff-weight-flattening.md` | `10-08-progress-weight-flattening` | ① Javadoc 全局重构 ② `SubtitlePipeline` 执行模型拆分 ③ 删 `backend-before-reconstruction/` ④ `../vidsage-before` worktree 去留 | ①② 已由后续任务完成（注释收口任务树 / `10-08-pipeline-executor-split`，均已归档）；③④ **实测目录与 worktree 都已不存在**（`git worktree list` 只剩主仓） |
| `handoff-pipeline-executor-split.md` | `10-08-pipeline-executor-split` | 「下一步怎么起」 | 已归档（`0d388d4` 等） |
| `handoff-pipeline-state-broadcast.md` | `10-08-pipeline-state-broadcast` | 两处并发竞态 + SSE/通知面搬家「均另开」 | 见下条 |
| `handoff-workspace-port-inversion.md` | `10-09-workspace-port-inversion` | 「下一步」 | 已归档 |
| `handoff-reuse-versioning.md` | `10-20-subtitle-reuse-versioning` | §6 的 O1–O4、§7 要动的清单、§9 下一步 | 已在该任务的 `prd.md` T1–T4 逐条定案并实现，已归档 |

**唯一还活着的那条**（「两处并发竞态 + SSE 搬家」）拆开看：

- **两处并发竞态 —— 已被字幕模型重构解决。**
  「提交查重 check-then-insert」由 W1 事务 + C63（撞 `uk_source` 后同事务锁定读重查）解决；
  「并发 `POST /translate` 双跑」由 `uk_active_run` 解决。两条都在集成评审轮 AC4 实测过
  （3 并发打全新 URL → 1×202 + 2×409、收敛到同一条 `subtitle_task`，无死锁）。
- **SSE / 通知面搬家 —— 已由重构的 S2 整段做掉**（`TaskSubscriptionRegistry` /
  `TaskSseConnectionManager` / `TaskProgressNotifier` 全部退场，换成 `RunRef` 分桶 +
  端口 `TaskProgressBroadcaster` + 实现 `web/sse/SseTaskProgressBroadcaster`）。
  **连带后果见 §3.1。**

### 3.1 ★ `10-11-sse-connection-pairing`：前提已消失，2026-10-21 已删除

`.trellis/tasks/10-11-sse-connection-pairing/`（原状态 `planning`，从未 `start`）整篇讲的是一件事：

> `TaskSseConnectionManager` 里，一条 SSE 连接与它在 `TaskSubscriptionRegistry` 里的那条订阅的
> **配对是隐式的**，订阅句柄被关在 lambda 闭包里，于是**写失败那条路径没法退订**。

**这两个类都已经被字幕模型重构 S2 删掉了**（`backend/src` 零命中）。新实现
`SseTaskProgressBroadcaster` 里**根本没有订阅注册表**——广播是直接的，
连接表是 `ConcurrentHashMap<RunRef, CopyOnWriteArrayList<SseEmitter>>`，
`sendTo` 的 catch 直接 `remove(run, target)`，没有"够不着的订阅句柄"这回事。

所以**该任务的前提已不成立**，它描述的缺陷在新代码里不存在。

**处置（2026-10-21，项目所有者裁定）**：**直接删除**，不归档、不重写。

- **不重写**：它整篇的立论对象（`TaskSseConnectionManager` / `TaskSubscriptionRegistry`）在新代码里已不存在，
  按新实现重写等于另起一个任务，与「让旧任务退场」是两件事。
- **不归档**：`archive/2026-10/` 下 37 个任务**全是 `completed`**，无「未开工就归档」先例；
  塞一个 `planning` / `branch: null` 的条目进去，会把「归档 = 做过的事」这个含义搅浑。
- 目录已 `git rm`；本节结论（前提已消失）留档，完整 PRD 留在 git 历史里。

---

## 4. 待项目所有者裁定

| # | 事项 | 我的倾向 |
|---|---|---|
| 1 | **F1 的降级算成功还是失败？** | **算成功、但要显形** —— 保留「宁可没译，不可乱译」这个正确取舍，只把 `untranslatedCount > 0` 提升为可观测（至少 `log.warn`；最好落进 artifact 行或 run 的 `error_message`，并在 `/artifacts` 可见）。判失败会让「模型抖了一下」变成「整条任务白跑」。 |
| 2 | **F1 什么时候修？** | 另开一个小修任务，**不卡**任何已归档的东西。成本很小（半天以内）。 |

---

## 5. 相关

- 字幕模型重构的**终态口径**：`.trellis/tasks/archive/2026-10/10-15-subtitle-task-model-refactor/design.md`（权威）
- 集成评审的**逐条实测与证据**：同目录 `implement.md` §9 + `evidence/2026-10-20/`
- 该任务树的状态与沿革：[[subtitle-model-c35-review]]
