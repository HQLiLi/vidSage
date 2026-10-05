# 验收证据（前端 v0 冒烟，2026-10-03）

> 端到端 UI 手验（真实浏览器走上传页→工作区）留待用户验收；本文记录主线执行的
> 后端契约冒烟，覆盖 AC 中由契约保证的部分与两个关键风险点。

## 环境

- 后端：`mvn spring-boot:run`（localhost:8080，分支 dev 本地构建）
- 测试媒体：ffmpeg 生成 2s 440Hz 正弦 wav（`.zcode/tmp/smoke-test.wav`，64KB）
- 任务号：`8d0edf09aeb343f0b34293292aa41b69`

## 逐项结果

| 验证点 | 结果 | 证据 |
|---|---|---|
| `POST /api/tasks/upload`（multipart） | 202，返回 PENDING TaskView | 响应体 taskNo=8d0e…，`createdAt=2026-10-03T14:25:51` |
| SSE `GET /…/events` | 连接即推快照，终态后收口 | `event:progress` → `{"taskStatus":"SUCCEEDED","stage":{"stage":"clean","progress":100,…}}`；2s 音频管线 3s 内完成（14:25:51→14:25:54） |
| `GET /api/tasks/{taskNo}` | 契约逐字段符合 | artifacts：`segments-asr-cloud{,-clean}.json` + 两个 0 字节 srt（正弦波无语音，空产物是合法态） |
| 产物 JSON 信封 | **发现 P0-3**：信封 `{route, language, segments}`，前端初版按裸数组解析——真实任务字幕稿必挂 | 真实产物 head 验证；修复：`SubtitleArtifactFile` / `TranslatedArtifactFile` 镜像 + 取 `.segments` |
| **Range 支持（D2 风险）** | **206**，`bytes=0-1023` 精确返回 1024 字节 | Spring 对 `ResponseEntity<FileSystemResource>` 自动处理 Range；播放器 seek 可用，无需新增媒体端点 |
| 404 | `GET /api/tasks/nonexistent…` → 404 | 工作区「任务不存在」卡的数据前提成立 |

## 遗留

- 翻译链路（POST translate → SSE translationStatus → 双语 cues）未做真实 LLM 冒烟
  （正弦波无文本，翻译无意义且花钱）；P0-1 修复后的调用链已经逻辑推演确认，真实链路
  留待用户首次上传真实素材时验证。
- B 站 URL 全链路同上，属用户手验范围。
