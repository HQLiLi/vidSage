# 设计：WorkspaceService 端口倒置

## 一、拆之前 / 拆之后

**今天**（一个类干三件事，其中一件是 I/O）：

```
application/media/WorkspaceService.java        @Service
  ├─ rootDir()          解析配置根 + FALLBACK_NAME 回退        ← 要出应用层
  ├─ ensureCreated()    Files.createDirectories + ISE + debug  ← 要出应用层（I/O）
  ├─ workspaceRoot()    rootDir().toAbsolutePath()             ← 要出应用层
  ├─ workspaceFor(String, WorkspaceKey)
  ├─ workspaceFor(MediaInput)   含 getMediaFileInfo → stat      ← stat 要留在应用层
  └─ getMediaFileInfo()  FileStateReader 采样                   ← 搬去 resolver
```

**之后**（三件，各回答一个问题）：

```
application/media/
  ├── port/WorkspaceDirectoryManager.java          ← 端口：key → 目录
  ├── WorkspaceKeyGenerator.java   @Component  ← 机制/协作件：MediaInput → key
  └── package-info.java                             （重写）

infrastructure/fs/
  └── NioWorkspaceDirectoryManager.java  @Component ← 实现：根解析 + 建目录（唯一碰 I/O 的地方）

application/media/WorkspaceService.java             ← 删除
```

调用点（4 个 Coordinator）：

```java
WorkspaceKey key = keyResolver.resolve(input);                    // ① MediaInput → key（含 stat、null 卫语句）
Path workDir = workspaceDirs.workspaceFor(input.dirLabel(), key); // ② key → 目录（解析 + 建目录）
```

> **为什么是两行不是一行**：Java 实参从左到右求值，写成
> `workspaceDirs.workspaceFor(input.dirLabel(), keyResolver.resolve(input))` 会让
> `input.dirLabel()` 先跑，`input == null` 时抛 NPE 而不是原来的 IAE「媒体输入不能为空」。
> 生产路径上 4 个 Coordinator 各自已有前置卫语句（`SubtitleCleanupCoordinator:130` 等），此分支不可达，
> 但单测断言它在——**保持两行写法，语义与今天逐字相同**。

## 二、构件契约

### 2.1 `application/media/port/WorkspaceDirectoryManager`（新增端口）

```java
public interface WorkspaceDirectoryManager {

    /** 解析工作区目录并确保存在（已存在时是幂等空操作）。 */
    Path workspaceFor(String label, WorkspaceKey key);

    /** 工作区根目录的绝对路径（不建目录、无副作用）。 */
    Path workspaceRoot();
}
```

- import 只允许 `domain/shared/model/WorkspaceKey` 与 `java.nio.file.Path`——**签名里零 media 词汇**。
- 端口归属判据（spec [端口归属判据](../../../spec/backend/directory-structure.md)）：技术词汇（工作区目录定位），兜底问「删掉它业务规则还成立吗」→ 成立 → application 端口。

### 2.2 `infrastructure/fs/NioWorkspaceDirectoryManager`（新增实现）

- `@Component`（spec：`infrastructure/` → `@Component`）。
- 注入 `config/WorkspaceProperties`（Q4 定：留 `config/`）。
- **不注入任何 application 类型**——这是本设计的核心约束（§四）。
- 逐字搬入：`rootDir()`（含 `WorkspaceDir.FALLBACK_NAME` 回退）、`ensureCreated()`。
- 命名：接口名 + 技术前缀 `Nio`（先例 `NioFileStateReader`，同为 `java.nio.file` 能力）。

### 2.3 `application/media/WorkspaceKeyGenerator`（新增协作件）

职责一句话：**把 `MediaInput` 解析成它自己的内容寻址 key**（今天 `WorkspaceService` 私有方法 `getMediaFileInfo` 那 6 行 + 一次 `getContentKey`）。

```java
WorkspaceKey resolve(MediaInput input) {
    if (input == null) {
        throw new IllegalArgumentException("媒体输入不能为空：请传 RemoteUrl / LocalPath / InWorkspace");
    }
    return input.getContentKey(fileStateOf(input));
}
```

