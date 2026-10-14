# 移交文档：翻译多语言选择与翻译子状态下线（子任务 B）

## ▶ 当前状态（2026-10-14 · 第四棒，先读这段）

**本任务（子任务 B）已恢复。翻译侧未完事项已逐条按代码实核 → 见 §14；与 §4 冲突处以 §14 为准。**

**本会话（第四棒）已落**

| 项 | 状态 |
|---|---|
| 前置任务 `10-14-subtitle-artifact-naming` | 已完结归档，已合回本分支（`5047c2b`） |
| `translate` 方法体两刀 | ✅ **91 行 → 66 行**。**刀 2** 抽 `reuseCheckpointIfPresent`（`SubtitleTranslationCoordinator:202`）；**刀 3** 抽 `resolveContext`（同文件 `:225`），**顺带收掉 §4.3「决策 5 第二半」**——翻译侧摘要不带关键词，`FRESH_SUMMARY` 改为只打日志、不落盘 |
| 三处失实注释订正 | ✅ 类注释里的旧产物名（`-translate-<target>.json`）、`terms-<route>-<target>.json`、指向已不存在的 `isSameMainLanguage` |
| `props` 死字段 | ✅ 连同构造参数已清（项目所有者执行）；`import SubtitleTranslateProperties` 悬空，待顺手删 |
| 编译 | ✅ `mvn clean compile` = BUILD SUCCESS |
| 路径来源那 4 行 `resolve` | **有意不动** —— `SubtitleWorkspaceFilePaths.of()` 是扫盘快照，给不出「还不存在」的写目标；它真正的消费者是 `deleteTranslatedArtifacts`。详见 §14.5 |

**下一步（全表见 §14.2）**

1. **A · 批 3 登记表接管翻译会话** —— 卡着 AC9 + AC10，`regenerate:233-255` 是现成样板
2. **B · #2 翻译失败收口** —— 失败现在被广播成「翻译完成」
3. **E · 过时判据**（译文 §14.4 + 术语表 §14.6，**都是零判据**）—— 推荐方案「乙 + 甲」

**A 与 B 建议一起做**：都动 `SubtitlePipeline` + `TaskProgressNotifier`，分两次改同一个文件不划算。

**⚠ 两条硬边**

1. **翻译侧没有自动化网**：`SubtitleTranslationCoordinatorTest` 仍在父任务那 12 个编不过的文件里
   → 本会话改动的验收只能靠**运行期冒烟**（断点复用 / 源=目标跳过 / 术语查证，三条路各走一遍）
2. **批 4 已做而批 3 未做**（design §6 要求反过来）：正处在「并发不拦 + 重连看不到正在翻译」的窗口

**未提交改动**：`backend/src/main/.../SubtitleTranslationCoordinator.java` + 本文件。**未归档。**
硬门不变：完成即停等审查；提交 / 合并 / 归档逐门等批准；合回 `feature/subtitle-multilang-translate`。

---

> 【以下是第三棒（2026-10-14）的交待，写于前置任务 `10-14` 开工前，保留作史实。其中
> 「读工作区里有什么产物**可以直接用** `SubtitleWorkspaceFilePaths.of(workDir)`」一条**已被第四棒核否**
> —— 它是扫盘快照、给不出还不存在的写目标，见 §14.5；另 §2.4 的残留清单也已被 §14.3 订正】

## ▶ 当时状态（2026-10-14 · 第三棒）

**前置任务 `10-14-subtitle-artifact-naming` 已完结并归档 —— 本任务可以恢复了。**

- **它落地了什么**（这些直接改变本任务的写法，详见它的 `implement.md`）：
  - 字幕产物名去路线化：`segments-asr-cloud-clean.json` → `clean-segments.json` 等 8 项，
    翻译侧是 `translated-{segments-<lang>.json,subtitles-<lang>.srt}` 与 `terms-<lang>.json`
  - `SubtitleRoute` 整体退场：枚举删、**五个 record 的 `route` 字段一并删**
    （`SubtitleTrack` / `TranslatedTrack` / `SubtitleArtifact` / `CleanedSubtitle` / `TranslatedSubtitle`）、
    `ASRProvider.route()` 删
  - 新增 `application/workspace/SubtitleWorkspaceFilePaths.of(Path)`——扫一遍工作区出产物清单
    （字幕 5 项 + 译文按语种成组 + 媒体 3 项，**缺席一律 `Optional` 且不抛**）+ 单独成文件的
    `TranslationFilePaths`（一个语种三件套：`langCode` / 译文 json / 双语 SRT / terms）
  - `SubtitleArtifactSpec`：`fileName(route, lang)` → **`getFileName(lang)`**，常量名全换
    （`SEGMENTS_RAW` → `ORIGINAL_SEGMENTS` …），新增 `getLanguage(String)` 反解语种
  - `source.*` 名字族收进 `MediaInput`（`SOURCE_FILE_PREFIX` / `SOURCE_INFO_JSON_NAME`）、
    音频前缀收进 `AudioArtifact.AUDIO_PREFIX`；新增 `application/exception/InvalidWorkDirectoryException`
- **对本任务的直接影响（写代码前先看这几条）**：
  1. 构造那五个 record 时**少传一个 `route` 参数**；`track.route()` 之类全部不存在了
  2. `SubtitleTranslationCoordinator` 与 `SubtitleCleanupCoordinator` 的构造参数里
     **已经没有 `asrProvider`**（死字段已清）；`SubtitlePreparationCoordinator` 保留它（要用 `requiredAudioSpec()`）
  3. 读工作区里有什么产物，**可以直接用** `SubtitleWorkspaceFilePaths.of(workDir)`
     —— 这正是 §11 第 1 刀要的东西
  4. 测试树里本任务那 12 个文件仍编译不过（`translate(...)` 签名 + `translationStatus` 删），
     **10-14 没有还这笔债**，红面就是它们
- **分支**：`feature/subtitle-multilang-translate` 已合并 `feature/subtitle-artifact-naming`
- **回来第一步**：仍读 **§11 本任务的恢复点**

---

> 【以下是 2026-10-14 第二棒的交待，写于 10-14 前置任务开工前，保留作史实】

