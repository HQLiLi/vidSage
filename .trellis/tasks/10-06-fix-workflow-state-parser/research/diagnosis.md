# 诊断与精确改动内容（2026-10-06 会话实证）

> 本文件是实施的唯一改动依据：所有旧/新字符串全文在此，实施时不必自行推导。

## 1. 故障链（证据）

1. workflow.md:149（管理注释块 SYNC MARKER 行）行尾裸露 `[workflow-state:in_progress-inline]`，来自提交 `f7459d2`（10-05 派发协议并行化）。
2. 两份 hook 的 `_TAG_RE` 未锚定行首：`\[workflow-state:([A-Za-z0-9_-]+)\]\s*\n(.*?)\n\s*\[/workflow-state:\1\]`——149 行行尾标签满足"标签后仅空白+换行"的开启条件，形成 **149–288 行、13925 字符的幻影匹配**。
3. finditer 非重叠扫描被幻影吞段后，解析字典只剩 3 块；`no_task` / `task_error` / `planning` / `planning-inline` / `in_progress` 五个状态全部丢失。
4. 后果（实测）：无任务时每轮 breadcrumb 降级为 "Refer to workflow.md for current step."；若走 inline 模式，每轮注入 13925 字符幻影正文。
5. 受影响副本：`.zcode/hooks/inject-workflow-state.py:204` 与 `.claude/hooks/inject-workflow-state.py:204`（同款正则）。

## 2. 体积数据（in_progress 块 3556B 的来源，git blame 分桶）

| 来源 | 字节 | 占比 | 行 |
|---|---|---|---|
| `f7459d2` 10-05 派发协议并行化 | 2493B | 70% | 255, 258–263 |
| `312f690` 09-24 分支工作流 v4 | 570B | 16% | 256–257 |
| `91bb19c` 09-23 上游模板 | 493B | 13% | 264–265 |

官方文档（docs.trytrellis.app/zh/advanced/custom-workflow）要求每个面包屑块 ≈200 字节（每轮注入）。瘦身依据：Parallelism/Tier test/Round rules/Worktree ritual 四条细则的全文已在 Phase 2.1 正文（SessionStart 通道）与 `dispatch-protocol.md`（按需正典），面包屑只需硬门 + 指针。

## 3. R1：`_TAG_RE` 替换（两份 hook 副本，内容完全相同）

**旧**（两副本均在 `:204` 附近）：

```python
_TAG_RE = re.compile(
    r"\[workflow-state:([A-Za-z0-9_-]+)\]\s*\n(.*?)\n\s*\[/workflow-state:\1\]",
    re.DOTALL,
)
```

**新**（已对该仓库 workflow.md 实测 8/8 全解析）：

```python
_TAG_RE = re.compile(
    r"^\[workflow-state:([A-Za-z0-9_-]+)\][ \t]*\r?\n(.*?)^\[/workflow-state:\1\][ \t]*\r?$",
    re.DOTALL | re.MULTILINE,
)
```

只改正则串与 flags 行，不改周边注释。设计说明：`^` 锚定配合 MULTILINE 要求块标签必须顶格（真实块全部顶格，prose 提及均在行中有缩进/前置文字，天然排除）；`[ \t]*\r?\n` 容忍行尾空格与 CRLF；`\r?$` 同理。

## 4. R2：两个面包屑块重写（`.trellis/workflow.md`，全 CRLF）

**旧块 1**：`[workflow-state:in_progress]`（现 254–266 行，含 Tools/Flow/Wrap-up gates/Dispatch granularity/Dispatch protocol mirror 4 条/Main-session default/Dispatch prompt 共 11 行正文）——整块替换为：

```
[workflow-state:in_progress]
流程：派发 `trellis-implement` → `trellis-check` → `trellis-update-spec` → 停下等用户审查（Phase 3.4 门）→ 批准后提交。
硬门：完成即停等审查；禁止链式 commit/push/merge/归档；审查意见回 2.1 修后走新 3.4 批；未归档前每轮结尾提醒 `任务 <name> 未归档 — 审查确认后跑 /trellis:finish-work`。
派发硬规：单批 ≤3-4 步，超了先拆批；派发词以 `Active task: <task path>` 开头；子代理内长输出命令必须 tail/grep 截断。
细则全文：.trellis/spec/guides/dispatch-protocol.md · .trellis/spec/guides/git-workflow.md
[/workflow-state:in_progress]
```

**旧块 2**：`[workflow-state:in_progress-inline]`（现 278–288 行）——整块替换为：

