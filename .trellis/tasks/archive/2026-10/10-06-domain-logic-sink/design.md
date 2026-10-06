# 技术设计 — 业务逻辑下沉 domain

> 需求与验收见 prd.md；证据基线（逐方法归类 + 行号锚点）见 [research/logic-distribution.md](./research/logic-distribution.md)，本文件只记结构与决定，不重复明细。

## 1. domain 目标布局（新增件）

```
domain/shared/
├─ SourceUri            # 新：输入身份规范化（normalize 公式；URL 原文 / LocalPath→path|size|mtime 三元组）
├─ WorkspaceDir         # 新：目录名值对象（dirName 公式 + sanitizeName/保留名；2026-10-06 所有者确认随行）
├─ WorkspaceKey         # 已有：+静态工厂 ofHash(String)（shortHash 吸收，宽度 12 单源）
└─ StageEvent           # 已有：+阶段词表常量归位（STAGE_TRANSLATE 等从 infrastructure 迁入）
domain/media/
└─ AudioArtifact        # 已有：+指纹命名政策（audioFileName/sampleRateKilo 吸收，或伴生 AudioFileNamer，实现时定）
   MediaInput           # 已有：+静态工厂 ofTaskInput(TaskInputType, ...)（toMediaInput 重建规则单源）
domain/subtitle/
├─ Bcp47Language        # 新：语种值对象（resolve*/isSameMainLanguage/mainLanguageSubtag；zh-CN 陷阱语义随行）
├─ TermVerification     # 新：术语查证政策（触发三条件 + official/common/none 记账 + 失败词不进产物/全失败不落盘）
└─ CheckpointPolicy     # 新：检查点语义单源（JSON 非空=完成、0 字节=未完成、SRT 派生不参与；四服务合流）
                        #   + 上下文政策（缓存-or-摘要、空摘要绝不落盘；与文件 I/O 分离，I/O 留应用层）
domain/task/
└─ VideoTask            # 已有：+isEventSettled()（终态谓词单源）+active 状态谓词（供 repository 引用）
   TaskArtifactScan     # 或等价：产物可见性过滤政策（*.srt + segments-*.json、排除 terms-/context-）
```

**归属判据**：跨上下文共用词汇进 `domain/shared/`（`WorkspaceDir`/`SourceUri` 与 Phase 2 `WorkspaceKey` 同判据——`VideoTask` 持有字段、media 侧生产使用）；单上下文政策进对应上下文包。政策对象是值对象/纯函数领域服务，**不是**技术端口（quality-guidelines「不预先抽接口」不受影响）。

## 2. 下沉映射表（逻辑块 → 目标）

明细行号见调研报告 §二，此处按目标归组：

| 目标 | 来源逻辑块（服务 @ 行） | 测试随迁 |
|---|---|---|
| `SourceUri` | WorkspaceService `normalize` L177-185、`localPathKey` 公式部分 L196-213（stat 留应用，三元组入参） | WorkspaceServiceTest 对应条目 |
| `WorkspaceDir` | WorkspaceService `dirName` L120-122、`sanitizeName`/`isReservedName`/RESERVED_NAMES L246-287 | 同上（目录名公式断言） |
| `WorkspaceKey.ofHash` | WorkspaceService `shortHash` L297-307 + TaskService `KEY_LENGTH=12` L71 | `shortHashIsStableTwelveHexChars` 等 |
| `Bcp47Language` | SubtitleTranslationService `resolveTargetLanguage` L386-394、`resolveSourceLanguage` L406-411、`isSameMainLanguage`/`mainLanguageSubtag` L434-453、`skipAll` L466-470 | 该服务约半数测试（L271-348、L477-517、L559-649） |
| `TermVerification` | 同服务 `verifyTermsIfNeeded` L292-308、结论记账 L318-348 | 查证政策断言 |
| `CheckpointPolicy` | SubtitleService L133-139+L173-186、SubtitleCleanupService L144-151、SubtitleTranslationService L190-196+`hasTranslationArtifact` L126-138、AudioPreparationService extract 判据 L156-162 | 检查点断言四处合流 |
| 上下文政策 | SubtitleCleanupService L169-184 + SubtitleTranslationService L209-227（近逐字重复） | `summarizeDegradeDoesNotWriteContextFile` 等 |
| `VideoTask.isEventSettled()` | TaskController L258-262 + TaskSseHub L115-119（重复）→ 单源 | web 测试适配 |
| `VideoTask` active 谓词 | `VideoTaskRepository.findActiveByWorkspaceKey` L71 的 `in(PENDING,RUNNING)` | repository 测试适配 |
| 产物可见性过滤 | TaskService `scanArtifacts` L402-403 | 快照形状断言（不变） |
| `MediaInput.ofTaskInput` | TaskService L144 + TaskPipelineService `toMediaInput` L373-382（配对映射） | 管线测试适配 |
| `AudioArtifact` 指纹命名 | AudioPreparationService `audioFileName` L192-203、`sampleRateKilo` L211-216 | 3 条（L85/L98/L113） |
| StageEvent 词表 | `SubtitleTranslator.STAGE_TRANSLATE` 等 → domain/shared；TaskService L17 的 infrastructure import 消除 | — |

