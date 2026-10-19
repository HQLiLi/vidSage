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


## Session 17: 官方译名接入翻译提示词：deepseek 基线对照与双语种实测
<!-- trellis-session: v=2 fp=c9b27607a61ca8b6 -->

**Date**: 2026-09-30
**Task**: 官方译名接入翻译提示词：deepseek 基线对照与双语种实测
**Branch**: `dev`

### Summary

前置查证任务交付后、引擎切换开工前，项目所有者改道：先把查证好的官方译名按提示词路线接入现有翻译做基线（新分支基于查证分支顶端，qwen-mt 引擎切换暂缓为未来可选测试任务）。SubtitleTranslator 增 officialTerms 入口与 3 参等价重载，术语参考段按查证结论分流——有译名的渲染「词 → 译名」要求照翻、弃权词维持保留原样；service 读 terms-<route>-<target>.json（该产物首个消费方）传入；主翻译模型 mimo→deepseek。真实验证三场：只狼素材 zh→en 对照昨日产物，5/5 查证译名全部生效（只狼→Sekiro: Shadows Die Twice、弹刀→deflection、架势条→Stance 等），10/10 弃权词两侧一致保留原样，0 重试 0 回退 7.7s，译文中文残留 10→5 行；德剑素材 zh→en 冷启动全链路（摘要→查证→翻译 12.5s）复现已知盲区——摘要层把配重球固定为 Palm 且查证层循环确认，再次印证查证修不了上游（term-source-limitation.md 范畴）；zh→ja 一轮 4 术语全部弃权（英文维基不算日文译名，同词异语种不同结论，验证产物按语种分文件的设计），德语术语保留原文恰合 HEMA 惯例，体捌き/見せ場/パッ 等领域词地道，跨批边界语句衔接无缝。人工评审结论：双语种均无系统性机翻味。326 测试全绿；遗留：qwen-mt prd 留档三项已核实 API 事实（单轮无 System Message 定论、terms 无数量上限受 8192 token 约束、max_tokens 须显式设置）。

### Git Commits

| Hash | Message |
|------|---------|
| `97af795` | feat(subtitle): 术语官方译名接入翻译提示词——查证译名照翻、弃权维持保留原样 |
| `c6d49a7` | chore(config): 翻译主模型切回 deepseek（官方译名对照实验基线） |
| `981a80d` | Merge branch 'feature/subtitle-official-terms-translate' into dev |
| `3cdbd92` | docs(task): qwen-mt 降级为未来可选测试任务，三项待核实 API 事实已核实留档 |

### Status

[OK] **Completed**


## Session 18: MVP-1 异步任务管线落地 + 翻译按需触发拆分
<!-- trellis-session: v=2 fp=5df127e2a247492d -->

**Date**: 2026-10-02
**Task**: MVP-1 异步任务管线落地 + 翻译按需触发拆分
**Branch**: `dev`

### Summary

实现 MVP-1 总提交接口与异步管线（URL/上传双路线、MySQL 落库、状态机、虚拟线程编排、SSE 进度推送、订阅注册表、产物下载），收编三个旧同步端点；grill 定调翻译拆为按需触发（POST /api/tasks/{taskNo}/translate + translation_status 子状态）；441 测试全绿，BV11QMd6WEJp 两段式真实取证；真实运行抓修 SSE 断连订阅泄漏；spec 重建 database-guidelines 并修订 directory-structure；合并回 dev 并归档任务。

### Git Commits

| Hash | Message |
|------|---------|
| `3357a55` | feat(backend): MVP-1 异步任务管线与总提交接口 |
| `60c67db` | refactor(backend): 收编旧同步端点，业务入口收敛到 TaskController |
| `60cc039` | docs(spec): 数据库规范重建 + 目录结构修订 |
| `a508766` | docs(task): MVP-1 规划产物、取证文档与 SSE 抓流记录 |
| `c5c1444` | feat(backend): 翻译拆分为按需触发 |

### Status

[OK] **Completed**

---

## Session 19: 前端引入 GSAP 与 axios 基础设施及规范沉淀
<!-- trellis-session: v=2 fp=gsap-axios-infra -->

**Date**: 2026-10-03
**Task**: 前端引入 GSAP 与 axios 基础设施及规范沉淀
**Branch**: `dev`

### Summary

grill-me 三轮定案后落地前端基建：安装 gsap ^3.15 与 axios ^1.20；`src/lib/gsap.ts` 作全项目唯一 registerPlugin 入口（用户否掉 composables 方案、点名 lib/ 与 request.ts 命名）；`src/api/` 按同源相对路径建 axios 实例（无 baseURL、Vite 代理 /api→8080），types.ts 逐字段镜像后端裸 DTO（无 envelope、409 附既有任务视图），tasks/media 一域一文件覆盖全部非 SSE 端点；发现并 vendoring GreenSock 官方 agent skills 5 个（core/frameworks/performance/timeline/scrolltrigger）进 .agents/skills，与项目 spec 双轨分层（API 级归官方 skills，animation.md 只写项目决策，三类条款标注防冲突）；新增 animation.md 与 api.md 两份 spec，同步 index/directory-structure/type-safety；type-check+lint 全绿，dev 代理实测接管（/api→502=后端未启动，对照 /→200 SPA）；缓动基线 power2.out 用户拍板保留（讨论过改回官方默认，先不动）。

### Git Commits

| Hash | Message |
|------|---------|
| `502ef30` | feat(frontend): 引入 gsap 与 axios 基础设施及动画/API 规范 |
| （auto） | chore(task): archive 10-03-gsap-axios-infra |

### Status

[OK] **Completed**


## Session 20: 前端 v0 两态页面落地与审查期收敛
<!-- trellis-session: v=2 fp=9cd991384d821335 -->

**Date**: 2026-10-05
**Task**: 前端 v0 两态页面落地与审查期收敛
**Branch**: `dev`

### Summary

前端 v0 全程收口：0b14df0 按原型落地上传/工作区两态页面，接通提交（本地/链接/409 冲突跳转）、SSE 进度与终态、按需翻译与产物下载契约；随后审查期 14 批改动收敛——baseURL 收敛进 request（frontend/.env 注入 VITE_API_BASE_URL=/api，非 axios 出口保留全路径双拼）、ApiError 拦截器归一错误（status/message/existingTask）、成功响应统一解包 + 类型化门面 {get,post}（门面边界受控 as 为 no-as 唯一例外）；纯函数入 utils/transcript、fetchArtifactJson 归 api 层；枚举值迁 enums/task.ts（as const 键镜像后端，types/ 经 import type+typeof 保持纯类型）；HeroWave/AudioWaveform 装饰波形抽页面私有组件，MediaPane 按同名文件夹规则提升（index.vue+components/），AppHeader 抽跨页共享组件统一三处 header（bg-surface、px-4 sm:px-6 口径取设计令牌注释意图）；修 workspace 入场动画滚动条闪烁（h-screen 固定壳上 y 位移瞬时溢出，就绪态 main overflow-clip，Playwright rAF 采样 665 帧实测溢出与滚动条占宽恒 0）；type-safety.md 增补 TypeScript 类型规范 8 条、animation.md 增补固定视口壳入场位移约定；三命令全绿，Range 206 已实证。遗留 P2：终态谓词两处编码统一、SSE 重连 404 静默、进度条 SCSS 两页重复。

### Git Commits

| Hash | Message |
|------|---------|
| `0b14df0` | feat(frontend): 按原型落地两态页面，接通提交/SSE/翻译/产物契约 |
| `9ba3868` | refactor(frontend): 审查期修复与架构收敛（baseURL/ApiError/解包门面、enums·utils 分层、组件抽取与 AppHeader 共享、入场滚动条修复） |

### Status

[OK] **Completed**


## Session 21: 派发工作流提速协议落地（10-05-workflow-dispatch-tuning）
<!-- trellis-session: v=2 fp=a8663edd3dc1313f -->

**Date**: 2026-10-05
**Task**: 派发工作流提速协议落地（10-05-workflow-dispatch-tuning）
**Branch**: `dev`

### Summary

按 10-03 实测基线落地派发协议调优：正典 dispatch-protocol.md（并行默认/检查拆面/减轮数/档位判据/worktree 仪式/台账/工具适配层）+ workflow.md 与两个 SKILL.md 英文镜像（SYNC MARKER）+ tools/trellis-worktree.sh 幂等脚本 + .codex/agents/trellis-mechanic.toml。三检查面并行核对 AC1–AC7 全过，修复 2P0+2P1+4P2；ZCode 不热加载新 agent 类型，mechanic 按 §7 降级全高档，新会话复测（台账 research/execution-log.md）。

### Git Commits

| Hash | Message |
|------|---------|
| `f7459d2` | docs(trellis): 派发协议并行化落地（10-05-workflow-dispatch-tuning） |

### Status

[OK] **Completed**


## Session 22: DDD 四层化重构：机械迁移 + 值对象 + 角色子包 + spec 重写
<!-- trellis-session: v=2 fp=7ffe6506aad1cb03 -->

**Date**: 2026-10-06
**Task**: DDD 四层化重构：机械迁移 + 值对象 + 角色子包 + spec 重写
**Branch**: `feature/ddd-four-layer`

