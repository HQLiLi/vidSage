# 技术设计：application 出站依赖倒置

> 本文件记录**边界、契约、落位、取舍**。执行顺序与验证命令见 `implement.md`。

## 1. 目标形态

改造前（反向依赖，靠 spec 豁免清单 a / b 合法化）：

```
web ──→ application ──→ infrastructure（具体类，反向）
web ──→ domain（枚举 + VideoTask.isEventSettled）
```

改造后：

```
web ──→ application
          │  │
          │  └──→ domain ←── infrastructure
          │         │              │
          │         │ 定义领域端口  │ implements
          │         └──────────────┘
          └──────────────────────────┐
                                     │
        application 定义的端口 ───────┘ ←── infrastructure implements（窄口）
```

- **端口按语义职责分两层定义**：契约是领域词汇且业务规则需要它 → **domain 定义**；技术/运维词汇、或仅编排/集成需要 → **application 定义**。
- 两类端口**都由 infrastructure 实现**，infrastructure 是唯一实现端口的地方。
- application 只调用端口，不实现出站端口。
- `domain` 聚合根不调端口；`domain service` 可调 domain 端口（本轮未落任何 domain service）。

### 1.1 依赖规则（本轮改写后的正典口径）

| 层 | 依赖 |
|---|---|
| `domain` | 不依赖任何模块（实测跨层 import 0 处；`domain → shared` 禁止） |
| `application` | 依赖 `domain`、`shared` |
| `infrastructure` | 依赖 `domain`、`application`（仅窄口白名单）、`shared` |
| `web` | **main 源码**只依赖 `application`；**test 源码豁免**（见 §6.3） |
| `config` | 谁的都不依赖（纯 `@Configuration` / `@ConfigurationProperties`） |

**`infrastructure → application` 是窄口 + 白名单**，不是「次级依赖」：

```
允许：
  import application/<上下文>/<端口接口>     （仅用于 implements 出站端口）
禁止：
  import application/<上下文>/*Service       （不得反向调用 application 用例服务）
  import web/ 与 web/dto/ 的任何类型
```

禁止反向调用用例服务是这条规则里最要紧的一句——一旦放任，会出现循环依赖、事务边界混乱，infrastructure 变成四不像。

### 1.2 端口归属判据

看契约的**语义职责**，不只看词汇：

```
领域词汇 + 业务规则需要     → domain 端口（落 domain/<上下文>/）
技术/运维词汇               → application 端口（落 application/<上下文>/）
领域词汇 + 仅编排/集成需要   → application 端口
```

兜底问题：**删掉这个端口，业务规则还成立吗？** 成立 → application 端口；不成立 → domain 端口。

> **已知歧义（待复核）**：按兜底问题的字面读法，`SubtitleCleanser` / `SubtitleTranslator` 这类「领域词汇 + 实现里带着业务判据」的端口会两边都沾边——删掉它们，任务状态机与检查点语义照样成立。本轮采信的读法是「业务规则」指**该能力自身的业务语义**（清洗的「键集必须一致、改写幅度不超阈值」就在实现里），据此判为 domain。项目所有者 2026-10-06 裁定「先切着，后面再看」，故本轮按 domain 落，并把本条标为待复核。

## 2. 端口清单

### 2.1 domain 定义的端口（4 个）

**`domain/task/VideoTaskRepository`**

```java
public interface VideoTaskRepository {
    VideoTask insert(VideoTask task);
    Optional<VideoTask> findByTaskNo(TaskNo taskNo);
    Optional<VideoTask> findActiveByWorkspaceKey(WorkspaceKey workspaceKey);
    void update(VideoTask task);
}
```

四个方法**原样搬自现具体类**，含 `insert` 返回 `VideoTask`（回填数据库自增 id）——id 是 `VideoTask` 聚合根的身份，属领域事实，不是持久化细节，故留在端口签名里。Javadoc 从现具体类逐方法迁入（含「什么算 active 是业务谓词，单源在 `VideoTask.ACTIVE_STATUSES`」那段）。

