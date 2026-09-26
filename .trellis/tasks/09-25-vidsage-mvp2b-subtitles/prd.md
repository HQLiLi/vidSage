# MVP-2b 音频转字幕（ASR → SRT）— PRD

> 父任务（源需求与规划期决策）：`../09-24-vidsage-mvp2-subtitles/prd.md`。
> 技术方案：`design.md`；执行计划：`implement.md`。
>
> **本次修订（2026-09-26）**：**平台字幕路线整体移出本任务**，降为后续添加项。修订理由与实测依据见「规划期决策」第 3 行与「被移出的范围」一节。原 PRD 里与平台字幕相关的条款（人工优先/自动兜底、vtt/json3 归一、语言选择策略、B站风控实测）**全部失效**，不要按旧描述实现。

## 前置依赖（硬前置）

**MVP-2a 已完成并合入 `dev`**（实现 `4bd3574` → 合入 `cc111b8`，任务已归档）。本任务直接复用 2a 冻结的接口面：

- `MediaInput` / `AcquiredMedia` / `AudioSpec`（含 `WAV_PCM_16K_MONO`、`M4A_AAC_48K_MONO`）/ `AudioArtifact`
- `AudioPreparationService.prepare(MediaInput, AudioSpec, ProgressListener) → AudioArtifact`（要音频时调它，不自己碰 ffmpeg）
- `WorkspaceService.workspaceFor(MediaInput)`（字幕产物落同一目录）
- `StageEvent` / `ProgressListener`

**本任务不碰**：ffmpeg 参数、工具路径、`ExternalToolProperties`、工作目录内的文件名规则、yt-dlp。

> 原 PRD 列的「复用 `ExternalProcessRunner` / `YtDlpRunner` 拉平台字幕」一条**随平台字幕一并移出**。理由见下：本轮不再需要 yt-dlp。

## 目标

把「一个 URL 或本地媒体文件」变成「一个 SRT 字幕文件 + 一份 segments JSON」。

一句话判据：**调用方只认产物，不认过程**——拿到 `subtitles-<路线>.srt` 与 `segments-<路线>.json`，不需要知道中间经过了几次外部调用、音频是什么格式、转写走的是哪家服务。

## 范围

### 1. ASR 音频规格声明

epic design 定了「**建议由 ASR provider 声明所需格式，而非把判断散在管线里**」。本任务把它落地：云端 DashScope 要 M4A，管线据此向 2a 要对应格式的音频，**不在管线里写死容器名**。

具体形态（`ASRProvider` 加方法 vs 独立的规格映射）见 `design.md` 决策 1。这会**改动已落地的 `ASRProvider` 接口与 `DashScopeAsrProvider`**，属接口变更。

### 2. ASR 转写路线（本轮唯一路线）

- 调 `TranscriptionService` → `ASRProvider`（当前 `vidsage.asr.provider=cloud`）→ `TranscriptionResult`
- 云端路线复用 `AudioSource.AudioFilePath`（`DashScopeAsrProvider` 已实现本地文件上传到百炼临时存储）
- **本轮不实现本地 FunASR 实现**（`LocalFunAsrProvider`），见规划期决策第 4 行

### 3. 统一产物 + 路线标记

- 产物契约：`subtitles-<路线>.srt` + `segments-<路线>.json`（后者供 MVP-3 翻译直接消费，无需再解析 SRT）
- **路线标记落盘**，四值一次定死：`platform-manual` / `platform-auto` / `asr-local` / `asr-cloud`；本轮只产出 `asr-cloud`
- 产物文件名带路线指纹，理由见 `design.md` 决策 4

### 4. 断点复用

- 判据：**`segments-<路线>.json` 存在且非空即跳过**，不重跑音频准备与 ASR
- 为什么检查点选 JSON 而不是 SRT，见 `design.md` 决策 4（空转写会产出 0 字节 SRT）

