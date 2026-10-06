# 技术设计 — 后端 DDD 四层化重构

> 需求与验收见 prd.md；执行清单见 implement.md。自包含于本文，`docs/ddd-refactor-handoff.md` 是底稿。

## 1. 目标架构（to-be，经典四层）

```
com.vidsage/
├─ domain/                      # 领域层：业务概念、规则、端口接口的家
│  ├─ task/                     # 任务上下文
│  ├─ media/                    # 媒体获取与音频上下文
│  ├─ transcription/            # 转写上下文
│  ├─ subtitle/                 # 字幕上下文
│  └─ shared/                   # 共享内核（跨上下文领域词汇）
├─ application/                 # 应用服务层：编排、读模型、应用事件
│  ├─ task/  media/  transcription/  subtitle/  diagnostics/
├─ infrastructure/              # 基础设施层：出站适配
│  ├─ persistence/              # entity / mapper / repository
│  ├─ process/                  # 外部进程执行能力（原 provider/tool + model/process）
│  └─ provider/                 # asr / audio / download / llm 各实现
├─ web/                         # 接口层（不变）：controller / dto / sse
├─ config/                      # 装配与配置（不变）
├─ shared/                      # 共享工具（原 utils/ 改名）：fs / progress / subtitle / text
└─ VidSageApplication.java
```

### 依赖规则（务实分层，不是教科书纯洁）

- 正向：`web → application → domain`；`infrastructure → domain`（实现端口、依赖领域词汇）。
- **豁免清单**（明确写进 Phase 2 的 package-info 与 Phase 3 的 spec，不做接口脱壳）：
  - a. `application → infrastructure` 具体类：`VideoTaskRepository`（保持具体类，沿用 quality-guidelines「不预先抽接口」）；application/diagnostics 的 `ToolDiagnosticsService` → `ExternalToolDiagnostics` / `ExternalToolReport`（诊断编排；2026-10-05 执行期按实际 import 修订——`ExternalProcessRunner` 实为 infrastructure 内部能力，跨层经由其封装类发生）；`infrastructure/provider/llm/` 四个 worker 具体类（`SubtitleCleanser`、`SubtitleTranslator`、`SubtitleContextSummarizer`、`MessageHistory`）。
  - b. `web → infrastructure`：`MediaController` 直接读 `ExternalToolReport`（诊断端点）。
- 理由：quality-guidelines 明确反对「为以后可能用到」的接口增殖；用户诉求是**结构 DDD 化**（消除 model/domain 错位、领域概念有家），不是依赖倒置洁癖。正统化留作未来升级路径，触发条件：出现第二个存储实现或本地模型部署。

## 2. 迁移映射表

> 主代码 90 类 + 测试镜像 39 类同步迁移。「不迁移」= 原地不动。核对合计：domain 31 + application 18 + infrastructure 21 + 不迁移 20（含 utils 改名 shared 8 类）= 90。

### domain/（31 类）

| 目标包 | 来源 | 类 |
|---|---|---|
| `domain/task/` | `domain/task/`（原地） | `VideoTask` |
| `domain/task/` | `model/task/` | `TaskStatus`、`TaskInputType`、`TranslationStatus` |
| `domain/media/` | `model/media/` | `MediaInput`（含 3 个内嵌 record）、`AcquiredMedia`、`AudioArtifact`、`AudioContainer`、`AudioSpec` |
| `domain/media/` | `provider/download/` | `MediaAcquirer`（端口接口） |
| `domain/media/` | `provider/audio/` | `AudioExtractor`（端口接口） |
| `domain/transcription/` | `model/asr/` | `AudioSource`（含 2 个内嵌 record）、`TranscriptionCommand`、`TranscriptionResult`、`TranscriptionSegment` |
| `domain/transcription/` | `provider/asr/` | `ASRProvider`（端口接口） |
| `domain/subtitle/` | `model/subtitle/` | `CleanedSubtitle`、`SubtitleArtifact`、`SubtitleContext`、`SubtitleRoute`、`SubtitleTrack`、`TermLookupCommand`、`TermLookupResult`、`TranslatedSegment`、`TranslatedSubtitle`、`TranslatedTrack`、`VerifiedTerm`、`VerifiedTerms` |
| `domain/subtitle/` | `provider/llm/termlookup/` | `TermLookupProvider`（端口接口） |
| `domain/shared/` | `model/progress/` | `StageEvent`、`ProgressListener` |

