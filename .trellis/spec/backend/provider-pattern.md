# Provider 模式

> 本项目最重要的架构约定。ASR、LLM、Embedding 三个环节都走这套模式：
> **接口抽象 + 多实现 + 配置驱动装配**，本地与云端各一个实现，`application.yml` 切换。

---

## 何时适用

新增或修改任何一个「同一能力、多种来源」的环节时：本地模型 vs 云 API、多家云厂商备选、主实现 vs 兜底实现。

已落地：`ASRProvider`
规划中（见主 PRD 与 `docs/prd/local-model-deployment.md`）：`LLMProvider`、`EmbeddingProvider`

---

## 四件套

每个环节固定由四个部分组成，缺一不可：

| 部件 | 职责 | 参照文件 |
|---|---|---|
| 接口 | 定义对外契约，不含任何实现细节或框架注解 | `asr/ASRProvider.java` |
| 实现类 | 一个来源一个类，用 `@Component` + `@ConditionalOnProperty` 装配 | `asr/provider/dashscope/DashScopeAsrProvider.java` |
| 配置属性 | 该实现专用的配置，`record` + `@DefaultValue` | `asr/provider/dashscope/DashScopeProperties.java` |
| 契约 | 入参 `Command` / 出参 `Result`，全部 `record` | `asr/Transcription*` |

### 1. 接口保持纯净

```java
public interface ASRProvider {
    TranscriptionResult transcribe(TranscriptionCommand command);
}
```

- 不继承任何框架接口
- 不加 `@Component` 等注解
- 方法只接收和返回本包定义的契约类型，**不出现 SDK 类型**

### 2. 实现类按配置装配

```java
@Component
@ConditionalOnProperty(name = "vidsage.asr.provider", havingValue = "cloud")
@EnableConfigurationProperties(DashScopeProperties.class)
public class DashScopeAsrProvider implements ASRProvider {
```

- 属性名格式：`vidsage.<环节>.provider`，值是该实现的短名（`cloud` / `local`）
- **用 `@ConditionalOnProperty` 而不是 `@Primary` / `@Qualifier`** —— 同一时刻容器里只有一个实现 Bean
- `@Component` 而非 `@Service`（见 [directory-structure.md](./directory-structure.md)）

### 3. 配置属性用 record

```java
@ConfigurationProperties(prefix = "vidsage.asr.dashscope")
public record DashScopeProperties(
        String apiKey,
        @DefaultValue("qwen-audio-3.1-asr-flash-filetrans") String model) {
}
```

- `record` 而非 class —— 不可变，无需 setter
- 默认值用 `@DefaultValue`，**不写 `application.yml` 里已经写了的重复默认值也可**（见 `DashScopeProperties` 类注释）
- 前缀与 `@ConditionalOnProperty` 的父路径对齐：`vidsage.asr.dashscope.*` ↔ `vidsage.asr.provider`
- 依靠启动类的 `@ConfigurationPropertiesScan` 被发现

### 4. 契约全部 record

```java
public record TranscriptionCommand(AudioSource source, String language) {
}

public record TranscriptionResult(List<TranscriptionSegment> segments, String language) {
}

public record TranscriptionSegment(long beginTimeMs, long endTimeMs, String text) {
}
```

- **契约一律 `record`**，不加 Lombok（Lombok 不用于契约类）
- 参数名即语义，配合 Javadoc 写清约束
- 时间统一用**毫秒 `long`**（`beginTimeMs` / `endTimeMs`），字段名带单位后缀
- `TranscriptionResult` 这类聚合结果用 `List<>`，**用 `List.of()` 表达空集合，不返回 `null`**（见 `DashScopeAsrProvider.toResult`）

---

### 5. 契约必须实现方中立

契约描述「做什么」，不描述「某个实现怎么吃」。**契约类中禁止出现任何实现方专属的字段名、约束或注释**——第一次写实现时最容易把该实现的输入要求直接固化成契约字段（如 `audioUrl` 来自 DashScope 的接口限制），导致第二个实现（如本地文件输入）出现时契约必须重改。

实现相关的输入差异用中立抽象表达（sealed 接口 + 子类型，各实现自行校验支持的类型）：

