# 后端包结构改为技术分层 — 技术设计

## 1. 目标结构

```
backend/src/main/java/com/vidsage/
├── VidSageApplication.java            # 不动（唯一的启动类）
├── controller/
│   └── AsrController.java
├── service/
│   └── TranscriptionService.java
├── model/
│   └── asr/
│       ├── AudioSource.java
│       ├── TranscriptionCommand.java
│       ├── TranscriptionResult.java
│       └── TranscriptionSegment.java
└── provider/
    └── asr/
        ├── ASRProvider.java
        └── dashscope/
            ├── DashScopeAsrProvider.java
            └── DashScopeProperties.java
```

```
backend/src/test/java/com/vidsage/
├── VidSageApplicationTests.java       # 不动
├── controller/
│   └── AsrControllerTest.java
└── provider/
    └── asr/
        └── dashscope/
            └── DashScopeAsrProviderTest.java
```

**`config/` 不创建**（见 R4）。它作为「跨领域装配类的未来落点」记入 `directory-structure.md`，出现第一个跨领域 `@Configuration` 时才建。

## 2. 文件级映射

| 现路径（`com.vidsage.` 之后） | 新路径 | 改动内容 |
|---|---|---|
| `asr/ASRProvider.java` | `provider/asr/ASRProvider.java` | package + 2 import |
| `asr/TranscriptionService.java` | `service/TranscriptionService.java` | package + 3 import |
| `asr/model/AudioSource.java` | `model/asr/AudioSource.java` | 仅 package |
| `asr/model/TranscriptionCommand.java` | `model/asr/TranscriptionCommand.java` | 仅 package |
| `asr/model/TranscriptionResult.java` | `model/asr/TranscriptionResult.java` | 仅 package |
| `asr/model/TranscriptionSegment.java` | `model/asr/TranscriptionSegment.java` | 仅 package |
| `asr/provider/dashscope/DashScopeAsrProvider.java` | `provider/asr/dashscope/DashScopeAsrProvider.java` | package + 5 import |
| `asr/provider/dashscope/DashScopeProperties.java` | `provider/asr/dashscope/DashScopeProperties.java` | 仅 package |
| `asr/web/AsrController.java` | `controller/AsrController.java` | package + 3 import |
| `test/asr/web/AsrControllerTest.java` | `test/controller/AsrControllerTest.java` | package + 4 import |
| `test/asr/provider/dashscope/DashScopeAsrProviderTest.java` | `test/provider/asr/dashscope/DashScopeAsrProviderTest.java` | package + 4 import |
| `VidSageApplication.java` | 不变 | — |
| `test/VidSageApplicationTests.java` | 不变 | — |

合计：**9 个主文件 + 2 个测试文件**搬包。`model/asr/` 的四个契约类互相引用都在同包内，因此只需改 package 声明、不产生 import 变更 —— 这四行是本次重构的「零 import」样本，可用于自检映射是否按预期发生。

新包名清单：`com.vidsage.controller` / `com.vidsage.service` / `com.vidsage.model.asr` / `com.vidsage.provider.asr` / `com.vidsage.provider.asr.dashscope`。

## 3. 顶层分包判据（要写进 spec 的规则）

### 3.1 分成哪几个包

| 包 | 职责 | 允许出现 | 禁止出现 |
|---|---|---|---|
| `controller/` | HTTP 入站适配 | `@RestController`、请求体 record、局部 `@ExceptionHandler` | 业务逻辑、直接依赖 `provider/`、SDK 类型 |
| `service/` | 应用逻辑编排 | `@Component`、组装契约、委托 Provider 接口 | 实现类、SDK 类型、HTTP 类型 |
| `model/` | 进程内数据契约 | `record`、sealed 接口、Javadoc 约束 | 框架注解（Jackson 除外）、行为方法、SDK 类型 |
| `provider/` | 出站实现（外部 API / 模型服务） | 接口 + 实现类 + 专用 `*Properties` | SDK 类型外泄到公开签名 |
| `config/` | 跨领域装配（未来落点） | `@Configuration`、跨领域 Bean | 某一实现专用的配置（那属于 `provider/<领域>/<实现>/`） |

