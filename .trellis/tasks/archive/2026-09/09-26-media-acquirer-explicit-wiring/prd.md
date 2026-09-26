# 媒体获取装配：改为 @Bean 显式产出 List

## Goal

在一个 `@Configuration` 里用 **`@Bean` 方法直接返回 `List<MediaAcquirer>`**，清单写成字面量；两个实现不再是 Spring bean（只在那个方法体里 `new` 出来）。`MediaAcquisitionService` **一行不改**。

**用户价值**：装配点集中一处、编译期可查；清单是字面量，扫包改不动它。

**业主决策留档（2026-09-26）**：
1. 在「只加护栏测试」「`@Bean` 登记显式」「List 完全显式」「`@Bean` 直接产出 List」几个形态间，业主选定**`@Bean` 直接产出 List**。
2. 业主明确本项目为**个人项目**：「别人改了那是别人的项目，跟我没关系」——故不为「第三方新增实现」场景设防。相关的机制与后果仍须**留档**（见 Background-7），但**不作为反对本方案的理由**。

## Background：已查证的事实

### 1. 现状装配路径

- 两个实现靠类上的 `@Component` 被扫描：`YtDlpMediaAcquirer.java:43`、`LocalFileMediaAcquirer.java:31`
- `MediaAcquisitionService` 靠 `@Service`（`:23`）成为 bean；构造器收 `List<MediaAcquirer>`，字段 `List.copyOf`
- 该 List 目前由容器按**元素类型**自动收集（`resolveMultipleBeanCollection`）
- **证据**：`spring-beans-7.0.9.jar`（Boot 4.1.1）`javap -c -p` 反编译，本次实测

### 2. ⚠️ 本方案凭什么能成立（核心机制，实测）

`resolveMultipleBeanCollection` 的行为（逐条来自反编译）：

1. 取**元素类型**：`descriptor.getResolvableType().asCollection().resolveGeneric(0)` → `MediaAcquirer`
2. `findAutowireCandidates(元素类型, ...)` —— **只找可赋给 `MediaAcquirer` 的 bean**；`List` 类型的 bean 不在候选之列
3. 候选**为空** → 返回 `null` → 落回**单 bean** 解析 → 此时按依赖类型 `List<MediaAcquirer>` 匹配 → **用你的 `@Bean List`**
4. 候选**非空** → 用那些候选拼成 List 返回 → **你的 `@Bean List` 被完全无视**

据此得到三种形态的实际来源：

| 容器里 `MediaAcquirer` 类型的 bean | 该注入点实际来源 | 你那个 `@Bean List` |
|---|---|---|
| **0 个**（本方案的形态：实现只在方法体里 `new`） | 落回单 bean 解析 → **用你的 List bean** | ✅ 生效 |
| 1 个（有人给某实现加了 `@Component`） | 容器收集（**1 项 = 那个游离实现**） | ❌ 被无视，**清单被整体替换** |
| 2 个（把两个实现也注册成 `@Bean`） | 容器收集（2 项，内容相同） | ❌ 被无视，成死代码 |

→ **结论：本方案要求两个实现不进容器**（不标 `@Component`、也不各自声明 `@Bean`）。这不是可选项，是生效前提。

### 3. 顺序

注入的清单是 `List.of(...)` 的**字面量**，顺序由书写顺序决定，与 `@Order` 无关。**本任务不引入 `@Order`。**

### 4. 波及面（实测）

全仓 grep `YtDlpMediaAcquirer|LocalFileMediaAcquirer`：

- 主代码里**没有任何其它类直接注入它们**（唯一命中是 `MediaAcquisitionService` 的注释文字）
- 测试对它们都是 `new` 出被测对象的纯单元测试（`YtDlpMediaAcquirerTest:50`、`LocalFileMediaAcquirerTest:173`），不起容器
- 注入 `List<MediaAcquirer>` 的地方**只有** `MediaAcquisitionService` 构造器；单独注入 `MediaAcquirer` 的地方**没有**

→ 摘 `@Component` 的波及面 = 这两个文件 + 新增配置类。

### 5. 规范冲突（2 条硬 + 5 处补空洞/修过期）

