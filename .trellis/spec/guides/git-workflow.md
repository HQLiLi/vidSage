# 分支工作流（硬规则）

> 2026-09-24 项目所有者确立；同日引入 `dev` 常驻集成分支（v2，替代仅 master 的 v1），改为「从当前分支开」（v3），并追加「完成即停、逐门批准」的收尾审查规则（v4）。适用于前后端所有代码与规范改动。

> **同步标记（SYNC MARKER）**：规则 2 / 3 / 7（改完即停、审查期间继续改、归档由用户发起 + 未归档每轮提醒）在 `.trellis/workflow.md` 里有简版镜像，一共三处——`[workflow-state:in_progress]`、`[workflow-state:in_progress-inline]` 的 `Wrap-up gates (hard)` 行，以及 Phase 3.4 / 3.5 的段落。**改这三条中的任何一条，必须同时改这三处镜像；反之亦然。**两边不一致时**以本文件为准**，并立刻把镜像补齐。镜像存在的理由：workflow.md 的 tag 块是每轮必注入的通道，本文件只在被加载时生效。

> 规则 9（归档后收尾会话归档）的镜像集不同，一共四处：本文件的规则 9 与收尾门顺序图、`.trellis/workflow.md` 的 Phase 3.5 段、`.claude/commands/trellis/finish-work.md` 与 `.agents/skills/trellis-finish-work/SKILL.md` 的 Step 5。**这四处必须同改。**

---

## 分支角色

| 分支 | 角色 | 谁能直接提交 | 更新时机 |
|---|---|---|---|
| `master` | 发布线，永远保持可发布 | 无人 | 仅用户明确要求发布时 `dev → master` |
| `dev` | **常驻集成分支**，日常工作的基准 | 无人 | feature 分支获用户批准后合并进入 |
| `feature/<短名>` | 承载一次修改 | 开发期间自由提交 | — |

## 规则

1. **先开分支再修改**：任何修改动手前，**从当前分支**开特性分支（`git checkout -b feature/<短名>`），全程在分支上改；未提交的改动会随新分支一起带走。**唯一例外**：当前在 `master` 上时，先切到最新 `dev` 再开——master 刻意落后于 dev，从它开会丢掉已批准未发布的改动。禁止直接在 `dev` 或 `master` 上修改
2. **改完即停**：修改完成、测试通过后**立刻停下**，把改动交给项目所有者审查（改了什么 / 怎么验证 / 哪些没验证）。**同一次执行里不得继续 `git commit`、`git push`、合并、归档或跑 `/trellis:finish-work`**——收尾的每条命令都是一道独立的门，门与门之间必须等到用户的明确批准。**用户沉默不等于批准**
3. **审查期间继续改**：审查窗口内用户提出修改建议 → 回到修改 → 重新验证 → 再次停下等审查。审查窗口原地循环，不新开门，也不得趁机提交
4. **批准提交后才提交并推送**：用户明确批准提交后，才执行 `git commit` + `git push -u origin feature/<短名>`；推送后回到审查窗口，由项目所有者审阅分支
5. **批准合并后才合并**：获得项目所有者的明确批准后，才把分支合并回 `dev`。AI 不得自行合并
6. **合并后删除冗余分支**：本地与远端的特性分支都删除，保持分支列表干净
7. **归档由用户发起**：只有用户跑 `/trellis:finish-work` 或明说「归档」时才归档；**AI 不得自行调用 `/trellis:finish-work` / `task.py archive`**。任务未归档期间，**每轮回复结尾固定一行**提醒：
   `任务 <name> 未归档 — 审查确认后跑 /trellis:finish-work`
   归档完成后停止提醒
8. **发布**：`dev → master` 仅在用户明确要求发布时执行，AI 不得自行发布合并
9. **归档后收尾会话归档**：`/trellis:finish-work` 归档任务后，**同一次执行内**继续做会话归档，不必再等一道门——它只写 `.trellis/workspace/HeQiu/traces/` 下已 gitignore 的本地文件，不碰 git 历史、不推送、不合并。命令固定（Claude Code 侧）：

   ```bash
   python ~/.agents/skills/session-archive/scripts/archive_session.py \
     --platform claude \
     --slug <本次会话的英文短名> \
     --cwd f:/personal_project/vidSage \
     --out f:/personal_project/vidSage/.trellis/workspace/HeQiu/traces
   ```

   Codex 侧把 `--platform claude` 换成 `--platform codex`。随后用会话内记忆 + 脚本统计写 `summary.md`（两段式：`## 用户摘要` / `## AI 检索段`，格式见 session-archive skill），**不重读 `transcript.jsonl`**。目录固定在 `.trellis/workspace/HeQiu/traces/`，流程中不得再询问用户选目录。`--slug` 必须与本次会话的任务/主题相关：有 Trellis 任务时直接用任务 slug，否则从用户请求提炼 2–5 个英文词——归档目录名是检索时辨认归档的入口，不得省略或填无意义串。另外，`--out` 指向的 `.trellis/workspace/HeQiu/traces` 正是 session-archive 脚本自己认的 Trellis「项目内」候选（见脚本 `find_trellis_traces_dir()`），与 `AGENTS.md` 中 `.trellis/workspace/` 存放 session traces 的说明一致；不要改回仓库根的 `session-archives/`。

