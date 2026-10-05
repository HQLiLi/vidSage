# 后端 DDD 化重构 · 移交文档

> 状态：**待执行**（本文档是规划共识，尚未创建 Trellis 任务、尚未动代码）
> 产出会话：2026-10-05，与项目所有者（HeQiu）讨论收敛
> 执行会话：新会话按 [.trellis/workflow.md](../.trellis/workflow.md) **现行版**创建复杂任务，本文档作为需求来源（内容吸收进 `prd.md` / `design.md`）

---

## 1. 背景与动机

项目所有者对现有包结构提出两次质疑：

1. `model/` 与 `domain/` 顶层并存有错位感（`domain/` 只有一个类 `VideoTask`，却像第五个角色层；概念上它和 `model/` 才是一对）。
2. 希望架构**偏向 DDD**：领域概念有明确的家，而不是散在技术层里。

讨论中确认了现状的三条轴：顶层按**架构角色**拆（web/service/provider/persistence），层内子包按**业务边界**拆（model/asr、model/subtitle…），`model` vs `domain` 只是"数据 vs 行为"的类安置判据。错位感来源：包树顶层混排了两个轴。

**已定决策（项目所有者 2026-10-05 选定）：经典四层 DDD 结构**，即 domain / application / infrastructure / web 四层，领域层内按限界上下文分包。备选方案"模块优先"（每个上下文自带三层）因"管线阶段共享一条数据流、人为切分风险大、迁移量最大"被否；"最小增量"因错位感只弱化不消除被否。

**前端零影响**：HTTP/SSE 路径与 JSON 契约不变，本次重构纯后端结构迁移。

## 2. 结构总览

### 现状（as-is，重构前）

```
backend/src/main/java/com/vidsage/
├─ config/                 ChatClientConfig、Task/Workspace/SubtitleCleanup/SubtitleTranslate Properties（5）
├─ domain/
│  └─ task/                VideoTask（状态机，唯一居民）（1）
├─ model/                  # 纯数据形状：record / enum / sealed interface
│  ├─ asr/                 AudioSource（sealed）、TranscriptionCommand / Result / Segment（4）
│  ├─ media/               MediaInput（sealed）、AcquiredMedia、AudioArtifact、AudioContainer、AudioSpec（5）
│  ├─ process/             ExternalToolReport、ProcessOutcome（2）
│  ├─ progress/            ProgressListener、StageEvent、TaskProgressMessage（3）
│  ├─ subtitle/            CleanedSubtitle、SubtitleArtifact、SubtitleContext、SubtitleRoute、SubtitleTrack、
│  │                       TermLookupCommand / Result、TranslatedSegment / Subtitle / Track、VerifiedTerm、VerifiedTerms（12）
│  └─ task/                TaskArtifact、TaskArtifactFile、TaskInputType、TaskStatus、TaskView、TranslationStatus（6）
├─ persistence/
│  ├─ entity/  mapper/  repository/     VideoTaskEntity、VideoTaskMapper、VideoTaskRepository（3）
├─ provider/               # 端口 + 适配器混在同一子树
│  ├─ asr/                 ASRProvider（端口）＋ dashscope/（DashScopeAsrProvider、DashScopeProperties）
│  ├─ audio/               AudioExtractor（端口）＋ ffmpeg/（FfmpegAudioExtractor）
│  ├─ download/            MediaAcquirer（端口）＋ local/（LocalFileMediaAcquirer）＋ ytdlp/（YtDlpMediaAcquirer、YtDlpProperties、YtDlpRunner）
│  ├─ llm/                 MessageHistory、SubtitleCleanser、SubtitleContextSummarizer、SubtitleTranslator
│  │                       ＋ termlookup/（TermLookupProvider 端口）＋ termlookup/dashscope/（实现 + Properties）
│  └─ tool/                ExternalProcessRunner、ExternalToolDiagnostics、ExternalToolProperties（3）
├─ service/                # 12 类平铺：TaskService、TaskPipelineService、MediaAcquisitionService、AudioPreparationService、
│                          # TranscriptionService、SubtitleService、SubtitleCleanupService、SubtitleTranslationService、
│                          # WorkspaceService、ToolDiagnosticsService、TaskConflictException、TaskNotFoundException
├─ subscribe/              # 中立 pub-sub：Subscription、TaskSubscriptionRegistry（2）
├─ utils/                  fs/ArtifactFiles、progress/ProgressMapper、subtitle/（5 类）、text/TextTail（8）
├─ web/
│  ├─ controller/          ApiExceptionHandler、MediaController、TaskController（3）
│  ├─ dto/                 SubmitUrlRequest、TaskConflictResponse（2）
│  └─ sse/                 TaskSseHub（1）
└─ VidSageApplication.java
```

主代码共 90 类；测试目录（`backend/src/test/java/com/vidsage/`）以相同结构镜像（39 类，另含两个无主类对应的测试辅助 `ModelProbeTest`、`ProcessHelperMain`），随主代码同表迁移。

