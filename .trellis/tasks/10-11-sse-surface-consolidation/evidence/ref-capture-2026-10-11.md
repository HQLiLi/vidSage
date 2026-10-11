# 步骤 0 基线记录（2026-10-11）

## 树状态

| | 值 |
|---|---|
| 分支 | `feature/sse-surface-consolidation`（自 `dev` 切出） |
| 基点 | `0307f867c281fa050c61e91a5b9452163e1553bd`（= dev HEAD） |
| 树上附带改动 | **仅批次 0 的 spec 文档**（`spec/backend/directory-structure.md` 四处）。纯文档，不参与编译与运行 → 抓取结果等价于 dev ``0307f86`` |
| 主源码改动 | 无 |

## 后端测试基线

```
rm -rf backend/target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn clean test
```

**Tests run: 488, Failures: 0, Errors: 0, Skipped: 1** — BUILD SUCCESS（`2026-10-11T16:08:44`）。
与上一任务（10-08）记录的 488 一致。AC7 的「全绿 + 用例数 ≥ 基线」以此为底。

> 注：跑前先删 `surefire-reports`，否则陈旧报告会冒充本次结果。

## 环境

| | 值 |
|---|---|
| 端口 | 18080 |
| profile | `local`（默认；MySQL `localhost:3306/vidsage`，跑前确认 3306 在监听） |
| 启动 | `JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080` |
| 输入 | `https://www.bilibili.com/video/BV1dHY76oEgw/?spm_id_from=333.1387.homepage.video_card.click`（与 10-08 基线逐字相同） |
| taskNo | `09601a66e6f5419b843ecc3af712be82` |
| 工作区 | 抓前清掉该输入的工作区（用户授权），**真实全跑**，非断点短路 |

## 场景 A：在线全程 SSE

`ref-sse-raw.txt`（原始，CRLF）/ `ref-sse.txt`（剥 CRLF + taskNo → `<TASKNO>`）。

- **14 个 `event:progress`**，终态为 `clean-subtitles/100/任务已完成/SUCCEEDED`，`translationStatus=null`。
- 进度序列：`0 / 21 / 21 / 42 / 42 / 66 / 66 / 70 / 70 / 79 / 89 / 100 / 100 / 100`
- 另有 `:ping` 心跳 2 个（落第 4、30 行）。

**与 10-08 归档基线对照**：

```
diff <(tr -d '\r' < ref-sse.txt) <(tr -d '\r' < archive/2026-10/10-08-.../evidence/ref-sse.txt)
```

14 格进度序列**逐格相同**；唯一差异是 `:ping` 的落点（基线 1 个、本次 2 个）——心跳按固定间隔发，
落点随运行时序抖动，非行为差异。

> ⚠️ 比对前必须 `tr -d '\r'`，否则行尾差异会冒充行为差异（10-08 踩过）。

## 场景 B：`GET /api/tasks/{taskNo}` 快照

`ref-snapshot-raw.json`（原始）/ `ref-snapshot.json`（taskNo → `<TASKNO>`，三个时间戳 → `<TS>`，剥 CRLF）：

```json
{"taskNo":"<TASKNO>","inputType":"URL","sourceUri":"https://...BV1dHY76oEgw...","status":"SUCCEEDED","translationStatus":null,"currentStage":"clean-subtitles","errorMessage":null,"createdAt":"<TS>","startedAt":"<TS>","finishedAt":"<TS>","artifacts":[]}
```

- 该响应**不含 `terminal`**，AC7 要求改动后与此**逐字节一致**。
- `artifacts` 为**空数组**（首次查询与 40 秒后复查一致，非时序问题）——即本基线事实，改动后须同样为空。

## 文件清单

| 文件 | 内容 |
|---|---|
| `smoke-boot.log` | 实例启动 + 全程运行日志 |
| `ref-submit.json` | 提交响应 |
| `ref-taskno.txt` | taskNo |
| `ref-sse-raw.txt` / `ref-sse.txt` | 场景 A 原始 / 归一化 |
| `ref-snapshot-raw.json` / `ref-snapshot.json` | 场景 B 原始 / 归一化 |

## 收尾

实例按端口 PID `taskkill //PID 34576 //F` 收掉，18080 已无监听（`netstat` 复查通过）。
