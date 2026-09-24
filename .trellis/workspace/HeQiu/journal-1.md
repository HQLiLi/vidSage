# Journal - HeQiu (Part 1)

> AI development session journal
> Started: 2026-09-23

---


## Session 1: 本地 ASR 部署与 funasr-server 实测
<!-- trellis-session: v=2 fp=517f84bba2e9d215 -->

**Date**: 2026-09-23
**Task**: 本地 ASR 部署与 funasr-server 实测
**Branch**: `master`

### Summary

从本地 ASR 模型选型调研起，在 Windows 本机（RTX 3060 Laptop 6GB）原生部署 FunASR 的 OpenAI 兼容转写服务并实测通过。环境由 conda 切换为 uv，缓存迁至 F 盘，补齐 ffmpeg 解码。

### Main Changes

# 会话归档：2026-09-23 vidSage 本地 ASR 部署

## 用户摘要

这次会话从"推荐本地 ASR 模型"聊起，最终在 Windows 本机（RTX 3060 Laptop 6GB）完整部署了 FunASR 的 OpenAI 兼容转写服务并实测通过。环境从 conda 起步、中途应你的要求切换为 uv 管理，缓存全部迁到 F 盘（C 盘释放约 9GB），并装了 ffmpeg 补齐 AAC/MP3 解码。SenseVoice-Small 和 Fun-ASR-Nano 两个模型都已就位，转写接口（含句级时间戳）验证通过。下一会话的接续点：写 Java 侧 LocalFunAsrProvider、部署 Qwen3-ASR-0.6B 和 MOSS-Transcribe-Diarize 0.9B 做三模型对比实测、删除冗余的 conda 环境。

## AI 检索段

日期：2026-09-23。session id：01a0cdd7-8ffb-7353-a15b-653057058a25。一句话标题：vidSage 项目本地 ASR 模型选型调研 + Windows 原生部署 funasr-server 并实测转写。

用户请求要点（保留关键词）：
- "部署本地asr模型，推荐主流最新模型"
- "部署是怎么部署"、"不用走wsl+docker吗"、"Windows上所有asr模型都行吗"
- "用conda不用uv？"、"conda装在哪里"、"先加入path"、"系统python是不是没用了"（答：保留，node-gyp/Electron 打包依赖）
- 用户规则："只有我说'执行'2字时才能装东西"
- "python我没学过"、"需要自己写接口吗，没有现成的吗"（答案：funasr-server 官方 OpenAI 兼容服务器，无需自写）
- "SenseVoice-Small 什么时候出的"（2024-07）、"现在 2026.9 只推荐到年初？"（补查了 2-9 月新模型：VibeVoice-ASR 需 24GB、GLM-ASR-Nano 需 8GB+、Moonshine 英文实时向，结论不变）
- "ASR 是不是换成新的比较好"（结论：bake-off 实测决定，默认 SenseVoice 不动）
- "不是只有中文，还有英文日语要翻译"（ASR 只管转写；翻译走 LLM 环节；多语种路由：5 语种内 SenseVoice、长尾 faster-whisper）
- "怎么调用llm"、"llm是个文件还是文件夹"（讲解 fun_asr_nano/model.py 架构：Whisper式编码器 + Qwen3-0.6B LLM 解码器，self.llm.generate()）
- "部署 qwen 最新 asr 配置够吗"（0.6B 够、1.7B FP16 5.1GB 需 INT8 量化 ~3.2GB）
- "MOSS 0.9B ASR 呢"（MOSS-Transcribe-Diarize 0.9B，2026-07 开源，原生时间戳+说话人分离+50+语言，长音频需 chunking）
- "会话留存，vidSage根目录 session-archives，摘要会话记录分别建文件夹"