**零改动**：MediaAcquisitionService、TranscriptionService、ToolDiagnosticsService、TaskPipelineService（仅 `toMediaInput` 调用点与终态谓词引用替换）。

## 3. 关键决定与灰色回填

1. `WorkspaceDir`/`SourceUri` → `domain/shared/`：2026-10-06 所有者问询归属后按共享内核一致性确定（与 `WorkspaceKey` 同判据）；`sanitizeName`/保留名随 `WorkspaceDir` 走，目录名公式不跨层。
2. `localPathKey` 切两层：身份公式（域）接受 `(path, size, mtime)` 三元组；文件 stat 留 application（技术 I/O）。
3. 检查点政策落 `domain/subtitle/`（四个使用场景全在字幕上下文与音频准备）；「空转写合法」契约已在 `SubtitleTrack`，**不重复搬运**。
4. Controller 双份卫语句保留（error-handling.md「两问两答」判据）；只收编 `isTerminal` 重复。
5. 灰色 8 项裁决全文见调研报告 §三（6 项按推荐回填：translateTask 序列留编排、文案留编排、ISE 保持、stageEventOf 留应用、localPathKey 切两层、上下文政策抽共享；2 项所有者已定：sanitize 随行、shared 五件不动）。
6. 行为等价策略：纯函数搬家不改算法体；有状态/含 I/O 的块切层（域核 + 应用壳）；每批以现有测试为等价护栏，断言随迁不改值。

## 4. 批切（每批一 commit，延续 feature/ddd-four-layer 线）

- **Phase A**：Workspace 家族（`SourceUri`/`WorkspaceDir`/`WorkspaceKey.ofHash`）+ WorkspaceService 瘦身 + 测试随迁。串行单批（公式跨 TaskService/WorkspaceService/Repository 纠缠）。
- **Phase B**：字幕政策（`Bcp47Language` + `TermVerification` + `CheckpointPolicy` + 上下文政策）+ `STAGE_TRANSLATE` 归位 + 三字幕服务与 AudioPreparationService 瘦身。串行单批（政策与服务互相纠缠）。
- **Phase C**：零散谓词与工厂（`isEventSettled`、active 谓词、产物可见性、`MediaInput.ofTaskInput`、指纹命名）+ spec 同步（directory-structure.md 第二批值对象事实、quality-guidelines 如涉）。可与 B 并行评估（文件所有权不相交则并行，按准入测试定）。

## 5. 风险

- **行为等价**是最大风险（vs Phase 1–4 的结构等价）：护栏 = 现有 441 测试 + 断言随迁不改值 + 每批 diff 审查「算法体零改动」。
- 测试迁移量大（Workspace ~18 条 + Translation ~15 条 + 零散），随批走、不攒。
- 陈旧 class bean 冲突教训（Phase 4）：门禁统一 `mvn clean test`。
- `Bcp47Language` 命名与形态（值对象 vs 领域服务）实现时按最小形态定，不预铺抽象。

## 6. 回滚

- 每批独立 commit 可单独 revert；Phase A 失败不可修则整批 revert，不影响 Phase B/C 的规划独立性（B/C 不依赖 A 的产物——subtitle 政策与 workspace 值对象零交集）；Phase D 与 A/B/C 无产物依赖（读模型拆分不触碰下沉逻辑）。

## 7. Phase D：读模型拆分（2026-10-06 追加，项目所有者选定方案）

- **方案**（所有者原话方案化）：`TaskView` 改名 `TaskResponse` 落 `web/dto/`（保留 @Schema）；`application/task/query/` 放**无注解**读模型 `TaskSnapshot`。application 不依赖 web：映射以静态工厂 `TaskResponse.from(TaskSnapshot)` 放 web/dto 侧，controller 调用。
- **定案链**：所有者先质疑读模型落位（CQRS 判据解释）→ 提出去 @Schema（前提「测试接口残留」被前端 5 处消费证据否决，@Schema 保留在活契约上）→ 最终选定拆分：@Schema 隔离到 web/dto，模型纯净留在 application。artifacts（查询期文件扫描）留应用侧读模型，无「查询期数据进域」问题——此变体优于域快照方案。
- **落位**：
  - `web/dto/TaskResponse`：@Schema 齐全（类 + 11 组件），`from(TaskSnapshot)` 静态映射；
  - `application/task/query/TaskSnapshot`：零 web 注解，字段与 TaskResponse 一一对应（taskNo 保持 String）；
  - `TaskService` 组装/返回 `TaskSnapshot`；`TaskConflictException` 携带 `TaskSnapshot`；`TaskConflictResponse` 内嵌 `TaskResponse`；`ApiExceptionHandler` 做异常载荷映射；
  - 4 端点（submitUrl/submitUpload/getTask/translate）返回 `TaskResponse`；SSE 链路走 `TaskProgressMessage` 不受影响。
- **spec 约定**（并入 Phase C 的 spec 同步）：directory-structure.md「契约类的位置」补判据——对外响应类命名 `XXResponse`、落 `web/dto/`、@Schema 集中于此；应用层读模型无 web 注解、落 `<上下文>/query/`。`SubmitUrlRequest`/`TaskConflictResponse` 已符合命名。
- **前端零感知**：JSON 字段名/类型逐一不变；前端 `types/task.ts` 零改动。
