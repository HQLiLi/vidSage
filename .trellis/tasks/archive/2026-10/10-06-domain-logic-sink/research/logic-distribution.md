# 调研报告：application service 逻辑归属盘点（2026-10-06）

> trellis-research 产出（dispatch 开始 1791255401 前，usage：992,999 tok / 32 uses / 663,287 ms）。
> 触发：项目所有者评价「application service 还是传统 CRUD service，没做拆分」；目标 = service 纯编排、业务规则下沉 domain、技术实现归 infrastructure。
> 基线：分支 feature/ddd-four-layer @ e97f2a6（Phase 4 后）。

## 一、总表

| # | 服务（行数） | 编排 | 业务规则 | 技术实现 | 灰色 | 下沉建议（一句话） |
|---|---|---|---|---|---|---|
| 1 | TaskService (413) | ~7 方法 | 3 块 | 3 块 | 1 | key 宽度公式并入 `WorkspaceKey` 工厂；`source.mp4`/产物可见性过滤/active 谓词下沉；`toMediaInput` 重建规则与管线共用搬 domain 工厂 |
| 2 | TaskPipelineService (383) | 主体 | 2 块 | 极少 | 1 | 基本不动；仅 `toMediaInput`（L373）与终态谓词下沉 |
| 3 | MediaAcquisitionService (91) | 全部 | 0 | 0 | 0 | 零改动，纯端口分派 |
| 4 | AudioPreparationService (233) | 主体 | 2 块 | 少 | 0 | `audioFileName`/`sampleRateKilo`（L192/L211）指纹命名下沉 domain/media |
| 5 | WorkspaceService (308) | 薄 | 4 块 | 2 块 | 2 | 最大单块：`normalize`/`dirName`/`shortHash`/`sanitizeName` → `SourceUri`/`WorkspaceDir` 值对象（design.md 已点名的第二批 VO） |
| 6 | TranscriptionService (55) | 全部 | 0 | 0 | 0 | 零改动 |
| 7 | SubtitleService (211) | 主体 | 1 块 | 少 | 0 | 检查点判据（JSON 非空=完成）抽 domain 政策，与另两个服务合流 |
| 8 | SubtitleCleanupService (256) | 主体 | 3 块 | 少 | 2 | 检查点判据 + 上下文"空摘要不落盘"政策（与翻译服务重复）抽 domain |
| 9 | SubtitleTranslationService (518) | 骨架 | 6 块 | 少 | 1 | 下沉大头：语种政策（resolve*/isSameMainLanguage）+ 术语查证政策 → domain/subtitle |
| 10 | ToolDiagnosticsService (38) | 全部 | 0 | 0 | 0 | 零改动，纯门面 |

application 层共 2506 行，可下沉纯域规则约 400–600 行（不足 1/5）。三个服务零改动（共 184 行），TaskPipelineService 近乎达标。

## 二、逐服务明细

### 1. TaskService（413 行）

公开方法：`submitUrl(String)` L99、`submitUpload(InputStream,String)` L127、`findByTaskNo(String)` L170、`translate(String)` L186、`snapshot(String)` L208、`openArtifact(String,String)` L229；包内 static `stageEventOf(VideoTask)` L277、`translationStageEventOf(VideoTask)` L306。

