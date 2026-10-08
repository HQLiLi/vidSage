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
├── application/                   # 应用服务层：用例编排、读模型、应用消息与异常、本层定义的出站端口
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

> **这是示意，不是文件清单。** 树只列到「层 → 上下文/构件 → 实现」这一级，不逐类登记；**新增类不需要回头改这份文档**。`<...>` 是占位符，`asr/`、`dashscope/` 是当前的示例值。判断一个新东西该放哪，看下面的[分层归属判据](#分层归属判据)、[依赖规则](#依赖规则)与[契约类的位置](#契约类的位置)。

> **历史注（2026-10-05 DDD 四层化前）**：本规范上一版的结构是顶层 `model/`（纯数据契约）、顶层 `service/`（全部应用服务平铺）、顶层 `provider/`（端口接口与实现同居）、顶层 `subscribe/`（中立订阅机制）、`persistence/` 与顶层 `utils/`。四层化后：`model/` 并入 `domain/`，`service/` 按上下文归入 `application/`，`subscribe/` 归入 `application/task/`，`utils/` 改名 `shared/`，`provider/` 拆为「端口接口进 `domain/`、适配器进 `infrastructure/provider/`」，`persistence/` 挂到 `infrastructure/` 之下。涉及包名的旧条目在下文一律以时间线语境出现。

测试包与主包**镜像对应**（`backend/src/test/java/com/vidsage/`），规则见下方[测试包结构](#测试包结构)。

## 分层归属判据

每个层（包）「允许出现什么、禁止出现什么」：

| 包 | 职责 | 允许出现 | 禁止出现 |
|---|---|---|---|
| `domain/<上下文>/` | 领域层：业务概念、规则、端口接口的家 | 领域词汇（`record` / `enum` / `sealed` 接口）、**状态机与业务规则**、**端口接口**、**值对象**、紧凑构造器承载的字段不变量、**直接充当对外契约时的 `@Schema`**（后两项见下方两处例外） | 框架注解（**会改变行为的**，Jackson 绑定注解除外）、SDK 类型、HTTP 类型、用例编排 |
| `application/<上下文>/` | 应用服务层：用例编排 | `@Service` 编排类、**读模型**、**应用消息**、**应用异常**、**中立订阅机制**、**本层定义的出站端口**（业务无关者，判据见[端口归属判据](#端口归属判据)） | 实现类、SDK 类型、HTTP 类型、`infrastructure/` 的具体类 |
| `infrastructure/persistence/` | 出站存储适配 | MyBatis-Plus 三件套：`XxxEntity` / `XxxMapper` / `XxxRepository`（`XxxRepository` 实现 domain 定义的存取端口，如 `MybatisVideoTaskRepository` 实现 `domain/task/port/VideoTaskRepository`） | 业务编排、MP 类型外泄 |
| `infrastructure/process/` | 调外部程序的**通用机制** | 进程执行器、外部工具路径解析与诊断等**不面向业务契约**的机制类 | 面向业务契约的 Provider 四件套（那属于 `infrastructure/provider/`）、业务编排 |
| `infrastructure/fs/` | 本机文件系统**读数**适配（2026-10-08 端口定案） | 只读 stat 类端口实现：`NioFileStateReader`（实现 application 的 `FileStateReader` 端口） | 业务编排、写入策略（产物落盘在 `shared/fs/ArtifactFiles`，编排在应用层） |
| `infrastructure/provider/` | 出站实现（外部 API / 模型服务 / 端口适配器） | 端口适配器 + LLM worker 具体类 + 该实现专用的 `*Properties` | SDK 类型外泄到公开签名 |
| `web/controller/` | HTTP 入站适配 | `@RestController`、局部 `@ExceptionHandler` | 业务逻辑、**main 源码**直接依赖 `infrastructure/` 或 `domain/`（跨层规则见[依赖规则](#依赖规则)）、**请求/响应契约的定义**（那是 `web/dto/` 的活） |
| `web/dto/` | 需**展平**的 HTTP 请求/响应契约 | 顶层 `record`、`@Schema`、纯映射的静态工厂（如 `from(...)`） | 业务逻辑（校验之外的编排）、`@RestController`、**main 源码**直接依赖 `infrastructure/` 或 `domain/` |
| `web/sse/` | SSE 连接管理 | emitter 持有 / 心跳 / 清理等 web 层构件 | 业务编排 |
| `config/` | 跨领域配置与装配 | `@Configuration`、`@ConfigurationProperties`、跨领域 Bean | 某一实现专用的配置（那属于 `infrastructure/provider/<能力域>/<实现短名>/`） |
| `shared/<领域>/` | 跨领域的纯算法与本地文件系统助手 | 纯静态方法的工具类（私有构造器 + 全静态） | 网络 / 外部进程 / Spring 注解与容器类型 / HTTP 类型 / 业务编排（准入判据见[下文](#shared共享工具层)） |

各层「装什么」的细则：

**`domain/`（五个上下文 + 共享内核）**：

- 领域词汇：`record` / `enum` / `sealed` 接口等中立契约（如 `TranscriptionCommand` / `TranscriptionResult`、`MediaInput`、`CleanedSubtitle`）。
- 状态机与业务规则：出现「随调用改变状态、并拒绝非法变化」的对象才进 `domain/`——如 `domain/task/VideoTask`（`PENDING → RUNNING → SUCCEEDED/FAILED`，非法迁移抛 `IllegalStateException`）。只有字段约束的数据（`record` + 紧凑构造器）同样是 domain 居民，但它们是**领域词汇**，不是状态机；两类并存，不互相冒充。（历史注：2026-09-30 MVP-1 时 `domain/` 曾与顶层 `model/` 并存——「有状态机的进 domain、纯数据的留 model」；2026-10-05 四层化后 `model/` 整体并入 `domain/`，判据收敛为「领域的东西都进 domain」。）
- **端口接口**（六边形原则：端口属于六边形内部，适配器在 infrastructure）：`ASRProvider` → `domain/transcription/port/`；`MediaAcquirer` / `AudioExtractor` → `domain/media/port/`；`TermLookupProvider` / `SubtitleCleanser` / `SubtitleTranslator` / `SubtitleContextSummarizer` → `domain/subtitle/port/`；`VideoTaskRepository` → `domain/task/port/`。均无框架注解、纯契约，与领域层 purity 一致。**端口也可以定义在 application**（`ToolDiagnostics` → `application/diagnostics/port/`；`FileStateReader` → `application/media/port/`（2026-10-08，文件 stat 采样是技术词汇，实现落 `infrastructure/fs/`）），归属判据见[端口归属判据](#端口归属判据)。
- **值对象**：落位跟着**归属**走——谁定义它的含义就住谁那儿（`TaskNo` → `domain/task/model/`；`SubtitleStageName`（2026-10-07）→ `domain/subtitle/model/common/`：被 media 与 task 引用，但含义由字幕管线的阶段划分定义）。**只有归属落位会造出上下文互相依赖时才退到共享内核 `domain/shared/`**——`WorkspaceKey`（`VideoTask`（task）持有、`VideoTaskService`（application/task）计算、`MediaInput`（media）携带、`VideoTaskRepository` 按它查询，放共享内核避免 `domain/task → domain/media` 依赖）与进度词汇 `StageEvent` / `ProgressListener`（task / transcription / subtitle 三处共用；数据词汇进 `model/`、接口进 `port/`）。**第二批值对象已按同判据落地（2026-10-06 业务逻辑下沉 Phase A）**：`SourceUri`（输入身份规范化串：URL 原文 / 本地路径按 path|size|mtime 定身份）与 `WorkspaceDir`（目录名公式 `sanitize(标签)-key` + Windows 保留名安全化），同为 task 与 media 两上下文共用，随行的 `sanitizeName` / 保留名规则一并入域。
- **纯函数领域政策**（2026-10-06 业务逻辑下沉 Phase B/C 落地）：无状态、零 I/O 的规则判据收进 domain——应用层持事实（量字节、读文件、查库），域做裁决；政策对象是**值对象 / 纯函数领域服务，不是技术端口**。现行归属：`domain/subtitle/policy/Bcp47Language`（语种政策）、`domain/subtitle/policy/TermVerification`（术语查证触发与记账）、`domain/subtitle/policy/CheckpointPolicy`（检查点语义 + 上下文缓存-or-摘要）；`domain/task/policy/TaskArtifactScan`（产物可见性白名单）、`domain/task/policy/TaskStageEvent`（任务状态 → 阶段事件的渲染政策，2026-10-06 自 `VideoTaskService` 的两个包级私有 static 下沉——跨包共用同一份形状，靠 public 政策而非放宽可见性）；`domain/media/model/AudioArtifact.fileName`（音频规格指纹命名）、`domain/media/model/MediaInput.ofTaskInput`（TaskInputType → 变体重建工厂 + `source.mp4` 落盘名）；`domain/task/VideoTask` 上的查询谓词 `isEventSettled`（终态判定，web 收口引用）与 `ACTIVE_STATUSES`（active 判据，repository 查重引用）。

**`application/`（五个上下文）**——用例编排的家，五类一等构件：

- **用例服务**：`@Service` 编排类，**有 web 调用面**（`VideoTaskService`——`TaskController` 唯一注入的应用服务）或**被多处方共用的横切能力**（`WorkspaceService`，被 6 处注入）。组装契约、委托端口，不出现适配器实现类与 SDK 类型。
- **管线构件**：`@Component` 编排类，**没有 web 调用面**——`SubtitlePipeline`（管线的所有者：配额、虚拟线程、阶段顺序、进度广播、终态收口）与各阶段构件（认领进度阶段的那一步，只被上级管线按顺序调用一次）。判据、命名与「为什么不下沉 domain」见下节[构件说明](#构件说明服务与管线构件)。
- **读模型**：`TaskSnapshot` / `TaskArtifact` / `TaskArtifactFile`——面向查询的应用层投影，**零 web 注解**（2026-10-06 读模型拆分：对外 @Schema 集中到 `web/dto/TaskResponse`，见[契约类的位置](#契约类的位置)判据 2）。
- **应用消息 / 应用异常**：`TaskProgressMessage`（应用 → web 的进度消息）、`TaskConflictException` / `TaskNotFoundException`。
- **中立订阅机制**：`TaskSubscriptionRegistry` / `Subscription`——application 层管线广播事件、web 层挂「写 emitter」回调，两边都依赖它。判据是「写得出它不许装什么」：**禁 HTTP 类型**（`SseEmitter` 只活 `web/sse/`，此 Javadoc 承诺原样保持）、**禁业务编排**；与 `shared/` 同类「机制包」先例。（历史注：该机制 2026-09-30 曾定调为顶层 `subscribe/` 包，2026-10-05 四层化归入 `application/task/`；升级条件随之带入——出现多传输协议（SSE + WebSocket）并存或包内构件 ≥3 时再议拆分。）

上述五类构件在 `application/task/` 内按角色子包落位（2026-10-05，2026-10-06 补 `pipeline/`）：服务门面与 `TaskProgressMessage` 留根，管线构件进 `pipeline/`、读模型进 `query/`、应用异常进 `exception/`、中立订阅机制进 `subscribe/`（禁 HTTP 类型的承诺随迁入其包说明）；判据见下文[二级包怎么分](#二级包怎么分)。

### 构件说明：服务与管线构件

`application/` 下的编排类分两档，**判据是「有没有 web 调用面」**：

| 档 | 注解 | 是什么 | 现行成员 |
|---|---|---|---|
| **服务** | `@Service` | 有 web 调用面，或被多处方共用的横切能力 | `VideoTaskService`（`TaskController` 唯一注入的应用服务）、`WorkspaceService`（被 6 处共用） |
| **管线构件** | `@Component` | 没有 web 调用面，只被上级管线按顺序调用 | `SubtitlePipeline`（管线的所有者）、各 `XxxCoordinator` / `XxxStage`（管线的一步） |

命名跟着分两条：

- **服务类前缀与聚合根对齐**——聚合根是 `VideoTask`，故 `TaskService` → `VideoTaskService`（2026-10-06 命名对齐）。**对外契约与值对象不跟这个前缀**：「任务」是对外概念（路由就是 `/api/tasks`，前端也这么叫）。故 `VideoTaskService` 返回 `TaskSnapshot` 是**已知且可接受**的不一致——口径是「服务与聚合根对齐，读模型与对外契约沿用对外概念」。
- **管线不跟聚合根前缀，按产出命名**（2026-10-07）——管线的所有者叫 `SubtitlePipeline`，不叫 `VideoTaskPipeline`。两条理由：① 两个前缀回答的是**两问**——服务前缀答「这是谁的任务」，管线前缀答「**它产出什么**」，不该共用一个词；② `VideoTask` 里的 `Video` 本身是**窄化**的（音频是一等输入、走同一条管线），拿它当别的类型的限定语会把错误传下去。
- **管线构件后缀按角色取**（2026-10-07 重立）——**`XxxCoordinator` = 认领进度阶段、驱动若干协作者的那一步**。判据是「**它认不认领阶段事件**」，即在 `emit(...)` 里报出阶段名。
  - 存量里名不副实的是 `MediaAcquisitionStage`（选一个 `MediaAcquirer` 并委托）与 `TranscriptionStage`（包 `ASRProvider`）——它们**一个阶段事件都不发**，只是阶段内部调用的端口分派与包装。该用「做事的人」的 `-er` / `-or` 名（拟 `MediaAcquirerDispatcher` / `Transcriber`），**待改**。
  - 被替换的旧判据「管线的一步以 `Stage` 结尾，**一个阶段对应一个执行类**」**已作废**：后半句两头都不成立——`AudioPreparationStage` / `SubtitlePreparationCoordinator` 各认领**两个**阶段，而上面那两个认领**零个**。

  （`Service` 后缀仍**不许**用在管线构件上：它在本项目的含义是「用例入口 / 横切服务」，管线构件与两者都不沾。2026-10-06 命名对齐前，六个阶段类叫 `SubtitleService` / `SubtitleCleanupService` / …，名不副实。）

**为什么管线构件不下沉 `domain/`**：它们全是 I/O 密集（读工作区、调端口、写产物文件），而 `domain → shared` 禁止、`domain` 的紧凑构造器不许做 I/O；纯业务判据已下沉完（`CheckpointPolicy` / `TermVerification` / `Bcp47Language` / `TaskArtifactScan` 等）。**问题不在分层，在名字。**

> 判据的来历（2026-10-06 命名对齐）：旧口径下这两档都叫 `XxxService`，但全项目**只有 `VideoTaskService` 有 web 调用面**，其余每一个的调用者都恰好只有一个——没有一个是独立用例（`WorkspaceService` 是唯一的横切例外）。名字在撒谎，故按「有没有 web 调用面」重划，并让 stereotype 跟着判据走。

**`infrastructure/`（persistence / process / provider）**：

- `persistence/`：MyBatis-Plus 三件套（见[持久化三层](#持久化三层entity--mapper--repository)）。
- `process/`：外部进程能力——`ExternalProcessRunner`、`ProcessOutcome`、`ProcessToolDiagnostics`、`ExternalToolProperties`。**执行机制本身不抽端口**：只有「在本机调外部程序」一种实现方式，不存在「同一能力、多种来源、配置二选一」的前提——与 [quality-guidelines.md](./quality-guidelines.md) 反模式清单同一条精神。测试的可替换点应落在更小的地方（如外部命令的测试替身）。
  - **但诊断能力抽了端口**（2026-10-06 端口倒置）：`ProcessToolDiagnostics` 实现 `application/diagnostics/port/ToolDiagnostics`，出参 `ToolDiagnosticReport` 随端口落在 application 层——诊断是业务无关的运维能力，故端口归 application（判据见[端口归属判据](#端口归属判据)）。这是全项目唯一一处 `infrastructure → application` 的依赖，窄口只开在「实现端口」上。
  - （历史注：2026-10-05 前它曾以顶层 `provider/tool/` + `model/process/` 两处存在，`ProcessOutcome` 本就是 `ExternalProcessRunner` 的出参契约，合并后归此。原 `ExternalToolReport` / `ExternalToolDiagnostics` 于 2026-10-06 分别更名为 `ToolDiagnosticReport`（迁至 application）与 `ProcessToolDiagnostics`。）详见 [provider-pattern.md](./provider-pattern.md) 的「同能力多实现共存」一节。
- `provider/`：各端口适配器（`DashScopeAsrProvider`、`FfmpegAudioExtractor`、`LocalFileMediaAcquirer`、`YtDlpMediaAcquirer`、`DashScopeTermLookupProvider`）+ LLM worker 具体类（`SubtitleCleanser` / `SubtitleTranslator` / `SubtitleContextSummarizer` / `MessageHistory`，**不抽端口**）+ 该实现专用的 `*Properties`。

**`web/` 与 `config/` 职责不变**：`web/controller/` 收发 HTTP、`web/dto/` 装需展平的契约、`web/sse/` 管连接；`config/` 装跨领域 `@Configuration` / `@ConfigurationProperties`。

**`shared/`（原 utils/，2026-10-05 改名）准入判据**：纯静态工具、无状态、不发起网络 / 外部进程；可引用 spring-core 工具类（`org.springframework.util.*`）与 Jackson / slf4j，**去掉容器仍可编译**；依赖上只认 **JDK + Jackson + slf4j + domain 词汇**；不含业务编排。完整细则见[下文](#shared共享工具层)。

> **`domain/` 契约的两处例外**（禁止清单收窄的两处，判据如下）：
> 1. **`@Schema`**：禁止清单的立法意图是「防止外部框架语义渗进中立契约」，它列举的「行为方法、SDK 类型」都是**有实质影响**的东西。`@Schema` 跳过整个运行时——**删光它接口行为一个字节不变**。所以原措辞收窄为「框架注解（**会改变行为的**，Jackson 绑定注解除外）」；`@Schema` 这类**零运行时影响的纯文档元数据**，仅当该类型**直接充当对外契约**时允许（判据见[契约类的位置](#契约类的位置)）。
> 2. **紧凑构造器承载的字段不变量**：它**不属「行为方法」**——紧凑构造器不是实例方法，且实例出生后**仍无任何行为方法**：不变量在出生时验过，此后该类型只是一袋数据。因此契约级字段约束可以放进 `domain/`（`AudioSpec` 即如此），不必在各服务与各实现里各写一遍校验。

## 依赖规则

> **本文件是依赖规则的正典落点**，其余 spec 文件引用本节、不复述全文。

**依赖表**：

| 层 | 允许依赖 |
|---|---|
| `domain` | 不依赖任何模块（跨层 import 0 处；`domain → shared` 也禁止） |
| `application` | `domain`、`shared` |
| `infrastructure` | `domain`、`application`（**仅窄口白名单**，见下）、`shared` |
| `web` | **main 源码**只依赖 `application`；**test 源码**为构造夹具允许 import `domain` |
| `config` | 谁的都不依赖（纯 `@Configuration` / `@ConfigurationProperties`） |

`shared/` 是工具层：`shared → domain`（借用领域词汇）允许，**`domain → shared` 禁止**（领域不依赖工具，也不制造环）。application 不依赖 web（HTTP 类型只在 `web/`）。

**`infrastructure → application` 是窄口 + 白名单**（2026-10-06 端口倒置落地）：

```
允许：
  import application/<上下文>/<端口接口>      仅用于 implements 出站端口
禁止：
  import application/<上下文>/*Service        不得反向调用 application 的用例服务
  import web/ 与 web/dto/ 的任何类型
```

禁止反向调用用例服务是这条规则里最要紧的一句：一旦放任，会出现循环依赖、事务边界混乱，infrastructure 变成四不像。

**web 的 test 豁免**：依赖规则约束**生产代码**；`web/` 的 test 源码为构造夹具允许 import `domain`——`TaskProgressMessage` / `TaskSnapshot` 这类 application 类型的组件本身就是领域词汇，禁掉它等于逼 application 为测试再造一套 String 化构造入口，是把测试的需要泄漏进生产 API。

### 端口归属判据

端口按契约的**语义职责**分流，不只看词汇：

```
领域词汇 + 业务规则需要       → domain 端口（落 domain/<上下文>/）
技术/运维词汇                 → application 端口（落 application/<上下文>/）
领域词汇 + 仅编排/集成需要    → application 端口
```

兜底问题：**删掉这个端口，业务规则还成立吗？** 成立 → application 端口；不成立 → domain 端口。

**端口实现规则**：两类端口均由 `infrastructure/` 实现，infrastructure 是唯一实现端口的地方；application 只调用端口，不实现出站端口；domain 聚合根不调端口，domain service 可调 domain 端口（本仓当前尚无 domain service）。

> **豁免清单已清空（2026-10-06）**。此前 `application → infrastructure`（豁免 a，5 处具体类）与 `web → infrastructure`（豁免 b，`MediaController` 直读 `ExternalToolReport`）被明文允许；端口倒置后两处归零（任务 `10-06-port-inversion`）。同期 `web → domain` 也已清零（main 源码）。

## 二级包怎么分

三条轴各管各的，**不要互相推导**：

- **`domain/`、`application/` 按限界上下文分**：`task/`、`media/`、`transcription/`、`subtitle/`（application 另有 `diagnostics/`）。上下文是领域概念的家；跨上下文词汇进 `domain/shared/` 共享内核。
- **上下文包内按「角色」拆桶，桶名用该层已有的角色词**（2026-10-06 包结构整理定，取代旧的「1–3 类平铺即可」阈值）：判据不看包有多大，只看**这个类在所属层的角色**——角色决定桶路径，新增类据此机械落位。**根留该包的门面**，该上下文没有门面时根只剩 `package-info.java`（合法）；**有成员才建桶**，不建空目录：

| 层 | 桶 | 根留什么 |
|---|---|---|
| `domain/<上下文>/` | `port/`（接口）、`policy/`（final class + 静态方法的无状态政策）、`model/`（record / enum / sealed 数据词汇） | 主类型（状态机/聚合，如 `VideoTask`） |
| `application/<上下文>/` | `port/`（本层出站端口）、`pipeline/`（管线构件：管线的所有者与各阶段类）、`query/`（读模型）、`exception/`（应用异常）、`subscribe/`（中立订阅机制） | `@Service` 服务门面 + 应用消息（如 `VideoTaskService` + `TaskProgressMessage`） |

- **`model/` 内可再按子主题分一层**——全项目唯一的二层用法（2026-10-06）：`domain/subtitle/` 的数据载体多到该再分，故有 `model/cleanup/`、`model/translation/`、`model/term/`、`model/common/`。口径是「先角色，角色内再按子主题」。
- **不再套桶的组合**（已被别的判据定义过，或本来只有一个角色）：`infrastructure/provider/<能力域>/<实现短名>/`（该实现的适配器 + 专用 `*Properties` + 私有助手算一件）、`infrastructure/persistence/`（entity / mapper / repository 三件套）、`web/`（controller / dto / sse）、`shared/<域>/`（纯静态助手）。

> **判据的来历与代价（2026-10-06 包结构整理）**：旧口径是「角色子包随 mass 出现——1–3 类的小上下文平铺即可，类多且角色分明再分子包」，其中「类多」是多少、「角色分明」怎么算都要逐包争论。新口径只留角色轴，好处是落位可机械推导；代价是允许**只有 1 个文件的桶**（如 `domain/task/port/`），这是分类的代价、可接受。`application/task/` 自 2026-10-05 的 `query/`、`exception/`、`subscribe/` 起就是这条例子的实例，本次把它推广到全部上下文包。
- **`web/` 按角色（构件类型）分**（现有三个：`controller/`、`dto/`、`sse/`，第三个 2026-09-30 随 MVP-1 加入）。`TaskController`（入站适配构件）与 `SubmitUrlRequest`（HTTP 契约构件）是**两种构件**，不是一个领域的两个层次——把它们堆进同一包才会混杂。这与上下文轴是**两条独立判据**。
- **`infrastructure/` 按构件类型 + 能力域分**：`persistence/` 内按构件类型分三子包（见[持久化三层](#持久化三层entity--mapper--repository)）；`provider/` 按能力域 → 实现短名两级分（`asr/dashscope/`、`download/ytdlp/`）——契约类名与实现配置类名（`TranscriptionCommand` / `DashScopeProperties`）**不带机制前缀**，平铺后必然混杂。

> **本规范曾以「再套一层二级目录是冗余层级」为由否掉过这类结构，此处有意推翻。** 那条判据对「**同一构件**的再分组」（按上下文把 Controller 再切一层）成立；对「**不同构件**的分离」不成立。区分点是包内是否会混放异质构件，而非目录深度本身。

**新增上下文时**：`domain/<新上下文>/` + `application/<新上下文>/` 各开一包；新端口接口先进 `domain/<上下文>/port/`，适配器与该实现专用的 `*Properties` 进 `infrastructure/provider/<能力域>/<实现短名>/`（能力域已存在的直接落子包），见 [provider-pattern.md](./provider-pattern.md) 的四件套。

> **`infrastructure/provider/llm/` 的形态（2026-09-28 定，2026-10-06 端口倒置后重写）**：包里是三个 LLM 调用 worker 的**实现**——`LlmSubtitleCleanser`（字幕纠错）、`LlmSubtitleTranslator`（翻译）、`LlmSubtitleContextSummarizer`（摘要），各实现 `domain/subtitle/` 的同名端口；另有包私有的多轮历史 `MessageHistory`（只被同包两个 worker 用，装不进窄缝，不抽端口）。SDK 类型（`ChatClient` / `Message` 等）只出现在这些类的内部，公开签名只有本项目的契约类型。
>
> **端口在 2026-10-06 补抽**。此前的定性是「它们不是『同一能力、多种来源』的缝，故不抽接口」——该定性被推翻，新判据见 [quality-guidelines.md](./quality-guidelines.md)「不预先抽接口」一节：抽端口看的是**上层要不要不依赖具体实现类**，不是有没有第二个实现。上层用不到的便捷重载（3 参 `clean` / 3、4 参 `translate`）与包私有测试钩子留在实现类里，不进端口。
>
> **`LLMProvider` 接口仍然不建**（2026-09-28 定）：供应商切换由 Spring AI 承担（本地 Ollama 与云端 DeepSeek 均 OpenAI 兼容），翻案条件见 [provider-pattern.md](./provider-pattern.md)。曾经的临时顶层包 `com.vidsage.llm` 就此消除。

## 持久化三层：Entity / Mapper / Repository

> **已落地（2026-09-30，MVP-1）**：布局为**三个子包** `entity/`、`mapper/`、`repository/`——2026-09-30 项目所有者定调按「构件类型」分子包、类不平铺在持久化包根；当前单表不分领域二级包，表多时再议。2026-10-05 起整体挂到 `infrastructure/persistence/` 之下。依赖与数据源契约见 [database-guidelines.md](./database-guidelines.md)。

| 层 | 类名 | 职责 | 谁看得见 |
|---|---|---|---|
| 表映射 | `XxxEntity` | 数据库行结构，`@TableName`，普通类 + `@Getter`/`@Setter` | 仅 `persistence/` 内部 |
| 数据访问 | `XxxMapper` | `interface extends BaseMapper<XxxEntity>`，拼查询条件 | **仅 `Repository`**；application 层不可见 |
| 对上契约（实现） | `MybatisXxxRepository` | 具体类（`@Repository`），**实现 domain 层的 `XxxRepository` 端口**，包装 Mapper，做**实体 ↔ 领域对象转换** | 上层可见的是 domain 的端口接口，不是本类 |

- **共存，不是二选一**：三层同时存在，职责不同。`Repository` 要转换，就必须包着 `Mapper`。
- **受限的是可见性，不是存在性**：`Mapper` 必须存在（`Repository` 要用），但**不许出现在 application 层**。规则是「application 层不许直接依赖 `Mapper`」，不是「不许同时存在 `Mapper` 和 `Repository`」。
- **端口在 domain，实现在此**（2026-10-06 端口倒置）：`XxxRepository` 是 domain 层的**接口**（如 `domain/task/port/VideoTaskRepository`），实现类住本包，命名 = **技术前缀 + 接口名**（`MybatisVideoTaskRepository`）。上层依赖端口，不认识实现类，`Wrappers` 与实体转换仍锁在本包内。
- **「不预先抽接口」这条旧规则已作废**（2026-10-06）。原表述是「单一存储实现下抽接口属于过度设计」，判据狭窄且与本仓既有事实矛盾——`domain/media/port/AudioExtractor` 一直是单实现端口。新判据见 [quality-guidelines.md](./quality-guidelines.md)「不预先抽接口」一节。
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
| 端口接口 | 用业务名 + 角色后缀，**不加 `I` 前缀** | `ASRProvider`、`VideoTaskRepository`、`ToolDiagnostics` |
| 适配器实现类 | **与接口同名，只加技术前缀**（2026-10-06 端口倒置厘清） | `DashScopeAsrProvider`、`MybatisVideoTaskRepository`、`LlmSubtitleCleanser`、`ProcessToolDiagnostics` |
| 配置属性 | 实现方名 + `Properties` | `DashScopeProperties` |
| 契约 | 动作名 + `Command` / `Result` | `TranscriptionCommand` / `TranscriptionResult` |
| 契约子结构 | 语义名，不叫 `DTO` / `VO` | `TranscriptionSegment` |
| Spring 组件 | **按包选 stereotype**：`web/controller/`→`@RestController`、`application/` **用例服务与横切服务**→`@Service`、`application/` **管线构件**（`XxxPipeline` / `XxxCoordinator` / `XxxStage`）与机制类→`@Component`、`infrastructure/`→`@Component`、`infrastructure/persistence/`→`@Repository`、`web/sse/`→`@Component` | `TaskController`、`VideoTaskService`、`SubtitlePipeline`、`SubtitlePreparationCoordinator`、`DashScopeAsrProvider` |
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

**判据 2（对外响应类的命名与落位，2026-10-06 读模型拆分定，对未来新增端点生效）**：对外响应类命名 `XXResponse`、落 `web/dto/`、`@Schema` 集中于此；由应用层读模型组装的响应，读模型本体**零 web 注解**、落 `<上下文>/query/`，web 侧以静态工厂 `from(读模型)` 纯映射、Controller 调用（`TaskResponse.from(TaskSnapshot)` 为首例）。**响应内嵌的清单元素类型同理**（`TaskArtifactResponse.from(TaskArtifact)`）。请求类命名沿用 `XXRequest`（`SubmitUrlRequest` 已符合）。判据 1 的「直接对外」分支对既有落位类型（`ToolDiagnosticReport` / `TranscriptionResult`）维持原状，不据此新造。

**现有代码已按此判据运行，本节只是把既有事实写成规则**：

| 类型 | 位置 | 为什么 |
|---|---|---|
| `ToolDiagnosticReport` | `application/diagnostics/`（根） | 字段全是 `String` / `boolean`，形状可直接对外 → `MediaController` 诊断端点直接返回；其端口 `ToolDiagnostics` 进 `port/` 子包（2026-10-06 端口倒置，原 `infrastructure/process/ExternalToolReport`；端口归属见[判据](#端口归属判据)） |
| `TranscriptionResult` | `domain/transcription/model/` | 字段为 `List<TranscriptionSegment>` + `String`，形状可直接对外 → 直接充当对外契约，补 `@Schema` |
| `TaskSnapshot` / `TaskArtifact` | `application/task/query/` | 任务查询的应用层读模型（查询期产物扫描随行）→ **零 web 注解**（判据 2）；对外 @Schema 在 `web/dto/TaskResponse` 与元素级 `web/dto/TaskArtifactResponse`。流式下载出参 `TaskArtifactFile`（同在 `query/`）字段走响应头不进 JSON，**不补 @Schema** |
| `TaskResponse` / `TaskArtifactResponse` | `web/dto/` | 任务提交 / 查询端点的对外响应（判据 2）：@Schema 齐全（自原 `TaskView` / `TaskArtifact` 逐条迁入，注解参数未改写），静态工厂 `from(读模型)` 纯映射，JSON 字段名 / 类型与拆分前逐一一致 |
| `TaskProgressMessage` | `application/task/` | 应用 → web 的进度消息 → **不带 `@Schema`**：SSE 端点返回的是 `SseEmitter`，本类型只作 `TaskSseHub.send(...)` 的入参流转，注解进不了 `/v3/api-docs`（2026-10-07 删） |
| `SubmitUrlRequest` | `web/dto/` | 入参契约；只带 url 字符串进应用层，`MediaInput` 的构造在 application 内完成（2026-10-06 起 web main 不依赖 `domain/`） |
| `TaskConflictResponse` | `web/dto/` | HTTP 专属组合形状（错误说明 + 既有任务视图），由 `ApiExceptionHandler` 从 application 异常映射 |

数据契约（`Command` / `Result` / 子结构）放 `domain/<上下文>/model/`；读模型放 `application/<上下文>/query/`；端口接口（`XxxProvider`）在 `domain/<上下文>/port/`，适配器实现与该实现专用的 `*Properties` 进 `infrastructure/provider/<能力域>/<实现短名>/`。

**顶层包：`dto/`、`vo/` 这类「形状分类」包不做顶层；`shared/` 这类「内容 / 机制」包可以做顶层。**

- **`dto/`、`vo/`、`entity/` 这类形状分类包不做顶层**：它们只按「是什么形状」分类，任何一层的任何东西都能声称自己属于它，而且把它们全顶置会让顶层包数量失控（本项目顶层控制在构件层 `domain` / `application` / `infrastructure` / `web` / `config` / `shared`）。需要时作为某构件的子包存在（如 `web/dto/`、`infrastructure/persistence/entity/`）。
- **`shared/` 可以做顶层**：它装的是跨领域的内容 / 机制，不是形状空壳，有责可辨，顶置不失控（按领域子包收敛：`shared/<领域>/`）。

> **为何 `dto/` 作 `web/` 的子包可以、作顶层包不可以**：`web/dto/` 有明确且**唯一的**职责边界（「需展平的 HTTP 请求/响应契约」），填得满[分层归属判据](#分层归属判据)的职责表，故不是桶式包；而顶层的 `dto/` 没有归属语境，写不出「这个包不许装什么」。**判据是「能否写出它不许装什么」，不是「包名是不是分类词」。** 构件层（有职责边界、填得满职责表）不受本条限制；`config/` 是另一例，见上。

参照：`domain/task/`（根 `VideoTask` + `model/` + `policy/` + `port/` 三桶齐备的样板）、`domain/subtitle/model/`（全项目唯一的桶内二层）、`application/task/`（根服务门面 + `pipeline/` + `query/` + `exception/` + `subscribe/`）、`infrastructure/provider/asr/dashscope/`（适配器 + 配置）、`application/task/VideoTaskService.java`、`web/controller/TaskController.java`、`web/dto/`。

## shared/：共享工具层

**准入判据**：纯静态工具、无状态、不发起网络 / 外部进程；可引用 spring-core 工具类与 Jackson / slf4j，去掉容器仍可编译；只依赖 JDK + Jackson + slf4j + domain 词汇；不含业务编排。（历史注：本包 2026-10-05 DDD 四层化前叫顶层 `utils/`，子包结构不变，仅改名。）

**`shared/<领域>/` —— 领域化助手包（2026-09-27 以 `utils/` 立；2026-09-27 修订边界）**：`shared/` 是允许的顶层包，`shared/<领域>/` 装该领域的助手——**只收纯算法助手，以及仅使用 `java.nio.file` 的本地文件系统助手**；以领域名限定归属。服务编排、适配器实现、`*Properties`、契约模型一律不得进入。参照：`shared/subtitle/`（切分器 / 相似度 / 渲染器 / 产物命名 / 术语命中判定）、`shared/fs/ArtifactFiles`（产物落盘、JSON 产物读写与大小判定）、`shared/text/TextTail`（文本尾部截取）、`shared/progress/ProgressMapper`（进度监听器重挂）。

**Spring 边界看容器，不看 jar**：判据是「**把 Spring 容器整个拿掉，这个包还能不能编译并通过测试**」——**能**才准进。故 `org.springframework.util.*` 这类纯静态工具**允许**；Spring 注解（`@Component` / `@Service` / `@Autowired` / `@Value` / `@ConfigurationProperties` 等）、`ApplicationContext`、`Environment`、HTTP 类型**禁止**。

**其余允许**：JDK、slf4j、Jackson、`java.nio.file`。**其余禁止**：自己发起网络传输或调用外部进程——注意**依赖注入进来的接口不算**（某个工具类只调注入的函数式接口、真正的 HTTP 在装配处发生，就不算它发的）。这条划的是「谁真的发请求」，不是「谁的名字听起来像外部调用」。（2026-09-28 注：原范例 `utils/subtitle/SubtitleCorrector` 已被 LLM worker `SubtitleCleanser` 取代删除——后者持 Spring AI 类型，本就过不了「拿掉容器还能编译」判据；该 worker 现居 `infrastructure/provider/llm/`。）

**`domain/` 不得依赖 `shared/`**——尤其 `shared/fs/`：`domain/` 类型的紧凑构造器里不许做 I/O，且领域不依赖工具（依赖方向固定为 `shared → domain` 单向借用词汇）。这条是允许 `shared/fs/` 存在的前提：`shared/` 从此不再保证「无副作用」，靠这条护栏挡住最危险的误用点。

**「同一判据只有一份」优先于「别提前造 util」**（2026-09-28 立）：当某条判定已经有两个以上真实使用者时，把它提到 `shared/<领域>/` 是**搬迁**而非提前抽象，不适用上面那条「别为可能用到提前造桶」的告诫。判据：搬的是**纯函数**（无状态、无 I/O、无 Spring 类型），不是流程骨架。反例：批处理循环、校验重试循环这类骨架，即使形状相似也不要抽公共基类——参数会比逻辑多。参照：`shared/subtitle/TermMatcher`（`hitTerms` 原在 `SubtitleCleanser` 内，因清洗的渲染/校验基线/指标统计三处共用而被要求「抽成一处」，翻译成为第四个使用者后落到共享工具）。

跨包使用须 `public`，纯内部助手保留包私有。

## 应用服务层与 HTTP 入口

- **应用服务（`XxxService`）与管线构件（`XxxPipeline` / `XxxStage`）都放 `application/<上下文>/`**：对上屏蔽端口装配细节，组装契约后委托端口，不出现适配器实现类与 SDK 类型。两者的区别与命名判据见[构件说明](#构件说明服务与管线构件)
- **Controller 放 `web/controller/`**：**main 源码**只依赖 application 层，不直接依赖 `infrastructure/` 或 `domain/`（跨层规则见[依赖规则](#依赖规则)；`web/` 的 **test 源码**为构造夹具允许 import `domain`）
- **请求体与响应体契约放 `web/dto/`**——除非该类型的 JSON 形状本就可直接对外，那种情形留在所属层包并补 `@Schema`。归属判据见[契约类的位置](#契约类的位置)的判据 1。**响应体与请求体同判据**：不要因为「响应是应用层返回的」就默认它属于应用层契约
- `VideoTaskService` 用 `@Service`（`application/` 的用例服务；管线构件用 `@Component`，判据见[构件说明](#构件说明服务与管线构件)），Controller 只做「接收 → 卫语句 → 委托 → 返回」，不写业务逻辑
- **模型 ↔ HTTP 契约的转换逻辑留在 `web/controller/`，不为它另开第三个子包**：这部分代码大半是 HTTP 输入校验，而「校验」本就归 Controller（见上一行）；真正「不属于 HTTP」的只有末尾构造那几行，不值得为它单独开包

## 测试包结构

测试包与主包**镜像对应**：`backend/src/test/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeAsrProviderTest.java` 对应 `.../main/java/com/vidsage/infrastructure/provider/asr/dashscope/DashScopeAsrProvider.java`。

- 测试类名 = 被测类名 + `Test`
- 另有 `VidSageApplicationTests` 作为上下文加载冒烟测试

## 不适用

`database-guidelines.md` 已于 2026-09-30 随 MVP-1 落库重建（此前因「没有任何持久化依赖」删除），持久化三层现居 `infrastructure/persistence/`。`logging-guidelines.md` 已于 2026-09-24 随日志功能落地而重建。
