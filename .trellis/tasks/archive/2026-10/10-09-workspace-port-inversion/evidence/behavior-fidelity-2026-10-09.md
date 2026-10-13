# 行为保真核对（对照 design.md §五，2026-10-09）

**预期差异栏：空 ✓**（纯结构迁移，无行为变更）

| 项 | 核对结果 | 证据位置 |
|---|---|---|
| IAE 文案「媒体输入不能为空：请传 RemoteUrl / LocalPath / InWorkspace」 | 逐字一致 | `WorkspaceKeyGenerator.parseMediaToWorkspaceKey` |
| ISE 文案「无法创建任务工作目录：…；请检查 vidsage.workspace.root 是否存在且可写。原因：…」 | 逐字一致 | `NioWorkspaceDirectoryManager.ensureCreated` |
| debug 日志「任务工作目录: dir={}」（只打 getFileName()） | 逐字一致 | 同上 |
| 根回退 `properties.root() == null` → `WorkspaceDir.FALLBACK_NAME` | 逐字一致 | `NioWorkspaceDirectoryManager.getRootDir` |
| 根纯解析（绝对路径、不建目录、同配置稳定） | 一致 | `getWorkspaceRoot()` = `getRootDir().toAbsolutePath()`，无副作用 |
| 目录名公式 `WorkspaceDir.of(label, key)` | 未动（domain 单源） | — |
| 副作用时点（卫语句先于一切 I/O；目录创建在解析之后） | 保持 | 4 个 Coordinator 调用点均为两行式（先 `parseMediaToWorkspaceKey` 后 `ensureWorkspaceDirectory`） |

**实现期命名偏差**（用户口径：方法名以动词开头，具体以代码为准）：

| design.md 示例名 | 实际代码名 |
|---|---|
| `workspaceFor(String, WorkspaceKey)` | `ensureWorkspaceDirectory` |
| `workspaceRoot()` | `getWorkspaceRoot()` |
| `WorkspaceKeyGenerator.resolve` | `parseMediaToWorkspaceKey` |
| `rootDir()` | `getRootDir()` |

结构上唯一非逐字的一处：`ensureWorkspaceDirectory` 里 `rootDir().resolve(...)` 改经
`getWorkspaceRoot().resolve(...)`（`toAbsolutePath()` 与 `resolve()` 可交换，最终路径逐字相同）。
