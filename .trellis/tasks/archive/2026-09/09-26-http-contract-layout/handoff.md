# 交接：HTTP 契约归位与包结构规范修订

> 规划已于 **2026-09-26 经项目所有者审查通过**。本文件给**新会话**一个起点；细节在 [`prd.md`](./prd.md) / [`design.md`](./design.md) / [`implement.md`](./implement.md)。

## 状态

| | |
|---|---|
| 任务 | `09-26-http-contract-layout` —— **已批准进入实现**（2026-09-26） |
| 分支 | `feature/http-contract-layout`，位于 **`f210fe6`**（= 当前 `dev`） |
| `base_branch` | `dev` —— **合并链只有一层**（本任务 → `dev`） |
| 可接受的脏路径 | 仅 `.claude/settings.json`（本机工具配置，非本任务）+ 本任务目录 |

## 从这里开始

1. **先跑 `implement.md` 的「前置门」**——四条检查全绿才动手。任何一条不过就停下查原因，不要绕过
2. 按 `implement.md` 的 **步骤 0 → 8** 顺序执行。**每个回滚点完成后停下等业主审查**，不要连续推进
3. **动手前必读 `design.md` 的 §二（三条归属判据）、§六（13 处规范改写清单）、§七（取舍与破例）**——这三节是全部设计和判断依据所在

## 关键上下文（上一会话产出，不在上面三份文档里）

- **MVP-2a 已归档、已并入 `dev`**；它的 Phase 3.3 规范回写**也已完成**（`8a05a75`）。**本任务面对的规范是最新的**——包括 `error-handling.md` 已写明全局 handler、`provider-pattern.md` 已补 §2b 与 Jackson 3 包名坑
- **`dev` 领先 `origin/dev` 若干提交，未推送**（业主明确指示「先不推送」）。本项目**有 remote**（`origin` → `https://github.com/HQLiLi/vidSage.git`）；推送需单独批准
- 上一会话**犯过三个错并已留档**（`.trellis/workspace/HeQiu/traces/20260926-114244-http-contract-layout/summary.md`）。其中与本任务直接相关的一条：
  > **`09-25-vidsage-mvp2a-media-to-audio/implement.md:193`（现已在 archive/ 下）把「`model/` 禁止框架注解（Jackson 除外）」误引为出自 `provider-pattern.md`，实际住在 `directory-structure.md:36`。** 本任务 design.md §六 末尾记了这条勘误——**别去 `provider-pattern.md` 找一条不存在的规则**。
- 另两条错误（与本任务间接相关）：曾误判 `AsrController` 与 `MediaController` 的响应策略分裂是缺陷（实为「形状能否直接对外」这一判据的两个分支）；曾照搬过时记忆断言「本项目没有 remote」

## 不要做

- **不要提交 / 合并 / 推送**而不经业主逐门批准
- **不要顺手改本任务范围外的代码**——尤其 `provider-pattern.md`（本任务明确不改它）
- **不要引用历史测试数字**——每次实测（命令见 `implement.md` 步骤 0；`mvn -q` 会吞 summary，必须读 surefire 报告）
