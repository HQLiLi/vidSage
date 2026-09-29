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


## Session 6: ASR 接口补 Swagger 中文注解文档
<!-- trellis-session: v=2 fp=d22a669edbf8ca8e -->

**Date**: 2026-09-24
**Task**: ASR 接口补 Swagger 中文注解文档
**Branch**: `dev`

### Summary

选定方案 A（Swagger 注解）为 AsrController 与 TranscriptionResult/TranscriptionSegment 补 @Tag/@Operation/@Schema/@ApiResponse 中文文档描述，零业务逻辑改动；16/16 测试全绿，运行时验收 api-docs 中文描述与 Swagger UI 200；规范补注解约定（HTTP 契约模型字段必须有 @Schema）。特性分支已合入 dev。

### Git Commits

| Hash | Message |
|------|---------|
| `45e9686` | feat: ASR 接口与模型补 Swagger 中文注解文档 |

### Status

[OK] **Completed**


## Session 7: 收尾门接入会话归档（规则 9）
<!-- trellis-session: v=2 fp=08904092e03d3fda -->

**Date**: 2026-09-24
**Task**: 收尾门接入会话归档（规则 9）
**Branch**: `dev`

### Summary

把 session-archive 会话归档接进 Trellis 收尾门：finish-work 新增 Step 5，固定输出到 session-archives/，每次必写 summary.md 且不重读 transcript；规则正本落 .trellis/spec/guides/git-workflow.md 规则 9，两处平台入口与 workflow.md 镜像同步。trellis-implement + trellis-check 双代理验证，镜像一致性与命令可用性均 PASS。特性分支已合入 dev，并同步发布至 master。

### Git Commits

| Hash | Message |
|------|---------|
| `df3878e` | docs(spec): 分支工作流新增规则 9 —— 归档后同一次执行内做会话归档 |
| `671ed95` | chore(task): session-archive-on-finish 任务记录 |

### Status

[OK] **Completed**


## Session 8: 归档目录对齐 Trellis traces 约定并给文件夹名加任务短名
<!-- trellis-session: v=2 fp=bebb41721334c8ff -->

**Date**: 2026-09-24
**Task**: 归档目录对齐 Trellis traces 约定并给文件夹名加任务短名
**Branch**: `dev`

### Summary

把会话归档根目录从仓库根的 session-archives/ 改到 .trellis/workspace/HeQiu/traces——正是 session-archive 脚本 find_trellis_traces_dir() 认的 Trellis 项目内候选，也与 AGENTS.md 中 workspace 存放 session traces 的说明一致。归档目录名新增任务短名（<时间戳>-<英文短名>），由脚本新增的 --slug 传入并做 ASCII 安全化；同时修好标题启发式，使它跳过命令展开与框架噪音、取用户真实首句。4 份旧归档连同 index.jsonl 迁移并重写绝对路径，3 份历史遗留的嵌套布局（summary/summary.md）全部平铺——其 meta.json/index 引用本就是断链。trellis-implement + trellis-check 双代理，检查抓出的 2 个缺陷已修并复验。特性分支已合入 dev。

**补记（2026-09-24，任务归档后）**：上面写的"检查抓出的 2 个缺陷"不完整。`/trellis:finish-work` 的 Step 5 实跑时暴露了第 3 个缺陷——`archive_session.py` 里 `info["slug"] = suffix or None` 写在 `main()` 中归档函数返回之后，而 `meta.json` 已在归档函数内部写完，该赋值是死代码，归档产物的 `meta.json` 不含 slug。已改为赋值后显式重写 `meta.json`，重跑验证 `slug = "session-archive-traces-slug"`。该修复改的是不在版本控制内的全局 skill（`C:\Users\14683\.agents\skills\session-archive`），完成于任务归档之后，不在任何提交内；完整经过见本会话归档 `traces/20260924-204921-session-archive-traces-slug/summary.md`。

### Git Commits

| Hash | Message |
|------|---------|
| `1335a1f` | docs(spec): 归档目录改到 .trellis/workspace/<dev>/traces，目录名带任务短名 |
| `4991a8e` | chore(task): session-archive-traces-slug 任务记录 |

### Status

[OK] **Completed**


## Session 9: MVP-2 路线敲定：yt-dlp 直调 + ffmpeg 抽音频（含外部进程实测）
<!-- trellis-session: v=2 fp=ccd7fd13040d3dc6 -->