## ▶ 当时状态（2026-10-14 · 第二棒）

**本任务已暂停，停在「字幕产物命名」任务之前。**

- **读这份文档的时机**：子任务 **`10-14-subtitle-artifact-naming`**（字幕产物命名去路线化与
  `SubtitleRoute` 退场）**结束之后**。在它结束前读，看到的是「正在等它」的状态。
- **本任务做到哪了**：核心功能已提交 **`ffe641a 翻译多语言选择：mvp跑通`**（25 个文件）。
  多语种选择 / 跳过判据 / 删 DB 翻译子状态 / 白名单 400 都已落地并做过运行期实测；
  **剩下的是 §4 那批未完事项**（批 3 登记表接管、#2 翻译失败收口、批 5 `GET /api/languages` 等），
  一条都没少。
- **为什么停在半路**：做 `translate` 方法拆分时发现一条更大的前置——字幕产物命名要按语义重排
  （文件名去掉 `{route}` 段）、`SubtitleRoute` 整体退场、并落地一个「工作区文件路径」record。
  那件事的产出形态**决定了本任务 `translate` 拆分的形态**，所以先切出去单独做。
- **回来后第一步**：读 **§11 本任务的恢复点**（`translate` 拆分的逐刀结论 + 未完事项清单）。
- **注意所在分支**：本任务的分支 `feature/subtitle-multilang-translate` 之外，已经切出
  `feature/subtitle-artifact-naming` 并在其上工作；本任务恢复时先确认自己站在哪条分支上。

> 【以下是 2026-10-13 第一棒的交待，大部分仍成立，但「全程未提交」等几处已被 §8 订正】
>
> 2026-10-13 会话移交。任务目录 `.trellis/tasks/10-11-subtitle-multilang-translate/`，
> 父任务 `10-11-subtitle-keywords-multilang` 的**子任务 B**，状态 `in_progress`。
> 分支 **`feature/subtitle-multilang-translate`**（从 `dev` 切出 → **合回 `dev`**）。
> 需求在 `prd.md`，原技术方案在 `design.md`，逐批代码在 `implement.md`——
> **但先读第 0 节，那两份文档已经部分失效。**

---

## 0. 最重要的一条：分工与文档效力

- **`backend/src/main` 由用户（HeQiu）手写；AI 负责 `backend/src/test`、spec 更新、运行期验收。**
  这是本会话用户明确划定的：「main 代码由我写」。
- **`implement.md` / `design.md` 只是参考稿，不是契约。** 用户原话：「业务流程我自己来补……
  implement 文档我不会完全参考，甚至有的地方会做出改变」「原先的业务流程有点问题」。
  → **判据一律以实际代码为准**；文档与代码不符时，按仓库惯例订正
  （`prd.md` 删除线+日期，`design.md`/`implement.md` **直接重写为现状**）。
- **`src/test` 也不再是 AI 独占**：用户本会话直接改过
  `VideoTaskServiceTest` / `SubtitlePipelineTest` / `TaskControllerTest`。接手前先看 `git diff`，别覆盖。

---

## 1. 已实现但**尚未落进 prd/design** 的口径

这些是本次会话里定下来（或用户直接写进代码）的口径，**文档里还没有**。
接手第一件事：逐条决定「落文档」还是「推翻」。

| 口径 | 现状 / 代价 |
|---|---|
| **源语种也必须落在白名单**（`VideoTaskService.translate` 对 `sourceLanguage` 也 `parse`） | 已实现。产品代价：`de → en`（德语视频译英语）**关闭**；源语种成了**调用方断言**且没有服务端兜底（`resolveSourceLanguage` 作废） |
| **跳过判据 = 枚举相等**，取代「只比主语言子标签 + 中文正简脚本」那套 | 已实现（`SupportedLanguage#isSameLanguage`）。`zh→zh-CN` 仍跳过、`zh→zh-Hant` 真翻 ✓。**连带**：`Bcp47LanguagePolicy` 三个方法失去调用者 → 该类可整体删除 |
| **开始建自定义异常体系**（`UnsupportedLanguageException` + `ApiExceptionHandler` 一条映射 → 400） | 已实现。spec `error-handling.md:62`「项目当前**不定义自定义异常类**」与 `:154` 反模式行「当前代码没有」**已失实**，待订正（与 `:175` 自相矛盾） |
| **新增顶层桶 `domain/exception/`** | spec 的 domain 是**按上下文分桶**（`domain/<上下文>/{port,policy,model}`）。此桶是否登记进 spec、还是把异常移到 `domain/subtitle/exception/`，**未定** |
| `SupportedLanguage` 落 `domain/subtitle/model/translation/` | design §2.1 写的是 `model/common/`（理由「被 common 的 SubtitleArtifactSpec 引用」）。**以实现为准**，design 待改 |
| 枚举值名 `SIMPLE_CHINESE` / `TRADITIONAL_CHINESE` / `ENGLISH` / `JAPANESE`；字段 `matchKeys`（**含 code 本身**） | design §2.1 写的是 `ZH_HANS` / `aliases`（不含 code），**已作废**，design 待改 |
| 异常消息文案 | 现在是 `"当前输入语种无法匹配支持语种，输入：de"`——**只有「缺什么」，没有「怎么补」**，违 `error-handling.md:80`（该消息直接展示给前端）。且源/目标共用同一异常，看不出是哪个参数错 |
| ~~早前讨论的字段名 `recognizedTags`、`code` 用标准大小写 `zh-Hans` + `fileNameToken()` 取小写~~ | **作废**：用户实现为 `matchKeys` + `code` 直接小写口径。以代码为准 |

---

## 2. 本会话已完成（含验证）

### 2.1 批 0（类改名）—— 非本会话所做，只做了复核

- `Bcp47Language` → `Bcp47LanguagePolicy` 已提交 **`84bbac7`**（作者 HeQiu，早于本会话）。
- 复核：`grep -rn "Bcp47Language\([^P]\|$\)" backend/src/` 为空 ✓；`mvn test` = 570 / 0 err / 0 fail。
- `implement.md` 批 0 里「已在工作区完成，**待提交**」「索引里只有两个 rename」两句**已订正**为「已提交 84bbac7 / 工作区干净」。

