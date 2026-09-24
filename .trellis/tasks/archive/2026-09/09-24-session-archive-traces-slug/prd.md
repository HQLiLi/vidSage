# 归档目录对齐 Trellis traces 约定并给文件夹名加任务短名 — PRD

## 背景

上一轮接入的会话归档把根目录选在了仓库根的 `session-archives/`，与项目文档和脚本自身的约定都不一致：

- `AGENTS.md` 写明 `.trellis/workspace/` 存放 "per-developer journals and **session traces**"。
- 全局 skill 脚本 `C:\Users\14683\.agents\skills\session-archive\scripts\archive_session.py` 里有 `find_trellis_traces_dir()`：在 Trellis 项目里沿目录向上找 `.trellis/`，从 `.trellis/.developer` 读 `name=` 取开发者名（读不到则取 `workspace/` 下第一个子目录），返回 **`.trellis/workspace/<开发者>/traces`** 作为「项目内」候选。本项目即 `.trellis/workspace/HeQiu/traces`。

结果是文档承诺的 traces 目录一直是空的，4 份归档全在仓库根。`AGENTS.md` 那段位于 `<!-- TRELLIS:START -->` 受管块内，`trellis update` 会覆盖，因此**对齐脚本约定比改文档稳**。

另外两处问题：归档文件夹名是 `<时间戳>-<session id 前 8 位>`，不点进去看不出是哪个会话；`index.jsonl` 的 `title` 字段出现垃圾值（本会话被记为 `Base directory for this skill: ...`），根因是脚本用「第一条非注入的用户消息」当标题，而该条恰好是 `/session-archive` 展开出的 skill 正文。

**重要约束**：全局 skill 目录 `C:\Users\14683\.agents\skills\session-archive` **不在任何 git 仓库内**（自身及上级 `.agents` 都没有 `.git`），改它没有版本历史可回退，改前需自行留意。本任务对它的改动会影响用户所有项目，不只 vidSage。

## 范围

1. **归档根目录改为 `.trellis/workspace/HeQiu/traces`**。本项目规则 9 与两个平台的 finish-work Step 5 里的 `--out` 一并改到这个路径。
2. **目录名带任务相关短名**：格式 `<YYYYmmdd-HHMMSS>-<slug>`。slug 由模型在调用时通过新增的 `--slug` 传入，**必须与本次会话的任务/主题相关**（有 Trellis 任务时直接用任务 slug，如 `session-archive-traces-slug`；无任务时从用户请求提炼 2–5 个英文词），不得用无意义随机串。脚本对 slug 做安全化（剥离路径分隔符与 Windows 非法字符、压成 kebab、限长，安全化后为空则回退 `<时间戳>-<session id 前 8 位>`）。省略 `--slug` 时保持现有命名不变。
3. **修脚本标题启发式**：`title` 不得取命令展开 / skill 正文类消息（如以 `Base directory for this skill:` 开头、或整段是带 YAML frontmatter 的 skill 文档），应取第一条真正由用户书写的输入。
4. **迁移已有归档**：把 `session-archives/` 下 4 个归档目录与 `index.jsonl` 迁到 traces 目录，并重写 `index.jsonl` 里每条 `dir` 字段与各 `meta.json` 里的 `out_dir` 字段（两者都是绝对路径）。`source_rollouts`（原始 transcript 路径）不动。
5. **修正一处残缺归档**：`session-archives/20260924-122630-01a0d170/summary/summary.md` 是手工产物（ASR 本地文件上传修复那次），**多套了一层 `summary/` 目录**、没有 `transcript.jsonl`、也不在 `index.jsonl` 里。迁移时把 `summary.md` 上提到归档根，目录改名沿用现有时间戳与 sid8（无 slug 可考）。不试图重新生成 transcript。
6. **gitignore**：`.trellis/.gitignore` 增加 `workspace/*/traces/`（对话原文含 thinking，不进版本库）；根 `.gitignore` 移除已无用的 `session-archives/`。
7. **同步本项目四处镜像**：`.trellis/spec/guides/git-workflow.md` 规则 9、`.trellis/workflow.md` Phase 3.5、`.claude/commands/trellis/finish-work.md` Step 5、`.agents/skills/trellis-finish-work/SKILL.md` Step 5，命令统一为新 `--out` + `--slug`。
8. **全局 SKILL.md** 补 `--slug` 说明，并要求调用方总是传与会话相关的短名。

## 不做

- 不加 `config.yaml` 的 `after_archive` hook（上一轮已论证：hook 成功不回显、每次 archive 都触发）。
- 不把 traces 纳入 git 跟踪。
- 不改 Codex 侧会话解析逻辑，也不改清洗规则（截断上限、保留 thinking 等）。
- 不为残缺归档重新生成 transcript。
- 不动 `.trellis/workspace/index.md` 的文件结构示意图（该文件由 `add_session.py` 维护，另有更新机制）。

## 验收标准

- [x] 脚本 `--slug` 生效：新归档目录名为 `<时间戳>-<短名>`；传入含 `../`、`:`、空格的脏 slug 时被安全化且不逃出归档根。（22 个脏输入实测，均断言为归档根的直接子目录）
- [x] 省略 `--slug` 时目录名与改动前一致（`<时间戳>-<sid8>`），不破坏既有行为。
- [x] 标题启发式修好：`index.jsonl` 与 `meta.json` 的 `title` 不再取命令展开/框架噪音文本。（真实 rollout 重跑，标题取到用户原话；regex 单测 8 例通过）
- [x] `session-archives/` 下 4 个归档 + `index.jsonl` 全部迁到 `.trellis/workspace/HeQiu/traces/`，`index.jsonl` 与 `meta.json` 中的绝对路径已重写且指向新位置；残缺归档的 `summary.md` 已在归档根、不再嵌套。（另两个旧归档的嵌套布局也一并平掉——其 `meta.json`/index 引用本就断链）
- [ ] `git status` 中不出现 traces 目录（gitignore 生效）→ 已验证；`session-archives/` 已空并删除 → 已空，**删除留到收尾时**（收尾 Step 5 实跑后再删）
- [x] 本项目四处镜像措辞一致，命令均为新 `--out` + `--slug`（逐处 grep 核对）。
- [ ] 实跑验证：用本次会话在新路径归档一次，目录名带本次任务短名，`index.jsonl` 条目正确指向新目录。上一轮那份 `20260924-201608-9288e082`（命名不合新规）在验证后被新目录取代并删除。
      → 待验证。本任务自身的 `/trellis:finish-work` 收尾就是第一次实跑，届时一并完成"删除旧目录 + 删空 session-archives/"。

## 落地位置

- `C:\Users\14683\.agents\skills\session-archive\scripts\archive_session.py` — `--slug` 参数、目录名拼装、标题启发式
- `C:\Users\14683\.agents\skills\session-archive\SKILL.md` — `--slug` 说明与调用要求
- `f:\personal_project\vidSage\.trellis\spec\guides\git-workflow.md` — 规则 9（正本）
- `f:\personal_project\vidSage\.trellis\workflow.md` — Phase 3.5 及其 SYNC MARKER
- `f:\personal_project\vidSage\.claude\commands\trellis\finish-work.md` — Step 5
- `f:\personal_project\vidSage\.agents\skills\trellis-finish-work\SKILL.md` — Step 5
- `f:\personal_project\vidSage\.trellis\.gitignore` — 增 `workspace/*/traces/`
- `f:\personal_project\vidSage\.gitignore` — 移除 `session-archives/`
- `f:\personal_project\vidSage\.trellis\workspace\HeQiu\traces\` — 迁移目标