### application/（18 类）

| 目标包 | 来源 | 类 |
|---|---|---|
| `application/task/` | `service/` | `TaskService`、`TaskPipelineService`、`TaskConflictException`、`TaskNotFoundException` |
| `application/task/` | `model/task/` | `TaskView`、`TaskArtifact`、`TaskArtifactFile`（读模型） |
| `application/task/` | `model/progress/` | `TaskProgressMessage`（应用→web 的进度消息） |
| `application/task/` | `subscribe/` | `TaskSubscriptionRegistry`、`Subscription`（中立 pub-sub，包内禁止 HTTP 类型——原 Javadoc 承诺保持） |
| `application/media/` | `service/` | `MediaAcquisitionService`、`AudioPreparationService`、`WorkspaceService` |
| `application/transcription/` | `service/` | `TranscriptionService` |
| `application/subtitle/` | `service/` | `SubtitleService`、`SubtitleCleanupService`、`SubtitleTranslationService` |
| `application/diagnostics/` | `service/` | `ToolDiagnosticsService` |

### infrastructure/（21 类）

| 目标包 | 来源 | 类 |
|---|---|---|
| `infrastructure/persistence/entity|mapper|repository/` | `persistence/`（整体平移） | `VideoTaskEntity`、`VideoTaskMapper`、`VideoTaskRepository` |
| `infrastructure/process/` | `provider/tool/` + `model/process/` | `ExternalProcessRunner`、`ExternalToolDiagnostics`、`ExternalToolProperties`、`ProcessOutcome`、`ExternalToolReport` |
| `infrastructure/provider/asr/dashscope/` | 同名 | `DashScopeAsrProvider`、`DashScopeProperties` |
| `infrastructure/provider/audio/ffmpeg/` | 同名 | `FfmpegAudioExtractor` |
| `infrastructure/provider/download/local/` | 同名 | `LocalFileMediaAcquirer` |
| `infrastructure/provider/download/ytdlp/` | 同名 | `YtDlpMediaAcquirer`、`YtDlpProperties`、`YtDlpRunner` |
| `infrastructure/provider/llm/` | `provider/llm/` | `SubtitleCleanser`、`SubtitleTranslator`、`SubtitleContextSummarizer`、`MessageHistory`（具体 worker 类，不抽端口） |
| `infrastructure/provider/llm/termlookup/dashscope/` | 同名 | `DashScopeTermLookupProvider`、`DashScopeTermLookupProperties` |

### 不迁移（12 类 + shared 改名 8 类）

- `web/`：`controller/`（`ApiExceptionHandler`、`MediaController`、`TaskController`）、`dto/`（`SubmitUrlRequest`、`TaskConflictResponse`）、`sse/`（`TaskSseHub`）——原样。
- `config/`：`ChatClientConfig`、`TaskProperties`、`WorkspaceProperties`、`SubtitleCleanupProperties`、`SubtitleTranslateProperties`——原样。
- 根：`VidSageApplication`。
- `utils/` → **改名** `shared/`，子包结构不变：`fs/ArtifactFiles`、`progress/ProgressMapper`、`subtitle/`（`SrtRenderer`、`SubtitleArtifactNames`、`SubtitleSegmentSplitter`、`TermMatcher`、`TextSimilarity`）、`text/TextTail`。

## 3. 关键设计决定

