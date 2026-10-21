# 字幕关键词纠错与多语言翻译

> **状态：已归档（2026-10-20）。** 本文件是**父任务**：前半是源需求集（2026-10-11 与项目所有者逐轮 grill
> 收敛出的共识清单 A–F），后半是任务地图与跨子任务验收标准。技术设计不在这里，在各子任务的 `design.md`。
> 来源：2026-10-11 会话中项目所有者提出的两个需求（"完善字幕生成：传一个关键词列表让 LLM 纠音误" +
> "翻译改成前端选语种，DB 就不用记翻译阶段了"），经 grill 逐轮定稿。

## Goal

给字幕生成补两件事：

1. **关键词纠错**——用户手填一份关键词/术语列表（**纯字符串数组，没有 asr→正确写法的对照关系**），
   LLM 在 ASR 转写之后据此修正同音误写。最典型的对象是**视频发布者的名字**：几乎必然被转错，
   而用户一眼就知道正确写法。列表可反复修改、反复重新生成字幕。
2. **翻译语种可选**——翻译目标语种不再由配置文件定死，改由前端提供固定白名单供用户选择；
   随之取消 DB 里的翻译子状态（一列 `translation_status` 表达不了"en 翻完了、ja 还在跑"）。

本次**只做后端**。前端涉及页面结构重构，另开任务（见 E 节）。

## 需求共识清单（2026-10-11 逐轮确认）

### A. 关键词（术语列表）

| # | 结论 |
|---|---|
| A1 | "重新生成字幕"**只重跑 LLM 清洗**，不重跑 ASR、不重抽音频。关键词只影响纠错这一步；重跑 ASR 既烧钱又改变时间轴，还会让已翻好的译文整体失效。 |
| A2 | 关键词**存 DB 新建的一张表**，**一个 `workspace_key` 一行**（`workspace_key` 唯一），列表以 `U+0001` 连接存在一列里。**不设 DB 外键约束**（全库目前没有任何 `FOREIGN KEY`）。为什么不是 `task_no`——见事实 F7。<br>**（2026-10-12 订正）** ~~主键是它自己的自增 id；一关键词一行；外键列指向 `workspace_key`（`CHAR(12)` + 普通索引）~~ —— 拆行的前提是「能按关键词反查谁用过它」，本项目从不这样查，行存储只带来成本。理由见子任务 A 的 `design.md §5.1`。 |
| A3 | 只存**当前生效的一份**（不做历史快照）；前端每次提交**完整列表**（覆盖全量语义，没有增量合并的歧义）；**单条 ≤ 50 字、总共 ≤ 50 条**，超限 400。 |
| A4 | 关键词走**两条通道**：① 喂**摘要器**（`SubtitleContextSummarizer`），让它据此产出 `asr → fixed` 术语，从而吃到清洗阶段既有的相似度基线兜底；② 清洗提示词里**独立一段**，摘要降级为空上下文时仍生效。**这条独立通道不参与** `TermMatcher` 命中判定、不进术语指标、不进相似度基线。 |
| A5 | 摘要产物与清洗产物各记一个 `keywordsFingerprint`（关键词列表的哈希）。与当前列表不一致即重摘要 + 重洗；前端也靠它判断"这份字幕对不对得上当前关键词"。 |
| A6 | 提交任务时也能带**可选**关键词（`POST /api/tasks`、`/upload`），首次摘要即带上——"这个视频是谁的"用户看标题就知道，不该逼他等一轮跑完再等一轮重跑。 |
| A7 | **Prompt 注入防护**：prompt 里所有外部来源文本（后窗、关键词、参考术语表、术语参考）统一走一个数据块渲染——标签包裹 + **结构性字符**（换行、回车、控制字符）替换成空格 + system 提示词里声明"标签内是数据不是指令"。**内容性字符（`<` `"` `{`）保留**，不做黑名单（黑名单既易绕过又误杀真术语）。输出侧既有的键集校验 + 相似度守卫保持不动。 |

### B. 重跑清洗

