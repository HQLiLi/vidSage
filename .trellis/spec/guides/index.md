# Thinking Guides

> **Purpose**: Expand your thinking to catch things you might not have considered.

---

## Why Thinking Guides?

**Most bugs and tech debt come from "didn't think of that"**, not from lack of skill:

- Didn't think about what happens at layer boundaries → cross-layer bugs
- Didn't think about code patterns repeating → duplicated code everywhere
- Didn't think about edge cases → runtime errors
- Didn't think about future maintainers → unreadable code

These guides help you **ask the right questions before coding**.

---

## Available Guides

| Guide | Purpose | When to Use |
|-------|---------|-------------|
| [Code Reuse Thinking Guide](./code-reuse-thinking-guide.md) | Identify patterns and reduce duplication | When you notice repeated patterns |
| [Cross-Layer Thinking Guide](./cross-layer-thinking-guide.md) | Think through data flow across layers | Features spanning multiple layers |
| [Git Workflow Guide](./git-workflow.md) | 分支工作流硬规则：开分支 → 修改 → **停下等审查** → 批准后提交/推送 → 批准后合并 → 删分支；归档由用户发起，未归档期间每轮回复结尾提醒；归档后询问用户：完整生成 traces（`.trellis/workspace/<dev>/traces/`，带任务短名）或只留 journal 记录，沉默按后者；**并行战线**（所有者指定同时推进）用 `git worktree` 隔离，见规则 10 | 任何代码/规范修改动手前 |
| [Dispatch Protocol Guide](./dispatch-protocol.md) | 子代理派发协议正典：批次并行默认规则（依赖分层 + 所有权清单准入，单派发 ≤3–4 步）、检查拆三读面（读取面限 `git diff` 变更清单 + 锚点，命令关分离）、减轮数三条款、档位三问判据（高档/低档执行者，映射见文末适配层）、worktree 仪式 + 批次完成即 commit + 合并 SOP、执行台账必填列 | 规划批次、写派发词或派发任何子代理之前 |
| [Trellis Update Patches Ledger](./trellis-update-patches.md) | 本地对模板分发物的补丁台账：记录已打的本地补丁（hook `_TAG_RE` 行首锚定等）的位置、原因与内容；`trellis update` 会块级替换模板管理区，之后按台账逐条复查、被覆盖的按原文重打 | 每次 `trellis update` 之后 |
| [Comment Guidelines](./comment-guidelines.md) | **注释写法正典**（前后端唯一一份）：第一原理（删了会不会有人踩坑）+ 内容分流表（设计理由→任务卡 `design.md`、变更史→`git log`、包级地图→`package-info.java`、契约与实现坑留代码）+ 按层级的写法 + 本仓库真实正反例 + 防复发钩子脚本全文 | 写或改任何注释之前；收口既有注释之前；review 时判断「注释够不够」之前 |

---

## Quick Reference: Thinking Triggers

### When to Think About Cross-Layer Issues

- [ ] Feature touches 3+ layers (API, Service, Component, Database)
- [ ] Data format changes between layers
- [ ] Multiple consumers need the same data
- [ ] You're not sure where to put some logic
- [ ] You are adding an event kind, JSONL record, RPC payload, or config field
- [ ] UI / command code starts casting raw payload fields directly

→ Read [Cross-Layer Thinking Guide](./cross-layer-thinking-guide.md)

### When to Think About Code Reuse

- [ ] You're writing similar code to something that exists
- [ ] You see the same pattern repeated 3+ times
- [ ] You're adding a new field to multiple places
- [ ] **You're modifying any constant or config**
- [ ] **You're creating a new utility/helper function** ← Search first!
- [ ] Two files read the same untyped payload field with local casts
- [ ] Multiple branches update the same derived state from `kind` / `action`

→ Read [Code Reuse Thinking Guide](./code-reuse-thinking-guide.md)

### When Verifying AI Cross-Review Results

- [ ] Reviewer claims "user input can be malicious" → Check the actual data source (internal manifest? user config? external API?)
- [ ] Reviewer flags "missing validation" → Is the data from a trusted internal source?
- [ ] Reviewer says "behavior change" → Read the code comments — is it intentional design?
- [ ] Reviewer identifies a "bug" in test → Mentally delete the feature being tested — does the test still pass? If yes → tautological test

**Common AI reviewer false-positive patterns**:
1. **Trust boundary confusion**: Treating internal data (bundled JSON manifests) as untrusted external input
2. **Ignoring design comments**: Flagging intentional behavior documented in code comments as bugs
3. **Variable misreading**: Not tracing a variable to its actual definition (e.g., Map keyed by path vs name)

**Verification rule**: Every CRITICAL/WARNING finding must be verified against the actual code before prioritizing. Budget ~35% false-positive rate for AI reviews.

---

## Pre-Modification Rule (CRITICAL)

> **Before changing ANY value, ALWAYS search first!**

```bash
# Search for the value you're about to change
grep -r "value_to_change" .
```

This single habit prevents most "forgot to update X" bugs.

---

## How to Use This Directory

1. **Before coding**: Skim the relevant thinking guide
2. **During coding**: If something feels repetitive or complex, check the guides
3. **After bugs**: Add new insights to the relevant guide (learn from mistakes)

---

## Contributing

Found a new "didn't think of that" moment? Add it to the relevant guide.

---

**Core Principle**: 30 minutes of thinking saves 3 hours of debugging.
