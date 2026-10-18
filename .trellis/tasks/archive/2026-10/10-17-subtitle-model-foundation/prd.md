# 字幕模型重构 1/8：数据层地基（五张新表 + 新领域 + 新仓储）

> 父任务 [10-15-subtitle-task-model-refactor](../10-15-subtitle-task-model-refactor/prd.md)；
> **终态设计一律引它的 [design.md](../10-15-subtitle-task-model-refactor/design.md)**（权威），本文只写本段的范围与验收，不复述终态、不另开一份设计。
> **本段对代码是纯增量**：只新增文件，不改任何调用方、不删任何旧件 —— 这是全程唯一的「新旧并存」窗口，且是单向的（新代码还没接上任何调用方）。既有文件只动两个：`schema.sql`（加五张表）与 `database-guidelines.md`（收尾同步现状，见需求 21）。

## Goal

把新模型的**数据层地基**一次落齐：`schema.sql` 新增五张表（`workspace` / `subtitle_task` / `task_run` / `subtitle_artifact` / `intermediate_file`），配五个领域类、随行的枚举与值对象、五个仓储端口、五套 MyBatis 三件套，以及覆盖这些映射与状态迁移的纯单测。收尾时**同步改掉 `database-guidelines.md` 里已失实的「建表契约」一节**（它整段照旧 `task` 表写，含与 N6 冲突的 `ENUM` 规则）。

做完这一段，S2 起只做「接线 + 删旧」：表结构、领域类、端口签名都不再回头改。

## Requirements

### 建表（DDL 逐字取自 design §1.1 / §1.2 / §1.3 / §1.4 / §1.6）

1. 五张新表全部落进 [schema.sql](../../../backend/src/main/resources/schema.sql)，DDL **逐字照抄** design 对应小节：列注释、`CONSTRAINT` 具名、`ENGINE` / `DEFAULT CHARSET` / 表注释一处不省。
2. 索引与约束命名一律按 design §2 的 N4 规则：`pk_` / `uk_` / `idx_` 带表名段、主键具名、唯一约束写成 `CONSTRAINT ... UNIQUE`（不用 `UNIQUE KEY` / `UNIQUE INDEX`）。索引名长度不超 64（当前最长 59）。
3. 枚举列一律 `VARCHAR(32)`（N6）：不用 `ENUM`、不用 `CHECK`，加值零 DDL。
4. 两个生成列（`task_run.active_flag` / `subtitle_artifact.active_flag`）按 design 原样建为 `GENERATED ALWAYS AS (...) STORED`。**互斥语义整个靠它们**，不得改成应用层判重或加锁。
5. `subtitle_artifact` 的四个文件列与四个哈希列齐备且**一一对应**（`segments` / `subtitles` / `context` / `terms` 各一对，`VARCHAR(255)` + `CHAR(64)`），不挤成一列。
6. `task` 与 `subtitle_keyword` 两张旧表**本段一个字节都不动**（S2 才处理）。`schema.sql` 头部补一段存量迁移说明：本版只是新增五张表、旧库直接跑即可；并预告 `subtitle_keyword` 的列名与类型会在 S2 变，届时旧库必须手工 `DROP TABLE subtitle_keyword` 一次。

### 领域类（落点照 design §3 的表）