| # | 出处 | 原文要点 | 关系 |
|---|---|---|---|
| 1 | `provider-pattern.md:24` 四件套表·实现类行 | 「一个来源一个类，用 `@Component` + `@ConditionalOnProperty` 装配」 | **硬冲突**。§2b 只豁免了 `@ConditionalOnProperty`，未豁免「靠类上注解装配」 |
| 2 | `directory-structure.md:149` stereotype 表 | 「`provider/`→`@Component`」 | **硬冲突** |
| 3 | `directory-structure.md:42 / 74 / 128` | `config/` 是「**跨领域**装配」落点，且写着「**未来落点，当前未创建**」 | **空洞 + 过期**。`config/WorkspaceProperties.java` 已在其中且已入库；本任务是**跨实现**（非跨领域）装配 |
| 4 | `directory-structure.md:120-124` | 「目录随代码出现，不预建空目录」+「出现第一个跨领域 `@Configuration` 时才建」 | 建目录触发条件写窄了：`config/` 是被一个 `@ConfigurationProperties` 类先带出来的 |

**不再撞的一条**（相比「显式 `@Bean` 装配 Service」的形态）：`service/`→`@Service`。本方案**不动** `MediaAcquisitionService`，故不需要为 service 的 stereotype 开例外。

**已核查为「不冲突」的两条**：`quality-guidelines.md:216`（那管 Provider 自己 `new` 依赖）、`provider-pattern.md:195`（`@Primary`/`@Qualifier` 反模式）。

### 6. 前置阻塞项已解除（留档）

开工前工作树有 6 个在途/无关改动（业主审查代码时的注释白话化等），业主决定先提交到 `dev`，已于 2026-09-26 完成：`85a2f7e`（`application.yml` 的 `profiles.default: local`）、`f5e511c`（注释白话化 + 补两处 Javadoc）、`44ea3ec`（`.claude/settings.json` 权限）。特性分支已 fast-forward 到 `dev` 顶点，工作树干净。`dev` 领先 `origin/dev` 18 个提交，**推送需单独批准**。

### 7. 本方案不提供的东西（如实留档）

| 缺什么 | 后果 |
|---|---|
| **启动期 fail-fast** | 忘把新实现加进 `List.of(...)`（或漏 `new`）→ 该路线静默失效，服务起得来 |
| **对「实现被注册成 bean」的防护** | 一旦有实现进容器（补回 `@Component`、或给它加 `@Bean`），本方案的 `@Bean List` **静默失效**，服务改从容器收集（Background-2 表格第 2/3 行） |

两条都由 **R4 的断言**发现（不是阻止）。业主已知悉并接受——个人项目，不为第三方场景设防。

## Requirements

- **R1 新增配置类**：`com.vidsage.config.MediaAcquisitionConfiguration`（`@Configuration`），含**一个** `@Bean` 方法：

  ```java
  @Bean
  public List<MediaAcquirer> mediaAcquirers(YtDlpRunner ytDlp, ObjectMapper objectMapper,
                                            ExternalProcessRunner runner, ExternalToolDiagnostics tools,
                                            ExternalToolProperties properties) {
      return List.of(new YtDlpMediaAcquirer(ytDlp, objectMapper),
                     new LocalFileMediaAcquirer(runner, tools, properties));
  }
  ```

  返回类型 `List<MediaAcquirer>`（这就是注入点要的类型）；依赖走**方法参数**。

- **R2 摘掉两个实现的 `@Component`**（本方案生效的前提）：`YtDlpMediaAcquirer.java:17` import + `:43` 注解；`LocalFileMediaAcquirer.java:18` + `:31`。保留 `@Slf4j`。**不给它们声明 `@Bean`**（一进容器，R1 的 List 就失效）。

- **R3 `MediaAcquisitionService` 零改动**：构造器、`List.copyOf`、`@Service`、`select()`、类注释、所有方法体**一行不动**。它仍由扫包成为 bean，构造参数由 R1 的 `@Bean List` 满足。

- **R4 装配断言**：在既有 `VidSageApplicationTests`（已是 `@SpringBootTest`）内新增一个方法，断言两件事：
  1. 注入的 `List<MediaAcquirer>` 恰好是**两项**，且分别为 `YtDlpMediaAcquirer` / `LocalFileMediaAcquirer`（顺序即 `List.of` 顺序）——抓「漏加实现进清单」与「清单被替换」
  2. 容器里**不存在**任何 `MediaAcquirer` 类型的 bean——这是本方案生效的前提，一旦被破坏（有人补回 `@Component`）立即报警

