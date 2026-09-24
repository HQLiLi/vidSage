# 包结构与命名

## 按技术分层，同类构件集中

本项目后端**按技术分层**：顶层包按「构件类型」划分（控制器、服务、契约、出站实现），同类构件集中在同一包内，方便横向查找与对比。`controller/` 下能一眼看全所有 HTTP 入口，是采用这种分层的核心动机。

**不要**按功能建顶层包（如 `asr/`），功能语义体现在类名与二级包上。

当前结构（`backend/src/main/java/com/vidsage/`）：

```
com.vidsage
├── VidSageApplication.java        # 唯一的启动类，放在根包
├── controller/                    # 全部 HTTP 入口平铺于此
│   └── AsrController.java
├── service/                       # 全部应用服务平铺于此
│   └── TranscriptionService.java
├── model/                         # 进程内数据契约，按领域二级分包
│   └── asr/
│       ├── AudioSource.java       # sealed：AudioUrl / AudioFilePath
│       ├── TranscriptionCommand.java
│       ├── TranscriptionResult.java
│       └── TranscriptionSegment.java
└── provider/                      # 全部出站实现，按领域二级分包
    └── asr/
        ├── ASRProvider.java       # 接口留在领域包根
        └── dashscope/             # 一个实现一个子包
            ├── DashScopeAsrProvider.java
            └── DashScopeProperties.java
```

测试包与主包**镜像对应**（`backend/src/test/java/com/vidsage/`）：

```
com.vidsage
├── VidSageApplicationTests.java
├── controller/
│   └── AsrControllerTest.java
└── provider/
    └── asr/
        └── dashscope/
            └── DashScopeAsrProviderTest.java
```

## 顶层分包判据

每个包「允许出现什么、禁止出现什么」：

| 包 | 职责 | 允许出现 | 禁止出现 |
|---|---|---|---|
| `controller/` | HTTP 入站适配 | `@RestController`、请求体 record、局部 `@ExceptionHandler` | 业务逻辑、直接依赖 `provider/`、SDK 类型 |
| `service/` | 应用逻辑编排 | `@Component`、组装契约、委托 Provider 接口 | 实现类、SDK 类型、HTTP 类型 |
| `model/` | 进程内数据契约 | `record`、sealed 接口、Javadoc 约束 | 框架注解（Jackson 除外）、行为方法、SDK 类型 |
| `provider/` | 出站实现（外部 API / 模型服务） | 接口 + 实现类 + 专用 `*Properties` | SDK 类型外泄到公开签名 |
| `config/` | 跨领域装配（**未来落点，当前未创建**） | `@Configuration`、跨领域 Bean | 某一实现专用的配置（那属于 `provider/<领域>/<实现>/`） |

### `controller/`、`service/` 平铺，`model/`、`provider/` 要领域二级包

判据是**包内类名是否已自带领域语义**：

- `AsrController`、`LLMController` 平铺在 `controller/` 下，看到类名就知道领域，再套一层二级目录是冗余层级。`service/` 同理（`TranscriptionService`）。
- 契约类名（`TranscriptionCommand`）与实现配置类名（`DashScopeProperties`）**不带机制前缀**，平铺后必然混杂。MVP-3 的 `LLMCommand` 与 `TranscriptionCommand` 并排在 `model/` 根下读起来分不清归属，所以这两处按领域分组（`model/asr/`、`model/llm/`、`provider/asr/`、`provider/llm/`）。

**新增领域时**：`controller/`、`service/` 直接加类；`model/`、`provider/` 下开新的领域子包。`provider/<领域>/` 内仍遵循 Provider 四件套结构（接口留领域包根、实现与该实现专用的 `*Properties` 进 `provider/<领域>/<实现短名>/`，见 [provider-pattern.md](./provider-pattern.md)）。

## `model` / `domain` / `persistence` / `config` 怎么选

这四个包名不是互斥选项：`model` 与 `domain` 是同一个轴（类**是什么**），`persistence` 是另一个轴（类**放哪一层**），永远不与之抢位置。

| 包名 | 语义 | 判据：出现什么才建 |
|---|---|---|
| `model` | 进程内数据形状，无行为 | 类都是 `record`/sealed，只有字段与 Javadoc 约束 |
| `domain` | 业务对象，有行为与不变量 | 类里有状态流转与校验逻辑，出现 `markRunning()` 这类会拒绝非法状态的方法 |
| `persistence` | 出站存储适配层 | 出现表映射、Mapper、缓存读写 |
| `config` | 跨领域装配 | 出现不属于任何单一实现、跨领域共用的 `@Configuration` |

