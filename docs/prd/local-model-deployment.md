# 本地模型部署方案

## 背景与目标

VidSage 管线里 ASR、翻译、向量化都要调模型。云端 API 按量计费且演示依赖网络，用户希望先把本地模型跑起来：一是降低使用成本，二是体验本地部署，三是为简历项目增加「本地模型 + 双路 Provider 架构」的深度。

## 需求描述

把三个模型环节全部本地化，同时保留云端兜底：

1. **ASR**：FunASR（SenseVoice-Small 默认，Paraformer-Large 备选）独立 Python 微服务，提供 REST 转写接口。**2026-09-23 修订：本机原生进程运行，非 Docker**（见「方案」）。
2. **LLM**：Ollama（Docker）跑 Qwen3 量化模型，提供 OpenAI 兼容接口，用于翻译与总结；DeepSeek 云 API 保留为质量兜底。
3. **Embedding**：bge-m3（Ollama 拉取），用于字幕片段向量化；GLM embedding-3 云端保留兜底。

不做的事（Out of scope）：

- 模型微调、训练、量化调优。
- 本地 TTS / 配音。
- 服务器 GPU 部署（云端部署走轻量服务器 + 云 API 组合，见主 PRD）。

## 方案

**部署形态（2026-09-23 修订）**：原定 Docker Compose 统一编排，实测后**改为本机原生进程**。原因：FunASR v1.3+ 自带 `funasr-server`（OpenAI 兼容端点），Windows 原生可直接跑，无需容器；WSL2 跨文件系统 9P I/O 慢。Docker 编排保留为后续服务器交付形态（服务器无 GPU，走云 API）。

**6GB 显存预算的关键依据**：管线是顺序执行的（transcribe -> translate -> summarize -> index），模型不需要同时常驻显存。Ollama 默认在空闲后卸载模型，FunASR 服务按请求加载，同一时间基本只有一个大模型占用 GPU。

| 环节 | 本地方案 | 云端兜底 | 预计显存 |
|------|----------|----------|----------|
| ASR | FunASR SenseVoice-Small | **阿里云百炼**（2026-09-23 定，0.29 元/小时 + 每月 10 小时免费） | ~1-2GB |
| LLM | Qwen3 4B/8B 量化（Ollama） | DeepSeek API | 4B Q4 约 3GB / 8B Q4 约 5GB |
| Embedding | bge-m3（Ollama） | GLM embedding-3 | ~1GB（可 CPU） |

架构要求：Spring Boot 侧沿用主 PRD 的 Provider 抽象，`ASRProvider` / `LLMProvider` / `EmbeddingProvider` 三个接口各留本地与云端两个实现，通过 `application.yml` 切换，本地默认。

涉及的模块（项目初始化阶段同步落地）：

- `docker-compose.yml`：funasr-service、ollama、主应用（后续加 Redis）。**注：2026-09-23 起本机开发改为原生进程，此文件延后到服务器交付阶段再建。**
- FunASR 微服务：**2026-09-23 实测后改为直接用官方 `funasr-server`，不手写 FastAPI 封装**——`POST /v1/audio/transcriptions` 的 `verbose_json` 已返回句级时间戳分段，自建无收益。
- Spring Boot 三个 Provider 接口 + 本地实现。

## 验收标准

- [x] ~~`docker compose up` 一键拉起 FunASR 服务、Ollama、主应用~~ → **2026-09-23 改为原生进程验证**：`funasr-server --model sensevoice --device cuda --host 127.0.0.1 --port 8100` 单服务拉起并转写成功。Docker 编排延后到服务器交付阶段。
- [ ] 传入一段本地音频，FunASR 微服务返回带时间戳的分段转写结果。
- [ ] 通过 Ollama（Qwen3）完成一段字幕的翻译，输出结构稳定。
- [ ] 通过 bge-m3 向量化一条字幕并成功入库检索。
- [ ] `application.yml` 切换 Provider 后，三个环节均可走云端实现，结果可用。

## 未决问题

- Qwen3 4B 与 8B 在本机 RTX 3060 6GB 上的实际速度与显存占用实测（决定默认档位）。
- ~~本机 Docker Desktop 的 GPU 直通（WSL2）是否可用~~ → **已作废**：2026-09-23 改为本机原生进程，不走 Docker。
- ~~FunASR 官方 Docker 镜像 vs 自建 FastAPI 封装~~ → **已解决**：用官方 `funasr-server`。实测补充：`verbose_json` 返回完整句级时间戳（Java 侧只认这个格式）；`srt` / `vtt` 未实现，会原样返回 json。

## 实测约束（2026-09-23）

- **云端 DashScope 本地文件上传：oss:// 临时地址必须带 Header X-DashScope-OssResourceResolve: enable**（2026-09-24 实测，缺失时 filetrans 报 REQUEST_INVALID_FILE_URL_VALUE FAILED；已在 DashScopeAsrProvider.buildParam 对 oss:// 来源加头修复）。百炼临时存储免费、48 小时自动清理；sample.wav / sample.m4a 本地路径转写均通过。
- **ffmpeg 兜底解码路径会丢失时间戳**（`duration` / `end` 返回 0.0）→ Java 侧必须抽 16k wav 再上传，不能直接送原始 m4a。
- 已就位模型：SenseVoice-Small（936 MB）、Fun-ASR-Nano-2512（2.13 GB），均在 `F:\app_soft\ai-cache\modelscope`。
- 环境：`asr-service/.venv`（uv 管理，Python 3.12.14）+ 系统级 ffmpeg（`F:\app_soft\ffmpeg\bin`）。
- 启动命令：`funasr-server --model sensevoice --device cuda --host 127.0.0.1 --port 8100`。