## 收尾门的顺序

```
修改 + 测试通过
  └─ 门 1：停下 → 用户审查（中途可提修改建议 → 回到修改，原地循环）
       └─ 用户批准提交 → git commit + git push -u origin feature/<短名>
            └─ 门 2：用户审查分支 + 明确批准合并 → git merge --no-ff 到 dev + 删分支
                 └─ 门 3：用户主动归档 → /trellis:finish-work
                      └─ 归档提交 + journal 提交落地后 → 会话归档（写 .trellis/workspace/<dev>/traces/，不等门）
```

一次执行只推进一道门。任何一道门没等到用户明确表态，就停在原地，并在汇报里写清「当前卡在哪道门、等什么」。

## 操作序列

```bash
# 1. 从当前分支开分支（未提交改动会随新分支带走）
git checkout -b feature/<短名>

# 仅当当前在 master 上时，先切 dev 再开：
git checkout dev && git pull && git checkout -b feature/<短名>

# 2. 修改、测试通过 → 门 1：停下等用户审查（不要接着跑下面的命令）

# 3. 用户批准提交后：
git add ... && git commit -m "..."
git push -u origin feature/<短名>

# 4. 门 2：用户明确批准合并后：
git checkout dev && git pull
git merge --no-ff feature/<短名> && git push
git branch -d feature/<短名>
git push origin --delete feature/<短名>

# 5. 门 3：用户主动要求归档时（AI 不得自行触发）：
/trellis:finish-work

# 6. 仅当用户明确要求发布时
git checkout master && git pull
git merge --no-ff dev && git push
git checkout dev
```

## 边界情况

- **在 feature 分支上再开新分支（嵌套）**：新分支会包含旧分支未合并的提交，合并时按依赖顺序先合被依赖的分支，旧分支合入 dev 后新分支的合并自然变平凡；不想嵌套就先合并旧分支或切回 dev 再开
- **开发期间 dev 前进了**：合并前 `git checkout dev && git pull`，再在 feature 分支上 `git merge dev`（或 rebase），冲突在分支上解决后再推送
- **dev 与 origin/dev 有分叉**：先 `git pull` 对齐再开分支
- **一次会话多个不相关改动**：各自开分支，不混在一个分支里
- **用户在一轮里连说「提交吧」「合并吧」**：批准可以连着给，但每道门仍需用户自己的一句表态；不得由 AI 推断补全缺失的那道
- **审查期间提出修改**：仍在同一特性分支（同一任务）上继续改，不新开分支、不新开任务，除非用户要求单独立项
- **v1 时期的在途分支**（以 master 为基准开的）：合并目标按本版追溯为 `dev`

## 反模式

| 不要做 | 原因 |
|---|---|
| 直接在 `dev` / `master` 上修改提交 | 绕过审查，集成分支失去「已批准」的含义 |
| 未获批准自行合并（feature→dev 或 dev→master） | 审查批准权与发布时机都归项目所有者 |
| 一轮里连跑 提交 → 合并 → 归档 | 三道审查门被压成一次执行，用户失去全部批准点（2026-09-24 实际发生过一次） |
| 未经批准自行 `git commit` | 提交是门 1 之后才允许的动作，见规则 2 / 4 |
| AI 自行调用 `/trellis:finish-work` 或 `task.py archive` | 归档是用户的动作，自行归档等于绕过审查 |
| 改完不推送就在本地合并 | 跳过「推送 → 用户审查」这个可见点 |
| 把用户沉默当成批准 | 门必须由用户明确打开 |
| 合并后留下 feature 分支 | 冗余分支堆积，分支列表失真 |
| 一个分支塞多个不相关改动 | 审查粒度失控 |
| 未经用户要求执行 `dev → master` | 发布由项目所有者决定 |