**当前只建 `model/`，不建 `domain/` 与 `persistence/`。** 现有类全是无行为的 `record` 或纯委托，没有任何不变量。把贫血 record 放进 `domain/` 会让包名做出「这里有业务规则」的承诺，而后来者照 `domain/` 找校验逻辑却找不到，最终把规则写进 `service/` —— 规范与代码打架。

**`domain/` 升级触发条件**：当出现**必须拒绝非法状态变化**的对象时，建 `domain/`，与 `model/` 并存、不合并。候选场景：转写任务生命周期状态机（`PENDING → RUNNING → SUCCEEDED/FAILED`）、字幕编辑合并、翻译重试策略。

**`persistence/` 与 `model/` 不合并**：数据库行结构与接口契约的演进节奏不同；若合并，MyBatis 注解会混进 `model/`，破坏「框架类型不外泄」的同一条精神。转换点只允许有一处。

## 持久化三层：Entity / Mapper / Repository

> 前置说明：`backend/pom.xml` 目前**没有任何持久化依赖**，`persistence/` 尚不存在。以下为**落库时的规则**，不是当前代码的现状。

| 层 | 类名 | 职责 | 谁看得见 |
|---|---|---|---|
| 表映射 | `XxxEntity` | 数据库行结构，`@TableName`，普通类 + `@Getter`/`@Setter` | 仅 `persistence/` 内部 |
| 数据访问 | `XxxMapper` | `interface extends BaseMapper<XxxEntity>`，拼查询条件 | **仅 `Repository`**；`service/` 不可见 |
| 对上契约 | `XxxRepository` | 具体类（`@Component`），包装 Mapper，**实体 ↔ record 契约转换**，暴露领域语义方法 | `service/` 唯一可见的持久化入口 |

- **共存，不是二选一**：三层同时存在，职责不同。`Repository` 要转换，就必须包着 `Mapper`。
- **受限的是可见性，不是存在性**：`Mapper` 必须存在（`Repository` 要用），但**不许出现在 `service/` 里**。规则是「`service/` 不许直接依赖 `Mapper`」，不是「不许同时存在 `Mapper` 和 `Repository`」。
- **不预先抽接口**：`XxxRepository` 直接是**具体类**，不写 `interface` + `Impl`。单一存储实现下抽接口属于「为以后可能用到」的过度设计（与 [quality-guidelines.md](./quality-guidelines.md) 反模式清单同一条精神）。
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

理由：空目录不表达任何约束，只制造虚假的结构信号。本规范的上一版曾规定 package-by-feature，而磁盘上长期存在空的 `controller/`、`service/`、`config/` 目录，让人误以为技术分层已经落地 —— 这正是本次结构修正的起因之一。

推论：`config/` 作为「跨领域装配类的未来落点」记入本文件即可，**出现第一个跨领域 `@Configuration` 时才建**。

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
| Spring 组件 | 实现类用 `@Component`，**不用 `@Service`** | `DashScopeAsrProvider`、`TranscriptionService` |
| Controller | 业务名 + `Controller`，平铺在 `controller/` | `AsrController` |

> `ASRProvider` 是全大写缩写开头，与 Spring 常见的 `AsrProvider` 写法不同。**跟随现有代码**，不要"顺手改正"。

## 契约类的位置

数据契约（`Command` / `Result` / 子结构）放 `model/<领域>/`；出站接口（`XxxProvider`）留 `provider/<领域>/` 包根，实现类与该实现专用的 `*Properties` 进 `provider/<领域>/<实现短名>/`。

不建 `dto/`、`util/` 这类按技术命名的顶层分包（`config/` 例外，见上）。

参照：`provider/asr/`（接口在领域包根，`dashscope/` 放云端实现与配置）、`model/asr/`（中立契约）、`controller/AsrController.java`、`service/TranscriptionService.java`。

## 服务层与 HTTP 入口

- **服务层（`XxxService`）放 `service/`**：对上屏蔽 Provider 装配细节，组装契约后委托接口，不出现实现类与 SDK 类型
- **Controller 放 `controller/`**：依赖服务层，不直接依赖 Provider；请求体 record 定义在 Controller 内部
- `TranscriptionService` 用 `@Component`，Controller 只做「接收 → 校验 → 委托 → 返回」，不写业务逻辑

## 测试包结构

测试包与主包**镜像对应**：`backend/src/test/java/com/vidsage/provider/asr/dashscope/DashScopeAsrProviderTest.java` 对应 `.../main/java/com/vidsage/provider/asr/dashscope/DashScopeAsrProvider.java`。

- 测试类名 = 被测类名 + `Test`
- 另有 `VidSageApplicationTests` 作为上下文加载冒烟测试

## 不适用

`database-guidelines.md` 与 `logging-guidelines.md` 已删除（原因见 [index.md](./index.md)）：前者因目前没有任何持久化依赖与代码，后者因全部源码中没有一条日志语句。落库与日志功能落地后再重建。
