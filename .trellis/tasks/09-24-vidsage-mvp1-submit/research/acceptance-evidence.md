# MVP-1 真实运行证据（acceptance evidence）

> **2026-10-02 15:16 两段式补充（翻译拆分为按需触发后）**：URL `BV11QMd6WEJp`，
> taskNo `8c5af2e72b2b4aff99154ddd110f02cf`。
> ① 提交 → **16 秒 SUCCEEDED**，artifacts 仅 4 件（原文/清洗，**无翻译产物**），translationStatus=NULL；
> ② `POST /api/tasks/{taskNo}/translate` → 202（translationStatus=RUNNING）→ 数十秒后 translation
> **SUCCEEDED**，`subtitles-asr-cloud-translate-ja.srt` 下载 200；翻译中重复触发 → 202 不重入。
> 本地表已 DROP 重建（translation_status 列）。

> 运行时间：2026-10-02 10:11–10:24（本机）
> 环境：分支 `feature/mvp1-task-pipeline`（步骤 1-8 全部实现 + 两轮质检后）；Boot 4.1.1 +
> MyBatis-Plus `mybatis-plus-spring-boot4-starter:3.5.17`；本机 MySQL 8.0.37（库 `vidsage`，
> schema.sql 随启动自动执行）；翻译目标语种为当时配置 `vidsage.subtitle.translate.target-language=ja`
> （项目所有者为官方译名实验所设，本证据如实记录，不构成对语种的主张）。
> 服务进程：`mvn spring-boot:run`，PID 16552（第二次启动，含 SSE 退订修复，见「事件插曲」）。

## Run 1 — URL 提交 → 全流程（验收核心）

- 提交：`POST /api/tasks {"url":"https://www.bilibili.com/video/BV1GJ411x7h7"}`
  （【官方 MV】Never Gonna Give You Up - Rick Astley，212 秒）
  → **HTTP 202**（秒回，不等管线），taskNo `b15e718f1564464187c13c42fd607929`，status PENDING。
- 重复提交（管线运行中）：同请求重发 → **HTTP 409**，响应体含 `existingTask`（taskNo + status=RUNNING
  + currentStage=acquire），错误消息中文、给出「查询或订阅既有任务」的指引。
- 执行：**10:11:12 → 10:12:54，全流程 1 分 42 秒无人值守跑完**：
  `acquire`（yt-dlp 下载）→ `extract-audio`（ffmpeg 抽 M4A）→ `transcribe`（DashScope 云端转写）→
  `write-subtitles` → `clean`（LLM 纠错，3 批）→ `translate`（LLM 翻译 en→ja，5 批）→ **SUCCEEDED**。
- SSE 全程留档：`research/sse-url-run.log`——快照首推、每阶段 RUNNING/SUCCEEDED、批级进度
  （`"message":"第 1/3 批"`、`"第 5/5 批"`）、30s 心跳注释行 `:ping`、终态 `taskStatus:SUCCEEDED`
  后服务端主动收口。任务级进度权重符合 design（0..60 / 60..80 / 80..100，批级区间单调）。
- 终态查询：`GET /api/tasks/{taskNo}` → status=SUCCEEDED，artifacts 6 件（原始/清洗/翻译的
  segments JSON 与 SRT，含字节数）。

## Run 2 — 文件上传 → 全流程

- 提交：`POST /api/tasks/upload`（multipart，upload-evidence.mp4，18.6 MB——取自既有工作区的
  BV1Tk3Q69Erd 视频）→ **HTTP 202**，taskNo `513d8dab5e3c4e879655432a0adaac41`。
- 全流程跑完（与 Run 1 并发执行——信号量 `vidsage.task.max-concurrent=2` 恰好双满载）→
  **SUCCEEDED**，artifacts 6 件（含 `subtitles-asr-cloud-translate-ja.srt` 5202 B）。

## Run 3 — 上传同文件重传 → 断点复用（SKIPPED）

