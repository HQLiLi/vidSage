# workflow-state 面包屑解析修复与瘦身

## Goal

根修 `.zcode` / `.claude` 两份 `inject-workflow-state.py` 的 `_TAG_RE` 行首锚定，修复 workflow.md:149 裸标签引发的幻影匹配（5/8 状态块被吞、每轮面包屑降级为通用文案）；并把 `in_progress` / `in_progress-inline` 两个面包屑块从 3378B/2689B 瘦身到 ≤600B 的中文"硬门 + 指针"形态；在 spec/guides 建模板补丁台账防 `trellis update` 回归。

## Requirements

- **R1 解析器根修**：`.zcode/hooks/inject-workflow-state.py` 与 `.claude/hooks/inject-workflow-state.py` 的 `_TAG_RE` 改为行首锚定版本，两份完全一致；使 prose 中行尾裸标签（workflow.md:149）不再被当作块起点。
- **R2 面包屑瘦身**：重写 `[workflow-state:in_progress]`（3378B）与 `[workflow-state:in_progress-inline]`（2689B）为中文"硬门 + 指针"形态，每块 body ≤600B；被删细则的全文本来就存在于 Phase 2.1 正文与两份 guides 正典。
- **R3 硬门保留**（镜像 git-workflow 规则 2/3/7 + 10-02 派发粒度规则）：完成即停等审查；禁止链式 commit/push/merge/归档；审查意见回 2.1 修后走新 3.4 批；未归档每轮提醒；单批 ≤3-4 步；派发词以 `Active task:` 开头；子代理长输出截断。
- **R4 补丁台账**：新增 `.trellis/spec/guides/trellis-update-patches.md`，记录 hook 正则补丁及"trellis update 后复查重打"条目，并按现有格式收入 `.trellis/spec/guides/index.md`。

## Constraints

- STATUS 名保持英文 `[A-Za-z0-9_-]+`（解析器字符集限制）；面包屑正文用中文；所有改动保持 UTF-8 无 BOM；workflow.md 保持全 CRLF（不允许混合行尾）。
- 不改动：149 行与管理注释区（101–158 行）、其余五个状态块（no_task / task_error / planning / planning-inline / completed）、Phase 正文（含 2.1 派发粒度中文全文）、两份 guides 正典全文。
- 明确不做：workflow.md 尾部 "Full contract" 一节的两个死链修复（`.trellis/spec/cli/backend/workflow-state-contract.md`、`.trellis/scripts/inject-workflow-state.py`，上游模板问题，走上游反馈渠道）。

## Acceptance Criteria

- [ ] AC1 锚定正则对当前 workflow.md 解析 8/8 块，`in_progress` 与 `in_progress-inline` 的 body 均 ≤600B
- [ ] AC2 冒烟测试（UserPromptSubmit、无活动任务）输出 no_task 详细分诊指引，不含降级文案 "Refer to workflow.md for current step."
- [ ] AC3 两份 hook 副本的 `_TAG_RE`（含 flags）逐字节一致
- [ ] AC4 补丁台账存在且被 `guides/index.md` 收录
- [ ] AC5 `git diff --stat` 仅触及预期 5 文件：两个 hook 副本、`.trellis/workflow.md`、`trellis-update-patches.md`、`guides/index.md`
- [ ] AC6 workflow.md 全 CRLF（lone LF / lone CR 计数均为 0）