关键决策与原因：
- 不用 WSL/Docker：funasr-server 在 Windows 原生可跑；该微服务只存在于开发机，服务器交付走云端 ASR（PRD 已定）；WSL 跨文件系统 9P I/O 慢
- 不手写 FastAPI 接口：FunASR v1.3+ 自带 funasr-server（OpenAI 兼容 /v1/audio/transcriptions、verbose_json 带 segments、/health、/docs）
- 默认模型 SenseVoice-Small：成熟、Windows 原生稳、234M 快（实测 RTF ~0.06-0.16）、6GB 显存无压力；新模型（Fun-ASR-Nano/Qwen3-ASR/MOSS）纳入对照实测而非直接替换默认
- 环境管理从 conda 切到 uv（用户选择）：uv 0.12.18（winget），Python 3.12.14 由 uv 管理，项目内 .venv + .python-version=3.12
- 缓存全迁 F 盘：MODELSCOPE_CACHE=F:\app_soft\ai-cache\modelscope、UV_CACHE_DIR=F:\app_soft\ai-cache\uv（用户环境变量已写入）；pip 旧缓存清理 2.9GB、uv 旧缓存清理 5GB
- 系统级 ffmpeg 装 F 盘（F:\app_soft\ffmpeg\bin，用户 PATH）：服务解码 AAC/MP3、Java 侧抽音轨共用；Docker 部署时需在镜像内另装
- 多语种路由设计：ASR 返回 language 字段 → 5 语种内走 sensevoice，长尾走 faster-whisper（PRD 预留）；翻译与 ASR 分离走 LLM 环节

已执行的关键命令与验证结果：
- Miniconda 静默安装 /D=F:\app_soft\miniconda3（conda 26.7.1，未进 PATH 后手动加了 condabin）
- conda create -n vidsage-asr python=3.12（后被 uv 方案取代，待删）
- uv venv（asr-service 目录）+ uv pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu124（torch 2.6.0+cu124）+ funasr fastapi uvicorn python-multipart（funasr 1.4.16，transformers 5.17.0 随装）
- CUDA 验证：torch.cuda.is_available()=True，RTX 3060 Laptop 6GB
- 启动：funasr-server --model sensevoice --device cuda --host 127.0.0.1 --port 8100
- 转写实测（sample.wav，官方测试音频）：{"language":"zh","text":"欢迎大家来体验达摩院推出的语音识别模型","segments":[{"start":0.0,"end":5.5,"text":...}]}；verbose_json 秒→毫秒 ×1000 映射 TranscriptionSegment
- m4a 测试通过（ffmpeg 解码链路）；发现 ffmpeg 兜底路径 duration/end=0.0 时间戳丢失 → Java 侧必须抽 16k wav 再上传
- fun-asr-nano（2.13GB）意外触发下载并在 Windows AutoModel 后端加载成功；vLLM 报 No module named 'vllm' 自动降级

涉及文件/目录（绝对路径）：
- F:\personal_project\vidSage\asr-service\.venv\（uv 虚拟环境，Python 3.12.14）
- F:\personal_project\vidSage\asr-service\.python-version（内容 3.12）
- F:\personal_project\vidSage\asr-service\sample.wav、sample.m4a（测试音频）
- F:\app_soft\miniconda3\（conda 本体，保留）；F:\app_soft\miniconda3\envs\vidsage-asr\（冗余 conda 环境，待删 3.5GB）
- F:\app_soft\ffmpeg\bin\（ffmpeg.exe/ffprobe.exe/ffplay.exe）
- F:\app_soft\ai-cache\modelscope\（模型缓存：iic--SenseVoiceSmall 936MB、FunAudioLLM--Fun-ASR-Nano-2512 2.13GB）
- F:\app_soft\ai-cache\uv\（uv 缓存，新指向）
- C:\Users\14683\.cache\modelscope（已迁空）、C:\Users\14683\AppData\Local\uv（已删 5GB）、C:\Users\14683\AppData\Local\pip\cache（已清 2.9GB）
- 用户 PATH 追加：F:\app_soft\miniconda3\condabin、F:\app_soft\ffmpeg\bin
- 用户环境变量：MODELSCOPE_CACHE、UV_CACHE_DIR
- 后端接口契约：backend\src\main\java\com\vidsage\asr\ASRProvider.java、TranscriptionResult.java、TranscriptionSegment.java（beginTimeMs/endTimeMs/text，毫秒）
- PRD：F:\personal_project\vidSage\video-knowledge-assistant.md