### 2.2 基线

`mvn clean test` 在 `84bbac7` 上实测 **tests=570 errors=0 failures=0 skipped=1**（`implement.md` 预期的 570 对得上）。

### 2.3 目标语种链路（端到端）

- 新增 `domain/subtitle/model/translation/SupportedLanguage`：`code` / `label` / `matchKeys`（**code 也在 matchKeys 里**）、
  `matches`（大小写不敏感；**缺 `trim()`**）、`parse`（不中 → 抛异常）、`getFirstMatchLang`、`isSameLanguage`。
- 新增 `domain/exception/UnsupportedLanguageException`（`extends RuntimeException`）+ `ApiExceptionHandler:48` 映射 **400 + WARN**。
- `VideoTaskService.translate` **第一行**两个 `parse`（**早于 `loadTask` 的 I/O** ✓），往下**传枚举**；
  `SubtitlePipeline.translateTask` / `SubtitleTranslationCoordinator.translate` 签名都收 `SupportedLanguage`。
- 产物名 / 提示词 / `TranslatedTrack` / 日志**全部走 `getCode()`**（不再用调用方原始串）；
  端口 `SubtitleTranslator` 与 `TermLookupCommand` 仍是 `String`（未把枚举泄进端口 ✓）。

### 2.4 删面（DB 翻译子状态）

已完成：`VideoTask`（字段 + 三个迁移方法 + getter）、`TaskStageEvent`（`fromTranslation` + 那条分支）、
`TaskProgressMessage`（分量 + `terminal` 派生 + `of` 收三参）、`TaskProgressNotifier`（两处实参）、
`TaskSnapshot`、`TaskResponse`、`VideoTaskEntity`、`MybatisVideoTaskRepository`、`commonTransition`。

**仍未删干净**：`domain/task/model/TranslationStatus.java`（文件还在）、`VideoTask` 里还剩 2 处
`translationStatus`（构造器形参 + `@param`，无人使用）、`resources/schema.sql` 的 `translation_status` 列
与迁移说明、`domain/task/package-info` 里那句。
→ **AC7 的 grep 现在还不为空。**

### 2.5 运行期端到端验证（真实素材，2026-10-13）

素材：`https://www.bilibili.com/video/BV1Lx4y1s7Xi/?spm_id_from=333.1391.0.0&vd_source=...`
任务号 `432e2c4cd0cf4bb2869e273150ec42aa`，工作区
`backend/data/works/https___www.bilibili.com_video_BV1Lx4y1s7Xi__spm_id_from=333.1391.0.0&vd_source=-a9d3b17ddea4`。

| AC | 结果 | 证据 |
|---|---|---|
| **AC2 多语种共存** | ✓ | 三份并存：`-translate-en.*`（新）+ `-translate-zh-hant.*`（12:38 时间戳**未变、未被覆盖**）+ `-translate-zh-hans.*` |
| **AC3 简繁真转** | ✓ | 源 `传剑或者叫弯顶，是我们剑术中非常重要的一个技巧。` → 译 `傳劍或者叫彎頂，是我們劍術中非常重要的一個技巧。`（**真繁体**） |
| **AC4 简中不误翻** | ✓ | `zh-Hans` → 日志 `skipped=true` + SSE `state=SKIPPED`；无 LLM 调用 |
| **AC6 幂等** | ✓ | 再点 `zh-Hant` → 日志「字幕翻译断点复用跳过」、`cost=6ms`、未调 LLM |
| **落盘 / 接口可见** | ✓ | 6 份产物出现在 `GET /api/tasks/{taskNo}` 的 `artifacts` 里（此前怀疑的「落盘有问题」**不成立**） |
| **#1 白名单 400** | ✓ | `{"targetLanguage":"de"}` → **400** `{"error":"当前输入语种无法匹配支持语种，输入：de"}`（不再是 500）；`BOGUS` + 合法语种 → 404（证明 parse 在 `loadTask` 之前） |
| 译文内容 | ✓ | en 译文 `传剑 or 弯顶 is a very important technique in our swordsmanship.`（术语按 system prompt 保留原样） |

### 2.6 运行期**未过**的 AC

| AC | 状态 | 原因 |
|---|---|---|
| AC1 `GET /api/languages` | ✗ **404** | 批 5 未做 |
| AC5 白名单外 400 | ✓（已过） | 探针 2/4 |
| AC7 后端零残留 | ✗ | 见 2.4 |
| AC8 失败原因落 `error_message` | ✗ | 见 4.2（#2 未修，且现在失败会被广播成「翻译完成」） |
| AC9 翻译期间 SSE 收进度 | ✗ | 见 4.1——**批 3 之前必然失败** |
| AC10 并发 409 | ✗ | 登记表未接，**实测不拦**：本会话误连发三次翻译，三个翻译全部并发跑完 |

> AC9 的机制说明：`TaskProgressNotifier.createSnapshot` 走 `findRunningStage(workspaceKey)`，
> 而**登记表现在没有任何写入者** → 永远 `null` → 快照渲染成主状态终态 → `terminal=true`
> → 订阅连接**立刻被关**。实测：触发前订阅，拿到的唯一事件就是
> `{"stage":{"stage":"clean-subtitles",…,"state":"SUCCEEDED"},"terminal":true}`。

---

## 3. 编译与测试现状

- `mvn clean test-compile`：**`src/main` 全绿 ✓；`src/test` 全红 ✗**。
- 用户要求：**测试代码先别管**（main 还没全部改完）。
- 波及的测试文件（9 个，javac 报错到 100 条上限，实际更多）：

