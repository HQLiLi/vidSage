# 视频知识助手 VidSage（暂定名）

## 背景与目标

定位：AI 全栈 / Agent 实习方向的简历主项目，区别于学习载体 lab09 / ai-interview-coach。

一句话：输入视频链接（B站、YouTube）或本地视频文件，产出字幕、双语翻译、结构化总结，并支持带时间戳引用的内容问答。

赛道调研结论（2026-09-22 核实，仓库一手内容）：

- 字幕生成 + 翻译 + 合成是红海：VideoCaptioner（awesome-video 列表收录，约 15k star，二手来源）、VideoLingo、KlicStudio、video-subtitle-master 等功能高度重叠，几乎全是 Python。yt-dlp 是下载层事实标准（193k star，仓库页面直接核实）。
- 总结 + 内容问答接近空档：bibi-gpt 是浅实现（纯前端调 API，无 RAG 管线、无时间戳溯源、无评测；star 数未核实）；2026-09-23 另发现 junnhwan/vid-lens（映知 VidLens，Go + Next.js 视频知识库，star 数未核实），说明该方向已有人做，但 Java/Spring 全栈实现 + MCP 封装 + 评测仍是差异点。
- 策略：成熟环节对标开源实现少踩坑，创新集中在时间戳问答、MCP 封装与评测。个人求职项目不需要全新赛道，需要「成熟工作流 + 有深度的差异化」。

## 需求描述

阶段一（MVP）：

1. 任务提交：B站 / YouTube 链接或本地视频、音频文件；异步任务管线，SSE 推送进度（复用 0009 经验）。
2. 字幕获取：优先拉取平台已有字幕（yt-dlp --write-subs）；无字幕走 ASR——本地 FunASR 微服务为默认，云端 ASR API 为兜底，通过 ASRProvider 接口配置切换。
3. 翻译：全走 LLM（DeepSeek，OpenAI 兼容接口），不做传统机器翻译 API。要求上下文感知的整段翻译、术语与称谓全片一致；架构上预留「直译 -> 反思 -> 重写」的进阶轮次（借鉴 VideoLingo 三遍过思路，2026-09-22 README 核实）。导出原文 + 译文双语 SRT。
4. 总结：结构化总结（要点、章节、关键结论），长字幕走分段压缩（0007 上下文工程）。

阶段二（差异化）：

5. 时间戳问答：字幕片段向量化入库，问答返回答案 + 来源片段时间轴，前端点击可跳转。
6. MCP Server：把「检索视频内容」「获取章节总结」封装成 MCP 工具。
7. 评测：检索命中率 + 答案质量回归集（复用 0008 思路），产出可展示的优化前后数据。

Out of scope：

- 抖音等强反爬平台：下载源做成适配器接口，抖音标记为实验性，后续再补。
- 配音 / TTS、视频剪辑、硬字幕 OCR（video-subtitle-extractor 方向）。
- 模型训练、本地推理优化。
- 下载功能的公开服务化（版权灰色地带，仅自用与演示）。
- 桌面端封装（Electron / Tauri）：阶段二之后可选，不进当前范围。

## 方案

技术栈（沿用既有决策，版本以官方文档为准）：

- Java 21 + Spring Boot 4.1.x + Spring AI 2.0.1（BOM，2026-09-13 核实），前端 Vue3 + TS。
- 产品形态：浏览器展示、本地部署（2026-09-22 用户确认，暂时不做桌面封装）。Spring Boot 起服务 + Vue3 页面；后续如需桌面端，用 Tauri/Electron 包 Web 版即可，不影响核心代码。
- 下载：yt-dlp 命令行调用（2026-09-22 核实，193k star）。
- 音频抽取：ffmpeg 命令行调用。
- ASR：双路方案（2026-09-22 用户确认写入；2026-09-23 修订价格与云端服务商）。
  - 接口：`ASRProvider` 抽象，通过 `vidsage.asr.provider` 配置切换（本地 `local` / 云端 `cloud`）。已落地云端实现 `DashScopeAsrProvider`（模型 `qwen-audio-3.1-asr-flash-filetrans`，基于官方 dashscope-sdk-java 的提交+轮询）。
  - 本地实现是**开发与演示期的替身，不进入交付形态**（交付见下方「部署形态」）：本机 i7-12700H + RTX 3060 6GB + 16GB 内存可跑，默认 SenseVoice-Small（2024-07，234M，zh/en/ja/ko/yue，2026-09-23 实测 RTF ~0.06–0.16，6GB 显存无压力）。
  - 云端服务商**定为阿里云百炼**（2026-09-23 决策）：paraformer-v2 录音文件识别约 **0.29 元/小时**，含每月 36,000 秒（10 小时）免费额度，为三家最低；且百炼同时提供 Paraformer / Fun-ASR / Qwen3-ASR，与本地 FunASR 模型族对齐，本地↔云端切换时行为差异最小。
  - **价格口径修正**：2026-09-22 记录的「阿里 Paraformer 约 2.5 元/小时起」来自**智能语音交互**传统产品线；百炼大模型线为 0.29 元/小时，相差 8.6 倍。腾讯云大模型录音文件识别约 1.8 元/小时（每月赠 5 小时）；火山豆包**无免费额度**且未核实到公开单价，暂作备选。
  - 多语种视频预留 faster-whisper 备选实现。
