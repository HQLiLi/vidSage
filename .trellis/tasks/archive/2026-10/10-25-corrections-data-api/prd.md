# 纠错数据层与 API

> 父任务：`10-25-subtitle-manual-correction`。技术口径见父任务 `design.md` §2–§4。

## Goal

为字幕手动纠错提供可持久化的数据基础：给字幕片段加稳定 id、建立纠错表与读写端口，并对外提供保存/删除/查询/清理失效的 HTTP 接口。

## Scope

- `TranscriptionSegment` / `TranslatedSegment` 增加 `id` 组件与缺省构造。
- `subtitle_correction` 表、领域值对象、仓储端口与 MyBatis 实现。
- `SubtitleCorrectionOverlay`（纠错合并 + matched 计算）。
- `SubtitleCorrectionService` 与校验。
- `SubtitleCorrectionController` + `web/dto/` 契约。

不包含：下载渲染、翻译接线、前端。

## Requirements

1. id 规则：`seg-<beginTimeMs>-<endTimeMs>`；旧 JSON 缺 id 时读取自动合成；写出时带 id。
2. 纠错表按 `(task_id, segment_id, field, lang)` 唯一；重复保存是 upsert，刷新 `updated_at`。
3. `field=text` 时 `lang` 为空串；`field=translation` 时 `lang` 必填。
4. 保存时记录这条纠错所依据的源文本到 `source_text`（原文纠错记改前的原文；译文纠错记当时的原文）。
5. 校验与状态码：任务不存在 404；运行中 409；条目不存在 409；空值/超长/缺 lang 400。
6. `GET /corrections` 返回每条纠错的 `matched` 与 `sourceChanged`。
7. `DELETE /corrections?staleOnly=true` 只删 `matched=false` 的条目。

## Acceptance Criteria

- [ ] AC1 读取缺少 id 的旧 `segments.json` 不报错，且每条都有按时间合成的 id；写出的 JSON 带 id。
- [ ] AC2 同一 `(task, segment, field, lang)` 连续 PUT 两次只留一行，~~`updated_at` 变化~~ **两次 PUT 间隔 ≥1 秒时 `updated_at` 变化**（2026-10-25 订正：`updated_at` 是 `DATETIME`，精度只到秒，不为此改 DDL）。
- [ ] AC3 任务运行中 PUT/DELETE 返回 409；任务不存在返回 404。
- [ ] AC4 `segmentId` 不在当前 CLEANED 产物里时返回 409，文案提示刷新；不写入数据库。
- [ ] AC5 `field=translation` 且 `lang` 无对应译文产物时返回 400。
- [ ] AC6 空串（trim 后）与超长文本返回 400。
- [ ] AC7 `GET /corrections` 的 `matched` 在重新生成字幕后变为 `false`。
- [ ] AC8 `DELETE ?staleOnly=true` 返回删除条数，只影响 `matched=false` 的行。
- [ ] AC9 `mvn clean test-compile` 与 `mvn test` 全绿，新增单测覆盖仓储转换、覆盖层合并与校验分支。

## Dependencies

- 无前置子任务。
- 后续：`10-25-corrections-read-paths`、`10-25-corrections-frontend`。
