# VidSage 视频知识助手 — PRD

> 竞品调研与 ASR 模型选型调研见 `research/competitive-and-asr.md`，技术方案见 `design.md`。
> 本地已下载的开源参考项目（VideoLingo / VideoCaptioner / AI-Media2Doc）「哪个环节抄哪个」见 `research/oss-references.md`。

## 背景与目标

定位：AI 全栈 / Agent 实习方向的简历主项目，区别于学习载体 lab09 / ai-interview-coach。

一句话：输入视频链接（B站、YouTube）或本地视频文件，产出字幕、双语翻译、结构化总结，并支持带时间戳引用的内容问答。

## 需求描述

### 阶段一（MVP）

1. 任务提交：B站 / YouTube 链接或本地视频、音频文件；异步任务管线，SSE 推送进度（复用 0009 经验）。
2. 字幕获取：优先拉取平台已有字幕（yt-dlp --write-subs）；无字幕走 ASR——本地 FunASR 微服务为默认，云端 ASR API 为兜底，通过 ASRProvider 接口配置切换。
3. 翻译：全走 LLM（DeepSeek，OpenAI 兼容接口），不做传统机器翻译 API。要求上下文感知的整段翻译、术语与称谓全片一致；架构上预留「直译 -> 反思 -> 重写」的进阶轮次（借鉴 VideoLingo 三遍过思路，2026-09-22 README 核实）。导出原文 + 译文双语 SRT。
4. 总结：结构化总结（要点、章节、关键结论），长字幕走分段压缩（0007 上下文工程）。

### 阶段二（差异化）

5. 时间戳问答：字幕片段向量化入库，问答返回答案 + 来源片段时间轴，前端点击可跳转。
6. MCP Server：把「检索视频内容」「获取章节总结」封装成 MCP 工具。
7. 评测：检索命中率 + 答案质量回归集（复用 0008 思路），产出可展示的优化前后数据。

### Out of scope

- 抖音等强反爬平台：下载源做成适配器接口，抖音标记为实验性，后续再补。
- 配音 / TTS、视频剪辑、硬字幕 OCR（video-subtitle-extractor 方向）。
- 模型训练、本地推理优化。
- 下载功能的公开服务化（版权灰色地带，仅自用与演示）。
- 桌面端封装（Electron / Tauri）：阶段二之后可选，不进当前范围。

## 验收标准

- [ ] 提交一个 B站 / YouTube 链接或本地 mp4，异步完成字幕获取与翻译，SSE 显示进度，产出双语 SRT。
- [ ] 生成结构化总结（要点 + 章节）。
- [ ] 对视频内容提问，返回带时间戳引用的答案，引用可跳转（阶段二）。
- [ ] 评测脚本输出检索命中率数据（阶段二）。
- [ ] 全流程留下至少一条真实运行证据（learning-records）。

## 未决问题

- 向量库选型（Redis vs pgvector，结合本机已有 Docker Redis 的现状）。
- 项目名：候选 VidSage（2026-09-23 GitHub 搜索未见同名仓库；VidLens、VidWise-AI、VidAtlas 均已被占用或近似占用），待用户确认后定仓库名。
- ~~B站字幕拉取的登录 / 风控边界需要实测~~ → **已实测（2026-09-26），结论不是风控边界，是登录态壁垒**：未登录时 B站 提取器一律不返回真实字幕（`--list-subs` 只给伪语言 `danmaku`），4 条视频（普通投稿 / 多分P / bangumi）行为一致，yt-dlp 明报 `Subtitles are only available when logged in.`。**因此平台字幕路线的前置问题不是技术而是产品**：cookies 用谁的？自用 / 演示形态下用个人 cookies 可行，部署给别人用则这条路不该存在（与本文件 Out of scope 的「下载功能公开服务化」同源）。取证见 `../09-25-vidsage-mvp2b-subtitles/research/platform-subtitle-formats.md`。
- ASR 模型 bake-off 未做（候选与约束见 `research/competitive-and-asr.md`）。
- 是否引入说话人分离（diarization）：现验收标准未要求，但阶段二「带时间戳引用的问答」若面向访谈 / 对话类视频可能受益。启用成本：本地需 MOSS 且受 15–20 分钟限制，云端需 ¥2.00/小时。**属产品决策，需先定——它决定上层 `TranscriptionSegment` 是否要预留 `speakerId` 可选字段，以及本地 / 云端能力不对齐时的降级策略。**
  - **2026-09-26 已定：`TranscriptionSegment` 不预留 `speakerId`**（经项目所有者拍板，见 `../09-25-vidsage-mvp2b-subtitles/prd.md` 规划期决策 5）。理由：当前两个 ASR 实现（DashScope、SenseVoice）都不输出说话人，预留会是恒为 `null` 的槽位，属「为将来可能用到预先造」，且 `provider-pattern.md` 明令契约不得固化实现方专属字段。**本项其余部分仍未决**——是否引入说话人分离本身、以及引入时的时间戳 / 降级策略，仍待产品决策；届时加字段本就属那个任务的范围。
- Paraformer-Large 在本机的实测未做（SenseVoice-Small 已于 2026-09-23 实测通过）。

## 任务拆分（实现以子任务为单位）

本任务是 epic/路线图，不直接承载实现。MVP 拆分为：

| 子任务 | 范围 | 状态 |
|---|---|---|
| `09-24-vidsage-mvp1-submit` | 任务提交 + 落库 + SSE 进度管线 | planning |
| `09-24-vidsage-mvp2-subtitles` | **2a 输入获取与音频抽取（已完成并归档）** + 2b 音频转字幕（走 ASR；平台字幕路线 2026-09-26 移出、降为后续项；本地 FunASR 本轮不做） | planning（父任务） |
| `09-24-vidsage-mvp3-translate` | DeepSeek 翻译 + 双语 SRT 导出 | planning |
| `09-24-vidsage-mvp4-summary` | 结构化总结 + 长字幕分段压缩 | planning |

阶段二（时间戳问答 / MCP Server / 评测）在 MVP 端到端闭环后再拆分子任务。各子任务完成时回勾本文件「验收标准」对应条目。
