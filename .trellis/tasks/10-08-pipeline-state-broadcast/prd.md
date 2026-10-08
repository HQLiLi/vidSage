# SubtitlePipeline 状态迁移 × 广播收口拆分

## Goal

把 SubtitlePipeline 的状态迁移（DB）与广播（SSE）两种终态收口拆开；两个 progressListener 的落库/广播职责理清；行为不变，另附两处已核实并发竞态的处置

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
