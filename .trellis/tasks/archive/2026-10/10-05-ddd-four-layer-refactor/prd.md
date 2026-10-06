# 后端 DDD 四层化重构（机械迁移 + 值对象增量 + spec 重写）

## Goal

把后端包结构从「顶层按架构角色拆、`model/` 与 `domain/` 两轴混排」的现状，重排为项目所有者 2026-10-05 选定的**经典四层 DDD** 结构（domain / application / infrastructure / web，领域层内按限界上下文分包），让领域概念有明确的家。

核心动作：`model/` 解散（领域词汇 → `domain/`，读模型与应用消息 → `application/`）；四个 provider 端口接口上移 `domain/`；`service/` 平移改名 `application/`；`provider/tool` + `model/process` 合并 `infrastructure/process/`；`subscribe/` 并入 `application/task/`；`utils/` 改名 `shared/`；`persistence/` 整体平移 `infrastructure/`。

用户价值：消除 model/ 与 domain/ 顶层并存的错位感；后续新代码有稳定的归属判据（四层 + 限界上下文）；HTTP/SSE 契约不变，前端零影响、零回归成本。

需求来源：[docs/ddd-refactor-handoff.md](../../../docs/ddd-refactor-handoff.md)（2026-10-05 与项目所有者收敛的规划共识，本文已吸收）。

## 已确认事实（2026-10-05 证据核验）

- 主代码 90 类、测试 39 类（另有两个无主类对应的测试辅助 `ModelProbeTest`、`ProcessHelperMain`），与交接文档 as-is 结构逐一相符。
- `AudioSource` 为纯契约 sealed interface（`AudioUrl` / `AudioFilePath` 两个内嵌 record），与 media 侧 `AudioArtifact` 零耦合：`SubtitleService.java:147` 是在边界处把 `AudioArtifact.audioPath()` 包成 `AudioSource.AudioFilePath` 再交给转写上下文 → 归属 `domain/transcription/` 成立。
- Spring 注册机制均不受包迁移影响：`VidSageApplication` 用 `@ConfigurationPropertiesScan` 根包全扫；4 处 `@EnableConfigurationProperties` 写在随迁的 provider 类自身上（`DashScopeAsrProvider:53`、`YtDlpRunner:30`、`DashScopeTermLookupProvider:41`、`ExternalToolDiagnostics:35`），改 import 即可；`VideoTaskMapper` 用 `@Mapper` 注解交启动类根包自动发现（无 `@MapperScan`、无 mapper XML）；`VideoTaskEntity` 表名走 `@TableName("task")` 注解。
- resources 仅 `application.yml` / `application-local.yml` / `schema.sql`，无按包路径引用的资源。
- `VideoTask.java` 头注释含「放进 domain/（与 model/ 并存、不合并）」段落，是 Phase 1 Javadoc 清理点（交接文档已预告）。
- 工作区现状：`VideoTask.java` 有一处未提交改动（删无用 import `lombok.AllArgsConstructor`，语义中性），`docs/ddd-refactor-handoff.md` 未跟踪。

## Requirements

- R1 结构迁移：按 design.md §2 映射表完成主代码 + 测试的包重排，目标树见 design.md §1。`model/`、`service/`、`subscribe/`、顶层 `provider/`、`utils/`、顶层 `persistence/` 六个旧包位消失。
- R2 Phase 1 纯机械迁移：仅文件移动 + package/import 重写 + 引用旧结构的 Javadoc 措辞清理，零逻辑变更。
- R3 Phase 2 值对象增量：`TaskNo`、`WorkspaceKey` 落地并切换 `VideoTask` 与相关调用方（含测试）；每层补 `package-info.java` 写明职责与依赖规则（含豁免清单）。
- R4 Phase 3 spec 重写：`.trellis/spec/backend/` 各文档中旧包路径清零，新依赖规则与豁免清单写进 spec。
- R5 契约不变：HTTP/SSE 路径与 JSON 形状不变，前端代码零改动。
- R6 每 Phase 独立 commit（`feature/ddd-four-layer` 分支），可独立 revert。
- R8（2026-10-05 追加，项目所有者选定范围）：`application/task/` 建角色子包 `query/`（读模型三件套）、`exception/`（两个应用异常）、`subscribe/`（中立 pub-sub 两类），服务与应用消息留根；其余 application 上下文与全部 domain 上下文不拆（一包一类空壳 / 连续词汇表）。纯机械迁移，对外 JSON/SSE 形状零变化。

## Acceptance Criteria

- [ ] AC1 `cd backend && mvn test` 全绿（Phase 1/2/3 结束时各跑一次，作为每阶段门禁）。
- [ ] AC2 主代码 + 测试中旧包名零残留：`grep -rn "com\.vidsage\.\(model\|service\|provider\|subscribe\|utils\|persistence\)" src/` 无命中（`com.vidsage.infrastructure.persistence` 属正常命中，按全限定名核对）。
- [ ] AC3 包结构与 design.md §1 目标树一致：domain 31 类 / application 18 类 / infrastructure 21 类 / web+config+根 12 类不动 / `shared/` 8 类改名迁入。
- [ ] AC4 Phase 1 commit 的 git diff 逐文件审查：仅含 package/import 行变更、文件移动与 Javadoc 措辞修正，无逻辑 diff。
- [ ] AC5 `.trellis/spec/backend/` 内旧包名 grep 零残留（历史决策记录保留时间线语境的除外）。
- [ ] AC6 前端目录零改动（`git diff --stat frontend/` 为空）；可选保险：起后端 + 前端跑一次提交 URL → SSE → 产物下载冒烟。
- [ ] AC7 `TaskNo` / `WorkspaceKey` 生效后，任务号与工作区键不再以裸 `String` 在领域与应用层流转（含测试同步切换）。
- [ ] AC8 `application/task/` 子包落位与 design.md §7 一致；`mvn test` 全绿；`TaskView` / `TaskProgressMessage` 等对外契约类仅 package/import 行 diff，JSON/SSE 形状零变化。

## Out of Scope

- 前端任何改动（契约不变，无需回归）。
- 依赖倒置正统化：`VideoTaskRepository`、`ExternalProcessRunner`、LLM worker 四类不抽接口（豁免清单见 design.md §3；升级触发条件：出现第二个存储实现或本地模型部署）。
- `SourceUri` / `WorkspaceDir` / `WorkspaceDir(Path)` 等第二批值对象（延后，按需跟进）。
- 任何业务逻辑、状态机、管线行为变更。
- `config/` 下沉：`SubtitleCleanupProperties` / `SubtitleTranslateProperties` 暂留 `config/`（若 Phase 1 迁移中发现纯属 LLM worker 实现细节，再按 provider-pattern 四件套原则下沉并记录）。

## Open Questions

（无阻塞项。交接文档 §7 六个开放点已按其内置推荐/委托逐项回填，见 design.md §4，随最终规划摘要一并提交项目所有者评审。）