**`domain/subtitle/SubtitleCleanser`**

```java
public interface SubtitleCleanser {
    CleanseResult clean(List<TranscriptionSegment> segments, SubtitleContext context,
                        String reference, ProgressListener listener);
}
```

只声明上层真正调用的 4 参版本（`SubtitleCleanupService:191`）。**不带** 3 参便捷重载——那是给实现类自己的测试用的。

**`domain/subtitle/SubtitleTranslator`**

```java
public interface SubtitleTranslator {
    TranslateResult translate(List<TranscriptionSegment> segments, SubtitleContext context,
                              String targetLanguage, List<VerifiedTerm> officialTerms,
                              ProgressListener listener);
}
```

只声明 5 参版本（`SubtitleTranslationService:249`）。**不带** 3 参 / 4 参重载。

**`domain/subtitle/SubtitleContextSummarizer`**

```java
public interface SubtitleContextSummarizer {
    Optional<SubtitleContext> summarize(String content);
}
```

### 2.2 application 定义的端口（1 个）

**`application/diagnostics/ToolDiagnostics`**

```java
public interface ToolDiagnostics {
    List<ToolDiagnosticReport> diagnoseAll();
}
```

**单方法端口**，判据是「业务无关」：契约里没有一丝领域词汇（工具名 yt-dlp/ffmpeg/ffprobe、版本号、路径、可用与否），这是**环境自检 / 运维排障**能力，不是视频转写业务的组成部分。

`requireFfmpeg()` / `requireFfprobe()` / `requireYtDlp()` **不进端口**——它们的调用点全在 infrastructure 内部（`FfmpegAudioExtractor:105`、`LocalFileMediaAcquirer:136`、`YtDlpRunner:68,103`），返回的 `Path` 是技术产物，不该成为跨层契约。

### 2.3 端口落位与跨上下文边

| 端口 | 定义层 | 签名触及的领域类型 |
|---|---|---|
| `VideoTaskRepository` | `domain/task/` | `VideoTask`、`TaskNo`（task）、`WorkspaceKey`（shared） |
| `SubtitleCleanser` | `domain/subtitle/` | `TranscriptionSegment`（transcription）、`SubtitleContext`（subtitle）、`ProgressListener`（shared） |
| `SubtitleTranslator` | `domain/subtitle/` | 同上 + `VerifiedTerm`、`TranslatedSegment`（subtitle） |
| `SubtitleContextSummarizer` | `domain/subtitle/` | `SubtitleContext`（subtitle） |
| `ToolDiagnostics` | `application/diagnostics/` | `ToolDiagnosticReport`（application/diagnostics） |

`domain/subtitle → domain/transcription` 这条跨上下文边**已经存在**（`Bcp47Language`、`SubtitleTrack` 在用），端口不引入新的边。

## 3. 出参契约

### 3.1 嵌套 record 提升为 domain 顶层 record

```java
// domain/subtitle/CleanseResult.java
public record CleanseResult(List<TranscriptionSegment> segments, int correctedCount,
                            int retrySteps, int termHits, int termApplied) {}

// domain/subtitle/TranslateResult.java
public record TranslateResult(List<TranslatedSegment> segments, int retrySteps,
                              int untranslatedCount) {}
```

**为什么提升而不是嵌在端口接口里**：本项目对「端口出参」的既有做法就是顶层 record——`domain/transcription/TranscriptionResult`、`domain/subtitle/TermLookupResult`、`domain/media/AcquiredMedia` 三个先例。若让 `CleanseResult` 嵌在接口里，同一个 `domain/subtitle/` 包内会出现「`TermLookupResult` 独立成文件、`CleanseResult` 却嵌在接口里」两种形态，后人得靠猜。

### 3.2 诊断报告搬进 application 并改名

