# 关键词纠错与字幕重生成

## Goal

关键词表落库（新表，外键指向 workspace_key，不设 DB 外键约束）+ 提交任务时可带可选关键词 + 重跑清洗端点 POST /{taskNo}/subtitles/regenerate + 摘要器与清洗提示词两条通道（后者不参与 TermMatcher 命中判定/指标/相似度基线）+ keywordsFingerprint 断点判据 + 成功后删全部 -translate-* 与 terms-* + prompt 注入防护（四类外部文本统一数据块渲染）。D 组共用地基（内存在途登记 + 工作区级互斥 409 + 快照查询在途会话）落在本子任务。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