模型知识（2026-09-23 核实）：
- SenseVoice-Small：2024-07，阿里 FunAudioLLM，234M，zh/en/ja/ko/yue，GPU 170x 实时，无 LLM
- Fun-ASR-Nano-2512：2025-12，800M（0.7B 编码器+Qwen3 LLM 解码器），zh/en/ja+方言，原生时间戳，GPU ~17x
- Qwen3-ASR：2026-01-29 开源（0.6B/1.7B），52 语种，开源 SOTA；时间戳需另配 Qwen3-ForcedAligner-0.6B；vLLM 后端不支持 Windows；1.7B FP16 需 ~5.1GB，INT8 ~3.2GB
- MOSS-Transcribe-Diarize 0.9B：2026-07，复旦 OpenMOSS，Apache 2.0，Whisper 编码器+Qwen3-0.6B，原生时间戳+说话人分离+50+语言+90min 单次（长音频显存随长度涨，6GB 需 chunking）；transformers 5.x 可跑，SGLang day-0，有 GGUF 社区版
- FireRedASR（2025-01，8.3B LLM 版超显存）、GLM-ASR-Nano（1.5B 需 8GB+）、VibeVoice-ASR（需 24GB）均不适合本机

失败与未完成事项：
- [ ] conda 环境 vidsage-asr 待删除（用户已口头同意方向，等"执行"）
- [ ] Java 侧 LocalFunAsrProvider 未写（RestClient → POST /v1/audio/transcriptions，verbose_json segments ×1000）
- [ ] Qwen3-ASR-0.6B 未部署（transformers 后端，权重未下载）
- [ ] MOSS-Transcribe-Diarize 0.9B 未部署
- [ ] 三模型 bake-off 未做（用户那首 3 分钟的歌是现成测试素材；Qwen3-ASR 强项歌词，MOSS 强项多说话人）
- [ ] start-asr.bat 一键启动脚本未创建（用户未确认）
- [ ] Swagger UI /docs 页面资源走公共 CDN，曾出现加载卡顿（服务本身正常，非本地问题）
- [ ] Swagger 上传文件需手动选择、language 参数留空（占位符 "string" 会干扰语言检测）
- [ ] 用户选择 B 方式装 ffmpeg（F 盘手动）已执行完毕；服务器 Docker 镜像内需另装 ffmpeg


### Git Commits

(No commits - planning session)

### Testing

- [OK] torch.cuda.is_available() = True，识别到 RTX 3060 Laptop 6GB
- [OK] sample.wav 转写返回 language=zh 与完整句级时间戳，映射 TranscriptionSegment 正确
- [OK] m4a 走 ffmpeg 解码链路通过，但发现兜底路径会丢失 duration/end 时间戳
- [OK] fun-asr-nano 在 Windows AutoModel 后端加载成功（vLLM 缺失自动降级）

### Status

[OK] **Completed**

### Next Steps

- 写 Java 侧 LocalFunAsrProvider（RestClient POST /v1/audio/transcriptions）
- 部署 Qwen3-ASR-0.6B 与 MOSS-Transcribe-Diarize 0.9B，做三模型对比实测
- 删除冗余 conda 环境 vidsage-asr（约 3.5GB）
- 创建 start-asr.bat 一键启动脚本（待用户确认）


## Session 2: ASR 本地文件上传修复与手动测试
<!-- trellis-session: v=2 fp=e6a2a8d1319501f4 -->

**Date**: 2026-09-24
**Task**: ASR 本地文件上传修复与手动测试
**Branch**: `master`

### Summary

修复 DashScope 云端 ASR 本地文件链路：OSSUtils 上传成功但提交 filetrans 缺少 X-DashScope-OssResourceResolve: enable 头导致 REQUEST_INVALID_FILE_URL_VALUE；buildParam 对 oss:// 来源加头并补单测。实测 sample.wav/sample.m4a/aac 均可本地路径转写，m4a/aac 无需 ffmpeg 预抽 wav（该约束仅适用于本地 funasr-server）。文档同步至 docs/prd/local-model-deployment.md。

### Git Commits

| Hash | Message |
|------|---------|
| `8367b36` | feat: ASR 支持本地文件路径上传临时存储转写并修复 oss 解析头 |

### Status

[OK] **Completed**


## Session 3: 后端包结构改为技术分层
<!-- trellis-session: v=2 fp=502d5d72f73593a8 -->

**Date**: 2026-09-24
**Task**: 后端包结构改为技术分层
**Branch**: `refactor/tech-layering`

### Summary