### Summary

完成 10-05-ddd-four-layer-refactor 全部四个 Phase：Phase 1 机械迁移（111 git mv，四层结构落地，零逻辑变更）；Phase 2 值对象 TaskNo/WorkspaceKey（domain/shared 共享内核）+ 17 个 package-info；Phase 3 backend spec 七文档重写（豁免清单正典落 directory-structure.md）；Phase 4 application/task 角色子包（query/exception/subscribe，所有者选定范围）。每批三读面质检 + mvn clean test 441 全绿 + 旧 FQN grep 零残留；13+ 次子代理派发按派发协议并行/串行切批，台账在任务 research/execution-log.md。归档前按所有者要求补记「DDD 规则与现行规范冲突记录」（design.md §8 六项定案，含读模型落位与 @Schema 保留的证据核验）。执行期异常：一次来源不可归因的 TaskPipelineService 无用 import 删除（疑似 research 子代理越权写），已还原到已验证提交态。遗留：feature/ddd-four-layer 四 commit 待所有者审阅合并 dev；新任务 10-06-domain-logic-sink（业务逻辑下沉）规划完成待批准实施。

### Git Commits

| Hash | Message |
|------|---------|
| `43e852c` | refactor(backend): DDD 四层化 Phase 1——机械迁移（纯移动+import 重写） |
| `de004d9` | refactor(backend): DDD 四层化 Phase 2——值对象增量与层 package-info |
| `4327563` | docs(spec): DDD 四层化 Phase 3——backend spec 重写与旧路径清零 |
| `e97f2a6` | refactor(backend): DDD 四层化 Phase 4——application/task 角色子包 |

### Status

[OK] **Completed**


## Session 23: workflow-state 解析修复与面包屑瘦身
<!-- trellis-session: v=2 fp=d60a46d139627c25 -->

**Date**: 2026-10-06
**Task**: workflow-state 解析修复与面包屑瘦身
**Branch**: `feature/domain-logic-sink`

### Summary

诊断并根修 trellis 面包屑故障：workflow.md:149 行尾裸标签 × 未锚定正则 = 幻影匹配吞掉 5/8 状态块（每轮降级文案）。两份 hook _TAG_RE 行首锚定；in_progress 3378B→594B、in_progress-inline 2689B→522B（中文硬门+指针）；新建 spec/guides/trellis-update-patches.md 补丁台账（含 trellis update 后复查项与编辑纪律）。

### Git Commits

| Hash | Message |
|------|---------|
| `ff39880` | fix(trellis): workflow-state 解析正则行首锚定，in_progress 面包屑瘦身（3378B→594B） |

### Status

[OK] **Completed**


## Session 24: 业务逻辑下沉 domain：四批重构 + 读模型拆分
<!-- trellis-session: v=2 fp=38df5fd512dac1f1 -->

**Date**: 2026-10-06
**Task**: 业务逻辑下沉 domain：四批重构 + 读模型拆分
**Branch**: `feature/domain-logic-sink`

### Summary

执行任务 10-06-domain-logic-sink：application 业务规则下沉 domain，service 瘦身为纯编排。Phase A（efe80ae）SourceUri/WorkspaceDir 值对象 + WorkspaceKey.ofHash 单源 key 宽度；Phase B（7afe71f）Bcp47Language/TermVerification/CheckpointPolicy 三政策件，检查点判据四服务合流，STAGE_TRANSLATE 归位 StageEvent；Phase C（48dabe7）VideoTask.isEventSettled/ACTIVE_STATUSES 谓词单源、TaskArtifactScan 产物可见性、MediaInput.ofTaskInput、AudioArtifact.fileName 指纹命名 + spec 同步；Phase D（6743a3e）读模型拆分 TaskSnapshot（application 零注解）/TaskResponse+TaskArtifactResponse（web/dto @Schema），4 端点与 409 载荷切换。每批 trellis-implement 实现 + trellis-check 独立核查全 PASS；mvn clean test 465 全绿（基线 441）；三零改动服务 diff 为空；对外 JSON/SSE 零变化，前端 types/task.ts 零改动。测试净增 24 条，断言随迁不改值。

### Git Commits

| Hash | Message |
|------|---------|
| `efe80ae` | refactor(backend): 业务下沉 Phase A——Workspace 寻址家族归 domain/shared |
| `7afe71f` | refactor(backend): 业务下沉 Phase B——字幕语种/术语/检查点政策归 domain/subtitle |
| `48dabe7` | refactor(backend): 业务下沉 Phase C——零散谓词与工厂单源化 + spec 同步 |
| `6743a3e` | refactor(backend): 读模型拆分 Phase D——TaskView 拆为 TaskSnapshot/TaskResponse |

### Status

[OK] **Completed**


## Session 25: application 出站依赖倒置：端口归 domain/application，infrastructure 实现
<!-- trellis-session: v=2 fp=400dab6fe1fefb72 -->

**Date**: 2026-10-06
**Task**: application 出站依赖倒置：端口归 domain/application，infrastructure 实现
**Branch**: `dev`

### Summary

消除 application 与 web 对 infrastructure 的 5 处反向依赖与 web→domain 的 4 处引用：新增 5 个端口（VideoTaskRepository、SubtitleCleanser/Translator/ContextSummarizer 落 domain；ToolDiagnostics 落 application）、8 个新类型，5 个实现类改名（Mybatis*/Llm*/Process*），删除 ToolDiagnosticsService 与 ExternalToolReport，豁免清单 a/b 清零；同步重写 directory-structure.md 依赖规则为「窄口+白名单+端口归属判据」。行为等价，465 测试全绿。审查期另立命名任务 10-06-naming-alignment。

### Git Commits

| Hash | Message |
|------|---------|
| `62e9b47` | refactor(backend): 出站依赖倒置——端口归 domain/application，infrastructure 实现；web 去 domain 化 |
| `9bb8cae` | docs(spec): 对账端口倒置后的依赖规则、端口归属判据与包说明 |

### Status

[OK] **Completed**


## Session 26: 命名与构件语义整理：Task 前缀对齐、管线方法与阶段类改名
<!-- trellis-session: v=2 fp=bd9f56cc667eb592 -->

**Date**: 2026-10-06
**Task**: 命名与构件语义整理：Task 前缀对齐、管线方法与阶段类改名
**Branch**: `dev`

### Summary

执行 10-06-naming-alignment：把端口倒置审查期暴露的三类命名问题一次收口。类名——TaskService→VideoTaskService、TaskPipelineService→VideoTaskPipeline（前缀对齐聚合根 VideoTask），六个名不副实的服务去 Service 后缀改 XxxStage（SubtitlePreparationStage / SubtitleCleanupStage / SubtitleTranslationStage / AudioPreparationStage / MediaAcquisitionStage / TranscriptionStage）；方法——run→runPipeline、runStages→prepareAndClean、runTranslation→runTranslationPipeline（execute / translateTask 保持）；七个管线构件 stereotype @Service→@Component，依赖字段跟改（含 videoTaskService 的 pipelineService→pipeline）。spec 新增「构件说明：服务与管线构件」，把判据从「名字里有没有 Service」收敛为「有没有 web 调用面」，并写进服务前缀对齐聚合根、管线构件后缀规则；删掉已 100% 执行完、且 §4-2/§4-3/§7-6 三处结论被端口倒置推翻的 docs/ddd-refactor-handoff.md。零行为变化：mvn clean test 45 类 / 465 用例 / 0 failures，与开工基线逐项一致。审查期 trellis-check 独立复核抓出一个真缺陷——R1 三个方法改名整体漏做（AC1 门禁 grep 因类内非限定调用而恒真通过），已补齐并把门禁改为「正向断言新名必须命中」写回任务产物。

### Git Commits

| Hash | Message |
|------|---------|
| `7b9db46` | refactor(backend): 命名对齐——服务前缀对齐聚合根、管线去 Service 后缀、六个阶段类改 XxxStage |
| `bcb1629` | docs(spec): 对账管线构件与聚合根前缀命名，删除已执行完的 DDD 移交文档 |

### Status

[OK] **Completed**


## Session 27: 包结构整理归档 + 架构「形状」设计讨论
<!-- trellis-session: v=2 fp=965d1f8e09698d21 -->

**Date**: 2026-10-07
**Task**: 包结构整理归档 + 架构「形状」设计讨论
**Branch**: `dev`

### Summary

