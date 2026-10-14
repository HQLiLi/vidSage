# 翻译多语言选择与翻译子状态下线 — 技术设计

> 需求在 `prd.md`（本任务是父任务 `10-11-subtitle-keywords-multilang` 的子任务 B，需求共识的完整来源在
> 父 `prd.md` 的 C / D 两节）。执行顺序与逐批代码在 `implement.md`。
> **依赖**：子任务 A（`10-11-subtitle-keyword-regenerate`）已归档，它的 D 组地基本次**直接用**，不重复实现：
> `application/task/session/TaskSessionRegistry`、`TaskStageEvent.from(task, runningStage)`、
> `TaskProgressMessage.terminal` 的新派生规则。
> 来源：2026-10-11 会话 grill 共识 + 2026-10-13 本轮 grill 定的四条口径（§4 决策 1 / 5 / 6 / 7）。

## 0. 一句话

目标语种从配置搬到请求参数（白名单四项 + 常见写法别名 + 一份独立清单端点），随之把 DB 里的翻译子状态
整列删掉——一列 NULL 的 ENUM 表达不了「en 翻完了、ja 还在跑」；翻译会话在不在跑，改由 A 落地的
内存登记表回答。

## 1. 边界

| | 内容 |
|---|---|
| **做** | 父 prd C 节全部：目标语种参数化 / 白名单与别名 / 跳过判据升级 / DB 翻译子状态全删 / `error_message` 收口 |
| **不做** | 关键词与重跑清洗（子任务 A，已归档）、前端一切文件（父 E 节，另开任务）、父 F 节四条 |
| **上游（已完成）** | 子任务 A 的 D 组地基三件（登记表 / `from(task, runningStage)` / `terminal` 派生） |
| **下游（本次会打断）** | 前端 `isTranslating` 靠 `TaskResponse.translationStatus` 算，删字段即断——前端待办要点名 |
| **不重叠** | `10-11-sse-connection-pairing` 只改 `web/sse/TaskSseConnectionManager`，本任务零改动（父 prd D5） |

## 2. 契约

### 2.1 领域词汇（新增）`domain/subtitle/model/common/SupportedLanguage`

枚举，四个值，每个值三样：`code`（写进产物、提示词与接口响应的 BCP-47 短码）、`label`（给前端下拉显示的
中文名）、`aliases`（同一门语言的其它常见写法）。

| 值 | code | label | aliases |
|---|---|---|---|
| `ZH_HANS` | `zh-Hans` | 简体中文 | `zh`、`zh-CN`、`zh-SG` |
| `ZH_HANT` | `zh-Hant` | 繁体中文 | `zh-TW`、`zh-HK`、`zh-MO` |
| `EN` | `en` | 英语 | （无） |
| `JA` | `ja` | 日语 | （无） |

对外方法：`matches(String)`（这个写法是不是本值，大小写与首尾空白不敏感）、`getCode()` / `getLabel()`
（Lombok `@Getter`）、`fileNameToken()`（见决策 3）。

**不带 `@Schema`**：它不被 web 层直接引用（对外形状由 `web/dto/LanguageResponse` 持有），中文名走 `getLabel()`。

落位理由：它被 `Bcp47LanguagePolicy`（domain 政策）与 `SubtitleArtifactSpec`（同在 `model/common/`）引用，
含义由字幕翻译定义，跟 `SubtitleRoute` / `SubtitleStageName` 同一格。

### 2.2 领域政策（改）`domain/subtitle/policy/Bcp47LanguagePolicy`

**加两个方法，删两个方法**（旧的与新的不能共存，见 §6）：

```java
/** 解析请求参数里的目标语种：认四个 code 与它们的常见别名，大小写与首尾空白不敏感。 */
public static SupportedLanguage parseTargetLanguage(String requested);   // 空白 / 不在表里 → IAE（→400）

/** 这两份语种标签是不是同一门语言：非中文只比主语言子标签；中文再比一次正简脚本，两边都相同才跳过。 */
public static boolean isSameLanguage(String source, SupportedLanguage target);
```

