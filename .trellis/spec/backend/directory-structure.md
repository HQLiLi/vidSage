# 包结构与命名

## 按技术分层，同类构件集中

本项目后端**按技术分层**：顶层包按「构件类型」划分（控制器、服务、契约、出站实现），同类构件集中在同一包内，方便横向查找与对比。`web/controller/` 下能一眼看全所有 HTTP 入口，是采用这种分层的核心动机。

**不要**按功能建顶层包（如 `asr/`），功能语义体现在类名与二级包上。

结构示意（`backend/src/main/java/com/vidsage/`）：

```
com.vidsage
├── VidSageApplication.java        # 唯一的启动类，放在根包
├── web/                           # HTTP 入站适配层
│   ├── controller/                # 全部 HTTP 入口平铺于此
│   └── dto/                       # 需展平的请求/响应契约（见「契约类的位置」）
├── service/                       # 全部应用服务平铺于此
├── model/                         # 进程内数据契约，按领域二级分包
│   └── <领域>/                    # 如 asr/：AudioSource、TranscriptionCommand 等
└── provider/                      # 全部出站实现，按领域二级分包
    └── <领域>/                    # 如 asr/
        ├── XxxProvider.java       # 接口留在领域包根
        └── <实现短名>/             # 如 dashscope/：实现类 + 该实现专用的 *Properties
```

