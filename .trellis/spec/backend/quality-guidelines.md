# 代码质量规范

---

## 一、不重复造轮子（项目级硬性要求）

**有成熟的主流库/插件/官方 SDK 就用它，不要手搓。**

这条是项目所有者的明确规定，适用于前后端全部代码。已有代码的体现：

- **转写任务提交与轮询**：直接用官方 `dashscope-sdk-java` 的 `Transcription` 类（`asyncCall` + `wait`），没有手写 HTTP 轮询循环。

  ```java
  // infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java
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

## 二、实现方式选当前主流（项目级硬性要求）

「一」解决**要不要造轮子**，本条解决**走哪条路线**：实现任何功能，先确认**当前**的主流做法并跟随它，不用已经过时的旧主流。项目所有者的明确规定。

已有代码的体现：

- **结构化日志用 Spring Boot 内核内置的 structured logging**，不引 `logstash-logback-encoder`（Boot 3.4 起内核吸收了该能力，见 [logging-guidelines.md](./logging-guidelines.md)）
- **OpenAPI 文档引入时用 `springdoc-openapi`**，不用已停更的 `springfox`
- **Java 21 现代写法优先**（record / sealed / 模式匹配 / `getFirst()`），不沿用旧惯用（见下文 Java 21 节）

### 判断标准

| 场景 | 做法 |
|---|---|
| 框架内核已内置同类能力 | 用内置，不引第三方插件 |
| 旧方案已停更、官方有新替代 | 用新替代，即使旧方案「还能用」 |
| 新旧都在维护、新方案尚不稳定 | 用旧主流，在注释或 PRD 里记录评估结论 |

> 与「一」的组合判断顺序：先问有没有现成轮子（没有→才允许自己写，注释说明理由）；有轮子→再选当前主流的那个、按当前主流的用法用。

---

## 三、注释要求（项目级硬性要求）

> **规则正文在 [guides/comment-guidelines.md](../guides/comment-guidelines.md)**——项目级正典，
> 前后端共用一份，含内容分流表、按层级的写法、真实正反例与防复发钩子。本节只留后端该看的例子与本层约定。

**三句话摘要**

1. **第一原理**：删了会不会有人踩坑。会 → 留；不会 → 不留。
2. **写与删是两件事（2026-10-08 定）**：新写的成员**一律先写上注释**——包括构造函数这类「自动生成」的，默认就带，不要自己先省。写完由用户过目，**他觉得没意义的会自己删掉**。由此推出：**「这里没有注释」不等于违规**（很可能是用户有意删的，先自问「这行是不是看了就懂」，是就别报、也别引本条硬线要求补），**写的人不能越位省**（判断有没有意义是用户的活）。
3. **内容分流**：为什么这样设计 → 任务卡 `design.md`；什么时候改的（日期 / 任务号 / `设计决策 N`）→ `git log`；包级地图 → `package-info.java`；流程步骤与阶段词表 → `.trellis/spec/`；**契约（语义 / 单位 / `null` 含义 / 负面约束）与实现坑留在代码里**。

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
- **全大写常量与枚举常量一律保留注释**，哪怕只是一句人话翻译（`MAX_COUNT` → 「关键词条数上限。」）

参照：`domain/transcription/port/ASRProvider.java`、`domain/transcription/model/TranscriptionCommand.java`、`domain/transcription/model/AudioSource.java`、`infrastructure/provider/asr/dashscope/DashScopeProperties.java`

> 注：现有代码的私有方法（如 `buildParam`）**尚未**加注释。本规范生效后新增的私有方法要补上——不要因为旧代码没写就跟着不写。

---

## 四、契约设计

- 对外契约（`Command` / `Result` / 子结构）**一律 `record`**，见 [provider-pattern.md](./provider-pattern.md)
- 时间字段带单位后缀（`beginTimeMs`），用 `long`
- 集合字段不返回 `null`，用 `List.of()`

### 「守卫型」校验要为目标改动设基线

用相似度这类「最小改动」守卫校验 LLM 输出时，会把**我们要求模型做的那类改动**一并判成违规，把正确的输出打回。

实例（2026-09-28，字幕纠错）：术语表要求把 ASR 记成中文音译的专有名词换成原文（`米特号` → `Mittelhau`），而跨书写系统替换的相似度天然很低——实测 0.571 / 0.684 / 0.492，全低于长句阈值 0.7。模型一旦真替换就被判「改太狠」→ 重试 3 轮 → 用尽后**整批回退原文**，连原本改对的行也一起丢，结果比不替换更差。

- **比对目标要先把「被要求的改动」做进基准文本**，形成**基线**（本例：把命中术语的 `asr` 字面替换成 `fixed`）
- **允许双目标**：`max(ratio(原文, 候选), ratio(基线, 候选)) ≥ 阈值`。**只比基线是错的**——模型按上下文判定「不该改」而保留原文时会被判违规，等于把刚交给模型的判断权又抢回去
- 双目标的代价是「只改一半」也可能通过（术语占比小时两侧都过：0.7727 / 0.7925；占比大时只剩基线侧：0.6875 / 0.7317）。这是把判断权留给模型的自然后果，**配套必须加可观测指标**（命中数 / 已替换数 / 未替换清单），否则失败是静默的——本例最初的故障正是静默漏改

```java
// Wrong：只跟原文比，被要求的替换会被判成「改太狠」
double sim = TextSimilarity.ratio(orig, cand);