| 文件 | 大致要改什么 |
|---|---|
| `application/subtitle/pipeline/SubtitleTranslationCoordinatorTest` | `translate(input)` → `translate(input, 源, 目标[, listener])`；`hasTranslationArtifact` 带语种；补 mtime 判据 |
| `application/task/pipeline/SubtitlePipelineTest` | `translateTask` 新签名；`translationStatus` 断言；幂等/并发/失败用例**要按登记表落地后的形态重写** |
| `application/task/VideoTaskServiceTest` | `translationStatus`、`succeededWithTranslation(...)`、`restore(...)` 少一个参数 |
| `application/task/message/TaskProgressMessageTest` | `of(...)` 四参→三参；构造器五参→四参 |
| `application/task/notify/TaskProgressNotifierTest` | `fromTranslation` 断言、`translationStart/Succeed` |
| `domain/task/VideoTaskTest` | `translation*` 用例整批删；`isEventTerminal` 断言删；补 `recordTranslationFailure`/`clearTranslationFailure` |
| `infrastructure/persistence/repository/MybatisVideoTaskRepositoryTest` | 减字段 |
| `web/controller/TaskControllerTest` | translate 带 body；`translationStatus` 断言 |
| `web/sse/TaskSseConnectionManagerTest` | 事件不再带子状态，改由 `stage`/`terminal` 表达 |

**建议分两拨改**（避免改两遍）：与批 3/批 4 无关的（Coordinator 签名与语种用例、新 `SupportedLanguageTest`）
现在就能改；与登记表/失败收口强相关的等 4.1/4.2 落地后一次性改。

---

## 4. 未完事项（按优先级）

### 4.1 批 3 · 登记表接管翻译会话（AC9 + AC10）—— 最优先

**硬边提醒**：design §6 要求「批 3 **必须先于**批 4（删 DB 子状态）」。**批 4 已经做了**，
所以现在正处在 design 警告的那个窗口里——「并发不重入」和「重连看到正在翻译」**两个能力同时缺席**。
本会话的运行期实测已经踩到（三次翻译并发全跑完）。

要做的（形状照 `VideoTaskService.regenerate`，它是 A 已落地的样板）：

- `VideoTaskService.translate`：主状态校验(400) → `taskSessionRegistry.tryStart(workspaceKey, TRANSLATE_SUBTITLES)`，
  抢不到 → `TaskConflictException`（409，**附既有任务快照**——快照组装只有 service 有）→
  `boolean handedOff` + `finally { if (!handedOff) finish(...); }`
- `SubtitlePipeline.translateTask`：**只负责 `finally` 退登记**（管线不抢锁，这是 A 定的分工，
  见 `SubtitlePipeline` 的类注释「互斥抢锁在调用方，本类只负责 finally 退登记」）；
  收敛支、异步体 `finally`、排队被打断回调**三条退出路径各退一次**。
- 顺带把 `regenerate` 的 409 文案从「已有一次字幕重新生成在跑」改成「已有一次**后置会话**在跑」。

### 4.2 #2 翻译失败收口（AC8）

现在 `SubtitlePipeline.failTranslation`（私有）失败路径上调的是 `taskProgressNotifier.completeTranslation(task)`
→ **失败被广播成「翻译完成」**、`error_message` 不落库。三块要连着补：

1. `VideoTask` 补 `recordTranslationFailure(String)` / `clearTranslationFailure()`（照 `recordRegenerateFailure:211` / `clearRegenerateFailure:240` 的形状；`TRANSLATION_FAILURE_PREFIX` 已存在）；
2. `TaskProgressNotifier` 补 `failTranslation(task, reason)`（照 `failRegenerate:97`，换阶段名与前缀）；
3. 删掉管线那个私有 `failTranslation`，两处调用点（排队被打断、异步体 catch）改调 notifier。

### 4.3 其余功能项

- **决策 6**：成功路径（`runTranslationPipeline`）补 `task.clearTranslationFailure()`，否则上一次的「翻译失败：」永远挂着。
- **决策 5 第二半**：`SubtitleTranslationCoordinator` 里 `case FRESH_SUMMARY -> ArtifactFiles.writeJsonAtomically(context, …, ctxFile)`
  **仍在落盘**，而它写的那份用的是**空关键词** → 覆盖清洗侧带关键词的摘要且不报错。应改成只打日志、不落盘。
- **决策 5 第一半**（mtime 判据）也未见实现（`isStaleAgainst` 是否已加待确认）。
- **死代码**：`hasTranslationArtifact` 零生产调用（幂等目前靠 coordinator 内部断点复用兜住，所以「不发 LLM」仍成立）；
  `props.targetLanguage()` 只剩它那一处使用 → `SubtitleTranslateProperties.targetLanguage` 与 `application.yml` 两行可删。
- **`Bcp47LanguagePolicy` 归零**：`resolveTargetLanguage`/`isSameMainLanguage`/`resolveSourceLanguage` 都已无调用者，
  只剩 `skipAll`（建议搬 `TranslatedTrack.skipped(route, source, target, segments)`）→ 搬完可**整类删除**（连测试）。
- **批 5**：`GET /api/languages`（`LanguageOptionSnapshot` + `SubtitleLanguageService` + `LanguageResponse` + `LanguageController`），
  经应用层读模型暴露（`web → domain` 是禁例）。AC1 靠它。

### 4.4 spec 更新清单（AI 负责）

- `error-handling.md`：`:62` 与 `:154` 两处失实订正；409 场景从「同工作区已有重新生成」扩成「已有**后置会话**（重新生成**或**翻译）」。
- `directory-structure.md`：`:68` 政策件清单划掉 `Bcp47LanguagePolicy`（若整类删除）；
  登记 `domain/exception/` 这个新桶；登记 `SupportedLanguage` 的值对象落位；`config/` 补一句「翻译目标语种不再走配置」。
- `frontend/*`：`translationStatus` 字段消失、`translate` 要 body、新增 `GET /api/languages`（**前端任务待办**，本次不改前端）。

### 4.5 前端待办（父任务 E 节，另开任务）

`POST /{taskNo}/translate` 现在**要 body**（`{"sourceLanguage":…,"targetLanguage":…}`）；
`TaskResponse.translationStatus` 与 SSE 里的 `translationStatus` 没了 → 前端 `isTranslating` 必须改判据
（看 `stage.stage == "translate-subtitles"` 且 `state == "RUNNING"`，或 `terminal`）；
下拉清单取 `GET /api/languages`。
**另外**：订阅与触发的时序是硬的（见 2.6）——**必须先触发再订阅**，否则会被终态快照立刻关掉。

---

## 5. 环境备忘（本会话实测）

