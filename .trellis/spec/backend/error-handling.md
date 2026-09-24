# 错误处理

> 当前项目**没有全局异常处理器**（无 `@ControllerAdvice`、无自定义异常类）。
> 现有约定是：**在 Provider 内部用卫语句尽早失败，抛 JDK 标准异常并带可操作的信息。**

---

## 卫语句优先

在方法开头集中校验所有前置条件，**先失败再干活**，不要在业务流程中间零散判空。

参照：`asr/provider/dashscope/DashScopeAsrProvider.java` 的 `transcribe()` —— 前置校验（API key、来源类型、URL 非空）连续排在最前面，之后才是 SDK 调用。

```java
if (!StringUtils.hasText(properties.apiKey())) {
    throw new IllegalStateException("Missing DashScope API key: set vidsage.asr.dashscope.api-key or DASHSCOPE_API_KEY");
}
if (!(command.source() instanceof AudioSource.AudioUrl audioUrl)) {
    throw new IllegalArgumentException(
            "DashScope cloud transcription requires a publicly accessible audio URL (AudioUrl); "
                    + "for local files upload them first or pass an AudioUrl. Got: "
                    + (command.source() == null ? "null" : command.source().getClass().getSimpleName()));
}
```

## 异常类型选择

| 情况 | 异常 | 现有例子 |
|---|---|---|
| 调用方传参不合法 | `IllegalArgumentException` | 来源类型不支持（本地路径传给云端实现）、URL 为空 |
| 配置缺失/环境不对 | `IllegalStateException` | API key 未配置 |
| 外部服务未按预期返回 | `IllegalStateException` | DashScope 未返回 task id、子任务失败、成功但无下载地址 |

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

参照：`asr/provider/dashscope/DashScopeAsrProvider.java`

## 错误信息写法

**必须包含"缺什么"和"怎么补"两个信息。**

```java
// 好：说清了配置项名和环境变量名
"Missing DashScope API key: set vidsage.asr.dashscope.api-key or DASHSCOPE_API_KEY"

// 好：说清了触发条件与解决办法
"DashScope cloud transcription requires a publicly accessible audio URL (AudioUrl); for local files upload them first or pass an AudioUrl. Got: AudioFilePath"

// 坏：只陈述现象，调用方不知道下一步做什么
"Invalid parameter"
```

对外部系统失败，**把实际状态附上**：

```java
throw new IllegalStateException("DashScope task did not succeed: " + finished.getTaskStatus());
throw new IllegalStateException("DashScope subtask failed: " + taskResult.getMessage());
```

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
    throw new IllegalStateException("DashScope transcription file has no transcripts");
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

## 尚未建立的约定

- **全局异常处理**：无 `@ControllerAdvice`，因为目前还没有 Controller。新增 REST 接口时再定这一层。
- **错误码体系**：未定义。
- **日志**：见 [index.md](./index.md) 说明，当前无日志语句。