// Correct：原文与「做完术语替换的基线」都是合法目标，够到任一个就算过
double best = Math.max(
        TextSimilarity.ratio(orig, cand),
        TextSimilarity.ratio(baselineOf(orig, hits), cand));
```

必测点：**必须有一条钉住「双目标」的用例**——造一条长句（阈值 0.7）且模型保留原文，此时 `ratio(基线, 原文)` 只有 0.432；若有人把实现简化成「只比基线」，这条立刻变红。只测「不替换也不被拦」是**恒真**的（`ratio(原文, 原文) = 1.0`），钉不住任何东西。

参照：`infrastructure/provider/llm/LlmSubtitleCleanser#validate` / `#baselineOf`，用例见 `LlmSubtitleCleanserTest`。

### 字幕术语表契约（`SubtitleContext.Term`）

摘要在前、清洗在后，两段 LLM 之间靠这张表传递专有名词：

| 字段 | 语义 | 约束 |
|---|---|---|
| `asr` | 字幕原文里的写法 | 必须是原文里真实出现过的字串，逐字照抄，不做任何规范化；空格串视为无效，命中判定直接跳过 |
| `fixed` | 正确的写法，**也是字幕里应当替换成的写法** | 正确写法是外语原文时就写外语原文（`Mittelhau`）；与 `asr` 相同时表示无需替换 |
| `note` | **替换理由**：说清 `asr` 错在哪、为什么该换成 `fixed` | 必须提到 asr 与 fixed 的写法；**只能写成「为什么该换成 fixed」**，不得写成「两个写法都可以」的并列口吻——否则清洗模型会把 `fixed` 读成别名，于是并列保留而不替换 |

- 渲染给清洗模型的格式：`- "<asr>" → "<fixed>"（候选正确写法）：<note>`；只列**本批正文里真出现过 asr 的**条目（命中判定只有一处实现：`shared/subtitle/TermMatcher#hitTermsByAsr`，`SubtitleCleanser` 为调用方，渲染、基线、指标三处共用）
- 术语表是**证据不是指令**：换不换由清洗模型按该句上下文判断（读得通就换、读不通保留原写法）。**不要**在提示词里点名哪些词必须换，也**不要**在代码里加强制校验
- 术语表**未命中**的其他专有名词一律不动；`密度号` 这类「同一个词的另一种音译变体」不靠模糊匹配兜底，靠模型自己按上下文推断（2026-09-28 决策）
- 术语替换统计走 `CleanseResult.termHits / termApplied`，口径是**保守近似**（变体形算未替换，宁可多报）：`asr == fixed` 的条目从分子分母都排除；跨批次累计；未替换清单进 DEBUG 日志，汇总数进服务层日志与 HTTP 响应

### 不预先抽接口