## 规划期决策（2026-09-26，用户逐条拍板）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| 1 | 本轮范围（是否含 LLM 翻译） | **只到 SRT**。翻译仍在 MVP-3 | 2b 现有验收标准除平台字幕那条外全是关于 SRT 的；翻译有独立的一大块设计面（上下文分块、全局术语表、LLM 坏 JSON 鲁棒性、双语排版）。先冻结字幕契约，MVP-3 照它写，不返工 |
| 2 | 平台字幕是否留扩展位 | **只留路线标记枚举**（四值），本轮只产出两个 | 枚举成本近零，将来加平台字幕时不用改契约、不用改产物 JSON 的取值域。符合 2a 先例：不为单一实现预先抽接口，但把已想清楚的枚举一次定死 |
| 3 | 平台字幕路线 | **整体移出本任务**，降为后续添加项 | 实测（`research/platform-subtitle-formats.md`）：**B站平台字幕是登录态资源**，未登录时 4 条视频（普通投稿 / 多分P / bangumi）一律只给出伪语言 `danmaku`（弹幕 XML），yt-dlp 明报 `Subtitles are only available when logged in.`；要用就得配个人 cookies，而 cookies 指向「谁在用什么账号」这类部署形态问题，不该由本任务顺手决定 |
| 4 | 本地 FunASR 实现 | **本轮不做**，只做云端 | 用户决定。**直接后果：验收标准里「local / cloud 产出结构一致的 SRT」改为标注延后**，原因写在该条目下。代价是 epic 定的「双路 ASR」在代码上仍是空的，字幕契约只经一个实现验证 |
| 5 | `TranscriptionSegment` 预留 `speakerId` | **不预留** | 两个实现都不输出说话人，字段会是恒为 `null` 的槽位，属「为将来可能用到预先造」；且 provider-pattern 明令契约不得固化实现方专属字段。将来说话人分离立项时加字段本就是那个任务的范围 |

## 不做

- **平台字幕（整条路线）**：`yt-dlp --write-subs` / `--write-auto-subs`、vtt/json3 → SRT 归一、平台字幕语言选择策略、B站 cookies 配置。全部降为后续添加项
- **本地 FunASR 实现**（`LocalFunAsrProvider`）与 ASR 模型 bake-off、说话人分离
- 翻译（MVP-3）、结构化总结（MVP-4）
- 长音频分块转写、静默点切分（父任务「不做」清单）
- 字幕编辑、断句优化、`optimize_timing` 这类显示层打磨
- SSE 端点、任务落库、任务提交接口（MVP-1）
- ASR 内容寻址缓存（`oss-references.md` 第 18 条）：本任务只用「产物存在即跳过」的粗粒度断点

## 验收标准

- [ ] 一条 B站链接 → 抽音频 → 云端 ASR → 产出 SRT，路线标记为 `asr-cloud`
- [ ] 一个本地 mp4 → 同一条链路 → 产出结构一致的 SRT
- [ ] 一个本地纯音频文件（如 `asr-service/sample.wav`）→ 产出 SRT
- [ ] 重跑跳过已完成阶段：已有 segments JSON 时不重复下载 / 抽音频 / 调 ASR
- [ ] **`vidsage.asr.provider=local` 与 `=cloud` 产出结构一致的 SRT —— ⏸ 标注延后**，原因：本轮不实现本地实现（规划期决策 4）。此条在 `LocalFunAsrProvider` 落地后补验
- [ ] 产物文件名与内容里的路线标记一致，且**换 provider 后不会误用上一次的产物**（产物名带路线指纹）
- [ ] 单元测试覆盖：SRT 时间格式、空转写产出、路线指纹命名、断点复用判定、JSON 契约序列化；**不起 Spring 容器**
- [ ] 至少一条真实运行证据

## 被移出的范围（后续添加项）

平台字幕路线整体降级，**不是砍掉**。将来重启时需要的实测结论已经落盘在 `research/platform-subtitle-formats.md`（651 行，逐条标注实测/未验），要点：

1. B站平台字幕需登录态；未登录时 `--write-subs` 会「成功」产出 `*.danmaku.xml`——**盲收 yt-dlp 产物会把弹幕当字幕吃进去**，必须按语言码白名单过滤掉 `danmaku`
2. 「平台无字幕」**exit 0 且无产物**，不能靠退出码判断
3. 人工字幕与自动字幕的产物**文件名完全相同**，路线标记只能靠「哪一次调用产出的」
4. `--convert-subs srt` 只能转 VTT（且需 ffmpeg）；`json3` 与弹幕 `xml` 转不了
5. 登录后 B站给出什么格式**未验**——这决定归一要不要自写 `json` 解析，重启时优先补测

该文件里的未验证项清单（附 B，8 条）也一并留档。