```
[workflow-state:in_progress-inline]
流程：`trellis-before-dev` → 直接改代码 → `trellis-check` → 验证 → `trellis-update-spec` → 停下等用户审查（Phase 3.4 门）→ 批准后提交。
硬门：完成即停等审查；禁止链式 commit/push/merge/归档；审查意见回 2.1 修后走新 3.4 批；未归档每轮提醒。inline 模式不派发 implement/check 子代理。
批次纪律：单批 ≤3-4 步；并行准入 = 无跨批依赖 + 逐文件所有权清单；细则全文 .trellis/spec/guides/dispatch-protocol.md。
[/workflow-state:in_progress-inline]
```

**不动**：149 行及整个管理注释区、其余五个状态块、Phase 正文。管理注释区 147–153 行的 "Dispatch protocol mirror" SYNC MARKER 描述在瘦身后不再有对应四条镜像——**有意保留不修**（减少管理区冲突面），此偏差记录在 R4 台账。

## 5. R4：补丁台账新文件全文

`.trellis/spec/guides/trellis-update-patches.md`：

```markdown
# 本地对模板分发物的补丁台账

`trellis update` 会块级替换模板管理区（workflow.md 标签块、平台 hooks、注释块等）。
本文件记录已打的本地补丁；每次 `trellis update` 后逐条复查，被覆盖的按原文重打。

## P1 hook 解析正则行首锚定（2026-10-06）

- 位置：`.zcode/hooks/inject-workflow-state.py` 与 `.claude/hooks/inject-workflow-state.py` 的 `_TAG_RE`（约 204 行）
- 原因：未锚定行首的正则会把 prose 行尾的裸 `[workflow-state:xxx]`（如 workflow.md:149）当成块起点，形成幻影匹配吞掉后续真实状态块（2026-10-06 实测吞掉 5/8 块，每轮面包屑降级）
- 补丁：正则改为 `^\[workflow-state:([A-Za-z0-9_-]+)\][ \t]*\r?\n(.*?)^\[/workflow-state:\1\][ \t]*\r?$`（DOTALL | MULTILINE）
- 复查方法：见下节冒烟命令，应输出 no_task 详细指引而非 "Refer to workflow.md for current step."
- 关联决策：workflow.md:149 本身不动（管理注释区，等上游修复）；147–153 行 "Dispatch protocol mirror" 注释在 2026-10-06 面包屑瘦身（任务 10-06-fix-workflow-state-parser）后不再对应实际镜像，保留不修
- 另记：workflow.md 尾部 "Full contract" 引用的 `.trellis/spec/cli/backend/workflow-state-contract.md` 与 `.trellis/scripts/inject-workflow-state.py` 本地不存在（上游模板死链），无需处理
```

在 `.trellis/spec/guides/index.md` 按其现有条目格式收录一行（先读该文件再定格式）。

## 6. 验证命令（check 阶段全跑）

```bash
# V1 锚定正则 8/8 + 块字节数（同时覆盖 AC1）
python <<'EOF'
import re
text = open('.trellis/workflow.md', 'rb').read().decode('utf-8')
R = re.compile(r"^\[workflow-state:([A-Za-z0-9_-]+)\][ \t]*\r?\n(.*?)^\[/workflow-state:\1\][ \t]*\r?$", re.DOTALL | re.MULTILINE)
ms = {m.group(1): len(m.group(2).encode('utf-8')) for m in R.finditer(text)}
print(len(ms), 'blocks:', ms)
assert len(ms) == 8 and ms['in_progress'] <= 600 and ms['in_progress-inline'] <= 600
print('V1 PASS')
EOF

# V2 冒烟（覆盖 AC2）：输出应含 "No active task"（no_task 详细指引），不含 "Refer to workflow.md for current step."
echo '{"session_id":"t","hook_event_name":"UserPromptSubmit","prompt":"x"}' | python .zcode/hooks/inject-workflow-state.py

# V3 两副本正则一致（覆盖 AC3）：两条 md5 应相同
grep -A2 '_TAG_RE = re.compile' .zcode/hooks/inject-workflow-state.py | md5sum
grep -A2 '_TAG_RE = re.compile' .claude/hooks/inject-workflow-state.py | md5sum

# V4 行尾检查（覆盖 AC6）：lone LF 与 lone CR 均应为 0
python -c "raw=open('.trellis/workflow.md','rb').read(); print('CRLF:',raw.count(b'\r\n'),'lone LF:',raw.count(b'\n')-raw.count(b'\r\n'),'lone CR:',raw.count(b'\r')-raw.count(b'\r\n'))"

# V5 文件范围（覆盖 AC5）
git diff --stat
```

## 7. 回滚点

全部改动为未提交工作区改动：`git checkout -- <file>` 逐文件回滚，或 `git stash` 整体回滚。
