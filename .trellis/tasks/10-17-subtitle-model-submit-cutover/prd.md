# 字幕模型重构 2/8：换血（身份键 + 提交路径 + run 生命周期）

## Goal

身份键（C7-C12：bvid|p 规范化、短链超时+缓存、上传 sha256）、提交 URL/上传（W1 事务 + C63 撞键锁定读 + 插 MAIN run）、run 级收口（C43/C44/C61）与启动收口（C15 三条 UPDATE）、新契约骨架（GET 身份+lastRun / POST 202·409 {taskNo,runNo} / keywords 换锚点）、SSE 一条 run 一条流（C57）、subtitle_keyword 换锚点（手工 DROP）、旧栈整块删。前置：S1。本段最大。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
