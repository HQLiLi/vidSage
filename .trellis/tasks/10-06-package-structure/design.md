# 设计：包结构整理（角色轴）

## 1. 决策（2026-10-06，用户选定）

| 决策 | 选定 | 被否掉的备选与理由 |
|---|---|---|
| 拆包轴 | **A 角色轴**：桶 = 该层的角色词 | B 子主题轴（按业务环节分、端口与数据同包）——判据不机械：`domain/task` 会拆出只有 1 个文件的 `artifact/`，且 `TaskArtifactScan` 算「产物环节」还是「任务词汇」本身要争论 |
| 阈值 | 无下限，碎包可接受 | 「1–3 类平铺」的现行阈值（spec `:162`）——用户口径是「分好比杂乱好，后面添加可以知道往哪加」 |
| 范围 | 全层扫一遍 | 只动 `domain/`——用户要的是全层口径统一 |

## 2. 通用规则

1. **桶名 = 该层已有的角色词**（不新造词；spec 的分层归属判据已定义各层的角色）
2. **根留该包的「门面」**：domain 留主类型（状态机/聚合），application 留 `@Service` 服务门面 + 应用消息；没有门面就只剩 `package-info`（合法）
3. **有成员才建桶**，不建空目录（某角色在该层没有成员就不出现该目录）
4. **spec 已定义为「一件」的组合不再套桶**：`infrastructure/provider/<能力域>/<实现短名>/`（该实现的适配器 + 专用 `*Properties` + 私有助手）、`infrastructure/persistence/`（entity/mapper/repository 三件套）、`web/`（controller/dto/sse 三角色）、`shared/<域>/`（纯静态助手单一角色）
5. **新增类怎么落位**：先判该类的角色（依据 spec「分层归属判据」与「构件说明」），角色决定桶路径——同一层内桶路径固定

### 逐层落位表

| 层 | 桶 | 根留什么 |
|---|---|---|
| `domain/<上下文>/` | `port/`、`policy/`、`model/`（模型多时按子主题再分一层） | 主类型（状态机/聚合） |
| `application/<上下文>/` | `port/`、`pipeline/`、`query/`、`exception/`、`subscribe/` | `@Service` 服务门面 + 应用消息 |
| `config/` | 不拆（spec 把 `@Configuration` 与 `@ConfigurationProperties` 定义为同一职责「跨领域配置与装配」） | 全部 |
| `infrastructure/persistence/` | 已有 entity/mapper/repository | — |
| `infrastructure/process/` | 不拆（判断点 J3） | 全部 |
| `infrastructure/provider/**` | 不拆（规则 4，四件套一件） | 全部 |
| `shared/<域>/` | 不拆（规则 4，单一角色） | 全部 |
| `web/` | 已有 controller/dto/sse | — |

## 3. 逐包归属（before → after）

### `domain/task`（8 → 4 桶）

```
domain/task/
├── VideoTask.java              ← 主类型留根（状态机 + 两处查询谓词）
├── package-info.java           （重写）
├── model/   TaskStatus、TranslationStatus、TaskInputType、TaskNo
├── policy/  TaskArtifactScan
└── port/    VideoTaskRepository
```

### `domain/media`（8 → 2 桶，根只剩 package-info）

```
domain/media/
├── package-info.java           （重写）
├── model/   AcquiredMedia、AudioArtifact、AudioSpec、AudioContainer、MediaInput
└── port/    AudioExtractor、MediaAcquirer
```

### `domain/transcription`（6 → 2 桶）

```
domain/transcription/
├── package-info.java           （重写）
├── model/   AudioSource、TranscriptionCommand、TranscriptionResult、TranscriptionSegment
└── port/    ASRProvider
```

### `domain/shared`（6 → 2 桶）

```
domain/shared/
├── package-info.java           （重写）
├── model/   SourceUri、WorkspaceDir、WorkspaceKey、StageEvent
└── port/    ProgressListener
```

### `domain/subtitle`（22 → 3 桶，`model/` 二层）

```
domain/subtitle/
├── package-info.java           （重写：现在是逐类词汇索引，拆包后失效）
├── port/     SubtitleCleanser、SubtitleTranslator、SubtitleContextSummarizer、TermLookupProvider
├── policy/   Bcp47Language、TermVerification、CheckpointPolicy
└── model/
    ├── cleanup/      SubtitleTrack、CleanedSubtitle、CleanseResult
    ├── translation/  TranslatedTrack、TranslatedSubtitle、TranslatedSegment、TranslateResult
    ├── term/         TermLookupCommand、TermLookupResult、VerifiedTerm、VerifiedTerms
    └── common/       SubtitleArtifact、SubtitleRoute、SubtitleContext
```

### `application/*`

```
application/task/                       application/media/
├── VideoTaskService.java   ← 服务门面  ├── WorkspaceService.java  ← @Service 横切，留根
├── TaskProgressMessage.java ← 应用消息  ├── package-info.java
├── package-info.java                   └── pipeline/  AudioPreparationStage、MediaAcquisitionStage
├── pipeline/  VideoTaskPipeline
├── query/     （不动：TaskSnapshot、TaskArtifact、TaskArtifactFile）
├── exception/ （不动：TaskConflictException、TaskNotFoundException）
└── subscribe/ （不动：TaskSubscriptionRegistry、Subscription）

application/subtitle/                   application/transcription/      application/diagnostics/
├── package-info.java                   ├── package-info.java           ├── ToolDiagnosticReport.java ← 出参留根
└── pipeline/  3 个 Stage 类            └── pipeline/  TranscriptionStage  ├── package-info.java
                                                                        └── port/  ToolDiagnostics
```