### 3.2 为什么 `controller/`、`service/` 平铺，而 `model/`、`provider/` 要领域二级包

判据是**包内类名是否已自带领域语义**：

- `AsrController`、`LLMController` 平铺在 `controller/` 下，看到类名就知道领域，二级目录是冗余层级
- 契约类名（`TranscriptionCommand`）与实现配置类名（`DashScopeProperties`）**不带机制前缀**，平铺后会混杂；MVP-3 的 `LLMCommand` 与 `TranscriptionCommand` 并排在 `model/` 根下，读起来分不清归属，所以按领域分组

**新增领域时**：`controller/`、`service/` 直接加类；`model/`、`provider/` 下开新的领域子包（`model/llm/`、`provider/llm/`）。这同时保证 `provider/asr/` 内的 Provider 四件套结构（接口留包根、实现进 `provider/<实现短名>/`）原样保留。

## 4. `model` / `domain` / `persistence` 命名判据（要写进 spec 的规则）

用户提出的问题：这三个包名怎么选。它们不是互斥选项 —— `model` 与 `domain` 是同一个轴（类**是什么**），`persistence` 是另一个轴（类**放哪一层**），永远不与之抢位置。

| 包名 | 语义 | 判据：出现什么才建 |
|---|---|---|
| `model` | 进程内数据形状，无行为 | 类都是 `record`/sealed，只有字段与 Javadoc 约束 |
| `domain` | 业务对象，有行为与不变量 | 类里有状态流转与校验逻辑，`markRunning()` 这类会拒绝非法状态的方法 |
| `persistence` | 出站存储适配层 | 出现表映射、Mapper、缓存读写 |
| `config` | 跨领域装配 | 出现不属于任何单一实现、跨领域共用的 `@Configuration` |

> 注：`mapper` / `repository` **不是包名，是 `persistence/` 内的类名**（`XxxMapper`、`XxxRepository`），见 4.1。它们是否各自再开子包，等落库时按实际类数量决定，本次不预设。

**本次不建 `domain/`、`persistence/`。** 现有 9 个类全是无行为的 `record` 或纯委托，没有不变量。把贫血 record 放进 `domain/` 会让包名做出「这里有业务规则」的承诺，而子 agent 照着 `domain/` 找校验逻辑会找不到，最终把规则写进 `service/` —— 规范与代码再次打架，与本次要修的正是同一类问题。

**升级触发条件**（写进 spec，作为可执行判据）：当出现必须拒绝非法状态变化的对象时，建 `domain/`，与 `model/` 并存、不合并。候选场景：转写任务生命周期（`PENDING → RUNNING → SUCCEEDED/FAILED` 状态机）、字幕编辑合并、翻译重试策略。

**`persistence/` 与 `model/` 不合并**：数据库行结构与接口契约演进节奏不同；若合并，MyBatis 注解会混进 `model/`，破坏「框架类型不外泄」的同一条精神。转换点只允许有一处。

### 4.1 持久化三层：Entity / Mapper / Repository

本节经历两次修正，记下错因以免重犯：

1. **初稿把 `repository` 列为「禁用」**，理由是「MP 用 Mapper 词汇，叫 repository 名不副实」。**错。** MP 自 v3.5.9 起把 service 模块抽象重构为 `CrudRepository` 并明确不再建议使用 `IService`；其代码生成器支持 `MP%sRepository` 文件名；上游 Issue #5764 讨论的是把 `IService` **反向**改名为 `IRepository`。`Repository` 是 MP 自己的抽象名，不是外来词。
2. **修正时又写成「`Mapper` 与 `Repository` 二选一、不混用」**。**也错**，且与 epic `09-24-vidsage/design.md:15`「Repository 负责实体与契约转换」直接冲突 —— Repository 要转换，就必须包着 Mapper。