```java
// application/diagnostics/ToolDiagnosticReport.java
@Schema(description = "单个工具链的诊断报告")
public record ToolDiagnosticReport(String toolName, boolean available, String version,
                                   String path, String hint) {}
```

- `infrastructure/process/ExternalToolReport` **整体搬过来**，infrastructure 侧不再保留同名类型（不搞两份形状 + 映射）。
- 去掉「External」：站在领域视角，「外部」这个限定词本身就是站在应用内部往外看的说法，是技术视角的残留。
- **保留 `@Schema`**：它直接充当 HTTP 契约（`MediaController` 直接返回 `List<ToolDiagnosticReport>`），命中 `directory-structure.md`「契约类的位置·判据 1」的「形状可直接对外」分支——该分支允许这类类型留在其所属层并带 `@Schema`（先例：`domain/transcription/TranscriptionResult`、`application/task/TaskProgressMessage`）。
- `@Schema` 描述文案随类型名更新；**字段名、字段类型、字段级 `@Schema` 参数一律不动**——JSON 本体零变化。

## 4. 实现类：改名与落位

| 改造后 | 落位 | stereotype | 实现的端口 |
|---|---|---|---|
| `MybatisVideoTaskRepository` | `infrastructure/persistence/repository/` | `@Repository` | `domain/task/VideoTaskRepository` |
| `LlmSubtitleCleanser` | `infrastructure/provider/llm/` | `@Component` | `domain/subtitle/SubtitleCleanser` |
| `LlmSubtitleTranslator` | `infrastructure/provider/llm/` | `@Component` | `domain/subtitle/SubtitleTranslator` |
| `LlmSubtitleContextSummarizer` | `infrastructure/provider/llm/` | `@Component` | `domain/subtitle/SubtitleContextSummarizer` |
| `ProcessToolDiagnostics` | `infrastructure/process/` | `@Component` | `application/diagnostics/ToolDiagnostics` ← **窄口** |

- 实体 ↔ 领域对象转换、`Wrappers` 查询构造、LLM 调用逻辑、进程诊断逻辑**一行不动**。
- LLM 实现在端口之外**保留**原有 public 便捷重载与包私有测试钩子：`LlmSubtitleCleanser` 的 3 参 `clean`、`validate` / `baselineOf` / `ValidationFailure`；`LlmSubtitleTranslator` 的 3/4 参 `translate` 与 `validate`；`ProcessToolDiagnostics` 的 `require*` 与全部包私有 static 助手。

**未受影响**：`MessageHistory`（包私有，application 从不引用，只被同包两个 worker 用）、`ProcessOutcome`、`ExternalProcessRunner`、`ExternalToolProperties`、`ChatClientConfig`（三个 `ChatClient` bean 与 worker 类无编译期引用）。

### 4.1 同层消费方

`FfmpegAudioExtractor`、`LocalFileMediaAcquirer`、`YtDlpRunner` 需要 `require*`，故**按具体类 `ProcessToolDiagnostics` 注入**——同层依赖，不违分层。Spring 里接口与具体类解析到同一个单例 bean。

## 5. 上层消费方改动

### 5.1 application

| 文件 | 改动 |
|---|---|
| `TaskService` | import 改 `domain.task.VideoTaskRepository`；其余零改动（端口同名，字段声明与构造参数原样） |
| `TaskPipelineService` | 同上 |
| `SubtitleCleanupService` | 两处 import 改 domain；`SubtitleCleanser.CleanseResult` → `CleanseResult` |
| `SubtitleTranslationService` | 两处 import 改 domain；`SubtitleTranslator.TranslateResult` → `TranslateResult` |
| `TaskProgressMessage` | **新增派生方法**（见 §5.3） |
| `ToolDiagnosticsService` | **删除**（见 §5.2） |