执行 10-06-package-structure 收尾（提交 / 合并 dev / 推送远端 / 归档），收尾前完成一轮架构「形状」设计讨论。收尾：138 个改动分三个提交（代码 / spec / 任务工件）——domain 与 application 各上下文包按角色轴拆桶（port / policy / model），67 个类改名移动，测试包镜像跟随；唯一非纯移动的改动是 R7 下沉（VideoTaskService 两个包级私有 static → domain/task/policy/TaskStageEvent，判据逐字搬）。验证用 mvn clean test（必须 clean：动了 67 个文件，target/classes 会留陈旧 class）→ 45 类 / 465 用例 / 0 失败 / 1 跳过。审查期抓到两处规范与代码不符：① TaskProgressMessage 的 @Schema 被用户自行删除——理由是该注解是死的（SSE 端点返回 SseEmitter，本类型只作 TaskSseHub.send 入参流转，进不了 /v3/api-docs），已同步改 directory-structure.md 判据 2 清单与该行；② TranscriptionResult 同属「@Schema 是死的」但仍在代码里，属未决口径，本轮未动。设计讨论（用户主导，结论已定、任务尚未建）：用户自陈「只有 DDD 的形，内在还是乱——读得懂但加新功能不知道从哪下手」。诊断结论：核心概念只有具体实例、没有统一表示——阶段（六个阶段 = 六个类 + 三份名字常量 + 三层进度权重区间）、产出物（命名 / 检查点 / 可见性分散在 SubtitleArtifactNames / CheckpointPolicy / TaskArtifactScan 三处）、输入身份（提交时按 URL 串或内容 sha256 算，LocalPath 在阶段里按「路径 + 大小 + mtime」重算，代码造了 MediaInput.InWorkspace 变体绕行——该类型自己的注释写「只承载位置」却被自己破掉）、生命周期（主状态机 + 翻译子状态机各硬编码一台）。用户要的是「类似公式的架构」：变更类型 → 落点清单，可机械代入。四条形状决策：(1) 阶段成为类型——要；(2) 产出物成为类型——要；(3) 输入身份收敛成一套、身份作为输入自身属性、消除 normalize() 三路 switch——要；(4) 生命周期统一——不做（只有一个样本，分不清「生命周期是任务的」还是「产物自己的」两种候选形状，造错抽象比没有抽象更糟）。另查明 SubtitleCleanser.clean(...) 的 reference 参数恒传空串——为「术语表喂清洗」预留但从未接上的死参数（术语查证后来挪去翻译侧）。CQRS 是讨论中的岔路并已排除：Fowler 明确警示谨慎使用，本项目读写负载无失衡，两条触发条件与嗅觉测试均不满足。

### Git Commits

| Hash | Message |
|------|---------|
| `35c7a83` | refactor(backend): 包结构整理——平铺包按角色拆分子包 |
| `3e13c54` | docs(spec): 二级包按角色分桶的判据改写与路径引用对账 |
| `866e647` | chore(task): 记录 10-06-package-structure 规划工件 |

### Status

[OK] **Completed**

### Next Steps

- 建形状重构任务：把四条决策写成 PRD 验收标准 + 形状设计，变更类型 → 落点清单作为产出；执行者用户本人，非 sub-agent


## Session 28: 命名判据重立 + 管线与阶段构件改名（*Stage → *Coordinator）
<!-- trellis-session: v=2 fp=ceea7e765170947d -->

**Date**: 2026-10-07
**Task**: 命名判据重立 + 管线与阶段构件改名（*Stage → *Coordinator）
**Branch**: `feature/shape-refactor`

### Summary