**Date**: 2026-09-25
**Task**: MVP-2 路线敲定：yt-dlp 直调 + ffmpeg 抽音频（含外部进程实测）
**Branch**: `feature/gitignore-tools`

### Summary

从任务盘点开始，先决定 MVP-2 先行（目标是把视频到字幕跑通），再对 yt-dlp 的集成路线做了一轮深度调研与实测。关键结论：Java 侧只能 shell out 调 yt-dlp（无可用纯 Java 替代，NewPipeExtractor 官方不支持 B站），但不需要把 yt-dlp 挪到 Python 侧——那个方案唯一的支柱是 asr-service 常驻，而 ASR 走云端后不成立。最终定为两条输入路线收敛到同一个抽音频步骤：链接走 yt-dlp 下载，本地文件直接用，两者都由 Java 调 ffmpeg 抽音频。yt-dlp 已装为 standalone exe，全部所需 flag 与自更新能力均实测确认。

### Main Changes

- 任务盘点：确认 4 个 MVP 子任务状态；MVP-3 退回 planning（实测其无任何 LLM 代码，09-24 的前置成果已在 dev 上）
- 集成路线调研：VideoLingo / VideoCaptioner 均 import yt_dlp 当库用；Java 无法复用，且无可用纯 Java 替代库
- 否决方案 B（yt-dlp 放 Python 侧 + Java 发 HTTP）：前提「asr-service 必常驻」在云端 ASR 下不成立
- 装 yt-dlp standalone exe 到 tools/（2026.08.19，当前最新稳定版），逐 flag 实测核实
- 实测四个坑：输出编码 GBK、退出码语义极弱、yt-dlp 不接受本地文件、ffmpeg/ffprobe 是外部依赖
- 音频格式决策：本地 FunASR 用 wav，云端 DashScope 用 m4a（官方支持，体积小 4-8 倍）
- 新增 research/external-process-integration.md；epic design.md 增「输入路线与外部工具」一节；MVP-2 prd 范围细化
- .gitignore 加根锚定 /tools/（在 feature/gitignore-tools 分支上，尚未提交）

### Git Commits

(No commits - planning session)

### Testing

- [OK] yt-dlp --help 逐个核实所需 flag：-J/-j/--write-subs/--write-auto-subs/--sub-langs/--convert-subs/--cookies/--skip-download/--no-playlist/-P/--progress-template/--ignore-config/-U/--update-to
- [OK] yt-dlp -U 自更新实测通过（退出码 0，报告 stable@2026.08.19 为最新）
- [OK] 编码字节级验证：yt-dlp 输出中文为 d6 d0 ce c4（GBK）；PYTHONIOENCODING=utf-8 无效
- [OK] java -XshowSettings:properties 实测：file.encoding=UTF-8，native.encoding=GBK
- [OK] yt-dlp 传本地文件实测：ERROR not a valid URL，退出码 1
- [OK] 未跑 mvn test；未做端到端下载冒烟

### Status

[OK] **Completed**

### Next Steps

- git 收尾：.gitignore 改动待批准 commit；research / design / prd 改动待定提交路径
- MVP-2 现属复杂任务，task.py start 前需补 design.md 与 implement.md
- 待定：是否做 yt-dlp 端到端冒烟（-J 取元数据，零副作用）
- 未决：并发限流值、asr-service 是否保留、产物清理策略（本会话明确推迟）


## Session 10: MVP-2a 收尾并归档；HTTP 契约归位任务完成规划
<!-- trellis-session: v=2 fp=b74a387ede15b0ca -->

**Date**: 2026-09-26
**Task**: MVP-2a 收尾并归档；HTTP 契约归位任务完成规划
**Branch**: `dev`

### Summary

MVP-2a（输入获取与音频抽取）完成收尾：提交实现（4bd3574）与 Phase 3.3 规范回写（8a05a75）、以 --no-ff 合并回 dev（cc111b8）、并归档任务。同期完成新任务「HTTP 契约归位与包结构规范修订」的完整规划（prd/design/implement 三件套 + 两个 jsonl 清单），该任务尚未开工，基线为 cc111b8、target 为 dev。dev 领先 origin/dev 若干提交，未推送。

### Git Commits