- 翻译：LLM 唯一路线（2026-09-22 用户确认），体现 AI 原生管线定位；传统机器翻译 API 不做，接口层不为其预留实现。
- 向量库：Spring AI VectorStore 抽象，Redis 或 pgvector（选型待定）。
- Embedding：智谱 GLM embedding-3（2026-09-22 用户确认沿用主项目选型；application.yml 注释核实于 2026-09-15，OpenAI 兼容端点 open.bigmodel.cn，2048 维，key 在环境变量 GLM_EMBEDDING_KEY，主项目 0004 评测已实测可用）。
- 部署形态：开发与演示阶段本地起服务（localhost）；对外交付用轻量云服务器 + Docker Compose（jar + Redis 一键自托管，LLM/ASR/embedding 均为云 API，服务器无需 GPU）。jpackage 与 GraalVM native image 不做。
- 持久化边界按工作区规则：接口契约用 Java record，MyBatis-Plus 实体用普通类 + Lombok @Getter/@Setter，Repository 负责实体与契约转换。

架构：任务管线 download -> extract audio -> transcribe -> translate -> summarize -> index -> QA。每个阶段的产物落盘、可断点复用（借鉴 KlicStudio 分阶段 CLI 的思路，核实于 2026-09-22 README）。

为什么是这个组合：下载、转写、翻译都有成熟开源对照，实现风险低、有对照实验可讲；时间戳问答 + MCP + 评测对应招聘 JD 高频词（RAG、MCP、可评测），且赛道空档有一手调研依据。

## 验收标准

- [ ] 提交一个 B站 / YouTube 链接或本地 mp4，异步完成字幕获取与翻译，SSE 显示进度，产出双语 SRT。
- [ ] 生成结构化总结（要点 + 章节）。
- [ ] 对视频内容提问，返回带时间戳引用的答案，引用可跳转（阶段二）。
- [ ] 评测脚本输出检索命中率数据（阶段二）。
- [ ] 全流程留下至少一条真实运行证据（learning-records）。

## 未决问题

- 向量库选型（Redis vs pgvector，结合本机已有 Docker Redis 的现状）。
- 项目名：候选 VidSage（2026-09-23 GitHub 搜索未见同名仓库；VidLens、VidWise-AI、VidAtlas 均已被占用或近似占用），待用户确认后定仓库名。
- B站字幕拉取的登录 / 风控边界需要实测。
- ASR 模型 bake-off 未做。候选与关键约束（2026-09-23 调研，数据待实测复核）：
  - **FireRedASR2-AED**（1.1B；q8_0 仅 1.28 GB，fp16 2.35 GB）：歌词识别 CER **1.17%**，显著优于 Fun-ASR 3.05% / Qwen3-ASR 2.57% / Doubao 4.36%；原生**词级时间戳 + 置信度**；支持唱歌、20+ 方言、中英混说。限制：官方建议单次 ≤60 秒，需切块（词级时间戳可无损拼回时间轴）。
  - **Qwen3-ASR-0.6B**：中英混说 WER **13.6%** vs FunASR（SenseVoice 组合）19.5%，术语还原最好；但**无原生时间戳**，需另配 Qwen3-ForcedAligner-0.6B（两个模型同时驻留会挤压 6GB 显存）。
  - **MOSS-Transcribe-Diarize 0.9B**：唯一原生说话人分离。本地 6GB 上限约 **15–20 分钟**音频（KV cache 随音频长度线性增长，30 分钟约需 8GB）；云端有官方托管（`moss-transcribe-1.0` ¥0.80/小时；`moss-transcribe-diarize-pro` ¥2.00/小时，最长 60 分钟）。
  - **注意归档中的一处结论需要修正**：session-archives 记录「FireRedASR（8.3B LLM 版）超显存，不适合本机」——那是指 2025-01 的初版 LLM 变体；FireRedASR2-**AED** 仅 1.1B，不适用该结论。
  - 评测素材：一首 3 分钟歌曲（有精确 ground truth，区分度高）+ 若干真实口播 / 访谈片段。
- **是否引入说话人分离（diarization）**：现验收标准未要求，但阶段二「带时间戳引用的问答」若面向访谈 / 对话类视频可能受益。启用成本：本地需 MOSS 且受 15–20 分钟限制，云端需 ¥2.00/小时。**属产品决策，需先定——它决定上层 `TranscriptionSegment` 是否要预留 `speakerId` 可选字段，以及本地 / 云端能力不对齐时的降级策略。**
- Paraformer-Large 在本机的实测未做（SenseVoice-Small 已于 2026-09-23 实测通过）。