```bash
# 判编译：一律 clean（增量编译会给假绿：没被碰过的文件即使编译不过也不报）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q clean test-compile

# 起服务：测试红着时必须跳过测试编译（spring-boot:run 绑 test-compile；-DskipTests 不够）
cd backend && SERVER_PORT=18080 JAVA_HOME=/f/java_soft/java21 \
  mvn -q -Dmaven.test.skip=true spring-boot:run > /tmp/vidsage-run.log 2>&1

# 收工：按 PID 杀子 JVM（TaskStop 只杀 mvn 包装进程，子 JVM 继续占端口）
netstat -ano | grep LISTENING | grep :18080   # 查 PID
taskkill //PID <pid> //F

# 推送（直连 GitHub 必失败）
https_proxy=http://127.0.0.1:7897 git push
```

SSE 抓取：**提交与订阅放同一条 shell**；`--max-time` 留足。**注意**：触发前订阅会被终态快照立刻关掉（见 2.6）。

---

## 6. 当前残留

- **后台服务仍在跑**：PID **36208**，端口 18080，日志 `/tmp/vidsage-run.log`。
  后台任务 ID `bemdhhiq5`（`TaskStop` 杀不掉子 JVM，要按 PID 杀）。
- 测试任务号 `432e2c4cd0cf4bb2869e273150ec42aa`，工作区见 2.5（**别删**，后续验收还要用它的产物）。
- SSE 抓取文件：`/tmp/sse-main.txt` / `sse-tr.txt` / `sse-en.txt` / `sse-zh-Hans.txt` / `sse-zh-Hant.txt`。
- 未提交改动：19 个 `src/main` 文件 + 3 个 `src/test` 文件；`design.md` / `implement.md` 仍是 **untracked**。
  **本任务全程未提交任何一笔**（用户未批准）。

---

## 7. 工作流约束（别忘）

- 硬门：**完成即停等审查**；禁止链式 commit / push / merge / 归档；提交、合并、归档**逐门等批准**。
- 分支：从 `dev` 切出的 `feature/subtitle-multilang-translate` → **合回 `dev`**（不是 master）。
- 未归档期间，每轮回复结尾提醒「任务 … 未归档」。

---

# 2026-10-14 移交（第二棒）：本任务暂停，让位给子任务

## 8. 状态变化（订正第一棒）

- **本任务已提交**：`ffe641a 翻译多语言选择：mvp跑通`（25 个文件，含本目录的 `design.md` /
  `implement.md` / `handoff.md` / `task.json`）。§6 里「本任务全程未提交任何一笔」那句**已失实**。
- **分支**：本任务分支 `feature/subtitle-multilang-translate` 停在 `ffe641a`。
  已从它切出 **`feature/subtitle-artifact-naming`**（命名任务用），**当前工作区在那条分支上**。
  命名任务完成后合回 `feature/subtitle-multilang-translate`，本任务再在上面继续。
  **恢复本任务时先确认自己站在哪条分支上。**
- **未提交项**（切分支时一并带过去了）：

  | 路径 | 归属 |
  |---|---|
  | `backend/…/domain/shared/model/SubtitleWorkspaceFilePaths.java`（新） | 命名任务（形态依赖新命名） |
  | `backend/…/application/subtitle/pipeline/SubtitleTranslationCoordinator.java` | 在途编辑 |
  | `backend/…/domain/transcription/model/TranscriptionSegment.java` | 在途编辑 |
  | `.trellis/tasks/10-11-…/handoff.md`、`task.json` | 本次文档更新（父任务的 `children` 已加上新任务） |
  | `.trellis/tasks/10-14-subtitle-artifact-naming/`（新目录） | 命名任务 |

- **`src/main` 本轮未动一行**（10-14 会话只读盘 + 查代码 + 建任务）。
- **接手时先看 §0–§7**，那份仍然成立；§9 起是本次新增。恢复点在 **§11**。

## 9. 本会话（10-14）定下的口径

### 9.1 结论：产物命名去路线化（这引出下面的新任务）

定案：**字幕产物文件名不再带来源路线**，改按产物语义命名，`SubtitleRoute` 随之整体退场。
起因是审计出 `route` 在本仓库**零判据**——它的全部出现只有「进文件名」与「被 record 透传」两类：

| 类别 | 位置 | 有判据？ |
|---|---|---|
| 进文件名 | `SubtitleArtifactSpec.fileName` 的 `{route}` 段 + `RouteAlternation` | ✅ 断点判据靠它区分 |
| 从 provider 问一次 | `asrProvider.route()`（3 处 coordinator）+ `DashScopeAsrProvider.route()` | — |
| 被 record 透传 | `SubtitleTrack` / `TranslatedTrack` / `SubtitleArtifact` / `CleanedSubtitle` / `TranslatedSubtitle` | ❌ 无人读 |
| 只被写回外层 | 三处 `new XxxSubtitle(…, track.route(), …)` | ❌ 纯搬运 |

**别搞混的两组 local/cloud**（项目所有者明确纠正过）：

- **媒体获取**：`YtDlpMediaAcquirer`（URL 下载）/ `LocalFileMediaAcquirer`（本机文件），
  按**输入子类型**分派（`MediaAcquirer` 端口 javadoc 原话：「由输入子类型定，不由配置定」）
  → **与本变更无关，不动**
- **ASR 路线**：`SubtitleRoute.ASR_CLOUD` / `ASR_LOCAL`，由 `ASRProvider.route()` 回答
  → 就是它退场。注意 `infrastructure/provider/asr/` 下**只有** `DashScopeAsrProvider` 一个实现，
  `ASR_LOCAL` 是没有实现的预留枚举值

### 9.2 工作区文件的消费者总表（设计 record 的依据，实测得出）

| 文件 | 内部消费者 | 可下载 |
|---|---|---|
| `source.<ext>` | `AudioPreparationCoordinator` → `audioExtractor.extract(media.mediaPath(), …)` | ✗ |
| `source.info.json` | **只有 `YtDlpMediaAcquirer`**（写后读回拿 ext/title/duration + 断点复用判据） | ✗ |
| `audio-*.m4a` | `SubtitlePreparationCoordinator` → `transcriber.transcribe(AudioSource.AudioFilePath(…))` | ✗ |
| `segments-*.json` | 各阶段**检查点**，下一阶段读它当输入 | ✓ |
| `subtitles-*.srt` | **零消费者** | ✓ |
| `context-*.json` | cleanup 与 translation 都读（缓存命中判据） | ✗ |
| `terms-*.json` | translation 的 `readOfficialTerms` | ✗ |

