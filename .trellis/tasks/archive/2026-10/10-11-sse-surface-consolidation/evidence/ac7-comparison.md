# AC7 双侧对照（2026-10-11）

> **本次为「修后重取」**。第一轮抓取（17:17，taskNo `8a8ac84d…`）发生在审查意见修复**之前**——
> 那轮之后 `remove()` 改成原子回收、投递路径改成每连接 1:1、`open()` 加了读快照失败的
> 回滚、`send` 拆成 `sendTo`。投递路径正是 #2 动过的地方，故证据已相对代码过期，按协议重取。
> 下面全部是第一轮 `--max-time 600` 抓取被 `ref-*`（改动前）取代后的**最终**证据。

## 树状态

| | 值 |
|---|---|
| 分支 | `feature/sse-surface-consolidation`（基点 `0307f86`） |
| 树上改动 | 后端批次 1 全部（含审查意见 #1/#2/#4 的修）+ 批次 3 测试 + 前端批次 2 |
| 未提交 | 是（全部改动仍在工作树，无任何提交） |
| 冒烟端口 | 18080 |

## 运行

提交与订阅写在同一 shell（分两条命令会因间隔太久只拿到终态快照）：

```
URL='https://www.bilibili.com/video/BV1dHY76oEgw/?spm_id_from=333.1387.homepage.video_card.click'
SUB=$(curl -s -X POST http://localhost:18080/api/tasks -H 'Content-Type: application/json' -d "{\"url\":\"$URL\"}")
TASKNO=$(printf '%s' "$SUB" | sed -n 's/.*"taskNo":"\([^"]*\)".*/\1/p')
curl -sN "http://localhost:18080/api/tasks/$TASKNO/events" > ac7-sse-raw.txt
curl -s  "http://localhost:18080/api/tasks/$TASKNO"        > ac7-snapshot-raw.json
```

taskNo `aeef76ccc23248719147b80c131f96a9`（17:54:43 提交）；工作区抓前已清（用户授权），**真实全跑**。
`curl` 于 17:55:10 自行退出——**服务端在终态事件推完即 `complete()` 关流**，这本身是终态收口在场的证据。

## A. SSE 事件流

| 项 | 本次 | 参照侧（`ref-sse.txt`） | 判 |
|---|---|---|---|
| `event:progress` 条数 | 14 | 14 | ✓ |
| 进度序列 | `0/21/21/42/42/66/66/70/70/79/89/100/100/100` | 同 | ✓ |
| 终态事件 | `clean-subtitles/100/任务已完成/SUCCEEDED`，`translationStatus=null` | 同 | ✓ |
| `:ping` 心跳 | 1 个 | 2 个 | 时序抖动，非行为差异 |

**唯一新增差异**：每条 `data:` 末尾多出 `"terminal"` 字段——

```
"terminal":false  × 13
"terminal":true   × 1     ← 末条（终态）
```

验证方式（剥掉新字段后应零差异）：

```
diff <(sed 's/,"terminal":[a-z]*}/}/' ac7-sse.txt) <(tr -d '\r' < ref-sse.txt)
→ 仅 `:ping` 落点（3a4,5 / 27a30,31 / 30,31d33）
```

## B. `GET /api/tasks/{taskNo}` 快照

归一化（taskNo → `<TASKNO>`，三个时间戳 → `<TS>`，剥 CRLF）后：

```
cmp ac7-snapshot.json ref-snapshot.json  →  逐字节一致 ✓
```

该响应**不含 `terminal`**，与改动前逐字节相同；`artifacts` 同为 `[]`（与参照侧一致，非本次引入）。

## 文件

| 文件 | 内容 |
|---|---|
| `ac7-boot.log` | 实例启动日志 |
| `ac7-submit.json` / `ac7-taskno.txt` | 提交响应 / taskNo |
| `ac7-sse-raw.txt` / `ac7-sse.txt` | SSE 原始（CRLF）/ 归一化 |
| `ac7-snapshot-raw.json` / `ac7-snapshot.json` | 快照原始 / 归一化 |

## 收尾

实例按端口 PID `taskkill //F` 收掉，18080 已释放。
