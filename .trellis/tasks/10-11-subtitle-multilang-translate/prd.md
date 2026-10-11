# 翻译多语言选择与翻译子状态下线

## Goal

目标语种从配置移到请求参数（固定白名单 zh-Hans / zh-Hant / en / ja，新增 SupportedLanguage 枚举 + GET /api/languages，删 vidsage.subtitle.translate.target-language）；跳过判据升级为「主语言 + 中文正简脚本」（zh→zh-CN 仍跳过，zh→zh-Hant 真翻）；翻译触发帖 GET 改 POST body {targetLanguage}；全删 DB 翻译子状态（task.translation_status 列、TranslationStatus 枚举、VideoTask 三个迁移方法、TaskStageEvent.fromTranslation、TaskProgressMessage/TaskResponse 的 translationStatus 字段）；ChatClientConfig 里烧死在 system 提示词的目标语种移出。依赖子任务 subtitle-keyword-regenerate 先落地的 D 组共用地基（在途登记/互斥/快照）。

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