**删**：`resolveTargetLanguage(String): String`（它的两件事各归其主——白名单校验搬进
`parseTargetLanguage`，产物名的串由枚举给）、`isSameMainLanguage(String, String)`（语义变了，名字会撒谎）。

`resolveSourceLanguage` / `skipAll` 不动。

**错误语义的搬家**（prd「待开工时展开」第一条）：旧方法吃的是配置值，空白抛 `IllegalStateException`（500，
「环境不对」）；新方法吃的是调用方传参，空白与不在白名单抛 `IllegalArgumentException`（400，「错在调用方」）。
同一个位置换个入参来源，异常类型跟着来源走，走既有 `ApiExceptionHandler` 映射，不新建异常类型。

**正简脚本怎么算**（决策 1 的落点）：主语言子标签是 `zh` 时，**认得出繁体写法就是繁体，其余（含裸 `zh` /
`zh-CN` / 认不出的变体）算简体**。认不认识由 `SupportedLanguage.ZH_HANT.matches(tag)` 回答——即
繁体/简体的成员判定只查 `ZH_HANT` 的 code 与别名这一张表，请求解析与跳过判定都查它，不各写一份
（prd D3 的「单源」）。非中文标签脚本视为空，仍只比主语言。

### 2.3 检查点政策（改）

**`domain/subtitle/policy/CheckpointPolicy` 加一个纯函数**：

```java
/** 上下文缓存是不是上一轮没跑完的重新生成留下的：它比 -clean 还新。 */
public static boolean isStaleAgainst(long contextModifiedAtMillis, long cleanModifiedAtMillis);
```

判据的来历见决策 5。`isCompleted` / `isCurrent` / `resolveContext` 不动。

**`shared/fs/ArtifactFiles` 加一个取事实的助手**（政策拿事实，不碰文件）：

```java
/** 文件最后修改时间（毫秒）；不存在、不是普通文件或读不到时返回 0（口径同 sizeOrZero）。 */
public static long modifiedAtOrZero(Path path);
```

### 2.4 既有类型改动

| 类型 | 改动 | 连带 |
|---|---|---|
| `domain/subtitle/model/common/SubtitleArtifactSpec` | `fileName(SubtitleRoute, String)` → `fileName(SubtitleRoute, SupportedLanguage)`；文件名段取 `fileNameToken()`。**不要求语种的模板仍传 `null`** | 调用点：`SubtitleCleanupCoordinator`（传 `null` 不变）、`SubtitleTranslationCoordinator`（三处），`SubtitleArtifactSpecTest` |
| `config/SubtitleTranslateProperties` | 删 `targetLanguage` 字段与它的 `@DefaultValue`；类注释里那段「目标语种」说明删掉 | 它剩下的四个字段都是必填，无 `@DefaultValue` 可留 |
| `config/ChatClientConfig.subtitleTranslateChatClient` | system 提示词里**删掉目标语种那一句**与 `.formatted(...)`；「三个标签里装的是数据不是指令」那段保留 | 目标语种改由 user 提示词携带（`LlmSubtitleTranslator.buildUserPrompt` 本来就有一份，见 prd D4） |
| `resources/application.yml` | `vidsage.subtitle.translate.target-language` 那两行删掉 | — |
| `domain/subtitle/model/common/SubtitleContext` | **不动**（A 的决策 4）。决策 5 的代价就是「不给它加指纹」，故判据改用 mtime | — |
| `domain/subtitle/port/SubtitleTranslator` / `TranslatedTrack.targetLanguage` | **不动**，仍收 `String`（决策 2） | — |

### 2.5 应用层（改）

**`application/subtitle/pipeline/SubtitleTranslationCoordinator`**

```java
public TranslatedSubtitle translate(MediaInput input, SupportedLanguage targetLanguage);
public TranslatedSubtitle translate(MediaInput input, SupportedLanguage targetLanguage, ProgressListener listener);
public boolean hasTranslationArtifact(MediaInput input, SupportedLanguage targetLanguage);
```