**端口同名带来的红利**：`TaskServiceTest` / `TaskPipelineServiceTest` / `SubtitleCleanupServiceTest` / `SubtitleTranslationServiceTest` 里 `Mockito.mock(VideoTaskRepository.class)` / `Mockito.mock(SubtitleCleanser.class)` 这类**类型引用一字不改**，只有 import 行从 infrastructure 换成 domain。

### 5.2 `ToolDiagnosticsService` 删除

它原本的全部内容是：

```java
public List<ExternalToolReport> diagnoseAll() { return diagnostics.diagnoseAll(); }
```

存在理由（类 Javadoc 自述）是「隔离 infrastructure 出站实现」——端口搬到 `application/diagnostics/` 后这个理由消失，剩下的纯是方法转发。`MediaController` 改为**直接注入 `ToolDiagnostics` 端口**：端口就在 application 层，注入它是同层依赖，不是跳层。

`application/diagnostics/` 改造后自洽：`package-info.java` + `ToolDiagnostics`（端口）+ `ToolDiagnosticReport`（出参）。

### 5.3 web 层去 domain 化

main 侧只有三处、两类用法：

**① 领域规则被 web 直接调用**

```java
// TaskController:191 与 TaskSseHub:116，两处都是这句
if (VideoTask.isEventSettled(snapshot.taskStatus(), snapshot.translationStatus())) { ... }
```

解法：判定搬进 application 的消息类——`TaskProgressMessage` 新增派生方法，内部仍调 `VideoTask.isEventSettled`（**单源不变**，application 允许 import domain）。两个调用点改成 `snapshot.isEventSettled()` / `event.isEventSettled()`，web 只拿 `boolean`，**连 import 都不需要**。

> **实现时必须躲的坑**：`TaskProgressMessage` 是**直接序列化成 SSE JSON** 的 record。新方法若用 `isXxx` getter 形状命名，Jackson 会把它当成一个属性序列化出去，SSE payload 凭空多出 `eventSettled` 字段——那是契约变更。**故方法命名为非 getter 形状（`settled()`）**，并在改完后**实际比对 SSE payload**（`/api/tasks/{taskNo}/events` 的 data JSON），不靠推断。

**② `TaskResponse` 的三个枚举字段**

```java
// 改造前
@Schema(description = "输入类型")   TaskInputType inputType,
@Schema(description = "任务状态")   TaskStatus status,
@Schema(description = "翻译子状态") TranslationStatus translationStatus,
```

改为 `String`，映射处取 `.name()`（`translationStatus` 可空，需 `== null ? null : ...`），`@Schema` 描述文案改为携带取值：

```java
@Schema(description = "任务状态：PENDING（排队中）/ RUNNING（执行中）/ SUCCEEDED（成功）/ FAILED（失败）")
String status,
```

**JSON 响应体零变化**（Jackson 本来就把枚举序列化成名字字符串）。代价是 OpenAPI 里这三个字段从 `enum [...]` 降级为普通 `string`——**经核实本项目无消费方**：`frontend/package.json` 没有 `openapi-typescript` / `orval` / `swagger-typescript` 等生成工具，也没有生成脚本；`frontend/src/types/task.ts` 与 `frontend/src/enums/task.ts` 是**手写的契约镜像**（文件头自述「与 Java record / 枚举逐字段镜像」）。故此降级只影响 Swagger UI 的读者，而取值已在描述文案里写清。

**③ 其余**：main 侧无其他 domain 用法。

## 6. 测试改动

### 6.1 应用层测试（4 个）

只改 import（+ `CleanseResult` / `TranslateResult` 的限定名去掉外层）。

### 6.2 实现类测试改名（5 个，镜像对应）

`VideoTaskRepositoryTest` → `MybatisVideoTaskRepositoryTest`；`SubtitleCleanserTest` → `LlmSubtitleCleanserTest`；`SubtitleTranslatorTest` → `LlmSubtitleTranslatorTest`；`SubtitleContextSummarizerTest` → `LlmSubtitleContextSummarizerTest`；`ExternalToolDiagnosticsTest` → `ProcessToolDiagnosticsTest`。测试**算法与断言零改动**，只改类型名。