| 逻辑块 | 位置 | 归类 | 理由 |
|---|---|---|---|
| submitUrl 主流程 | L99-111 | 编排 | 校验→算 key→查重→落库→异步，本职；但空白 url 校验 L100-103 与 `TaskController` L102-105 同规则双份 |
| submitUpload 主流程 | L127-160 | 编排 | 临时文件→摘要→查重→归位→落库的步骤顺序 |
| 流式落盘+边写边算 sha256 | L348-354（`streamToTemp`） | 技术实现 | DigestInputStream/Files.copy；**但 key=摘要前 12 位的宽度规则**（L71 `KEY_LENGTH=12`）与 `WorkspaceService.shortHash` L301（6 字节=12 hex）是同一业务常量的两条公式 |
| 临时文件归位 | L361-366（`settleUpload`） | 技术实现 | 建目录+同盘 rename；`UPLOAD_SOURCE_FILE_NAME="source.mp4"` L62 是产物命名规则，被 `TaskPipelineService.toMediaInput` L378 跨类引用 |
| 查重 | L338-343（`rejectIfActive`） | 编排壳+业务核 | "什么算 active"的谓词（PENDING/RUNNING）实体化在 `VideoTaskRepository.findActiveByWorkspaceKey` L71（infrastructure 里写着业务规则） |
| 路径穿越防护 | L239-247 | 技术实现/边界防护 | 文件系统级安全判据，非领域不变量 |
| 产物可见性过滤 | L402-403（`scanArtifacts`） | **业务规则** | "哪些产物对外可见"（*.srt + segments-*.json，terms-/context- 排除）是领域政策，混在 Files.list 技术扫描里 |
| stageEventOf / translationStageEventOf | L277-316 | 编排（读模型组装） | 状态→粗粒度事件的形状组装；被管线 `broadcastTerminal` L296-297 包内跨类复用 |
| toView / snapshot / findByTaskNo / translate | L170-212、L372-377 | 编排 | 读模型装配，本职 |

**测试影响**（TaskServiceTest，30 条）：冲突/快照形状/读模型断言留；若 key 公式搬 `WorkspaceKey`，submitUpload 4 条（L128-195）改断言目标；路径穿越 4 条（L408-481）留。

### 2. TaskPipelineService（383 行）

公开：`execute(TaskNo)` L110、`translateTask(TaskNo)` L134；包内 `run` L174。

| 逻辑块 | 位置 | 归类 | 理由 |
|---|---|---|---|
| execute / run / runTranslation | L110、L174-214、L221-243 | 编排 | 异步边界、信号量配额、状态迁移时机 |
| runStages 阶段顺序+权重 | L249-256 | 编排 | prepare 0..70 → clean 70..100，管线灵魂所在 |
| translateTask 触发判定序列 | L134-169 | **灰色** | 状态守卫已在 `VideoTask`（L194-235），剩下的是判定顺序与幂等语义 |
| 复合监听器（先落库后广播） | L263-272 | 编排 | 事件投递政策 |
| failTask / failTranslation 文案 | L323-339、L349-365 | **灰色** | "任务在 X 阶段失败："/"翻译失败："前缀的归属 |
| toMediaInput 重建规则 | L373-382 | **业务规则** | TaskInputType→MediaInput 变体的身份一致性映射（与 TaskService L144 配对）→ 搬 `MediaInput` 静态工厂 |
| maxConcurrent 校验 | L89-92 | 编排（配置不变量） | — |

**测试影响**（21 条）：几乎全为编排断言，留；translateTask 判定序列 6 条（L327-397）若判定下沉部分随迁。

### 3. MediaAcquisitionService（91 行）— 零改动

`reuseExisting` L50-56、`acquire` L67-71、`select` L79-90 全部为端口分派（`supports()` 互斥→findFirst）。

### 4. AudioPreparationService（233 行）

| 逻辑块 | 位置 | 归类 | 理由 |
|---|---|---|---|
| prepare 两阶段编排 | L108-127、runAcquireStage L132-148 | 编排 | 阶段顺序与事件发射 |
| extract 断点判据 | L156-162 | **业务规则**（技术表达） | "产物存在且 size>0 才算完成"——检查点语义实例 |
| `audioFileName(spec)` | L192-203 | **业务规则** | 规格指纹命名（L179-187 论证），纯函数 → domain/media |
| `sampleRateKilo` | L211-216 | **业务规则** | 指纹的一半（44.1k 陷阱 L207-209） |
| describe / emit | L221-232 | 编排辅助 | — |

**测试影响**：13 条中 `usesM4aFileNameForM4aSpec` L85、`namesAudioFileWithFullSpecFingerprint` L98、`differentSpecsMapToDifferentAudioPaths` L113 随命名规则搬家。

### 5. WorkspaceService（308 行）— 下沉最大单块