从一次长命名讨论收口成一个纯改名任务并落地。判据不是先立规则再改代码，是从数据里 grep 出来的：*er/*or 留给做事的人（domain 端口已占满 SubtitleCleanser/SubtitleTranslator/MediaAcquirer），*Coordinator 给认领进度阶段的那一步，管线所有者按产出命名。VideoTaskPipeline → SubtitlePipeline（Video 是错的：音频是一等输入走同一条管线），三个 *Stage → *Coordinator。验证 mvn clean test → 45 类 / 469 用例 / 0 失败 / 0 错误 / 1 跳过，与基线一字不差。顺带：补回 Bcp47Language 丢掉的调用点约束、关闭 SubtitleArtifactSpec 那笔账、把与实际不符的 spec 命名判据作废并重立。任务 10-07-pipeline-stage-rename 已归档并 --no-ff 合回 feature/shape-refactor。

### Git Commits

| Hash | Message |
|------|---------|
| `399126f` | docs(task): 10-07 批次 2 定案——只做阶段名单源，权重移出 |
| `1af5f25` | docs(backend): 补回 resolveTargetLanguage 的调用点约束 |
| `e144df5` | docs(spec): 管线构件后缀判据暂停——与实际不符 |
| `f899d4b` | chore(task): 10-07-pipeline-stage-rename 规划工件 |
| `27c8b65` | chore(task): 记录 10-07-pipeline-stage-rename 的分支元数据 |
| `5fe9742` | docs(task): 10-07 会话移交刷新 |
| `5c09fe8` | chore(task): 10-07-pipeline-stage-rename 置为 in_progress |
| `fcf0750` | refactor(backend): 管线与阶段构件改名——管线按产出命名，阶段构件按角色命名 |
| `dc2aced` | docs(spec,task): 管线构件命名判据重立 + 任务工件 |
| `ef571a5` | merge: 管线与阶段构件改名（10-07-pipeline-stage-rename） |

### Status

[OK] **Completed**

### Next Steps

- 10-07-shape-refactor 批次 2（阶段名单源）仍未开工——注意改名已使该批次的逐行清单失效，需重扫。注释偏多的问题用户要求后面统一处理。


## Session 29: 形状重构收尾：批次 2/3 完成验收、合并推送并归档
<!-- trellis-session: v=2 fp=30274c9e241699e7 -->

**Date**: 2026-10-08
**Task**: 形状重构收尾：批次 2/3 完成验收、合并推送并归档
**Branch**: `dev`

### Summary

批次 2：六阶段名收进 SubtitleStageName 枚举并改「动词-对象」值，注释/文案复述清零，失败文案去阶段名；SSE 事件流 before/after 逐条零差异（除四条改名映射）。批次 3：MediaInput.getContentKey 多态 + MediaFileInfo 快照 + FileStateReader 端口（infrastructure/fs 新开桶），WorkspaceService 去 switch/normalize，双参 workspaceFor 保留（提交时刻无 MediaInput 的鸡生蛋洞）；四路线工作区目录名 before/after 逐字一致 + 断点复用佐证。AC1–AC7 对账打勾（三句过严表述按删除线+日期修正），design §五落点清单改终稿，spec 加 fs 分层行。473 用例全绿。合并 dev（4e5862a）并推送，任务归档。

### Git Commits

| Hash | Message |
|------|---------|
| `1fb308c` | refactor(backend): 批次 3 身份归属——MediaInput 自算身份，stat 采样经端口 |
| `f7bdcfb` | docs(spec,task): 批次 3 工件对账 + spec 加 infrastructure/fs 分层行 |

### Status

[OK] **Completed**


## Session 30: 进度权重扁平化执行与验收（AC1-AC9 全绿）
<!-- trellis-session: v=2 fp=3b75e1c41f235f74 -->

**Date**: 2026-10-08
**Task**: 进度权重扁平化执行与验收（AC1-AC9 全绿）
**Branch**: `dev`

### Summary

执行 10-08-progress-weight-flattening 步骤 0-8：SubtitleStageName 加 weight/inMainPipeline、区间按声明顺序累加；换算收敛到 clean 批级一处（ProgressMapper 保留唯一用途）；写字幕开始 70→66（R8 唯一有意变更）；三个 *Stage 改名落地。分工：用户写 main、AI 写测试并代改了 listener 接线三处。476 测试全绿；AC7 双实例（dff027b 对照 18081 vs 分支 18080）逐格比对 14 格恰差第 7 格，证据在任务 evidence/。已按批准合并 dev 并推送（merge 7cdcb2c）、删特性分支。按用户指示仅记 journal，任务未归档（task.py archive 跳过，留待后议）。

### Git Commits

| Hash | Message |
|------|---------|
| `f7a7f96` | refactor(backend): 进度权重扁平化——阶段份额单源 / 换算只剩 clean 一处 / 三个 *Stage 改名（10-08-progress-weight-flattening） |
| `d36b2f8` | docs(spec,task): 命名判据收口 + 归档任务落点订正 + 权重扁平化工件与 AC7 证据（10-08） |

### Status

[OK] **Completed**


## Session 31: 执行模型拆分：TaskConcurrencyGate 端口 + 行为逐格对照零差异
<!-- trellis-session: v=2 fp=25ba26a25565600d -->

**Date**: 2026-10-08
**Task**: 执行模型拆分：TaskConcurrencyGate 端口 + 行为逐格对照零差异
**Branch**: `dev`

### Summary

把 SubtitlePipeline 的 executor/semaphore/排队语义拆成 application 端口 TaskConcurrencyGate 与 infrastructure/concurrency 实现；两条排队中断语义不合并、翻译成本日志「含排队时间」口径原样保留。全量 482 用例绿（1 skip 为既存），闸门命门用例做过变异验证（错放许可 0.011s 即红）；真实链路 SSE 14 格与 dev 基线逐字节零差异，R3 差异栏由两格补齐为四格。规范侧：infrastructure 范围按「有没有状态」划线、六处失实订正（含 application.yml 注释）、注释「写与删」口径写进 quality-guidelines。收尾：合并回 dev 0d388d4、删功能分支、归档任务，记录仅 journal。

### Git Commits

| Hash | Message |
|------|---------|
| `95a8886` | docs(spec): 分层判据按「有没有状态」划线 + infrastructure/concurrency 落位（10-08-pipeline-executor-split） |
| `ef39a14` | refactor(pipeline): 执行模型拆出端口 TaskConcurrencyGate + 信号量实现（10-08-pipeline-executor-split） |
| `efdbf81` | test(pipeline,gate): 排队中断两条分支 + 闸门两条命门 + 校验文案逐字（10-08-pipeline-executor-split） |
| `4ba9b09` | docs(spec,infra): 失实订正六处（配额/虚拟线程归属）+ 注释「写与删」口径（10-08-pipeline-executor-split） |
| `133dcdc` | chore(task): 10-08-pipeline-executor-split 工件与 AC4 对照证据入库 |
| `38ea58e` | docs(workspace): 执行模型拆分移交文档重写为现状 |
| `0d388d4` | merge: SubtitlePipeline 执行模型拆分——端口 TaskConcurrencyGate + 信号量实现 / 行为逐格不变（10-08-pipeline-executor-split） |

### Status

[OK] **Completed**


## Session 32: WorkspaceService 端口倒置：代码审查、拼写订正、测试重分与收尾归档
<!-- trellis-session: v=2 fp=52c88b7fe0dc764b -->

**Date**: 2026-10-10
**Task**: WorkspaceService 端口倒置：代码审查、拼写订正、测试重分与收尾归档
**Branch**: `dev`

### Summary

审查项目所有者写的批次 1-3 主源码（拼写 WorkSpace→Workspace、ensureWorkspaceDirectory 等按用户动词开头口径定名）；批次 4 测试重分 WorkspaceServiceTest 9 例拆 4+5、6 夹具换构造，482 绿与基线持平；批次 5 spec 三档化与 evidence 落盘；合并 dev 推送删分支，任务归档。

### Git Commits

| Hash | Message |
|------|---------|
| `18938a4` | refactor(media): WorkspaceService 端口倒置——工作区定位与创建移出应用层 |
| `4f3c43e` | chore(task): 10-09-workspace-port-inversion 任务卡与证据入库 |

### Status

[OK] **Completed**


## Session 33: 10-08 广播收口全链路收尾：批 0 判据到合并归档
<!-- trellis-session: v=2 fp=89a014b68de4e10c -->

**Date**: 2026-10-11
**Task**: 10-08 广播收口全链路收尾：批 0 判据到合并归档
**Branch**: `dev`

### Summary

读交接后 task.py start 并开 feature 分支。批 0 spec 判据 5 处（根包构成收窄+命名登记）；步骤 0 基线 482 全绿+SSE 参照侧 14 格与归档基线零差异、场景 B 三快照；批 3 主源码审查保真 10/10（方法名以代码为准同步工件）；批 4 派发 488 全绿（+6 新用例）+check 三读面 PASS 断言红线干净；批 5 spec §七 #5-9、AC2/AC3 grep 证据（豁免 17 处全列）、AC5 双侧逐字节对照（含翻译增量补抓）；AC1-AC7 全 PASS。两笔提交合并 dev 推送（1fbf095）、任务归档（41a8c8f）。过程事故：5a 子代理 402（文件已写完主线验收）、三次 SSE 抓取竞态（均修正）、target 并发竞态（清后重跑）。

### Git Commits

| Hash | Message |
|------|---------|
| `901449e` | chore(task): 10-08-pipeline-state-broadcast 任务卡与证据入库 |
| `53f9377` | refactor(task): SubtitlePipeline 状态迁移×广播收口——新增 TaskProgressNotifier、消息迁 message/ 桶 |
| `1fbf095` | Merge branch 'feature/pipeline-state-broadcast' into dev |

### Status

[OK] **Completed**


## Session 34: 10-11 SSE 面收口全链路收尾：批 0 判据到合并归档
<!-- trellis-session: v=2 fp=6ead9e4a9dcf8867 -->

**Date**: 2026-10-11
**Task**: 10-11 SSE 面收口全链路收尾：批 0 判据到合并归档
**Branch**: `dev`

### Summary

读交接后切 feature 分支 + task.py start。批 0 spec 判据 4 处（web/sse 职责补订阅配对、isEventTerminal 登记、-Manager 第二例、terminal 派生字段说明）；步骤 0 基线 488 全绿 + 参照侧 SSE 14 格与 10-08 归档基线逐格零差异、快照落档；批 1 后端所有者写（open() 把 register+subscribe 配对收成单一入口、remove() 空桶回收、TaskProgressMessage 增 terminal 派生字段、TaskSseHub 更名 TaskSseConnectionManager）；批 2 前端派发实现（两处重复终态判据删除改读字段、断连致命/瞬时分流 + 重连提示条）；批 3 测试 497 全绿（+9 用例，另补三处清单外构造点改走 of()）；批 4 AC4/AC5/AC6/AC7 证据（AC7 修后重取：14 格逐格相同、唯一差异是新 terminal 字段、快照逐字节一致）。两轮 trellis-check 三+二读面 + 一轮窄复检，共修 6 处缺陷（remove 原子性与 open catch 过宽经二次迭代才关死）。AC1-AC5/AC7 PASS，AC6 部分（diagnoseFatal 的 loadError 无渲染出口，登记不改）。写失败不退订另立 10-11-sse-connection-pairing。提交 edcbe81、合并 dev b1ffa1d 推送（推送需走本机 7897 代理，git 未配）。

### Git Commits

| Hash | Message |
|------|---------|
| `edcbe81` | refactor(task): SSE 面收口——订阅配对内聚、终态判据出 terminal 字段、断连可见 |
| `b1ffa1d` | Merge branch 'feature/sse-surface-consolidation' into dev |

### Status

[OK] **Completed**


## Session 35: 10-11 进度载荷命名收口：StageEvent → StageProgress 改名、独立核查、合并归档
<!-- trellis-session: v=2 fp=f158a2dbe0546b4b -->

**Date**: 2026-10-11
**Task**: 10-11 进度载荷命名收口：StageEvent → StageProgress 改名、独立核查、合并归档
**Branch**: `dev`

### Summary

执行 10-11-stage-progress-rename：把管线进度载荷归队到本仓 Progress 词族，零行为变更、对 SSE 契约零影响（类型名不进 JSON，payload 键是 stage）。三组改名——StageEvent → StageProgress（含文件改名 domain/shared/model/）、嵌套枚举 StageEvent.StageState → StageProgress.State、回调 ProgressListener.onEvent → onProgress；另按所有者裁定把主代码里指向该载荷的局部变量/参数 event 改成 stageProgress（对齐仓库既有的 VideoTask videoTask 惯例），测试内变量名刻意不动。范围 32 文件；HEAD↔改动后逐模式计数一一对应（StageEvent 156→0、StageState 90→0、onEvent 25→0，新名同数），TaskStageEvent 政策类按裁定暂留（16 处自引用未变）、TaskProgressMessage event 两个参数按判据未动。验证：mvn clean test 498 用例 / 0 失败 / 0 错误 / 1 跳过（基线经核定，journal 旧记的 497 是收尾中途数），前端 npm run type-check 零错误；另设一条门 G5——把新旧两版都归一化掉那三组词后 diff，仅 5 个手工改过的文件有残差，其余 27 个文件的差异恰好只有替换本身，证明只有改名没有夹带。执行期抓到并修正两处：(1) 我漏做 StageProgress.java 的过渡态 Javadoc 订正，被 G5 跑出来；(2) 我写的 Javadoc 声称「transcription、subtitle、task 各自的阶段都上报它」失实——transcription 上下文对 ProgressListener 引用为 0、Transcriber 不接 listener，转写阶段进度实由字幕侧 SubtitlePreparationCoordinator 发出，已改为 media 与 subtitle 两条线；并经所有者裁定把同源失实的 spec directory-structure.md:67「task / transcription / subtitle 三处共用」一并订正为「media / subtitle / task」。trellis-check 独立复核 6 项中 1–4、6 PASS，第 5 项由上述修正闭环；它未自行改码、只报问题并说明不擅改的理由，判断准确。收尾：4 个提交（代码 / spec / 任务工件 / .gitignore 清理），--no-ff 合回 dev 并推送、本地与远端 feature 分支均删。随任务删掉重构期对照 worktree backend-before-reconstruction（detached HEAD e6313b4 在 dev 上可达、无独有内容丢失）及其 .gitignore 条目。已知小瑕疵：StageEvent.java → StageProgress.java 因 Javadoc 扩写使相似度降至约 45%、卡在 git 默认 50% 门槛下，提交里记成 delete+create 而非 rename（40% 阈值下可识别）。

### Git Commits

| Hash | Message |
|------|---------|
| `b994d31` | refactor(domain): 进度载荷命名收口——StageEvent → StageProgress |
| `062b11f` | docs(spec): 目录结构规范的进度词汇同步为 StageProgress |
| `d8b7ca7` | docs(task): 补齐 stage-progress-rename 的执行清单与核查上下文 |
| `dba6091` | chore: 移除 backend-before-reconstruction 忽略条目 |
| `5d453fe` | Merge branch 'feature/stage-progress-rename' into dev |

### Status

[OK] **Completed**


## Session 36: 10-11 关键词纠错与字幕重生成：六批实现到合并归档
<!-- trellis-session: v=2 fp=9ca534277bfb11b3 -->

**Date**: 2026-10-12
**Task**: 10-11 关键词纠错与字幕重生成：六批实现到合并归档
**Branch**: `dev`

### Summary

执行 10-11-subtitle-keyword-regenerate（父任务 10-11-subtitle-keywords-multilang 的子任务 A）。从 dev 切 feature/subtitle-keyword-regenerate，task.py start 后按 implement.md 六批推进：批 1 DB（subtitle_keyword 表、SubtitleKeywords 值对象含归一化/去重保序/上限校验/指纹、仓储端口与三件套）；批 2 产物指纹（SubtitleTrack 加 keywordsFingerprint、CheckpointPolicy.isCurrent）；批 3 清洗链路（端口签名改 SubtitleKeywords、PromptDataBlock 四段数据块、三处 system 补声明、SubtitleCleanupCoordinator 指纹断点与 regenerate 先成功后删 translate/terms）；批 4 会话地基（TaskSessionRegistry、TaskStageEvent.from 收在途阶段名、terminal 双条件派生）；批 5 HTTP 面（两新端点、提交路径带关键词、service 同步段四条错误映射、finally 退登记）。两处计划外改动：SubtitleArtifactSpec.compileTemplate 路由交替缺括号组致 matches 恒 false（artifacts 清单恒空），经所有者拍板一并修并加往返测试；TaskSseConnectionManagerTest 与 TaskProgressMessageTest 的终态事件夹具按「stage 状态位跟主状态走」的生产形状改（旧夹具 SUCCEEDED+RUNNING-stage 在旧规则下碰巧成立）。trellis-check 独立审查六维度 + AC 对照，修 8 类问题（409 文案分场景加 TaskConflictException message 构造器、spec 登记 6 处、keywords→findKeywords 动词化、补 5 个钉子用例含 regenerateSubtitles 的 finally 退登记、Javadoc 失实等），报 2 项待决——所有者裁定：成功后按前缀清失败原因（VideoTask.REGENERATE_FAILURE_PREFIX 单源 + clearRegenerateFailure，只认前缀不误伤翻译/主管线记录）；翻译侧摘要缓存不看指纹的偏差记入子任务 B 的 prd。另发现并修复存量 swagger 故障：openai-java-core 传递的非 jakarta swagger-annotations:2.2.31 与 springdoc 的 jakarta 版同名同包冲突，classpath 装载到缺 $dynamicRef() 的那份，/v3/api-docs 500；pom 加 exclusion 后实测恢复 200、两个新端点与 DTO schema 均入文档、swagger-ui 200。收尾：6 笔提交（功能 / 测试 / 建表语句 / pom 修复 / spec / 任务工件）+ merge，--no-ff 合回 dev，归档并删除本地 feature 分支。测试 570 用例 / 0 失败 / 0 错误 / 1 跳过（基线 498 → 净增 72，AC13 满足；1 跳过是存量 ModelProbeTest）。批 6.1 运行期验收（AC1–AC11 端到端）所有者在实例上自行验证后选择跳过，未由本会话执行；dev 领先 origin 11 笔尚未推送。

### Git Commits

| Hash | Message |
|------|---------|
| `198244c` | feat(subtitle): 关键词纠错与字幕重生成 |
| `7fca0c0` | test(subtitle): 关键词与重生成的单测 |
| `63b36fb` | feat(subtitle): 新增 subtitle_keyword 建表语句 |
| `ad85780` | fix(deps): 排除 openai SDK 传递的非 jakarta swagger-annotations |
| `87794e3` | chore(spec): 登记关键词任务引入的桶、表与终态派生改判 |
| `cb00a25` | chore(task): 关键词任务 planning 工件与回归卡登记 |
| `2de2ae5` | Merge branch 'feature/subtitle-keyword-regenerate' into dev |

### Status

[OK] **Completed**


## Session 37: 注释收口：domain 层试点 + 注释写法正典定稿
<!-- trellis-session: v=2 fp=fc84889cb00aca14 -->

**Date**: 2026-10-12
**Task**: 注释收口：domain 层试点 + 注释写法正典定稿
**Branch**: `dev`

### Summary

domain 全层 56 文件 / 186 注释块逐块读过，46 文件收口（注释净减 193 行）：删变更史与归档指针、类注释按「只回答四件事」重写、修 4 处失实、删 3 行失效 import。新建 .trellis/spec/guides/comment-guidelines.md 作注释规则唯一正典（十三条禁令 + 内容分流表 + 正例反例 + 防复发钩子 + 元规则「标准只住一处」），两份 quality-guidelines 改指针。审查窗口里用户逐轮校准，禁令从 5 条长到 13 条；父 design.md 的过期标准副本已删、改指正典，5 个子任务 prd 同步改指正典，模式清单收敛到正典 §六 一处。tests=570 全绿、AC1=0。另开 10-12-failure-prefix-and-guard 承接跨层代码项。

### Git Commits

| Hash | Message |
|------|---------|
| `6965134` | chore(task): 注释收口任务树 planning 工件登记 |
| `6b45f24` | docs(comment): 新增注释写法正典（十三条禁令），两份 quality-guidelines 改指针 |
| `d4a0dda` | docs(domain): 注释收口 —— 删变更史与决策记录、修正粒度错位 |
| `d210de5` | Merge branch 'feature/comment-domain-pilot' into dev |

### Status

[OK] **Completed**


## Session 38: 注释收口 application 层：清变更史、粒度归位、订正腐坏
<!-- trellis-session: v=2 fp=7dc2d11007cbdd5c -->

**Date**: 2026-10-12
**Task**: 注释收口 application 层：清变更史、粒度归位、订正腐坏
**Branch**: `dev`

### Summary

按注释写法正典（十三条禁令）收口 application 层 25 个文件：变更史与决策引用清零并改指其单源、类注释只留四件事、进度事件序列上移 spec 新增小节、两处失实（subtitle/package-info 的 Stage 后缀、AudioPreparationCoordinator 的 MVP-1 过渡态预言）订正。非注释行改动 0，mvn test 570/0/0 绿；独立质检两条硬不变量均成立。门 G2 用户裁定保留 spec 小节，随后提交、合并回 dev、删除 feature 分支并归档。

### Git Commits

| Hash | Message |
|------|---------|
| `c29156b` | docs(application): 注释收口 —— 清变更史与决策引用、订正腐坏 |
| `dd2798a` | chore(task): 回填 application 层执行结果，修父计划的失效指针 |

### Status

[OK] **Completed**


## Session 39: 注释收口 infrastructure 层：清变更史、粒度归位、订正腐坏
<!-- trellis-session: v=2 fp=2f3f41323bd6352b -->

**Date**: 2026-10-13
**Task**: 注释收口 infrastructure 层：清变更史、粒度归位、订正腐坏
**Branch**: `dev`

### Summary

按注释写法正典收口 infrastructure 层 31 个文件中的 27 个（26 改 + 新增 provider/llm/package-info）：15 处变更史与归档指针清零、改指单源；约 17 处「包级可见 / 包级 static：供测试…」按禁令 3/7 删；批级进度事件序列上移 spec（directory-structure.md 补「清单为空时不发批级事件」一格）；订正五类腐坏（persistence/package-info 漏列 subtitle_keyword 三件套、LlmSubtitleCleanser 两处未闭合括号残句、DashScopeTermLookupProvider 叠着两个 Javadoc 块、YtDlpRunner 与 YtDlpMediaAcquirer 两处失效预言、fs 两个类复述类名）。集外扩入 5 个文件（全层块复核捞出）。非注释行改动 0，mvn test 570/0/0 绿。门 G3 用户审查通过，并手删了一批他认为属废话的段（按用户要求不逐条登记、不还原）。随后提交、合并回 dev、删除 feature 分支与远端分支并归档。

### Git Commits

| Hash | Message |
|------|---------|
| `d4cf2f9` | docs(infrastructure): 注释收口 —— 清变更史与决策引用、订正腐坏 |

### Status

[OK] **Completed**


## Session 40: 注释收口 web/shared/config 层：清变更史、删「解释既成安排」的废话，并把判据回写正典
<!-- trellis-session: v=2 fp=4f5b7092905c3c08 -->

**Date**: 2026-10-13
**Task**: 注释收口 web/shared/config 层：清变更史、删「解释既成安排」的废话，并把判据回写正典
**Branch**: `dev`

### Summary

先给 10-12-comment-web-shared-config 补文档：prd 订正三处（删失实的 CheckpointPolicy 半句、api/→web/controller/、状态行）、implement.md 新建（三条筛选线实测：23 文件 / 83 块 / 并集 14 文件；阶段 0 裁定不新建 web 与 config 的 package-info）、两份 jsonl 由空填实（13 / 9 条）。同时把 infra 层留的 6 条标准补充请用户逐条裁定，5 条回写正典（禁令 3 补「给测试用/可直测」族；§二 新增「两条边界」——构件内部步骤序列直接删、事故叙事一律删但缺口未修的例外；§二 分流表加「命令/参数清单」；§五 保留行并入 docs/**）。随后收口三包 20 个文件（23 个里的 20 个，非注释行 0）：变更史与归档指针清零（含正典常量条点名的那处「SSE 契约」）、类注释改职责边界、删跨类类比（禁令 1）与测试用途说明与流水账、订正四处新腐坏（TaskResponse.from 残句、TaskArtifactResponse 未闭合括号、SubtitleSegmentSplitter 的 BREAK_CHARS 失实残句——整句删、原意不可复原已登记待裁、ChatClientConfig 的 /*** 与空标签）。登记一处代码问题不改：ChatClientConfig:18 方法名 ContentSummarizer 首字母大写。独立质检（trellis-check）六条 AC 全 PASS、未改文件，但指出 implement.md 白名单与实际 diff 不符（ApiExceptionHandler:18 的指针随变更史整段删），已订正。审查回合用户手删「解释既成安排」一族（落位依据、可见性辩护、反事实推演、机制推论、与别处重复的约束），我按同一判据追加 6 处并回查悬空引用与注释块内裸空行。该判据经用户裁定回写正典 §一（「读者读完会不会改变行为」+ 五种形态 + 删完回查两件事）并在禁令 6 加指引线。收尾：4 笔提交 + --no-ff 合回 dev、推送、本地 feature 分支删除、任务归档（52cf428）。遗留：domain / application / infrastructure 三层未按新尺子回扫；父任务还剩 backend-test 与 frontend 两个子任务。

### Git Commits

| Hash | Message |
|------|---------|
| `65bf3ca` | docs(comment): 注释正典补六条裁定 |
| `30409f7` | chore(task): 注释收口 web/shared/config 层 planning 工件与开工 |
| `caaf2d2` | docs(comment): 正典补「读者读完会不会改变行为」这把尺子 |
| `69019cb` | docs(web,shared,config): 注释收口 —— 清变更史与决策引用、删「解释既成安排」的废话 |
| `05f34e4` | chore(task): 回填 web/shared/config 层收口结果 |

### Status

[OK] **Completed**


## Session 41: 注释收口 backend test 层（子任务 5）+ 任务树对齐正典当前版本
<!-- trellis-session: v=2 fp=3af87381769e71d6 -->

**Date**: 2026-10-13
**Task**: 注释收口 backend test 层（子任务 5）+ 任务树对齐正典当前版本
**Branch**: `dev`

### Summary

先把注释任务树的文档按正典当前版本（§一 那把尺子、§二 两条边界、逐层沉淀）对齐并合入 dev；再执行子任务 5：收口 backend/src/test/java 56 个文件的注释，26 个文件改动（工作集 21 + 人读补扫新增 5）、非注释行 0、≥16 行块 1→0、mvn test 570/0/1/0。分支已合并回 dev 并删除。

### Git Commits

| Hash | Message |
|------|---------|
| `8afb33a` | chore(task): 注释任务树对齐正典当前版本（尺子/两条边界/逐层沉淀） |
| `b309a06` | docs(backend-test): 注释收口 —— 清变更史与决策引用、删「解释既成安排」的段 |
| `f7f4fc8` | chore(task): 回填 backend test 层收口结果 |

### Status

[OK] **Completed**


## Session 42: 注释收口 frontend 层（子任务 6，注释任务树最后一个）
<!-- trellis-session: v=2 fp=30c89883a3bce3f5 -->

**Date**: 2026-10-13
**Task**: 注释收口 frontend 层（子任务 6，注释任务树最后一个）
**Branch**: `dev`

### Summary

执行注释收口任务树的子任务 6：收口 frontend/src 的注释，19 个文件改动（+47 −70）、非注释行 0、210 个 JSDoc 块不变、≥16 行块 2 → 0、注释行 595 → 573（24.4% → 23.7%）、npm run type-check 与双 linter（oxlint + ESLint，带 --fix 但未改动任何文件）全绿——前端无单测基线，这两条就是本层「行为不变」的替身。开工前的实测把 prd 预估顶掉三处：prd 说「变更史 5 处 / 25 文件 / 2413 行」，实测 27 文件 / 2440 行 / 210 块，且归档指针共有三族共 25 处——① 正典 §六 机械线只报出 4 处；② design §N 章节号 14 处 / 9 文件（机械线模式只列 design.md，拦不住这个写法）；③ 任务卡需求号 R# / D# / AC# 8 处 / 4 文件（经核实出自归档的 .trellis/tasks/archive/2026-10/10-03-vidsage-fe-v0/prd.md 与 design.md）。即机械线报 4、人眼捞 22。处置：design §N 删括注保正文（正文多是真边界，如「本入口只管日历时间」），需求号整处删，另加 4 处日期与 3 处自然语言变体（「按原型验证…重写」「（原型验证的绑定口径）」「（原工作区产物面板修复先例）」）。按正典 §一 尺子与禁令 2 / 10 另收口：「解释既成安排」段（落位依据、可见性辩护）删，现状快照改职责边界（enums/task.ts、routes/index.ts），同一处写两遍的合一（useTranscriptPlayer 的类型标注句），构件内部步骤序列整条删（stores/task.ts 的数据流箭头链，按 §二 两条边界第 1 条）。request.ts 门面块里 axios 条件类型的整段推导在 type-safety.md:95 已有完整出处，代码里压成一句 why + 指针。prd 点名「不动」的 lib/gsap.ts 一字未动，并复核「当前无插件使用场景」仍真（全仓只有两个 view 从 @/lib/gsap 导入，无 registerPlugin 调用）。5 个人读复核后判不动：App.vue、BrandLogo.vue、HeroWave.vue、AudioWaveform.vue、views/not-found/index.vue。§4 标准补充四条：Ⓐ design §N 与需求号这类「任务卡内部编号」在正典 §五 表里没名字、§六 模式清单也拦不住，建议补一行（待裁）；Ⓑ 两个 .vue 里几乎同文的 SCSS 说明——禁令 5 / 10 都只管文件内，跨文件没口径，本层做了不对称处置（两份都删「来自原型」的出处句，只留 workspace 侧「与上传页的进度条同一视觉」这条跨组件不变量），待裁；Ⓒ 类型标注策略说明正典已覆盖，无需补；Ⓓ 新增一条：「需要 put / delete 时按同款签名在此追加」这类扩展点指引算不算禁令 6 的辩护句——本层判留（两处），理由与辩护句不同：读者本来就会去做那件事，它防的是另建实例、把单文件撑爆。§3 待订正空表，三处疑似失实（SubtitleArtifactFile 类型、后端 TaskSseConnectionManager、gsap 无插件）都查证为属实，记进表里备查。独立质检（trellis-check）已派发但截至提交尚未返回。收尾：本层是注释任务树 6 个子任务的最后一个，分支已 --no-ff 合回 dev、本地 feature 分支已删；dev 领先 origin/dev 10 笔未推。父任务 10-12-comment-consolidation 仍欠收尾三件（全量 AC1 三面复核 + 装钩子验 AC7 + domain/application/infrastructure 三层按 §一 尺子回归扫描），写在父 implement.md §5。

### Git Commits

| Hash | Message |
|------|---------|
| `8c5a5d0` | chore(task): 注释收口 frontend 层工件与开工 |
| `9a5bc0d` | docs(frontend): 注释收口 —— 清变更史与归档指针、删「解释既成安排」的段 |

### Status

[OK] **Completed**


## Session 43: 注释收口：三层回归扫描（domain / application / infrastructure）
<!-- trellis-session: v=2 fp=c6af44b077c86f44 -->

**Date**: 2026-10-13
**Task**: 注释收口：三层回归扫描（domain / application / infrastructure）
**Branch**: `dev`

### Summary

按正典 §一 那把尺子回扫三层：人读 301 个 ≥6 行块，31 文件 / 32 处删改（落位依据、反事实推演、机制推论、重复约束）；修掉 1 处注释块裸空行；AC1 三面复核归零（test 层一处「逐字节一致」判为误报并改写）；装 pre-commit 注释卫生闸（AC7）并实测拦截。父任务 10-12-comment-consolidation 随之归档。

### Git Commits

| Hash | Message |
|------|---------|
| `96cd921` | docs(backend): 注释回归扫描 —— 按正典 §一 尺子回扫 domain/application/infrastructure |
| `0f0a173` | Merge branch 'feature/comment-regression-scan' into dev |

### Status

[OK] **Completed**


## Session 44: 失败前缀常量化 + 后置生命周期守卫改名
<!-- trellis-session: v=2 fp=ef81061f7e3c485b -->

**Date**: 2026-10-13
**Task**: 失败前缀常量化 + 后置生命周期守卫改名
**Branch**: `dev`

### Summary

把 SubtitlePipeline 里两处硬编码失败前缀（「任务执行失败：」「翻译失败：」）提成 VideoTask 常量并接到真正的生产者，三个前缀与既有 REGENERATE_FAILURE_PREFIX 聚成一簇；私有守卫 requireMainSucceededForTranslation 改名为 requireMainSucceededForPostLifecycle（语义已泛化到重新生成）。纯行为等价：前缀字符串值逐字不变，5 条半角冒号日志格式串未动，测试文件一行未改，mvn clean test 570/0/0/1 与基线逐数相同。计划阶段做了事实核查，订正了 prd 里过期的行号（314/335→302/323）与断言计数，并把「旧名全仓零命中」收窄为 backend/src（归档文档里的史实引用不该动）。mvn javadoc:javadoc 仍有 1 条存量 error（SubtitleContextSummarizer.java:26 的 {@link CheckpointPolicy} 缺 import，与本次无关），新增的 6 处 {@link} 零诊断。

### Git Commits

| Hash | Message |
|------|---------|
| `04d2f5c` | chore(task): 失败前缀常量化 + 后置生命周期守卫改名 —— 计划产物 |
| `d177a60` | refactor(task): 失败前缀常量化并接到生产者，后置生命周期守卫改名 |

### Status

[OK] **Completed**


## Session 45: 字幕产物命名去路线化：SubtitleWorkspaceFilePaths 落地 + 运行期验收 + 归档
<!-- trellis-session: v=2 fp=785a1ca563a1caef -->

**Date**: 2026-10-14
**Task**: 字幕产物命名去路线化：SubtitleWorkspaceFilePaths 落地 + 运行期验收 + 归档
**Branch**: `feature/subtitle-multilang-translate`

### Summary

10-14-subtitle-artifact-naming 全流程收口。批1-3：8 项产物名去路线化、SubtitleRoute 整体退场（枚举+五个 record 字段+ASRProvider.route()）。批4：落地 application/workspace/ 的 SubtitleWorkspaceFilePaths.of(Path)（扫工作区出清单，缺席一律 Optional 不抛）+ 单独成文件的 TranslationFilePaths；MediaInput/SubtitleArtifactSpec 常量归位，SubtitleArtifactSpec 新增 getLanguage 反解（{lang} 正则加捕获组）。批5：57 测试全绿，测试树红面 16→12（余下全是父任务 translate 签名债）。批6：spec 订正 12 处（登记两个新包、删已不存在的 TaskArtifactScan、订正自定义异常陈述）。批7：运行期验收 AC1/AC5/AC6/AC7 全过（素材 BV1ZNgZ6nEee 32 秒；断点复用 LLM 批次 1→1；en+ja 并存）。批8：合并回 feature/subtitle-multilang-translate 并归档，父任务 handoff 记上第三棒。AC10 原文不可达成，口径改判为『不新增失败』后达成，prd 里刻意留未勾待点头。

### Git Commits

| Hash | Message |
|------|---------|
| `2216390` | 编写SubtitleWorkspaceFilePaths.of前 |
| `e5bac58` | feat(workspace): 落地 SubtitleWorkspaceFilePaths.of 与产物名常量归位 |
| `778567c` | chore(task): 任务文档按 as-built 同步 |
| `594ac33` | test(workspace): 补 SubtitleWorkspaceFilePaths 与产物名的单测，同步既有测试 |
| `20adb01` | fix(workspace): 收掉落地时留下的六条欠账 |
| `8d18db5` | docs(spec): 登记两个新包并订正产物命名与自定义异常的相关陈述 |
| `539250b` | chore(task): 批 7 运行期验收结果与 AC 勾选 |
| `9c92891` | chore(task): 父任务 handoff 记上第三棒状态（前置任务已归档，B 可恢复） |
| `5047c2b` | Merge branch 'feature/subtitle-artifact-naming' into feature/subtitle-multilang-translate |

### Status

[OK] **Completed**


## Session 46: 翻译多语言选择与翻译子状态下线：收尾（批 3–5 + 测试债）
<!-- trellis-session: v=2 fp=20733241833c547f -->

**Date**: 2026-10-14
**Task**: 翻译多语言选择与翻译子状态下线：收尾（批 3–5 + 测试债）
**Branch**: `dev`

### Summary

把翻译接进工作区登记表（tryStart + 409 + 三条退登记路径，AC9/AC10 冒烟通过）、失败收口落 error_message 并广播 FAILED 终态（AC8）、清掉批 4 全部残留（AC7）、新增 GET /api/languages 四项清单（AC1）；测试树 8 个文件跟上子状态下线与签名漂移，新增 SupportedLanguageTest / LanguageControllerTest，mvn clean test = 569 全绿。另订正四份 spec 并把「提交 vs 后置会话」这条从未落文档的缺口写进 error-handling.md。E（过时判据）与 ④（清失败原因）按用户指示留待后续。

### Git Commits

| Hash | Message |
|------|---------|
| `165ec65` | feat(subtitle): 翻译接入登记表、失败收口与语种清单端点 |
| `03dd97f` | docs(spec): 订正后置会话互斥口径、语种枚举落位与失败可见性 |

### Status

[OK] **Completed**


## Session 47: 字幕模型重构 S1 数据层地基：实现 + 四轮评审改名 + 注释标记清理
<!-- trellis-session: v=2 fp=96a51d9c3c560b03 -->

**Date**: 2026-10-18
**Task**: 字幕模型重构 S1 数据层地基：实现 + 四轮评审改名 + 注释标记清理
**Branch**: `feature/subtitle-model-foundation`

### Summary

建 workspace / subtitle_task / task_run / subtitle_artifact / intermediate_file 五张新表，配五个领域类（三个带状态机）、七个枚举、SourceKey 与五个仓储端口 + MyBatis 实现 + 10 个测试类，纯增量不动调用方；测试基线 569 → 649 全绿。评审四轮把命名收紧：实体迁移改目标态动词（completeCreation / supersede / confirmDeletion / revive）、私有守卫改 validateXxx、intermediate_file.kind 统一成 file_type、task 号生成收进 TaskNo.random()；并把 of 的适用判据（必须带实参）与工厂三族写进 directory-structure.md，把前置守卫命名写进 error-handling.md，把 database-guidelines.md 的建表契约一节按六表现状重写。代码注释里所有 C*/§/W*/design.md 指针、日期、任务号、阶段编号按 comment-guidelines 的内容分流表清干净；application.yml 与 schema.sql 一并清。真库验过 AC1（五张表逐列对版，含 51 个列注释逐字一致）与 AC6（旧契约回归，走断点复用）。分支 feature/subtitle-model-foundation 已推送，尚未合并回 dev。