两次错误的共同根因：**把「可见性规则」误当成了「存在性规则」**。正确表述是「`service/` 不许直接依赖 `Mapper`」，而不是「不许同时存在 Mapper 和 Repository」。

#### 三层与可见性

| 层 | 类名 | 职责 | 谁看得见 |
|---|---|---|---|
| 表映射 | `XxxEntity` | 数据库行结构，`@TableName`，普通类 + `@Getter`/`@Setter` | 仅 `persistence/` 内部 |
| 数据访问 | `XxxMapper` | `interface extends BaseMapper<XxxEntity>`，拼查询条件 | **仅 `Repository`**；`service/` 不可见 |
| 对上契约 | `XxxRepository` | 具体类（`@Component`），包装 Mapper，**实体 ↔ record 契约转换**，暴露领域语义方法 | `service/` 唯一可见的持久化入口 |

- **共存，不是二选一**：三层同时存在，职责不同
- **受限的是可见性**：`Mapper` 必须存在（Repository 要用），但不能出现在 `service/` 里
- **不预先抽接口**：`XxxRepository` 直接是具体类，不写 `interface` + `Impl`。单一存储实现下抽接口属于「为以后可能用到」的过度设计（与 [quality-guidelines.md](../../spec/backend/quality-guidelines.md) 反模式清单同一条精神）

#### 反模式

| 不要做 | 原因 |
|---|---|
| `service/` 直接注入 `XxxMapper` | 破可见性边界；Mapper 是 persistence 的内部细节 |
| MP 类型（`BaseMapper`、`LambdaQueryWrapper`、`IPage`）出现在 `service/` 方法签名里 | 破 provider-pattern.md「第三方 SDK 类型只在实现类内出现」的同一条精神 |
| 继承 MP 的 `IService` / `ServiceImpl` / `CrudRepository` | 见 4.2 |
| `XxxRepository` 里写业务规则 | 它只做存取与转换；业务编排在 `service/` |

### 4.2 不用 MP 的 service 层抽象

MP 的持久化抽象是三档叠加，**我们只用第一档**：

| 档 | 组件 | 用不用 |
|---|---|---|
| 数据访问 | `BaseMapper<T>` | **用** |
| 泛型 CRUD 外壳 | `IService<T>` / `ServiceImpl` / `CrudRepository` | **不用** |

后一档是给「不想手写 Service 的 CRUD 项目」的糖。本项目的 `service/` 装的是应用编排（Provider 装配、契约组装、管线调度），继承它会把这套数据访问词汇搬进 `service/` —— 正是 MP 转向 `CrudRepository` 想解决的「业务层数据混乱」。

因此「MP 已不推荐 `IService`」对本项目是**空问题**：这三个我们一个都不继承。

> 前置说明：本项目 `pom.xml` 目前**没有任何持久化依赖**（见 [index.md](../../spec/backend/index.md)），以上是落库时的规则，不是当前代码的现状。本次任务**不引入** MyBatis-Plus。
>
> 颗粒度边界：**「三层职责 + 可见性」写入 `directory-structure.md`**；具体用法（`Wrapper` 写法、分页、事务）留到落库时由重建的 `database-guidelines.md` 定义 —— 遵守 index.md「不要在功能落地前凭空补这些规范。子 agent 会照着规范写代码，写了不存在的模式比不写更糟」。

## 5. 兼容性影响面

已扫描确认下列均**不需要改动**：

| 项 | 原因 |
|---|---|
| `pom.xml` 的 `<groupId>com.vidsage</groupId>` | Maven 坐标与包路径解耦；Spring Boot 的基准包由启动类所在包决定，不由 groupId 决定 |
| `application.yml` / 测试 yml | grep 全仓库 `*.yml`/`*.xml`/`*.properties`：无任何 `com.vidsage` 包路径引用 |
| `@SpringBootApplication` + `@ConfigurationPropertiesScan` | 均未指定 `basePackages`，从 `com.vidsage` 起全量扫描，搬包后自动覆盖 |
| `vidsage.asr.provider` / `vidsage.asr.dashscope.*` | 配置键是字符串常量，与包名无关；`@ConditionalOnProperty` 的匹配值是 `cloud`/`local` |
| REST 路径与请求/响应体 | 由 `@RequestMapping` 注解值与 record 定义决定，record 内容不动 |
| `.idea/workspace.xml` | IDE 本地状态文件，不手改，由 IDE 自行刷新 |

