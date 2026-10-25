# 媒体源播放端点与工作区媒体区接入

> 来源：`.trellis/workspace/HeQiu/known-bugs.md` §6 第 1 条（媒体区不可播放）与 §4 第 3 条（建议单开后端任务）。
> 立档：2026-10-24。

## Goal

让工作区的媒体区能真正播放视频/音频：后端补一个媒体字节端点，前端 `MediaPane` 接上播放器。

后端现在没有任何媒体字节出口（`/api/media` 下只有 `/tools`），前端只能放 16:9 占位。本任务补上出口并接入，
**不改动**任务快照、产物清单与字幕相关契约。

## Requirements

- R1 **媒体源字节端点**：`GET` / `HEAD /api/media/source/{taskNo}`，返回该任务源媒体文件的字节。
  - 支持 HTTP Range：全量 `200`、单区间 `206`、越界 `416`；响应带 `Accept-Ranges: bytes`，
    206 带 `Content-Range`。
  - 文件缺失（任务没有 `READY` 的 `SOURCE_MEDIA` 行，或登记了但文件已不在工作区）→ `404`，
    错误体沿用全局 `{ "error": message }`。
  - `Content-Type` 按**文件头嗅探**判定，认不出再退回扩展名；上传路线无论传什么都落成 `source.mp4`，
    只按扩展名会把上传的 mp3 声明成 `video/mp4` 而被浏览器拒播。
- R2 **端点归属**：挂在 `/api/media` 命名空间下，新增 `MediaSourceController`；现有
  `MediaController`（外部工具诊断）不改。**不**把媒体端点塞进 `/api/tasks/{taskNo}/...`。
- R3 **前端播放**：`MediaPane` 用 `<video controls preload="metadata">` 播放
  `/api/media/source/{taskNo}`；字节请求吃不到 axios 的 baseURL，按 `api.md` 的「双拼法」写全路径。
- R4 **缺失降级**：拿不到媒体（404 / 解码失败 / 运行中尚未产出）时出占位，布局高度不变；
  占位文案要分状态——**运行中说「解析中」，就绪后说「没有可播放的媒体」**，不能反过来把「还没解析完」说成「没有」。
- R5 **尺寸约束**：竖屏 / 超宽素材必须被收进固定的 16:9 媒体区（`object-contain` 黑边），不得撑动左列布局。

## 不在本范围内

- 字幕与播放的时间联动（点句跳转、反向高亮）——改动面跨三个组件，另开任务。
- 上传落盘保留真实扩展名（`source.mp4` 恒定的根因）——要动 `UPLOAD_SOURCE_FILE_NAME`、`MediaInputUtils`
  与复用判据，另开任务。
- 转码：浏览器解不了的容器（如 mkv/flv）不转，直接降级占位。
- `.trellis/spec/frontend/api.md` 的其余失实条目订正（known-bugs §6 第 3 条）。

## Acceptance Criteria

### 后端

- **AC1** 对一个 `SOURCE_MEDIA` 为 `READY` 的任务发 `GET /api/media/source/{taskNo}`：
  `200`、`Accept-Ranges: bytes`、`Content-Length` = 磁盘文件字节数、`Content-Type` 与文件真实格式相符，
  响应体 sha256 与磁盘文件一致。
- **AC2** 同一地址带 `Range: bytes=0-99`：`206`、`Content-Range: bytes 0-99/<size>`、`Content-Length: 100`，
  响应体与文件前 100 字节逐字节一致。
- **AC3** `Range: bytes=<size>-`（起点等于文件长度）：`416` + `Content-Range: bytes */<size>`。
- **AC4** `HEAD` 同一地址：`200`、带 `Accept-Ranges` 与正确的 `Content-Length`、响应体为空。
- **AC5** `taskNo` 不存在 → `404` `{error}`；任务在但无 `READY` 的 `SOURCE_MEDIA` 行 → `404`；
  行在但文件被挪走 → `404` 且**不修改任何数据库行**（自愈口径，与 `IntermediateFileStore.isReusable` 一致）。
- **AC6** `Content-Type` 嗅探生效：上传一个 mp3 提交的任务 → `audio/mpeg`；URL 下载的 mp4 → `video/mp4`。
- **AC7** 单元测试覆盖嗅探的每个分支与「认不出→退回扩展名」；控制器切片测试覆盖 200 / 206 / 416 / 404 / HEAD。
  `mvn clean test-compile` 通过，新增测试全绿。

### 前端

- **AC8** 打开一个有可播放源的任务：媒体区出现播放器，能播放、能拖动进度（拖动会触发 `206`）。
- **AC9** 打开没有媒体源的任务：就绪态显示「媒体预览暂不可用 / 该任务暂无可播放的媒体」；
  **运行中**显示「媒体准备中… / 任务解析中，媒体准备好后会自动出现」；两种情况控制台均无未捕获异常。
- **AC9b** 竖屏视频（如 360×640）播放时，媒体区仍是 16:9 且尺寸不变（实测 1014×571），画面居中留黑边。
- **AC10** 切换任务后失败态重置（上一个任务的加载失败不会带到下一个）；运行态结束转就绪后若媒体已产出，
  播放器能自动拿到（无需手动刷新页面）。
- **AC11** `npm run type-check`、`npm run lint`、`npm run build` 全绿。

## 依赖与顺序

无前置任务。后端（R1/R2）先完成并自测，前端（R3/R4）在后端契约稳定后接入；AC1–AC7 不依赖前端，
AC8–AC11 依赖后端可运行。