- 再次上传**同一份文件**：`POST /api/tasks/upload` → 202，taskNo `551bd1d15e2d49f3919662b7bd202958`。
- **创建与完成同秒**（10:22:11）：内容寻址 sha256 命中同一工作区，应用日志显示全链 SKIPPED——
  `已有 segments 产物，跳过转写` / `已有字幕产物，跳过写盘` / `已有清洗产物，跳过清洗` /
  `已有翻译产物，跳过翻译`，零重算、零 API 调用。

## Run 4 — URL 重提交 → 断点复用（SKIPPED）

- 重发 Run 1 的 URL → taskNo `37539f245021477880c23543dd779223`，数秒内 SUCCEEDED，
  应用日志同上全链 SKIPPED（transcribe 57 / write-subtitles 60 / clean 80 / translate 100）。

## 产物下载与路径穿越防护

- `GET /api/tasks/b15e718f…/artifacts/subtitles-asr-cloud-translate-ja.srt` → **200**，
  内容为双语 SRT（原文 + 译文逐句对照，见 `research/artifact-translate-ja.srt` 头几行）。
- 穿越矩阵：编码绕过 `..%2F..%2Fapplication.yml` → **400**；字面 `../` → 404（容器规范化先行，
  与批次 B 实现时的判断一致：服务层 resolve 后校验兜住编码形态）；幽灵 taskNo → **404**。

## 事件插曲：SSE 重复推送（真实运行抓到的缺陷，已修复）

Run 1 的 SSE 抓流中每个中间事件出现两次（40 帧 / 管线实际发射 20 次）。根因：第一次抓流的 curl
因输出目录不存在在客户端写失败断连——hub 的 onError 钩子摘除了**连接**，但控制器没有退订注册表里的
**订阅者**，泄漏的消费者继续把每个广播事件写给幸存连接（终态事件只一份：第一次终态写完即
complete 清场）。修复：`TaskController.subscribeEvents` 把 `subscription.close()` 挂进
`emitter.onCompletion`（断连 / 超时 / 终态都会走它，close 幂等）。修复后重启（PID 16552）复跑：
Run 3 的 SSE 流无重复帧；终态快照推送 + 立即收口的「订阅已完结任务」路径同时得到验证。

## 验收标准对照（PRD）

| 验收标准 | 结果 |
|---|---|
| 提交 URL / 上传文件秒级返回 202 + 任务 ID；持久化可查；长视频不顶超时 | ✅ Run 1/2（202 秒回；状态与产物经查询端点核对） |
| SSE 按任务实时推送阶段事件；重复提交见 SKIPPED | ✅ Run 1 全程流（含 SKIPPED 于 Run 3/4 语义等同场景：复用链在 SSE 与应用日志均有记录；SKIPPED 事件帧见 Run 3 应用日志摘录） |
| 一条 B 站 URL 无人值守跑完全流程，产物端点取到双语 SRT | ✅ Run 1 + 产物下载 200 |
| 非法输入明确 4xx | ⚠️ 部分达标——url/文件同时传、都不传、空文件、重复提交 → 400/409 ✅；「不支持的链接」目前走管线以 FAILED + SSE 呈现而非提交时 4xx（提交口做站点白名单等于复制 yt-dlp 的站点知识）。**留待项目所有者裁决**：接受 FAILED 呈现则修订该条 AC 措辞；要求 4xx 则需加提交口预检 |
| 阶段失败 → FAILED 可查 + SSE FAILED 事件 | ✅ 机制有单测与切片测试覆盖（本轮四次真实运行均未触发失败路径，真实失败证据由既有任务的失败用例补足） |
| 至少一条真实运行证据 | ✅ 本文档 |

## 遗留

- 2GB 上传超限的 `MaxUploadSizeExceededException` 未映射为统一错误形状（终检遗留项，非验收内）。
- 管线级看门狗（ASR / LLM 无超时）为既有 follow-up。