7. 新增 `domain/workspace/`：`Workspace`（根）、`model/SourceType`、`model/SourceKey`、`package-info.java`。
8. 新增 `domain/subtitle/` 的四个类：`SubtitleTask` / `TaskRun` / `SubtitleArtifact` / `IntermediateFile`；随行枚举落 `domain/subtitle/model/`。
9. **领域实体带状态机**（design §3「`TaskRun` 状态机在这里」、§5「带行为的领域实体」、§4 的三处状态表）：`TaskRun` / `SubtitleArtifact` / `IntermediateFile` 各按 design §4 的「**每个值恰好一条路径**」落迁移方法，非法迁移抛 `IllegalStateException`；重建已落库的行只能走 `restore`。样板是 `domain/task/VideoTask`（私有构造 + 静态工厂 / `restore` + 迁移方法 + 私有守卫）；**迁移方法一律「目标态当动词」**（`succeed` / `supersede` / `confirmDeletion` / `revive`），不用 `mark*` 前缀 —— 那是本次新造的词，存量 `VideoTask` 用的是 `start/succeed/fail`；**私有状态守卫用 `validateXxx`**（细则见 `error-handling.md`）。**工厂名分三族**（口径见 `directory-structure.md`）：`of(实参…)` / `from(实参…)` —— 由形参构成，**必须带实参**（`Workspace` / `SubtitleTask` / `TaskRun` / `IntermediateFile`）；`createXxx(…)` —— 初态是半成品（只有 `SubtitleArtifact.createCreatingArtifact`，它产出的是文件列全 `null` 的 `CREATING` 占位行）；**凭空生成**类按来源 / 状态命名（`TaskNo.random()`）。**`SubtitleTask` 不再自带一份 task 号生成逻辑** —— 那段（`UUID.randomUUID()` 去连字符）搬进 `TaskNo` 作静态工厂，两边共用同一处（存量的 `VideoTask.newTaskNo` 留着不动，S2 随该类一起删）。
10. `Workspace` 的内容指纹就是 `durationMs` + `publishedAt` 两个可读列（C27），**不另拼一列串**；指纹的写入路径落成端口方法。
11. `SubtitleArtifact.lang` 用**空串**（不是 `null`）表示「无语言维度」（design §1.4 要点 1：MySQL 里 NULL 不参与唯一约束）。

### 端口与实现

12. 五个新端口落 `domain/*/port/`：`WorkspaceRepository` / `SubtitleTaskRepository` / `TaskRunRepository` / `SubtitleArtifactRepository` / `IntermediateFileRepository`。
    **是五个，不是四个** —— 「四个」是 C62 加 `intermediate_file` 之前的数，design §3 已订正（父 prd 的 S1 行与本任务 task.json 里的旧数同步订正）。
    `SubtitleKeywordRepository` 本段不动（换锚点在 S2）。
13. 端口方法**按 design §7 已写明的写路径配齐**，不留给 S2 追加。方法清单与逐条依据见 [implement.md](./implement.md) 步骤 5。
14. `TaskRunRepository` 抢互斥那条 INSERT **返回 `Optional`**：撞 `uk_active_run` 时返回空，由调用方（S2）翻成 409。仓储内 catch `DuplicateKeyException` 后不外抛 —— 撞键是预期分支不是异常，且不跨 Spring 代理边界就不会把事务标成 rollback-only（C63 记的那个坑）。
15. 五套 MyBatis 三件套落 `infrastructure/persistence/{entity,mapper,repository}/`，命名与可见性照 [directory-structure.md](../../../.trellis/spec/backend/directory-structure.md)「持久化三层」：`XxxEntity`（`@TableName` + `@Getter/@Setter`，仅 persistence 内可见）、`XxxMapper extends BaseMapper`、`MybatisXxxRepository implements` 对应端口（`@Repository`）。
16. 枚举列**按名映射**（实体字段直接用 Java enum）；值对象 ↔ String 列的转换收敛在 Repository，Mapper / SQL / Entity 不感知。
17. 表数从 2 涨到 7 也**不另分领域二级包**（三个子包平铺十几个类）—— directory-structure.md 的现行口径是「表再多时再议」，本段不动它。

### 测试