规律：**JSON 都是有消费者的检查点，SRT 都是零消费者的下载件**；例外是 `context` / `terms`
两个 JSON——检查点但**不可下载**（内部件）。`AcquiredMedia.infoJsonPath` 是**死字段**（生产零调用，
只有 3 处测试断言）。

### 9.3 `SubtitleWorkspaceFilePaths` 的形态（已定）

- 翻译相关**不能**用三个平行 `List<Path>`：`terms` 与 `translate` 份数不保证相同
  （`SKIP_NO_TERMS` / `SKIP_LOOKUP_DISABLED` / 全部查证失败都不落盘）
  → 用嵌套元素 `TranslationFiles(targetLanguage, segments, srt, terms)`
- 缺席一律 `Optional.empty()`，不抛——「没找到就走下游判重跑」
- 工厂 `of(Path workDirectory)` 只能**扫目录**（route 与语种推不出来），
  代价是 I/O 进了 domain（spec 写着 domain 的构造器不许 I/O）
- **该 record 已移交新任务**（形态依赖新命名，见 §10）

## 10. 新任务：`10-14-subtitle-artifact-naming`

- 目录 `.trellis/tasks/10-14-subtitle-artifact-naming/`，**child of 本任务**
- 分支 `feature/subtitle-artifact-naming`，`base_branch` = `feature/subtitle-multilang-translate`
  —— **完成后合回本任务的分支，不是 dev**
- 范围：R1 新命名表（8 项）、R2 `raw`→`original` 词汇对齐、R3 `SubtitleRoute` 退场、
  R4 落地 `SubtitleWorkspaceFilePaths`、R5 存量不迁移（`data/works` 改名留档，重跑）
- **完成后回本任务**，本任务接着做：`SubtitleTranslationCoordinator.translate` 的方法拆分
  （用新 record 替换内联的路径推导），以及 §4 其余未完事项

## 11. 本任务的恢复点

回到本任务时，`translate` 的拆分边界（10-14 会话讨论，尚未落地）：

| 刀 | 内容 | 结论 |
|---|---|---|
| 1 | 路径派生 → 用 `SubtitleWorkspaceFilePaths` 替代 | **已移交新任务** |
| 2 | 断点复用 → `reuseCheckpointIfPresent(...)` 提前 return | 待做 |
| 3 | 上下文解析（20 行 switch）→ `resolveContext(...)` | 待做。**底下压着一个真 bug**：`case FRESH_SUMMARY -> writeJsonAtomically(...)` 用的是**空关键词**摘要，会覆盖清洗侧带关键词的那份且不报错 |
| 4 | 翻译主体（17 行，要回传 3 个值） | **不抽** |
| 5 | 收尾（9 行） | **不抽** |
| — | 方法头的 2 条守卫（8 行） | **不抽**（兄弟类 `doClean` 的三条守卫也是内联的） |

§4 其余未完事项（批 3 登记表接管、#2 翻译失败收口、批 5 `GET /api/languages` 等）**全部仍然有效**，
本节未改动它们。

## 12. 本会话顺带查实、要写进 spec 的失实项

1. `error-handling.md:62` / `:154`：项目**已定义自定义异常**（`UnsupportedLanguageException` +
   `ApiExceptionHandler` 一条映射 → 400），与「项目当前不定义自定义异常类」矛盾
2. `directory-structure.md:68`：`domain/task/policy/TaskArtifactScan`（产物可见性白名单）
   **在代码里已不存在**，全仓 grep 只在文档里命中；白名单现在是 `SubtitleArtifactSpec.isDownloadable`
3. `directory-structure.md:391`：说 `shared/subtitle/` 含「产物命名」，实际那目录只有
   `SrtRenderer` / `SubtitleSegmentSplitter` / `TermMatcher` / `TextSimilarity` 四个算法助手
4. 本任务 `implement.md` 里「`Bcp47LanguagePolicy` 归零可整类删除」「`props.targetLanguage()` 可删」
   **两条均失实**：`SubtitleArtifactSpec:67` 生产调用 `resolveTargetLanguage`，
   `ChatClientConfig:116` 生产调用 `translateProperties.targetLanguage()`（喂翻译 system prompt）
   → 该类只能删 `isSameMainLanguage` + `resolveSourceLanguage`（连测试），`application.yml` 那两行**不能删**

## 13. 环境备忘补遗（10-14 实测）

- **yt-dlp 就是仓库自带那份**：`tools/yt-dlp.exe`，版本 **2026.08.19**，与盘上 info json 的
  `_version` 完全一致 → 复现实验用它，不要用 PATH 里的
- **`--clean-info-json` 是 yt-dlp 的默认**（help 里标 `(default)`）。实测：默认 45 个顶层字段，
  加 `--no-clean-info-json` 是 58 个；被剥掉的含 `_filename` / `filename` / `requested_formats`
  （**后者含 B站 CDN 的签名 URL**）。→ 项目注释里「info json 不含路径字段（实测三项全缺）」
  的原因**不是站点特性，是这条旗标默认值**，建议订正
- **工作区根**：`vidsage.workspace.root` 默认 `./data/works`（相对 `backend/`），
  `application-local.yml` 没覆盖；`backend/data/` 已在 `.gitignore` 内
- 本会话全程**未改任何代码**，只读盘 + 查代码 + 建任务

---

## 14. 第四棒（2026-10-14）实核：翻译侧还剩什么

本节**逐条按代码实核**，不抄 §4；与 §4 冲突的以本节为准。

### 14.1 本会话已落的

- `SubtitleTranslationCoordinator.translate` 方法体两刀（**91 行 → 66 行**）：
  - **刀 2** 断点复用抽 `reuseCheckpointIfPresent`（`SubtitleTranslationCoordinator:202`）
  - **刀 3** 上下文解析抽 `resolveContext`（同文件 `:225`）—— **顺带收掉 §4.3「决策 5 第二半」**：
    翻译侧摘要不带关键词，`FRESH_SUMMARY` 改成只打日志、不落盘