1. **端口接口进 domain**：`ASRProvider` / `MediaAcquirer` / `AudioExtractor` / `TermLookupProvider` 移入对应上下文的 `domain/` 包（六边形原则：端口属于六边形内部，适配器在 infrastructure）。四者均无框架注解、纯契约，与领域层 purity 一致。provider-pattern.md 的「四件套」中「接口」一件的位置随之更新（Phase 3）。
2. **依赖规则**：见 §1（正向 + 豁免清单）。quality-guidelines「不预先抽接口」是此决定的依据。
3. **LLM worker 不抽端口**：与 provider-pattern.md 已有的「LLMProvider 不建（2026-09-28 定）」同一条精神——供应商缝由 Spring AI `ChatClient` 承担，再抽能力接口是抽象套抽象。
4. **`subscribe/` 撤销顶层包位**：归 `application/task/`，包内禁止 HTTP 类型的 Javadoc 承诺保持；其中「2026-09-30 定调的顶层包 subscribe/」等措辞同步更新。
5. **`model/process` + `provider/tool` 合并**：`ProcessOutcome` / `ExternalToolReport` 本就是 `ExternalProcessRunner` 的出参契约，合并为 `infrastructure/process/`。
6. **值对象增量（Phase 2，与 Phase 1 分开提交）**：第一批 `TaskNo`（裸 `String`，32 位短 UUID 语义）→ `domain/task/`；`WorkspaceKey`（短哈希语义）→ `domain/shared/`（共享内核——Phase 1 后实测：`VideoTask`（domain/task）持有 workspaceKey 字段、`TaskService`（application/task）计算它、`MediaInput`（domain/media）携带它、`VideoTaskRepository.findActiveByWorkspaceKey` 按它查询，两上下文共用词汇，放共享内核避免 domain/task→domain/media 依赖；2026-10-05 执行期修订）。**约束：对外 JSON 形状不变**——`TaskView` / `TaskProgressMessage` 等读模型与 SSE 消息的 `taskNo` 字段对序列化保持 `String`，值对象在应用服务边界转换；web 路径变量、异常消息同为 `String`；`TaskNo` / `WorkspaceKey` 只在 domain ↔ application ↔ persistence 边界内流转。`SourceUri` / `WorkspaceDir` / `WorkspaceDir(Path)` 是否第二批跟进，实现时按成本判断，本任务不承诺。

## 4. 交接文档 §7 开放问题回填

| # | 问题 | 回填决策 | 依据 |
|---|---|---|---|
| 1 | `AudioSource` 归属 | `domain/transcription/` | 证据核验：纯契约 sealed interface，与 `AudioArtifact` 零耦合；`SubtitleService.java:147` 在边界处包装后交转写上下文。文档暂定方案成立；若实现中反证，一行改到 `domain/media/` |
| 2 | `MediaController` → `ExternalToolReport` | 维持直接引用，记入豁免清单 b | 文档推荐；quality-guidelines 反对为纯洁加映射层 |
| 3 | `utils/` → `shared/` 改名 | 改名 | to-be 树（交接文档 §2）即含 `shared/`；DDD 共享内核词汇；成本可控（8 类 + import 重写） |
| 4 | `SubtitleCleanupProperties` / `SubtitleTranslateProperties` | 暂留 `config/` | 文档默认；Phase 1 中若发现纯属 LLM worker 实现细节再下沉（provider-pattern 四件套），发现即记录 |
| 5 | 值对象范围 | 第一批仅 `TaskNo` + `WorkspaceKey` | 文档 §4-6；其余延后按需 |
| 6 | 仓库接口化 | `VideoTaskRepository` 保持具体类 | 文档 §4-2 已定决策（务实分层）；正统化升级路径见 §1 触发条件 |

## 5. 兼容性与技术风险核查（2026-10-05 实测）

| 检查点 | 结论 |
|---|---|
| 配置属性注册 | `@ConfigurationPropertiesScan` 根包全扫（`VidSageApplication.java:8`）→ Properties 类任意迁移无影响 |
| 显式属性注册 | 4 处 `@EnableConfigurationProperties` 在随迁类自身 → 仅 import 重写 |
| Mapper 发现 | `VideoTaskMapper` 用 `@Mapper` 交启动类根包自动发现，无 `@MapperScan`、无 mapper XML → 迁入 `infrastructure/persistence/mapper` 仍在根包下，无影响 |
| 实体映射 | `@TableName("task")` 注解定表名 → 包迁移无影响 |
| springdoc | `@Schema` 直接充当对外契约的 `ExternalToolReport` / `TranscriptionResult` 仍被 controller 返回类型引用 → 扫描不受包名影响 |
| 资源文件 | resources 仅 `application.yml` / `application-local.yml` / `schema.sql`，无按包路径引用 → 无影响 |
| 前端契约 | HTTP/SSE 路径与 JSON 形状不变；`web/` 包零迁移 → 前端零影响 |
| 未提交改动 | `VideoTask.java` 删无用 import `lombok.AllArgsConstructor`（语义中性）→ 随 Phase 1 commit 收编，commit message 注明 |

## 6. 回滚设计

