# 包结构与命名

## 经典四层：domain / application / infrastructure，加 web / config / shared

本项目后端采用 **DDD 经典四层**——`domain/`（领域层）、`application/`（应用服务层）、`infrastructure/`（基础设施层）——外加 HTTP 入站层 `web/`、装配层 `config/` 与共享工具层 `shared/`。一级包按「层」划分，同类构件集中在同一层内；二级包按「限界上下文」或「构件类型」划分，功能语义体现在上下文名与类名上。

**不要**按功能建顶层包（如 `asr/`）：一个功能会同时拥有领域词汇、用例编排与出站实现，切成顶层包只会让同一层的构件各自散落。

结构示意（`backend/src/main/java/com/vidsage/`）：

```
com.vidsage
├── VidSageApplication.java        # 唯一的启动类，放在根包
├── domain/                        # 领域层：领域词汇、状态机与业务规则、端口接口
│   ├── task/                      # 任务上下文
│   ├── media/                     # 媒体获取与音频上下文
│   ├── transcription/             # 转写上下文
│   ├── subtitle/                  # 字幕上下文
│   └── shared/                    # 共享内核：跨上下文领域词汇
├── application/                   # 应用服务层：用例编排、读模型、应用消息与异常
│   ├── task/ media/ transcription/ subtitle/ diagnostics/
├── infrastructure/                # 基础设施层：出站适配
│   ├── persistence/               # entity / mapper / repository（MyBatis-Plus 三件套）
│   ├── process/                   # 外部进程执行能力
│   └── provider/                  # asr / audio / download / llm 各端口适配器
│       └── <能力域>/<实现短名>/    # 如 asr/dashscope/：实现类 + 该实现专用的 *Properties
├── web/                           # HTTP 入站适配层
│   ├── controller/                # 全部 HTTP 入口平铺于此（能一眼看全所有 HTTP 入口）
│   ├── dto/                       # 需展平的请求/响应契约（见「契约类的位置」）
│   └── sse/                       # SSE 连接管理
├── config/                        # 跨领域配置与装配
└── shared/                        # 共享工具：fs / progress / subtitle / text
```