同层消费方测试（3 个）：`FfmpegAudioExtractorTest` / `LocalFileMediaAcquirerTest` / `YtDlpRunnerTest` 里 `mock(ExternalToolDiagnostics.class)` → `mock(ProcessToolDiagnostics.class)`。

### 6.3 web 层测试

- `MediaControllerTest`：`ExternalToolReport` → `ToolDiagnosticReport`；`@MockitoBean ToolDiagnosticsService` → `@MockitoBean ToolDiagnostics`。
- `TaskControllerTest` / `TaskSseHubTest`：**保留对 domain 类型的引用**——它们用 `TaskStatus` / `TranslationStatus` / `TaskInputType` / `StageEvent` 构造 `TaskProgressMessage` 与 `TaskSnapshot` 夹具，而这些 application 类型的**组件本身就是 domain 词汇**。
- **豁免明文写进 spec**：层间依赖规则约束**生产代码**；`web/` 的 **test 源码**为构造夹具允许 import `domain`。理由：禁掉它等于逼 application 为测试再造 String 化构造入口，是把测试的需要泄漏进生产 API。

## 7. spec 对账清单

### 7.1 `.trellis/spec/backend/directory-structure.md`

| 位置 | 现状（失实） | 改为 |
|---|---|---|
| `:12-33` 四层树示意 | 无 `web`/`application` 端口的表示 | 树注释补「端口可定义在 domain 或 application」 |
| `:47` 分层归属判据表 | `application/` 禁止列无端口相关表述 | `application/` 允许列补「application 定义的出站端口」；`web/controller/` 禁止列改为「直接依赖 `infrastructure/` 或 `domain/`（main 源码）」 |
| `:52` / `:251` | controller 禁止依赖 infra「唯一例外见豁免清单 b」 | 删例外指向；改为「main 源码不直接依赖 `infrastructure/` 与 `domain/`」 |
| `:64` 端口接口段 | 只列 `ASRProvider` / `MediaAcquirer` / `AudioExtractor` / `TermLookupProvider` | 补五个新端口；写明端口可定义在 domain 或 application，附**归属判据 + 兜底问题** |
| `:66` | 「（quality-guidelines「不预先抽接口」不受影响）」 | 删该括注 |
| `:80` `process/` 段 | 「单一实现的基础设施…因此**不抽接口**」 | 改为：调外部程序的**机制**不抽接口（`ExternalProcessRunner` 仍无实现缝），但**诊断能力**已抽 `ToolDiagnostics` 端口（定义在 `application/diagnostics/`）；报告类型已搬出本包 |
| `:91-102` 依赖规则与**豁免清单** | 豁免 a（5 处）+ 豁免 b（web→infra） | **整节重写**为 §1.1 的依赖表 + 窄口白名单 + §1.2 归属判据 + 端口实现规则；豁免清单清空 |
| `:108` 上下文清单 | 「application 另有 `diagnostics`」 | 保留（domain 侧**没有** diagnostics） |
| `:116` llm 现阶段形态 | 「没有抽接口」「本包就是终态形态」 | 整段重写：端口在 `domain/subtitle/`，实现在此包，命名 `Llm*` |
| `:118-132` 持久化三层 | 表列「对上契约 `XxxRepository` 具体类（`@Repository`）…application 层唯一可见的持久化入口」；`:130`「不预先抽接口」整条 | 表改为「端口在 `domain/task/`、实现在 `infrastructure/persistence/repository/`」；`:130` 改为新判据 |
| `:181-186` 命名约定表 | 适配器实现类行 | 补「接口与实现同名，实现加技术前缀」的正式表述 |
| `:213` 契约表 | `ExternalToolReport` → `infrastructure/process/` | `ToolDiagnosticReport` → `application/diagnostics/`，理由列同步改 |
| — | 无 web 层依赖条款 | 新增「`web/` main 源码只依赖 application；test 源码豁免」明文 |

