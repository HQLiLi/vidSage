# 字幕模型重构 7/8：对账（只读对账端点 + 哈希检测）

## Goal

POST /api/maintenance/reconcile（C65 只读：四件事全报、②只报不修、不加鉴权）；报告两类行（带 task_no / 带目录名）；临时文件后缀白名单提到全局并拆掉 .info.json；哈希比对作为 C64 的读取方。前置：S6。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
