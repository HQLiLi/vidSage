# 完成报告：SubtitlePipeline 状态迁移 × 广播收口（2026-10-11）

任务 `10-08-pipeline-state-broadcast`，分支 `feature/pipeline-state-broadcast`（切自 `dev@dd015fd`）。

## AC1–AC7 逐条对账

| AC | 口径 | 结果 | 证据 |
|---|---|---|---|
| **AC1 结构** | 根只剩 `VideoTaskService` + `package-info`；`message/`、`notify/` 落位命名档位合 spec（含登记） | **PASS** | `grep-2026-10-11.txt`（根文件清单 + 七桶）；spec 批 0 订正 5 处（命名表「组件后缀登记」行、二级包表双口径、三档表） |
| **AC2 构造单点** | main 只在 notifier 内；test 夹具豁免全列 | **PASS** | `grep-2026-10-11.txt`：main 2 处（notifier :73/:87）；test 豁免 **17 处 / 5 文件**逐行列出 |
| **AC3 送出单点** | `SubtitlePipeline` 零注册表引用；broadcast/complete 单点 | **PASS** | 同上：引用 **0**；`.broadcast(` main 仅 notifier:86；`.complete(` 仅 notifier:102 + `TaskSseHub:122`（`SseEmitter` 同名，注明不计入） |
| **AC4 行为保真** | design §五 10 条逐条落地（日志文案/异常语义/顺序/清订阅骨架） | **PASS** | 批 3 审查（2026-10-11）逐条核验 10/10；实现记录见 implement.md |
| **AC5 双侧对照** | SSE 事件流 + 快照对照 dev 基线逐字节一致 | **PASS** | `ac5-comparison.md`：场景 A 14 格 data 行全同（仅 `:ping` 落点，ac4 同款裁定）；场景 B 三条首条快照全同；翻译增量重叠段全同 |
| **AC6 测试** | 只换夹具断言零放宽；`mvn clean test` 全绿 | **PASS** | 最终轮 **488 / 0 / 0 / 1，BUILD SUCCESS**（`mvn clean test`）；红线 diff 核验见 trellis-check 报告（改行仅 import/构造/注释） |
| **AC7 规范** | design §七 订正清单逐条落地且不自相矛盾 | **PASS** | #1–#4 批 0（spec 判据 5 处）；#5–#7 5a（三个 package-info）；#8–#9 5b（`TaskStageEvent` 调用者、`TaskResponse` 路径各 1 行注释） |

## 关键事实

- **行为零改动**：双侧 SSE 快照逐字节对照通过；日志文案、`task.fail()` ISE 吞噬语义、终态 `try/finally` 清订阅全部原样（AC4）。
- **单点收口达成**：消息构造与送出各归 notifier 一处；`VideoTaskService` 快照与在线事件同源由结构保证（R4）。
- **域状态迁移留在调用点**（R5）：`succeed`/`fail`/`translationSucceed`/`translationFail` 未进 notifier。
- **测试**：基线 482 → 488（+6 新增 `TaskProgressNotifierTest`）；既有断言零改动零删除。

## 过程事故与处置（如实记录）

1. **批 5a 子代理 API 402 中止**：三个 package-info 已写完，主线逐字验收通过（台账 #4）。
2. **首轮分支侧 SSE 抓取失败两次**：① 跨命令模型间隔 > 跑全程，订阅晚到只拿到终态快照；② 工作区清理相对路径写错（evidence 目录内 `../../../backend`）致断点秒过（`startedAt==finishedAt` 同秒）。处置：清工作区 + 提交/订阅放进同一 shell，第三次成功（14 格）。
3. **target/ 竞态**：首轮冒烟 `spring-boot:run`（绑定 test-compile）撞上并行编译，类文件 NoSuchFileException + 连锁符号错误。处置：`rm -rf target` 后重启，干净通过（boot log 零 ERROR）。
4. **B-2 窗口无增量**：首批 LLM 响应 10s > 8s 抓取窗口；日志时间戳证明回调与广播链路正常。处置：另跑全程订阅轮补抓 7 格，与参照重叠段逐字节相同。

## 证据索引（`evidence/`）

参照侧：`ref-capture-2026-10-10.md`、`ref-sse.txt`、`ref-snapshot-*.txt`（3）。
分支侧：`branch-boot.log` / `branch-boot2.log`、`branch-submit.json`、`branch-sse.txt`、`branch-snapshot-*.txt`（3）、`branch-translation-events.txt`、`branch-translate-trigger.json`。
对照与静态：`ac5-comparison.md`、`ac5-scenarioA.diff`、`grep-2026-10-11.txt`。
过程：`research/execution-log.md`（派发台账 5 条）。
