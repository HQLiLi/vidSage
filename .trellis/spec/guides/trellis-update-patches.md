# 本地对模板分发物的补丁台账

`trellis update` 会块级替换模板管理区（workflow.md 标签块、平台 hooks、注释块等）。
本文件记录已打的本地补丁；每次 `trellis update` 后逐条复查，被覆盖的按原文重打。

## P1 hook 解析正则行首锚定（2026-10-06）

- 位置：`.zcode/hooks/inject-workflow-state.py` 与 `.claude/hooks/inject-workflow-state.py` 的 `_TAG_RE`（约 204 行）
- 原因：未锚定行首的正则会把 prose 行尾的裸 `[workflow-state:xxx]`（如 workflow.md:149）当成块起点，形成幻影匹配吞掉后续真实状态块（2026-10-06 实测吞掉 5/8 块，每轮面包屑降级）
- 补丁：正则改为 `^\[workflow-state:([A-Za-z0-9_-]+)\][ \t]*\r?\n(.*?)^\[/workflow-state:\1\][ \t]*\r?$`（DOTALL | MULTILINE）
- 复查方法：冒烟命令 `echo '{"session_id":"t","hook_event_name":"UserPromptSubmit","prompt":"x"}' | python .zcode/hooks/inject-workflow-state.py`，输出应含 no_task 详细指引而非 "Refer to workflow.md for current step."
- 关联决策：workflow.md:149 本身不动（管理注释区，等上游修复）；147–153 行 "Dispatch protocol mirror" 注释在 2026-10-06 面包屑瘦身（任务 10-06-fix-workflow-state-parser）后不再对应实际镜像，保留不修
- 另记：workflow.md 尾部 "Full contract" 引用的 `.trellis/spec/cli/backend/workflow-state-contract.md` 与 `.trellis/scripts/inject-workflow-state.py` 本地不存在（上游模板死链），无需处理

## 编辑 workflow.md 的纪律（随 P1 事故沉淀，2026-10-06）

- 面包屑块（`[workflow-state:*]`）每轮注入所有会话，官方建议每块 ≈200 字节：只放硬门 + 指针；长规则写进 Phase 正文（每会话注入一次）或 guides 正典（按需），不要整段塞进面包屑。
- prose/注释里引用标签时，绝不让 `[workflow-state:xxx]` 出现在行尾（后面只跟换行）——即使本地正则已锚定行首也保持此习惯，上游模板副本未必都打了补丁。
- 块标签必须顶格（行首无缩进）；STATUS 名限 `[A-Za-z0-9_-]+`（英文），块正文可用中文。
- 编码与行尾：workflow.md 与两份 hook 保持 UTF-8 无 BOM；workflow.md 全 CRLF，编辑后检查无混合行尾（lone LF / lone CR 均为 0）。