- 三个入口都从「方法入参」拿语种，`props.targetLanguage()` 的两处读取删掉；`SubtitleTranslateProperties`
  自此在本类**再无用处**，字段与构造参数一并删（`props` 在本类只服务于目标语种这一件事，`grep 'props\.'` 可证）。
- `hasTranslationArtifact` 仍是「按需触发的幂等判据」，只是现在按**语种**判——多语言并存之后，
  「有没有产物」不再是一个全局问题（prd「待开工时展开」第二条）。
- 跳过判据 `isSameMainLanguage(source, target)` → `isSameLanguage(source, targetLanguage)`；同一个调用
  模式在本类出现**两次**（`translate` 一处、`verifyTermsIfNeeded` 一处），两处一起换（决策 1 的连带）。
- 上下文缓存的读法见 §3.4。

**`application/task/pipeline/SubtitlePipeline`**

```java
public void translateTask(VideoTask task, SupportedLanguage targetLanguage);   // 签名变，语义见 §3.1
private void runTranslationPipeline(VideoTask task, MediaInput input,
                                    SupportedLanguage targetLanguage, long startTimeMs);
private void failTranslation(...);   // 删——失败收口整体搬进 TaskProgressNotifier（与 failRegenerate 对称）
```

**`application/task/VideoTaskService`**

```java
public TaskSnapshot translate(String taskNo, String targetLanguage);   // 白名单校验 + 404/400/409 + 抢登记
```

同步段的判定责任从管线搬到本类：**409 的响应体要附既有任务快照，而快照组装只有本类有**
（与 A 的 `regenerate` 同一条理由）。`toSnapshot` 少一个分量（§2.7）。

**`application/task/notify/TaskProgressNotifier`**

| 方法 | 改动 |
|---|---|
| `createSnapshot` | 不再传翻译子状态；快照的「正在翻译」由 `runningStage`（登记表）渲染 |
| `broadcast` | 同上 |
| `completeTranslation` | 不再走 `TaskStageEvent.fromTranslation`，改为显式发翻译阶段的 `SUCCEEDED` 事件 |
| `failTranslation`（**新增**） | 与 `failRegenerate` 对称：落失败原因 + 广播带原因的 `FAILED` 阶段事件 + 清订阅者 |
| `completeTask` / `broadcastTranslationStage` / `broadcastStage` / `failRegenerate` | 不动 |

### 2.6 应用层（新增）语言清单的对外供给

spec 禁 `web → domain`（main 源码），而白名单在 domain（`Bcp47LanguagePolicy` 要用它）。所以清单经一层
应用层读模型过去（决策 7）：

- `application/subtitle/query/LanguageOptionSnapshot(String code, String label)` —— 读模型，零 web 注解。
- `application/subtitle/SubtitleLanguageService.listSupportedLanguages()` —— `@Service`（有 web 调用面），
  把枚举值域投影成读模型清单。

### 2.7 删面清单 —— C5 一整批（编译期原子，见 §6）

| 文件 | 删什么 |
|---|---|
| `domain/task/model/TranslationStatus` | **整个文件** |
| `domain/task/VideoTask` | 字段 `translationStatus`、`newPending` / `restore` / 私有构造的该入参、`translationStart` / `translationSucceed` / `translationFail`、`isEventTerminal`；**新增** `recordTranslationFailure(String)` 与 `clearTranslationFailure()`（§4 决策 6 与 §3.1） |
| `domain/task/policy/TaskStageEvent` | `fromTranslation(VideoTask)`；`from(task, runningStage)` 里那条「SUCCEEDED 且子状态存在 → 转派」的分支；类注释里的「翻译子状态覆盖」段 |
| `application/task/message/TaskProgressMessage` | 分量 `translationStatus`；`terminal` 的派生只剩「主状态终态 && stage 非 RUNNING」 |
| `application/task/query/TaskSnapshot` | 分量 `translationStatus` |
| `web/dto/TaskResponse` | 字段与 `@Schema`、`from` 里的映射 |
| `infrastructure/persistence/entity/VideoTaskEntity` | 字段 `translationStatus` |
| `infrastructure/persistence/repository/MybatisVideoTaskRepository` | `toDomain` / `toEntity` 里的该字段 |
| `application/task/VideoTaskService.toSnapshot` | 构造调用里的该分量 |
| `resources/schema.sql` | `translation_status` 列 + 存量库迁移说明按删列重写（§5.1） |