```java
public sealed interface AudioSource {
    record AudioUrl(String value) implements AudioSource { }
    record AudioFilePath(String value) implements AudioSource { }
}
```

- 不支持的子类型：抛 `IllegalArgumentException`，错误信息说明该实现的输入要求与替代做法（如「先上传生成外链」）
- 参照：`asr/model/AudioSource.java`、`asr/provider/dashscope/DashScopeAsrProvider.java` 的 `transcribe()` 卫语句

## 第三方 SDK 只在实现类内出现

**这是 Provider 模式的核心价值，也是新实现最容易破的规矩。**

`DashScopeAsrProvider` 用到的 `TranscriptionParam`、`TranscriptionQueryParam`、`TaskStatus` 全部是阿里 SDK 类型，它们**只出现在这一个类的私有方法和局部变量里**。对外暴露的 `transcribe()` 方法签名只有本包的 record。

新增本地实现时（例如 `LocalFunAsrProvider`），只要同样只吐 `TranscriptionResult`，上层管线一行都不用改。

参照：`asr/provider/dashscope/DashScopeAsrProvider.java` —— `buildParam()` 是 `private`，SDK 类型不外泄。

---

## 外部响应映射

云 API 的响应结构**用嵌套 record + Jackson 注解**在实现类内部建模，**不建独立 DTO 文件**：

```java
@JsonIgnoreProperties(ignoreUnknown = true)
record TranscriptionFile(
        @JsonProperty("file_url") String fileUrl,
        @JsonProperty("audio_info") AudioInfo audioInfo,
        List<Transcript> transcripts) { ... }
```

规则：

- 嵌套 record 定义在实现类**内部**（`DashScopeAsrProvider.TranscriptionFile`），不单独建文件
- 必须加 `@JsonIgnoreProperties(ignoreUnknown = true)`，防止上游加字段导致反序列化失败
- snake_case 字段用 `@JsonProperty` 显式映射
- 映射逻辑抽成 **`static` 方法**（`toResult`），包级可见（**不带 `private`**），这样单元测试可以直接调用而不必起 Spring 容器

参照：`asr/provider/dashscope/DashScopeAsrProvider.java` 与 `asr/provider/dashscope/DashScopeAsrProviderTest.java`

---

## 配置切换

`application.yml`：

```yaml
vidsage:
  asr:
    # 本地 FunASR 供应商为规划项；当前默认走云端
    provider: cloud
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      model: qwen-audio-3.1-asr-flash-filetrans
```

- 密钥**一律走环境变量插值** `${ENV_VAR}`，不把真实密钥写进 yml
- 在 `provider` 上方留一行注释说明当前选择与原因
- 主 PRD 目标态是**本地默认、云端兜底**；当前默认值是 `cloud`（本地实现尚未写）

---

## 反模式

| 不要做 | 原因 |
|---|---|
| 在接口上写 `@Component` / `@Conditional` | 接口必须与框架解耦 |
| 用 `@Primary` + `@Qualifier` 选实现 | 同时刻存在多个实现 Bean，配置切换不直观 |
| 让 SDK 类型出现在接口签名里 | 换实现时要改上层，Provider 模式失效 |
| 契约类用 Lombok `@Data` | 契约必须不可变，用 record |
| 返回 `null` 集合 | 用 `List.of()`，调用方无需判空 |
| 映射逻辑写成非静态的私有方法 | 测试被迫起 Spring 上下文 |
| 契约字段绑定某个实现的输入形状（如 `audioUrl`） | 换实现就要改契约，抽象失效 |
| 为第二个实现复制一份契约类 | 契约属于环节，不属于实现 |

---

## 新增一个实现的检查清单

1. `implements <X>Provider`，只实现接口方法
2. 加 `@Component` + `@ConditionalOnProperty(name = "vidsage.<环节>.provider", havingValue = "<短名>")`
3. 需要配置就建 `<实现名>Properties` record，加 `@EnableConfigurationProperties`
4. 第三方响应在类内建嵌套 record，加 `@JsonIgnoreProperties(ignoreUnknown = true)`
5. 映射抽成包级 `static` 方法
6. 在 `application.yml` 补配置块，密钥用 `${ENV}` 插值
7. 写单元测试覆盖映射与参数校验失败路径（见 [quality-guidelines.md](./quality-guidelines.md)）



