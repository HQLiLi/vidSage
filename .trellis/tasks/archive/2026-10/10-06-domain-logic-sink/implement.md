# 执行计划 — 业务逻辑下沉 domain

> 前置阅读：implement.jsonl → prd.md → design.md（目标布局与映射表）→ [research/logic-distribution.md](./research/logic-distribution.md)（逐方法行号锚点，实施时对照）。
> 分支：延续 `feature/ddd-four-layer` 线（其合并后自新基线拉任务分支）；Phase A/B/C 各一 commit。

## 验证命令（全阶段通用）

```bash
# 门禁 1：行为等价护栏（必须 clean，防陈旧 class bean 冲突）
cd backend && mvn clean test

# 门禁 2：单源验证（AC3）
grep -rn "KEY_LENGTH\|KEY_WIDTH" backend/src/                    # key 宽度常量仅一处
grep -rn "isEventSettled\|isTerminal" backend/src/ --include="*.java" | grep -v "VideoTask.java"  # 终态谓词无第二实现
grep -rn "PENDING, RUNNING\|in(PENDING" backend/src/ --include="*.java"   # active 谓词仅 repository 引用域谓词

# 门禁 3：对外形状
# TaskView / TaskProgressMessage / 各 @Schema 契约类零语义 diff（web 仅 isTerminal 调用改动）
```

## Phase A —— Workspace 寻址家族（design.md §4-A）

- [ ] 1. 新建 `domain/shared/SourceUri`（normalize 公式；localPathKey 切两层：公式收 `(path,size,mtime)` 三元组）、`domain/shared/WorkspaceDir`（dirName 公式 + sanitizeName/保留名随行）；`WorkspaceKey` 增 `ofHash` 工厂吸收 shortHash（宽度 12 单源）。
- [ ] 2. `WorkspaceService` 瘦身：保留 `workspaceFor` 编排 / `rootDir` / `ensureCreated`（文件系统操作），业务公式改调 domain；`TaskService` 的 `KEY_LENGTH` 与 key 计算改走工厂。
- [ ] 3. 测试随迁：WorkspaceServiceTest 规则断言（约 18 条）迁 domain/shared 对应测试，断言值不变；submitUpload 相关 4 条改断言目标。
- [ ] 4. 门禁 1–3 + diff 审查（算法体零改动）→ commit Phase A。

## Phase B —— 字幕语种/术语/检查点政策（design.md §4-B）

- [ ] 1. 新建 `domain/subtitle/Bcp47Language`（resolve*/isSameMainLanguage/mainLanguageSubtag/skipAll）、`TermVerification`（触发三条件 + 记账 + 失败词不进产物/全失败不落盘）、`CheckpointPolicy`（JSON 非空=完成 + 0 字节判据 + SRT 派生不参与 + 上下文缓存-or-摘要/空摘要不落盘政策）。
- [ ] 2. `STAGE_TRANSLATE` 等阶段常量归位 `domain/shared/StageEvent` 一族；消除 TaskService 对 `SubtitleTranslator` 的常量 import；三字幕服务 + AudioPreparationService 瘦身改调政策对象。
- [ ] 3. 测试随迁：SubtitleTranslationServiceTest 约半数、SubtitleServiceTest 3 条、Cleanup 检查点/降级断言迁 domain/subtitle；断言值不变。
- [ ] 4. 门禁 1–3 + diff 审查 → commit Phase B。

## Phase C —— 零散谓词与工厂 + spec 同步（design.md §4-C）

- [ ] 1. `VideoTask.isEventSettled()` 单源化（TaskController/TaskSseHub 改调用）；active 谓词收进 `VideoTask`，repository 引用；产物可见性过滤 → domain/task 政策；`MediaInput.ofTaskInput` 工厂吸收 toMediaInput 配对映射；音频指纹命名 → `domain/media/`（AudioArtifact 工厂或伴生类）。
- [ ] 2. spec 同步：directory-structure.md 补「第二批值对象（SourceUri/WorkspaceDir）已落地」与政策对象归属事实；quality-guidelines / error-handling 如有受影响表述一并更新。
- [ ] 3. 门禁 1–3 + 任务末全量 `mvn clean test` + diff 审查 → commit Phase C。
- [ ] 4. 工作流收尾（journal、finish-work 由项目所有者触发）。

## Phase D —— 读模型拆分（design.md §7，2026-10-06 追加 R7/AC7）

- [ ] 1. `application/task/query/TaskView` → `TaskSnapshot`（去掉全部 @Schema 注解，字段与组装逻辑不动）；新建 `web/dto/TaskResponse`（@Schema 齐全，`from(TaskSnapshot)` 静态映射）。
- [ ] 2. 切换调用面：`TaskService` 组装/返回 `TaskSnapshot`；`TaskConflictException` 载荷改 `TaskSnapshot`；`TaskConflictResponse` 内嵌改 `TaskResponse`；`ApiExceptionHandler` 补载荷映射；4 端点返回 `TaskResponse`；全部 import 更新。
- [ ] 3. spec：directory-structure.md「契约类的位置」补「XXResponse 命名 + web/dto 落位 + 应用读模型无注解」判据。
- [ ] 4. 门禁：`mvn clean test` 全绿；JSON 字段名/类型逐一不变（前端 `types/task.ts` 零改动）；`grep -rn "TaskView" backend/src/` 零残留。
- [ ] 5. commit Phase D。

## 回滚点

- 每批独立 commit 可单独 revert；B/C 与 A 无产物依赖（字幕政策与 workspace 值对象零交集），A 失败不阻塞 B/C。
- 越界信号：mvn test 出现非迁移类失败；断言值被改；对外契约类（TaskSnapshot/TaskResponse/TaskProgressMessage）出现语义 diff 或 JSON 字段名/类型变化；web 层出现 isTerminal 以外的逻辑改动。