`VideoTask` 的翻译失败前缀常量 `TRANSLATION_FAILURE_PREFIX` **保留**（仍在用），只是落它的方法从
`translationFail` 换成 `recordTranslationFailure`。

### 2.8 HTTP 契约

| 端点 | 请求 | 响应 |
|---|---|---|
| `POST /api/tasks/{taskNo}/translate` | `TranslateRequest(String targetLanguage)` | 202 + `TaskResponse` |
| `GET /api/languages` | — | `LanguageResponse` 清单（`code` + 中文 `label`） |

新增三个 web/config 侧文件：
- `web/dto/TranslateRequest` —— 单字段 HTTP 专属形状，不直接用领域枚举（domain 词汇不进 web 契约）。
- `web/dto/LanguageResponse` + `from(LanguageOptionSnapshot)`，`@Schema` 集中于此（spec 判据 2）。
- `web/controller/LanguageController`（`@RequestMapping("/api/languages")`，`web/controller/` 平铺）。

**错误映射**（全部走既有 `ApiExceptionHandler`，不新建异常）：taskNo 空白 / 目标语种空白或不在白名单 /
主状态非 SUCCEEDED → 400；任务不存在 → 404；同工作区已有后置会话在跑 → 409（响应体附既有任务快照）。

`@Operation` 要写明的两件事：目标语种取自 `GET /api/languages` 的 `code`（也接受 `zh` / `zh-CN` / `zh-TW`
这类常见写法）；同一语种重复触发走幂等（产物在就不发 LLM），并发触发走 409。

## 3. 数据流

### 3.1 触发一次翻译（新）

```
POST /api/tasks/{taskNo}/translate  {targetLanguage}
  → LanguageController/TaskController 卫语句（taskNo 空白）
  → VideoTaskService.translate(taskNo, targetLanguage)
      ① Bcp47LanguagePolicy.parseTargetLanguage(...)        ← 白名单校验最早，先于一切 I/O（400）
      ② loadTask                                    ← 未知 taskNo（404）
      ③ 主状态非 SUCCEEDED → IAE「字幕尚未就绪」（400）
      ④ taskSessionRegistry.tryStart(workspaceKey, TRANSLATE_SUBTITLES)   ← 抢不到（409，附快照）
      ⑤ pipeline.translateTask(task, targetLanguage)  handedOff 判据 + finally 退登记
      ⑥ 重读任务行回视图（202）
  → SubtitlePipeline.translateTask
      幂等收敛：hasTranslationArtifact(input, target) 为真 → 不动 error_message、退登记、返回
      否则 concurrencyGate.submit(runTranslationPipeline, 排队被打断的收口)
  → runTranslationPipeline（虚拟线程）
      translationStage.translate(input, target, listener)   ← 事件自成 0..100 段广播
      clearTranslationFailure() → completeTranslation(task)
      catch → taskProgressNotifier.failTranslation(task, reason, cause)
      finally → taskSessionRegistry.finish(workspaceKey)
```

三处与改动前不同，都在决策里写了理由：**守卫从 DB 子状态换成工作区登记表**（决策 4）、
**幂等与不重入的语义分开**（同语种重复触发是幂等，并发触发是 409）、**失败原因只由成功路径清**
（决策 6）。

### 3.2 重连快照与 `terminal`