### Git Commits

| Hash | Message |
|------|---------|
| `39dfa48` | feat(subtitle): 模型重构 S1 数据层地基（五张表 + 领域类 + 仓储端口与实现） |
| `bf97754` | docs(spec): 订正建表契约、补工厂判据与前置守卫命名 |
| `72004c9` | chore(config): 清理 application.yml 注释里的出处与历史 |
| `c6ec7cb` | chore(task): S1 任务工件与父任务文档订正 |

### Status

[OK] **Completed**


## Session 48: 字幕模型重构 S2：换血（提交路径 + run 生命周期 + SSE + 新契约）
<!-- trellis-session: v=2 fp=effc35bb6a9ad931 -->

**Date**: 2026-10-18
**Task**: 字幕模型重构 S2：换血（提交路径 + run 生命周期 + SSE + 新契约）
**Branch**: `dev`

### Summary

S2 换血全段落地：步骤 1–9 + 三面检查修复 + 四项收尾清理；全量测试 555 绿，真库冒烟 AC1–AC11 逐条过。

### Main Changes

## 做了什么

字幕模型重构 S2（`10-17-subtitle-model-submit-cutover`，父 10-15 的第 2/8 段）：把「旧栈还在跑」换成「新栈在跑」。

- **步骤 1–4**（`ebd706d`）：身份键 `MediaIdentityResolver`（B 站：剥跟踪参数 / av 换算 / 短链只跟一次 + 进程内有界 LRU）；SSE 全套（`RunRef` / `TaskRunProgress` sealed 三变体 / `TaskProgressBroadcaster` 端口 / `SseTaskProgressBroadcaster`）；`TaskRunSupervisor`（start / advance / finish / recoverInterruptedRuns）；四个 coordinator 收 `workDir`。
- **步骤 5–9**（`d13dd8d`）：schema 删 `task` 表、`subtitle_keyword` 换锚点到 `task_id` 并净增 `updated_at`；`SubtitleTaskService`（`TransactionTemplate` 显式圈事务 + C63 撞键锁定读）；新对外契约（身份 + `lastRun`、四个触发点统一 202/409、SSE 迁到 `/runs/{runNo}/events`、终态 204）；旧栈整块删；包结构按「任务与执行 / 内容加工」两条轴重排；启动收口；design 与两份 spec 同步。

