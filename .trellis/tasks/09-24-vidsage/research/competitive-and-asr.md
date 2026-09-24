# 赛道调研与 ASR 模型选型

## 赛道调研结论（2026-09-22 核实，仓库一手内容）

- 字幕生成 + 翻译 + 合成是红海：VideoCaptioner（awesome-video 列表收录，约 15k star，二手来源）、VideoLingo、KlicStudio、video-subtitle-master 等功能高度重叠，几乎全是 Python。yt-dlp 是下载层事实标准（193k star，仓库页面直接核实）。
- 总结 + 内容问答接近空档：bibi-gpt 是浅实现（纯前端调 API，无 RAG 管线、无时间戳溯源、无评测；star 数未核实）；2026-09-23 另发现 junnhwan/vid-lens（映知 VidLens，Go + Next.js 视频知识库，star 数未核实），说明该方向已有人做，但 Java/Spring 全栈实现 + MCP 封装 + 评测仍是差异点。
- 策略：成熟环节对标开源实现少踩坑，创新集中在时间戳问答、MCP 封装与评测。个人求职项目不需要全新赛道，需要「成熟工作流 + 有深度的差异化」。

## ASR 模型 bake-off 候选（2026-09-23 调研，数据待实测复核）

- **FireRedASR2-AED**（1.1B；q8_0 仅 1.28 GB，fp16 2.35 GB）：歌词识别 CER **1.17%**，显著优于 Fun-ASR 3.05% / Qwen3-ASR 2.57% / Doubao 4.36%；原生**词级时间戳 + 置信度**；支持唱歌、20+ 方言、中英混说。限制：官方建议单次 ≤60 秒，需切块（词级时间戳可无损拼回时间轴）。
- **Qwen3-ASR-0.6B**：中英混说 WER **13.6%** vs FunASR（SenseVoice 组合）19.5%，术语还原最好；但**无原生时间戳**，需另配 Qwen3-ForcedAligner-0.6B（两个模型同时驻留会挤压 6GB 显存）。
- **MOSS-Transcribe-Diarize 0.9B**：唯一原生说话人分离。本地 6GB 上限约 **15–20 分钟**音频（KV cache 随音频长度线性增长，30 分钟约需 8GB）；云端有官方托管（`moss-transcribe-1.0` ¥0.80/小时；`moss-transcribe-diarize-pro` ¥2.00/小时，最长 60 分钟）。
- **注意归档中的一处结论需要修正**：session-archives 记录「FireRedASR（8.3B LLM 版）超显存，不适合本机」——那是指 2025-01 的初版 LLM 变体；FireRedASR2-**AED** 仅 1.1B，不适用该结论。
- 评测素材：一首 3 分钟歌曲（有精确 ground truth，区分度高）+ 若干真实口播 / 访谈片段。