```
GET /api/tasks/{taskNo}/events
  → TaskProgressNotifier.createSnapshot(task)
      runningStage = taskSessionRegistry.findRunningStage(workspaceKey)
      stage = TaskStageEvent.from(task, runningStage)      ← 有在跑的就渲染成那条 RUNNING
      terminal = 主状态终态 && (stage == null || stage.state() != RUNNING)
```

翻译会话在跑 → 登记表里有一条 → 快照渲染成 `translate-subtitles` 的 `RUNNING` → `terminal=false`，
连接不被终态立刻关掉（prd AC9）。跑完 → 异步体的 `finally` 先退登记，终态事件带 `SUCCEEDED` 阶段发出去
→ 订阅者收到后关流。`fromTranslation` 删掉之后，DB 里再没有任何东西能表达「翻译状态」，
这件事**只剩登记表一个来源**——这正是本次要的效果。

### 3.3 跳过判据（`zh → zh-Hant` 真翻）

| 源 | 目标 | 归一化后 | 结果 |
|---|---|---|---|
| `zh` / `zh-CN` / `zh-SG` / `zh-Hans` | `zh-Hans`（或其别名写法） | (zh, 简) = (zh, 简) | **跳过**（保住原来那条 `zh-CN` 陷阱修正） |
| `zh` | `zh-Hant` | (zh, 简) ≠ (zh, 繁) | **真翻** |
| `en` / `ja` 与其它 | 同主语言 | 只比主语言 | 照旧跳过 |
| 任一方取不到语种 | — | 主语言为空 | **不跳过**（宁可多跑一趟） |

**连带（决策 1 的一条，别漏）**：`isSameLanguage` 在 `SubtitleTranslationCoordinator` 有**两个**调用点，
第二个在 `verifyTermsIfNeeded` 里喂给 `TermVerification.decide`。判据升级后 `zh → zh-Hant` 在那一处也
从「同一门语言」变成「不同」→ **繁中翻译会首次触发术语译名查证**（落 `terms-<route>-zh-hant.json`）。
语义上是对的（繁中要的本来就是繁中译名，查证正是为译文里的专名一致服务的），本次**认下**。

### 3.4 翻译侧读上下文缓存

```
ctxFile = context-<route>.json
cached = CheckpointPolicy.isStaleAgainst(modifiedAt(ctxFile), modifiedAt(cleanSeg))
             ? null                                     ← 判为上一轮中断的重生成留下的
             : ArtifactFiles.readJsonOrNull(ctxFile, ...)
resolveContext(cached, () -> summarize(cleanSeg 正文, 空关键词))
```

判据的来历、为什么不用指纹、以及「翻译侧不再落盘」这条配套见决策 5。

## 4. 关键决策与被否方案

### 决策 1（2026-10-13）请求参数吃别名，裸 `zh` 也算简体 ⇒ prd AC5 的 `zh` 用例改判

**定了什么**：`parseTargetLanguage` 认四个 `code` **与全部别名**（`zh` / `zh-CN` / `zh-SG` / `zh-TW` /
`zh-HK` / `zh-MO`，大小写与首尾空白不敏感），命中的都归一成四个枚举值之一。裸 `zh` 归一成 `zh-Hans`。

**为什么**：prd D3 已经把「解析与跳过判定都调别名表」写成一条要求，那就得只有**一张**表。若请求参数
只认四个 canonical code，别名表就要拆成「解析能接受的」与「源语种能识别的」两张，同一件事两个口径。

**必须知情的后果**：**prd AC5 把裸 `zh` 列为「一律 400」的例子，与这条不相容**——`zh` 正是简体的别名。
本次按「吃」处理，AC5 那条例子要换成真正在门外的反例（`de` / `fr` / 空串 / `../../etc`，这些仍然 400）。
`GET /api/languages` 返回的清单仍是四项（不上裸 `zh`——它等同 `zh-Hans`，下拉列两个简中选项只会让用户困惑），
所以**「白名单四项」说的是清单与响应，不是入参集合**，两个口径都不变。