### `domain/task/policy` 新增 `TaskStageEvent`（2026-10-06 用户追加，见 prd R7）

```
domain/task/policy/
├── TaskArtifactScan.java   （既有）
└── TaskStageEvent.java     ← 新增：从 VideoTask 的持久化状态渲染 StageEvent 的纯函数政策
```

- 承载原本内联在 `VideoTaskService` 的两个包级私有 static：`stageEventOf(VideoTask)`（主状态渲染，SUCCEEDED 且翻译子状态存在时转派）与 `translationStageEventOf(VideoTask)`（翻译子状态渲染）
- 形态照 `TaskArtifactScan`：`public final class` + 私有构造器 + static 方法；判据（分支、进度值、面向用户的中文文案）**逐字搬**，行为零变化
- 可行性已核实：入参 `VideoTask`、出参 `StageEvent` 都是 domain 类型；`domain/task → domain/shared` 方向既有（`VideoTask.java:3` 已 import `WorkspaceKey`）；`StringUtils` 在 domain 已有 4 处先例（不引入新依赖种类）
- `VideoTaskService` 的 `snapshot` 与 `VideoTaskPipeline` 的两处终态广播改调新类；被删的两个 static 在 `VideoTaskService` 的 Javadoc 交叉引用一并改指

### 不动的包

`config/`、`infrastructure/persistence/**`、`infrastructure/process/`（J3）、`infrastructure/provider/**`、`shared/**`、`web/**`。

### 测试包镜像

跟随主包逐批移动，共约 20 个测试类受影响：`domain/{subtitle→policy/、model/term/、model/}`（4 个）、`domain/{media,shared}/model/`（5 个）、`domain/task/`（`VideoTaskTest` 跟随 `VideoTask` 留根，不动）、`application/{media,subtitle}/*StageTest → pipeline/`（5 个）、`application/task/VideoTaskPipelineTest → pipeline/`、`application/media/WorkspaceServiceTest` 留根。其余测试类只需改 import。

## 4. 判断点（请审阅时确认或否决）

- **J1** `application/subtitle`（3 个 Stage）与 `application/transcription`（1 个 Stage）是单角色包，仍**建 `pipeline/`**——为的是落位规则统一（「新增阶段类 → `pipeline/`」），代价是 `application/transcription/pipeline/` 只有 1 个类。**若你否决，这两个包的 Stage 类留在根，落位规则变成「只有混放才拆」**
- **J1.5（2026-10-06 实施中发现，已解决）** `application/task/pipeline/` 一度被阻断：`VideoTaskPipeline` 移出包后访问不到 `VideoTaskService` 里两个包级私有 static（`stageEventOf` / `translationStageEventOf`），Java 包级私有不跨子包。曾备选「放宽为 public」与「不拆该包」，**用户裁定改由下沉 domain 解决**（R7）——判据本就纯函数（入参出参都是 domain 类型），下沉后是 public API，根与桶都能调，无需放宽任何可见性。这条同时印证了设计原则：跨包共享的判据应当住在它所属的层，而不是靠放宽可见性留在原处
- **J2** `application/diagnostics` 的出参 `ToolDiagnosticReport` **留根**（与 `application/task` 根留应用消息同判据）；只有端口进 `port/`
- **J3** `infrastructure/process/`（4 类：`ExternalProcessRunner` / `ProcessOutcome` / `ProcessToolDiagnostics` / `ExternalToolProperties`）**保持平铺**：spec 把它定义为「通用机制」一件，且「机制 / 出参 / 诊断实现 / 配置」四者的角色边界本身要争论（与方案 B 的毛病同源）。**若你要拆，判据就是这四桶**
- **J4** `domain/media`、`transcription`、`shared`、`subtitle` 没有主类型，根**只剩 `package-info`**——空根合法，判据是「没有门面就空着」

## 5. 兼容性与风险

- **无行为、契约、数据变化**：只改 `package` 声明、`import` 与文件位置；类名、方法签名、注解、日志文案一律不动（R7 的下沉例外：判据逐字搬，面向用户的中文文案、进度取值、分支顺序必须一字不差）
- **Spring 装配不受影响**：组件扫描自 `com.vidsage` 起，包移动在扫描根之下；代码与 resources 里**无硬编码包名**（`@MapperScan`/`basePackages` 零处）；`logging.level.com.vidsage: DEBUG` 按前缀生效
- **陈旧产物是本任务的头号坑**（包改名后旧包名的 class 与旧 surefire 报告都不会被覆盖）：验证必须 `mvn clean test`，量测试数前先删 `target/surefire-reports`。排查「源码里没有却报它导致的错」时先看 `target/classes/`
- **Javadoc `{@link}`**：跨包引用用的是类型名，移动文件不破链；但 spec 与 `package-info` 里按路径写的引用要人工对账
- **文档面**：7 份 backend spec 引用受影响路径（`directory-structure.md` 最重，含「二级包怎么分」阈值句、`参照：` 行、读模型/构件说明里的路径）
- **批次间无依赖**：每批独立可编译可测，可分批提交、分批回滚

## 6. 回滚

- 每批 = 一个回滚点：该批 `mvn clean test` 绿之前不进入下一批（做法见 `implement.md`）
- 单批回滚：`git checkout -- .`（丢弃本批未提交改动；注意 `git clean -fd` 会顺手删掉未跟踪文件，仅在本批新建目录需要清理时使用）
- 整体回滚：丢弃 feature 分支，回 `dev`