| Hash | Message |
|------|---------|
| `4bd3574` | feat(mvp2a): 输入获取与音频抽取 |
| `8a05a75` | docs(spec): MVP-2a Phase 3.3 规范回写 |
| `cc111b8` | Merge branch 'feature/mvp2a-media-to-audio' into dev |

### Status

[OK] **Completed**


## Session 11: HTTP 契约归位与包结构规范修订（含一处顺带修复的校验顺序缺陷）
<!-- trellis-session: v=2 fp=90f9a0cf6584c641 -->

**Date**: 2026-09-26
**Task**: HTTP 契约归位与包结构规范修订（含一处顺带修复的校验顺序缺陷）
**Branch**: `dev`

### Summary

完成「HTTP 契约归位与包结构规范修订」（任务 09-26-http-contract-layout）。包结构：`controller/` → `web/controller/`（5 文件，纯改名）、三个嵌套 HTTP 契约 record 提升为 `web/dto/` 顶层 record（`PrepareAudioRequest` / `PreparedAudioResponse` / `TranscriptionRequest`，`@Schema` 一字未动）、`AudioSpec` 的 5 条字段校验由「服务层与抽取实现各写一遍」收敛进紧凑构造器（异常类型 IAE → ISE）、`MediaController` 补 `audioTrackIndex` 边界卫语句。规范侧：改写 `directory-structure.md`（含修复「按技术分层」与「不建按技术命名的顶层分包」的字面矛盾、写入 `model/` 两处例外、补契约归属判据 1）、`error-handling.md`（补「谁之过」判据）、`index.md`、`logging-guidelines.md`；另修正归档任务 mvp2a implement.md 的规范出处误引（经所有者批准，唯一跨任务边界改动）。验证：全量测试 14 类 / 164 用例全绿；运行时与基线 f210fe6 逐字节对照一致（三个端点 + /v3/api-docs，唯一差异是两实例端口不同）。**顺带修复（非本任务目标，design 未记载）**：`POST /api/media/audio` 原先对非法参数会先完整获取一遍媒体再拒绝——`requireValidSpec` 排在 acquire 阶段之后，故既不必要地下载，又在环境也坏时把参数错误掩盖成 500「未找到 yt-dlp」；卫语句前移到 HTTP 边界后，边界可判定的参数错误一律在零 I/O 下返回 400。已实测对照：基线 500 / 改后 400（同一环境、同一请求）。分支已 --no-ff 合并回 dev（03950db）并归档（d95bcbc）。dev 领先 origin/dev，未推送。

### Git Commits

| Hash | Message |
|------|---------|
| `045b89a` | refactor(web): controller/ 改名为 web/controller/ |
| `6f52b67` | refactor(web): 提取嵌套 HTTP 契约到 web/dto/ |
| `9878df3` | refactor(model): AudioSpec 校验移入紧凑构造器，异常改 ISE |
| `79d6e85` | fix(web): MediaController 补 audioTrackIndex 边界卫语句 |
| `affdd81` | docs(spec): 包结构与错误处理规范修订 |
| `c1e1f6a` | docs(spec): 补边界卫语句须早于 I/O 的实测依据 |
| `fceb609` | docs(task): 修正 mvp2a implement.md 的规范出处误引 |

### Status

[OK] **Completed**


## Session 12: MVP-2b 音频转字幕（ASR→SRT）实现+合并+归档
<!-- trellis-session: v=2 fp=61ec50c91df4f9c1 -->

**Date**: 2026-09-26
**Task**: MVP-2b 音频转字幕（ASR→SRT）实现+合并+归档
**Branch**: `dev`

### Summary

实现音频转字幕 MVP-2b：ASRProvider 加 requiredAudioSpec()/route() 自述方法，DashScopeAsrProvider 实现并以 HAVE_NO_WORDS 降级为空结果；新增 model/subtitle 三契约类、SubtitleService（编排+JSON检查点断点复用+SRT自渲染+原子写盘+进度映射）、POST /api/subtitles 临时端点。全量单测 16类/181全绿，6项真实运行+空转写端到端验证通过。已合并回 dev 并归档。

### Git Commits

| Hash | Message |
|------|---------|
| `45e852c` | feat(subtitle): 音频转字幕 MVP-2b——ASR 自述规格/路线 + SubtitleService + POST /api/subtitles |

### Status

[OK] **Completed**


## Session 13: 字幕清洗 v0 收尾整理与 LLM 层收口
<!-- trellis-session: v=2 fp=35082ba07b3e3a64 -->

