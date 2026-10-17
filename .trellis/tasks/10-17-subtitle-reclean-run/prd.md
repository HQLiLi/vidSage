# 字幕模型重构 4/8：重洗（RECLEAN run + 换版级联）

## Goal

RECLEAN run；W5 换版 + 下游级联（新 CLEANED READY 与下游全标 SUPERSEDED 同一事务）；C22/C23 两种终态（旧 CLEANED 覆盖型停 SUPERSEDED、下游译文孤儿型走 DELETED）。前置：S3。S5 依赖本段建立的「READY 即基于当前 CLEANED」口径。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