- 注入 `FileStateReader`（application 端口，实现在 `infrastructure/fs/`）。
- 内部规则逐字照搬：只有 `MediaInput.LocalPath`（且 `value() != null`）才去采样，其余一律 `MediaFileInfo.NOT_APPLICABLE`。
- 「防陈旧校验」（`LocalPath.getContentKey` 里的 path 一致性检查）留在 domain，不动。
- stereotype `@Component`（机制/协作件档；2026-10-09 同日改判，原判 `@Service`，见 prd Q7）。落 `application/media/` 根。

## 三、调用点改动表

| 类 | 现在 | 之后注入 | 改动 |
|---|---|---|---|
| `AudioPreparationCoordinator` | `WorkspaceService`（:48/:59） | `WorkspaceDirectoryManager` + `WorkspaceKeyGenerator` | :107 改两行组合 |
| `SubtitlePreparationCoordinator` | `WorkspaceService`（:38/:53） | 同上 | :95 改两行组合 |
| `SubtitleCleanupCoordinator` | `WorkspaceService`（:51/:70） | 同上 | :137 改两行组合 |
| `SubtitleTranslationCoordinator` | `WorkspaceService`（:72/:96） | 同上 | :138、:187 改两行组合 |
| `VideoTaskService` | `WorkspaceService`（:76/:86） | **只** `WorkspaceDirectoryManager` | :313 直接调；:134/:239/:344 的 `workspaceRoot()` 改口 |
| `SubtitlePipeline` | `WorkspaceService`（:55/:72） | **只** `WorkspaceDirectoryManager` | :343 `workspaceRoot()` 改口 |

> 依赖按需加：`VideoTaskService` / `SubtitlePipeline` 用不到 resolver（它们只走显式 key 或根解析），不硬塞。

## 四、依赖方向（本次最要紧的一条）

```
application/media/port/WorkspaceDirectoryManager   ← 端口
infrastructure/fs/NioWorkspaceDirectoryManager     ──implements──▶ 唯一一条 import application 的行
application/media/WorkspaceKeyGenerator        ← 应用层协作件，只用端口，不认识实现
```

- `NioWorkspaceDirectoryManager` **不 import** `FileStateReader`。理由（prd 动机第 3 条）：spec 的窄口是「仅为 `implements` 出站端口而 import」，注入一个自己不实现的端口是本仓从未有过的形状，且一旦开口，「infrastructure 只实现端口」这条就失去可判性。
- `application` 零 `infrastructure` import（今天已清零，本次不得回退）。

## 五、行为保真清单（逐字保留，AC4 的对照依据）

| 项 | 现在的样子 | 去处 |
|---|---|---|
| IAE 文案 | `媒体输入不能为空：请传 RemoteUrl / LocalPath / InWorkspace` | resolver |
| ISE 文案 | `无法创建任务工作目录：{abs}；请检查 vidsage.workspace.root 是否存在且可写。原因：{msg}` | 实现 `ensureCreated` |
| debug 日志 | `任务工作目录: dir={}`（只打 `getFileName()`） | 实现 `ensureCreated` |
| 根回退 | `properties.root() == null` → `WorkspaceDir.FALLBACK_NAME` | 实现 `rootDir` |
| `workspaceRoot()` | 绝对路径、**不建目录**、同配置解析稳定 | 实现 |
| 目录名 | `WorkspaceDir.of(label, key)`（`sanitize(标签)-key`） | 不动（domain） |
| 副作用时点 | 卫语句先于一切 I/O；目录创建在解析之后 | 保持（两行写法） |

**预期差异栏：空。** 这是纯结构迁移，无行为变更；若执行期发现任何一格变了，按缺陷处理而不是记进差异栏。

## 六、测试重分方案

`WorkspaceServiceTest`（[现状](../../../../backend/src/test/java/com/vidsage/application/media/WorkspaceServiceTest.java)，9 例）拆到两个新测试类，**断言值一个字不放宽**：