## 关键决策

- **T9 撤回**：原定「`taskNo` 加进 `TaskRun`」，项目所有者改口径为「`taskNo` 的家是 `RunRef`，实体只留内部 `taskId`」—— 实体上那个家落不了库、读回要补查，而三处读调用方手里本来就有 `SubtitleTask`。
- **事务机制**：`handleSubmit` 用 `TransactionTemplate` 而非 `@Transactional` —— 身份键计算必须留在事务外，于是事务方法只能同类内调用，而同类自调用会绕过 Spring 代理、注解静默失效。**不是禁用 `@Transactional`**，别处该用照用。
- **步骤 4 边界收窄**：原 4.1/4.2/4.3（`WorkspaceDir` 换工厂 / `MediaInput` 瘦身 / 目录管理器换签名）移入步骤 5，因为它们与 `VideoTaskService` 互锁（它手里只有老的 `WorkspaceKey`）。
- **`@Lazy` 换掉**：F5 的修法一度让 broadcaster 依赖应用服务、成 bean 环；改成 `open(ref, Supplier<快照>)`，环消失（起服务无 BeanCurrentlyInCreation 可证）。

## 三面检查与修复

规范关 / 契约关 / 逻辑关三面纯读，跑出 5 条，全部落地：

- **F1（必现 bug）**：C63 并发分支一进就 500 —— 事务快照建立早于胜者提交，`workspace` / `subtitle_task` 两行重查用了 `FOR SHARE` 拿到了，但**取既有 run 那一步用的是普通读**，陈旧快照里看不到 → `IllegalStateException`。修法：`TaskRunRepository.findLatestForShareByTaskId`（照 design 自己的原则补一处）。
  **复现要点**：三个 curl 进程测不出来（启动错开几毫秒，根本进不去 C63）；要单进程 `curl --parallel --parallel-immediate` 把到达压到亚毫秒 —— 修复前 3 轮全是 1×202 + 7×500，修复后 3 轮全是 1×202 + 7×409。
