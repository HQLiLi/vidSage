# 代码质量规范

---

## 一、不重复造轮子（项目级硬性要求）

**有成熟的主流库/插件/官方 SDK 就用它，不要手搓。**

这条是项目所有者的明确规定，适用于前后端全部代码。已有代码的体现：

- **转写任务提交与轮询**：直接用官方 `dashscope-sdk-java` 的 `Transcription` 类（`asyncCall` + `wait`），没有手写 HTTP 轮询循环。

  ```java
  // provider/asr/dashscope/DashScopeAsrProvider.java
  var submitted = transcription.asyncCall(param);
  var finished = transcription.wait(
          TranscriptionQueryParam.FromTranscriptionParam(param, submitted.getTaskId()));
  ```

- **HTTP 客户端**：用 Spring 的 `RestClient`（通过注入的 `RestClient.Builder` 构建），不自己拼 `HttpURLConnection`。
- **JSON**：用 Jackson 注解（`@JsonProperty` / `@JsonIgnoreProperties`），不手写解析。
- **配置绑定**：用 `@ConfigurationProperties` record，不自己读 `Environment`。

### 判断标准

| 场景 | 做法 |
|---|---|
| 官方 SDK / 主流 starter 覆盖 | **直接用**，哪怕它多带几个用不上的类 |
| 主流库需要少量适配 | 用适配代码包一层，**不要因此重写整个功能** |
| 确实没有合适轮子 | 才自己写，并在注释里说明为何不用现成方案 |

> 唯一例外是**接口契约需要自己控制**时——例如本地 FunASR 服务倾向自建 FastAPI 封装而非用官方镜像，理由就是要控制返回格式。这类偏离必须在 PRD 或代码注释里写明理由。

---

## 二、注释要求（项目级硬性要求）

**每个函数/方法都要写注释。**

### 已有代码的写法（跟随它）

对外类型与契约方法用 Javadoc，说明语义与约束，**不只是复述方法名**：

```java
/**
 * 一句话转写结果，时间戳用于前端点击引用跳转。
 *
 * @param beginTimeMs 句子开始时间（毫秒）
 * @param endTimeMs   句子结束时间（毫秒）
 * @param text        句子识别文本
 */
public record TranscriptionSegment(long beginTimeMs, long endTimeMs, String text) {
}
```

契约参数上的**负面约束尤其要写**——即"什么情况下不要这么传"：

```java
/**
 * @param source   音频来源（URL 或本地路径，由各实现校验自己支持的类型）
 * @param language 可选的语种提示，如 "zh"；语种不确定或多语种混合时不要传
 */
public record TranscriptionCommand(AudioSource source, String language) {
}
```

### 约定

- 语言：**中文**。与现有代码一致。
- 公共类型、公共方法、record 的每个参数：**Javadoc**
- 私有方法：可用行注释说明**为什么**这样做，而不是做了什么
- 解释**原因和约束**，不解释显而易见的代码

参照：`provider/asr/ASRProvider.java`、`model/asr/TranscriptionCommand.java`、`model/asr/AudioSource.java`、`provider/asr/dashscope/DashScopeProperties.java`

> 注：现有代码的私有方法（如 `buildParam`）**尚未**加注释。本规范生效后新增的私有方法要补上——不要因为旧代码没写就跟着不写。

---

## 三、契约设计

- 对外契约（`Command` / `Result` / 子结构）**一律 `record`**，见 [provider-pattern.md](./provider-pattern.md)
- 时间字段带单位后缀（`beginTimeMs`），用 `long`
- 集合字段不返回 `null`，用 `List.of()`

## 四、Lombok 的使用边界

`pom.xml` 引入了 Lombok（`optional`，已从 boot jar 排除），但**现有契约类一个都没用**。

- 契约类（`record`）：**不用 Lombok**
- 未来的实体类：主 PRD 规定用普通类 + `@Getter` / `@Setter`
- `@Data` 慎用——它会生成 `equals`/`hashCode`/`toString`，对可变实体往往不是想要的语义