把后端包结构从 package-by-feature 改为扁平技术分层（controller/service/model/provider）。9 个主文件 + 2 个测试文件搬包，git 全部识别为 rename；零逻辑改动——.java diff 只含 package/import 行，mvn test 16/16 绿，契约字段/sealed 层次/类名(ASRProvider 全大写)/配置键/REST 路径均未变。重写 directory-structure.md（新增顶层分包判据、model/domain/persistence 选择标准、持久化三层职责与可见性规则、不预建空目录硬规则），并修 provider-pattern/quality-guidelines/error-handling 三份 spec 的 13 行失效路径 + index.md 索引描述；同步 MVP-3 的 implement.jsonl 与 handoff.md 落点，避免子 agent 按旧约定建 com.vidsage.llm。两条贯穿本会话的纠正值得留意：(1) 我曾断言『禁用 repository』『Mapper 与 Repository 二选一』，均错误——MP 自己就用 CrudRepository；正确口径是三层共存、受限的是可见性（service/ 不许直接依赖 Mapper）。(2) 我写的 Gate A 检查命令 -v 模式漏了 diff 的 +/- 前缀，导致一条都滤不掉、检查静默失效。另：.codex/config.toml 是会话前就存在的非本任务改动（codex 沙箱放开网络访问），经用户确认未随本次提交带入。

### Git Commits

| Hash | Message |
|------|---------|
| `32a9ac5` | refactor: 后端包结构改为技术分层（controller/service/model/provider） |

### Status

[OK] **Completed**


## Session 4: service 层注解回归 @Service
<!-- trellis-session: v=2 fp=c91230b1aafaab97 -->

**Date**: 2026-09-24
**Task**: service 层注解回归 @Service
**Branch**: `refactor/service-annotation`

### Summary

同一会话的第二个任务（承接 09-24-tech-layering）。先把 refactor/tech-layering fast-forward 合入 master（master 前进到 48ae51c），再从 master 开 refactor/service-annotation 做本任务。改动：TranscriptionService 的 @Component → @Service（仅注解+import 两行，零行为改动，mvn test 16/16 绿）。规则由『所有 Spring 组件统一用 @Component』改为『按包选 stereotype』——controller/→@RestController、service/→@Service、provider/→@Component、persistence/→@Repository；DashScopeAsrProvider 保持 @Component 不是遗漏（它不在 service/ 包、不是应用服务）。spec 同步 5 处，并补上原规范缺失的理由（此前 provider-pattern.md 说『见 directory-structure.md』而后者只列规则无解释，是悬空引用）。两处如实标注：@Component 与 @Service 在 Spring 中行为完全等价（javap spring-context:7.0.9 核实，@Service 只是 @AliasFor 特化），故这是可读性选择而非功能差异；@Repository 仅为社区惯例，MyBatis-Plus 下是否触发异常转换未核实，禁止写成收益。另补切面定位提示：用包定位而非注解定位（注解定位漏标时静默失效且不报错）。踩坑记录：task.py create 在已切到特性分支后执行，会把 base_branch 戳成该特性分支本身，导致 archive 因『PR 不能指向自己的分支』被拒；需 task.py set-base-branch <task> master 修正，或在 master 上先建任务。

### Git Commits

| Hash | Message |
|------|---------|
| `bbd27b7` | refactor: service 层注解从 @Component 改为 @Service |

### Status

[OK] **Completed**


## Session 5: 后端接入 springdoc-openapi 接口文档
<!-- trellis-session: v=2 fp=dcf93692f8ae7ea0 -->

**Date**: 2026-09-24
**Task**: 后端接入 springdoc-openapi 接口文档
**Branch**: `dev`

### Summary

调研确认 springdoc-openapi 为 Spring Boot 4 下的主流接口文档方案（knife4j 尚未适配 Boot 4）；引入 springdoc-openapi-starter-webmvc-ui 3.1.1，零配置生成 OpenAPI 3 JSON 与 Swagger UI；16/16 测试全绿，运行时验收 /v3/api-docs 与 /swagger-ui/index.html 均通过；同步更新后端规范技术栈基线。特性分支 feature/springdoc-api-docs 已合入 dev。

### Git Commits

| Hash | Message |
|------|---------|
| `8155e31` | feat: 后端接入 springdoc-openapi 接口文档 |

### Status

[OK] **Completed**