- **web→domain 依赖违规**：`TaskController` 曾直接 import 四个 domain 类型 → 开流判定收进应用层，controller 现在零 domain import。
- **F5 SSE 订阅 TOCTOU**：判定可订阅到建桶之间若恰好收口，连接永远收不到终态 → 改成建桶之后再取快照。
- **F4**：`av` 号溢出时抛 400（违反「抽不到要回落」）→ 改回落。
- 删死类 `SourceUri`（含它的测试与两处文档订正）。

## 收尾清理（项目所有者逐个提问后落）

- `emit` 在四个 coordinator 里重复四份 → 收成 `shared/progress/StageEvents`。
- `catch (Throwable)` 接住之后 JVM 不再打默认堆栈 → 补 `log.error`，且**只打异常类型与调用栈帧、不打 message**（下载器会把外部命令 stderr 拼进消息，里面可能带 URL）。
- `SubtitleTaskService` 439 行、四个变化理由 → 拆成写用例 / `SubtitleTaskQueryService`（读）/ `SubtitleTaskFinder`（共用定位）。

## 验证

- `clean test-compile` 通过；全量测试 **555 / 0 失败 / 0 错误 / 1 skipped**。
- 条数账（dev 基线 649）：678（步骤 4 后）→ 550（换血后）→ 549（修复批次）→ 555（拆分后）；下降部分全部可解释（随删类 −128、重写缩水 −20、新增 +20…）。
- 真库冒烟（项目所有者选「甲：全量跑 AC1–AC11」）：手工 `DROP TABLE subtitle_keyword / task` → 清 `backend/data/works*` → 起服务。AC1–AC11 逐条过：同一视频不同跟踪参数收敛同一身份键、并发 1×202 + 7×409、无残留 RUNNING、kill 重启后残留 run 变 INTERRUPTED 且启动日志有「收掉 N 个任务」、SSE 三态（404 / RUNNING 开流首条是 DB 快照 / 终态 204）、契约形状、关键词换锚点首插两列同值、旧件零残留、三种 run 各跑通、产物落盘。