## 五、Java 21 语言特性

`pom.xml` 设 `java.version=21`，可以用现代 API：

- `getFirst()` / `getLast()`（SequencedCollection）—— 已在 `DashScopeAsrProvider` 中使用，优于 `get(0)`
- `record`、`sealed`、模式匹配、文本块
- **虚拟线程已全局开启**：`application.yml` 里 `spring.threads.virtual.enabled: true`。写阻塞式 IO 时不需要自己搞线程池。

## 六、测试

### 工具与风格

- JUnit 5（`org.junit.jupiter.api`）
- **静态导入断言**：`import static org.junit.jupiter.api.Assertions.assertEquals;`
- **测试方法名描述行为**，不用 `testXxx` 前缀：

  ```java
  void mapsSentencesToSegments() { ... }
  void throwsWhenTranscriptsMissing() { ... }
  void requiresApiKey() { ... }
  void rejectsLocalFilePath() { ... }
  ```

  命名模式：正常路径用陈述句（`mapsSentencesToSegments`），异常路径用 `throwsWhen...` / `requires...`。

### 不要为单元测试起 Spring 容器

`DashScopeAsrProviderTest` **没有** `@SpringBootTest`，而是直接 `new` 出被测对象，把依赖手动传进去：

```java
var provider = new DashScopeAsrProvider(
        RestClient.builder(), new DashScopeProperties("", MODEL));
```

这要求被测类**构造函数可注入依赖**（本项目 `DashScopeAsrProvider` 正是构造函数注入）。写新 Provider 时保持这个可测性。

### 映射逻辑必须可直接测

外部响应 → 内部契约的映射方法设计成**包级 `static`**（如 `toResult`），这样测试可以直接调，不必构造完整流程：

```java
TranscriptionResult result = DashScopeAsrProvider.toResult(file);
```

### 覆盖要求

每个 Provider 至少覆盖：

1. 正常映射（字段对齐、毫秒值正确）
2. 上游返回不合法时抛异常
3. 配置缺失时抛异常
4. 入参不合法时抛异常

参照：`backend/src/test/java/com/vidsage/provider/asr/dashscope/DashScopeAsrProviderTest.java`

### 测试数据

测试用的嵌套 record 直接内联构造，不建 fixture 文件：

```java
var file = new DashScopeAsrProvider.TranscriptionFile(
        "https://example.com/audio.mp3",
        new DashScopeAsrProvider.TranscriptionFile.AudioInfo("mp3", 16000),
        List.of(...));
```

## 七、依赖管理

- 版本号统一在 `pom.xml`；Spring AI 通过 `spring-ai-bom` 在 `dependencyManagement` 里导入，**单个依赖不写版本**
- 引入会传递冲突依赖的 SDK 时，**显式 `<exclusion>` 并在注释里写明原因**：

  ```xml
  <!-- SDK 自带 slf4j-simple，与 Spring Boot 的 Logback 冲突 -->
  <exclusion>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
  </exclusion>
  ```

- 仅编译期使用的工具（Lombok）标 `optional` 并从 boot jar 排除

## 八、反模式清单

| 不要做 | 依据 |
|---|---|
| 手写 HTTP 轮询 / JSON 解析 / 配置读取 | 「不重复造轮子」要求 |
| 契约类用 `@Data` 或改成普通 class | 现有契约全为 record |
| 私有方法不写注释 | 「函数方法要写注释」要求 |
| 单元测试起 `@SpringBootTest` | 现有测试均为纯单元测试 |
| 测试方法命名为 `testXxx` | 现有命名为行为描述式 |
| Provider 构造函数里 `new` 出依赖 | 必须构造函数注入，否则不可测 |
| 引入依赖时不排除冲突的日志实现 | pom 已有先例注释 |