| 原用例 | 新落点 | 理由 |
|---|---|---|
| `createsDirectoryAndNestedRootLazily` | `NioWorkspaceDirectoryManagerTest`（`infrastructure/fs/`，镜像包） | 只考建目录 + 根懒创建 |
| `explicitKeyEntryCreatesDirectoryWithSanitizedLabelAndGivenKey` | 同上 | 只考显式 key 入口 |
| `explicitKeyEntryFallsBackLabelForBlankOrNull` | 同上 | 标签回退（`WorkspaceDir` 公式，不动） |
| `workspaceRootResolvesWithoutCreatingDirectory` | 同上 | 根纯解析 |
| `rejectsNullInput` | `WorkspaceKeyGeneratorTest`（`application/media/`） | null 卫语句搬去 resolver |
| `localFileKeyIncorporatesSize` | 同上 | 考身份（`MediaInput` + stat），不考目录 |
| `missingFileYieldsPlaceholderIdentity` | 同上 | 同上（末句的 `workspaceFor` 改调端口） |
| `localFilePathNameIsUsedAsLabel` | 同上 | 断言的是 `input.dirLabel()` 进目录名——组合职责 |
| `sameLocalFileMapsToSameDirectory` | 同上 | 组合两件的稳定性 |

其余 6 个测试类只改构造夹具（`new WorkspaceService(props, fsr)` → `new NioWorkspaceDirectoryManager(props)` + `new WorkspaceKeyGenerator(fsr)`）：`SubtitlePipelineTest`、`VideoTaskServiceTest`、`SubtitleCleanupCoordinatorTest`、`SubtitlePreparationCoordinatorTest`、`AudioPreparationCoordinatorTest`、`SubtitleTranslationCoordinatorTest`。

> 执行期分工（沿用既有口径）：**主源码由项目所有者写**，测试由 AI 按其写出的代码改。

## 七、规范与文档订正清单

**顺序：先改判据、再搬代码**（沿用 `10-08-pipeline-executor-split` R9 的做法）。

| # | 文件 | 改动 |
|---|---|---|
| 1 | `spec/backend/directory-structure.md` `infrastructure/fs/` 行（:51） | 「本机文件系统**读数**适配」→「本机文件系统的**读数与定位/创建**适配」；禁止栏「写入策略」收窄表述，说明建目录归此、产物落盘仍归 `shared/fs/ArtifactFiles` |
| 2 | 同文件 依赖表（:129-131） | `application` 与 `infrastructure` 两行补 `config`（Q5） |
| 3 | 同文件 用例服务条（:72）与 服务表（:86） | `WorkspaceService` 的例子**清理**（不是「换名留任」——`WorkspaceKeyGenerator` 已归机制/协作件档，不再充当服务例子）；`-Generator` 后缀破例写明照旧，落机制/协作件档（参照 :278「破例不必回避，只需写明」） |
| 4 | 同文件 判据来历注（:101） | 「`WorkspaceService` 是唯一的横切例外」补日期修订（历史注改写为现状） |
| 5 | 同文件 端口归属判据节 | 补 `WorkspaceDirectoryManager` 为例（与 `TaskConcurrencyGate` 并列） |
| 6 | `application/media/package-info.java` | 重写：根留 resolver（`@Component`，机制/协作件档），`port/` 收两个技术端口；不再提 `WorkspaceService` |
| 7 | `infrastructure/package-info.java` | 分包清单补 `fs`（2026-10-08 已建桶但清单漏列，既有债） |
| 8 | `config/WorkspaceProperties.java` Javadoc（:16） | 「见 `WorkspaceService`」→ 新名 |
| 9 | `domain/shared/model/WorkspaceDir.java` Javadoc（:39） | 「`WorkspaceService.workspaceFor` 的两条路线」→ 新名（**存活引用，必须改**） |
| 10 | `domain/shared/model/WorkspaceKey.java` 类注释（:15-17） | 「**生成入口只有本类的两个静态工厂**」在新增 `WorkspaceKeyGenerator` 后不再成立——改成「**公式**的两个静态工厂在本类（`ofHash` / `ofDigest`），`WorkspaceKeyGenerator` 负责按输入取事实后调它们」。只动「唯一性」那半句，`ofHash` / `ofDigest` 的说明原样保留 |
| 11 | 同文件 构件说明表（:82-87）、「根留什么」（:173/:178）（2026-10-09 同日追加，随 prd Q7 改判） | 两档表改**三档**：新增机制/协作件档（`@Component`），`TaskSubscriptionRegistry` 与 `WorkspaceKeyGenerator` 收编进表；服务判据加限定「横切**编排**能力」（多处注入不升服务）；「根留该包的门面 / `@Service` 服务门面」松为「门面或横切协作件」 |