与目标的差异一句话：`model/` 解散、`service/` → `application/`、`provider` 端口上移 `domain/`、`persistence`+`provider/tool`+`model/process` 收进 `infrastructure/`、`subscribe/` 并入 `application/task/`、`utils/` 改名 `shared/`、`domain/` 从 1 类扩为 31 类。

### 目标（to-be，经典四层）

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

**核心动作**：`model/` 解散（领域词汇 → `domain/`，读模型与应用消息 → `application/`）；四个端口接口上移 `domain/`；`service/` 平移改名 `application/`；`provider/tool` + `model/process` 合并为 `infrastructure/process/`；`utils/` 改名 `shared/`。

## 3. 类迁移映射表

> 主代码 90 类 + 测试镜像 39 类同步迁移。`不迁移` = 原地不动。

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
| `infrastructure/persistence/entity/` 等 | `persistence/`（整体平移） | `VideoTaskEntity`、`VideoTaskMapper`、`VideoTaskRepository` |
| `infrastructure/process/` | `provider/tool/` + `model/process/` | `ExternalProcessRunner`、`ExternalToolDiagnostics`、`ExternalToolProperties`、`ProcessOutcome`、`ExternalToolReport` |
| `infrastructure/provider/asr/dashscope/` | 同名 | `DashScopeAsrProvider`、`DashScopeProperties` |
| `infrastructure/provider/audio/ffmpeg/` | 同名 | `FfmpegAudioExtractor` |
| `infrastructure/provider/download/local/` | 同名 | `LocalFileMediaAcquirer` |
| `infrastructure/provider/download/ytdlp/` | 同名 | `YtDlpMediaAcquirer`、`YtDlpProperties`、`YtDlpRunner` |
| `infrastructure/provider/llm/` | `provider/llm/` | `SubtitleCleanser`、`SubtitleTranslator`、`SubtitleContextSummarizer`、`MessageHistory`（具体 worker 类，见 §4-3） |
| `infrastructure/provider/llm/termlookup/dashscope/` | 同名 | `DashScopeTermLookupProvider`、`DashScopeTermLookupProperties` |

### 不迁移（20 类）

- `web/`：`controller/`（`ApiExceptionHandler`、`MediaController`、`TaskController`）、`dto/`（`SubmitUrlRequest`、`TaskConflictResponse`）、`sse/`（`TaskSseHub`）——原样。
- `config/`：`ChatClientConfig`、`TaskProperties`、`WorkspaceProperties`、`SubtitleCleanupProperties`、`SubtitleTranslateProperties`——原样（下沉与否见 §7-6）。
- 根：`VidSageApplication`。
- `utils/` → **改名** `shared/`，子包结构不变：`fs/ArtifactFiles`、`progress/ProgressMapper`、`subtitle/`（`SrtRenderer`、`SubtitleArtifactNames`、`SubtitleSegmentSplitter`、`TermMatcher`、`TextSimilarity`）、`text/TextTail`。

## 4. 关键设计决定

1. **端口接口进 domain**：`ASRProvider` / `MediaAcquirer` / `AudioExtractor` / `TermLookupProvider` 移入对应上下文的 `domain/` 包（六边形原则：端口属于六边形内部，适配器在 infrastructure）。这些接口本就"无框架注解、纯契约"，与领域层 purity 要求天然一致。**provider-pattern.md 的"四件套"中"接口"一件的位置随之更新**。

2. **依赖规则（务实分层，不是教科书纯洁）**：
   - 正向：`web → application → domain`；`infrastructure → domain`（实现端口、依赖领域词汇）。
   - **豁免清单（明确写进 spec，不做接口脱壳）**：
     a. `application → infrastructure` 具体类：`VideoTaskRepository`（保持具体类，沿用 quality-guidelines"不预先抽接口"）；`ExternalProcessRunner`；`infrastructure/provider/llm/` 四个 worker 具体类。
     b. `web → infrastructure`：`MediaController` 直接读 `ExternalToolReport`（诊断端点，见 §7-2）。
   - 理由：项目 quality-guidelines 明确反对"为以后可能用到"的接口增殖；用户诉求是**结构 DDD 化**（消除 model/domain 错位、领域概念有家），不是依赖倒置洁癖。正统化（仓库/worker 全接口化）留作未来升级路径，触发条件：出现第二个存储实现或本地模型部署。

3. **LLM worker 不抽端口**：`SubtitleCleanser` / `SubtitleTranslator` / `SubtitleContextSummarizer` 保持具体类进 `infrastructure/provider/llm/`。这与 provider-pattern.md 已有的"LLMProvider 不建（2026-09-28 定）"决策同一条精神——供应商缝由 Spring AI `ChatClient` 承担，再抽能力接口是抽象套抽象。

4. **`subscribe/` 撤销顶层包位**：`TaskSubscriptionRegistry` + `Subscription` 是应用层的中立通知机制（管线广播、web 订阅），归 `application/task/`。其 Javadoc 中"2026-09-30 定调的顶层包 subscribe/"等措辞需同步更新。

5. **`model/process` 与 `provider/tool` 合并**：`ProcessOutcome` / `ExternalToolReport` 本就是 `ExternalProcessRunner` 的出参契约，拆在两处是历史痕迹；合并为 `infrastructure/process/`。