18. 每个 Repository 一个纯单测（Mockito mock Mapper，**不连真库**），覆盖：实体 ↔ 领域对象逐字段双向转换、`insert` 回填自增 id、查询条件、`Optional` 空/非空两条路径。样板见 `MybatisVideoTaskRepositoryTest`（`LambdaQueryWrapper` 的列名解析要在 `@BeforeAll` 手工 `TableInfoHelper.initTableInfo`）。
19. **三个**带状态机的领域实体各一个单测（`TaskRun` / `SubtitleArtifact` / `IntermediateFile`），按 design §4 的表**逐值覆盖**：每条合法迁移都验「终态 + 时间戳落位」，非法迁移抛 `IllegalStateException`。另给 `SourceKey` 一个值对象单测（不变量：非空白、不超列宽）—— 先例是 `domain/shared/model/` 下的 `WorkspaceKeyTest` / `SourceUriTest`。
20. 既有测试**一条不改**、全绿。

### 规范同步（收尾做，改的是 `.trellis/spec/`，按 v5 要开 feature 分支）

21. 改写 [database-guidelines.md](../../../.trellis/spec/backend/database-guidelines.md) 的「建表契约（现状：`task` 表）」一节为现状：枚举列 `VARCHAR(32)`（不是 `ENUM`）、具名约束规则、生成列做「部分唯一」、时间列不再「四件套」且 `updated_at` 与所属 UPDATE 同句刷、表清单从 1 张变六张（`task` 由 S2 删）、`source_uri` 的脱敏红线保留。**spec 是现状文档，直接重写、不留旧说法**。
22. 这一节**不抄 DDL 全文、不抄 `C*` 编号**，指向 design 对应小节即可 —— 别把它写成第二份 design。

## 约束

- 编号引用（`C*` / `N*` / `W*` / `D-*`）的定义见父任务 [handoff.md](../10-15-subtitle-task-model-refactor/handoff.md)；实施以 design.md 的收敛版为准。
- **脱敏红线**：`source_uri`（URL 原文 / 上传文件原始名）不得写日志 —— 本段任何日志、异常消息、测试断言都不得打印它的值。
- 本段**不引入 `@Transactional`**：写集（W1 / W5 / W5′）的事务边界属于 S2 起的编排层，仓储只发单语句。
- 本段**不造任何只为验收存在的临时件**：端口方法要么有 design 的写路径背书，要么不写。
- `domain/` 层不依赖 `shared/`，不加会改变行为的框架注解（`@Schema` 的落位另有未决口径，本段不碰）。

## 范围外（明确交给后面哪一段）

| 不做 | 交给 | 为什么 |
|---|---|---|
| `MediaIdentityResolver`（C7–C12 的 URL 规范化策略端口与实现） | **S2** | 父 prd 的任务地图把「身份键」归 S2；本段只落 `SourceKey` 值对象本身 |
| `subtitle_keyword` 换锚点（`workspace_key CHAR(12)` → `task_id BIGINT`）、**新增 `updated_at` 列**与它的写入点 | **S2** | 三件事是同一件事：该表既改列名又改类型，只能手工 `DROP` 后重建，列由那次重建带进来（design §1.5 定案「加」），写入点也落在同一次改动的 upsert 里 |
| `TaskNo` 搬去 `domain/subtitle/model/` | **S2** | N5：先落定删除集 → 删 → 再对存活集改名。本段复用 `domain/task/model/TaskNo` |
| `TaskStatus` / `TaskInputType` 旧枚举退场 | **S2** | 本段直接新建终态名的枚举，与旧枚举并存一段 |
| 旧 record 让名（`SubtitleArtifact` → `SubtitleStageOutput`、`AudioArtifact` → `AudioStageOutput`） | **S2** | 本段的新类直接用 `domain/subtitle/SubtitleArtifact` 这个名字，与旧 record 同名不同包并存 |
| `task` 表删除；`VideoTask` / `TaskSessionRegistry` / `TaskStageEvent` 退场 | **S2** | 都是「删旧」那一段的活 |
| `@Transactional` 装配、SSE、HTTP 契约（`/artifacts` / 下载端点 / 对账端点） | S2 起 | 本段没有任何对外面 |

## 验收标准