> **这是示意，不是文件清单。** 树只列到「层 → 领域 → 实现」这一级，不逐类登记；**新增类不需要回头改这份文档**。`<...>` 是占位符，`asr/`、`dashscope/` 是当前的示例值。判断一个新东西该放哪，看下面的[顶层分包判据](#顶层分包判据)与[契约类的位置](#契约类的位置)。

测试包与主包**镜像对应**（`backend/src/test/java/com/vidsage/`），规则见下方[测试包结构](#测试包结构)。

## 顶层分包判据

每个包「允许出现什么、禁止出现什么」：

| 包 | 职责 | 允许出现 | 禁止出现 |
|---|---|---|---|
| `web/controller/` | HTTP 入站适配 | `@RestController`、局部 `@ExceptionHandler` | 业务逻辑、直接依赖 `provider/`、SDK 类型、**请求/响应契约的定义**（那是 `web/dto/` 的活） |
| `web/dto/` | 需**展平**的 HTTP 请求/响应契约 | 顶层 `record`、`@Schema`、纯映射的静态工厂（如 `from(...)`） | 业务逻辑（校验之外的编排）、`@RestController`、直接依赖 `provider/` |
| `service/` | 应用逻辑编排 | `@Service`、组装契约、委托 Provider 接口 | 实现类、SDK 类型、HTTP 类型 |
| `model/` | 进程内数据契约 | `record`、sealed 接口、Javadoc 约束、**紧凑构造器承载的字段不变量**、**直接充当对外契约时的 `@Schema`**（后两项见下） | 框架注解（**会改变行为的**，Jackson 绑定注解除外）、行为方法、SDK 类型 |
| `provider/` | 出站实现（外部 API / 模型服务） | 接口 + 实现类 + 专用 `*Properties` | SDK 类型外泄到公开签名 |
| `provider/tool/` | 调外部程序的**通用机制** | 进程执行器、外部工具路径解析与诊断等**不面向业务契约**的机制类 | 接口 + 多实现的 Provider 四件套（那属于 `provider/<领域>/`）；直接依赖 SDK |
| `config/` | 跨领域配置与装配（**已建**：`WorkspaceProperties`） | `@Configuration`、`@ConfigurationProperties`、跨领域 Bean | 某一实现专用的配置（那属于 `provider/<领域>/<实现>/`） |
| `utils/` | 跨领域的纯算法与本地文件系统助手 | 纯静态方法的工具类（私有构造器 + 全静态）；`utils/<领域>/` 二级分包；不要求容器的 `org.springframework.util.*` 工具 | 网络 / 外部进程 / Spring 注解与容器类型 / HTTP 类型；被 `model/` 依赖；业务编排 |

> **`provider/tool/` 是「单一实现的基础设施」形态，不是 Provider 四件套。** 该机制只有一种实现方式（在本机调用外部程序），不存在「同一能力、多种来源、配置二选一」的前提，因此**不抽接口**——与 [quality-guidelines.md](./quality-guidelines.md) 反模式清单同一条精神。测试的可替换点应落在更小的地方（如外部命令的测试替身），而不是为单一实现预先造接口。
> 详见 [provider-pattern.md](./provider-pattern.md) 的「同能力多实现共存」一节。

> **`model/` 的两处例外**（禁止清单收窄的两处，判据如下）：
> 1. **`@Schema`**：禁止清单的立法意图是「防止外部框架语义渗进中立契约」，它列举的「行为方法、SDK 类型」都是**有实质影响**的东西。`@Schema` 跳过整个运行时——**删光它接口行为一个字节不变**。所以原措辞收窄为「框架注解（**会改变行为的**，Jackson 绑定注解除外）」；`@Schema` 这类**零运行时影响的纯文档元数据**，仅当该类型**直接充当对外契约**时允许（判据见[契约类的位置](#契约类的位置)）。
> 2. **紧凑构造器承载的字段不变量**：它**不属「行为方法」**——紧凑构造器不是实例方法，且实例出生后**仍无任何行为方法**：不变量在出生时验过，此后该类型只是一袋数据。因此契约级字段约束可以放进 `model/`（`AudioSpec` 即如此），不必在服务层与各实现里各写一遍校验。

### `service/` 平铺，`web/` 按角色分子包，`model/`、`provider/` 要领域二级包

判据是**包内类名是否已自带领域语义**：

- `TranscriptionService` 平铺在 `service/` 下，看到类名就知道领域，再套一层二级目录是冗余层级。
- `AsrController` 平铺在 `web/controller/` 下（HTTP 入口之间不存在再分组的需求）。
- 契约类名（`TranscriptionCommand`）与实现配置类名（`DashScopeProperties`）**不带机制前缀**，平铺后必然混杂。MVP-3 的 `LLMCommand` 与 `TranscriptionCommand` 并排在 `model/` 根下读起来分不清归属，所以这两处按领域分组（`model/asr/`、`model/llm/`、`provider/asr/`、`provider/llm/`）。

**`web/` 下的两个子包是另一条轴：按「角色」分，不是按「领域」分。** `AsrController`（入站适配构件）与 `PrepareAudioRequest`（HTTP 契约构件）是**两种构件**，不是一个领域的两个层次——把它们堆进同一包才会混杂。这与 `model/` / `provider/` 的「按领域分」是**两条独立判据，不要互相推导**。

> **本规范曾以「再套一层二级目录是冗余层级」为由否掉过这类结构，此处有意推翻。** 那条判据对「**同一构件**的再分组」（按领域把 Controller 再切一层）成立；对「**不同构件**的分离」不成立。区分点是包内是否会混放异质构件，而非目录深度本身。

**新增领域时**：`web/controller/`、`service/` 直接加类；`web/dto/` 直接加契约；`model/`、`provider/` 下开新的领域子包。`provider/<领域>/` 内仍遵循 Provider 四件套结构（接口留领域包根、实现与该实现专用的 `*Properties` 进 `provider/<领域>/<实现短名>/`，见 [provider-pattern.md](./provider-pattern.md)）。

> **`provider/llm/` 的现阶段形态（2026-09-28）**：包里目前是两个**具体类**的 LLM 调用 worker——`SubtitleCleanser`（字幕纠错）、`SubtitleContextSummarizer`（摘要），**没有抽接口**：它们不是「同一能力、多种来源」的缝，而是吃 LLM 的领域 worker（与 `provider/tool/` 单一实现不抽接口同一条精神；纠错的 `.messages()` 多轮历史也装不进窄缝）。SDK 类型（`ChatClient` / `Message` 等）只出现在这两类的内部，公开签名只有本项目的契约类型。Provider 四件套留给规划中的 `LLMProvider`（主 PRD 本地模型落地时建，`vidsage.llm.provider` 键已预留），届时这两个 worker 改吃该缝。曾经的临时顶层包 `com.vidsage.llm` 就此消除。

## `model` / `domain` / `persistence` / `config` 怎么选

这四个包名不是互斥选项：`model` 与 `domain` 是同一个轴（类**是什么**），`persistence` 是另一个轴（类**放哪一层**），永远不与之抢位置。

| 包名 | 语义 | 判据：出现什么才建 |
|---|---|---|
| `model` | 进程内数据形状，无行为方法 | 类都是 `record`/sealed，只有字段与**约束**（Javadoc 声明的、或紧凑构造器强制的） |
| `domain` | 业务对象，有行为与不变量 | 类里有状态流转与校验逻辑，出现 `markRunning()` 这类会拒绝非法状态的方法 |
| `persistence` | 出站存储适配层 | 出现表映射、Mapper、缓存读写 |
| `config` | 跨领域配置与装配 | 出现不属于任何单一实现、跨领域共用的 `@Configuration` 或 `@ConfigurationProperties` |

**当前只建 `model/`，不建 `domain/` 与 `persistence/`。** 现有类全是 `record` 或纯委托：`record` 里可能有**构造期字段不变量**（如 `AudioSpec` 的紧凑构造器），但**没有任何状态流转不变量**——没有那种「随调用改变状态、并拒绝非法变化」的对象。`domain/` 的成立前提是后者，不是前者；把只有字段约束的数据放进 `domain/`，会让包名做出「这里有状态机」的承诺，而后来者照 `domain/` 找状态流转逻辑却找不到，最终把规则写进 `service/` —— 规范与代码打架。

**`domain/` 升级触发条件**：当出现**必须拒绝非法状态变化**的对象时，建 `domain/`，与 `model/` 并存、不合并。候选场景：转写任务生命周期状态机（`PENDING → RUNNING → SUCCEEDED/FAILED`）、字幕编辑合并、翻译重试策略。

**`persistence/` 与 `model/` 不合并**：数据库行结构与接口契约的演进节奏不同；若合并，MyBatis 注解会混进 `model/`，破坏「框架类型不外泄」的同一条精神。转换点只允许有一处。

## 持久化三层：Entity / Mapper / Repository

> 前置说明：`backend/pom.xml` 目前**没有任何持久化依赖**，`persistence/` 尚不存在。以下为**落库时的规则**，不是当前代码的现状。

| 层 | 类名 | 职责 | 谁看得见 |
|---|---|---|---|
| 表映射 | `XxxEntity` | 数据库行结构，`@TableName`，普通类 + `@Getter`/`@Setter` | 仅 `persistence/` 内部 |
| 数据访问 | `XxxMapper` | `interface extends BaseMapper<XxxEntity>`，拼查询条件 | **仅 `Repository`**；`service/` 不可见 |
| 对上契约 | `XxxRepository` | 具体类（`@Repository`），包装 Mapper，**实体 ↔ record 契约转换**，暴露领域语义方法 | `service/` 唯一可见的持久化入口 |

- **共存，不是二选一**：三层同时存在，职责不同。`Repository` 要转换，就必须包着 `Mapper`。
- **受限的是可见性，不是存在性**：`Mapper` 必须存在（`Repository` 要用），但**不许出现在 `service/` 里**。规则是「`service/` 不许直接依赖 `Mapper`」，不是「不许同时存在 `Mapper` 和 `Repository`」。
- **不预先抽接口**：`XxxRepository` 直接是**具体类**，不写 `interface` + `Impl`。单一存储实现下抽接口属于「为以后可能用到」的过度设计（与 [quality-guidelines.md](./quality-guidelines.md) 反模式清单同一条精神）。
- **`@Repository` 只是跟随社区惯例**：不要写成「白拿异常转换收益」—— `PersistenceExceptionTranslationPostProcessor` 的异常转换主要针对 JPA/Hibernate 原生异常，**MyBatis-Plus 下是否触发未经核实**，别把它当成立即收益。
- `mapper` / `repository` 是**类名**（`XxxMapper`、`XxxRepository`），不是顶层包名。

### 反模式

| 不要做 | 原因 |
|---|---|
| `service/` 直接注入 `XxxMapper` | 破可见性边界；Mapper 是 persistence 的内部细节 |
| MP 类型（`BaseMapper`、`LambdaQueryWrapper`、`IPage`）出现在 `service/` 方法签名里 | 破 [provider-pattern.md](./provider-pattern.md)「第三方 SDK 类型只在实现类内出现」的同一条精神 |
| 继承 MP 的 `IService` / `ServiceImpl` / `CrudRepository` | 见下 |
| `XxxRepository` 里写业务规则 | 它只做存取与转换；业务编排在 `service/` |

### 不用 MyBatis-Plus 的 service 层抽象

MP 的持久化抽象是三档叠加，**我们只用第一档**：

| 档 | 组件 | 用不用 |
|---|---|---|
| 数据访问 | `BaseMapper<T>` | **用** |
| 泛型 CRUD 外壳 | `IService<T>` / `ServiceImpl` / `CrudRepository` | **不用** |

后一档是给「不想手写 Service 的 CRUD 项目」的糖。本项目 `service/` 装的是应用编排（Provider 装配、契约组装、管线调度），继承那层抽象会把数据访问词汇搬进 `service/`。

> **颗粒度边界**：本文件只定义「三层职责 + 可见性」。**具体用法（`Wrapper` 写法、分页、事务）不在此文档**，留待落库时重建的 `database-guidelines.md` 定义 —— 遵守 [index.md](./index.md)「不要在功能落地前凭空补这些规范」。

## 目录随代码出现，不预建空目录

**硬规则：不得创建当前无代码的目录。** 需要时由搬入的第一个类带出目录。

理由：空目录不表达任何约束，只制造虚假的结构信号。本规范的上一版曾规定 package-by-feature，而磁盘上长期存在空的 `controller/`、`service/`、`config/` 目录，让人误以为技术分层已经落地 —— 这正是本次结构修正的起因之一。（历史注：其中的 `controller/` 已于 2026-09-26 改为 `web/controller/`；此处保留旧名是为了准确记录当时磁盘上的东西。）

> **`web/` 的子包不会重蹈 package-by-feature 的覆辙。** `web/controller/` + `web/dto/` 是**按角色**（构件类型）分：一个包装「入站适配构件」，另一个装「HTTP 契约构件」，与上面[顶层分包判据](#顶层分包判据)的职责表一一对应。package-by-feature 是按**功能领域**切（`asr/`、`translate/`），切出来每个包都同时装着 controller、service、model —— 包本身不再表达任何职责边界，这才是它被推翻的原因。两者不是同类东西。

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
| 接口 | 用业务名 + 角色后缀，**不加 `I` 前缀** | `ASRProvider` |
| 实现类 | 实现方特征前缀 + 接口名 | `DashScopeAsrProvider`（未来：`LocalFunAsrProvider`） |
| 配置属性 | 实现方名 + `Properties` | `DashScopeProperties` |
| 契约 | 动作名 + `Command` / `Result` | `TranscriptionCommand` / `TranscriptionResult` |
| 契约子结构 | 语义名，不叫 `DTO` / `VO` | `TranscriptionSegment` |
| Spring 组件 | **按包选 stereotype**：`web/controller/`→`@RestController`、`service/`→`@Service`、`provider/`→`@Component`、`persistence/`→`@Repository` | `AsrController`、`TranscriptionService`、`DashScopeAsrProvider` |
| Controller | 业务名 + `Controller`，平铺在 `web/controller/` | `AsrController` |

> `ASRProvider` 是全大写缩写开头，与 Spring 常见的 `AsrProvider` 写法不同。**跟随现有代码**，不要"顺手改正"。

> **按包选 stereotype 的理由**：跟随 Spring 社区惯例，让 `service/` 下的类一眼可辨层次。注意 `@Component` 与 `@Service` 在 Spring 中**行为完全等价**（`@Service` 只是 `@Component` 的 `@AliasFor` 特化、无额外行为），这是可读性选择而非功能差异。
>
> **写切面时用包定位**（`within(com.vidsage.service..*)`），不要用注解定位（`@within(org.springframework.stereotype.Service)`）—— 后者在有人漏标注解时会**静默失效且不报错**。

> **包名可叫 `dto/`，类名不可叫 `XxxDTO`——两者尺度不同，是分工而非双重标准。** 包名用**分类词**（`dto/`）是社区惯例，标识桶里装的是哪类东西；类名用**「动作名 + 角色后缀」**（`Request` / `Response`），因为 `DTO` 是分类词、**不承载方向与操作语义**。
>
> 佐证：HTTP 契约的角色后缀与服务层契约**同构**——
>
> ```
> TranscriptionRequest  (HTTP 入参)  ↔  TranscriptionCommand  (服务层入参)
> PreparedAudioResponse (HTTP 出参)  ↔  TranscriptionResult   (服务层出参)
> ```
>
> 故 `PrepareAudioRequest` = 动作名（PrepareAudio）+ 角色后缀（Request），完全符合上表「契约」行的既有约定；`DTO` 从来不在本项目的角色词表里（上表「契约子结构」行明确排除）。
>
> **已知破例（诚实记录）**：本项目角色包的类名基本都带同名后缀——`web/controller/`→`*Controller`、`service/`→`*Service`、`provider/`→`*Provider`；**`web/dto/` 会是唯一不同字的一个**。但 `web/controller/ApiExceptionHandler` 已是一例（它并非 Controller），**故该模式是倾向而非铁律**。破例不必回避，只需写明。

## 契约类的位置

**判据 1（请求与响应共用）：该类型的 JSON 形状是否就是要对外暴露的形状？**

- **是** → 直接充当 HTTP 契约，类型留在 `model/<领域>/`，字段补 `@Schema`（[`model/` 两处例外](#顶层分包判据)之一）
- **否**（含 `Path`、嵌套内部规格等需**展平**之物）→ 在 `web/dto/` 自持顶层 `record`，从模型类型映射

**现有代码已按此判据运行，本节只是把既有事实写成规则**：

| 类型 | 位置 | 为什么 |
|---|---|---|
| `ExternalToolReport` | `model/process/` | 字段全是 `String` / `boolean`，形状可直接对外 → Controller 直接返回 |
| `TranscriptionResult` | `model/asr/` | 字段为 `List<TranscriptionSegment>` + `String`，形状可直接对外 → 直接返回 |
| `PrepareAudioRequest` | `web/dto/` | 入参契约，需展开成 `MediaInput` + `AudioSpec` 两个模型类型 |
| `TranscriptionRequest` | `web/dto/` | 入参契约，需展开成 `AudioSource` + 语种提示 |
| `PreparedAudioResponse` | `web/dto/` | 源 `AudioArtifact` 含 `java.nio.file.Path` + 嵌套 `AudioSpec`，形状**需展平** |

数据契约（`Command` / `Result` / 子结构）放 `model/<领域>/`；出站接口（`XxxProvider`）留 `provider/<领域>/` 包根，实现类与该实现专用的 `*Properties` 进 `provider/<领域>/<实现短名>/`。

**顶层包：`dto/`、`vo/` 这类「形状分类」包不做顶层；`common/`、`utils/` 这类「内容 / 机制」包可以做顶层。**

- **`dto/`、`vo/`、`entity/` 这类形状分类包不做顶层**：它们只按「是什么形状」分类，任何一层的任何东西都能声称自己属于它，而且把它们全顶置会让顶层包数量失控（本项目顶层应控制在明显的构件层 `web` / `service` / `model` / `provider` / `config`）。需要时作为某构件的子包存在（如 `web/dto/`）。
- **`common/`、`utils/` 可以做顶层**：它们装的是跨领域的内容 / 机制，不是形状空壳，有责可辨，顶置不失控（按领域子包收敛：`utils/<领域>/`）。

> **为何 `dto/` 作 `web/` 的子包可以、作顶层包不可以**：`web/dto/` 有明确且**唯一的**职责边界（「需展平的 HTTP 请求/响应契约」），填得满[顶层分包判据](#顶层分包判据)的职责表，故不是桶式包；而顶层的 `dto/` 没有归属语境，写不出「这个包不许装什么」。**判据是「能否写出它不许装什么」，不是「包名是不是分类词」。** 构件层（有职责边界、填得满职责表）不受本条限制；`config/` 是另一例，见上。

参照：`provider/asr/`（接口在领域包根，`dashscope/` 放云端实现与配置）、`model/asr/`（中立契约）、`web/controller/AsrController.java`、`web/dto/`、`service/TranscriptionService.java`。

**`utils/<领域>/` —— 领域化助手包（2026-09-27 立；2026-09-27 修订边界）**：`utils/` 是允许的顶层包，`utils/<领域>/` 装该功能的助手——**只收纯算法助手，以及仅使用 `java.nio.file` 的本地文件系统助手**；以领域名限定归属。服务编排、Provider 实现、`*Properties`、契约模型一律不得进入。

**Spring 边界看容器，不看 jar**：判据是「**把 Spring 容器整个拿掉，这个包还能不能编译并通过测试**」——**能**才准进。故 `org.springframework.util.*` 这类纯静态工具**允许**；Spring 注解（`@Component` / `@Service` / `@Autowired` / `@Value` / `@ConfigurationProperties` 等）、`ApplicationContext`、`Environment`、HTTP 类型**禁止**。

**其余允许**：JDK、slf4j、Jackson、`java.nio.file`。**其余禁止**：自己发起网络传输或调用外部进程——注意**依赖注入进来的接口不算**（某个 utils 类只调注入的函数式接口、真正的 HTTP 在 `config/` 装配里发生，就不算它发的）。这条划的是「谁真的发请求」，不是「谁的名字听起来像外部调用」。（2026-09-28 注：原范例 `utils/subtitle/SubtitleCorrector` 已被 `provider/llm/SubtitleCleanser` 取代删除——后者持 Spring AI 类型，本就过不了 utils 的「拿掉容器还能编译」判据。）

**`model/` 不得依赖 `utils/fs/`**——`model/` 类型的紧凑构造器里不许做 I/O。这条是允许 `utils/fs/` 存在的前提：`utils/` 从此不再保证「无副作用」，靠这条护栏挡住最危险的误用点。

跨包使用须 `public`，纯内部助手保留包私有。参照：`utils/subtitle/`（切分器 / 相似度 / 渲染器 / 产物命名）、`utils/fs/ArtifactFiles`（产物落盘与大小判定）、`utils/text/TextTail`（文本尾部截取）。

## 服务层与 HTTP 入口

- **服务层（`XxxService`）放 `service/`**：对上屏蔽 Provider 装配细节，组装契约后委托接口，不出现实现类与 SDK 类型
- **Controller 放 `web/controller/`**：依赖服务层，不直接依赖 Provider
- **请求体与响应体契约放 `web/dto/`**——除非该类型的 JSON 形状本就可直接对外，那种情形留在 `model/<领域>/` 并补 `@Schema`。归属判据见[契约类的位置](#契约类的位置)的判据 1。**响应体与请求体同判据**：不要因为「响应是服务层返回的」就默认它属于服务层契约
- `TranscriptionService` 用 `@Service`，Controller 只做「接收 → 校验 → 委托 → 返回」，不写业务逻辑
- **模型 ↔ HTTP 契约的转换逻辑留在 `web/controller/`，不为它另开第三个子包**：这部分代码大半是 HTTP 输入校验，而「校验」本就归 Controller（见上一行）；真正「不属于 HTTP」的只有末尾构造那几行，不值得为它单独开包

## 测试包结构

测试包与主包**镜像对应**：`backend/src/test/java/com/vidsage/provider/asr/dashscope/DashScopeAsrProviderTest.java` 对应 `.../main/java/com/vidsage/provider/asr/dashscope/DashScopeAsrProvider.java`。

- 测试类名 = 被测类名 + `Test`
- 另有 `VidSageApplicationTests` 作为上下文加载冒烟测试

## 不适用

`database-guidelines.md` 已删除（原因见 [index.md](./index.md)）：目前没有任何持久化依赖与代码，落库后再重建。`logging-guidelines.md` 已于 2026-09-24 随日志功能落地而重建。