> `WorkspaceKey.java` 里两处「原 `WorkspaceService.shortHash`」是**历史注**（讲的是已吸收的东西从哪里来），按惯例保留；第 10 项改的是同一段落里的「唯一性」半句，不碰这两处历史注。

## 八、被否的方案（记录取舍）

| 方案 | 否掉的理由 |
|---|---|
| **原地不动，只把 `@Service` 改成 `@Component`** | 技术工具仍住应用层，判据的缝没补；第三个技术工具出现时重议 |
| **让 `NioWorkspaceDirectoryManager` 注入 `FileStateReader`** | 造出全仓第一处「infrastructure 注入自己不实现的 application 端口」，窄口失去可判性（Q2） |
| **不留 resolver，4 个调用点各自 stat** | 同一条身份规则写 4 遍（Q3 的另一端） |
| **留 application 侧串接壳（方案 B）** | 壳自己不做 I/O、只把两步串起来，等于把今天的 `WorkspaceService` 换个名留下；且读代码要跨一层才知道采过 stat（Q3） |
| **把 stat 折进 `FileStateReader`（加 `of(MediaInput)`）** | 让这个干净的文件读数端口认识 `MediaInput`；且调用点会写成 `input.getContentKey(fileStateReader.of(input))` 这种三层嵌套 |
| **`WorkspaceProperties` 跟实现走** | 与 3/4 先例相反，迁移面更大；`config/` 会空掉（它的首个类就是它） |
| **删掉 `MediaInput.LocalPath` 相关链路** | 那是刻意留的未接线路线（域注释 + `LocalFileMediaAcquirer` + 三个单测），删了是砍功能不是重构 |

## 九、风险与开放项

1. **（已定，2026-10-09；同日改判 stereotype，见 prd Q7）协作件名 `WorkspaceKeyGenerator`、stereotype `@Component`（机制/协作件档）、落 `application/media/` 根**。命名账：`-Generator` 全仓零占用（grep 命中的只有 JDK/Spring 的 `StringBuilder` / `ProcessBuilder` / `ChatClient.Builder`），本仓首例；项目所有者按「它产出的就是这个 key」定名。stereotype 初判 `@Service`（服务判据后半条「跨 media / subtitle 两上下文共用」），同日项目所有者改判 `@Component`：它是「一个事实 + 一条规则」的机械件，没有编排内容，「多处注入」不单独构成服务判据（先例 `TaskSubscriptionRegistry`，命名表「机制类 → `@Component`」既有档位）。落位不变，「根留 `@Service` 门面」措辞随松为「门面或横切协作件」；`-Generator` 与「服务类 `XxxService`」的后缀惯例不同，作为破例写进 spec。
   **配套必改**：`WorkspaceKey` 类注释那句「生成入口只有本类的两个静态工厂」随本次改写（§七 第 10 项）——不改就是留一句假话。
2. **`NioWorkspaceDirectoryManager` 的名字**：`Nio` 前缀跟随 `NioFileStateReader`；若所有者更认「Local」（先例 `LocalFileMediaAcquirer`），改名不改变结构。
3. **`WorkspaceDirectoryManager` 是新后缀 `-Manager`**：全仓首例，spec 命名表要登记（prd Q1）。
4. **两处写死 `vidsage.workspace.root` 的文案**（`VideoTaskService:159` 的上传失败提示、本类 ISE）指向配置键而非类名，**不改**。