- **R5 改写两个实现里「两个下载器同时驻留容器，故不加 `@ConditionalOnProperty`」注释**：本方案下**该句变成假的**（它们不再是 bean、不驻留容器）。改写后须说明：本类由 `config/MediaAcquisitionConfiguration` 的 `@Bean` 在方法体里 `new`，**不要给它加 `@Component` 或 `@ConditionalOnProperty`**（加了会让 R1 的清单失效）。

- **R6 规范改写（业主选定：消冲突 + 补空洞）**：按 design §六 清单改 `provider-pattern.md`（原计划 `:24`/`:73`/`:77`）+ `directory-structure.md` 5 处。**check 阶段另发现 2 处同类漏项，已补**：`provider-pattern.md:57`（§2b 正文「多个实现同时驻留」）与 `:209`（新增实现的检查清单第 2 条——**不补的话，照该清单新增一个「由输入类型分派」的实现会直接重现本任务要消除的问题**）。`:77` 仍待业主确认。

## Acceptance Criteria

- [ ] `config/MediaAcquisitionConfiguration.java` 存在，含 1 个 `@Bean` 方法，返回 `List<MediaAcquirer>`，方法体内为 `List.of(new ..., new ...)` 字面量
- [ ] 两个实现类**无 `@Component` 注解与 import 残留**：`grep -nE "^[[:space:]]*(import .*\.Component;|@Component)[[:space:]]*$"` 两个文件零命中。**注意**：注释正文里的 `{@code @Component}` 是 R5 要求的护栏文字（裸 `grep -n "@Component"` 会命中它），**不要为了凑「零命中」去删它**
- [ ] 两个实现类**没有被任何 `@Bean` 方法各自声明**（配置类里只有一个 `@Bean`）
- [ ] `MediaAcquisitionService.java` **零 diff**（含保留 `@Service`）
- [ ] 两个实现类里不再有「同时驻留容器，故**不加** `@ConditionalOnProperty`」原句；改写后含「由配置类的 `@Bean` new 出来」与「不要加 `@Component`/`@ConditionalOnProperty`」
- [ ] `cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test` 全绿；surefire 计数 = 基线 164 tests / 14 classes + 1 个新增方法
- [ ] **断言负向实证**：临时给 `LocalFileMediaAcquirer` 补回 `@Component` → 断言必须变红（证明它能抓「清单被替换」）→ 撤掉后复跑绿
- [ ] 启动冒烟：`GET /api/media/tools` 三工具 `available=true`；`POST /api/media/audio` 走本地文件路线成功产出音频；DEBUG 日志出现 `委托媒体获取: acquirer=LocalFileMediaAcquirer`
- [ ] `grep -n "当前未创建" .trellis/spec/backend/directory-structure.md` 零命中
- [ ] `directory-structure.md` 的 `config/` 条目允许「不属于任何单一实现的装配」，且写明 `config/` 的建目录触发条件不止 `@Configuration`
- [ ] `provider-pattern.md:24` 与 `directory-structure.md:149` 的 provider 格不再是无限定条件

## Out of Scope

- **不提供启动期 fail-fast**（见 Background-7；业主已知悉并接受）
- **不为「第三方新增实现」设防**（业主明确：个人项目，别人的事）
- **不引入 `@Order`**（清单是字面量，无排序语义）
- **不改 `MediaAcquisitionService` 的任何一行**（含 `@Service`）
- **不给两个实现声明 `@Bean`**（会使 R1 失效，见 Background-2）
- **不改 `MediaAcquirer` 接口**（`provider-pattern.md:37`：接口不加框架注解）
- **不推送 remote**（需单独批准）

## 测试影响

| 测试 | 处理 | 理由 |
|---|---|---|
| `YtDlpMediaAcquirerTest` / `LocalFileMediaAcquirerTest` | **零改动** | `new` 出被测对象的纯单元测试，不依赖容器 |
| `MediaAcquisitionServiceTest` | **零改动** | 手工 `List.of(...)` 构造，与装配方式无关 |
| `VidSageApplicationTests` | **新增 1 个方法** | 「清单内容」与「容器无 `MediaAcquirer` bean」两条不变量只能在此验证；放既有 `@SpringBootTest` 里，零新增容器启动 |

## 分支拓扑与依赖

- 分支 `feature/media-acquirer-explicit-wiring`，`base_branch` = **`dev`**；已 fast-forward 到 `dev` 顶点 `44ea3ec`，工作树干净（仅未跟踪的任务目录）
- 无前置任务依赖；前置状态依赖已解除（Background-6）
- ⚠️ `dev` 领先 `origin/dev` **18 个提交**，合并与推送均只在本地
