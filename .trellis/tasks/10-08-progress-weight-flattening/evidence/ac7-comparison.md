# AC7 真实链路逐格比对（2026-10-08）

## 环境

| | 对照实例 | 分支实例 |
|---|---|---|
| worktree | `../vidsage-before`（`dff027b` = 对照点 dev） | 本仓（`feature/progress-weight-flattening`） |
| 端口 | 18081 | 18080 |
| taskNo | `ea7a85fd725147bbbcf00951957100fc` | `882ba26f549d46a6815ca6d01f7f4d0e` |
| 提交时间 | 15:45:12 → 15:45:43（31s，SUCCEEDED） | 15:46:36 → ~15:47:0x（SUCCEEDED） |

- 输入（两实例逐字相同）：`https://www.bilibili.com/video/BV1dHY76oEgw/?spm_id_from=333.1387.homepage.video_card.click`
- 路线：URL 提交（yt-dlp 下载）。**未配置 cookies → 无登录态，结构上不可能选中大会员格式**；实测无格式告警。
- 串行执行（共享 MySQL，同输入进行中会 409）；两实例工作区各自 `./data/works`（相对各自 backend 目录），互不可见。
- 两边均为**真实全跑**（事件含 RUNNING+SUCCEEDED，非断点 SKIPPED 短路）；ASR 输出一致 → clean 均为 3 批。
- 抓取双通道互证：应用日志 `任务阶段事件`（DEBUG，无竞态）与 SSE `GET /api/tasks/{taskNo}/events`（`ref-sse.txt` / `branch-sse.txt`，各 14 个 progress 值，两文件等长 2971 字节）。

## 事件序列（SSE 14 格，含终态）

```
对照（dev dff027b）：0 / 21 / 21 / 42 / 42 / 66 / 70 / 70 / 70 / 79 / 89 / 100 / 100 / 100
本分支             ：0 / 21 / 21 / 42 / 42 / 66 / 66 / 70 / 70 / 79 / 89 / 100 / 100 / 100
                                     ↑ 第 7 格 写字幕「开始」：70 → 66（唯一差异）
```

逐格核对：**14 格中 13 格逐字节相同，差异恰好第 7 格**，与 prd R8 / design §四 的预期完全一致
（设计速览写的「10 格」未计入 clean 的 2 格批级事件 79/89——批级值两边同为 79/89，
`70+33×30/100=79`、`70+66×30/100=89`，换算公式互证一致）。

- 第 7 格差异为 **R8 有意修正**：写字幕「开始」从借用 `WRITE_DONE` 的终点值 70，改为发本阶段起点 66。
- **除此之外无任何多余差异**——多一格即回归的判据通过。

## 附带确认

- clean 批级进度经 `ProgressMapper.remap(70, 100)` 换算后入流（79/89/100 非 33/66/100 裸值）→ AC4 唯一保留的换算路径在真实链路生效。
- 翻译链路未跑真实验证（省配额）——**已接受的边界**（prd Out of Scope），其区间查询路径由 `SubtitleStageNameTest` 单测覆盖。

## 原始证据文件

- `ref-events-raw.log` / `branch-events-raw.log` —— 日志抽出的事件行
- `ref-sse.txt` / `branch-sse.txt` —— SSE 原始流
- `ref-log-marker.txt` / `branch-log-marker.txt` —— 抓取起点行号
