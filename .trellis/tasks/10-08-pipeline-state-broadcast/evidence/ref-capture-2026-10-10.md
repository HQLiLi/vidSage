# SSE 参照侧抓取记录（2026-10-10）

## 树状态（重要）

抓取时工作树**不是**裸 dev：批次 1（`TaskProgressMessage` → `message/`，含全部 import）与批次 2
（`TaskProgressNotifier` 已建、**无任何调用者**）已在树上，批次 3 调用点**未接线**
（`SubtitlePipeline` 仍持有 `TaskSubscriptionRegistry` 自己广播）。

**为什么这仍等价于 dev 行为**：批 1 是纯搬运（package/import 行 diff，类体零改动）；批 2 的
notifier 是无调用者的 `@Component`，不参与任何运行路径。SSE 行为的直接证据见下「零差异」。

## 环境

| | 值 |
|---|---|
| 代码基点 | `dd015fd`（dev）+ 上述批 1/2 变更 |
| 端口 | 18080 |
| 输入 | `https://www.bilibili.com/video/BV1dHY76oEgw/?spm_id_from=333.1387.homepage.video_card.click`（与历史基线逐字相同） |
| taskNo | `1b42f81f250f4c2cb912974b1ac0639c` |
| 工作区 | 抓前清掉了该输入的 `backend/data/works/https___...BV1dHY76oEgw...`（真实全跑，非断点短路） |
| AC6 基线 | 482 用例 / 0 失败 / 0 错误 / 1 跳过（`dd015fd` 干净树，先于批 1/2 出现在树上时测得） |

## 场景 A 在线全程：14 格

`ref-sse-raw.txt`（原始）/ `ref-sse.txt`（taskNo 归一化）。14 个 `event:progress`，1 个 `:ping`。

**与归档基线零差异**：`diff <(tr -d '\r' < ref-sse.txt) <(tr -d '\r' < 10-08-pipeline-executor-split/evidence/ref-sse-normalized.txt)`
逐字节相同（含 `:ping` 落点）。14 格序列：
`0 / 21 / 21 / 42 / 42 / 66 / 66 / 70 / 70 / 79 / 89 / 100 / 100 / 100`。

> 首次 diff 显示整文件差异是 **CRLF/LF 行尾**所致（归档文件带 `\r`），剥 `\r` 后零差异。
> 比对时务必 `tr -d '\r'`，别把行尾当行为差异。

## 场景 B 重连快照：三条命门全齐

| 状态 | 证据文件 | 首条快照要点 |
|---|---|---|
| SUCCEEDED 无翻译 | `ref-snapshot-succeeded-no-translation.txt` | `stage=clean-subtitles/100/任务已完成/SUCCEEDED`，`translationStatus=null` |
| 翻译 RUNNING | `ref-snapshot-translation-running.txt` | `stage=translate-subtitles/0/翻译进行中/RUNNING`，`translationStatus=RUNNING`（其后顺带抓到增量 25/50/75 批次事件） |
| 翻译 SUCCEEDED | `ref-snapshot-translation-succeeded.txt` | `stage=translate-subtitles/100/翻译完成/SUCCEEDED`，`translationStatus=SUCCEEDED` |

翻译为真实 DashScope 调用（`POST /{taskNo}/translate`，4 批，25/50/75/100）。
`ref-translate-trigger.json` = 触发响应（`translationStatus: RUNNING`）。

## 原始文件

| 文件 | 内容 |
|---|---|
| `smoke-boot.log` | 实例启动日志 |
| `ref-submit.json` | 提交响应 |
| `ref-sse-raw.txt` / `ref-sse.txt` | 场景 A 原始 / 归一化 |
| `ref-translate-trigger.json` | 翻译触发响应 |
| `ref-snapshot-*-raw.txt` / `ref-snapshot-*.txt` | 场景 B 三条原始 / 归一化 |
| `sse-vs-archive.diff` | 与归档基线的 diff（剥 `\r` 后为空） |

## 收尾

实例按端口 PID `taskkill //PID 2880 //F` 收掉，18080 已无监听。
