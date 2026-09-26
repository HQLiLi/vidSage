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
| 接口 | 定义对外契约，不含任何实现细节或框架注解 | `provider/asr/ASRProvider.java` |
| 实现类 | 一个来源一个类，用 `@Component` + `@ConditionalOnProperty` 装配 | `provider/asr/dashscope/DashScopeAsrProvider.java` |
| 配置属性 | 该实现专用的配置，`record` + `@DefaultValue` | `provider/asr/dashscope/DashScopeProperties.java` |
| 契约 | 入参 `Command` / 出参 `Result`，全部 `record` | `model/asr/Transcription*` |

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
- **`@Component` 而非 `@Service`**：Provider 是出站实现，不是应用服务，标 `@Service` 会误导；它也不在 `service/` 包下。`service/` 包下的应用服务才用 `@Service`（见 [directory-structure.md](./directory-structure.md)）

### 2b. 同能力多实现共存：由配置选择 vs 由输入类型选择

`@ConditionalOnProperty` 的**前提**是「同一能力、多种来源、由配置二选一」——同一时刻容器里只有一个实现 Bean。

**当选择依据是输入类型而非配置时，不要加 `@ConditionalOnProperty`**：多个实现同时驻留，各自由输入类型自荐。

```java
public interface MediaAcquirer {
    /** 由输入子类型决定是否支持，不由配置决定。 */
    boolean supports(MediaInput input);

    Optional<AcquiredMedia> reuseExisting(MediaInput input, Path workDir);

    AcquiredMedia acquire(MediaInput input, Path workDir);
}
```

| 情形 | 装配方式 |
|---|---|
| 同一进程内**只要一个**实现，由 `application.yml` 决定 | 加 `@ConditionalOnProperty`（见本节 §2） |
| 同一进程内**同时需要多个**实现，由**输入类型**决定用哪个 | **不加** `@ConditionalOnProperty`，用 `supports(input)` 自荐 |

反例：一个进程既要能下载 URL、又要能读本地文件，两条路线**在同一次运行里都要在**——用 `@ConditionalOnProperty` 二选一会让另一条路线直接不可用。

参照：`provider/download/MediaAcquirer.java` 与它的两个实现（`YtDlpMediaAcquirer` / `LocalFileMediaAcquirer`）

#### 想要一份"写死的清单"：`@Bean List<T>` 能否生效，取决于容器里有没有 `T` 的 bean

容器解析 `List<T>` 依赖时**只看元素类型 `T` 的候选**（`resolveMultipleBeanCollection`；`spring-beans-7.0.9` / Boot 4.1.1 反编译核实）：

| 容器里 `T` 类型的 bean | 该注入点实际来源 | 同类型的 `@Bean List<T>` |
|---|---|---|
| 0 个 | 落回单 bean 匹配 → **用它** | ✅ 生效 |
| ≥1 个 | 容器按类型收集并返回 | ❌ **被完全无视**（成死代码） |

因此有两种走法，按"实现要不要是 bean"选：

- **实现不进容器**（不标 `@Component`、也不各自声明 `@Bean`）→ `@Bean List<T>` 直接生效
- **实现是 bean**（要能单独注入 / 加切面）→ 在注入点加 `@Qualifier("<那个 List bean 的名字>")`，即可锁定到显式清单

> **`@Qualifier` 锁定的实测结论（2026-09-26）**：元素候选 **1 个**和 **2 个**两种情形下都锁定成功——qualifier 把元素候选过滤为空（实现 bean 的名字与 qualifier 不匹配）→ 落回单 bean 匹配 → 按 bean 名命中那份显式清单。**因此"`@Bean List` 必然被无视"是错的**，前提是注入点带了 qualifier。

两点注意：

