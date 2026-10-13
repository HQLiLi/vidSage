# 关键词纠错与字幕重生成 — 执行计划

> 需求在 `prd.md`，技术方案在 `design.md`。本文件是**有序列清单**：每批做完必须能编译、能跑测试、
> 能独立停下。批次之间的顺序不是建议，`批 3` 的签名改动会连带编译错误，不能拆开做。

## 0. 基线与前置

```bash
# 取基线（注意先删陈旧报告，否则改名/删类会数进幽灵测试）
cd /f/personal_project/vidSage/backend && rm -rf target/surefire-reports && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' target/surefire-reports/*.xml
```

**预期 498**（引自 [[stage-progress-rename-handoff]]）。对不上就先查清楚，别拿旧数字当基线。
`JAVA_HOME` 必须显式给 21，否则报「不支持发行版本 21」。

**开工前先读 spec**（这几份是本次改动的判据来源）：
`.trellis/spec/backend/directory-structure.md`（命名 / 落位 / 依赖规则）、`quality-guidelines.md`、
`logging-guidelines.md`（日志脱敏红线）、`error-handling.md`（IAE→400 / ISE→500 的判据）、
`provider-pattern.md`。

**开工前先定 `design.md §7` 的五个开放点**，尤其第 1 条（`SubtitleKeywords` 的实例方法是否要订正 spec）
——它决定批 1 的写法。

**分支**：从 `dev` 切 `feature/subtitle-keyword-regenerate`；收工时合回 **`dev`**（不是 master）。

## 批 1 · DB 与持久化（纯新增，不碰任何既有文件）

- `schema.sql` 加 `subtitle_keyword` 表（DDL 见 `design §5.1`；**一个 `workspace_key` 一行**，列表以 `U+0001` 连接存一列）。
- `domain/subtitle/model/keyword/SubtitleKeywords`：归一化（去首尾空白、结构性字符转空格、去重）+ 上限校验（50 条 / 50 字）+ `isEmpty()` / `computeFingerprint()`（排序后 `\u0001` 连接、SHA-256 取前 16 位十六进制）。**不另建政策类**（design §2.1 有理由）。
- `domain/subtitle/port/SubtitleKeywordRepository`。
- `infrastructure/persistence/` 三件套：`SubtitleKeywordEntity` / `SubtitleKeywordMapper` / `MybatisSubtitleKeywordRepository`。

**验证**：`mvn -q test` + 新增单测（归一化、上限、去重保序、指纹顺序不敏感、`replace` 覆盖全量后
库里那份被替换掉、含空格/逗号/中文/单关键词的串原样往返、`null` 行与空串都读回空列表、
空列表存成空串）。

**回滚点 A**：整批删掉即可，`DROP TABLE subtitle_keyword`。**没有任何既有文件被改**，这是本任务唯一的
纯新增批次，也是唯一有数据的批次——所以它排最前。

## 批 2 · 共享产物形状 + 检查点判据

- `SubtitleTrack` 加分量 `keywordsFingerprint`（raw 传 `null`，clean 传指纹）。连带修所有构造点与反序列化兼容。
- `CheckpointPolicy.isCurrent(sizeBytes, storedFingerprint, currentFingerprint)`。

**验证**：`mvn -q test` 全绿；**这一批会动既有测试夹具**，逐个数降下来的用例是不是都只是补了个构造参数、
不是被删掉。

**回滚点 B**：把字段与判据撤掉。已落盘的产物里那个字段会被 Jackson 忽略，无害。

**审查门**：`SubtitleTrack` 是「形状即契约」，改它要单独说明——`design §4 决策 1` 就是这份说明。

## 批 3 · 清洗链路（**不能拆开做**）

这一批同时改端口签名与编排，拆开任何半步都编译不过：

- `SubtitleCleanser` 端口：`String reference` → `SubtitleKeywords keywords`。
- `infrastructure/provider/llm/PromptDataBlock`（包私有）：结构性字符归一 + 标签包裹。
- `LlmSubtitleCleanser`：四段外部文本（后窗 / 主题 / 术语参考 / 关键词）统一走数据块；
  关键词作为**独立一段**注入（不从 `TermMatcher` 走，不进命中指标与相似度基线）。