### 7.2 `.trellis/spec/backend/quality-guidelines.md`

`:147-151`「### 不预先抽接口」**整节重写**为新判据：

> 端口不是「等出现第二个实现才抽」——**上层需要不依赖某个具体实现类时，就为它抽端口**。多实现是抽端口的常见理由，不是必要条件。

依据是项目自身的既有事实：`domain/media/AudioExtractor` 只有一个实现（`FfmpegAudioExtractor`），其 Javadoc 明写「接口保留是为了让上层不依赖实现类」——而原文的「现有四个端口各自有明确的多实现或替身需求」对它是**失实**的。新判据是把一直隐含的规则写成明文。

### 7.3 `.trellis/spec/backend/provider-pattern.md`

- `:15`「2026-10-05 DDD 四层化落地时复核：llm worker 具体类…不抽端口」——重写为已抽端口。
- `:30`「已落地的四个端口接口及其领域位置」——补五个新端口，并注明 `ToolDiagnostics` 例外地定义在 application 层（附理由）。
- `:220`/`:222` 反模式表**不动**——本轮端口完全符合。

### 7.4 package-info（6 处改 + 0 处新建）

- `application/package-info.java:5-9` —— 依赖规则段（豁免清单 a 的详述）重写。
- `infrastructure/package-info.java:7-13` —— 豁免段重写为窄口白名单。
- `infrastructure/persistence/package-info.java:4` —— 「保持具体类，不抽接口」重写。
- `domain/package-info.java:8-9` —— 端口清单补四个新端口。
- `application/diagnostics/package-info.java` —— 整段重写：不再是「诊断应用服务」，而是「application 定义的出站端口 + 出参 record，业务无关故不落 domain」。
- `domain/task/package-info.java`、`domain/subtitle/package-info.java` —— 补端口说明。

### 7.5 代码内 Javadoc

- `MybatisVideoTaskRepository` —— 删「`@Repository` 具体类、不抽接口…」那段；补「实现 `domain/task/VideoTaskRepository` 端口」。
- `TaskService` 构造参数 Javadoc `:75`「application/ 唯一可见的持久化门面」—— 措辞调整。
- `ProcessToolDiagnostics` / 三个 `Llm*` —— 补「实现某某端口」说明，删原文里「不抽接口」「终态形态」类表述。
- `MediaController` —— 删豁免 b 说明；补「只依赖 application」。
- `TaskProgressMessage` —— 补派生方法的 Javadoc，写明**为何不用 `isXxx` 命名**。

## 8. 关键取舍存档（grill 结论）

