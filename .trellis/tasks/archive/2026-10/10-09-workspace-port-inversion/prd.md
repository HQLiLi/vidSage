# WorkspaceService 端口倒置：工作区定位与创建移出应用层

## Goal

把 `WorkspaceService` 拆成三件——**目录能力**（根解析 + 建目录）抽成 application 端口、实现落 `infrastructure/fs/`（照 `FileStateReader` 先例）；**媒体身份解析**（`getMediaFileInfo` 那段 stat）独立成 application 协作件；调用点改为组合两者。**行为逐格不变**，同步订正 spec 与 `package-info` 的落位口径。

## 动机

1. `WorkspaceService` 零状态、零业务规则、零编排——实质是「根目录解析 + 建目录」的**技术工具**，却住在 application 并以 `@Service` 示人。spec 自己把它记成「全项目唯一的横切例外」（[directory-structure.md:101](../../../spec/backend/directory-structure.md)），例外留在原地，判据就永远有洞。
2. 它自己做 I/O（`Files.createDirectories`），与应用层「允许出现」清单（用例编排、读模型、应用消息、应用异常、中立订阅机制、本层端口）不符。
3. 直接搬破 `application → infrastructure` 禁线；而让**实现类注入 `FileStateReader`** 又会造出全仓第一处「infrastructure 注入一个自己不实现的 application 端口」——spec 的窄口只开给 `implements 出站端口`。所以 stat 必须从实现里摘出去，这不是可选项。

## 背景与已确认事实（2026-10-09 对照源码核实）

- `WorkspaceService` 三个 public：`workspaceFor(MediaInput)`（解析 + 建目录）、`workspaceFor(String label, WorkspaceKey key)`（解析 + 建目录）、`workspaceRoot()`（只解析）。私有 `rootDir()` / `ensureCreated()` 是仅有的两块自有逻辑。
- 目录名公式（`sanitize(标签)-key`）与身份 key **早已下沉 domain**（`WorkspaceDir.of`、`WorkspaceKey`、`MediaInput.getContentKey`），本类只是调用方。
- **`MediaInput.LocalPath` 在生产代码里零构造点**：全仓 `new MediaInput.LocalPath(...)` 只出现在测试里；`MediaController` 只做 `/tools` 诊断，不产输入。故 `getMediaFileInfo` 今天恒返回 `NOT_APPLICABLE`。该变体是刻意留的未接线路线（域注释 + `LocalFileMediaAcquirer` 整类 + `WorkspaceServiceTest` 三例），**不删、只搬**。
- `infrastructure → application` 现有 4 处 import，全部是「自己实现的那个端口」或「该端口的出参类型」（`FileStateReader` / `TaskConcurrencyGate` / `ToolDiagnostics` + `ToolDiagnosticReport`）。让实现类注入 `FileStateReader` 会是第 5 处、且是**第一处注入被消费端口**。
- `infrastructure → config` 的 import **已有 3 处**（`SemaphoreTaskConcurrencyGate→TaskProperties`、`LlmSubtitleCleanser→SubtitleCleanupProperties`、`LlmSubtitleTranslator→SubtitleTranslateProperties`），但**依赖表两行都没列 `config`**（表失实）。
- 注入点 6 个、受影响测试类 7 个（详见 design.md）。

## 已定边界（本次会话确立，不再重开）

1. **走端口倒置**（方案 A）：端口留 application，实现进 infrastructure。不选「只改 stereotype 原地不动」。
2. **端口名 `WorkspaceDirectoryManager`**，实现 `NioWorkspaceDirectoryManager`（spec：实现类 = 接口名 + 技术前缀，先例 `NioFileStateReader`）。
3. **`WorkspaceProperties` 留 `config/`**（跟 3/4 主流先例；`infrastructure → config` 是既有做法）。
4. **顺带补依赖表**：`application` 与 `infrastructure` 两行加 `config`。
5. **stat 抽成独立协作件**，调用点各拿两个依赖（`manager` + `resolver`），**不在调用点重写 stat 逻辑**。
6. 端口方法集照搬：**两个方法**（`workspaceFor(label, key)` / `workspaceRoot()`），签名里零 media 词汇。`workspaceFor(MediaInput)` 这个重载**随类消失**，其职责由调用点组合 + resolver 承接。

## Requirements

- **R1 端口新增**：`application/media/port/WorkspaceDirectoryManager`，两个方法照搬现状语义与 Javadoc 口径。
- **R2 实现新增**：`infrastructure/fs/NioWorkspaceDirectoryManager`（`@Component`），注入 `config/` 的 `WorkspaceProperties`，**不注入任何 application 端口**；`rootDir()`（含 `WorkspaceDir.FALLBACK_NAME` 回退）与 `ensureCreated()`（含 ISE 文案 + debug 日志）逐字搬入。
- **R3 stat 抽离**：新增协作件 `application/media/WorkspaceKeyGenerator`（~~`@Service`~~ → `@Component` 机制/协作件档，2026-10-09 同日改判，见 Q7）承载 `getMediaFileInfo` 那段（`instanceof LocalPath` → `FileStateReader`，其余 `NOT_APPLICABLE` → `input.getContentKey(info)`），含原 null 卫语句（文案逐字）。
- **R4 调用点改造**：6 个注入点按需换端口 / resolver；`WorkspaceService` 类删除。
- **R5 行为逐格不变**：目录名、懒创建、根回退、异常文案、日志文案、文件系统副作用时点全部不变（差异栏见 design.md，预期为空）。
- **R6 规范订正**：spec 与 `package-info` 按 design.md 的清单落地；顺序**先改判据、再搬代码**。
- **R7 依赖规则不被本次改动破坏**：`application` 零 `infrastructure` import；`NioWorkspaceDirectoryManager` 不 import 任何 application 包。