- 分支 `feature/ddd-four-layer` 自 `dev` 拉出；Phase 1/2/3 各一个独立 commit，任一 Phase 可单独 revert。
- Phase 1 前的 dev HEAD 即回滚基线；若 Phase 1 门禁失败且短时间不可修，整分支丢弃回 dev 即可（无数据库 / 配置 / 前端耦合）。
- 越界信号：diff 出现逻辑变更，或 `schema.sql` / `application.yml` 出现改动 → 立即停下回查是否引入了行为变更。

## 7. Phase 4 增量：application/task 角色子包（2026-10-05 追加）

- **动机**：`application/task/` 10 类平铺、混六种 stereotype，项目所有者要求按角色分组。
- **范围决策**（三选项供选，项目所有者选定）：仅 `application/task/` 拆；其余 application 上下文（1–3 类）不拆——一包一类空壳；domain 上下文不拆——连续领域词汇表 + directory-structure.md「形状分类包」警戒线。子包随 mass 出现（「目录随代码出现」原则）。
- **布局**：
  - 根（留）：`TaskService`、`TaskPipelineService`（服务门面）、`TaskProgressMessage`（应用消息）
  - `query/`：`TaskView`、`TaskArtifact`、`TaskArtifactFile`（读模型，CQRS 查询侧词汇）
  - `exception/`：`TaskConflictException`、`TaskNotFoundException`
  - `subscribe/`：`TaskSubscriptionRegistry`、`Subscription`（恢复 2026-09-30 定调的历史包名；「包内禁止 HTTP 类型」承诺随 Javadoc 迁入子包）
- **约束**：纯机械迁移（package/import/Javadoc/package-info/spec 路径），零逻辑变更；对外 JSON/SSE 形状零变化；`web` 层仅 import 行改动。spec 同步：directory-structure.md「二级包怎么分」补记「application 上下文内角色子包（query/exception/…）随 mass 出现」与 application/task 实例。

## 8. DDD 规则与现行规范冲突记录（2026-10-06 归档定案）

> 应项目所有者要求归档前补记：重构期间「DDD 规则 vs 现行规范」的实际冲突点与定案。五项已裁决落 spec，一项经证据核验维持现状。

| # | 冲突 | 定案 | 状态 |
|---|---|---|---|
| 1 | 依赖倒置正统（application 只依赖领域抽象）vs `VideoTaskRepository`/LLM worker 具体类直引、`MediaController` 直读 `ExternalToolReport` | 务实分层 + 豁免清单 a/b 落 directory-structure.md（2026-10-05 所有者定）；正统化触发条件：第二存储实现或本地模型部署 | ✅ 已落 spec |
| 2 | 计划期豁免清单写 `ExternalProcessRunner`，实际跨层是 `ToolDiagnosticsService` 经诊断编排引 `ExternalToolDiagnostics`/`ExternalToolReport` | 按实际 import 修订豁免清单 a，同步两个层 package-info | ✅ 已落 spec |
| 3 | 值对象紧凑构造器初版抛 IAE vs error-handling.md 判据（服务端算出的非法值应 ISE） | 改 ISE，对齐 domain 先例（AudioSpec/VideoTask 状态机） | ✅ 已修正 |
| 4 | 「领域模型都该进 domain」直觉 vs 读模型（`TaskView`/`TaskArtifact`/`TaskArtifactFile`）落 `application/task/query/` | CQRS 判据：读模型是查询投影（无不变量、为 JSON 形状而生）；所有者 2026-10-06 定案「先放 application」 | ✅ 所有者定案 |
| 5 | 所有者提议去掉读模型 `@Schema`（前提：属早期测试接口、已并入管线应改造） | **证据核验否决前提**：前端全量在用（`stores/task.ts:4` getTask、`useTaskEvents.ts:48` SSE、`MediaPane/index.vue:38` 产物含 source.mp4 预览）→ 活契约保留 @Schema；「并入管线」实指旧同步 ASR 端点（60c67db 已删），与 TaskView 无关 | ✅ 维持现状（证据定案） |
| 6 | 业务谓词住错层：active 谓词在 repository SQL、`isTerminal` 在 web 两处重复、`STAGE_TRANSLATE` 在 infrastructure、检查点语义散落 4 服务、`shared/` 纯域规则件 | 全部识别在案 → 移交后续任务 `10-06-domain-logic-sink`（Phase B/C）；`shared/` 五件所有者已定本轮不动 | 🔜 移交新任务 |