| 逻辑块 | 位置 | 归类 | 理由 |
|---|---|---|---|
| `dirName = sanitize(label)-key` | L120-122 | **业务规则** | 目录名公式单源（L112-114：TaskService 落库 workspace_dir 必须逐字一致） |
| `normalize`（URL 原文 / LocalPath→path\|size\|mtime） | L177-185 | **业务规则** | 输入身份规范化 → `SourceUri` 值对象 |
| `localPathKey` | L196-213 | **灰色** | 身份公式（域）与 Files.size/getLastModifiedTime 的 stat（技术）切两层 |
| `sanitizeName`/`isReservedName`/RESERVED_NAMES L48-51 | L246-287 | **灰色** | Windows/文件系统约束；随 WorkspaceDir VO 走最顺（2026-10-06 所有者问询后定为随行，归 domain/shared） |
| `shortHash`（sha256 前 12 hex） | L297-307 | 业务规则+技术壳 | → `WorkspaceKey` 静态工厂，宽度 12 与 TaskService L71 合一 |
| workspaceFor 主流程 / rootDir / ensureCreated | L82-92、L133-165 | 编排+技术 | 懒创建是文件系统操作 |

**测试影响**：WorkspaceServiceTest 22 条中约 18 条断言的就是这些规则（L42 至 L182），随 VO 搬 domain 测试；`createsDirectoryAndNestedRootLazily` L194、`workspaceRootResolvesWithoutCreatingDirectory` L227 留。

### 6. TranscriptionService（55 行）— 零改动

纯编排：卫语句→组 `TranscriptionCommand`→委托 `ASRProvider`→计时日志。

### 7. SubtitleService（211 行）

| 逻辑块 | 位置 | 归类 | 理由 |
|---|---|---|---|
| prepare 编排 | L116-165 | 编排 | 进度权重 0..60/60..95/95..100（L58-68） |
| 断点判据（JSON 非空=完成；SRT 永不算检查点） | L133-139 + `readCheckpoint` L173-186 | **业务规则** | 与 Cleanup/Translation 合流为 domain 检查点政策 |
| 空转写合法 | L154-158 | 业务（已就位） | 契约已在 `SubtitleTrack`，勿重复搬运 |
| writeArtifacts（先 JSON 后 SRT，原子替换） | L191-194 | 政策+技术 | — |

**测试影响**：3 条全是规则断言（L51/L70/L94），随政策对象迁。

### 8. SubtitleCleanupService（256 行）

| 逻辑块 | 位置 | 归类 | 理由 |
|---|---|---|---|
| clean 主编排 | L130-200 | 编排 | — |
| `-clean.json` 非空=完成 | L144-151 | **业务规则** | 检查点语义 |
| "先有原始字幕才能清洗"前置 | L158-161 | **业务规则** | 调用顺序不变量 |
| 上下文缓存-or-摘要 + "空摘要绝不落盘" | L169-184 | **业务规则** | 与 Translation L209-227 近逐字重复 → 抽共享政策 |
| readCheckpoint/readRaw/writeArtifacts/toCleaned | L206-256 | 技术+读模型 | — |

**测试影响**：`cleanReusesCheckpointSkipsCleanser` L116、`summarizeDegradeDoesNotWriteContextFile` L265、`cleanMissingRawThrows` L152 随政策搬家。

### 9. SubtitleTranslationService（518 行）— 规则密度最高

| 逻辑块 | 位置 | 归类 | 理由 |
|---|---|---|---|
| translate 编排骨架 | L171-264 | 编排 | — |
| `resolveTargetLanguage`（BCP-47 归一化+零 I/O 守卫） | L386-394 | **业务规则** | → `Bcp47Language` 值对象 |
| `resolveSourceLanguage`（摘要>ASR>未知不跳过） | L406-411 | **业务规则** | → 同上或 LanguagePolicy 领域服务 |
| `isSameMainLanguage` + `mainLanguageSubtag` | L434-453 | **业务规则** | 纯谓词（zh-CN 陷阱 L414-426） |
| 术语查证触发三条件 | `verifyTermsIfNeeded` L292-308 | **业务规则** | 查证经济学的领域政策 |
| 查证结论分类与落盘政策 | L318-348 | **业务规则** | official/common/none 记账、失败词不进产物 L337-341、全失败不落盘 L343-348 |
| `skipAll`（同语种=译文照抄） | L466-470 | **业务规则** | 纯域变换 |
| 断点判据 + hasTranslationArtifact | L190-196、L126-138 | **业务规则** | 幂等判据单源在本类（L113-116） |
| describeSource（只记 host） | L358-368 | 灰色 | 展示/脱敏政策 |
| 文件读写 | L491-507 | 技术 | — |