## Acceptance Criteria

- [ ] **AC1 结构**：`application/media/` 下不再有做 I/O 的类；端口与实现按 design.md 落位命名（`WorkspaceDirectoryManager` / `NioWorkspaceDirectoryManager`）。
- [ ] **AC2 类删除**：`WorkspaceService` 全仓零命中（main + test + spec + `package-info`）；历史注（`WorkspaceKey` 里「原 `WorkspaceService.shortHash`」）按惯例保留。
- [ ] **AC3 依赖方向**（grep 证据落 `evidence/`）：
  - `application/**` 零 `import com.vidsage.infrastructure.`；
  - `NioWorkspaceDirectoryManager` 的 import 里**没有** `com.vidsage.application.`；
  - `application/media/port/WorkspaceDirectoryManager` 的 import 只有 `domain`（`WorkspaceKey`）。
- [ ] **AC4 既有测试**：7 个受影响测试类改构造/注入后**断言一个字不放宽**（断言值不变），全绿。
- [ ] **AC5 新增测试**：端口实现的四条（懒创建 / 显式 key 落盘 / 标签回退 / 根纯解析）与 resolver 的身份路线断言按 design.md 重分，全绿。
- [ ] **AC6 规范订正**：design.md 清单逐条落地；`infrastructure/fs/` 一格不再自述「只读」；依赖表不再漏 `config`。
- [ ] **AC7 构建**：`mvn clean test` 全绿（改名/改结构后必须 `clean`）。

## Out of scope

- **`MediaInput.LocalPath` 的接线**（今天零生产构造点）：不删变体、不补生产者、不动 identity 规则。
- **`WorkspaceService` 的两个重载合并**（`10-07-shape-refactor` 记的「鸡生蛋洞」）：本次照搬三条入口，不合并。
- **状态迁移 × 广播收口三角**（`10-08-pipeline-state-broadcast`）：仍在 planning，本次不碰。
- **`shared/` / `config/` / `web/` 落位重议**；**Javadoc 全局重构**；**`@Schema` 落位口径**。
- **`FileStateReader` 端口契约变更**：不把 stat 折进它（那样会让这个干净的端口认识 `MediaInput`）。

## Key decisions

- **Q1（2026-10-09）端口名 `WorkspaceDirectoryManager`**（项目所有者定）。命名账：全仓 `*Manager` 零占用（`-Handler` 仅 `ApiExceptionHandler`、`-Helper` 仅 MyBatis 自带），但也是本仓**第一个** `-Manager` —— 现有 9 个端口全在 `-Provider`/`-Repository`/`-Reader`/`-Acquirer`/`-Gate`/`-Diagnostics`/`-Listener` 与 `-er/-or` 家族里。用新后缀的代价：spec 的端口命名表要登记，否则「Manager」会变成万能后缀。
- **Q2（2026-10-09）stat 抽出去，不让实现类注入 `FileStateReader`**。理由见「动机」第 3 条——窄口只给 `implements`，注入被消费端口是这个仓库从没有过的形状。
- **Q3（2026-10-09）调用点各拿两个依赖（方案 A），不留 application 侧串接壳**。理由：每个构件只回答一个问题（resolver 答「这是哪个 key」、manager 答「key 落在哪个目录」），编排摊在调用点上；留壳会让 FS 拆分白做一半、且那层壳就是今天的 `WorkspaceService` 换名。
- **Q4（2026-10-09）`WorkspaceProperties` 留 `config/`**。3/4 先例（`TaskProperties` / `SubtitleCleanupProperties` / `SubtitleTranslateProperties` 都在 `config/`），且 `infrastructure → config` 已存在。
- **Q5（2026-10-09）顺带补依赖表的 `config`**。表漏列但实际已 6 处 import（application 3 + infrastructure 3），本任务反正要在同一张表附近动刀。
- **Q6（2026-10-09）协作件名 `WorkspaceKeyGenerator`、stereotype ~~`@Service`~~、落 `application/media/` 根**（项目所有者定）。命名账：`-Generator` 全仓零占用，本仓首例。**配套必改**：`WorkspaceKey` 类注释那句「生成入口只有本类的两个静态工厂」随本次改写——不改就是留一句假话（design.md §七 第 10 项）。（stereotype 同日改判 → Q7）
- **Q7（2026-10-09，同日改判 Q6 的 stereotype 半句）stereotype 走 `@Component`（机制/协作件档），落位 `application/media/` 根不变**（项目所有者定）。理由：它是「一个事实 + 一条规则」的机械件（stat 采样 → 委托 domain 的 `getContentKey`），没有编排内容；「被多处方共用」单独不构成服务判据，否则任何小助手都升服务、档位失去区分度。先例 `TaskSubscriptionRegistry`（机制类 → `@Component`，命名表既有档位）。**随行订正**：构件说明表两档改三档（`TaskSubscriptionRegistry` 收编进机制档），服务判据加限定「横切**编排**能力」；「根留 `@Service` 门面」松为「门面或横切协作件」（落位随行项）；`-Generator` 破例登记照旧；design §七 追加 #11。