**Date**: 2026-09-28
**Task**: 字幕清洗 v0 收尾整理与 LLM 层收口
**Branch**: `dev`

### Summary

v0 跑通后的整理轮：任务文档按实际落地重写（prd/design 重写为现状、implement 瘦身，废弃内容删除只记 Llm 接口缺陷一笔）；删四个死文件与 SubtitleContext.isEmpty；com.vidsage.llm 挪入 provider/llm（具体类不抽接口），新建 SubtitleContextSummarizer 收口摘要调用使 service 无 Spring AI 类型；拍板不建 LLMProvider 接口（Spring AI 已承担供应商缝，本地/云端均 OpenAI 兼容）并删 vidsage.llm.provider 预留键；用户手改后窗提示语「以下→以上」原样入库。全量 231 例绿，合并回 dev（e48a9a7）并推送。遗留：摘要落盘 R5、摘要输入截断、并发批处理、真实素材 A/B（history-max-chars 0 vs 20000）。

### Git Commits

| Hash | Message |
|------|---------|
| `ec7f3c7` | feat(subtitle): 跨批对话历史与双判据切批 |
| `f378a4a` | docs(task): 09-27-subtitle-context 任务文档按实际落地重写 |
| `1ee40b4` | refactor(subtitle): 删除被 SubtitleCleanser 取代的旧实现 |
| `fe3f768` | refactor(llm): SubtitleCleanser 挪入 provider/llm，摘要调用收口 |
| `d8d191b` | docs(llm): 确定不建 LLMProvider 接口，删 vidsage.llm.provider 预留键 |
| `b9731bd` | fix(prompt): 后窗提示语「以下后文」→「以上后文」，注释梳理 |
| `106e675` | docs(task): 09-27-subtitle-context 收尾清单销账 |

### Status

[OK] **Completed**


## Session 14: 字幕术语替换：跨书写系统替换打通，暴露摘要层知识盲区
<!-- trellis-session: v=2 fp=f66a467485af8af4 -->

**Date**: 2026-09-28
**Task**: 字幕术语替换：跨书写系统替换打通，暴露摘要层知识盲区
**Branch**: `dev`

### Summary

让 ASR 记成中文音译的专有名词在字幕正文里替换成原文（米特号→Mittelhau 等）。先经 /grilling 逐轮收敛出 10 条拍板决策（字幕显示原文、替换与否交模型按上下文判断、note 改语义为替换理由、校验层只豁免不强制、加可观测指标），再分两批实现：提示词（总结的 fixed/note 语义与外语原文规则、清洗的翻译禁令收窄+判定口径+正反例、用户模板渲染改中性并列）与代码（校验层基线豁免 max(原文,基线)、术语替换指标贯通 HTTP、摘要结果 DEBUG 观测日志）。真实视频复跑：跨书写系统替换 9 处全中（改动前为 0 处），但暴露摘要层知识盲区——帕姆 6 次复跑里 5 次被编成 Palm（应为 Pommel），提示词加「不许凭读音猜」后 3/3 仍错；指标全程绿灯，证明它测的是遵从度不是正确性。已记入 research/term-source-limitation.md，prd 验收按实测订正。测试 239 全绿。

### Git Commits

| Hash | Message |
|------|---------|
| `2649a61` | feat(prompt): 术语替换的判定口径与 fixed/note 语义 |
| `96d5556` | feat(prompt): 外语原文不得凭读音猜，补反例与判定原则 |
| `c70cfa4` | feat(subtitle): 校验层基线豁免、术语替换指标与摘要观测日志 |
| `0e02750` | docs(spec): 契约设计补守卫基线豁免与字幕术语表契约 |
| `56d0a9f` | docs(task): 09-28-subtitle-term-replacement 规划、验收订正与研究记录 |
| `6d757e8` | Merge branch 'feature/subtitle-term-replacement' into dev |

### Status

[OK] **Completed**


## Session 15: MVP-3 LLM 翻译与双语 SRT：规划重写、分步实现与端到端验收
<!-- trellis-session: v=2 fp=8bfbc29c2972cfd5 -->

**Date**: 2026-09-29
**Task**: MVP-3 LLM 翻译与双语 SRT：规划重写、分步实现与端到端验收
**Branch**: `dev`

### Summary