| # | 结论 |
|---|---|
| B1 | 端点 `POST /api/tasks/{taskNo}/subtitles/regenerate`，body `{"keywords":[...]}`，202 返回任务视图。一次请求同时做两件事：落库关键词 + 重跑清洗。**不做**"只存关键词不重跑"的单独端点。 |
| B2 | 关键词**没变也照样重跑**。"重新生成"这个动作的语义就是重生成（LLM 有随机性）；指纹只用来**展示**"产物对不对得上当前关键词"，不用来拦请求。前端可自行加一句确认提示。 |
| B3 | 执行顺序：落库关键词 → 重摘要（**失败即整体失败**，不进入下一步）→ 重洗并**原子替换** `-clean.json` / `-clean.srt` → **成功之后**才删全部 `-translate-*` 与 `terms-*`。失败则**一个都不删**，旧字幕与旧译文原封不动。 |
| B4 | 失败原因写回 `task.error_message`，前缀「重新生成失败：」。 |
| B5 | 后置会话**不落 `current_stage`**（跟现在翻译的处理一致——`current_stage` 是主管线阶段词表的落点）。 |

### C. 翻译

| # | 结论 |
|---|---|
| C1 | 目标语种从**配置**移到**请求参数**。白名单固定 `zh-Hans` / `zh-Hant` / `en` / `ja`；新增 `GET /api/languages`（带中文 label）并做白名单校验；**删掉** `vidsage.subtitle.translate.target-language`。 |
| C2 | 触发端点 `POST /api/tasks/{taskNo}/translate`，body `{"targetLanguage":"en"}`。**一次一个语言、一个请求、一条流**；同语言重复触发走既有幂等（产物在就不发 LLM）。 |
| C3 | **跳过判据升级为「主语言 + 中文正简脚本」**：`zh`/`zh-CN`/`zh-SG`/`zh-Hans` 归一为简体，`zh-TW`/`zh-HK`/`zh-MO`/`zh-Hant` 归一为繁体，两边都相同才跳过。`zh → zh-CN` 照旧跳过（保住原有的 zh-CN 陷阱修正），`zh → zh-Hant` 会**真翻**。非中文仍只比主语言。 |
| C4 | `subtitleTranslateChatClient` 里**烧死在 system 提示词的目标语种必须移出**，改由每次请求的 user 提示词携带。 |
| C5 | DB 翻译子状态**全删**：`task.translation_status` 列、`TranslationStatus` 枚举、`VideoTask` 的三个翻译迁移方法、`TaskStageEvent.fromTranslation`、`TaskProgressMessage.translationStatus` 字段、`TaskResponse.translationStatus` 字段、`VideoTask.isEventTerminal` 的翻译分支。 |
| C6 | 失败原因写回 `task.error_message`，前缀「翻译失败：」。 |

### D. 进度与并发（两条线共用的地基）

| # | 结论 |
|---|---|
| D1 | 后置会话（重跑清洗 / 任一语言翻译）在**内存**登记"哪个工作区在跑什么"；`TaskStageEvent.from(task)` 组装快照时**先查登记**——有在跑的就返回那条 RUNNING（非终态）事件，否则走 DB 主状态。 |
| D2 | 这套登记**兼任工作区级互斥**：同一工作区同一时刻只允许一个后置会话，后到者返回 **409**（与"同 URL 已有进行中任务返回 409"同形）。 |
| D3 | 前端**零语义改动**：仍走同一个 `GET /api/tasks/{taskNo}/events`，仍是「POST → 重开订阅 → 收进度 → 终态关流」。**「订阅必须先于 POST」这条规则不需要**——登记在 202 返回前就已写入。 |
| D4 | 硬要求：登记必须在 `finally` 里退，任何异常路径都不能漏，否则会留下一条永远 RUNNING 的幽灵，前端卡在 loading。 |
| D5 | **不引 Redis**，SSE 基础设施（`TaskSseConnectionManager` / `TaskSubscriptionRegistry`）**零改动**。 |

**D 为什么不落 DB、也不上 Redis**（这段是本次最重要的取舍，改代码前先读）：

- 它是**一把锁**，不是一份要跨进程重启维持的状态。跑会话的虚拟线程和登记它的表**同生共死**：
  进程重启，会话和登记一起没，不存在"表里有、实际没跑"的窗口。
- **Redis 在这个场景里是负资产**：搬进去之后进程崩了而登记还在，它会躺着喊 RUNNING 而实际什么都没跑
  ——那就**必须**再补 TTL / 心跳 / 启动清理才能把谎话擦掉。这是加法不是减法。
