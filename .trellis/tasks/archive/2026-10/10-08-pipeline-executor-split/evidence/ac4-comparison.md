# AC4 真实链路逐格比对（2026-10-08）

## 环境

| | 基线（对照点） | 本次 |
|---|---|---|
| 代码 | 归档任务 `10-08-progress-weight-flattening` 的 branch 捕获（`d36b2f8`） | 本分支 `feature/pipeline-executor-split` |
| 端口 | 18080 | 18080 |
| taskNo | `882ba26f549d46a6815ca6d01f7f4d0e` | `7469c876364d4cdbb9d49db40674991b` |
| 结果 | SUCCEEDED | SUCCEEDED（cost=35420ms） |

**为什么只起一个实例**：基线那份捕获是「dev 合入该分支后的行为」——`git diff 7cdcb2c^2 15e6112 -- backend/src/main` **为空**（合并后 dev 只多了 docs/archive 提交），即当前 dev 与那次捕获的主源码逐字节相同。故 `branch-sse.txt` 就是 dev 现在的有效对照点，无需再起第二个实例。

- 输入（与基线逐字相同）：`https://www.bilibili.com/video/BV1dHY76oEgw/?spm_id_from=333.1387.homepage.video_card.click`
- 路线：URL 提交（yt-dlp 下载），未配置 cookies。
- **清掉了该输入的工作区**（`backend/data/works/https___...BV1dHY76oEgw...`，其余 12 个目录未动）→ 本次是**真实全跑**，非断点短路。
- 抓取双通道互证：应用日志 DEBUG「任务阶段事件」（无竞态，抓取起点见 `branch-log-marker.txt` = 第 56 行）与 SSE `GET /api/tasks/{taskNo}/events`（`branch-sse.txt`）。

## 结果：零差异

```
14 格事件序列（两边逐格相同）：
0 / 21 / 21 / 42 / 42 / 66 / 66 / 70 / 70 / 79 / 89 / 100 / 100 / 100
```

把两边 taskNo 归一化后**逐字节 diff**：

- 归一化后**等长 2635 字节**；
- 唯一差异是 `:ping` 心跳行落点不同（第 3–4 行 vs 第 15–16 行）——SSE keepalive，时序相关，**不是行为差异**；
- 14 个 `event:progress` 的 `data` 行（stage / progress / state / message / taskStatus）逐字节相同。
- 日志通道互证：管线级「任务阶段事件」13 行同序列——少的那一格是**终态广播**（日志走 `broadcastTerminal`，不落「任务阶段事件」行），SSE 侧含它共 14 格，与基线口径一致。

## 差异栏

| 预期差异 | 实测 |
|---|---|
| R3 第 1 格：幽灵任务（装载返回空）会先排队再放许可 | **未触达**——装载成功，无幽灵路径 |
| R3 第 2 格：「任务记录缺失」ERROR 日志在有排队时推迟 | **未触达**——同上 |
| 第 3 格：同一 taskNo 被 `execute` 两次时，基线因陈旧 PENDING 快照会把管线跑两遍；装载后移后确定放弃（2026-10-08 检查面补入） | **未触达**——需要同一 taskNo 被重复 `execute` |
| 第 4 格：幽灵任务排队时被打断 → 「任务记录缺失」ERROR 一次都不出现（2026-10-08 检查面补入） | **未触达**——需要任务记录缺失 + 排队被打断 |
| 翻译 cost 数值的来源时刻前移（微秒级） | **未触达**——翻译链路未跑（见边界） |

按 AC4 的文字「若不触达则为零差异」→ **本次为零差异**，与基线逐格一致。R3 四格全未触达，逐格枚举见 `prd.md`。

## 已接受的边界

1. **翻译链路不做真实运行**（省外部配额）。它不受本次改动影响的部分（区间直通、终态收口）由单测覆盖；`startTimeMs` 口径由「管线侧翻译排队中断」用例 + 代码位置锁住。
2. **不给 executor 加 `@PreDestroy` shutdown**（design §七 刻意不做：会让关闭时进行中的任务变 FAILED）。
3. R5「终态广播仍在配额内」是**结构性**保证（`succeed` / `update` / `broadcastTerminal` 全在闸门 body 内 = `release()` 之前），由代码顺序与本次事件流证明，非运行时可观测项。

## 附带确认

- **`@Autowired` 装配**：实例 4.4 秒正常启动（`Started VidSageApplication`），没有 `BeanInstantiationException: No default constructor found` —— 两构造器 + `@Autowired` 这处装配在真实容器里成立。
- **线程名保留**：日志线程列仍为 `vidsage-task-0`。
- **日志文案逐字**：`任务开始执行: taskNo=…, inputType=URL`、`任务执行成功: taskNo=…, cost=35420ms` 与改造前同款。
- 翻译链路未跑，故 AC7 里 `翻译已触发…` / `任务翻译完成…` 两条**本次未在真实链路复现**，由单测与代码逐字比对保证。

## 原始证据文件

| 文件 | 内容 |
|---|---|
| `branch-sse.txt` | 本次 SSE 原始流（14 格） |
| `branch-sse-normalized.txt` / `ref-sse-normalized.txt` | taskNo 归一化后的两流，供 `diff` |
| `branch-events-raw.log` | 日志抽出的管线级阶段事件（13 行） |
| `branch-run.log` | 实例完整启动 + 运行日志 |
| `branch-log-marker.txt` | 抓取起点行号（56） |
| `branch-submit.json` | 提交响应（taskNo / PENDING 视图） |
