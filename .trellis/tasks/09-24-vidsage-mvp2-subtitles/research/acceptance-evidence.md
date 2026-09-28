# MVP-2 跨子任务验收：补做的实测证据（2026-09-28）

> 父任务 8 条跨子任务验收里，此前只有 1 条（B站字幕拉取结论回写 epic）在子任务阶段销账。本次补跑缺口，逐条留证。
> 环境：本地起 Spring Boot `--server.port=8081`（8080 被 IDE 实例占用），JDK 21，`DASHSCOPE_API_KEY` 已设；输入全部取本机既有媒体（`backend/data/works/`，已 gitignore）。

## #1 一条 B站链接 + 一个本地 mp4，各走 ASR 路径产出字幕文件

**本地 mp4 那半（本次补）**

- 输入：`backend/data/works/https___www.bilibili.com_video_BV1Tk3Q69Erd__spm_id_from=333.1365.list.card_arch-42c1fbca3275/source.mp4`，**18,628,258 字节**
- 调用：`POST /api/subtitles`，body `{"localPath": "…/source.mp4"}`
- 结果：**HTTP 200，耗时 62.5s**；`route=ASR_CLOUD`、`language=null`、`segmentCount=20`、`srtSizeBytes=2533`
- 产物落在**新的内容寻址目录** `backend/data/works/source.mp4-23766966e2a8/`：
  `segments-asr-cloud.json`（3493 B）+ `subtitles-asr-cloud.srt`（2533 B）
- 目录名按「绝对路径 + 文件大小 + mtime」寻址——与 URL 输入那条路（`https___…-42c1fbca3275/`）完全隔离，互不干扰

**B站链接那半**：已有证据，见 `../../archive/2026-09/09-27-subtitle-cleanup/research/run-evidence.md`（`BV1axNJ6REoa`，36 → 42 段，全流程断言逐条实测）。

## #2 一个本地纯音频文件，走 ASR 路径产出字幕

- 输入：`…/BV1Tk3Q69Erd…/audio-16k1ch48k-t0.m4a`，**690,704 字节**
- 调用：`POST /api/subtitles`，body `{"localPath": "…/audio-16k1ch48k-t0.m4a"}`
- 结果：**HTTP 200，耗时 57.6s**；`route=ASR_CLOUD`、`segmentCount=21`、`srtSizeBytes=2562`
- 产物：`backend/data/works/audio-16k1ch48k-t0.m4a-e81cabf42c47/` —— `segments-asr-cloud.json`（3566 B）+ `subtitles-asr-cloud.srt`（2562 B）
- 纯音频输入照走「抽音频 → 转写」两阶段，未因输入已是音频而走特殊分支

## #3 中途失败后重跑，已完成阶段被跳过、不重复下载 / 抽音频 / 转写

两种情形都实测了：

**(a) 全完成后再跑（断点复用）**

- 同一 mp4 第二次调用：**HTTP 200，0.071s**（首次 62.5s，相差三个数量级）
- 响应字段与首次**完全一致**：`route=ASR_CLOUD`、`segmentCount=20`、`srtSizeBytes=2533`
- 日志：`字幕断点复用跳过: segments=segments-asr-cloud.json, routeToken=asr-cloud`，**整份日志只出现 1 次**

**(b) 模拟「转写阶段失败」（已完成的音频阶段被跳过）**

- 前置：删掉 `segments-asr-cloud.json` + `subtitles-asr-cloud.srt`，**保留** `audio-16k1ch48k-t0.m4a`；再调一次
- 结果：**HTTP 200，32.0s**（比首次 62.5s 短——省掉了获取与抽音频）
- 日志：`extract 阶段跳过（断点复用）: file=audio-16k1ch48k-t0.m4a`
- 转写**确实重跑了**：`segmentCount=21`（首次是 20）、`srtSizeBytes=2571`（首次 2533）

**下载阶段的跳过**：本轮两次输入都是 `LocalPath`，不存在下载阶段，故未覆盖。下载/获取阶段的跳过证据在 mvp2a（`implement.md` 步骤 9-4「一个本地 mp4 → 不下载、直接抽音频〔实测〕」）。

## #4 缺任一外部工具（yt-dlp / ffmpeg / ffprobe）时，报错说清「缺什么、怎么补」