6. **值对象扩充（增量，不阻塞迁移）**：第一批候选 `TaskNo`（现在裸 `String`，32 位短 UUID 语义）、`WorkspaceKey`（短哈希语义）。`SourceUri` / `WorkspaceDir` 是否跟进由执行会话判断。放在 Phase 2，与 Phase 1 机械迁移分开提交。

## 5. 迁移步骤

> 每阶段一个独立 commit，可独立 revert。分支：从 `dev` 拉 `feature/ddd-four-layer`。

**Phase 1 —— 机械迁移（纯移动 + import 重写，无语义变更）**
1. 按 §3 映射表移动主代码 + 测试（测试目录结构镜像主代码）。
2. 全局重写 package/import（`package-info.java` 若有随之更新）。
3. **门禁**：`mvn test` 全绿；`grep -r "com.vidsage.model\|com.vidsage.service\|com.vidsage.provider\|com.vidsage.subscribe\|com.vidsage.utils\|com.vidsage.persistence"` 零残留（注意 `infrastructure/persistence` 命中属正常，按全限定名核对）。
4. Javadoc 措辞清理：grep 引用旧结构的注释并更新——已知点：`VideoTask.java` 头注释（"与 model/ 并存、不合并"段落）、`TaskSubscriptionRegistry`（"顶层包 subscribe/"）、各 spec 引用。

**Phase 2 —— DDD 增量**
1. 值对象 `TaskNo` / `WorkspaceKey` 落地，`VideoTask` 与相关调用方切换（含测试）。
2. 每层补 `package-info.java`，写明该层职责与依赖规则（§4-2，含豁免清单）。
3. **门禁**：`mvn test` 全绿。

**Phase 3 —— spec 重写与收尾**
1. `.trellis/spec/backend/directory-structure.md`：重写"`model` / `domain` / `persistence` / `config` 怎么选"一节 → 四层结构 + 依赖规则 + 豁免清单；`model/`、`service/`、`subscribe/`、`utils/`、顶层 `provider/` 的历史描述归档化。
2. `.trellis/spec/backend/provider-pattern.md`：更新全部路径引用；"四件套"的接口位置改为 `domain/<上下文>/`。
3. `.trellis/spec/backend/database-guidelines.md`：persistence 路径更新。
4. 检查 `error-handling.md`、`logging-guidelines.md`、`quality-guidelines.md`、`index.md` 中的旧包路径引用，逐一更新。
5. **门禁**：spec 内 `grep` 旧包名零残留（历史决策记录除外，保留时间线语境）。

## 6. 验证清单

- `mvn test`（backend/，Maven）全绿——Phase 1/2/3 各跑一次。
- 前端不改动、不需回归（契约不变）；如新会话想加保险，起后端 + 前端跑一次提交 URL → SSE → 产物下载冒烟。
- git diff 审查：Phase 1 commit 应**只有** package/import 行变更 + 文件移动，无逻辑 diff。

## 7. 开放问题（执行会话与项目所有者确认）

1. **`AudioSource` 归属**：暂定 `domain/transcription/`（它是 ASR 契约的入参形状）；若实现中发现它与 `AudioArtifact` 耦合更深，可改 `domain/media/`，一行决定。
2. **`MediaController` → `ExternalToolReport`**：推荐维持直接引用（记入豁免清单）；若想要纯洁，加一层 web DTO 映射，实现时看成本。
3. **`utils/` → `shared/` 改名**：推荐改（DDD 共享内核词汇）；若想压噪声可保留 `utils/`，仅其余迁移。
4. **`config/SubtitleCleanupProperties` / `SubtitleTranslateProperties`**：暂留 `config/`；若执行时发现它们纯属 LLM worker 的实现细节，按"四件套"原则下沉 `infrastructure/provider/llm/`。
5. **值对象范围**：`TaskNo` / `WorkspaceKey` 之外，`SourceUri` / `WorkspaceDir` / `WorkspaceDir(Path)` 是否第一批跟进。
6. **仓库接口化**：本方案保持 `VideoTaskRepository` 具体类（见 §4-2）；若项目所有者希望正统 DDD（接口进 `domain/task/`，实现类 `VideoTaskRepositoryImpl` 进 infrastructure），在 Phase 2 追加，需同步修订 quality-guidelines 的"不预先抽接口"条款（为聚合仓库开例外）。

## 8. 新会话启动指引

1. 读 `.trellis/workflow.md` **现行版**（本文档写作时该文件正在修订中，不要假设步骤编号）。
2. 按工作流创建复杂任务（slug 建议 `ddd-four-layer-refactor`），本文档 §1–§5 吸收为 `prd.md` + `design.md`，§5 拆为 `implement.md` 的执行清单，§7 逐项过掉后回填决策。
3. 规划产物评审通过、`task.py start` 后才动代码；Phase 1/2/3 分 commit。
4. 相关 spec 索引：`.trellis/spec/backend/index.md`；重构完成后走工作流的 spec 更新与提交步骤。
