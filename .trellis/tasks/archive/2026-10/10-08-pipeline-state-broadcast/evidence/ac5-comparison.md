# AC5 双侧对照（2026-10-11，同机同轮两实例）

## 环境

| | 参照侧（dev 行为） | 分支侧 |
|---|---|---|
| 代码 | `dd015fd` + 批 1/2（行为等价论证见 `ref-capture-2026-10-10.md`） | 同基点 + 批 3 换线 + 批 4/5a/5b |
| 端口 | 18080 | 18080（分时复用，参照侧先收） |
| taskNo | `1b42f81f250f4c2cb912974b1ac0639c` | `2954f12846c74d518e5acfbc0a8ed83a` |
| 输入 | `https://www.bilibili.com/video/BV1dHY76oEgw/?spm_id_from=333.1387.homepage.video_card.click`（逐字相同） | 同左 |
| 结果 | SUCCEEDED（含 4 批翻译） | SUCCEEDED（含 4 批翻译），日志双行俱在 |

两侧各跑前都清了该输入的工作区（真实全跑，非断点复用）。**抓取方式**：分支侧「提交 → 订阅」在同一 shell 内毫秒级衔接（首抓因跨命令模型间隔超过跑全程、订阅晚了拿到终态快照，已重抓；另有一次工作区相对路径写错致断点秒过，绝对路径修正后重抓——过程如实记录）。

## 场景 A 在线全程：14 格 —— 零行为差异

```
diff <(tr -d '\r' < branch-sse.txt) <(tr -d '\r' < ref-sse.txt)
→ 仅 :ping 心跳行落点不同（参照侧在 ~15 行处，分支侧在 ~27 行处），40 字节
→ 14 个 event:progress 的 data 行逐字节相同（stage / progress / state / message / taskStatus / translationStatus）
```

14 格序列两侧相同：`0 / 21 / 21 / 42 / 42 / 66 / 66 / 70 / 70 / 79 / 89 / 100 / 100 / 100`。

**差异判定**：`:ping` 是 30s keepalive 注释行，落点纯由时序决定——归档基线 `10-08-pipeline-executor-split/evidence/ac4-comparison.md` 对同款差异的裁定为「SSE keepalive，时序相关，不是行为差异」，本次沿用。原始 diff 落 `ac5-scenarioA.diff`。

## 场景 B 重连快照：三条首条快照逐字节相同

| 状态 | 文件 | 首条快照 |
|---|---|---|
| SUCCEEDED 无翻译 | `branch-snapshot-succeeded-no-translation.txt` | `clean-subtitles/100/任务已完成/SUCCEEDED`，`translationStatus=null` —— 与参照**全文件相同**（diff 空） |
| 翻译 RUNNING | `branch-snapshot-translation-running.txt` | `translate-subtitles/0/翻译进行中/RUNNING`，`translationStatus=RUNNING` —— 首条**相同** |
| 翻译 SUCCEEDED | `branch-snapshot-translation-succeeded-translation.txt`* | `translate-subtitles/100/翻译完成/SUCCEEDED`，`translationStatus=SUCCEEDED` —— 与参照**全文件相同**（diff 空） |

\* 实际文件名 `branch-snapshot-translation-succeeded.txt`。

**B-2 增量条数差异说明**：参照侧抓取窗口（curl --max-time 8）横跨了 25/50/75 三格增量（当时翻译跑了约 8s+），分支侧翻译约 5s 完成、窗口内只有首条快照——**首条快照（AC5 对照口径：「重连拿首条快照」）逐字节相同**；增量条数由翻译时长决定，非行为差异。

翻译为真实 DashScope 调用（`branch-translate-trigger.json`，`translationStatus: RUNNING` → 4 批 → SUCCEEDED）。

## 补充：翻译增量事件流（同机第二实例，补观测缺口）

首抓 B-2 窗口内无增量（分支侧首批 LLM 响应 10s > 窗口 8s，日志证明回调全部触发：12:53:03 开始 → 13/15/18/19 四批 → cost=16355ms）。为把「翻译增量」也纳入直接观测，另跑一轮**全程订阅**（`626d5cd6…`，真实全跑 + 真实翻译）：

- 抓到 **7 格完整翻译事件流**：快照(开始翻译) + 第 1..4/4 批 + 翻译完成(relay) + 翻译终态，`branch-translation-events-raw.txt` / `.txt`；
- 与参照侧抓到的重叠段（快照 + 25/50/75 三批）**逐字节相同**（`diff head -12` 为空）；
- 收口方式同为终态推完即关（`curl_exit=0`）。

## 结论

**AC5 PASS**：SSE 事件流与快照响应体对照参照基线零行为差异——场景 A 14 格 data 行全同（仅时序性 `:ping` 落点差，裁定口径沿用 ac4 先例）；场景 B 三条首条快照全同；翻译增量事件流重叠段全同。

## 原始文件

`ref-submit.json` / `ref-sse.txt` / `ref-snapshot-*.txt`（参照侧）、`branch-submit.json` / `branch-sse-raw.txt` / `branch-sse.txt` / `branch-snapshot-*-raw.txt` / `branch-snapshot-*.txt` / `ac5-scenarioA.diff` / `branch-boot.log`（分支侧）。