- **只搬登记表也换不来多实例**：本仓本来就是单实例架构（`SemaphoreTaskConcurrencyGate` 是进程内信号量、
  SSE 两个 Map 是进程内的、`SseEmitter` 绑死在处理那条 HTTP 连接的实例上、**工作区是本地文件系统**）。
  真要上多实例，动的是共享存储 + 事件总线 + 分布式闸门，登记表是里面最不值一提的一格。

### E. 前端（本次不做，另开任务）

涉及页面结构重构，整体移出本次范围。将来待办：关键词的可增删标签列表、语言下拉（清单来自
`GET /api/languages`）、字幕展示区的 loading 态、多语种译文之间的选择器。设计文档里会留一节
「前端待办」，免得后面重新推一遍。

### F. 明确不做

| # | 不做的事 | 理由 |
|---|---|---|
| F1 | 提交路径复用旧任务（查到同 `workspace_key` 已有终态任务就不新建） | 独立改造，改的是提交主路径语义，与本次功能无关 |
| F2 | 建工作区表（`workspace` + `task.workspace_id`） | 工作区的实体在磁盘上，建表等于给磁盘事实做一份没有同步机制的 DB 影子；且要把 `task` 两列收成外键是贯穿四层的改造 |
| F3 | 关键词历史快照 | 没有"回溯我上次改了什么"的需求 |
| F4 | 引 Redis / 多实例改造 | 见 D 节 |

## 背景与已确认事实（2026-10-11 勘察，均对照当前代码核实）

| # | 事实 | 证据 |
|---|---|---|
| F1 | 清洗阶段**已经有一个** `reference`（参考术语表）入参，一路透传到提示词的「参考术语表」段 | `domain/subtitle/port/SubtitleCleanser.clean(segments, context, reference, listener)`；`LlmSubtitleCleanser.buildUserPrompt` 末段 |
| F2 | 但生产路径**写死传空串**，这个口子目前是死的 | `application/task/pipeline/SubtitlePipeline:205` `cleanupStage.clean(input, "", listener)` |
| F3 | 已经有一套**自动**术语机制：摘要器读全篇产出 `SubtitleContext.terms`（`asr`/`fixed`/`note` 三字段），清洗拿它做替换 + 相似度基线 + 命中指标 | `LlmSubtitleContextSummarizer`；`shared/subtitle/TermMatcher.hitTermsByAsr`；`LlmSubtitleCleanser.baselineOf` / `termMetrics` |
| F4 | 翻译目标语种是**配置项**，且**烧进了 system 提示词**（构造 bean 时就 `formatted`） | `vidsage.subtitle.translate.target-language`（默认 `zh`）；`config/ChatClientConfig:98,112` |
| F5 | 产物文件名**已经带 `{lang}` 指纹**，天然支持多语种共存 | `domain/subtitle/model/common/SubtitleArtifactSpec` 的 `SEGMENTS_TRANSLATED` / `TERMS` |
| F6 | `POST /api/tasks/{taskNo}/translate` **不收任何语言参数** | `web/controller/TaskController.translate` |
| F7 | **一个工作区目录对应 N 行 task**：同一 URL 重新提交会 `INSERT` 新行、复用同一目录的全部产物 | `VideoTaskService.submitUrl` → `rejectIfActive` 只查 `ACTIVE_STATUSES = {PENDING, RUNNING}`（`MybatisVideoTaskRepository.findActiveByWorkspaceKey`）；`schema.sql` 里 `workspace_key`/`workspace_dir` **无唯一约束**；`VideoTaskTest:300` 注释明说"终态不算 active（重新提交同一输入走断点复用）"。**这就是 A2 外键挂 `workspace_key` 而不是 `task_no` 的原因** |
| F8 | **任务行的状态机是单程的**：终态不可逆，`start()` 硬性要求当前是 `PENDING` | `domain/task/VideoTask` 类注释 + `start()` / `requireStatus`。故"复用旧任务行接着跑"不成立，只能新建行 |
| F9 | SSE 基础设施**按 `taskNo` 一个维度分桶**，广播与清理都作用于整桶 | `web/sse/TaskSseConnectionManager.emitters`、`application/task/subscribe/TaskSubscriptionRegistry.subscribers`；`broadcast(taskNo, …)` 发给该 taskNo 全部订阅者，`complete(taskNo)` 清空整桶。**故三个独立端点会串台**（D3 的由来） |
| F10 | 翻译子状态由一列 ENUM 撑起，串到 SSE 的 `terminal` 判定与重连快照 | `task.translation_status`；`VideoTask.isEventTerminal`；`TaskStageEvent.from` → `fromTranslation` |
| F11 | `Bcp47Language.isSameMainLanguage` **只比主语言子标签**（连字符前那段） | `domain/subtitle/policy/Bcp47Language:82`。故 `zh → zh-Hant` 现在会被判"同语言"直接跳过（C3 要修的就是这条） |
| F12 | 清洗提示词的拼接现状：正文 `jsonQuote` 过；`参考术语表` **原样拼**；`<subsequent_content>` 有声明**无转义** | `LlmSubtitleCleanser:596`（正文）/ `:600-602`（参考术语表）/ `:561-565`（后窗）。后窗内容来自 ASR 转写，**视频作者口述一句就能进提示词**——不是假想威胁 |
| F13 | 摘要失败会**降级成空上下文且不落盘** | `domain/subtitle/policy/CheckpointPolicy.DEGRADED_EMPTY`。故 A4 的第二条通道不是冗余，是防这条静默失败 |
| F14 | 全库只有 `task` 一张表，**没有任何 `FOREIGN KEY`**，且无迁移框架 | `src/main/resources/schema.sql`（33 行）；`spring.sql.init.mode: always` + `CREATE TABLE IF NOT EXISTS` |

