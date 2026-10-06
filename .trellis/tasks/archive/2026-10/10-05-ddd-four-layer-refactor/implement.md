# 执行计划 — 后端 DDD 四层化重构

> 前置阅读顺序：本任务 implement.jsonl → prd.md → design.md（映射表与设计决定）→ 本清单。
> 分支策略：自 `dev` 拉出 `feature/ddd-four-layer`；Phase 1/2/3 各一个 commit，独立可 revert。

## 验证命令（全阶段通用）

```bash
# 门禁 1：测试全绿
cd backend && mvn test

# 门禁 2：主代码 + 测试旧包名零残留
#   （infrastructure/persistence 命中属正常，按全限定名 com.vidsage.infrastructure.persistence 核对）
grep -rn "com\.vidsage\.\(model\|service\|provider\|subscribe\|utils\|persistence\)" backend/src/

# 门禁 3（仅 Phase 3）：spec 旧包名零残留（历史决策记录的时间线语境除外）
grep -rn "com\.vidsage\.\(model\|service\|provider\|subscribe\|utils\|persistence\)" .trellis/spec/backend/
```

## Phase 1 —— 机械迁移（纯移动 + import 重写，无语义变更）

- [ ] 1. 从 `dev` 拉 `feature/ddd-four-layer`；确认工作区基线：仅 `VideoTask.java` 无用 import 删除（随迁收编）与未跟踪 `docs/ddd-refactor-handoff.md`。
- [ ] 2. 按 design.md §2 映射表 `git mv` 主代码，顺序建议：`domain/`（含 4 个端口接口上移）→ `application/`（service 平移 + 读模型 + subscribe 并入）→ `infrastructure/`（persistence 平移 + process 合并 + provider 各实现）→ `utils/` 改名 `shared/`。`web/` / `config/` / 根类 12 处不动。
- [ ] 3. 测试目录镜像迁移（39 类，结构跟随主代码；`ModelProbeTest`、`ProcessHelperMain` 两个测试辅助同表处理）。
- [ ] 4. 全局重写 package / import；`package-info.java`（若存在）随之更新。
- [ ] 5. Javadoc 措辞清理：先 `grep -rn "model/\|service/\|provider/\|subscribe/\|utils/" src/ --include="*.java"` 圈出注释引用，已知点：`VideoTask.java` 头注释「与 model/ 并存、不合并」段、`TaskSubscriptionRegistry`「顶层包 subscribe/」。只改措辞，不改语义。
- [ ] 6. 中间检查（可选但推荐）：分批移动后跑 `mvn -q compile` 增量确认。
- [ ] 7. 门禁：验证命令 1 + 2 全过。
- [ ] 8. `git diff` 逐文件审查：仅 package/import 行 + 文件移动 + Javadoc 措辞；越界信号（design.md §6）出现即停。
- [ ] 9. commit Phase 1（message 注明收编了 VideoTask 无用 import 预清理）。

**风险点**：迁移面约 70 类 + 39 测试类，量大但机械；最大风险是漏改 import 或 Javadoc 残留——门禁 grep 覆盖。`TaskView` 等对外 DTO 零迁移，契约风险为零。

## Phase 2 —— DDD 增量

- [ ] 1. 值对象落地：`TaskNo` → `domain/task/`，`WorkspaceKey` → `domain/shared/`（语义与归属见 design.md §3-6，执行期已按证据修订归属）。生成点与流转路径已探明（2026-10-05 grep）：`TaskService` 计算 workspaceKey → `VideoTask.newPending` 存储 → `MediaInput.InWorkspace` 携带 → `findActiveByWorkspaceKey` 查询。
- [ ] 2. `VideoTask` 与相关调用方切换到值对象（含测试同步切换）；对外 JSON 形状不变——读模型/SSE 消息序列化边界、web 路径变量、异常消息保持 `String`（design.md §3-6 约束）。注意：TaskNo 批与 WorkspaceKey 批文件所有权相交（VideoTask / TaskService / VideoTaskRepository / MediaInput 双方共写）→ 按 dispatch-protocol §5.6 合并单批，不并行。
- [ ] 3. 每层补 `package-info.java`：`domain`（含各上下文子包）、`application`、`infrastructure`、`shared`——写明层/上下文职责 + 依赖规则 + 豁免清单（design.md §1）。
- [ ] 4. 门禁：验证命令 1 + 2 全过。
- [ ] 5. commit Phase 2。

## Phase 3 —— spec 重写与收尾

- [ ] 1. `.trellis/spec/backend/directory-structure.md`：重写「model / domain / persistence / config 怎么选」一节 → 四层结构 + 依赖规则 + 豁免清单；`model/`、`service/`、`subscribe/`、`utils/`、顶层 `provider/` 的历史描述归档化（保留时间线语境）。
- [ ] 2. `.trellis/spec/backend/provider-pattern.md`：更新全部路径引用；「四件套」的接口位置改为 `domain/<上下文>/`。
- [ ] 3. `.trellis/spec/backend/database-guidelines.md`：persistence 路径更新。
- [ ] 4. `error-handling.md`、`logging-guidelines.md`、`quality-guidelines.md`、`index.md` 中旧包路径逐一更新。
- [ ] 5. 门禁：验证命令 3 全过（人工复核历史决策记录的保留段）。
- [ ] 6. commit Phase 3（收编未跟踪的 `docs/ddd-refactor-handoff.md`）。
- [ ] 7. 走工作流 Phase 3 收尾：spec 更新确认、journal 记录、任务归档。

## Phase 4 —— application/task 角色子包（2026-10-05 追加，R8/AC8/design §7）

- [ ] 1. `git mv` 7 类入子包 + 测试镜像：`query/`（TaskView、TaskArtifact、TaskArtifactFile）、`exception/`（TaskConflictException、TaskNotFoundException）、`subscribe/`（TaskSubscriptionRegistry、Subscription）；根留 TaskService、TaskPipelineService、TaskProgressMessage。
- [ ] 2. 全局 import 重写（消费方以 grep 为准：web/controller、web/sse、application/task 根、TaskPipelineService、各测试）；`application/task/package-info.java` 改写为子包结构描述（subscribe 中立承诺迁入 subscribe/ 子包描述）。
- [ ] 3. Javadoc 路径引用清理；spec 同步：directory-structure.md「二级包怎么分」补角色子包判据 + application/task 实例（或对应小节），其余 spec 引用如涉 application.task.TaskView 等旧 FQN 一并更新。
- [ ] 4. 门禁：`mvn test` 全绿；旧全限定名（`com.vidsage.application.task.TaskView` 等七个类旧 FQN）零残留；`TaskView` / `TaskProgressMessage` 仅 package/import 行 diff。
- [ ] 5. commit Phase 4（独立可 revert）。

## 回滚点（Phase 4 追加）

- Phase 4 一 commit，独立 revert；失败不可修则 `git revert` 后回到 4327563 状态。

## 可选验收保险（AC6）

起后端（`mvn spring-boot:run`）+ 前端，跑一次提交 URL → SSE 进度 → 产物下载冒烟。前端零改动是硬约束，此步为额外保险，默认可跳过。

## 回滚点

- 每 Phase 一 commit，独立 revert；Phase 1 失败不可修则整分支丢弃回 `dev`。
- 越界信号（任一出现即停）：diff 出现逻辑变更；`schema.sql` / `application.yml` 被改动；前端目录出现 diff。
