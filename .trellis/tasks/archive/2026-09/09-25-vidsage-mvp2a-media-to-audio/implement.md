# MVP-2a 输入获取与音频抽取 — 执行计划

> 技术方案见 `design.md`（下文的「决策 N」「§N」都指它）。需求见 `prd.md`。
> **本文件是执行顺序 + 验证命令 + 审查门的清单**，不是设计文档。

## 前置

- **分支**：从 `dev` 开 `feature/mvp2a-media-to-audio`（规则 1；本任务改代码，无例外可援引）
- **基线**：开工前先跑一次测试，确认起点是 **16 个测试全绿**
- **本机工具**（研究 §七）：`tools/yt-dlp.exe`（2026.08.19）、`F:\app_soft\ffmpeg\bin`（在 PATH 上）、JDK 21

```bash
# 基线（在 Git Bash 下必须显式给 JAVA_HOME，否则报「不支持发行版本 21」）
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/target/surefire-reports/*.xml
```

## 步骤

### 步骤 0：先拿实测事实，再写代码（**不要跳过**）

**先读源码，再信索引。** `oss-references.md` 开篇自己写明「本文件是索引，不是实现说明……**动手写代码前必须按条目给出的路径打开源码读原文**」。本任务要用到的原文在 `F:\learning_projects\asr_references\` 下：

- `VideoCaptioner/videocaptioner/core/utils/video_utils.py` —— `video2audio` / `get_video_info`（**GPL-3.0：只读思路，一行源码都不抄**）
- `VideoLingo/core/asr_backend/audio_preprocess.py` —— 抽音频的补充参数（Apache-2.0）

`design.md` §十 列了 8 个未验项。按 `research/external-process-integration.md` 的规矩——「标未验的是推断，用前必须自测」——先把能用一条命令问清楚的都问清楚：

```bash
./tools/yt-dlp.exe --version
# 下一条短视频到临时目录，看 info json 里到底有哪些定位字段
./tools/yt-dlp.exe --no-playlist --write-info-json -o "%(id)s.%(ext)s" -P /tmp/vidsage-probe <一条B站视频URL>
ls /tmp/vidsage-probe && python -c "import json,glob;d=json.load(open(glob.glob('/tmp/vidsage-probe/*.info.json')[0]));print({k:d.get(k) for k in ['filepath','_filename','requested_downloads','title','duration']})"
# 多音轨：数一条真实 B站视频有几条音频轨、各是什么语言、-map 0:a:0 会取到哪条
ffprobe -v error -select_streams a -show_entries stream=index,codec_name,channels:stream_tags=language -of json <刚下载下来的视频文件>
# 顺带：越界音频轨的报错文案（设计 §八 那条消息要照实写）
ffmpeg -hide_banner -i <视频文件> -map 0:a:9 -f null - 2>&1 | tail -5
```

**产出**：把得到的原始输出与结论追加进 `../09-24-vidsage/research/external-process-integration.md`（该文件是研究笔记，属纯文档改动）。至少回答：产物定位字段（待验 1）、info json 命名（待验 6）、`--ffmpeg-location` 形式（待验 2）、YouTube 是否被 JS 运行时阻塞（待验 3）、**音轨条数与默认取值（待验 7/8）**。

**若步骤 0 的结论与 `design.md` 冲突** → 停下来改 `design.md`，不要按冲突的方案继续写代码。

### 步骤 1：契约层（`model/`）

- `model/media/`：`MediaInput`（sealed，`RemoteUrl(String)` / `LocalPath(Path)`）、`AcquiredMedia`、`AudioSpec` + `AudioContainer`、`AudioArtifact`
- `model/process/`：`ProcessOutcome`、`ExternalToolReport`
- `model/progress/`：`StageEvent` + `StageState`

要求：全 `record`；Javadoc 写语义与**负面约束**（`quality-guidelines.md` 第三节）；`AcquiredMedia.infoJsonPath` 用 `Optional<Path>`（本地路线没有）；**不写行为方法**（`errorHandling` 判定逻辑留给调用方）。

**验证**：`cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q compile`

### 步骤 2：进程执行器（本任务地基）

- `provider/tool/ExternalProcessRunner` + `ExternalToolProperties`
- 测试作用域 `ProcessHelperMain`（制造 GBK 字节 / 超长输出 / 非零退出 / 睡到超时四种行为）

**验证**：`ExternalProcessRunnerTest` 必须覆盖

1. 输出按 `native.encoding` 解码（喂 GBK 字节，断言中文正确）
2. 超大 stdout + stderr 同时输出**不死锁**（各 ~1 MB）
3. 非零退出码**不抛异常**、原样返回
4. 超时 → `timedOut=true`、进程被回收、方法在超时后有限时间内返回
5. `command()` 原样回传（调用方可复现命令行）

### 步骤 3：外部工具诊断

- `provider/tool/ExternalToolDiagnostics`：解析二进制（配置路径优先，否则扫 `PATH`）、探测版本、算新鲜度
- 版本解析抽成**包级 `static` 方法**（`provider-pattern.md` 的 `toResult` 同款做法），测试直接喂真实输出字符串

**验证**：`ExternalToolDiagnosticsTest` 覆盖「路径配置为不存在的文件」「PATH 里没有该工具」「版本字符串解析」「新鲜度超阈值告警」，且断言错误消息含「缺什么 + 怎么补」。

### 步骤 4：ffmpeg 抽取

- `provider/audio/AudioExtractor` 接口 + `provider/audio/ffmpeg/FfmpegAudioExtractor`

**验证**：`FfmpegAudioExtractorTest`（Mockito mock 掉 runner，**不起 Spring 容器**）

- WAV 规格 → 断言生成参数含 `-vn -map 0:a:0 -ac 1 -ar 16000 -c:a pcm_s16le`
- M4A 规格 → 断言含 `-c:a aac -b:a 48k`
- `audioTrackIndex = 2` → 断言生成 `-map 0:a:2`（多音轨媒体的显式取值）
- 非零退出 → `IllegalStateException` 且消息含 stderr
- 成功但产物为空 → 抛异常（不能静默返回空文件）
- **原子落盘**：断言写入的是 `audio.<ext>.part` 且在成功后原子改名为最终产物；失败路径下**不得残留**最终产物文件名（只能剩下 `.part` 且被清理）

### 步骤 5：输入获取

**新增**

- `provider/download/MediaAcquirer` 接口（`supports` + `acquire`，**不收 listener**——理由见 design §三 决策 2）
- `provider/download/local/LocalFileMediaAcquirer`
- `provider/download/ytdlp/`：`YtDlpRunner`（解析二进制 + 注入 `--ffmpeg-location` + 执行，**2b 复用**）、`YtDlpMediaAcquirer`、`YtDlpProperties`（`vidsage.download.yt-dlp.cookies-file`）

**yt-dlp 调用配方（全部有实测依据，见研究笔记 §8.6）**

命令 = `[解析到的 yt-dlp 路径]` + 下列参数（逐项传，**不走 shell**），URL 放最后：

```text
--no-playlist --no-progress --write-info-json
-o "source.%(ext)s"                          # 固定模板 → source.<ext> + source.info.json
-P <workDir>
--ffmpeg-location <解析到的 ffmpeg 二进制>     # 配置了才注入
[--cookies <cookiesFile>]                     # 仅当 vidsage.download.yt-dlp.cookies-file 非空
<url>
```

- `--no-progress` 的理由：阶段内进度明确不在范围内（design「不做」清单），且进度行会让 stdout 膨胀。将来做 SSE 进度时改用 `--progress-template`
- **产物定位**：`source.` + info json 的 `ext`。实测 info json **不含** `filepath` / `_filename` / `requested_downloads` 三项（§8.6-4），**不要去找它们**。推导出的文件不存在时，扫描工作目录里匹配 `source.*` 且**排除** `.info.json` / `.part` / `.ytdl` 的项；**多于一个或一个都没有 → 报错并附目录清单，不要猜**
- **元数据映射**：info json 的 `title` 与 `duration`（秒、浮点）→ `durationMs`（四舍五入取整）。`AcquiredMedia` 的 `title` / `durationMs` 是**必填**契约字段
- **不按退出码分类**（研究 §二：`1` 是万能错误码）：非零一律 `IllegalStateException` 并附 stderr 尾部；只对**已实测**文案做保守匹配，命不中即回退「未知失败 + stderr 尾部」
- **告警必须可见（这条的原始假设被实测推翻，以实测为准）**：B站 的会员限制那行（`Format(s) 1080P 高码率 are missing…`，§8.6-1）**不在 stderr**、**也没有 `WARNING:` 前缀**——它在 **stdout** 上、以 `[BiliBili]` 开头，且 **exit 0**。所以两条都要扫：stderr 的 `WARNING` 行 + **stdout 的格式不可用提示**（实测子串 `are missing; you have to become a`）。**都不算失败，但必须记 WARN**，否则用户只会觉得「画质莫名变差了」
- **日志脱敏**：INFO 记退出码 / 耗时 / 产物文件名，**不打完整命令行**（那是执行器的 DEBUG 职责）；URL 属用户内容，按 `logging-guidelines.md` 红线处理

**本地文件路线**

- 卫语句：不存在 / 不是普通文件 / 不可读 → `IllegalArgumentException`（消息含绝对路径 + 「请检查路径，或改传 url」）
- `title` 取文件名；`durationMs` 用 `ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 <file>`（秒 → 毫秒，解析抽成包级 `static` 以便直测）；`infoJsonPath` = `Optional.empty()`
- 需要 ffprobe 的「解析 + 强制要求」入口：`ExternalToolDiagnostics` 已有 `requireFfmpeg()`，**同款加 `requireFfprobe()`**（复用 `resolveBinary` / `missingHint`，**不得复制第二份提示文案**）

**验证**：`YtDlpMediaAcquirerTest`（mock runner）断言配方参数齐全与 URL 位置、info json 解析（含缺字段路径）、产物定位的推导与扫描兜底、WARNING 转日志；`LocalFileMediaAcquirerTest` 覆盖不存在 / 非普通文件 / 正常。

### 步骤 6：工作目录 + 编排 + 断点复用

- `config/WorkspaceProperties`（`config/` 目录由本类带出，符合 `directory-structure.md`「目录随代码出现」）
- `service/WorkspaceService`、`MediaAcquisitionService`、`ProgressListener`、`AudioPreparationService`

**验证**：`WorkspaceServiceTest`（同一输入 → 同一目录；不同输入 → 不同目录）；`AudioPreparationServiceTest`（mock acquirer/extractor）

- 首次跑：两个阶段都执行，事件序列 `acquire:RUNNING → acquire:SUCCEEDED → extract:RUNNING → extract:SUCCEEDED`
- 产物已存在再跑：**不调用** acquirer / extractor，事件为 `SKIPPED`
- 产物存在但大小为 0 → 视为未完成，重新执行
- **半截产物**：手工造一个非空但截断的文件（如只有最终产物一半大小的 `audio.wav`）→ 断言**它不被当成成品**。这条是原子落盘方案的回归测试：如果实现漏了 `.part` + `ATOMIC_MOVE`，此用例会失败

### 步骤 7：HTTP 层

- 提取 `controller/ApiExceptionHandler`（`@RestControllerAdvice`，`IAE→400` / `ISE→500`，形状 `{ "error": message }`），删掉 `AsrController` 的局部 handler
- `controller/MediaController`：`GET /api/media/tools` + `POST /api/media/audio`

**验证**：`AsrControllerTest` 保持 16 项基线里的 4 项全绿（重构不破行为）；新增 `MediaControllerTest` 切片测试覆盖「url 与 localPath 都不传 / 都传 → 400」「正常 → 200」「下游 ISE → 500」。切片测试照 `AsrControllerTest` 的 `@WebMvcTest` 写法。

### 步骤 8：配置与仓库卫生

- `application.yml` 加 `design.md` §九 的三个配置块（**不写本机绝对路径**）
- `.gitignore` 忽略工作目录（`/backend/data/`）
- 确认 `backend/src/test/resources/application.yml` 不需要工具配置也能加载上下文（诊断只在被调用时探测，**不在启动时强校验**——否则缺工具的机器起不来服务）

**验证**：`VidSageApplicationTests` 仍绿。

### 步骤 9：全量验证 + 真实运行证据

```bash
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/target/surefire-reports/*.xml
```

真实证据（至少三条，保留命令与原始输出）：

1. `GET /api/media/tools` 的诊断报告（贴出真实版本号与路径）
2. 一条**真实 B站链接** → 下载 + 抽 WAV（**断言中文标题不乱码**，这是验收标准里的实测项）
3. 一个**真实本地 mp4** → 抽 M4A；再抽一次 WAV，确认断点复用跳过
4. YouTube 一条 → 按步骤 0 的结论记录（通过或降级说法，**不许含糊**）

## 审查门与回滚点

| 门 | 时机 | 动作 |
|---|---|---|
| **门 1（硬门）** | 步骤 9 完成、证据齐 | **立刻停下**，交项目所有者审查：改了什么 / 怎么验证 / 哪些没验证。未获批准不得 `git commit` |
| 门 2 | 用户批准提交后 | `git commit` + `git push -u origin feature/mvp2a-media-to-audio` |
| 门 3 | 用户明确批准合并后 | `git merge --no-ff` 进 `dev`、删本地与远端分支 |
| 门 4 | 用户主动归档时 | `/trellis:finish-work`；归档后做会话归档（规则 9，先问用户二选一） |

**回滚点**（`design.md` §十一：本任务纯新增，回滚成本极低）

- 步骤 2 完成：可独立回滚（只有执行器与测试，无人依赖）
- 步骤 7 的小重构若在审查中被否：单独 revert 该提交，其余部分不受影响（响应形状不变是它的前提）
- 任一步骤发现设计缺陷 → 回 Phase 1.1 改 `design.md`，改完再继续（workflow 允许 Plan 回退）

## Phase 3.3 要回写的规范（实现落地后做，不在本任务步骤内）

1. `directory-structure.md` 顶层分包判据补 `provider/tool/`：收「调外部程序的通用机制」，并说明它不适用接口+多实现的形态（`design.md` 决策 1）
2. `provider-pattern.md` 补一节「同能力多实现共存」：`@ConditionalOnProperty` 只适用于「配置二选一」，由输入类型选择时不加（`design.md` 决策 2）
3. `error-handling.md` 把「暂不建全局 `@ControllerAdvice`」更新为已建立（第二个 Controller 落地，条件触发）
4. 若步骤 0 的实测结论推翻了研究笔记里的任何一条，同步修正该笔记
5. **Boot 4 用 Jackson 3**：JSON 绑定是 `tools.jackson.databind.ObjectMapper`，Jackson 2 的 `com.fasterxml.jackson.databind.ObjectMapper` 在 Boot 4 里**没有 bean**（步骤 5 实测踩到并改用 Jackson 3）。`provider-pattern.md` 的「JSON：用 Jackson 注解」一节要写明包名与这条坑
6. **`@TempDir` 在 Windows 上清理偶发失败**：`JUnitException: Failed to delete temp directory`，**两次独立复现**（`FfmpegAudioExtractorTest` 与 `LocalFileMediaAcquirerTest`，都是**真写文件**的用例；不写文件的用例未观察到），是环境性句柄占用而非实现缺陷。测试规范补两条：
   - 凡**会写文件**又用 `@TempDir` 的测试类，改用 `@TempDir(cleanup = CleanupMode.NEVER)`，别让环境问题把测试判红
   - **不要**以「顺手统一」为由修改本任务范围外、且无 flake 证据的既有测试类——那会给审查凭空增加噪音
7. **`provider-pattern.md` 的「映射逻辑抽成包级 `static`」措辞要放宽**：当解析本身需要注入的 `ObjectMapper` 时，`static` 是别扭的（要把依赖降成参数）。判据应写成「**包级可见**，使测试无需 Spring 容器即可直接调用」——`static` 只是达成该判据的常见形态之一，不是目的。参照 `YtDlpMediaAcquirer.parseInfoJson`（instance、包级可见、持有注入的 `ObjectMapper`）

### 需项目所有者拍板的两处规范冲突（实现过程中暴露，非本任务范围）

8. **`model/` 到底能不能带 `@Schema`** —— `provider-pattern.md` 写「`model/` 禁止框架注解（Jackson 除外）」，但**已落地的实践相反**：2026-09-24 的 Swagger 任务给 `TranscriptionResult` / `TranscriptionSegment`（都在 `model/asr/`）加了 `@Schema`，而 PRD 要求「HTTP 契约模型字段都要有 `@Schema`」。**规范与代码现在打架**，两条出路：在规范里为「HTTP 对外模型」写明 `@Schema` 例外，或改由 `controller/` 自持响应 record + 映射。步骤 7 暂按**既有实践**（沿用 `AsrController` 先例）实现，请定夺。

> **勘误（2026-09-26 补注，出处误引）**：本条把该规则的出处写成了 `provider-pattern.md`，**这是引错了文件**。全仓 grep `框架注解` 只命中两处：
> - `directory-structure.md` 的「顶层分包判据」职责表 **`model/` 行** —— **规则实际住在那里**
> - `provider-pattern.md` 的「Provider 接口」行 —— 它说的是 **Provider 接口**不含框架注解，与 `model/` 无关
>
> **别去 `provider-pattern.md` 找一条不存在的规则。**（此处刻意只按章节名引用、不写行号：该文档行号已多次随改写漂移。）

9. **`model/` 是否允许 record 的紧凑构造器做字段不变量校验** —— 现在 `AudioSpec` 的 5 条字段校验（`container` 非空、采样率/声道为正、M4A 码率为正、轨号非负）在 `AudioPreparationService` 与 `FfmpegAudioExtractor` **各写一份**。原因：`model/` 禁止行为方法，而「包级可见的静态方法放 `AudioSpec`」在包布局下**技术上行不通**（`com.vidsage.service` 与 `com.vidsage.model.media` 不同包，包级可见跨不过去，只能 `public`，更破规矩）。若能允许紧凑构造器承担不变量，即可收敛成一处。属架构取向，需定夺。

## 交给 2b 的接口面（本任务完成即冻结）

- `MediaInput` / `AcquiredMedia` / `AudioSpec`（含两个预置常量）/ `AudioArtifact`
- `AudioPreparationService.prepare(MediaInput, AudioSpec, ProgressListener) → AudioArtifact`
- `ExternalProcessRunner` / `YtDlpRunner`（2b 用 `--skip-download --write-subs` 拉平台字幕要走它）
- `WorkspaceService.workspaceFor(MediaInput)`（2b 的字幕产物落同一目录）
- `StageEvent` / `ProgressListener`

2b **不需要**知道：ffmpeg 参数、工具路径、`ExternalToolProperties`、工作目录里的文件名规则。

---

## 步骤 9 实测记录（2026-09-25，真实运行，非单元测试）

素材：B站 短视频 `av117317904177698`（47.717 秒，无 CC 字幕）；本地路线素材 = 本次下载产出的 `source.mp4`。
启动命令（带工具路径）：
`cd backend && YT_DLP_PATH=…/tools/yt-dlp.exe FFMPEG_PATH=F:/app_soft/ffmpeg/bin/ffmpeg.exe FFPROBE_PATH=F:/app_soft/ffmpeg/bin/ffprobe.exe JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run`

### 1. `GET /api/media/tools` —— 工具诊断（配置齐全）

```text
yt-dlp   available=true  version=2026.08.19  path=f:\personal_project\vidSage\tools\yt-dlp.exe
ffmpeg   available=true  version=9.0.2-essentials_build-www.gyan.dev  path=F:\app_soft\ffmpeg\bin\ffmpeg.exe
ffprobe  available=true  version=9.0.2-essentials_build-www.gyan.dev  path=F:\app_soft\ffmpeg\bin\ffprobe.exe
```

### 2. 真实 B站 链接 → WAV（首次）

```text
POST /api/media/audio {"url":"http://www.bilibili.com/video/av117317904177698","audioContainer":"WAV"}
→ 200, 9.95s
{"audioPath":"…\\works\\http___www.bilibili.com_video_av117317904177698-efea85c04089\\audio-16k1ch-t0.wav",
 "container":"WAV","sampleRateHz":16000,"channels":1,"bitrateKbps":0,"audioTrackIndex":0,"sizeBytes":1527022}
```

**体积自洽核对**：47.717 s × 16000 Hz × 2 B ≈ 1.527 MB，实测 1527022 B —— 与「16k 单声道 16-bit PCM」的预期吻合，不是「文件存在就算过」。
产物名 `audio-16k1ch-t0.wav` 证明**规格指纹进了文件名**（§六 那条修正生效）。工作目录名 `http___www.bilibili.com_video_av117317904177698-efea85c04089` 证明 URL 安全化 + 内容哈希都生效。

### 3. 同一请求重放 → 断点复用

```text
→ 200, 0.0096s（首次 9.95s，约 1000× 快）
```

```text
INFO  YtDlpMediaAcquirer : 复用已下载产物（断点复用）: media=source.mp4, durationMs=47717, sizeBytes=2481616
INFO  AudioPreparationService : acquire 阶段跳过（断点复用）: media=source.mp4
DEBUG AudioPreparationService : 阶段事件: stage=acquire, progress=50, state=SKIPPED
INFO  AudioPreparationService : extract 阶段跳过（断点复用）: file=audio-16k1ch-t0.wav, sizeBytes=1527022
DEBUG AudioPreparationService : 阶段事件: stage=extract-audio, progress=100, state=SKIPPED
```

进度 50 → 100 **单调**，符合「SKIPPED 取阶段终点值」的裁定。两个阶段都不再触碰外部进程。

### 4. 本地 mp4 → M4A

```text
POST /api/media/audio {"localPath":"…/works/…-efea85c04089/source.mp4","audioContainer":"M4A"}
→ 200, 0.84s, sizeBytes=304884
{"audioPath":"…\\works\\source.mp4-e84686b5ff60\\audio-16k1ch48k-t0.m4a", …}
日志：acquirer=LocalFileMediaAcquirer, reused=false → 两阶段 SUCCEEDED
```

**体积对照**：304884 / 1527022 ≈ **1/5**，与研究笔记 §五「m4a 比 wav 小 4–8 倍」吻合。
**目录分离正确**：URL 那条的工作目录含 `source.mp4` + `source.info.json` + wav；本地这条的工作目录**只有音频、没有拷贝源文件**（本地路线不拷贝）。

### 5. 平台侧告警可见、但不改变成败

```text
WARN YtDlpMediaAcquirer : yt-dlp 提示: [BiliBili] Format(s) 1080P 高码率 are missing; you have to become a premium member to download them. …
INFO YtDlpMediaAcquirer : yt-dlp 下载完成: exit=0, media=source.mp4, durationMs=47717, sizeBytes=2481616, elapsedMs=9534
```

即 §八 补的那条「不算失败但必须可见」：同一次调用里既有 WARN 提示又有 exit=0 的成功。

### 6. 缺工具时的可操作报错（重启为**不带** `YT_DLP_PATH` 的实例）

```text
GET /api/media/tools → yt-dlp available=false, version=null, path=null
  HINT: 未找到 yt-dlp：请设置 vidsage.tools.yt-dlp-path 或将 yt-dlp 放入 PATH；本项目自带二进制在 tools/yt-dlp.exe
POST /api/media/audio {"url":"…av117313860932095","audioContainer":"WAV"} → 500
  {"error":"未找到 yt-dlp：请设置 vidsage.tools.yt-dlp-path 或将 yt-dlp 放入 PATH；本项目自带二进制在 tools/yt-dlp.exe"}
```

**顺带验证了一个真断点复用的性质**：同一条**已下载过**的 URL，在 yt-dlp 不可用时仍然返回 200——因为 acquire 阶段靠 info json 复用了产物，**根本不需要 yt-dlp**。缺工具错误只对「需要真去下载」的新 URL 出现。这是正确行为，不是漏检。

### 7. 错误路径（真实 HTTP）

| 请求 | HTTP | 响应 |
|---|---|---|
| 本地文件不存在 | 400 | `本地媒体文件不存在或不可读：F:\no\such\file.mp4；请检查路径，或改传 url` |
| 来源两个都传 | 400 | `url 与 localPath 不能同时传：请恰好传其中一个` |
| 来源都不传 | 400 | `缺少媒体来源：请传 url（公网媒体页面地址）或 localPath（服务端可读的本地媒体文件路径）` |

### 8. 实测暴露的两条环境事实（待回写研究笔记 / 待拍板）

1. **`mvn spring-boot:run` 的 fork JVM 不随 maven 进程退出**：杀掉 maven 后应用仍占 8080。这与 `ExternalProcessRunner` 文档里记的「`destroyForcibly` 只保证杀直接子进程、孙子可能成孤儿」**同源**。本次实测被它误导过一次（以为端口已释放，实际应答的是旧实例），清理必须按 PID 杀监听者。
2. **工作目录名由 URL 派生，而 DEBUG 日志会打它**（`开始准备音频: workDir=http___www.bilibili.com_video_…`）——等价于把用户的 URL 打进日志。B站 页面 URL 不带签名参数、风险低，但与 `logging-guidelines.md`「不打完整外部 URL」的红线**有张力**。待项目所有者定：是否改为只打哈希段或目录名长度。
