# 会话归档接入 /finish-work 收尾门 — PRD

## 背景

`session-archive` skill 已全局安装（`C:\Users\14683\.agents\skills\session-archive`，经 `~/.claude/skills/session-archive` 软链暴露给 Claude Code），功能是把当前会话清洗成 `transcript.jsonl` + `summary.md` 并写 `index.jsonl` 索引。目前只能靠用户手动 `/session-archive` 触发，收尾时经常漏掉。

项目已按自己的约定归档了 3 份到 `f:/personal_project/vidSage/session-archives/`，根 `.gitignore` 已忽略该目录。本任务把「会话归档」接进 Trellis 的收尾门，让它跟着 `/trellis:finish-work` 一起发生。

## 范围

1. **触发点**：`/trellis:finish-work` 的收尾流程里新增一步「归档会话」，排在 Step 4（记 journal）**之后**，作为最后一步。这样落盘的 transcript 已包含归档提交与 journal 提交，是本次会话最完整的快照。
2. **规则落到 canonical 文件**：收尾门规则的正本是 `.trellis/spec/guides/git-workflow.md`（项目本地文件，不在 `.trellis/.template-hashes.json` 登记里，`trellis update` 不会覆盖）。会话归档规则加为该文件规则 7「归档由用户发起」的延伸，并更新「收尾门的顺序」图。
3. **同步镜像**：`.trellis/workflow.md` 中带 SYNC MARKER 的收尾门镜像段落，以及两个平台的 finish-work 入口文件，在同一轮改动里一起更新，措辞一致。
4. **固定输出目录**：`f:/personal_project/vidSage/session-archives/`，作为项目规则写死；`/finish-work` 流程里**不再询问用户选目录**。这覆盖 session-archive skill 里「非 TTY 调用必须先问用户选哪个 --out」的默认行为——以项目规则为准，理由是该目录已存在且已 gitignore。
5. **每次必写 summary.md**：写作依据是**会话内记忆 + 脚本输出的统计**，不重读 `transcript.jsonl`（同一会话收尾时模型已掌握全过程，重读只是白烧 token）。summary 仍按 skill 规定的两段式格式（`## 用户摘要` + `## AI 检索段`）。
6. **平台覆盖**：Claude Code（`.claude/commands/trellis/finish-work.md`）与 Codex（`.agents/skills/trellis-finish-work/SKILL.md`）两个入口同步，两份内容本就同源。

## 不做

- 不加 `.trellis/config.yaml` 的 `after_archive` hook。看了 `.trellis/scripts/common/task_utils.py` 的 `run_task_hooks`，hook 成功时 stdout **不回显**给模型（只有失败/超时才打印），拿不到「该写 summary 了」的信号；而且每次 `task.py archive` 都会触发，同一会话多次归档会在磁盘上生成多个目录。收益不抵复杂度。
- 不改 session-archive skill 本身（全局 skill 保持平台无关、可复用），也不改它的 `archive_session.py`。
- 不把 `session-archives/` 纳入 git 跟踪，维持现状。
- 不改各任务自己的归档行为（`task.py archive` 语义不动）。

## 验收标准

- [x] `.claude/commands/trellis/finish-work.md` 与 `.agents/skills/trellis-finish-work/SKILL.md` 都含「归档会话」步骤，且位置在记 journal 之后。
- [x] 步骤文案里写死了 `--out` 目录、明确不再问用户、明确 summary 必写且不重读 transcript。
- [x] `.trellis/spec/guides/git-workflow.md` 含该规则，收尾门顺序图已更新。
- [x] `.trellis/workflow.md` 中所有相关 SYNC MARKER 镜像位置同步，措辞与正本一致（逐处 grep 核对，不留半更新）。
- [ ] 实跑验证：在某次会话收尾时执行该步骤，`session-archives/` 下新增目录且含 `transcript.jsonl` + `summary.md`，`index.jsonl` 出现对应条目。若本次会话本身适合当验证样本，就用本次会话跑。
      → 待验证。本任务自身的 `/trellis:finish-work` 收尾就是第一次实跑，届时用它验收。

## 落地位置

- `.claude/commands/trellis/finish-work.md` — 新增 Step 5
- `.agents/skills/trellis-finish-work/SKILL.md` — 同步同一 Step 5
- `.trellis/spec/guides/git-workflow.md` — 规则区 + 收尾门顺序图（正本）
- `.trellis/workflow.md` — Phase 3.5 段 + `[workflow-state:in_progress]` / `[workflow-state:in_progress-inline]` 镜像 + 相关 SYNC MARKER 说明