**被否的**：只认四个 canonical code。理由见上（表要拆两张），且用户传 `zh-CN` 被 400 而 UI 上明明写着
「简体中文」也说不通。

### 决策 2（2026-10-13）枚举只在边界用，翻译链路内部仍传 `code` 串

**定了什么**：`SupportedLanguage` 出现在**请求参数、白名单校验、产物命名、清单响应**四处。
`SubtitleTranslator` 端口、`TranslatedTrack.targetLanguage`、`TermLookupCommand.targetLanguage`
这些下游仍是 `String`，由 coordinator 把 `getCode()` 传下去。

**为什么**：prd D3 只点了 `SubtitleArtifactSpec` 的入参收敛。端口那一层收的是「一个要写进提示词的短码」，
换成枚举会让 domain 端口认识一个「白名单」概念——而翻译器本身不关心白名单（它只关心译成什么）。
改面也最小。

### 决策 3（2026-10-13）`code` 与文件名段是两种写法

**定了什么**：`getCode()` 给接口响应与提示词（`zh-Hans`、`zh-Hant`，标准 BCP-47 大小写）；
`fileNameToken()` 给产物文件名（小写 `zh-hans`、`zh-hant`）。

**为什么**：`SubtitleArtifactSpec.compileTemplate` 给 `{lang}` 段的正则是 `[a-z0-9-]+`，大写字母**匹配不上**
——产物名一进 `matches()` 就恒 false，后果是 `artifacts` 清单里看不到译文、重生成时删不掉它（那是静默故障）。
要么把正则放宽，要么文件名保持小写。选后者：仓库里落盘的语种段本来就是小写（旧配置 `zh` → `-translate-zh.*`），
放宽正则等于让同一批产物出现两种大小写。

**被否**：`code` 直接写小写（`zh-hans`）当唯一形态——接口响应里那个 `code` 是给前端显示与回传的，
`zh-Hans` 才是标准写法（prd AC1 也是这么写的）。

### 决策 4（2026-10-11 父 prd D1/D2，本次落地）翻译会话的守卫与快照全靠登记表

**定了什么**：翻译的「进行中」不再写任何 DB 列：不重入由 `TaskSessionRegistry` 承担（抢不到 → 409），
重连快照由 `from(task, runningStage)` 渲染成 `RUNNING`。

**为什么**：一列 NULL 的 ENUM 表达不了「en 翻完了、ja 还在跑」；而且它本来就兼任两件事（不重入 + 快照），
这两件事现在都有别的来源了（父 prd D 节整段是「为什么不上 DB 也不上 Redis」的论证，本次只是消费它）。

**必须知情的后果（顺序是硬的）**：**登记表接管必须先于删 DB 列**。先删的话中间态里两个能力同时消失：
并发翻译会双跑，页面刷新看不到「正在翻译」。见 §6 的批序。

### 决策 5（2026-10-13）翻译侧上下文缓存按 mtime 判，且自己不再落盘

**第一半：怎么判。** 翻译侧读 `context-<route>.json` 原来只看「文件存在」，而这份缓存的内容是**关键词的函数**
（A 把关键词接进了摘要器）。重生成若在「摘要落盘 → 清洗失败」处断掉，会留下一份比 `-clean` 新的上下文；
那份摘要是按新关键词写的，拿去配手里这份旧字幕就是静默错配（prd「待开工时展开」里由 A 的 check 移交的那条）。

判据用 mtime：**上下文比 `-clean` 新 ⇒ 判为不可用、走重摘要**。成立的依据是清洗的落盘顺序——
先落摘要、后落 `-clean`，所以正常状态下 `-clean` 一定不比摘要旧；反过来只可能是中途断掉。

**被否的（语义最正、本次代价太大）**：给 `SubtitleContext` 加一栏关键词指纹，翻译侧算一遍当前指纹再比。
它推翻 A 已归档的「`SubtitleContext` 不动」，且翻译侧要注入关键词仓储才能算出当前指纹（新增一个跨层依赖
和一个写入点）。风险窗口只在「重生成断掉」这一种情形，mtime 够用；将来真要做，指纹方案随时可以把这条换掉。