扫描结论：**本次搬包在后端内部是纯粹的坐标变换，不触碰任何外部契约。**

## 6. 备选方案与否决理由

| 方案 | 结论 | 理由 |
|---|---|---|
| **扁平技术分层 + `model`/`provider` 领域二级包** | **采纳** | 满足「Controller 集中」诉求，同时不给 MVP-3 的 LLM 留混杂隐患 |
| 纯扁平（`provider/ASRProvider` 不分组） | 否决 | MVP-3 加 `LLMProvider` 后 `provider/` 根下接口与实现配置混杂，且 `provider/asr/dashscope/` 这类路径失去领域锚点 |
| 保持 package-by-feature | 否决 | 用户明确否决；且功能包增多后 HTTP 入口分散，正是本需求要解决的问题 |
| 完整 DDD 分层（`domain/` + `persistence/` + `repository/`） | 否决 | 现有类无行为无不变量，`domain/` 名不副实；落库（MyBatis-Plus）尚未动工，`persistence/` 是空目录，违反 R4 |
| 同时把类名规范化（如 `ASRProvider` → `AsrProvider`） | 否决 | `directory-structure.md` 有明确提示「跟随现有代码，不要顺手改正」；扩大改动面会让「纯搬包」这一可验证性质失效 |

## 7. 风险与回滚

| 风险 | 应对 |
|---|---|
| git 把搬包识别为「删除 + 新增」，diff 不可读 | 用 `git mv` 搬文件，保证 rename 识别；验收靠 `git diff --stat` 显示 `{old => new}` |
| 漏改 import 导致编译失败 | `mvn test` 兜底；先改完 package 声明再统一改 import，最后一次性编译 |
| 测试包未同步镜像，破坏 spec 的「测试包镜像对应」规则 | 验收标准显式检查测试包路径 |
| spec 路径引用漏改，子 agent 照空路径找参照实现 | 验收标准用 grep 显式检查 4 份 spec |
| MVP-3 子 agent 按旧约定建 `com.vidsage.llm` | 本任务在 MVP-3 恢复执行前完成，并同步其 `implement.jsonl` 与 `handoff.md` |

**回滚**：本次改动是单次原子提交，回滚点 = 重构前的 `master` HEAD。若 `mvn test` 不绿且短期无法修复，`git reset --hard <回滚点>` 即可完整还原（无中间状态、无数据库/配置副作用）。

## 8. 待写入 spec 的规则清单

`directory-structure.md` 重写后必须包含：

1. 目标结构树（第 1 节），移除 package-by-feature 全部表述
2. 顶层分包判据表（第 3.1 节），含每个包的「允许/禁止出现」
3. `model`/`provider` 需要领域二级包、`controller`/`service` 平铺的判据（第 3.2 节）
4. `model` / `domain` / `persistence` / `config` 判据表 + `domain` 升级触发条件（第 4 节）
5. 持久化三层职责与可见性规则（第 4.1、4.2 节）：Entity / Mapper / Repository 共存，受限的是可见性；不使用 MP 的 `IService`/`ServiceImpl`/`CrudRepository`。**必须写明具体用法（Wrapper、分页、事务）不在此文档**，留待落库时的 `database-guidelines.md`
6. 「目录随代码出现，不预建空目录」硬规则（R4），并说明空目录曾造成误导
7. 命名约定表（保留既有内容：接口不加 `I` 前缀、`ASRProvider` 全大写不改、`@Component` 而非 `@Service`）、契约类位置、测试包镜像对应
8. 全部「参照」路径改为新路径

另需更新 `index.md` 中对该文档的索引描述（当前「包结构、按功能分包、类与文件命名」含已废弃的「按功能分包」）。
