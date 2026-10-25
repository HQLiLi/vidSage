# 读取路径接入与强制重翻

> 父任务：`10-25-subtitle-manual-correction`。技术口径见父任务 `design.md` §4.4、§5。

## Goal

让用户在页面上看到、下载到的字幕，以及重新翻译时使用的源文本，都是纠错后的内容；同时提供强制重翻当前语种的能力。

## Scope

- CLEANED / TRANSLATED 的 `segments.json`、`subtitles.srt` 下载套用纠错。
- `?raw=1` 直发磁盘文件。
- 翻译取源文本时套用纠错。
- `POST /translate` 增加 `force` 语义。

不包含：前端交互、数据层。

## Requirements

1. 下载行为按 `(artifact type, file kind)` 静态判定，不做运行时猜测：
   - ORIGINAL 全部、CLEANED `context.json`、TRANSLATED `terms.json`：直接发磁盘文件。
   - CLEANED `segments.json` / `subtitles.srt`：有 text 纠错才渲染。
   - TRANSLATED `segments.json` / `subtitles.srt`：有该 lang 译文纠错才渲染。
2. 无纠错时下载必须与磁盘文件逐字节一致（走快路径，不重新序列化）。
3. `?raw=1` 永远返回磁盘文件。
4. 翻译重跑读到的是纠错后的源文本。
5. `force=true` 跳过"已有完好译文则跳过"的判定；`force=false` 保持现状。
6. 清洗复用判定、指纹、对账等机器产物读取路径**不得**套用纠错。

## Acceptance Criteria

- [ ] AC1 零纠错任务：下载 CLEANED `segments.json` / `subtitles.srt` 的 sha256 与磁盘文件一致。
- [ ] AC2 加一条原文纠错后：下载 `segments.json` 与 `subtitles.srt` 体现改动；`?raw=1` 仍与磁盘一致。
- [ ] AC3 加一条 A 语种译文纠错后：仅 A 语种下载变化，B 语种不受影响。
- [ ] AC4 改原文后调用 force 重翻：新译文基于改后原文；手改的译文纠错仍覆盖在最上层。
- [ ] AC5 `force=false` 时，已有完好译文的任务行为与改造前一致（跳过不重跑）。
- [ ] AC6 运行中调用 force 翻译返回 409（沿用现有互斥）。
- [ ] AC7 清洗复用判定不受纠错影响：有纠错但没有重新生成时，清洗阶段仍按原指纹复用。
- [ ] AC8 `mvn clean test-compile` 与 `mvn test` 全绿，新增测试覆盖下载分流与 force 分支。

## Dependencies

- 依赖 `10-25-corrections-data-api` 完成。