> **这是示意，不是文件清单。** 树只列到「层 → 上下文/构件 → 实现」这一级，不逐类登记；**新增类不需要回头改这份文档**。`<...>` 是占位符，`asr/`、`dashscope/` 是当前的示例值。判断一个新东西该放哪，看下面的[分层归属判据](#分层归属判据)、[依赖规则与豁免清单](#依赖规则与豁免清单)与[契约类的位置](#契约类的位置)。

> **历史注（2026-10-05 DDD 四层化前）**：本规范上一版的结构是顶层 `model/`（纯数据契约）、顶层 `service/`（全部应用服务平铺）、顶层 `provider/`（端口接口与实现同居）、顶层 `subscribe/`（中立订阅机制）、`persistence/` 与顶层 `utils/`。四层化后：`model/` 并入 `domain/`，`service/` 按上下文归入 `application/`，`subscribe/` 归入 `application/task/`，`utils/` 改名 `shared/`，`provider/` 拆为「端口接口进 `domain/`、适配器进 `infrastructure/provider/`」，`persistence/` 挂到 `infrastructure/` 之下。涉及包名的旧条目在下文一律以时间线语境出现。

测试包与主包**镜像对应**（`backend/src/test/java/com/vidsage/`），规则见下方[测试包结构](#测试包结构)。

## 分层归属判据

每个层（包）「允许出现什么、禁止出现什么」：

| 包 | 职责 | 允许出现 | 禁止出现 |
|---|---|---|---|
| `domain/<上下文>/` | 领域层：业务概念、规则、端口接口的家 | 领域词汇（`record` / `enum` / `sealed` 接口）、**状态机与业务规则**、**端口接口**、**值对象**、紧凑构造器承载的字段不变量、**直接充当对外契约时的 `@Schema`**（后两项见下方两处例外） | 框架注解（**会改变行为的**，Jackson 绑定注解除外）、SDK 类型、HTTP 类型、用例编排 |
| `application/<上下文>/` | 应用服务层：用例编排 | `@Service` 编排类、**读模型**、**应用消息**、**应用异常**、**中立订阅机制** | 实现类、SDK 类型、HTTP 类型 |
| `infrastructure/persistence/` | 出站存储适配 | MyBatis-Plus 三件套：`XxxEntity` / `XxxMapper` / `XxxRepository` | 业务编排、MP 类型外泄 |
| `infrastructure/process/` | 调外部程序的**通用机制** | 进程执行器、外部工具路径解析与诊断等**不面向业务契约**的机制类 | 面向业务契约的 Provider 四件套（那属于 `infrastructure/provider/`）、业务编排 |
| `infrastructure/provider/` | 出站实现（外部 API / 模型服务 / 端口适配器） | 端口适配器 + LLM worker 具体类 + 该实现专用的 `*Properties` | SDK 类型外泄到公开签名 |
| `web/controller/` | HTTP 入站适配 | `@RestController`、局部 `@ExceptionHandler` | 业务逻辑、直接依赖 `infrastructure/`（唯一例外见[豁免清单](#依赖规则与豁免清单) b）、**请求/响应契约的定义**（那是 `web/dto/` 的活） |
| `web/dto/` | 需**展平**的 HTTP 请求/响应契约 | 顶层 `record`、`@Schema`、纯映射的静态工厂（如 `from(...)`） | 业务逻辑（校验之外的编排）、`@RestController`、直接依赖 `infrastructure/` |
| `web/sse/` | SSE 连接管理 | emitter 持有 / 心跳 / 清理等 web 层构件 | 业务编排 |
| `config/` | 跨领域配置与装配 | `@Configuration`、`@ConfigurationProperties`、跨领域 Bean | 某一实现专用的配置（那属于 `infrastructure/provider/<能力域>/<实现短名>/`） |
| `shared/<领域>/` | 跨领域的纯算法与本地文件系统助手 | 纯静态方法的工具类（私有构造器 + 全静态） | 网络 / 外部进程 / Spring 注解与容器类型 / HTTP 类型 / 业务编排（准入判据见[下文](#shared共享工具层)） |

各层「装什么」的细则：

**`domain/`（五个上下文 + 共享内核）**：

- 领域词汇：`record` / `enum` / `sealed` 接口等中立契约（如 `TranscriptionCommand` / `TranscriptionResult`、`MediaInput`、`CleanedSubtitle`）。
- 状态机与业务规则：出现「随调用改变状态、并拒绝非法变化」的对象才进 `domain/`——如 `domain/task/VideoTask`（`PENDING → RUNNING → SUCCEEDED/FAILED`，非法迁移抛 `IllegalStateException`）。只有字段约束的数据（`record` + 紧凑构造器）同样是 domain 居民，但它们是**领域词汇**，不是状态机；两类并存，不互相冒充。（历史注：2026-09-30 MVP-1 时 `domain/` 曾与顶层 `model/` 并存——「有状态机的进 domain、纯数据的留 model」；2026-10-05 四层化后 `model/` 整体并入 `domain/`，判据收敛为「领域的东西都进 domain」。）
- **端口接口**（六边形原则：端口属于六边形内部，适配器在 infrastructure）：`ASRProvider` → `domain/transcription/`；`MediaAcquirer` / `AudioExtractor` → `domain/media/`；`TermLookupProvider` → `domain/subtitle/`。四者均无框架注解、纯契约，与领域层 purity 一致。
- **值对象**：单一上下文使用的进该上下文（`TaskNo` → `domain/task/`）；**跨上下文共用的词汇进共享内核 `domain/shared/`**——`WorkspaceKey`（`VideoTask`（task）持有、`TaskService`（application/task）计算、`MediaInput`（media）携带、`VideoTaskRepository` 按它查询，放共享内核避免 `domain/task → domain/media` 依赖）与进度词汇 `StageEvent` / `ProgressListener`（task / transcription / subtitle 三处共用）。**第二批值对象已按同判据落地（2026-10-06 业务逻辑下沉 Phase A）**：`SourceUri`（输入身份规范化串：URL 原文 / 本地路径按 path|size|mtime 定身份）与 `WorkspaceDir`（目录名公式 `sanitize(标签)-key` + Windows 保留名安全化），同为 task 与 media 两上下文共用，随行的 `sanitizeName` / 保留名规则一并入域。
- **纯函数领域政策**（2026-10-06 业务逻辑下沉 Phase B/C 落地）：无状态、零 I/O 的规则判据收进 domain——应用层持事实（量字节、读文件、查库），域做裁决；政策对象是**值对象 / 纯函数领域服务，不是技术端口**（quality-guidelines「不预先抽接口」不受影响）。现行归属：`domain/subtitle/Bcp47Language`（语种政策）、`domain/subtitle/TermVerification`（术语查证触发与记账）、`domain/subtitle/CheckpointPolicy`（检查点语义 + 上下文缓存-or-摘要）；`domain/task/TaskArtifactScan`（产物可见性白名单）；`domain/media/AudioArtifact.fileName`（音频规格指纹命名）、`domain/media/MediaInput.ofTaskInput`（TaskInputType → 变体重建工厂 + `source.mp4` 落盘名）；`domain/task/VideoTask` 上的查询谓词 `isEventSettled`（终态判定，web 收口引用）与 `ACTIVE_STATUSES`（active 判据，repository 查重引用）。

**`application/`（五个上下文）**——用例编排的家，四类一等构件：

- **用例编排**：`@Service` 服务（`TaskService`、`TaskPipelineService`、`MediaAcquisitionService`、`TranscriptionService`、`SubtitleService` 等），组装契约、委托端口，不出现适配器实现类与 SDK 类型。
- **读模型**：`TaskSnapshot` / `TaskArtifact` / `TaskArtifactFile`——面向查询的应用层投影，**零 web 注解**（2026-10-06 读模型拆分：对外 @Schema 集中到 `web/dto/TaskResponse`，见[契约类的位置](#契约类的位置)判据 2）。
- **应用消息 / 应用异常**：`TaskProgressMessage`（应用 → web 的进度消息）、`TaskConflictException` / `TaskNotFoundException`。
- **中立订阅机制**：`TaskSubscriptionRegistry` / `Subscription`——application 层管线广播事件、web 层挂「写 emitter」回调，两边都依赖它。判据是「写得出它不许装什么」：**禁 HTTP 类型**（`SseEmitter` 只活 `web/sse/`，此 Javadoc 承诺原样保持）、**禁业务编排**；与 `shared/` 同类「机制包」先例。（历史注：该机制 2026-09-30 曾定调为顶层 `subscribe/` 包，2026-10-05 四层化归入 `application/task/`；升级条件随之带入——出现多传输协议（SSE + WebSocket）并存或包内构件 ≥3 时再议拆分。）

上述四类构件在 `application/task/` 内按角色子包落位（2026-10-05）：服务门面与 `TaskProgressMessage` 留根，读模型进 `query/`、应用异常进 `exception/`、中立订阅机制进 `subscribe/`（禁 HTTP 类型的承诺随迁入其包说明）；判据见下文[二级包怎么分](#二级包怎么分)。

**`infrastructure/`（persistence / process / provider）**：

- `persistence/`：MyBatis-Plus 三件套（见[持久化三层](#持久化三层entity--mapper--repository)）。
- `process/`：外部进程能力——`ExternalProcessRunner`、`ProcessOutcome`、`ExternalToolReport`、`ExternalToolDiagnostics`、`ExternalToolProperties`。**这是「单一实现的基础设施」形态，不是 Provider 四件套**：该机制只有一种实现方式（在本机调用外部程序），不存在「同一能力、多种来源、配置二选一」的前提，因此**不抽接口**——与 [quality-guidelines.md](./quality-guidelines.md) 反模式清单同一条精神。测试的可替换点应落在更小的地方（如外部命令的测试替身），而不是为单一实现预先造接口。（历史注：2026-10-05 前它曾以顶层 `provider/tool/` + `model/process/` 两处存在，`ProcessOutcome` / `ExternalToolReport` 本就是 `ExternalProcessRunner` 的出参契约，合并后归此。）详见 [provider-pattern.md](./provider-pattern.md) 的「同能力多实现共存」一节。
- `provider/`：各端口适配器（`DashScopeAsrProvider`、`FfmpegAudioExtractor`、`LocalFileMediaAcquirer`、`YtDlpMediaAcquirer`、`DashScopeTermLookupProvider`）+ LLM worker 具体类（`SubtitleCleanser` / `SubtitleTranslator` / `SubtitleContextSummarizer` / `MessageHistory`，**不抽端口**）+ 该实现专用的 `*Properties`。

**`web/` 与 `config/` 职责不变**：`web/controller/` 收发 HTTP、`web/dto/` 装需展平的契约、`web/sse/` 管连接；`config/` 装跨领域 `@Configuration` / `@ConfigurationProperties`。

**`shared/`（原 utils/，2026-10-05 改名）准入判据**：纯静态工具、无状态、不发起网络 / 外部进程；可引用 spring-core 工具类（`org.springframework.util.*`）与 Jackson / slf4j，**去掉容器仍可编译**；依赖上只认 **JDK + Jackson + slf4j + domain 词汇**；不含业务编排。完整细则见[下文](#shared共享工具层)。

> **`domain/` 契约的两处例外**（禁止清单收窄的两处，判据如下）：
> 1. **`@Schema`**：禁止清单的立法意图是「防止外部框架语义渗进中立契约」，它列举的「行为方法、SDK 类型」都是**有实质影响**的东西。`@Schema` 跳过整个运行时——**删光它接口行为一个字节不变**。所以原措辞收窄为「框架注解（**会改变行为的**，Jackson 绑定注解除外）」；`@Schema` 这类**零运行时影响的纯文档元数据**，仅当该类型**直接充当对外契约**时允许（判据见[契约类的位置](#契约类的位置)）。
> 2. **紧凑构造器承载的字段不变量**：它**不属「行为方法」**——紧凑构造器不是实例方法，且实例出生后**仍无任何行为方法**：不变量在出生时验过，此后该类型只是一袋数据。因此契约级字段约束可以放进 `domain/`（`AudioSpec` 即如此），不必在各服务与各实现里各写一遍校验。

## 依赖规则与豁免清单

> **本文件是依赖规则与豁免清单的正典落点**，其余 spec 文件引用本节、不复述全文。

**正向依赖**：`web → application → domain`；`infrastructure → domain`（实现端口、依赖领域词汇）。`shared/` 是工具层：application / infrastructure / web 可用，`shared → domain`（借用领域词汇）允许，**`domain → shared` 禁止**（领域不依赖工具，也不制造环）。application 不依赖 web（HTTP 类型只在 `web/`）。

**豁免清单**（明确写进 Phase 2 的 package-info 与本文件，不做接口脱壳）：

- a. `application → infrastructure` 具体类：`VideoTaskRepository`（保持具体类，沿用 quality-guidelines「不预先抽接口」）；application/diagnostics 的 `ToolDiagnosticsService` → `ExternalToolDiagnostics` / `ExternalToolReport`（诊断编排；2026-10-05 执行期按实际 import 修订——`ExternalProcessRunner` 实为 infrastructure 内部能力，跨层经由其封装类发生）；`infrastructure/provider/llm/` 四个 worker 具体类（`SubtitleCleanser`、`SubtitleTranslator`、`SubtitleContextSummarizer`、`MessageHistory`）。
- b. `web → infrastructure`：`MediaController` 直接读 `ExternalToolReport`（诊断端点）。

**理由**：quality-guidelines 明确反对「为以后可能用到」的接口增殖；用户诉求是**结构 DDD 化**（消除 model/domain 错位、领域概念有家），不是依赖倒置洁癖。正统化留作未来升级路径，**触发条件：出现第二个存储实现或本地模型部署**。

## 二级包怎么分

三条轴各管各的，**不要互相推导**：

- **`domain/`、`application/` 按限界上下文分**：`task/`、`media/`、`transcription/`、`subtitle/`（application 另有 `diagnostics/`）。上下文是领域概念的家；跨上下文词汇进 `domain/shared/` 共享内核。上下文包内的**角色子包随 mass 出现**（`query/`、`exception/`、`subscribe/`…）：1–3 类的小上下文平铺即可，类多且角色分明再分子包；现行实例是 `application/task/`（根留服务门面与 `TaskProgressMessage`，`query/` 装读模型、`exception/` 装应用异常、`subscribe/` 装中立订阅机制）。
- **`web/` 按角色（构件类型）分**（现有三个：`controller/`、`dto/`、`sse/`，第三个 2026-09-30 随 MVP-1 加入）。`TaskController`（入站适配构件）与 `SubmitUrlRequest`（HTTP 契约构件）是**两种构件**，不是一个领域的两个层次——把它们堆进同一包才会混杂。这与上下文轴是**两条独立判据**。
- **`infrastructure/` 按构件类型 + 能力域分**：`persistence/` 内按构件类型分三子包（见[持久化三层](#持久化三层entity--mapper--repository)）；`provider/` 按能力域 → 实现短名两级分（`asr/dashscope/`、`download/ytdlp/`）——契约类名与实现配置类名（`TranscriptionCommand` / `DashScopeProperties`）**不带机制前缀**，平铺后必然混杂。

> **本规范曾以「再套一层二级目录是冗余层级」为由否掉过这类结构，此处有意推翻。** 那条判据对「**同一构件**的再分组」（按上下文把 Controller 再切一层）成立；对「**不同构件**的分离」不成立。区分点是包内是否会混放异质构件，而非目录深度本身。

**新增上下文时**：`domain/<新上下文>/` + `application/<新上下文>/` 各开一包；新端口接口先进 `domain/<上下文>/`，适配器与该实现专用的 `*Properties` 进 `infrastructure/provider/<能力域>/<实现短名>/`（能力域已存在的直接落子包），见 [provider-pattern.md](./provider-pattern.md) 的四件套。

> **`infrastructure/provider/llm/` 的现阶段形态（2026-09-28 定，2026-10-05 随四层化迁至现址）**：包里目前是四个**具体类**的 LLM 调用 worker——`SubtitleCleanser`（字幕纠错）、`SubtitleTranslator`（翻译）、`SubtitleContextSummarizer`（摘要）与多轮历史 `MessageHistory`，**没有抽接口**：它们不是「同一能力、多种来源」的缝，而是吃 LLM 的领域 worker（与 `infrastructure/process/` 单一实现不抽接口同一条精神；多轮历史也装不进窄缝）。SDK 类型（`ChatClient` / `Message` 等）只出现在这些类的内部，公开签名只有本项目的契约类型。**`LLMProvider` 接口不建**（2026-09-28 定）：供应商切换由 Spring AI 承担（本地 Ollama 与云端 DeepSeek 均 OpenAI 兼容），翻案条件见 [provider-pattern.md](./provider-pattern.md)——本包就是终态形态。曾经的临时顶层包 `com.vidsage.llm` 就此消除。

## 持久化三层：Entity / Mapper / Repository

> **已落地（2026-09-30，MVP-1）**：布局为**三个子包** `entity/`、`mapper/`、`repository/`——2026-09-30 项目所有者定调按「构件类型」分子包、类不平铺在持久化包根；当前单表不分领域二级包，表多时再议。2026-10-05 起整体挂到 `infrastructure/persistence/` 之下。依赖与数据源契约见 [database-guidelines.md](./database-guidelines.md)。

| 层 | 类名 | 职责 | 谁看得见 |
|---|---|---|---|
| 表映射 | `XxxEntity` | 数据库行结构，`@TableName`，普通类 + `@Getter`/`@Setter` | 仅 `persistence/` 内部 |
| 数据访问 | `XxxMapper` | `interface extends BaseMapper<XxxEntity>`，拼查询条件 | **仅 `Repository`**；application 层不可见 |
| 对上契约 | `XxxRepository` | 具体类（`@Repository`），包装 Mapper，**实体 ↔ 领域对象转换**，暴露领域语义方法 | application 层唯一可见的持久化入口 |

- **共存，不是二选一**：三层同时存在，职责不同。`Repository` 要转换，就必须包着 `Mapper`。
- **受限的是可见性，不是存在性**：`Mapper` 必须存在（`Repository` 要用），但**不许出现在 application 层**。规则是「application 层不许直接依赖 `Mapper`」，不是「不许同时存在 `Mapper` 和 `Repository`」。
- **不预先抽接口**：`XxxRepository` 直接是**具体类**，不写 `interface` + `Impl`。单一存储实现下抽接口属于「为以后可能用到」的过度设计（与 [quality-guidelines.md](./quality-guidelines.md) 反模式清单同一条精神）——这正是[豁免清单](#依赖规则与豁免清单) a 允许 `application → VideoTaskRepository` 的原因。
- **`@Repository` 只是跟随社区惯例**：不要写成「白拿异常转换收益」—— `PersistenceExceptionTranslationPostProcessor` 的异常转换主要针对 JPA/Hibernate 原生异常，**MyBatis-Plus 下是否触发未经核实**，别把它当成立即收益。
- `mapper` / `repository` **不做顶层包名**——它们以子包形式存在于 `infrastructure/persistence/` 之下（2026-09-30 项目所有者定调）；类名仍按本表（`XxxMapper`、`XxxRepository`）。

### 反模式

| 不要做 | 原因 |
|---|---|
| application 层直接注入 `XxxMapper` | 破可见性边界；Mapper 是 persistence 的内部细节 |
| MP 类型（`BaseMapper`、`LambdaQueryWrapper`、`IPage`）出现在 application 层方法签名里 | 破 [provider-pattern.md](./provider-pattern.md)「第三方 SDK 类型只在实现类内出现」的同一条精神 |
| 继承 MP 的 `IService` / `ServiceImpl` / `CrudRepository` | 见下 |
| `XxxRepository` 里写业务规则 | 它只做存取与转换；业务编排在 application 层 |

### 不用 MyBatis-Plus 的 service 层抽象

MP 的持久化抽象是三档叠加，**我们只用第一档**：

| 档 | 组件 | 用不用 |
|---|---|---|
| 数据访问 | `BaseMapper<T>` | **用** |
| 泛型 CRUD 外壳 | `IService<T>` / `ServiceImpl` / `CrudRepository` | **不用** |

后一档是给「不想手写 Service 的 CRUD 项目」的糖。本项目 application 层装的是用例编排（端口装配、契约组装、管线调度），继承那层抽象会把数据访问词汇搬进 application 层。

> **颗粒度边界**：本文件只定义「三层职责 + 可见性」。**具体用法（`Wrapper` 写法、分页、事务）不在此文档**，由 [database-guidelines.md](./database-guidelines.md) 定义 —— 遵守 [index.md](./index.md)「不要在功能落地前凭空补这些规范」。

## 目录随代码出现，不预建空目录

**硬规则：不得创建当前无代码的目录。** 需要时由搬入的第一个类带出目录。

理由：空目录不表达任何约束，只制造虚假的结构信号。本规范的上上一版曾规定 package-by-feature，而磁盘上长期存在空的 `controller/`、`service/`、`config/` 目录，让人误以为技术分层已经落地 —— 这正是当年结构修正的起因之一。（历史注：其中的 `controller/` 已于 2026-09-26 改为 `web/controller/`；此处保留旧名是为了准确记录当时磁盘上的东西。2026-10-05 DDD 四层化同理：代码搬完，spec 与 package-info 才跟着落。）

> **`web/` 的子包不会重蹈 package-by-feature 的覆辙。** `web/controller/` + `web/dto/` 是**按角色**（构件类型）分：一个包装「入站适配构件」，另一个装「HTTP 契约构件」。package-by-feature 是按**功能领域**切（`asr/`、`translate/`），切出来每个包都同时装着 controller、service、model —— 包本身不再表达任何职责边界，这才是它被推翻的原因。两者不是同类东西。`domain/`、`application/` 的上下文二级包**也不是** package-by-feature：每个上下文包只装**同一层**的构件（domain 包里只有领域词汇，application 包里只有编排与读模型），层职责边界依然清晰。

推论：`config/` **已建**，首个类是 `WorkspaceProperties`。**建目录的触发条件不止 `@Configuration`**——`@ConfigurationProperties`（跨领域配置属性）同样算，别把这条判据读窄了。

## 启动类

- 只有一个：`com.vidsage.VidSageApplication`
- 固定注解组合：`@SpringBootApplication` + `@ConfigurationPropertiesScan`
- 未指定 `basePackages`，从 `com.vidsage` 起全量扫描；**搬包/加包都不需要改启动类**

`@ConfigurationPropertiesScan` 让 `@ConfigurationProperties` 类无需逐个 `@EnableConfigurationProperties` 就能被扫描到。新增配置属性类时**不需要**改启动类。

参照：`backend/src/main/java/com/vidsage/VidSageApplication.java`

## 命名约定

| 类型 | 约定 | 例子 |
|---|---|---|
| 端口接口 | 用业务名 + 角色后缀，**不加 `I` 前缀** | `ASRProvider` |
| 适配器实现类 | 实现方特征前缀 + 接口名 | `DashScopeAsrProvider`（未来：`LocalFunAsrProvider`） |
| 配置属性 | 实现方名 + `Properties` | `DashScopeProperties` |
| 契约 | 动作名 + `Command` / `Result` | `TranscriptionCommand` / `TranscriptionResult` |
| 契约子结构 | 语义名，不叫 `DTO` / `VO` | `TranscriptionSegment` |
| Spring 组件 | **按包选 stereotype**：`web/controller/`→`@RestController`、`application/` 服务→`@Service`（订阅注册表等机制类→`@Component`）、`infrastructure/`→`@Component`、`infrastructure/persistence/`→`@Repository`、`web/sse/`→`@Component` | `TaskController`、`TaskService`、`DashScopeAsrProvider`、`VideoTaskRepository` |
| Controller | 业务名 + `Controller`，平铺在 `web/controller/` | `MediaController`、`TaskController` |

> `ASRProvider` 是全大写缩写开头，与 Spring 常见的 `AsrProvider` 写法不同。**跟随现有代码**，不要"顺手改正"。

> **按包选 stereotype 的理由**：跟随 Spring 社区惯例，让类一眼可辨层次。注意 `@Component` 与 `@Service` 在 Spring 中**行为完全等价**（`@Service` 只是 `@Component` 的 `@AliasFor` 特化、无额外行为），这是可读性选择而非功能差异。
>
> **写切面时用包定位**（`within(com.vidsage.application..*)`），不要用注解定位（`@within(org.springframework.stereotype.Service)`）—— 后者在有人漏标注解时会**静默失效且不报错**。

> **包名可叫 `dto/`，类名不可叫 `XxxDTO`——两者尺度不同，是分工而非双重标准。** 包名用**分类词**（`dto/`）是社区惯例，标识桶里装的是哪类东西；类名用**「动作名 + 角色后缀」**（`Request` / `Response`），因为 `DTO` 是分类词、**不承载方向与操作语义**。
>
> 故 `SubmitUrlRequest` = 动作名（SubmitUrl）+ 角色后缀（Request），`TaskConflictResponse` = 语义名 + 角色后缀（Response），完全符合上表「契约」行的既有约定；`DTO` 从来不在本项目的角色词表里（上表「契约子结构」行明确排除）。
>
> **已知破例（诚实记录）**：本项目角色包的类名基本都带同名后缀——`web/controller/`→`*Controller`、`application/`→`*Service`、领域端口与适配器→`*Provider`；**`web/dto/` 会是唯一不同字的一个**。但 `web/controller/ApiExceptionHandler` 已是一例（它并非 Controller），**故该模式是倾向而非铁律**。破例不必回避，只需写明。

## 契约类的位置

**判据 1（请求与响应共用）：该类型的 JSON 形状是否就是要对外暴露的形状？**

- **是** → 直接充当 HTTP 契约，类型留在它所属层的家（`domain/<上下文>/`、`application/<上下文>/`、`infrastructure/process/` 皆可能），字段补 `@Schema`（[两处例外](#分层归属判据)之一）
- **否**（含需**展平**、需组合的 HTTP 专属形状）→ 在 `web/dto/` 自持顶层 `record`，从各层类型映射

**判据 2（对外响应类的命名与落位，2026-10-06 读模型拆分定，对未来新增端点生效）**：对外响应类命名 `XXResponse`、落 `web/dto/`、`@Schema` 集中于此；由应用层读模型组装的响应，读模型本体**零 web 注解**、落 `<上下文>/query/`，web 侧以静态工厂 `from(读模型)` 纯映射、Controller 调用（`TaskResponse.from(TaskSnapshot)` 为首例）。**响应内嵌的清单元素类型同理**（`TaskArtifactResponse.from(TaskArtifact)`）。请求类命名沿用 `XXRequest`（`SubmitUrlRequest` 已符合）。判据 1 的「直接对外」分支对既有落位类型（`ExternalToolReport` / `TranscriptionResult` / `TaskProgressMessage`）维持原状，不据此新造。

**现有代码已按此判据运行，本节只是把既有事实写成规则**：

| 类型 | 位置 | 为什么 |
|---|---|---|
| `ExternalToolReport` | `infrastructure/process/` | 字段全是 `String` / `boolean`，形状可直接对外 → `MediaController` 诊断端点直接返回（依赖规则[豁免清单](#依赖规则与豁免清单) b） |
| `TranscriptionResult` | `domain/transcription/` | 字段为 `List<TranscriptionSegment>` + `String`，形状可直接对外 → 直接充当对外契约，补 `@Schema` |
| `TaskSnapshot` / `TaskArtifact` | `application/task/query/` | 任务查询的应用层读模型（查询期产物扫描随行）→ **零 web 注解**（判据 2）；对外 @Schema 在 `web/dto/TaskResponse` 与元素级 `web/dto/TaskArtifactResponse`。流式下载出参 `TaskArtifactFile`（同在 `query/`）字段走响应头不进 JSON，**不补 @Schema** |
| `TaskResponse` / `TaskArtifactResponse` | `web/dto/` | 任务提交 / 查询端点的对外响应（判据 2）：@Schema 齐全（自原 `TaskView` / `TaskArtifact` 逐条迁入，注解参数未改写），静态工厂 `from(读模型)` 纯映射，JSON 字段名 / 类型与拆分前逐一一致 |
| `TaskProgressMessage` | `application/task/` | SSE 端点的直接出参（应用消息，非读模型）→ 补 `@Schema`（判据 1 既有落位维持） |
| `SubmitUrlRequest` | `web/dto/` | 入参契约，需展开成 `MediaInput`（domain/media）等领域词汇才能进应用层 |
| `TaskConflictResponse` | `web/dto/` | HTTP 专属组合形状（错误说明 + 既有任务视图），由 `ApiExceptionHandler` 从 application 异常映射 |

数据契约（`Command` / `Result` / 读模型 / 子结构）放 `domain/<上下文>/` 或 `application/<上下文>/`；端口接口（`XxxProvider`）在 `domain/<上下文>/`，适配器实现与该实现专用的 `*Properties` 进 `infrastructure/provider/<能力域>/<实现短名>/`。

**顶层包：`dto/`、`vo/` 这类「形状分类」包不做顶层；`shared/` 这类「内容 / 机制」包可以做顶层。**

- **`dto/`、`vo/`、`entity/` 这类形状分类包不做顶层**：它们只按「是什么形状」分类，任何一层的任何东西都能声称自己属于它，而且把它们全顶置会让顶层包数量失控（本项目顶层控制在构件层 `domain` / `application` / `infrastructure` / `web` / `config` / `shared`）。需要时作为某构件的子包存在（如 `web/dto/`、`infrastructure/persistence/entity/`）。
- **`shared/` 可以做顶层**：它装的是跨领域的内容 / 机制，不是形状空壳，有责可辨，顶置不失控（按领域子包收敛：`shared/<领域>/`）。

> **为何 `dto/` 作 `web/` 的子包可以、作顶层包不可以**：`web/dto/` 有明确且**唯一的**职责边界（「需展平的 HTTP 请求/响应契约」），填得满[分层归属判据](#分层归属判据)的职责表，故不是桶式包；而顶层的 `dto/` 没有归属语境，写不出「这个包不许装什么」。**判据是「能否写出它不许装什么」，不是「包名是不是分类词」。** 构件层（有职责边界、填得满职责表）不受本条限制；`config/` 是另一例，见上。

参照：`domain/transcription/`（端口与契约同居上下文包）、`infrastructure/provider/asr/dashscope/`（适配器 + 配置）、`application/task/TaskService.java`、`web/controller/TaskController.java`、`web/dto/`。

## shared/：共享工具层

**准入判据**：纯静态工具、无状态、不发起网络 / 外部进程；可引用 spring-core 工具类与 Jackson / slf4j，去掉容器仍可编译；只依赖 JDK + Jackson + slf4j + domain 词汇；不含业务编排。（历史注：本包 2026-10-05 DDD 四层化前叫顶层 `utils/`，子包结构不变，仅改名。）

**`shared/<领域>/` —— 领域化助手包（2026-09-27 以 `utils/` 立；2026-09-27 修订边界）**：`shared/` 是允许的顶层包，`shared/<领域>/` 装该领域的助手——**只收纯算法助手，以及仅使用 `java.nio.file` 的本地文件系统助手**；以领域名限定归属。服务编排、适配器实现、`*Properties`、契约模型一律不得进入。参照：`shared/subtitle/`（切分器 / 相似度 / 渲染器 / 产物命名 / 术语命中判定）、`shared/fs/ArtifactFiles`（产物落盘、JSON 产物读写与大小判定）、`shared/text/TextTail`（文本尾部截取）、`shared/progress/ProgressMapper`（进度监听器重挂）。

**Spring 边界看容器，不看 jar**：判据是「**把 Spring 容器整个拿掉，这个包还能不能编译并通过测试**」——**能**才准进。故 `org.springframework.util.*` 这类纯静态工具**允许**；Spring 注解（`@Component` / `@Service` / `@Autowired` / `@Value` / `@ConfigurationProperties` 等）、`ApplicationContext`、`Environment`、HTTP 类型**禁止**。

**其余允许**：JDK、slf4j、Jackson、`java.nio.file`。**其余禁止**：自己发起网络传输或调用外部进程——注意**依赖注入进来的接口不算**（某个工具类只调注入的函数式接口、真正的 HTTP 在装配处发生，就不算它发的）。这条划的是「谁真的发请求」，不是「谁的名字听起来像外部调用」。（2026-09-28 注：原范例 `utils/subtitle/SubtitleCorrector` 已被 LLM worker `SubtitleCleanser` 取代删除——后者持 Spring AI 类型，本就过不了「拿掉容器还能编译」判据；该 worker 现居 `infrastructure/provider/llm/`。）

**`domain/` 不得依赖 `shared/`**——尤其 `shared/fs/`：`domain/` 类型的紧凑构造器里不许做 I/O，且领域不依赖工具（依赖方向固定为 `shared → domain` 单向借用词汇）。这条是允许 `shared/fs/` 存在的前提：`shared/` 从此不再保证「无副作用」，靠这条护栏挡住最危险的误用点。

**「同一判据只有一份」优先于「别提前造 util」**（2026-09-28 立）：当某条判定已经有两个以上真实使用者时，把它提到 `shared/<领域>/` 是**搬迁**而非提前抽象，不适用上面那条「别为可能用到提前造桶」的告诫。判据：搬的是**纯函数**（无状态、无 I/O、无 Spring 类型），不是流程骨架。反例：批处理循环、校验重试循环这类骨架，即使形状相似也不要抽公共基类——参数会比逻辑多。参照：`shared/subtitle/TermMatcher`（`hitTerms` 原在 `SubtitleCleanser` 内，因清洗的渲染/校验基线/指标统计三处共用而被要求「抽成一处」，翻译成为第四个使用者后落到共享工具）。

跨包使用须 `public`，纯内部助手保留包私有。

## 应用服务层与 HTTP 入口

- **应用服务（`XxxService`）放 `application/<上下文>/`**：对上屏蔽端口装配细节，组装契约后委托端口，不出现适配器实现类与 SDK 类型
- **Controller 放 `web/controller/`**：依赖 application 层，不直接依赖 `infrastructure/`（唯一例外见[豁免清单](#依赖规则与豁免清单) b）
- **请求体与响应体契约放 `web/dto/`**——除非该类型的 JSON 形状本就可直接对外，那种情形留在所属层包并补 `@Schema`。归属判据见[契约类的位置](#契约类的位置)的判据 1。**响应体与请求体同判据**：不要因为「响应是应用层返回的」就默认它属于应用层契约
- `TaskService` 用 `@Service`，Controller 只做「接收 → 卫语句 → 委托 → 返回」，不写业务逻辑
- **模型 ↔ HTTP 契约的转换逻辑留在 `web/controller/`，不为它另开第三个子包**：这部分代码大半是 HTTP 输入校验，而「校验」本就归 Controller（见上一行）；真正「不属于 HTTP」的只有末尾构造那几行，不值得为它单独开包

## 测试包结构

测试包与主包**镜像对应**：`backend/src/test/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeAsrProviderTest.java` 对应 `.../main/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java`。

- 测试类名 = 被测类名 + `Test`
- 另有 `VidSageApplicationTests` 作为上下文加载冒烟测试

## 不适用

`database-guidelines.md` 已于 2026-09-30 随 MVP-1 落库重建（此前因「没有任何持久化依赖」删除），持久化三层现居 `infrastructure/persistence/`。`logging-guidelines.md` 已于 2026-09-24 随日志功能落地而重建。