- 注释订正三处：类注释 `-translate-<target>.json` → 真实产物名、
  `terms-<route>-<target>.json` → `terms-<target>.json`、
  `isSameMainLanguage`（已不存在）→ `{@link SupportedLanguage#isSameLanguage}`
- `SubtitleTranslationCoordinator` 的 `props` 死字段连同构造参数已清（项目所有者执行）；
  第 5 行的 `import SubtitleTranslateProperties;` 现在悬空，待顺手删
- `mvn clean compile` = **BUILD SUCCESS**，IDE 诊断该文件为空

**未提交、未归档。** `SubtitleTranslationCoordinatorTest` 仍在父任务那 12 个红文件里
→ 本轮改动**没有自动化网**，验收只能靠运行期冒烟（断点复用 / 源=目标跳过 / 术语查证三条路）。

### 14.2 仍未做（按优先级）

| # | 事项 | 现状（实核） |
|---|---|---|
| **A** | **批 3 · 登记表接管翻译会话** | `VideoTaskService.translate:196-202` 只 parse + loadTask + 委托，**没有 `tryStart`、没有 409**；`regenerate:233-255` 是现成样板。后果：AC9 收不到翻译进度、AC10 并发**实测不拦**（三次翻译并发全跑完） |
| **B** | **#2 翻译失败收口（AC8）** | `SubtitlePipeline.failTranslation:302-305` 调的是 `notifier.completeTranslation(task)` → **失败被广播成「翻译完成」**、`error_message` 不落库。缺三块：`VideoTask.recordTranslationFailure/clearTranslationFailure`（grep 为空）、`TaskProgressNotifier.failTranslation`（只有 `failRegenerate:98`）、管线里两处调用点改调 notifier |
| **C** | **批 4 没删干净（AC7）** | 见 14.3 |
| **D** | **批 5 · `GET /api/languages`（AC1）** | 全仓 grep 为空：`LanguageController` / `LanguageResponse` / `LanguageOptionSnapshot` / `SubtitleLanguageService` 一个都没有 |
| **E** | **过时判据（今天新挖）**：译文 + 术语表 | **都是零判据**，且**共用同一把刀**（只在 `regenerate` 删）。译文见 14.4；术语表见 14.6，另有一条「部分失败永不补查」的硬伤 |
| **F** | 死代码 | `Bcp47LanguagePolicy.resolveSourceLanguage:48` **零调用者**（可删）；同类的 `resolveTargetLanguage` 被 `SubtitleArtifactSpec:67` 生产调用（**不能删**）、`skipAll` 有调用者 |
| **G** | spec 尾巴 | `error-handling.md:62/:154`「项目当前不定义自定义异常类」失实；409 场景未扩成「后置会话」；`directory-structure.md` 里 `TaskArtifactScan` 已不存在 |
| **H** | 前端 | `translate` 要 body、`translationStatus` 已消失、新增 `GET /api/languages`（另开任务） |

### 14.3 批 4 的残留（AC7 的 grep 还不为空）

| 残留 | 位置 |
|---|---|
| 枚举本体 | `domain/task/model/TranslationStatus.java` |
| **4 处悬空 import**（只剩 import，无实际使用） | `TaskProgressMessage:6` / `TaskSnapshot:5` / `VideoTask:7` / `VideoTaskEntity:5` |
| 库表列 + 迁移说明块 | `resources/schema.sql:7,9,11,23` |
| 包说明 | `domain/task/package-info.java:9` |
| **两处撒谎的 javadoc**（对外契约的注释里还写着这个字段） | `VideoTaskService:194`、`TaskController:136` |

> §2.4 说「`VideoTask` 里还剩 2 处 `translationStatus`（构造器形参 + `@param`）」—— **已失实**，
> 那里现在只剩一个悬空 import。悬空 import **不影响编译**，所以这不是「编不过」，是「没删干净」。

### 14.4 翻译过时判据（未做）

`deleteTranslatedArtifacts` **只在 `regenerate` 末尾跑**（`SubtitleCleanupCoordinator:241-244`）；
主管线那条普通 `clean`（`SubtitlePipeline:242` → `doClean(..., regenerate=false)`）**一个字都不删**。
所以译文目前**没有任何「过时」判据**——translate 只看 `translated-segments-<lang>.json`
在不在、非不非空（`readJsonOrNull`），不看它基于哪一版 `clean-segments`。

两条裂缝：

1. **可达且静默**：`regenerate` 先落库关键词（`VideoTaskService:242-247`）再异步跑；异步那趟失败时
   （摘要降级 → ISE，`doClean:220`）**关键词已改、`clean-segments` 还是旧的、译文也没删**。之后重新提交
   同一素材（`rejectIfActive` 只看「进行中」，旧任务已 SUCCEEDED）→ 主管线用**新关键词**调 `clean`
   → 指纹不匹配 → 重跑清洗换掉 `clean-segments` → **但 `regenerate=false`，不删译文** → 下次翻译
   把那批基于旧内容的译文当断点复用。
2. **不自证**：就算走 regenerate，删失败只记 WARN 不抛（`:271-275`），依赖「上一次删成功了」。

候选方向：**甲**（把删除挪出 regenerate，改成「写完 clean-segments 就删」）、
**乙**（译文记下它基于的 `clean-segments` 内容身份，读时比对，不匹配当没有重跑）、
**丙**（比 mtime）。推荐 **乙 + 甲**。

- **乙 一次能覆盖两件**：译文与术语表的上游是**同一次清洗**（术语表依赖 `context.terms`，
  而 context 与 `clean-segments` 在清洗时一起产出），所以「记下基于的那次 clean 的内容身份」
  这一条判据可以同时管译文（§14.4）和术语表（§14.6），不用两套。
- ⚠ 乙 **不能**拿现成的 `keywordsFingerprint` 当判据 —— regenerate 允许「关键词没变也重跑一遍」，
  同关键词两次清洗的内容可以不同（要**内容身份**，不是关键词身份）。