| # | 决策 | 结论 | 理由摘要 |
|---|---|---|---|
| 1 | 调用点在哪层 | application 编排类注入端口 | 「application 只剩步骤编排」与「编排搬进 domain」互斥 |
| 2 | 端口命名 | 接口与实现同名，实现加技术前缀 | 与既有先例 `ASRProvider`→`DashScopeAsrProvider` 一致；顺带取消 `Cleanser→Cleaner` 的拼写变化 |
| 3 | 本轮业务下沉 | 不做 | 硬造 domain service 只会是空壳；「domain service 可调端口」作为架构许可写进 spec |
| 4 | 交付切分 | 单任务，分批 | 改动机械，拆 child 开销大于收益 |
| 5 / 10 | 诊断出参归属 | 搬进 application 并去「External」 | 「外部」是站在应用内部往外看的说法；保留 `@Schema` 因其直接充当 HTTP 契约 |
| 6 | 诊断上下文 | **不新开 `domain/diagnostics/`** | Q16 修正后诊断端口归 application 定义，domain 侧不需要该上下文（原 Q6(a) 作废） |
| 7 → 18 | `ToolDiagnosticsService` | **删除** | 前提变化后它只剩方法转发（原 Q7(a)「保留」的理由是「web 不跳层」，端口搬进 application 后该理由消失） |
| 8 | spec 范围 | 全文对账 | 本项目对失实文档零容忍；留「不抽接口」段落必误导下一会话 |
| 9 | domain service | 本轮不造 | 代码里没有「该由 domain service 承担」的候选 |
| 11 | 实现前缀 | `Mybatis` / `Llm` / `Process` | 与 `DashScope` / `YtDlp` / `Ffmpeg` 同级的短技术名 |
| 12 | 端口出参类型 | 提升为顶层 record | 与 `TranscriptionResult` / `TermLookupResult` / `AcquiredMedia` 三个先例一致 |
| 13 | 提交切分 | 代码 / 文档分两次 | 代码提交可独立验证；文档提交不承担改坏的风险 |
| 14 | 嵌套 record | 提升为顶层 record | 避免同包内两种契约落位 |
| 15 | 便捷重载 | 不进端口 | 契约描述「上层需要什么」，不描述「实现提供了什么方便」 |
| 16 | 端口归属 | 按语义职责分流：4 个 domain + 1 个 application | 判据见 §1.2；LLM 组判为 domain 的依据是与 `ASRProvider` 的同构性（转写同样是调外部模型却在 domain）；**兜底问题上有已知歧义，标为待复核** |
| 17 | 跨层边写法 | 窄口 + 白名单，非主/次分级 | 「主/次」易被读成优先级；白名单写明哪个包能碰、哪个不能，可执行 |
| 19 | 正典条文落法 | 转写为本项目包名 | 所有者给的 `port.in` / `port.out` / `service` / `dto` / `bootstrap` 是通用示例，本项目实际是上下文分包；照抄会与 `directory-structure.md`「二级包按限界上下文分」打架 |
| 20 | application 端口落位 | 随上下文 `application/diagnostics/` | 与 `application/task/query/` 等既有角色子包做法一致；集中放 `port/out/` 只有端口多到散架时才划算 |
| 21 | `web → domain` | 清零（main 源码） | 所有者裁定「这是依赖问题」，按表收紧 |
| 22 | `TaskResponse` 三个枚举字段 | 改 `String` + 取值写进 `@Schema` 描述 | 经核实前端手写契约镜像、不消费 OpenAPI，机器可读枚举约束无消费方，不值得为此在 `web/dto` 硬编码一套与域枚举平行的取值 |
| 23 | 层间规则的 test 豁免 | main 严格、test 豁免（明文写进 spec） | `TaskProgressMessage` / `TaskSnapshot` 的组件本就是 domain 词汇，禁测试引用等于逼 application 为测试造 String 化入口 |

## 9. 行为等价边界与已知副作用

**保证等价**：方法体、日志、异常类型与文案、SQL、HTTP 状态码、SSE 事件形状与 payload、JSON 字段名与类型、持久化格式。

**有意为之的变化**：
1. OpenAPI 文档中 `ToolDiagnosticReport` 的 schema 组件名由 `ExternalToolReport` 改名而来；`@Schema` 描述文案随之更新。JSON 本体不变。
2. OpenAPI 文档中 `TaskResponse` 的 `inputType` / `status` / `translationStatus` 三个字段由 `enum` 降级为 `string`（取值写进描述文案）。JSON 本体不变；经核实无消费方。

**其他副作用**：5 个实现类 + 5 个测试类改名；`application/diagnostics/` 的职责由「服务」变为「端口 + 出参」。

## 10. 回滚形态

改造是**纯结构性**的：无数据库迁移、无配置键变更、无 API 路径变更。回滚即 `git revert` 该代码提交——不存在「回滚后数据不兼容」的问题。唯一跨切面影响是**类名**：回滚后类名回到旧值，与文档提交一起 revert 即可。

分批判定需中途止损时：批次 1–4 各自独立可编译可测试（见 `implement.md`），任一批失败可就地 revert 而不影响其余批次。
