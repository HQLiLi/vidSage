# 字幕模型重构 1/8：数据层地基（新六表 + 新领域 + 新仓储）

## Goal

建 workspace / subtitle_task / task_run / subtitle_artifact / intermediate_file 五张新表（N4 命名规则、N6 枚举列 VARCHAR(32)、C64 四列 sha256、§9.4j 四列 _file_name 与 255 宽度），配五个领域类 + TaskRunStatus + 四个新 port + MyBatis 实现。纯增量：不动任何调用方，既有 569 条测试一条不改全绿。前置：无。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