### 14.5 本会话新发现、未决

- `SubtitleCleanupCoordinator.readCheckpoint:286` 与 `SubtitlePreparationCoordinator.readCheckpoint:145`
  **与 `ArtifactFiles.readJsonOrNull` 逐字等价**（同一件事做两遍，只差日志文案）→ 可删两份换现成助手。
  项目所有者：**先放着**。
- `SubtitleWorkspaceFilePaths`（10-14 落地）**生产零调用**。`translate` 用不上它：写目标按名
  `resolve` 即可，存在性判据单源在 `ArtifactFiles` / `CheckpointPolicy`；它的真消费者是
  `deleteTranslatedArtifacts`（要枚举「盘上有哪些语种」）。

### 14.6 术语表（`terms-<lang>.json`）也没有过时判据

判据 = **「文件存在且非空」**：`verifyTermsIfNeeded` 传
`CheckpointPolicy.isCompleted(ArtifactFiles.sizeOrZero(termsFile))` →
`TermVerification.decide:41` 命中 `SKIP_ALREADY_VERIFIED`（该枚举值自己的注释就写着
「存在且非空 = 已查证，删文件即重查」）。**语种维度已隔离**（按语种分文件），除此之外零过时判断：

| 依赖维度 | 隔离了吗 |
|---|---|
| 目标语种 | ✓ 按 `terms-<lang>.json` 分文件 |
| **上游 `context.terms`**（摘要器产，关键词进摘要器） | ✗ 关键词变了 → 术语表换了 → 旧产物照旧被判「已查证」，新词永远不查 |
| **provider**（`vidsage.term-lookup.provider` 换实现） | ✗ 判据看不见 provider，换了也永不重查 |

作废机制与译文**共用同一把刀**（`deleteTranslatedArtifacts:264-266` 把 `TERMS` 一起删），
所以 §14.4 的两条裂缝原样适用。

**另有一条比「过时」更硬的（今天核出）——部分失败永不补查**：

- `Outcome.persistable()` = `official + common + none > 0`（`TermVerification`）——
  **只要有一条查成，就落盘**
- 判据又是「文件存在且非空即已查证」
- → **部分失败的那几个词从此永不补查**；只有**全部失败**才不落盘
- `FailedLookup` 的 javadoc 写着「（下次文件缺失时补查）」——**这句在部分失败时是假的**，
  文件并不缺失。`persistable()` 的注释（「落一份空产物会把整批词固化成『已查证』，
  下次永远不会再补查」）明明预见了这个危险，却只守住了全失败那一半。
  **注释与实现不自洽。**

---

## ▶ 收尾（2026-10-14 · 第五棒）

**本任务到此结束：批 3 / 批 4 收尾 / 批 5 / 失败收口 / spec 订正 / 测试债 全部落地，测试全绿（569 / 0 / 0 / 1 skipped），已合回 `dev` 并归档。**

| 项 | 结果 |
|---|---|
| **A** 批 3 · 登记表接管翻译会话 | ✅ `tryStart` + 409（附既有任务快照）+ 三条退登记路径全落；**运行期冒烟**：AC9 首帧是 `translate-subtitles` RUNNING 且 `terminal=false`、AC10 并发触发 409、跑完再触发 202（无幽灵） |
| **B** #2 · 翻译失败收口（AC8） | ✅ 失败落 `error_message`（「翻译失败：」前缀）+ 广播 `translate-subtitles` 的 FAILED 终态；失败后再触发仍 202 |
| **C** 批 4 残留（AC7） | ✅ 枚举本体 / 3 处悬空 import / package-info / `schema.sql` 列 全清；`src/main/java` 与 `src/test/java` 的 grep 为空 |
| **D** 批 5 · `GET /api/languages`（AC1） | ✅ 四项 `code` + 中文 `label`（简中 / 繁中 / 英语 / 日语），实测 200；源语种与目标语种**共用这一份清单**，前端回传 `code` |
| **F** 死代码 | ✅ 实际无货可清：`hasTranslationArtifact`、`resolveSourceLanguage` 主代码里早已不存在；`SubtitleWorkspaceFilePaths` 留着等 E 的消费者 |
| **G** spec 订正 | ✅ `error-handling.md`（409 扩成「后置会话」+ 记下已知缺口）、`directory-structure.md`（语种枚举落位 / 政策件两个纯函数 / terminal 判据）、`frontend/api.md`（失败只能读 `errorMessage`）、`comment-guidelines.md` |
| **测试债** | ✅ 8 个文件跟上子状态下线与签名漂移；新增 `SupportedLanguageTest`（5 条）与 `LanguageControllerTest`（2 条） |

**留给后续（都不在本任务范围）**

1. **E · 过时判据**（译文 §14.4 + 术语表 §14.6，含「部分失败永不补查」那条硬伤）—— 用户指示后面再做
2. **④ · 成功路径清失败原因**（决策 6）—— 判据**不能**用 `TranslatedSubtitle.skipped()`（它读的是检查点里存的 flag，不是「本次是否跳过」）；用户指示后面再做
3. **新缺口 · 提交 vs 后置会话** —— 翻译 / 重洗完在跑时同工作区还能提交新任务，主管线的 clean 会重写翻译正在读的 `clean-segments` / `context`。**从没进过任何文档**，现已记进 `error-handling.md` 的「已知缺口」；要修得先让登记表 value 带上 taskNo（或仓储加「按工作区取最近任务」），故单独开一条更合适
4. **AC 措辞三处待裁**：AC5（`zh` 实测是**合法**输入，不是白名单外）、AC7（pattern 有假阳性 `translationFail` ⊂ `translationFailure`，且与 AC12 的迁移说明冲突）、AC11（「基线只增不减」与批 4 的删面设计打架）
5. **prd / design / implement 与现状的偏差**：§1 那张表 + 语种枚举落位改 `model/translation/` + 跳过判据已换成枚举相等 + 测试基线数字（prd 写 498，实际 569）
6. **运行期验收的遗留物**：工作区 `...BV1ZNgZ6nEee-7d329d996d16` 里留下了 zh-hant 三件套产物（A 冒烟真翻出来的）
