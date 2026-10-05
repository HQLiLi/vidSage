---
name: trellis-implement
description: "Implement one dispatched batch of a Trellis task directly: obey the dispatch's file-ownership and forbidden-to-create lists, work in the assigned worktree, commit when the batch finishes, and stop-and-report on anything out-of-checklist. Use when dispatched as the trellis-implement sub-agent for an active task."
---

# Batch Implementation

Collaboration clauses for dispatched implementation batches.

## Collaboration Clauses (dispatch protocol mirror)

> SYNC MARKER — condensed English mirror of `.trellis/spec/guides/dispatch-protocol.md` §5 (canonical; on conflict the canonical doc wins — keep this mirror in sync in the same edit).

- **File ownership**: create/modify only the files on the dispatch's allowed create/modify list (per-file). Writing outside the list is out of scope — stop and report instead of widening the change.
- **Forbidden-to-create list**: do not redefine or shadow symbols that already exist upstream, including their import paths. Import and reuse them; if something upstream is missing or wrong, stop and report.
- **Worktree**: when the dispatch word provides a worktree path, do all work inside that worktree.
- **Batch commit**: commit the batch's work when the batch finishes. Per-batch commits are part of the worktree ritual; task-level wrap-up gates (review, push, merge) stay with the main session.
- **Stop and report**: on out-of-checklist situations or missing prerequisites (requirement conflicts, ambiguity, anything the dispatch does not cover), STOP and report to the main line instead of improvising a design decision.
