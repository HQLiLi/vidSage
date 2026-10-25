# 错误处理

> 项目已有**全局异常处理器**：`web/controller/ApiExceptionHandler`（`@RestControllerAdvice`）。它的提取条件是原规范预告的「第二个 Controller 落地、局部处理开始重复」——`MediaController` 落地时触发，见下方「REST 层错误映射」。
> 现有约定是：**在 Provider 内部用卫语句尽早失败，抛 JDK 标准异常并带可操作的信息。**

---

## 卫语句优先

在方法开头集中校验所有前置条件，**先失败再干活**，不要在业务流程中间零散判空。

参照：`infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java` 的 `transcribe()` —— 前置校验（API key、来源非空、URL/路径合法性）连续排在最前面，之后才是 SDK 调用。

```java
if (!StringUtils.hasText(properties.apiKey())) {
    throw new IllegalStateException("缺少 DashScope API key：请设置 vidsage.asr.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY");
}
if (source == null) {
    throw new IllegalArgumentException(
            "音频来源不能为空：请传 AudioUrl（公网 URL）或 AudioFilePath（本地文件）");
}
// AudioSource 是 sealed 接口，switch 穷尽分派；各分支再校验该来源取值本身的合法性
return switch (source) {
    case AudioSource.AudioUrl url -> requirePublicUrl(url);                  // URL 为空 -> IAE
    case AudioSource.AudioFilePath local -> uploadToTemporaryStorage(local); // 文件不存在/不可读 -> IAE
};
```

### 前置守卫的命名：`validateXxx`（2026-10-17 立）

把卫语句抽成方法时，**不返回值的**（不满足即抛、通过了就往下走）名字用 **`validateXxx`**：

```java
private void validateStatus(SubtitleArtifactStatus required, String targetDescription) { ... }  // 领域状态机
private static void validateTaskNo(String taskNo) { ... }                                        // 入参边界
```

**为什么不用 `requireXxx`**：`require` 读起来像「向调用方索要一个东西」（Kotlin 的 `require(condition)` 也是这个语义），而这类方法**不索要、不改状态、只校验并拒绝** —— `validate` 才是它干的事。这是 2026-10-17 由项目所有者定的口径。

**但「取值即抛」的访问器仍叫 `requireXxx`** —— 它们**返回一个值**，`validate` 不承诺返回，套上去会撒谎：

| 保留 `requireXxx` 的（返回值的） | 返回 |
|---|---|
| `ProcessToolDiagnostics.requireFfmpeg()` / `requireFfprobe()` / `requireYtDlp()` / `requireResolved(...)` | `Path` —— 解析不出来就抛 |
| `DashScopeAsrProvider.requirePublicUrl(url)` | `String` —— 校验通过就返回那个 URL |
| `YtDlpMediaAcquirer.requireTitle(info)` | `String` —— 取不到就抛 |

**存量待改清单：已清空。** 全仓不再有不返回值的 `requireXxx`；状态机实体用的是 `validateStatus` / `validateRunning`。

## 异常类型选择

| 情况 | 异常 | 现有例子 |
|---|---|---|
| 调用方传参不合法 | `IllegalArgumentException` | URL 为空、本地文件不存在或不可读、Controller 卫语句（audioUrl 与 audioFilePath 都传/都不传） |
| 配置缺失/环境不对 | `IllegalStateException` | API key 未配置 |
| 外部服务未按预期返回 | `IllegalStateException` | DashScope 未返回 taskId、子任务失败、成功但无 transcriptionUrl |

**判定标准：错在调用方 → `IllegalArgumentException`；错在环境或外部系统 → `IllegalStateException`。**

### 补充判据：非法值是「谁产生的」决定异常类型

上一张表回答「种类」，这条回答「**归属**」——同一个非法值，来源不同就该是不同的异常：

- **调用方产生**（请求体 / URL / 路径）→ 在 **HTTP 边界卫语句**里拦下，抛 `IllegalArgumentException` → **400 + WARN**
- **服务端自己算出** → 在 `domain/` 值对象的**紧凑构造器不变量**里拦下，抛 `IllegalStateException` → **500 + ERROR**（先例：`domain/task/model/TaskNo` 对空白 taskNo、`domain/media/model/AudioSpec` 对非正采样率 / 负音轨序号）

**落地规则：任何「拿调用方的值构造 `domain/` 值对象」的代码，必须先有一个边界卫语句。** 构造器那层是**最后防线**——它触发即意味着边界漏了。

**为何必须分两处**：`AudioSpec` 的构造器只看得到「`audioTrackIndex` 是负的」，**看不到这个负值是客户端传的还是服务端算的**。「谁之过」只能在知道来源的地方决定。

**卫语句的位置同样重要：必须早于一切 I/O。** 若校验排在服务层的获取 / 抽取之后，客户端参数错误会被**前面 I/O 的失败掩盖**。实测对照（2026-09-26，传 `audioTrackIndex: -1` 且媒体源不可获取）：

