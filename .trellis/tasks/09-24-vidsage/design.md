# VidSage — 技术方案

## 技术栈

沿用既有决策，版本以官方文档为准：

- Java 21 + Spring Boot 4.1.x + Spring AI 2.0.1（BOM，2026-09-13 核实），前端 Vue3 + TS。
- 产品形态：浏览器展示、本地部署（2026-09-22 用户确认，暂时不做桌面封装）。Spring Boot 起服务 + Vue3 页面；后续如需桌面端，用 Tauri/Electron 包 Web 版即可，不影响核心代码。
- 下载：yt-dlp 命令行调用（2026-09-22 核实，193k star）。
- 音频抽取：ffmpeg 命令行调用。
- ASR：双路方案（2026-09-22 用户确认写入；2026-09-23 修订价格与云端服务商），详见下文。
- 翻译：LLM 唯一路线（2026-09-22 用户确认），体现 AI 原生管线定位；传统机器翻译 API 不做，接口层不为其预留实现。
- 向量库：Spring AI VectorStore 抽象，Redis 或 pgvector（选型待定）。
- Embedding：智谱 GLM embedding-3（2026-09-22 用户确认沿用主项目选型；application.yml 注释核实于 2026-09-15，OpenAI 兼容端点 open.bigmodel.cn，2048 维，key 在环境变量 GLM_EMBEDDING_KEY，主项目 0004 评测已实测可用）。
- 持久化边界按工作区规则：接口契约用 Java record，MyBatis-Plus 实体用普通类 + Lombok @Getter/@Setter，Repository 负责实体与契约转换。

## ASR 双路方案

- 接口：`ASRProvider` 抽象，通过 `vidsage.asr.provider` 配置切换（本地 `local` / 云端 `cloud`）。已落地云端实现 `DashScopeAsrProvider`（模型 `qwen-audio-3.1-asr-flash-filetrans`，基于官方 dashscope-sdk-java 的提交+轮询）。
- 本地实现是**开发与演示期的替身，不进入交付形态**（交付见「部署形态」）：本机 i7-12700H + RTX 3060 6GB + 16GB 内存可跑，默认 SenseVoice-Small（2024-07，234M，zh/en/ja/ko/yue，2026-09-23 实测 RTF ~0.06–0.16，6GB 显存无压力）。
- 云端服务商**定为阿里云百炼**（2026-09-23 决策）：paraformer-v2 录音文件识别约 **0.29 元/小时**，含每月 36,000 秒（10 小时）免费额度，为三家最低；且百炼同时提供 Paraformer / Fun-ASR / Qwen3-ASR，与本地 FunASR 模型族对齐，本地↔云端切换时行为差异最小。
- 价格口径修正：2026-09-22 记录的「阿里 Paraformer 约 2.5 元/小时起」来自**智能语音交互**传统产品线；百炼大模型线为 0.29 元/小时，相差 8.6 倍。腾讯云大模型录音文件识别约 1.8 元/小时（每月赠 5 小时）；火山豆包**无免费额度**且未核实到公开单价，暂作备选。
- 多语种视频预留 faster-whisper 备选实现。

## 架构

任务管线 download -> extract audio -> transcribe -> translate -> summarize -> index -> QA。每个阶段的产物落盘、可断点复用（借鉴 KlicStudio 分阶段 CLI 的思路，核实于 2026-09-22 README）。

## 部署形态

- 开发与演示阶段本地起服务（localhost）。
- 对外交付用轻量云服务器 + Docker Compose（jar + Redis 一键自托管，LLM/ASR/embedding 均为云 API，服务器无需 GPU）。
- jpackage 与 GraalVM native image 不做。

## 为什么是这个组合

下载、转写、翻译都有成熟开源对照，实现风险低、有对照实验可讲；时间戳问答 + MCP + 评测对应招聘 JD 高频词（RAG、MCP、可评测），且赛道空档有一手调研依据（见 `research/competitive-and-asr.md`）。