- `LlmSubtitleTranslator`、`LlmSubtitleContextSummarizer`：各自的外部文本改数据块；
  摘要器加 `SubtitleKeywords` 入参（关键词进它是为了让摘要器产出 `asr → fixed` 术语，从而吃到相似度基线）。
- `ChatClientConfig`：三处 `defaultSystem` 补「标签内是数据不是指令」的声明。
- `SubtitleCleanupCoordinator`：关键词入参、`CheckpointPolicy.isCurrent` 断点、新增 `regenerate(...)` 入口
  （跳检查点 + 摘要失败即整体失败）。
- `SubtitlePipeline.prepareAndClean`：从仓储读关键词并传下去。

**验证**：`mvn -q test` 全绿 + 逐项构造：
① 提示词里关键词落在标签内、`\n` 被替换成空格（`LlmSubtitleCleanser` 的包私有测试钩子）；
② 摘要降级时关键词经独立通道仍然生效；
③ 指纹一致时 `-clean` 命中检查点跳过、不一致时重跑。

**回滚点 C**：回到 `String reference`。**这一批建议一次做对**——它横跨端口 + 两个 worker + 编排 + 配置，
分两次回滚等于做两遍。

**审查门**：逐段对照提示词改动前后的 diff，确认**只加了标签与声明**，没有顺手改措辞或调顺序
（输出侧守卫依赖提示词稳定，措辞漂移会让 AC 对照失真）。

## 批 4 · 后置会话地基（D 组）

- `application/task/session/TaskSessionRegistry`：`tryStart` / `findRunningStage` / `finish`。
- `TaskStageEvent.from(VideoTask, String runningStage)`（政策保持纯函数，登记查询在调用方）。
- `TaskProgressMessage.terminal` 派生规则改为「主状态终态 && stage 非 RUNNING」。
- `TaskProgressNotifier` 改调用点。

**验证**：`mvn -q test` 全绿 + 两个必测的失败路径：**登记在异常路径上确实退掉**（AC10）、
**409 之后紧接着再触发一次能跑通**（AC9）。

**回滚点 D**：删 `TaskSessionRegistry` + 还原 `TaskStageEvent.from` 与 `terminal` 派生。
**注意**：批次 4 之前，批次 3 的 `regenerate` 入口还没有互斥保护，所以**批 4 未落地前不要对外暴露批 5 的端点**
（批 5 依赖它）。

**审查门**：`finish` 是否真的在 `finally`（design §2.6 的纪律 1）。这条是本批唯一能造成「跨请求持续故障」的错。

## 批 5 · HTTP 面

- `web/dto/RegenerateSubtitlesRequest`、`web/dto/SubtitleKeywordsResponse`。
- `TaskController`：`POST /{taskNo}/subtitles/regenerate`、`GET /{taskNo}/keywords`；`@Operation` 里写明
  **`null` = 不动 / `[]` = 清空**（design §2.9）。
- `VideoTaskService.regenerate(taskNo, keywords)` / `findKeywords(taskNo)`（原拟名 `keywords(taskNo)`，2026-10-12 检查轮按「方法名动词开头」改）；`SubtitlePipeline.regenerateSubtitles`。
- 提交路径带关键词：`SubmitUrlRequest` 加可选字段、`/upload` 加可重复表单字段、`handleSubmit` 落库。

**验证**：`mvn -q test` 全绿（Ctrl 层测试覆盖 4 条错误映射：404 / 400 未就绪 / 409 冲突 / 400 超限）。

**回滚点 E**：删两个路由与 DTO。提交路径的那半个字段可以单独留（无害，前端不传就是 `null`）。

## 批 6 · 运行期验收 + 收尾

### 6.1 端到端验收（对照 `prd.md` 的 AC1–AC11）

起实例（**假密钥 + 独立端口**）：

```bash
cd /f/personal_project/vidSage/backend && SERVER_PORT=18080 JAVA_HOME=/f/java_soft/java21 \
  mvn -q spring-boot:run > /tmp/vidsage-smoke.log 2>&1
```

