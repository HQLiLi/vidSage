# 错误处理

> 项目已有**全局异常处理器**：`controller/ApiExceptionHandler`（`@RestControllerAdvice`）。它的提取条件是原规范预告的「第二个 Controller 落地、局部处理开始重复」——`MediaController` 落地时触发，见下方「REST 层错误映射」。
> 现有约定是：**在 Provider 内部用卫语句尽早失败，抛 JDK 标准异常并带可操作的信息。**

---

## 卫语句优先

在方法开头集中校验所有前置条件，**先失败再干活**，不要在业务流程中间零散判空。

参照：`provider/asr/dashscope/DashScopeAsrProvider.java` 的 `transcribe()` —— 前置校验（API key、来源非空、URL/路径合法性）连续排在最前面，之后才是 SDK 调用。

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

## 异常类型选择

| 情况 | 异常 | 现有例子 |
|---|---|---|
| 调用方传参不合法 | `IllegalArgumentException` | URL 为空、本地文件不存在或不可读、Controller 卫语句（audioUrl 与 audioFilePath 都传/都不传） |
| 配置缺失/环境不对 | `IllegalStateException` | API key 未配置 |
| 外部服务未按预期返回 | `IllegalStateException` | DashScope 未返回 taskId、子任务失败、成功但无 transcriptionUrl |

**判定标准：错在调用方 → `IllegalArgumentException`；错在环境或外部系统 → `IllegalStateException`。**

项目当前**不定义自定义异常类**。等到需要跨层区分错误类型（例如给前端不同错误码）时再引入，不要提前造。

## 字符串判空

**一律用 `org.springframework.util.StringUtils.hasText(...)`**，不要用 `str != null && !str.isBlank()`。

```java
import org.springframework.util.StringUtils;

if (!StringUtils.hasText(command.language())) {
    builder.parameter("language_hints", List.of(command.language()));
}
```

参照：`provider/asr/dashscope/DashScopeAsrProvider.java`

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
| 为"以后可能用到"预先造自定义异常体系 | 当前代码没有，属于过度设计 |
| 在 Provider 里做重试/降级逻辑 | 重试属于管线编排层，不属于 Provider（现阶段也无此实现） |

## REST 层错误映射（已建立，且已提取为全局）

第一个 Controller（`controller/AsrController.java`）确立了模式；第二个 Controller（`controller/MediaController.java`）落地时局部处理开始重复，**按原规范预告的条件提取成了全局处理器** `controller/ApiExceptionHandler`（`@RestControllerAdvice`）。后续 Controller **不必再写局部 `@ExceptionHandler`**。

- **入参校验用卫语句**：Controller 层判空白/越界即抛 `IllegalArgumentException`（保证切片测试中 Service 被 mock 也能覆盖 400 路径）
- **全局映射**：`ApiExceptionHandler` 把 `IllegalArgumentException` → **400**、`IllegalStateException` → **500**，响应体统一为 `{ "error": message }`（错误信息含「缺什么、怎么补」）
- **日志级别按「谁之过」分**：调用方输入问题（400）记 **WARN**——不该触发 ERROR 告警；服务端或环境故障（500）记 **ERROR** 并**带上异常对象**（保留调用栈供定位）
- **响应体形状只有一处定义**：`{ "error": message }` 由 `ApiExceptionHandler` 统一产出，接口文档也不逐接口重复声明

## 尚未建立的约定

- **错误码体系**：未定义。
- **自定义异常类**：未定义——等到需要跨层区分错误类型（例如给前端不同错误码）时再引入，不要提前造。
- **日志**：已落地，规范见 [logging-guidelines.md](./logging-guidelines.md)。