| | 旧结构（校验在服务层、排在 acquire 之后） | 现结构（卫语句在 HTTP 边界） |
|---|---|---|
| 返回 | **500**「未找到 yt-dlp」——参数错误根本没被看见 | **400**「音频轨序号不能为负」 |

源**可**获取时两者才同为 400。故守卫的**位置**（边界，早于任何服务调用）与守卫的**存在**同等重要：把卫语句「整理」进服务层，会让客户端错误重新变得**取决于环境是否就绪**。

**同一个值被检查两次不是冗余**：「调用方给的合法吗？」（边界）与「我们内部造出来的符合不变量吗？」（构造器）是**两问两答**——同一谓词、不同失败含义，而**含义决定状态码**。本项目实例：`SubtitleTaskFinder.require` 对入参 taskNo 先做 `StringUtils.hasText` 卫语句（IAE → 400），`TaskNo` 紧凑构造器再查一次空白（ISE → 500），同一谓词、异常类型随归属不同。

**残留风险（有意接受）**：边界卫语句若漏写，客户端错误会被构造器报成 **500**。取舍理由是**失败方向反转**——旧形态是服务端 bug **伪装成 400 / WARN 静默存活**，新形态把服务端疏漏**喊成 500 / ERROR，可见**。宁可大声暴露服务端缺陷，也不要藏进「用户错误」里。

**自定义异常类按「需要跨层区分错误码时再引入」这一条件引入，现已有六个**（清单见文末「尚未建立的约定」）。
上表与本判据仍以**语义**表达、不绑类型：默认用 IAE（错在调用方）/ ISE（错在环境）承载含义，
只有必须让前端区分状态码时才补一个具名类型 + 一条 `ApiExceptionHandler` 映射——**不为「以后可能用到」提前造**。

## 字符串判空

**一律用 `org.springframework.util.StringUtils.hasText(...)`**，不要用 `str != null && !str.isBlank()`。

```java
import org.springframework.util.StringUtils;

if (!StringUtils.hasText(command.language())) {
    builder.parameter("language_hints", List.of(command.language()));
}
```

参照：`infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java`

## 错误信息写法

**必须包含"缺什么"和"怎么补"两个信息。**

```java
// 好：说清了配置项名和环境变量名
"缺少 DashScope API key：请设置 vidsage.asr.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY"

// 好：说清了触发条件与解决办法
"本地音频文件不存在：" + path.toAbsolutePath() + "；请检查路径，或改传 audioUrl"

// 坏：只陈述现象，调用方不知道下一步做什么
"参数不合法"
```

对外部系统失败，**把实际状态附上**：

```java
throw new IllegalStateException("DashScope 任务未成功：" + finished.getTaskStatus());
throw new IllegalStateException("DashScope 子任务失败：" + taskResult.getMessage());
```

## 错误信息语言：中文为主，专有名词保留原文

**项目级硬性要求**：异常消息与日志消息一律用中文书写，以下「专有名词」保留英文原样、不翻译：

| 类别 | 例子 |
|---|---|
| 配置键 | `vidsage.asr.dashscope.api-key` |
| 环境变量名 | `DASHSCOPE_API_KEY` |
| 请求/契约字段名 | `audioUrl`、`audioFilePath`、`transcriptionUrl`、`transcripts` |
| 类名 / 类型名 | `AudioUrl`、`AudioFilePath` |
| 外部系统与产品名 | `DashScope`、`OSS`、`DeepSeek` |
| 外部返回的状态值与错误码 | `taskId`、`output`、`REQUEST_INVALID_FILE_URL_VALUE` |

```java
// 好：中文说明 + 保留配置键与环境变量名
"缺少 DashScope API key：请设置 vidsage.asr.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY"

// 好：中文说明 + 保留字段名
"本地音频文件不存在：" + path.toAbsolutePath() + "；请检查路径，或改传 audioUrl"

// 坏：整句英文（违背本条）
"Local audio file does not exist: ..."
```

理由：这些消息经 Controller 的 `{ "error": message }` 直接返回给前端展示，中文面向用户；保留专有名词是为了让使用者能把报错里的名字原样复制到配置或代码里检索。

## 空集合与空值

- **返回集合一律用 `List.of()` 表达空，不返回 `null`**

```java
List<TranscriptionSegment> segments = transcript.sentences() == null ? List.of()
        : transcript.sentences().stream()
                .map(sentence -> new TranscriptionSegment(...))
                .toList();
```

- 但**上游响应整体为空时要抛异常**，因为那说明契约被破坏，静默返回空集合会掩盖问题：

```java
if (file == null || file.transcripts() == null || file.transcripts().isEmpty()) {
    throw new IllegalStateException("DashScope 转写结果文件中没有任何 transcripts");
}
```

**区分标准：合法的"没有内容"→ 空集合；不该出现的"没有内容"→ 抛异常。**

## 反模式