**上层需要不依赖某个具体实现类时，就为它抽端口**——多实现是抽端口的常见理由，不是必要条件。端口落在哪一层、叫什么名字，判据见 [directory-structure.md](./directory-structure.md) 的[依赖规则](./directory-structure.md#依赖规则)与[端口归属判据](./directory-structure.md#端口归属判据)。

> **2026-10-06 改判**。原表述是「只有对着真实的可替换缝才引入；现有四个端口各自有明确的多实现或替身需求」——这句对 `domain/media/port/AudioExtractor` 是**失实的**：它一直只有一个实现（`FfmpegAudioExtractor`），其 Javadoc 明写「接口保留是为了让上层不依赖实现类」。新判据只是把一直隐含的规则写成明文，并把 `SubtitleTaskRepository` 与 `infrastructure/provider/llm/` 的三个 LLM worker 一并纳入（端口在 `domain/`，实现在 `infrastructure/`；详见任务 `10-06-port-inversion`）。
>
> **它仍然反对的是**「为以后可能用到」而造的抽象：抽端口要有**当下的上层可见性需求**做依据，不是「将来可能有第二个实现」。

DDD 四层的依赖规则以 [directory-structure.md](./directory-structure.md) 为正典。

## 五、Lombok 的使用边界

`pom.xml` 引入了 Lombok（`optional`，已从 boot jar 排除），但**现有契约类一个都没用**。

- 契约类（`record`）：**不用 Lombok**
- 未来的实体类：主 PRD 规定用普通类 + `@Getter` / `@Setter`
- 日志字段：用 `@Slf4j` 生成 `log`，当前唯一在用的注解（见 [logging-guidelines.md](./logging-guidelines.md)）
- `@Data` 慎用——它会生成 `equals`/`hashCode`/`toString`，对可变实体往往不是想要的语义

## 六、Java 21 语言特性

`pom.xml` 设 `java.version=21`，可以用现代 API：

- `getFirst()` / `getLast()`（SequencedCollection）—— 已在 `DashScopeAsrProvider` 中使用，优于 `get(0)`
- `record`、`sealed`、模式匹配、文本块
- **虚拟线程已全局开启**：`application.yml` 里 `spring.threads.virtual.enabled: true`。写阻塞式 IO 时不需要自己搞线程池。

## 七、测试

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

- **重装桩用 `doAnswer(...).when(mock).method(...)`，不用 `when(mock.method())`**：后者会**先执行已装的桩**，
  于是「桩里带副作用」时（往事件表记一笔、改计数器、抛异常）会在装桩阶段凭空多出一次调用记录。

  ```java
  // 好：装桩本身不触发任何副作用
  Mockito.doAnswer(invocation -> {
      events.add("write:updateReady");
      throw new IllegalStateException("落库失败");
  }).when(repository).updateReady(any());

  // 坏：when(...) 里这次调用会跑上一版 answer，事件表里多一条不属于本次的 "write:updateReady"
  when(repository.updateReady(any())).thenAnswer(invocation -> {
      events.add("write:updateReady");
      throw new IllegalStateException("落库失败");
  });
  ```

  先例：`SubtitleArtifactStoreTest#recordRepositoryWrites`（事务包裹用例靠「仓储写也记进同一张事件表」才钉得住顺序）。

### 会写文件的测试与 `@TempDir`

Windows 上 `@TempDir` 的清理**偶发失败**：`JUnitException: Failed to delete temp directory`。已在 `FfmpegAudioExtractorTest` 与 `LocalFileMediaAcquirerTest` **两次独立复现**，都是**真写文件**的用例；不写文件的用例未观察到。成因是环境性句柄占用，**不是实现缺陷**——别为它去改实现。

- 凡**会写文件**又用 `@TempDir` 的测试类，用 `@TempDir(cleanup = CleanupMode.NEVER)`（import `org.junit.jupiter.api.io.CleanupMode`），别让环境问题把测试判红
- **不要**以「顺手统一」为由修改范围外、且无 flake 证据的既有测试类——那会给审查凭空增加噪音

参照：`infrastructure/provider/audio/ffmpeg/FfmpegAudioExtractorTest.java`

### 测试分档：能不起容器就不起，按需升级

纯逻辑一律**直接 `new` 出被测对象、构造函数手动注入依赖**，不起 Spring 容器。

`DashScopeAsrProviderTest` 就是范例，它没有 `@SpringBootTest`：

```java
var provider = new DashScopeAsrProvider(
        RestClient.builder(), new DashScopeProperties("", MODEL));
```

这要求被测类**构造函数可注入依赖**（本项目 `DashScopeAsrProvider` 正是构造函数注入）。写新 Provider 时保持这个可测性。

**判据：这段逻辑用不用得上容器的能力？**（DI / AOP / 事务 / 自动配置 / MVC 绑定校验 / Jackson 装配）

用不上 → 纯单元测试。用得上 → 按下面逐级升，**能停在哪级就停在哪级**：

| 档位 | 判据 | 手段 | 本项目实例 |
|---|---|---|---|
| 纯单元测试 | 逻辑不依赖容器能力 | `new` + 构造注入 | `DashScopeAsrProviderTest` 等 21 个 |
| 切片测试 | 只缺某一层的框架能力 | `@WebMvcTest` / `@DataJpaTest` / `@JsonTest` | `MediaControllerTest`、`TaskControllerTest` |
| 全容器 | 验证跨层协作、自动配置、真实外部依赖 | `@SpringBootTest` + Testcontainers | `VidSageApplicationTests`（冒烟） |

**切片测试会起「部分容器」，这是设计如此，不违反本节。** 它管的不是「不许起容器」，而是「只缺某一层的框架能力时，别去起完整容器」。所以把 `@WebMvcTest` 叫「单元测试」或「集成测试」都不准确——**直接叫「切片测试」**。

`@SpringBootTest` **只留给冒烟与跨层集成**，不作为默认。

> **依据（2026-09-27 核实）**：Spring Boot Reference「You can instantiate objects by using the `new` operator without even involving Spring」；Framework 的 Unit Testing 章把不起容器的称作 *true* unit tests。Spring 官方示例项目 `spring-petclinic` 的实际分层与上表一致——它的 `CrashControllerTests` 连 Controller 都直接 `new`，源码注释里自称 "plain UNIT test"。
>
> 注意官方**从未把这条写成禁令**，用的是 "can" / "consider" 这类措辞。它是**默认起点 + 升级判据**，不是红线。

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

参照：`backend/src/test/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeAsrProviderTest.java`

### 测试数据

测试用的嵌套 record 直接内联构造，不建 fixture 文件：

```java
var file = new DashScopeAsrProvider.TranscriptionFile(
        "https://example.com/audio.mp3",
        new DashScopeAsrProvider.TranscriptionFile.AudioInfo("mp3", 16000),
        List.of(...));
```

## 八、依赖管理

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

## 九、反模式清单

| 不要做 | 依据 |
|---|---|
| 手写 HTTP 轮询 / JSON 解析 / 配置读取 | 「不重复造轮子」要求 |
| 契约类用 `@Data` 或改成普通 class | 现有契约全为 record |
| 私有方法不写注释 | 「函数方法要写注释」要求 |
| 纯逻辑测试起 `@SpringBootTest` | 纯逻辑 `new` 即可；`@SpringBootTest` 留给冒烟与跨层集成 |
| 测试方法命名为 `testXxx` | 现有命名为行为描述式 |
| 用 `when(mock.method())` 重装带副作用的桩 | 装桩阶段会跑上一版 answer，凭空多出一次调用记录 |
| Provider 构造函数里 `new` 出依赖 | 必须构造函数注入，否则不可测 |
| 引入依赖时不排除冲突的日志实现 | pom 已有先例注释 |
| 用已停更的旧方案实现新功能 | 「实现方式选当前主流」要求 |

## 十、并发与时序

### 订阅类路径：先登记连接，再读状态快照

**规则**：凡是「把连接登记进表 + 推一份状态快照」的订阅路径（SSE 等），
**读状态必须发生在登记之后**，并且要处理「登记完发现已经是终态」这一支。

**为什么**：反过来写（先读状态 → 判断还能订阅 → 再登记）有一个毫秒级窗口 ——
读到「进行中」之后、登记之前，产生方恰好收口；它的终态事件在登记表里**找不到任何人**（登记还没发生），
于是这条连接**永远收不到终态**，只能等空闲超时自愈。客户端看到的是「一直进行中」，刷新才好。

**写法**：
```java
// ✅ 先登记，再读状态：读到的就是登记之后的事实
emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);
emitter.onCompletion(() -> remove(key, emitter));
TaskRunProgress snapshot = readSnapshot();   // 登记之后才读
sendTo(key, emitter, snapshot);              // 已终态的快照会让 sendTo 顺手关掉这条连接
```

**与「已终态直接回 204、不开流」不冲突**：那是**还没登记**的连接走的另一条路；
本规则管的是**已经登记、已经以 200 建了流**的连接。