- [ ] **AC1 表齐且逐字对版**：起服务后 `SHOW CREATE TABLE` 五张新表，列、类型、注释、索引名、约束名与 design §1 逐字一致（含两个生成列、四列 `*_file_name` 的 255 宽、四列 `*_sha256`）。
- [ ] **AC2 旧件几乎零改动**：`git diff --stat` 里除新增文件与下面这份白名单外**没有别的改动**；旧类的引用一处未动。
  白名单（全部是注释 / 规范文本，零逻辑改动）：`schema.sql`、`domain/task/model/TaskNo.java`（加 `random()` 静态工厂 + 改 javadoc，**不碰任何调用方**）、`application.yml`（注释清理）、`.trellis/spec/backend/database-guidelines.md`（步骤 8）、`.trellis/spec/backend/directory-structure.md`（工厂判据）、`.trellis/spec/backend/error-handling.md`（守卫命名）。
- [ ] **AC3 领域类齐**：`domain/workspace/` 与 `domain/subtitle/` 的**五个类 + 七个枚举 + 一个值对象（`SourceKey`）**俱在，落点与 design §3 的表一致。
- [ ] **AC4 端口齐**：五个新端口的方法集逐条对上 implement.md 步骤 5 的清单（每条都有 design §7 的依据），无多无少。
- [ ] **AC5 测试**：五个 Repository 单测 + **三个**带状态机实体的迁移单测 + 一个 `SourceKey` 值对象单测（design §4 只有三处状态：`task_run` / `subtitle_artifact` / `intermediate_file`；`SubtitleTask` 无状态列、`Workspace` 只有 `updateFingerprint` 一个变更入口，两者不单独开测试类，随各自的 Repository 单测一起验）全绿；全量测试数与开工前实测基线相比**只增不减、零失败**。
- [ ] **AC6 旧契约回归**：`clean test-compile` 过；`spring-boot:run` 起得来（`CREATE TABLE IF NOT EXISTS` 幂等）；提交一个 URL → 202 → 旧形状 `GET` → 跑完（父 prd 给 S1 定的验证手段，验的是「新地基没碰坏旧栈」）。
- [ ] **AC7 规范同步**：`database-guidelines.md` 的「建表契约」一节改完自洽 —— 逐句对着已落地的 `schema.sql` 与 design §1/§2 核，**里面不再出现 `ENUM` 这条规则**；且这一节没被写成第二份 design（无 DDL 全文、无 `C*` 编号）。

## Notes

- **测试基线**：父任务记的 569 是**静态数**（`grep -c '@Test'`），另一口径记过 570。开工前**重量一次**（先 `rm -rf backend/target/surefire-reports` 再跑全量），以实测数为准 —— `surefire-reports/` 会留陈旧报告，数出来的会偏大。
- **分支**：本段既碰代码（`schema.sql` + 新 Java 文件）又碰规范（需求 21），按 git-workflow v5 **两条都要开 feature 分支**，不能直接在 `dev` 上做。当前分支是 `dev`，从它切。
- **S8 的 spec 更新范围**：父任务地图把「spec 更新」排在 S8，本段提前吃掉其中的「建表契约」一节；S8 仍负责其余部分（全量收口时按当时现状复核一遍）。
- **环境命令**（判编译、跑测试、起服务、推送代理）见 [handoff.md §10](../10-15-subtitle-task-model-refactor/handoff.md)。判编译一律 `clean test-compile` —— `mvn compile` 的增量编译会给假绿。
- **本段的欠账清单**（S1 结束时哪些东西还没到位、交给哪一段）在 [implement.md](./implement.md) 末尾，实施完必须逐条核对。
- 本段是 8 段里**文件数最多、但风险最低**的一段：约 40–45 个新文件（19 个领域侧 + 15 个持久化侧 + 9 个测试类），零调用方改动，正确性全靠单测与 `SHOW CREATE TABLE` 对版。
- **开工前有一道门**：`implement.md` **步骤 0** 要求先把预期目录结构给项目所有者过一眼，点头之后才开分支、才写第一行代码（先例：2026-10-06 改包结构时同样先看目录结构）。