| 不要做 | 原因 |
|---|---|
| 吞异常（`catch` 后不处理或只 `printStackTrace`） | 现有代码没有一处吞异常，保持这个纪律 |
| 返回 `null` 表示失败 | 调用方必须判空，容易漏 |
| 抛异常但 message 无上下文 | 线上无法定位 |
| 为"以后可能用到"预先造自定义异常体系 | 只在「需要跨层区分错误码」时加类型（现共六个，见文末清单），其余仍走 IAE / ISE 语义 |
| 在 Provider 里做重试/降级逻辑 | 重试属于管线编排层，不属于 Provider（现阶段也无此实现） |

## REST 层错误映射（已建立，且已提取为全局）

模式由首个 Controller 确立（该类已于 2026-10-02 收编旧同步端点时移除）；第二个 Controller（`web/controller/MediaController.java`）落地时局部处理开始重复，**按原规范预告的条件提取成了全局处理器** `web/controller/ApiExceptionHandler`（`@RestControllerAdvice`）。后续 Controller **不必再写局部 `@ExceptionHandler`**。

- **入参校验用卫语句**：Controller 层判空白/越界即抛 `IllegalArgumentException`（保证切片测试中 Service 被 mock 也能覆盖 400 路径）
- **全局映射**：`ApiExceptionHandler` 把 `IllegalArgumentException` → **400**、`IllegalStateException` → **500**，响应体统一为 `{ "error": message }`（错误信息含「缺什么、怎么补」）；`TaskNotFoundException` / `ArtifactNotFoundException` → **404**；`TaskConflictException` → **409**（载荷 `{taskNo, runNo}`，见 `TaskRunRefResponse`）；`CorrectionSegmentOutdatedException` → **409**（通用 `{error}` 形状，见下条）
- **409 有两种语义，响应体形状不同。**
  - **① 该任务已有一次执行在跑**：抢互斥就是插一条 `RUNNING` 的 run —— `uk_task_run_task_id_active_flag` 只约束 `RUNNING` 行（其余行的生成列为 NULL、不参与唯一比较），撞键即 409，**不排队等锁**。提交 / 重新生成 / 翻译三条触发路径共用这一句 INSERT（`TaskRunSupervisor.start`），响应体 `{taskNo, runNo}` **与 202 同形状、不含文案**：调用方靠状态码判冲突，拿这对去订阅那条正在跑的 run。
  - **② 要改的字幕条目已过期**（`CorrectionSegmentOutdatedException`，2026-10-25）：字幕重新生成过、切分变了，这个 segment id 已不在当前 CLEANED 里。走通用 `{error}` 形状，因为用户得读到「刷新后重试」这句话，而不是一对任务号。

  **「产物尚未就绪」是 400，不是 409。**
- **日志级别按「谁之过」分**：调用方输入问题（400 / 404 / 409）记 **WARN**——不该触发 ERROR 告警；服务端或环境故障（500）记 **ERROR** 并**带上异常对象**（保留调用栈供定位）
- **响应体形状只有一处定义**：`{ "error": message }` 由 `ApiExceptionHandler` 统一产出，接口文档也不逐接口重复声明

## 尚未建立的约定

- **错误码体系**：未定义。
- **自定义异常类**：已按「需要跨层区分错误码时再引入」的既定条件落地**六个**，每个要么自带 `ApiExceptionHandler` 映射、要么继承现成映射的类型：

  | 异常 | 包 | 映射 | 场景 |
  |---|---|---|---|
  | `TaskNotFoundException` | `application/task/exception/` | 404 | 按 taskNo 取资源的端点统一（任务查询、SSE 订阅、产物清单与下载） |
  | `ArtifactNotFoundException` | `application/subtitle/exception/` | 404（专用映射） | 任务在、但这一版产物不可用：该 `(type, lang)` 没有完好的当前版本，或文件已不在工作区（清单与下载端点） |
  | `TaskConflictException` | `application/task/exception/` | 409（载荷 `{taskNo, runNo}`） | 该任务已有一次执行在跑（撞互斥唯一键） |
  | `UnsupportedLanguageException` | `domain/exception/` | 400（专用映射） | 目标语种不在支持列表（`SupportedLanguage.parse` 认不出） |
  | `MediaSourceNotFoundException` | `application/media/exception/` | 404（专用映射） | 任务在、但没有可播放的媒体源：没有 `READY` 的 `SOURCE_MEDIA` 行，或登记的文件已不在 / 为空（媒体源播放端点） |
  | `CorrectionSegmentOutdatedException` | `application/subtitle/exception/` | 409（专用映射，通用 `{error}` 形状） | 要改的字幕条目已不在当前 CLEANED 字幕里（字幕重新生成过、切分变了）；**不复用** `TaskConflictException`，因为用户要读到「刷新后重试」这句文案 |

  **不再为别的场景提前造**：其余错误仍按 IAE / ISE 的语义表达。
- **日志**：已落地，规范见 [logging-guidelines.md](./logging-guidelines.md)。