## 欠账

14 条按计划记账（产物不落 `subtitle_artifact` 表 → S3；fingerprint 仍现算 → S3；RECLEAN / TRANSLATE 换版 → S4/S5；中间产物登记 → S6；对账端点 → S7；网络超时 → S8；前端点不通 → 另开任务…）。
本轮新增两条：**F3**（非 B 站 host 也会发一次跳转 GET，修法是把跟跳转限定 `b23.tv`；项目所有者裁定暂不做）与 **`SubtitlePipeline` 一个类扛三种 run**（未排期）。


### Git Commits

| Hash | Message |
|------|---------|
| `ebd706d` | feat(task): 字幕模型重构 S2 步骤 1–4（身份键 + SSE 全套 + run 机制 + coordinator 收 workDir） |
| `d13dd8d` | feat(task): 字幕模型重构 S2 换血（提交路径 + run 生命周期 + SSE + 新契约 + 包结构重排） |

### Status

[OK] **Completed**


## Session 49: 字幕模型重构 S3：产物权威
<!-- trellis-session: v=2 fp=5338fce6296e35ae -->

**Date**: 2026-10-19
**Task**: 字幕模型重构 S3：产物权威
**Branch**: `dev`

### Summary

S3 把产物事实源从文件系统换成 DB：主管线按 CREATING 占位到换版落定写入 subtitle_artifact，新增产物清单与下载端点，读路径按 C25 自愈，后置会话前置判据改认可读 READY；并给降级上下文加 degraded 标记，避免摘要失败反复卡死主管线。

### Main Changes

- 主管线接线：SubtitleArtifactStore.createCreatingPlaceholder / settleArtifact 包住 prepare 与 clean（ORIGINAL / CLEANED 各一版；revision 由 S1 的 MAX+1 回填，八列文件/哈希同生同灭）。
- 读路径：新增 ArtifactController（GET /{taskNo}/artifacts 清单 + GET /{taskNo}/artifacts/{type}/{file}?lang= 下载），C25 整版自愈（缺文件只 WARN、不改状态）；TaskController 未改。
- 前置判据：regenerate / translate 从查文件改成「有可读的 READY ORIGINAL / CLEANED」，不满足仍 400 且不插 run。
- 收口：TaskRunSupervisor.finish 两段 try 分开——本次 run 的 CREATING 占位标 FAILED 与 run 终态落库互不阻断。
- 摘要降级：SubtitleContext 加 degraded 组件，降级也落 context.json 但缓存政策视为未命中（下次重试）；断点复用分支补写缺失/降级的 context。
- 命名：ArtifactFiles 改 ArtifactFileUtils；begin / settle 改 createCreatingPlaceholder / settleArtifact，并把「方法名要见名知意」立进 directory-structure.md。
- spec：error-handling.md 按现状重写（ArtifactNotFound 到 404、409 只剩撞 uk_active_run、清掉 S2 后失实的旧类引用）；父 design.md 第 6 节补 C55 取值与 400/404 分工。

### Git Commits

| Hash | Message |
|------|---------|
| `57c4ebd` | feat(task): 字幕模型重构 S3 产物权威（artifact 表读写 + /artifacts 与下载端点 + 降级上下文标记） |
| `8a6b691` | Merge branch 'feature/subtitle-artifact-authority' into dev |

### Testing

- [OK] clean test-compile 通过；全量 610 tests / 0 failures / 0 errors / 1 skipped（基线 555）。
- [OK] 真库加起服务冒烟 AC1/AC2/AC4/AC5/AC6/AC7：两条 READY 落表无 CREATING；二次提交 rev 递增且旧行八列清空；下载逐字节 cmp 一致；400/404 矩阵；移走 context.json 整版消失且 DB 仍 READY；移走 clean-segments.json 后 translate 400 且 task_run 不增行。

### Status

[OK] **Completed**

### Next Steps

- RECLEAN / TRANSLATE 不写 artifact 表（S4 / S5）；中间产物登记与复用判据改造（S6）；对账与哈希比对（S7）；网络超时（S8）；前端接接口（另开任务）。
- 两条未裁口径：TaskRunSupervisor 的产物收口是否改走 store（现为直连端口）；SubtitleContext.degraded 是否改名。


## Session 50: S4 重洗：RECLEAN 落 artifact 表 + W5 换版级联
<!-- trellis-session: v=2 fp=c2904f6001e1d8f5 -->

**Date**: 2026-10-19
**Task**: S4 重洗：RECLEAN 落 artifact 表 + W5 换版级联
**Branch**: `dev`

### Summary

SubtitleArtifactStore 新增 settleCleanedAndSupersedeDownstream：事务内「旧 CLEANED 降级清列 → 内容真变了才批量作废下游译文 → 新行 READY」，事务提交后按 DB 行删文件、删干净才标 DELETED；主管线与 RECLEAN 两条路共用。SubtitleCleanupCoordinator 摘掉目录扫描删文件，级联改用批量 markSupersededByTaskIdAndType。测试基线 610 → 623 全绿；手工验收 AC1–AC6 / AC9 / AC11 在真库真服务上通过。父 design.md 与 directory-structure / logging / quality 三份 spec 同步订正。

### Git Commits

| Hash | Message |
|------|---------|
| `d499c9e` | feat(task): 字幕模型重构 S4 重洗（RECLEAN 落 artifact 表 + W5 换版级联 + 两种终态） |
| `ea2eeed` | Merge branch 'feature/subtitle-reclean-run' into dev |

### Status

[OK] **Completed**


## Session 51: 字幕模型重构 S5：翻译落表与复用判据
<!-- trellis-session: v=2 fp=f20fafad3251b8d0 -->

**Date**: 2026-10-19
**Task**: 字幕模型重构 S5：翻译落表与复用判据
**Branch**: `dev`

### Summary

S5 完成：TRANSLATE run 落 subtitle_artifact（成功后占位 / W4 事务化换版 / 复用判据只认 READY+应带文件位可读），TERMS 改为可选文件位并统一落定/可读/回收/清单四处，coordinator 文件断点复用整块删除。全量 633 tests 绿；真库手工验收 AC1–AC8 通过。既有缺陷记入欠账 #9：ArtifactFileUtils 写失败消息带工作区绝对路径，经 run.error_message 外露，另开小修。

### Git Commits

| Hash | Message |
|------|---------|
| `f4590a7` | feat(task): 字幕模型重构 S5 翻译（TRANSLATE 落 artifact 表 + 复用判据 + W4 事务） |

### Status

[OK] **Completed**