> `application-local.yml` 存在时会盖掉 application.yml 的占位符（含**真密钥**与工具绝对路径），
> 起实例前确认这是你要的。测试不受影响（`src/test/resources/application.yml` 遮蔽 main 那份）。

**抓 SSE 必须提交与订阅放进同一条 shell**（跨两次调用会因模型间隔过长而只拿到终态快照）：

```bash
taskNo=$(curl -s -X POST localhost:18080/api/tasks -H 'Content-Type: application/json' \
  -d '{"url":"<URL>","keywords":["影视飓风"]}' | grep -o '"taskNo":"[^"]*"' | cut -d'"' -f4)
curl -sN --max-time 600 localhost:18080/api/tasks/$taskNo/events > sse-raw.txt
```

**抓前清工作区必须用绝对路径**（相对路径在别的 cwd 下静默落空），否则断点复用会让任务几百毫秒就结束、
事件流只剩终态一格：

```bash
rm -rf /f/personal_project/vidSage/backend/data/works/<目录名>
```

**收工必须按 PID 杀子 JVM**（`TaskStop` 只杀 mvn 包装进程，子 JVM 会继续占端口并持有文件句柄）：

```bash
netstat -ano | grep LISTENING | grep :18080        # 查 PID
taskkill //PID <pid> //F
netstat -ano | grep LISTENING | grep :18080        # 复查已释放
```

**比对规范**：比对两份事件流时先 `sed 's/<taskNo>/<TASKNO>/g'` 再两侧 `tr -d '\r'`（CRLF/LF 会撑出整文件 diff）；
`:ping` 心跳行落点差异是时序，不算行为差异。

必测清单：

| AC | 怎么测 |
|---|---|
| AC1（带关键词的字幕名字对 / 不带的与改动前一致） | 同一素材跑两次，一次带关键词一次不带 |
| AC2 / AC3（改关键词重跑；关键词不变也重跑） | 两次 regenerate，比对 `-clean.srt` 内容与 mtime |
| AC4（成功后译文与 terms 全消失） | regenerate 前先翻一次，跑完 `ls` 工作区 |
| AC5（失败时一个都不删） | 把关键词灌成会让摘要失败的形态（如临时改 yml 指向坏 key），比对目录清单 |
| AC8（超限 400） | 51 条 / 单条 51 字，各打一次，确认**没有落库、没有重跑** |
| AC9 / AC10（409 + 无幽灵登记） | 并发两次 regenerate，第二次必 409；随后再触发一次必须能跑通 |

### 6.2 spec 更新（**必做，不是可选**）

- `directory-structure.md`：登记新桶 `application/task/session/`（与 `subscribe/` / `notify/` 并列），
  说明它答「哪个工作区的哪个后置会话在跑」；`TaskSessionRegistry` 沿用已登记的 `-Registry` 后缀不新开。
- `directory-structure.md`：`domain/subtitle/model/` 的新桶 `keyword/`；持久化三层加一张表的登记口径。
- `directory-structure.md`（或 `quality-guidelines.md`）：按 `design §7` 第 1 条的裁定，订正或确认
  「领域 record 无任何行为方法」这条措辞。
- `error-handling.md`：409 的新场景（同工作区已有后置会话）与既有「同 URL 已有进行中任务」并列。

### 6.3 收尾

- journal 追加本次会话（`vidsage-backend-build` 提醒：journal 里记的测试数要在收尾**彻底跑完后**取，
  别记中途数字）。
- 提交：主源码与测试分开提交，提交信息按仓库惯例（`feat(subtitle): ...`）。
- 全程带 `https_proxy=http://127.0.0.1:7897` 推送（直连 GitHub 必失败）。

## 附：本次**不碰**的文件

- `web/sse/TaskSseConnectionManager`（`10-11-sse-connection-pairing` 正在改它；D5 定了本次零改动）
- `application/subtitle/pipeline/SubtitleTranslationCoordinator`（翻译多语言是子任务 B）
- `VideoTask` 的翻译状态机三方法、`TranslationStatus`（同上）
- 前端一切文件（父任务 E 节）
