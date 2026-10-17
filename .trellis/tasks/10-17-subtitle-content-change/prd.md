# 字幕模型重构 6/8：内容变更（中间产物登记 + 指纹探测）

## Goal

intermediate_file 登记（C62）；复用判据从「文件在不在」改成「表里 READY 且文件在」；中间产物原子替换（C50）；指纹探测阶段（C47-C49，阶段内 探测->比对->级联->写指纹，级联与写指纹同事务）；C31 级联。前置：S4。硬顺序：C62 必须与指纹同段，否则指纹级联只动 DB、盘上中间产物没动，主管线重跑全部跳过 -> 静默交付旧内容。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