## 任务地图

| 子任务 | 覆盖 | 为什么能独立验收 |
|---|---|---|
| `10-11-subtitle-keyword-regenerate` | A + B + **D** | 端到端可验：填关键词 → 重生成 → 字幕里那个名字对了；再改一次 → 又对了 |
| `10-11-subtitle-multilang-translate` | C（**依赖** A 落地的 D） | 端到端可验：选 en 翻一遍、选 zh-Hant 再翻一遍，两份产物共存且简繁真的转了 |

**D 组落在 keywords 那条**（先落地的那条），multilang 那条**依赖它**。这不是依赖系统，只是排序，写进
各自 `implement.md` 的执行顺序里。

## 跨子任务验收标准

- **AC1** 两条线合起来，一条端到端路径跑通：提交任务（带关键词）→ 跑完 → 字幕里发布者名字正确 →
  重跑清洗改一次关键词（仍正确）→ 选 `zh-Hant` 翻译 → 拿到繁体译文；再选 `en` → 两份产物**共存**。
- **AC2** 重跑清洗成功后，旧的 `-translate-*` 与 `terms-*` 全部消失；重跑**失败**时一个都不消失。
- **AC3** 全库不再有 `translation_status` 这一列、这个枚举名，以及所有读它的代码路径。
- **AC4** 后端测试基线（当前 498，见 [[stage-progress-rename-handoff]]）只增不减，全绿。

## 待开工时展开

- 各子任务的 `design.md`（边界、契约、数据流、取舍、兼容与回滚）与 `implement.md`（有序列清单、
  验证命令、审查门、回滚点）。
- 关键词表的完整 DDL（表名、列、索引）与存量库的加表方式（`schema.sql` 无迁移框架，加表容易）。
- 摘要器提示词怎么接纳关键词（A4 通道①的具体措辞）。
- `SupportedLanguage` 枚举的落位与别名表（C1/C3，见子任务 B）。
- 前端待办清单（E 节）。

## Notes

- 任务创建：2026-10-11，三者 `base_branch = dev`，`--no-start`（创建时不激活）。
- 任务卡入库：`0a8ed94 chore(task): 字幕关键词与多语言翻译任务卡入库（父 + 两子）`。
- 兄弟（同日、无关）：`10-11-sse-connection-pairing`（在改 `TaskSseConnectionManager` 的连接表）。
  D5 保证本次不碰那个文件，两个任务不打架。
- 前序相关任务：`09-29-subtitle-term-official-lookup`（术语官方译名查证，产物 `terms-*.json` 由本次 B3 负责删）、
  `09-29-subtitle-translate-qwen-mt`（翻译管线）、`10-08-pipeline-state-broadcast`（`isEventTerminal` 与
  SSE `terminal` 字段的来处，C5 要改的正是这条判据；翻译子状态本身的来处尚未核对，动 `design` 前先确认）。