把工具路径指到不存在的路径起服务，逐一触发：

| 场景 | 输入 | 结果 |
|---|---|---|
| 三件都缺，本地 mp4 打 `/api/media/audio` | `localPath` | 500 `配置的 **ffprobe** 路径不存在：F:\nonexistent\ffprobe；请修正 **vidsage.tools.ffprobe-path**，或清空该配置以从 PATH 解析` |
| 三件都缺，远程 URL 打 `/api/subtitles` | `url` | 500 `配置的 **yt-dlp** 路径不存在：F:\nonexistent\yt-dlp；请修正 **vidsage.tools.yt-dlp-path**，或清空该配置以从 PATH 解析` |
| 只缺 ffmpeg，本地 mp4 打 `/api/media/audio` | `localPath` | 500 `配置的 **ffmpeg** 路径不存在：F:\nonexistent\ffmpeg；请修正 **vidsage.tools.ffmpeg-path**，或清空该配置以从 PATH 解析` |

三件工具全部覆盖，提示都做到了「点名工具 + 指明配置键 + 给出替代做法（清空以从 PATH 解析）」。

> **顺带发现一个测试缺口**：`ExternalToolDiagnosticsTest` 只断言了 **yt-dlp** 的 `hint()`（第 38-40 行），ffmpeg / ffprobe 的提示此前无任何测试覆盖——本轮靠实测才发现它们也正确。值得补两条断言（可落成后续小任务或下次改到该文件时顺手补）。

## #5 有一处能证明「换 provider 后不会误用上一次产物」的实测

**前提说清：没法真切换 provider。** `SubtitleRoute` 四个取值里只有 `ASR_CLOUD` 能产出（`ASR_LOCAL` 与两个 `PLATFORM_*` 都是「本轮不产出、保留取值以冻结契约域」），`DashScopeAsrProvider` 挂了 `@ConditionalOnProperty(havingValue="cloud")`，**没有第二个实现可切**——这与验收第 7 条（local/cloud 结构一致）同源阻塞。

**改测该验收要防的那个具体失效模式**：在同一工作目录里埋一条**另一路由**的产物，看本次路由会不会误用。

- 前置：在工作目录 `source.mp4-23766966e2a8/` 里删掉 cloud 的字幕产物，**埋入** `segments-asr-local.json`（122 B，内容含 `DECOY` 标记）+ `subtitles-asr-local.srt`（60 B）
- 调用：`POST /api/subtitles`，同一个 `localPath`
- 结果：**HTTP 200，32.0s**（真调了 ASR，产出 21 段）
  - 响应 `srtPath` = `…\source.mp4-23766966e2a8\subtitles-**asr-cloud**.srt` —— 是 cloud 产物，不是 local
  - **诱饵文件字节数一字未动**（122 B / 60 B，`DECOY` 标记原样）
  - 新产的 `segments-asr-cloud.json` 无 `DECOY`、含真实转写内容
- 机制依据：`SubtitleService.java:125-131` 的断点查找走 `SubtitleArtifactNames.segmentsFileName(route)`，文件名由 `route.name()` 派生 kebab token（`ASR_CLOUD` → `asr-cloud`）

**结论**：该失效模式被实测排除，属**结构上不可能发生**（文件名按路由隔离）。但这不等于「真切换 provider 的实测」——那要等 `ASR_LOCAL` 落地。

**诱饵已清理**（验证完即删）：留在工作目录里会是隐患——将来 `ASR_LOCAL` 真落地后，那个假 `-local` 产物会被当成合法断点。

## 遗留与发现

1. **ASR 输出非确定**：同一份音频两次转写得到 20 段 / 2533 B 与 21 段 / 2571 B。**任何「逐字节比对产物」类的验收都不可靠**，要改成结构化断言（段数区间、关键文本存在性）。
2. **下载阶段未被本次覆盖**（输入全是 LocalPath），证据仍在 mvp2a 与 run-evidence。
3. **#5 与 #7 同源阻塞**：都卡在 `ASR_LOCAL` 未落地。
4. **#4 的测试缺口**：ffmpeg / ffprobe 的提示无测试覆盖（见上）。
5. 验证过程中为逼出 ffmpeg 提示，删过 `source.mp4-23766966e2a8/audio-16k1ch48k-t0.m4a`（下次跑会重新抽取，无影响）。
