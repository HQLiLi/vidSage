# 字幕关键词纠错与多语言翻译

## Goal

为字幕生成补两件事：（1）用户手填的关键词/术语列表（纯字符串数组、无对照关系），供 LLM 纠正 ASR 同音误写（视频发布者名字这类几乎必错的词），并支持前端多次重新生成字幕；（2）翻译目标语种改为前端可选（固定白名单），随之取消 DB 的翻译子状态。本次只做后端，前端涉及页面结构重构，另开任务。来源：2026-10-11 设计 grill 共识清单（A–F 节）。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
