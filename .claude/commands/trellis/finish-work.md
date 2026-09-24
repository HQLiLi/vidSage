# Finish Work

Wrap up the current session: archive the active task (and any other completed-but-unarchived tasks the user wants to clean up) and record the session journal. Code commits are NOT done here — those happen in workflow Phase 3.4 before you invoke this command.

## Step 1: Survey current state

```bash
python ./.trellis/scripts/get_context.py --mode record
```

This prints:

- **My active tasks** — review whether any besides the current one are actually done (code merged, AC met) and should be archived this round.
- **Git status** — quick visual on what's dirty.
- **Recent commits** — you'll need their hashes in Step 4 for `--commit`.

If `--mode record` surfaces other completed tasks not tied to the current session, surface them to the user with a one-shot confirmation: "These N tasks look done — archive them too in this round? [y/N]". Default is no; the current active task is always archived in Step 3 regardless.

## Step 2: Sanity check — classify dirty paths

Run:

```bash
git status --porcelain
```

Filter out paths under `.trellis/workspace/` and `.trellis/tasks/` — those are managed by `add_session.py` and `task.py archive` auto-commits and will appear dirty as part of this skill's own work.

For each remaining dirty path, decide whether it belongs to **the current task** or to **other parallel work** (e.g., another terminal window editing the same repo). Heuristics:

- Paths referenced in the current task's `prd.md` / `implement.jsonl` / `check.jsonl` → current task
- Paths in code areas matching the task's stated scope, or that you remember editing this session → current task
- Paths in unrelated areas you have no recollection of touching this session → other parallel work

Then route:

- **Any remaining path looks like current-task work** — bail out with:
  > "Working tree has uncommitted code changes from this task: `<list>`. Return to workflow Phase 3.4 to commit them before running `/trellis:finish-work`."

  Do NOT run `git commit` here. Do NOT prompt the user to commit. The user goes back to Phase 3.4 and the AI drives the batched commit there.
- **All remaining paths look unrelated** (other parallel-window work) — report them once and continue to Step 3:
  > "FYI, dirty files outside this task's scope — leaving them for the other window: `<list>`."
- **Genuinely unsure** — ask the user once: "Are `<list>` this task's work I forgot to commit, or another window's? (commit / ignore)" — then route per their answer.

## Step 3: Archive task(s)

```bash
python ./.trellis/scripts/task.py archive <task-name>
```

At minimum: the current active task (if any). Plus any extra tasks the user confirmed in Step 1. Each archive produces a `chore(task): archive ...` commit via the script's auto-commit.

If there is no active task and the user did not confirm any cleanup archives, skip this step.

## Step 4: Record session journal

```bash
python ./.trellis/scripts/add_session.py \
  --title "Session Title" \
  --commit "hash1,hash2" \
  --summary "Brief summary"
```

Use the work-commit hashes produced in Phase 3.4 (visible in Step 1's `Recent commits` list, or via `git log --oneline`) for `--commit`. Do not include the archive commit hashes from Step 3. This produces a `chore: record journal` commit.

Final git log order: `<work commits from 3.4>` → `chore(task): archive ...` (one or more) → `chore: record journal`.

## Step 5: Archive this session

Ask the user first — this step has two outcomes and the choice is theirs:

- **Full trace** — run the archiver below, then write `summary.md` as described further down.
- **Summary only** — do not touch the traces directory. This session's record is the journal entry Step 4 already wrote; that is finish-work's original behaviour.

If the user does not answer (silence, or they skip the question), take **summary only** and stop here — never run the archiver on your own initiative.

This step is not a gate: it touches no git history, no push, no merge, and writes only local files inside the gitignored traces directory.

```bash
python ~/.agents/skills/session-archive/scripts/archive_session.py \
  --platform claude \
  --slug <this session's topical short name> \
  --cwd f:/personal_project/vidSage \
  --out f:/personal_project/vidSage/.trellis/workspace/HeQiu/traces
```

The output directory is fixed by project rule — do **not** prompt the user to choose one (the question you ask is *whether* to generate, not *where*). `--slug` must be topical: use this session's Trellis task slug when there is one, otherwise 2–5 English words drawn from the user's request. The directory name is how you tell archives apart without opening them, so never omit it or pass a meaningless string. The script prints the archive directory and its cleaning stats.

Then write `summary.md` into that directory yourself, from what you already know of this session plus the script's stats. Do **not** re-read `transcript.jsonl` — this is the same session that just ran, so re-reading only burns context. Two sections, in the user's language:

- `## 用户摘要` — three to five plain sentences: what this session did and how it turned out.
- `## AI 检索段` — factual and dense, no rhetoric. First line: date + session id + one-line title (the title may quote the user's original request). Then short lines: user request points (keep the user's own keywords), absolute paths of files touched, commands run and their verification results, key decisions and why, and anything failed or left unfinished. No secrets or credentials.

Report the full archive directory path at the end (full-trace branch only; under summary only, say the traces directory was left untouched by the user's choice).

Canonical rule: rule 9 of `.trellis/spec/guides/git-workflow.md`.
