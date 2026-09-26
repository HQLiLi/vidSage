# MVP-2b 平台字幕与 ASR 双路转写 — PRD

> 父任务（源需求与规划期决策）：`../09-24-vidsage-mvp2-subtitles/prd.md`。
> 必读研究：`../09-24-vidsage/research/oss-references.md` 第三节 MVP2 小节（**第 12 条统一字幕数据模型、第 14 条平台字幕拉取、第 13 条时间戳补位**）、`../09-24-vidsage/research/competitive-and-asr.md`（ASR 双路）、`../09-24-vidsage/research/external-process-integration.md`。
>
> **本文件当前是范围级 PRD。** `design.md` 与 `implement.md` 在 **2a 完成后、本任务开工前**补齐：那时 2a 的实际接口已冻结，照着写才不会返工。

## 前置依赖（硬前置）

**MVP-2a 必须先完成并合入 `dev`**，本任务分支从含 2a 的 `dev` 开出。2b 直接复用 2a 冻结的接口面：

- `MediaInput` / `AcquiredMedia` / `AudioSpec`（含 `WAV_PCM_16K_MONO`、`M4A_AAC_48K_MONO`）/ `AudioArtifact`
- `AudioPreparationService.prepare(...)`（要音频时调它，不自己碰 ffmpeg）
- `ExternalProcessRunner` / `YtDlpRunner`（拉平台字幕走它，不自己拼 yt-dlp 路径与参数）
- `WorkspaceService.workspaceFor(MediaInput)`（字幕产物落同一目录）
- `StageEvent` / `ProgressListener`

**2b 不碰**：ffmpeg 参数、工具路径、`ExternalToolProperties`、工作目录内的文件名规则。

## 目标

把「一个 URL 或本地媒体文件」变成「一个 SRT 字幕文件」：优先用平台已有字幕，没有才走 ASR 兜底；两条路线对上层是**同一个产物契约**，调用方不需要知道字幕是怎么来的（但产物里留下路线标记，便于排查与统计）。

## 范围

### 1. ASR 音频规格声明（落实 epic design 的一句话）

epic design 定了「**建议由 ASR provider 声明所需格式，而非把判断散在管线里**」。本任务把它落地：本地 FunASR 要 `WAV_PCM_16K_MONO`，云端 DashScope 要 `M4A_AAC_48K_MONO`，管线据此向 2a 要对应格式的音频。

- 具体形态（`ASRProvider` 加方法 vs 独立的规格映射）在 design 阶段定
- 这会**改动已落地的 `ASRProvider` 接口与 `DashScopeAsrProvider`**，属接口变更，需在 design 里写清兼容影响

### 2. 平台字幕路线（人工优先，自动兜底）

- `yt-dlp --skip-download --write-subs`（人工字幕）**优先**；无人工字幕再 `--write-auto-subs`（自动字幕）
  - 研究第 14 条：参考项目只做了自动字幕，**人工字幕是缺口**，本任务要补上
- **字幕格式归一**：vtt / json3 / srt / ass → 统一 SRT
  - 人工字幕与自动字幕的 vtt 结构不同（自动字幕常有滚动重复行），归一逻辑要分别处理
  - 参考 `oss-references.md` 第 12 条的 `ASRData.from_srt/from_vtt/from_json` 双向转换设计
- **语言选择策略**：目标语言优先级（如 zh-Hans / zh-CN → en）、多语言字幕时的取舍，在 design 里写死一套规则并测到
- 路径优先级判定（人工字幕存在性）要落盘/可观测，别只靠文件名猜
- ⚠️ **第 12/14 条的参考对象都是 VideoCaptioner，GPL-3.0**：`asr_data.py` 的双向转换与 `video_download_thread.py` 的字幕拉取都**只能读思路、用自己的话重写**，一行源码都不许抄（`oss-references.md` 开篇「许可证红线」一节）。本任务在 Java 侧实现，本就只能抄契约与设计

### 3. ASR 路线（无平台字幕时）

- 调 `ASRProvider`（`vidsage.asr.provider` 切换 local / cloud）→ `TranscriptionResult` → SRT
- 云端路线复用 `AudioSource.AudioFilePath`（`DashScopeAsrProvider` 已实现本地文件上传）
- 时间戳补位与异常段处理参考 `oss-references.md` 第 13 条（无词级时间戳、缺 start/end 的兜底）

### 4. 统一产物 + 断点复用

- 产物契约：`subtitles.srt` + 中间 segments JSON（供 MVP-3 翻译直接消费，无需再解析 SRT）
- 路线标记落盘（`platform-manual` / `platform-auto` / `asr-local` / `asr-cloud`），便于排查「这条字幕哪来的」
- 断点复用沿用 2a 的判据（文件存在且非空即跳过），已有 SRT 时不重跑 ASR

### 5. B站风控边界实测

B站字幕拉取的登录 / 风控边界形成实测结论，**回写 epic `prd.md` 的「未决问题」**（这是父任务跨子任务验收项之一）。

## 不做

- 视频下载与音频抽取（2a 已完成）
- 翻译（MVP-3）、结构化总结（MVP-4）
- ASR 模型 bake-off、说话人分离（epic 未决问题）
- 长音频分块转写、静默点切分（父任务「不做」清单）
- 字幕编辑、断句优化、`optimize_timing` 这类显示层打磨
- SSE 与任务落库（MVP-1）

## 验收标准

- [ ] 一个**有平台字幕**的 B站视频 → 产出 SRT，且路线标记证明用的是**人工字幕**；同一视频的自动字幕兜底路径也被验证到
- [ ] 一个**无平台字幕**的视频 → 走 ASR 产出 SRT
- [ ] 一个本地 mp4 → 走 ASR 产出 SRT
- [ ] 同一段音频在 `vidsage.asr.provider=local` 与 `=cloud` 下各跑一次，产出的 SRT 结构一致（时间戳毫秒、句级切分）
- [ ] 重跑跳过已完成阶段：已有 SRT 时不重复调用 ASR / yt-dlp
- [ ] B站字幕拉取的登录 / 风控边界形成实测结论，且已回写 epic 未决问题
- [ ] 单元测试覆盖：vtt/json3 → SRT 归一、语言优先级选择、路线判定、断点复用；不起 Spring 容器
- [ ] 至少一条真实运行证据

## 待决策（进入 design 前必须由用户拍板）

1. **`LocalFunAsrProvider` 是否在本任务落地？**
   验收标准要求 local / cloud 行为一致，但本地 Provider 实现尚未落地（funasr-server 已在机器上跑通，见 journal session 5；缺的是 Java 侧实现）。
   - 选项 A：本任务补上（验收可完整验证，工作量增加）
   - 选项 B：本任务只做云端，验收标准「local/cloud 一致」改为标注延后（诚实但验收有缺口）
2. **平台字幕的语言选择策略**：目标语言优先级怎么定？多语言字幕同时存在时选哪条、选不到怎么办？
3. **vtt / json3 → SRT 归一：自己写还是引库？**
   `quality-guidelines.md` 要求「有成熟的主流库就用」。需在 design 阶段调研可用的 Java 字幕解析库（若无可用的，才自己写并在代码注释里说明理由）。
   **附加约束**：调研时必须看**许可证**。「抄第 12 条」是抄它的**数据模型设计与转换契约**，不是抄代码——VideoCaptioner 是 GPL-3.0，传染整个仓库；即便引库，也要挑 Apache-2.0 / MIT 的，别把 GPL 依赖拖进 `pom.xml`。