- **顺序**：容器收集的顺序 **≠** 你书写的顺序（实测 `List.of(a, b)` 被反成 `[b, a]`）。要顺序确定，就用显式清单
- **qualifier 里写的是 bean 名** → 改名会让注入失败（**响亮**，不是静默）

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
- 参照：`model/asr/AudioSource.java`、`provider/asr/dashscope/DashScopeAsrProvider.java` 的 `transcribe()` 卫语句

## 第三方 SDK 只在实现类内出现

**这是 Provider 模式的核心价值，也是新实现最容易破的规矩。**

`DashScopeAsrProvider` 用到的 `TranscriptionParam`、`TranscriptionQueryParam`、`TaskStatus` 全部是阿里 SDK 类型，它们**只出现在这一个类的私有方法和局部变量里**。对外暴露的 `transcribe()` 方法签名只有本包的 record。

新增本地实现时（例如 `LocalFunAsrProvider`），只要同样只吐 `TranscriptionResult`，上层管线一行都不用改。

参照：`provider/asr/dashscope/DashScopeAsrProvider.java` —— `buildParam()` 是 `private`，SDK 类型不外泄。

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
- **Boot 4 用 Jackson 3，但只有一半搬了家**——别照旧文档的包名写：
  - `ObjectMapper` 与异常在 **`tools.jackson.*`**：`tools.jackson.databind.ObjectMapper`、`tools.jackson.core.JacksonException`
  - **注解仍在 `com.fasterxml.jackson.annotation.*`**：`@JsonProperty`、`@JsonIgnoreProperties`，包名**未变**
  - 后果：Jackson 2 的 `com.fasterxml.jackson.databind.ObjectMapper` 在 Boot 4 里**没有 bean**，`@Autowired` 它会直接注入失败
- 映射逻辑抽成**包级可见**的方法，使单元测试无需 Spring 容器即可直接调用。**`static` 只是达成该判据的常见形态之一，不是目的**：
  - 解析本身不需要注入依赖 → 用 `static`（如 `DashScopeAsrProvider.toResult`）
  - 解析需要注入的依赖（如 `ObjectMapper`）→ **instance 方法即可**，把依赖降成参数反而别扭（如 `YtDlpMediaAcquirer.parseInfoJson`：instance、包级可见、持有注入的 `ObjectMapper`）

参照：`provider/asr/dashscope/DashScopeAsrProvider.java` 与 `provider/asr/dashscope/DashScopeAsrProviderTest.java`

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
| 用 `@Primary` + `@Qualifier` 选实现 | 同时刻存在多个实现 Bean，配置切换不直观。（**不适用于**「用 `@Qualifier` 锁定显式清单 Bean」——那条钦定的是清单，不是在实现之间挑，见 §2b） |
| 让 SDK 类型出现在接口签名里 | 换实现时要改上层，Provider 模式失效 |
| 契约类用 Lombok `@Data` | 契约必须不可变，用 record |
| 返回 `null` 集合 | 用 `List.of()`，调用方无需判空 |
| 映射逻辑写成 `private` | 测试被迫起 Spring 上下文或走完整流程。判据是**包级可见**，不是「必须 `static`」 |
| 契约字段绑定某个实现的输入形状（如 `audioUrl`） | 换实现就要改契约，抽象失效 |
| 为第二个实现复制一份契约类 | 契约属于环节，不属于实现 |

---

## 新增一个实现的检查清单

1. `implements <X>Provider`，只实现接口方法
2. 加 `@Component` + `@ConditionalOnProperty(name = "vidsage.<环节>.provider", havingValue = "<短名>")`
3. 需要配置就建 `<实现名>Properties` record，加 `@EnableConfigurationProperties`
4. 第三方响应在类内建嵌套 record，加 `@JsonIgnoreProperties(ignoreUnknown = true)`
5. 映射抽成**包级可见**的方法（不需注入依赖用 `static`；需注入依赖用 instance 方法）
6. 在 `application.yml` 补配置块，密钥用 `${ENV}` 插值
7. 写单元测试覆盖映射与参数校验失败路径（见 [quality-guidelines.md](./quality-guidelines.md)）