**测试影响**：31 条中约一半（L271-348、L477-517、L559-649）随政策搬家——全部服务里测试迁移量最大。

### 10. ToolDiagnosticsService（38 行）— 零改动

纯委托门面，维持 Controller→application→infrastructure 依赖方向（豁免清单 b 项）。

## 三、灰色裁决清单

1. **translateTask 触发判定序列**（L134-169）：→ 回填：留编排（状态守卫已在 VideoTask，判定顺序与幂等收敛是用例步骤）。
2. **sanitizeName/Windows 保留名**：→ 2026-10-06 所有者问询「WorkspaceDir 放哪」后定为：随 WorkspaceDir 进 domain/shared（共享内核，与 WorkspaceKey 同判据）。
3. **localPathKey 的 stat**（L196-213）：→ 回填：切两层——身份公式进 domain，文件 stat 由 application 提供三元组（path,size,mtime）。
4. **失败文案组装**（failTask/failTranslation L323-365）：→ 回填：留编排层（面向用户文案是展示关切；域抛状态+原因）。
5. **上下文降级政策重复**：→ 回填：抽 domain/subtitle 共享政策对象（"空摘要绝不落盘"、缓存-or-摘要策略是域规则；文件 I/O 留应用）。
6. **shared/ 三个纯域规则件**：→ 2026-10-06 所有者选定：本轮不动（无状态纯函数、shared 准入判据覆盖、搬家波及面大）。
7. **raw/clean 缺失抛 ISE→500**：→ 回填：保持现状（error-handling.md 判据已覆盖：ISE=服务端缺陷语义成立）。
8. **stageEventOf/translationStageEventOf**：→ 回填：留 application/task（读模型组装是应用层职责）。

## 四、Controller 顺带扫描

- **TaskController**：卫语句与 TaskService 同规则双份（url L102-105；taskNo 空白 L150-152/172-174/214-216/237-242 vs `loadTask` L256-258）——按 error-handling.md「同一个值被检查两次不是冗余」（边界 IAE 400 vs 构造器/服务端 ISE）判据，**双份卫语句保留，非待修重复**。**`isTerminal` L258-262 是真正的业务谓词**（"主状态终态 且 翻译不在 RUNNING"），且与 `TaskSseHub` L115-119 重复 → 下沉 `VideoTask` 单源。
- **MediaController**：干净。

## 五、domain 现有覆盖度（避免重复搬运）

已就位：`VideoTask` 双状态机完整（start/succeed/fail L141-172、updateStage L181、translation* L194-235、守卫 L241-256）；`TaskNo` / `WorkspaceKey` 非空白不变量；`AudioSpec` 结构不变量 L32-54；subtitle 契约群；四个端口接口。

结构性事实：application 直接注入 `VideoTaskRepository` 与 LLM worker 具体类（豁免清单 a 项，维持）；`TaskService` L17 import `SubtitleTranslator` 仅为取 `STAGE_TRANSLATE` 阶段词常量（L308-312）——阶段词汇表住在 infrastructure，应归位 domain/shared/StageEvent 一族。

## 六、总体判断

**规模量级：中。** 工作量四块：

1. **Workspace 命名/寻址 → SourceUri/WorkspaceDir/WorkspaceKey 工厂**（~150-200 行规则 + WorkspaceServiceTest 22 条随迁）——即 design.md 已延后的第二批值对象；归属 domain/shared（跨上下文共用，Phase 2 WorkspaceKey 同判据）。
2. **SubtitleTranslationService 语种政策 + 术语查证政策**（~150 行 + 测试迁移大头）→ domain/subtitle。
3. **检查点语义统一**（4 个服务各写一遍）→ domain/subtitle 检查点政策。
4. **零散**：音频指纹命名（→domain/media）、产物可见性过滤、active 谓词（VideoTaskRepository L71）、终态谓词（web 两处重复）、toMediaInput 工厂、STAGE 常量归位。
