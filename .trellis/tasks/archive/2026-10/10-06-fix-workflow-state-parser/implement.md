# 执行计划（单批，4 步）

精确改动内容全部在 `research/diagnosis.md`，实施前先通读该文件 §1–§5。

1. **替换两份 hook 的 `_TAG_RE`**：`.zcode/hooks/inject-workflow-state.py` 与 `.claude/hooks/inject-workflow-state.py`，旧/新全文见 diagnosis.md §3。只动正则串与 flags 行，不改周边注释。
2. **重写 `.trellis/workflow.md` 两个块**：`[workflow-state:in_progress]`（现 254–266 行）与 `[workflow-state:in_progress-inline]`（现 278–288 行），新正文全文见 §4。文件为全 CRLF，编辑后必须跑 §6 V4 混合行尾检查；149 行与管理注释区一律不碰。
3. **新增补丁台账**：创建 `.trellis/spec/guides/trellis-update-patches.md`（全文见 §5），先读 `.trellis/spec/guides/index.md` 再按其现有条目格式收录。
4. **验证**：跑 §6 V1–V5 全部命令，汇报每条输出摘要；任何一条不过就修复后重跑。

回滚点：全部为未提交工作区改动，`git checkout -- <file>` 即回滚。