**第二半：翻译侧不再落盘摘要**（prd 没点到，是本次一并发现的，【需你点头】）。
现在翻译侧在缓存未命中时会**写** `context-<route>.json`，而它写的那份用的是**空关键词**
（`new SubtitleKeywords(List.of())`——翻译侧没有关键词）。这有两个后果：
① 覆盖掉清洗侧那份带关键词的摘要；② 下一次「关键词没变的重生成」会命中这份被覆盖的缓存（`isCurrent` 只看
`-clean` 上的指纹，认不出摘要被人换过）→ **用户填的关键词一个字都没进摘要器，且不报错**。
去掉这一处写入，两个洞一起堵上：翻译侧只消费清洗侧落的缓存，不生产。

**必须知情的代价**：在「重生成断掉」那个窗口里，每次触发翻译都会重摘要一次（缓存判为不可用）。
窗口本身要靠用户重跑一次重生成（或清洗）来收拾，所以这个代价有界；正常状态下缓存必在
（`-clean` 存在蕴含摘要已落盘），一次都不会多跑。

### 决策 6（2026-10-13）翻译成功清掉上一次「翻译失败：」

**定了什么**：`VideoTask.clearTranslationFailure()`，只认自己那个前缀，对称于 A 的 `clearRegenerateFailure`。
在**真跑完一轮**的成功路径上清（`runTranslationPipeline` 里，收口之前）；**幂等收敛路径不清**。

**为什么**：AC8 要求翻译失败时主状态保持 SUCCEEDED，于是「已成功的任务上挂着一条错误文案」会直接显示给
用户；不清就永远是上一次的。

**为什么收敛路径不清**：那条路径上什么都没跑。而且多语言之后一个任务可能有多次翻译，`error_message`
只有一列——`en` 翻好了但 `ja` 刚失败，这时用户再点 `en`（走收敛）不该把 `ja` 的失败原因抹掉。

### 决策 7（2026-10-13）语言清单经应用层读模型暴露

**定了什么**：`GET /api/languages` 的形状由 `web/dto/LanguageResponse` 持有，数据由
`application/subtitle/SubtitleLanguageService` 从枚举投影成 `LanguageOptionSnapshot` 给出。

**为什么**：spec 明文禁 `web → domain`（main 源码已清零），而白名单必须住 domain（`Bcp47LanguagePolicy` 要用）。
`Response` 与 `Snapshot` 这层映射不是形式主义——它是这条依赖规则唯一的出口。

**被否的**：① 让 `LanguageController` 直接读枚举（违反依赖规则）；② 在前端写死清单
（prd D5 已否：两端各存一份迟早对不上）；③ 把清单塞进任务视图（prd D5 已否：与任务无关的静态信息）。

## 5. 兼容与迁移

### 5.1 DB

`schema.sql`：删 `translation_status` 列，并把文件头那段存量迁移说明按**删列**重写（口径同 2026-10-02 加列那次）：

```sql
-- ⚠️ 存量迁移说明（2026-10-13 删列）：
--   CREATE TABLE IF NOT EXISTS 不会为已存在的旧表删列，MySQL 8 也没有 DROP COLUMN IF EXISTS。
--   本版删掉 translation_status 列——已有旧库必须二选一：
--   1. DROP TABLE task;                                   （推荐：本地 / 测试数据，重启后由本脚本重建）
--   2. ALTER TABLE task DROP COLUMN translation_status;   （要保留任务行时走这条）
--   旧表不迁移的后果：实体里已经没有这个字段，MyBatis-Plus 不再读写它——
--   多出来的一列没人读、也没人写，只是白占一行宽度，不影响启动与运行。
```

**不引迁移框架**（全库只有 `task` 与 `subtitle_keyword` 两张表，`spring.sql.init.mode: always`）。

### 5.2 存量产物

