# 字幕模型重构 3/8：产物权威（artifact 表读写 + 下载契约）

## Goal

artifact 表读写：CREATING 占位 -> 换版落定（W4，写文件列并入落定那一步）；ORIGINAL / CLEANED 落定；C21 只认 READY、C24 清空旧行文件列、C25 读路径自愈；GET /api/tasks/{taskNo}/artifacts（C54）；下载端点改 type+file+?lang=（C55）。前置：S2。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