按现状重写 mvp3 规划（输入改 -clean 产物、不预留反思轮次、语种并入摘要落盘、翻译器独立不抽骨架），分 7 步实现并逐步实现+检查子代理把关：观测项（批次字数+校验失败分类+开发期 TRACE 详情日志）、上下文缓存与源语种（Optional 区分成功与降级，失败绝不落盘）、翻译契约与双语渲染（译文==原文单行）、翻译器本体（12/400 批、三路上下文、键集校验、用尽重试回退原文）、编排与 /api/subtitles/translate 端点（主语言子标签比较防 zh-CN 失配、卫语句早于 I/O）。实现期发现并修正 design 两处缺陷：hitTerms 判据在翻译输入上失准（拆 hitTermsByAsr/Fixed）、步骤 1 对批参数的推论算错（句数才是实际抢跑判据）。端到端四场景实测（跳过 0.087s/翻译 8.34s/断点复用 0.104s/长视频 319 段 41.4s，时间轴逐条对齐），302 用例全绿。发现术语保留规则无方向性（zh→en 净负面，对照实验取证），所有者拍板另立「术语官方译名查询」任务（前置：外语素材+搜索选型实测）。已 --no-ff 合并回 dev 并归档。

### Git Commits

| Hash | Message |
|------|---------|
| `15170ef` | docs(task): mvp3 规划按现状重写，删失效移交文档 |
| `c5f87e4` | feat(subtitle): 校验失败分类计数与开发期详情日志 |
| `76f4db6` | docs(spec): 日志规范补开发期详情日志的例外说明 |
| `a8aaad7` | docs(task): mvp3 步骤 1 实测读数与详情日志机制 |
| `702b754` | feat(subtitle): 上下文缓存与源语种（清洗与翻译共用） |
| `ba63ec2` | docs(spec): utils 参照清单同步，并立「同一判据只有一份」规则 |
| `69de54f` | docs(task): mvp3 步骤 2 记录与 design §3.3 订正 |
| `91852d9` | feat(subtitle): 翻译契约与双语 SRT 渲染 |
| `da9b84d` | docs(task): mvp3 步骤 3 记录，并订正步骤 1 对翻译批参数的推论 |
| `e56f395` | feat(subtitle): 翻译器本体（批处理 + 三路上下文 + 校验回退） |
| `39f64e2` | feat(subtitle): 翻译编排与 /api/subtitles/translate 端点 |
| `61292e8` | refactor(subtitle): target 改名 targetLanguage，对齐日志占位符 |
| `a83f28d` | docs(task): mvp3 步骤 6/7 收尾——验收证据、术语规则方向性缺陷与遗留登记 |

### Status

[OK] **Completed**


## Session 16: 术语官方译名联网查证：实现、重改与换代验收
<!-- trellis-session: v=2 fp=c47215525b57c765 -->

**Date**: 2026-09-29
**Task**: 术语官方译名联网查证：实现、重改与换代验收
**Branch**: `feature/subtitle-term-official-lookup`

### Summary

完成字幕术语官方译名联网查证功能并经两轮迭代定稿：首版（893a959）走 provider/termlookup 四件套 + SubtitleTermVerifier service + Term 三态字段；按项目所有者三条审查意见重改（eb0a559）——查证收进 provider/llm/termlookup（接口保留为换模型留缝）、编排内联翻译流程、查证产出独立类型 VerifiedTerm/VerifiedTerms 落独立产物 terms-<route>-<target>.json（context 永不改写）。查证模型换代 qwen3.7-flash（qwen-flash 别名停在 2025-07-28 快照），请求固定 enable_thinking=false（思考链致单条 20~45s 超时）与 temperature=0.1（防逐轮抖动）。真实验收：context 6+ 次运行零改写，只狼→Sekiro（官方），单条 0.6~2.7s，321 测试全绿。遗留：任务 09-29-subtitle-translate-qwen-mt（qwen-mt-flash 引擎切换）已立项待启动，消费 terms 产物渲染标注；子代理 token 成本观测记于任务 research/subagent-cost-observation.md

### Git Commits

| Hash | Message |
|------|---------|
| `893a959` | feat(subtitle): 术语官方译名联网查证——摘要后逐条搜索，写入 context 落盘复用 |
| `eb0a559` | refactor(subtitle): 术语查证按审查意见重改——归入 llm/termlookup、编排内联翻译流程、换代 qwen3.7-flash |

### Status

[OK] **Completed**