**不动。** 旧工作区里 `segments-*-translate-zh.json` 这类产物按旧语种名落盘，新代码只会去找
`-translate-zh-hans.json` 等新名字，于是旧译文**看不见了**（文件还在，只是不再进清单）。
这是收敛的代价、也是安全的（不会拿旧译文冒充新语种），不做一次性改名——脚本改名要猜「旧 `zh` 是哪一种简体」，
而 `-translate-zh.json` 的来源是配置，配置写的是什么只有用户知道。

`context-<route>.json` 无变化（不带语种）。`-clean` 里的 `keywordsFingerprint` 是 A 的事，本次不动。

### 5.3 前端

本次不改前端，但**两处会让前端立刻看到不同**，要进「前端待办」：

1. `POST /{taskNo}/translate` 现在**要 body**（`{"targetLanguage":"en"}`），不收 body 会 400。
2. `TaskResponse.translationStatus` 与 SSE `TaskProgressMessage.translationStatus` **字段没了**
   ——前端 `isTranslating` 就是靠它算的，必须改判据（订阅事件里的 `stage.stage == "translate-subtitles"`
   且 `stage.state == "RUNNING"`，或直接看 `terminal`）。新增 `GET /api/languages` 供下拉取清单。

### 5.4 prd 待订正（本次两处，按仓库「订正惯例」改）

| 位置 | 现状 | 应改为 |
|---|---|---|
| AC5 的例子 | 「白名单外的语种（`de`、**`zh`**、空串、`../../etc`）一律 400」 | 删掉 `zh`，补 `fr`；`zh` 现在归一到 `zh-Hans`（决策 1） |
| AC11 的基线 | 「当前 **498**」 | **570**（A 落地后已刷新；A 的 AC13 里那个 498 同理是旧值） |

## 6. 回滚形状

按依赖从外到内，每一批都能独立停在原地：

| 批（见 implement.md） | 回滚动作 | 残留 |
|---|---|---|
| 批 1 域地基 | 删两个新方法 + 文件 | 无 |
| 批 2 语种端到端 | 还原签名与 `props` 读取、还原 yml 两行、还原 system 提示词 | 无（白名单里 `zh-Hans` 那条在旧逻辑下会被 `isSameMainLanguage` 判「同语言」而跳过，属旧行为） |
| 批 3 登记表接管 | 还原 `translateTask` 的守卫与 `finally` | 无（登记表本来就不落库） |
| 批 4 删 DB 子状态 | 加回列与枚举 | **列已删、值已丢**（`translation_status` 是纯派生态，丢了只影响「翻译到哪一步」的展示，不影响产物） |
| 批 5 清单端点 | 删路由与两个 DTO | 无 |

**批 3 与批 4 之间不要对外起实例**：中间态里 DB 子状态已经没人写了、却还有人读（`fromTranslation` 还在），
一个升级前留下 `translation_status = RUNNING` 的旧行会让快照永远说「翻译中」、连接不关。两批在同一会话里
连着做，做完再起实例。

**唯一危险的一步是批 4 的 DB 删列**（不可逆），所以它排在登记表接管之后、清单端点之前。

## 7. 待评审的开放点

1. **决策 5 第二半**（翻译侧不再落盘摘要）——prd 没点到，本次一并发现；照做要多改三行，不照做则留着
   「翻译把带关键词的摘要覆盖成无关键词的那份」这条静默错配。**等你点头**。
2. 409 的文案要不要点名「在跑的是哪一次会话」（登记表存的是 `clean-subtitles` / `translate-subtitles`
   这类 token，直接拼进中文句子有点生硬；现方案是不点名，两边共用一句「该工作区已有一次后置会话在跑」）。
3. `GET /api/languages` 的响应形状：现方案是**裸数组** `[{"code":…,"label":…}, …]`。备选是包一层
   `{"languages":[…]}`（将来加字段不用改形状）。裸数组更贴端点名，倾向裸数组。
4. §5.4 的两处 prd 订正要不要这次一起改（改的是 `prd.md`，按惯例删除线 + 日期）。
