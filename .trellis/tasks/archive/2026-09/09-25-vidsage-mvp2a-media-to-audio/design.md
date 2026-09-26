# MVP-2a 输入获取与音频抽取 — 技术方案

> 需求与验收见 `prd.md`。执行顺序见 `implement.md`。
> 规范依据：`.trellis/spec/backend/` 下 `directory-structure.md` / `provider-pattern.md` / `error-handling.md` / `quality-guidelines.md` / `logging-guidelines.md`。
> 实测依据：`../09-24-vidsage/research/external-process-integration.md`（下文标「研究 §N」即指该文第 N 节）。

---

## 一、边界与契约

2a 的输入输出只有三样，**全在 `model/` 里，全是 `record` 或 sealed 接口**：

```text
入：MediaInput（RemoteUrl | LocalPath）  +  AudioSpec（调用方指定规格）
出：AudioArtifact（音频文件路径 + 规格 + 体积）
旁：AcquiredMedia（媒体文件路径 + 标题 + 时长 + 可选 info json）—— 2b 与排查都要用
   ↑ 标题与时长**必填**：远程路线取自 info json，本地路线由文件名（标题）与 ffprobe（时长）补齐。
     只有 info json 真的可能不存在（本地路线没有它），所以它是唯一用 Optional 表达的字段
```

三条**不许破**的边界：

1. **2a 不认识 ASR。** 不读 `vidsage.asr.*`，不 import `provider.asr.*`，不做「wav 还是 m4a」的判断。规格由调用方（2b）传进来。这条保证 2a 与 2b 之间只有一个契约，不存在循环依赖。
2. **2a 不认识「字幕」。** 平台字幕、ASR、SRT 全在 2b。（代价：同一 URL 被 yt-dlp 访问两次，已在父任务 PRD 记账）
3. **第三方/JDK 进程类型不外泄。** `ProcessBuilder`、`Process`、`Charset` 只出现在 `provider/tool/ExternalProcessRunner` 内；对外只有 `ProcessOutcome` 这个 record。与 `provider-pattern.md`「SDK 类型只在实现类内出现」同一条精神。

## 二、包结构

按 `directory-structure.md` 的技术分层（`controller` / `service` / `model` / `provider` 平铺或按领域二级分包）：

```text
com.vidsage
├── controller/
│   └── MediaController.java               # 工具诊断 + 抽音频（同步，临时面，见 §七）
├── service/
│   ├── ToolDiagnosticsService.java        # 工具诊断的薄服务（初稿漏了它，落地时补上，见下）
│   ├── AudioPreparationService.java       # 本任务的阶段编排：输入 → 音频（+ 断点复用）
│   ├── MediaAcquisitionService.java       # 按 MediaInput 子类型选适配器（不出现实现类名）
│   └── WorkspaceService.java              # 内容寻址的工作目录
├── model/
│   ├── media/
│   │   ├── MediaInput.java                # sealed: RemoteUrl(String) | LocalPath(Path)
│   │   ├── AcquiredMedia.java             # 媒体文件 + 标题 + 时长 + info json
│   │   ├── AudioSpec.java                 # 容器 + 采样率 + 声道 + 码率 + 音频轨（含两个预置常量）
│   │   └── AudioArtifact.java             # 音频产物
│   ├── process/
│   │   ├── ProcessOutcome.java            # 退出码 + stdout + stderr + 命令行 + 超时 + 耗时
│   │   └── ExternalToolReport.java        # 工具名 + 可用 + 版本 + 路径 + 提示
│   └── progress/                          # 进度契约（事件 + 回调）放 model/ 而非 service/
│       ├── StageEvent.java                # stage + progress + message + state（+ StageState 枚举）
│       └── ProgressListener.java          # 函数式回调接口
├── provider/
│   ├── tool/                              # 外部程序基础设施（唯一进程出口）
│   │   ├── ExternalProcessRunner.java     # @Component
│   │   ├── ExternalToolDiagnostics.java   # @Component（含 requireFfmpeg()：调用方唯一的二进制解析入口）
│   │   └── ExternalToolProperties.java    # @ConfigurationProperties("vidsage.tools")
│   ├── download/                          # 输入获取
│   │   ├── MediaAcquirer.java             # 接口（领域包根）
│   │   ├── ytdlp/
│   │   │   ├── YtDlpMediaAcquirer.java    # @Component
│   │   │   ├── YtDlpRunner.java           # 解析二进制 + 注入 --ffmpeg-location + 执行（2b 复用）
│   │   │   └── YtDlpProperties.java       # @ConfigurationProperties("vidsage.download.yt-dlp")
│   │   └── local/
│   │       └── LocalFileMediaAcquirer.java # @Component
│   └── audio/                             # 音频抽取
│       ├── AudioExtractor.java            # 接口（领域包根）
│       └── ffmpeg/
│           └── FfmpegAudioExtractor.java  # @Component
└── config/
    └── WorkspaceProperties.java           # @ConfigurationProperties("vidsage.workspace")
```

测试包镜像对应（`backend/src/test/java/com/vidsage/...`），另加一个测试作用域的 `ProcessHelperMain`（见 §六）。

## 三、关键设计决策

### 决策 1：进程执行器放 `provider/tool/`，且不抽接口

- **放 `provider/` 的理由**：它是「出站实现」——调用本机外部程序。放 `service/` 会让「service 不许出现实现类/SDK 类型」这条规矩变得别扭（进程语义不是业务编排）。
- **不抽接口的理由**：只有一种实现方式，`directory-structure.md`「不预先抽接口」与 `quality-guidelines.md` 反模式清单同一条精神。测试的可替换点在 `ProcessHelperMain`（§六），不靠接口。
- **这是对规范的扩展，需在 Phase 3.3 回写**：`provider-pattern.md` 只描述了「接口 + 多实现 + `@ConditionalOnProperty`」这一种形态，没有覆盖「单一实现的基础设施」。本任务落地后应在 `directory-structure.md` 的顶层分包判据里补一行：`provider/tool/` 收「调外部程序的通用机制」。

### 决策 2：下载适配器**不用** `@ConditionalOnProperty`，两个实现同时驻留

`provider-pattern.md` 的装配规则是「同一时刻容器里只有一个实现 Bean」，前提是「同一能力、多种来源、由配置二选一」。

本任务的前提不同：**两条输入路线由输入类型决定，且同一次运行里都要在**（一个进程既要能下 URL 又要能收本地文件）。因此：

```java
public interface MediaAcquirer {
    boolean supports(MediaInput input);              // 由输入子类型决定，不由配置决定

    /** 产物已存在则直接复用（不重复下载）；不存在返回空。 */
    Optional<AcquiredMedia> reuseExisting(MediaInput input, Path workDir);

    /** 真正获取一次；失败抛 ISE。 */
    AcquiredMedia acquire(MediaInput input, Path workDir);
}
```

- **为什么要 `reuseExisting` 而不是让服务自己判「文件在不在」**（落地时发现的设计矛盾，此处修正）：断点复用要跳过下载，就得先判断产物在不在；但**上层造不出 `AcquiredMedia`**——`title` / `durationMs` 藏在 info json 里，而解析 info json 的知识（字段名、未知字段容错）属于适配器。若让服务去 glob 文件并重新解析 json，等于把适配器的知识复制一份到服务层。所以「产物在不在」这个问题**必须由持有该知识的适配器回答**，`reuseExisting` 就是它的接口形态
- 上层因此只知道「可能有已存在的产物」这个**概念**，不知道 `source.info.json` / `source.<ext>` 这些**名字**。命名知识仍然只有两处：适配器（写）与它自己（读）

- **`acquire` 不收 `ProgressListener`**（初稿设计里有，已去掉，两重理由）：
  1. **分层**：收 listener 会让 `provider/` 反向依赖 `service/`（初稿把 `ProgressListener` 放在 `service/`），是层次倒置。事件与回调都是中立契约，已一并放进 `model/progress/`
  2. **范围**：阶段内进度（下载百分比）明确不在本任务范围内（见「不做」清单），所以适配器没有阶段内事件要发。**阶段事件由 `AudioPreparationService` 在调用前后发出**（RUNNING / SUCCEEDED / SKIPPED / FAILED）。将来真要下载进度时，再让适配器收 listener——那时契约已在 `model/`，不再有分层问题
- 两个实现都是 `@Component`，**不加** `@ConditionalOnProperty`
- 选择逻辑收敛在 `MediaAcquisitionService`（构造注入 `List<MediaAcquirer>`，按 `supports()` 挑，找不到即 `IllegalStateException`）；`service/` 里因此不出现任何实现类名
- **这是 provider 模式的一个泛化形态**，落地后应在 `provider-pattern.md` 补一节「同能力多实现共存时的装配」区分两种场景

### 决策 3：`AudioExtractor` 抽接口，因为规范禁止 `service/` 依赖实现类

`directory-structure.md` 明写「service 禁止出现实现类」。若让 `AudioPreparationService` 直接注入 `FfmpegAudioExtractor`，就破规。故保留接口：

```java
public interface AudioExtractor {
    AudioArtifact extract(Path mediaPath, Path outputPath, AudioSpec spec);
}
```

- **输出路径是入参**，由调用方算 —— 路径只有一处定义（`AudioPreparationService` 私有方法），extractor 不猜文件名
- 未来若出现第二个抽取器（如 GPU 硬解、云端转码），本接口就是接入点；当前只有一个实现是被接口形态允许的（对比 `ASRProvider` 当时也只有一个实现）

### 决策 4：`AudioSpec` 用「容器枚举 + 通用参数」，不出现 ffmpeg 词汇

```java
public record AudioSpec(AudioContainer container, int sampleRateHz, int channels,
                        int bitrateKbps, int audioTrackIndex) {
    /** 本地 FunASR：零解码，模型输入本就是 16k 单声道 wav。 */
    public static final AudioSpec WAV_PCM_16K_MONO = new AudioSpec(AudioContainer.WAV, 16000, 1, 0, 0);
    /** 云端 DashScope：体积优先（wav 的 1/4～1/8），走上传带宽。 */
    public static final AudioSpec M4A_AAC_48K_MONO = new AudioSpec(AudioContainer.M4A, 16000, 1, 48, 0);
}

public enum AudioContainer { WAV, M4A }
```

- `bitrateKbps` 对无损容器传 `0` 表示不适用（Javadoc 写明）
- **`-c:a pcm_s16le` / `-c:a aac -b:a 48k` 这类 ffmpeg 参数映射只写在 `FfmpegAudioExtractor` 里**，契约不出现编码器名
- 选型依据：研究 §五（wav 与 m4a 的体积差 4–8 倍、`libmp3lame` 在精简构建里可能缺失、m4a/AAC 用 ffmpeg 原生编码器）
- **编码器不可用时不自动降级**：若 ffmpeg 报 `Unknown encoder 'aac'`，按 §八 报错（消息里写清「该构建缺 aac 编码器，请换完整构建」）。参考项目 VideoLingo 在 `libmp3lame` 缺失时降级到 `pcm_s16le wav`，那是它单进程管线内部的自由；**我们这里是跨进程契约，悄悄换容器等于悄悄改产物规格**，与「规格由调用方声明」直接冲突

### 决策 5：音频轨显式选择，不依赖 ffmpeg 默认挑选

`oss-references.md` 第 15 条记了一条本设计初稿**漏掉**的坑：`video2audio(input, output, audio_track_index)` 的第三个参数，配文「**B站视频常有多音轨**」。

- 初稿只写 `-vn -ac 1 -ar 16000`，靠 ffmpeg 的默认流选择。默认行为是「挑一条最佳音频流」，规则随容器与元数据变化，**多音轨媒体上不可预期，且会静默选错轨**
- 改为显式 `-map 0:a:<audioTrackIndex>`：默认第 0 条，可复现、可解释
- 取哪条轨是**调用方**的事：`AudioSpec` 增加 `audioTrackIndex` 字段（默认 `0`）。放 `AudioSpec` 而不是新开一个方法参数，理由是它和采样率、声道同属「我要什么音频」，且不让接口参数膨胀（备选「`extract` 加第四个参数」被否：会连带膨胀 `prepare` 的签名）
- **记录否掉的另一个方案**：照参考项目用 ffprobe/正则解析出音轨数与语言后「自动挑一条」。否掉的理由是多音轨上「哪条是对的」取决于人的意图，没有通用解——把决定权显式交给调用方更诚实
- **顺带否掉参考项目的 stderr 正则解析**：`get_video_info` 用正则从 ffmpeg stderr 里抠时长与音轨数；我们改用 `ffprobe -of json`（`ffprobe` 本来就在必检工具清单里）。理由：正则解析人类可读 stderr 会随 ffmpeg 版本漂移，JSON 是稳定接口
- **已实测（2026-09-25），本条从推断升级为结论**：`ffmpeg 9.0.2` 的默认音频流选择**跟随 `default` disposition 标记**——把第 2 轨标为 `default` 后，不写 `-map` 抽出来的就是第 2 轨（md5 与显式 `-map 0:a:1` 完全一致）；而「立体声 192k 的第 2 轨」并**没有**抢过单声道 32k 的第 1 轨，推翻了「默认取 best 流」的直觉。原始输出见研究笔记 §8.2。**静默取错轨是真实可复现的，不是理论风险**
- **待实测**：§十 新增第 7 项（真实 B站多音轨视频究竟几条轨、默认该取哪条）

## 四、数据流

```text
MediaInput ─┬─ RemoteUrl ─→ [MediaAcquisitionService] → YtDlpMediaAcquirer ─┐
            └─ LocalPath ─→                            LocalFileMediaAcquirer ┤
                                                                             ↓
                                              AcquiredMedia(mediaPath, title, durationMs, infoJson)
                                                                             ↓
                                 [AudioPreparationService] ── WorkspaceService: workDir
                                                                             ↓
                                      断点检查：audio.<ext> 已存在且非空 → 跳过（发 SKIPPED 事件）
                                                                             ↓
                            [AudioExtractor] FfmpegAudioExtractor（读 vidsage.tools.ffmpeg-path）
                                                                             ↓
                                                            AudioArtifact（路径 + 规格 + 体积）
```

阶段与阶段事件：`acquire`（下载/采用）→ `extract-audio`。每个阶段发 `RUNNING` / `SUCCEEDED` / `SKIPPED` / `FAILED`。

## 五、进程执行器的实现要点

**这是全任务最容易写错的地方，逐条对着研究笔记来。**

```java
public ProcessOutcome run(List<String> command, Path workingDir, Duration timeout)
```

| 要点 | 做法 | 依据 |
|---|---|---|
| 参数传递 | `List<String>` 逐项传给 `ProcessBuilder`，**永不拼字符串、永不 shell** | 编码 + 注入双风险；也避免 Windows 引号转义地狱 |
| 输出编码 | `System.getProperty("native.encoding")` → `Charset.forName(...)`，取不到或非法时兜底 `StandardCharsets.UTF_8`；**不用 `Charset.defaultCharset()`**（Java 21 恒为 UTF-8，正是踩坑写法） | 研究 §一（本机 yt-dlp 吐 GBK，Java 21 默认 UTF-8 → 中文静默乱码） |
| 防死锁 | stdout / stderr 各起一条虚拟线程 `readAllBytes()`，与 `waitFor(timeout)` 并行；**不用 `redirectErrorStream(true)`**（会污染正常输出） | 双管道写满即互锁 |
| 超时 | `process.waitFor(timeout)` 为 false → `descendants().forEach(destroyForcibly)` + `destroyForcibly()` + `waitFor()` 回收 → 结果标 `timedOut=true` | 研究 §六-2（Windows 上进程树/文件锁的局限，见下） |
| 退出码 | **非零不抛异常**，原样装进 `ProcessOutcome`，由调用方分类 | 研究 §二（`1` 是万能错误码，无法区分业务错误） |
| 标准输入 | 立即 `process.getOutputStream().close()`；ffmpeg 另加 `-nostdin` | 否则子进程可能阻塞等 stdin |
| 可观测 | 记录 `elapsedMs`；DEBUG 打完整命令行、INFO 打退出码与耗时、失败时打 stderr 尾部 | `logging-guidelines.md` |

**已知局限（写进代码注释，不假装解决）**：Windows 上 `destroyForcibly` 只杀直接子进程，yt-dlp 内部再拉起的 ffmpeg 可能变孤儿。先按「best-effort 杀进程树」实现，实测若残留进程再考虑 Windows Job Object。研究 §六-2 已把该风险标为「未验」。

**测试用的替身**：单元测试用 JDK 自带的 `java` 可执行文件（`System.getProperty("java.home") + "/bin/java"`）跑一个测试作用域的 `ProcessHelperMain`，由它按参数分别制造「GBK 字节输出」「超长输出」「非零退出」「睡到超时」四种行为。这样跨平台、无外部依赖，且走的是真实的 `ProcessBuilder` 路径。

**落地后补记的两处实现细节（与原设计的差异，均为有意为之）**：

- **留了一个测试缝**：包级构造 `ExternalProcessRunner(Charset)` + 包级 `static resolveConsoleCharset()`。生产路径仍走公有无参构造（解析 `native.encoding`），测试注入固定编码。「喂 GBK 字节断言中文正确」这条用例若依赖运行机器的 `native.encoding`，在 UTF-8 的机器/CI 上必然假红，注入才能跨平台确定；另有一条测试断言注入缝与 `native.encoding` 的结果一致。
- **日志与脱敏的取舍**（`logging-guidelines.md` 第四节红线 vs 本设计「DEBUG 打完整命令行」）：落地方案是 **DEBUG 打完整命令行**（注释里写明「调用方若传入带签名 URL，脱敏责任在调用方」），**INFO / WARN 只打可执行文件名**（不含参数），失败时附 stderr 尾部 20 行。理由：命令行是复现问题的唯一依据，而执行器是通用基础设施、不认识参数语义；stderr 尾部是排障必需，同样可能含 URL，脱敏责任一并归于调用方。**若将来有调用方真的会传带签名 URL，届时在执行器加一个可选的参数脱敏钩子**，不要现在凭空设计。
- **超时后必须有界等待流读取**（检查期发现并修复的挂死路径，比原设计描述严重一层）：本设计 §五 只记了「孤儿进程可能继续写文件」。实际后果更重——若孙子进程继承了 stdout/stderr 写端且没被强杀（**yt-dlp → ffmpeg 正是这个形态**），读线程等不到 EOF，而对 `CompletableFuture.get()` **不设超时**会让 `run()` 在超时后**永不返回**，PRD 验收项「超时能被打断并回收，不挂死」就成了空话。修法：抽 `static drain(future, charset, graceMs)`，`get(graceMs)`，`STREAM_DRAIN_GRACE_MS = 5_000`，超时打 WARN 并降级为空输出。
  - **有界等待无条件生效**，不做「只在 `timedOut` 时才有界」的优化：工具自身退出而它拉起的子进程还在跑时，同样会挂死
  - **已知最坏代价**：stdout 与 stderr 是**串行** drain，两条都被孤儿持有时最坏 `2 × 5s = 10s` 才返回。对默认 300s 超时的命令可接受，且是「有界」而不是「挂死」，故接受
  - **记录否掉的方案**：改 `allOf(...)` 并行等待 + `getNow` 取已完成的那条，最坏可压到 5s。否掉的理由：为一个罕见路径引入并发原语的复杂度不划算；真要改时只需动 `drain` 一处，试点明确
  - **未处理的已知残留（不假装解决）**：`TimeoutException` 分支下那条读虚拟线程不会被取消——`read()` 不可中断，`cancel(true)` 同样无效——它会阻塞到孤儿进程退出。虚拟线程不占 OS 线程，成本可忽略

## 六、工作目录与断点复用

- 根目录 `vidsage.workspace.root`，默认 `./data/works`（**加入 `.gitignore`**）
- 单任务目录 = `root/<安全化名字>-<sha256(规范化输入) 前 12 位>/`
  - 内容寻址而非文件名寻址：同一输入重跑落回同一目录，改名/换路径不影响（思路见 `oss-references.md` 第 18 条的「key 用内容哈希，不用文件名」）
  - `RemoteUrl` 的规范化串 = URL 本身；`LocalPath` 的规范化串 = 绝对路径 + 文件大小 + 最后修改时间
- 目录内固定文件名（**只有 `AudioPreparationService` 知道**这些名字）：

  | 文件 | 阶段 |
  |---|---|
  | `source.<ext>` | acquire（远程路线） |
  | `source.info.json` | acquire（远程路线） |
  | `audio-<sampleRate>k<ch>ch[<bitrate>k].<ext>`（如 `audio-16k1ch.wav`、`audio-16k1ch48k.m4a`） | extract-audio |
  | 2b 追加：`subs/`、`subtitles.srt` | 2b |

- **音频文件名必须带完整规格指纹**（落地时修正的漏洞）：若只叫 `audio.wav`，名字只反映容器。同一输入先用 16k 抽一次、再用 8k 抽一次（或码率/轨号不同），第二次会**命中第一次的文件被当成已完成而跳过**——返回一份与请求规格不符的音频，**不报错、无声失败**。这与决策 5（显式 `-map`，不依赖隐式行为）是同一条原则：**不让正确性依赖未言明的假设**
- 指纹**涵盖所有影响产物内容的参数**：采样率、声道、码率、**音频轨号**（轨号不同则产物不同，别漏）。落地口径：**统一生成、不做省略特例**——`audio-16k1ch48k-t0.m4a`、`audio-16k1ch-t1.wav`…即 `audio-<采样率>k<声道>ch[-<码率>k]-t<轨号>.<扩展名>`，无损容器无码率段。宁可稍微啰嗦，也不要「`t0` 省略」这类特例分支（特例就是漏判的入口）
- **采样率不是整千赫兹时保留小数**（如 `44100 → 44.1k`）：整数截断会把不同采样率映射成同名，等于把上面刚堵上的「静默复用错文件」漏洞从另一条路放回来

- **产物定位靠「我们自己的输出模板」，不靠 info json**（实测结论，见研究笔记 §8.6 第 4 条）：yt-dlp 落盘的 info json **不含任何产物路径字段**——`filepath` / `_filename` / `requested_downloads` 三项全缺。所以：
  - `-o` 模板**固定为 `source.%(ext)s`**（实测该模板派生出的 info json 叫 `source.info.json`，中间产物 `source.<format_id>.<ext>` 在合并后自动删除，目录最终只剩 `source.<ext>` + `source.info.json`）
  - 产物名 = `source.` + info json 里的 `ext`（合并后的最终扩展名；实测 `ext=mp4` 与磁盘上的 `BV1LLhW6uE12.mp4` 一致）
  - 兜底：上述推导出的文件不存在时，扫描工作目录里匹配 `source.*` 且**排除** `.info.json` / `.part` / `.ytdl` 的项；多于一个或一个都没有 → 报错并附目录清单（**不要猜**）
- **下载中间产物天然安全**：yt-dlp 用 `<name>.<format_id>.<ext>` 命名分片、用 `.part` 表示未完成，合并成功才产出 `source.<ext>`。这与 §六 的原子落盘要求天然一致，下载侧不需要额外的 `.part` 处理

- **产物必须原子落盘，否则断点判据不可靠**：ffmpeg 边转边写，中途被杀或超时会留下**非空但截断**的文件，「存在且大小 > 0」判不出这种半截产物 → 会静默把坏音频当成品复用。这正是参考项目 VideoLingo 用「临时文件 + `os.replace` 原子替换」（`oss-references.md` 第 18 条）与 `.subtitle_done` marker（第 5 条）的理由。故：
  - 抽音频先写 `<workDir>/audio.<ext>.part`，成功后 `Files.move(part, final, ATOMIC_MOVE)`；失败或超时则删除 `.part`
  - 下载侧**不需要**额外处理：yt-dlp 自身就用 `.part` + 完成后改名，未完成的下载不会伪装成 `source.<ext>`
  - 有了原子改名，「文件存在且非空」才是**可靠**判据而不是「大概率对」
  - **extractor 不覆盖已存在的产物**：`Files.move(..., ATOMIC_MOVE)` 在目标已存在时的行为是**实现相关**的（可能抛 `FileAlreadyExistsException`，也可能替换），不能依赖。所以 extractor 的契约是「目标不存在时原子落盘」；目标已存在则抛 `IllegalStateException`，消息要**可操作**（「产物已存在：<路径>；请先删除，或由上层断点复用逻辑跳过本阶段」）。**不做静默覆盖**——静默覆盖会掩盖「两条不同规格映射到同一路径」这类路径冲突缺陷；正常路径下上层（步骤 6）已先判存在即跳过，根本走不到这里
- **断点判据统一为「文件存在且大小 > 0」**，不看时间戳、不看 marker 文件（原子改名已经取代 marker 的作用）。**但「谁来检查」按产品归属分**：
  - **acquire 阶段的产品由适配器检查**（`reuseExisting`）：只有它知道 `source.<ext>` 的推导规则与 info json 的解析，上层拿不到 `title` / `durationMs` 就造不出 `AcquiredMedia`（见 §三 决策 2 的说明）
  - **extract 阶段的产品由服务检查**：`audio.<ext>` 的名字由 `AudioPreparationService` 定义（§六 文件名表），服务直接判存在且非空
  - 两处判据（存在且非空）的实现各写一次是可接受的：**判据相同，但归属不同**；强行合并成一处共用会逼服务层感知 `source.*` 命名，得不偿失
  - **本地路线的 `reuseExisting` 恒返回空**（落地时的判断，接受）：本地路线不下载不拷贝，**没有「获取产物」可复用**——输入文件本身始终是真源。若让它返回文件，acquire 阶段会被永久跳过，连「文件存在/可读」的校验与 ffprobe 时长读取一起丢掉。本地路线的复用价值落在 extract 阶段（不重复抽音频），由服务层的判据负责。**推论：`acquire:SKIPPED` 只在远程路线上会出现**
  - **SKIPPED 的进度取该阶段的终点值**（acquire → 50，extract → 100）：设计只说「沿用当前累计值」，有歧义。取终点值才能保证**进度单调**、且「全部复用的一次重跑」不会停在 50% 让人以为卡住（与 `oss-references.md` 第 2 条记的「progress 强制单调不回退」同一个道理）
  - **spec 必须先校验再用断点**：extract 阶段的断点分支不能绕过 `AudioSpec` 的字段校验（`container` 非空、`sampleRateHz > 0`、`channels > 0`、M4A 的 `bitrateKbps > 0`、`audioTrackIndex >= 0`）。否则「传了非法 spec、而对应文件恰好存在」会返回一个未经校验的产物。校验前置是零成本的
- 跳过时发 `StageEvent(state = SKIPPED)` 并记 INFO 日志 —— 断点复用必须**可观测**，否则「跳过了」和「没跑」分不出来

## 七、HTTP 层（临时面，明确标记）

- `GET  /api/media/tools` —— 工具诊断报告。只读、秒级。epic design.md 已要求「诊断端点报新鲜度」，这是它落地的地方
  - **初稿漏了一层**：本设计原打算让 `MediaController` 直接注入 `ExternalToolDiagnostics`，但那破了 `directory-structure.md`「`controller/` 禁止直接依赖 `provider/`」。落地时补了薄服务 `ToolDiagnosticsService`，把链路恢复成「controller → service → provider」。这是**设计的漏洞、实现的修正**，不是实现跑偏
- `POST /api/media/audio` —— 同步抽音频：`{ "url" | "localPath", "container": "WAV"|"M4A", ... }` → 音频产物信息

**为什么是同步的、以及它为什么是临时的**：MVP-1（任务提交 / 落库 / SSE）尚未落地，本任务需要一个能被真实调用的端到端入口来产出运行证据。同步端点在「本地文件 + 短视频」下可用，长视频会顶到 HTTP 客户端超时——**真正的形态是 MVP-1 的异步管线**。故：

- 控制器类注释里写明「临时面：等 MVP-1 的异步管线落地后，本端点应改为管线的一个触发入口或直接删除」
- 不引入任务 ID、不落库、不排队（那全是 MVP-1 的范围）

请求体 record 定义在 `MediaController` 内部（`directory-structure.md` 规定）；入参校验用卫语句（`url` 与 `localPath` 必须二选一）。

**顺带的小重构（规范已预告）**：`error-handling.md` 写「暂不建全局 `@ControllerAdvice`：等出现第二个 Controller、局部处理开始重复时再提取」。本任务正是第二个 Controller，因此提取 `controller/ApiExceptionHandler`（`@RestControllerAdvice`），把 `IllegalArgumentException → 400`、`IllegalStateException → 500` 收敛到一处，`{ "error": message }` 形状不变；`AsrController` 的局部 handler 删除。`AsrControllerTest` 的断言应保持通过。

## 八、错误处理与错误信息

沿用 `error-handling.md`：卫语句前置；**错在调用方 → `IllegalArgumentException`，错在环境/外部系统 → `IllegalStateException`**；不建自定义异常类；消息中文为主、专有名词保留原文。

需要写好的几条消息（都含「缺什么 + 怎么补」）：

| 场景 | 消息形状 |
|---|---|
| 工具不存在 | `未找到 yt-dlp：请设置 vidsage.tools.yt-dlp-path 或将 yt-dlp 放入 PATH；本项目自带二进制在 tools/yt-dlp.exe` |
| ffprobe 缺失 | `未找到 ffprobe：请设置 vidsage.tools.ffprobe-path 或加入 PATH；它用于读取媒体的时长与音轨信息`（工具缺失消息三条一套：yt-dlp / ffmpeg / ffprobe，形状一致） |
| yt-dlp 过期 | `yt-dlp 版本 2026.08.19 距今 37 天，超过 90 天阈值；站点解析规则变化会导致下载失败，请更新 tools/yt-dlp.exe（不自动更新）` |
| ffmpeg 未配置且不在 PATH | `未找到 ffmpeg：请设置 vidsage.tools.ffmpeg-path 或加入 PATH；该路径同时供 Java 抽音频与 yt-dlp --ffmpeg-location 使用` |
| 下载失败 | 附**完整 stderr**：`yt-dlp 下载失败（exit=1）：<stderr 全文>`（研究 §二：文案匹配规则要保守，命不中就归「未知失败 + 完整 stderr」） |
| 抽音频失败 | `ffmpeg 抽音频失败（exit=1）：<stderr 尾部 20 行>` |
| 编码器缺失 | **按失败的规格分别生成，不许写死**：M4A 失败 → `该 ffmpeg 构建不支持 aac 编码器：请把 vidsage.tools.ffmpeg-path 指向完整构建（容器里的精简构建常缺），或改用 AudioSpec.WAV_PCM_16K_MONO`；WAV 失败 → 只提换完整构建（**不许再建议「改用 WAV」——那正是失败的规格**） |
| 音频轨不存在 | `ffmpeg 抽音频失败（exit=-22）：Failed to set value '0:a:9' for option 'map': Invalid argument；音频轨不存在，请检查 AudioSpec.audioTrackIndex` —— **透传 ffmpeg 原文**。退出码取实测值 `-22`（AVERROR(EINVAL)，见下）；实测该报错**不含总轨数**（研究 §8.3），所以不写「共 N 条」这种拿不到的信息 |
| 本地文件不合法 | `本地媒体文件不存在或不可读：<绝对路径>；请检查路径，或改传 url` |
| 超时 | `外部命令执行超时（300 秒）：yt-dlp；可用 vidsage.tools.default-timeout-seconds 调整`（秒数取**持有该值的那一处**，不要散成两处） |
| HTTP：来源缺失 | `缺少媒体来源：请传 url（公网媒体页面地址）或 localPath（服务端可读的本地媒体文件路径）` |
| HTTP：来源冲突 | `url 与 localPath 不能同时传：请恰好传其中一个` |
| HTTP：路径写法非法 | `localPath 不是合法路径：<原值>；请检查路径写法（Windows 下 < > : " | ? * 为非法字符），或改传 url`（把 JDK 的英文 `InvalidPathException` 换掉，遵「错误信息中文为主」；**这是实现落地时的版本，比初稿多了「怎么补」，已回写对齐**） |

**文案匹配的边界（精确表述，取代原先「一律不做分类」的说法）**：研究 §二明确「文案是外部实现细节、会随版本变」，所以**不许凭猜写规则**。但有两条例外是本设计明确要求、且**实测过真实文案**的：编码器缺失（`Unknown encoder`）与 `-map` 越界（`option 'map'`）。这两条做**保守匹配**——命中给专门消息，**命不中即回退「未知失败 + stderr 尾部」**，绝不因匹配失败影响正常路径。其余一切失败一律「未知失败 + stderr 尾部」，等真实案例攒够再按实测输出收敛。

**失败时的信息分层**：

| 去处 | 内容 | 理由 |
|---|---|---|
| 面向用户的异常消息 | 中文说明 + stderr **尾部 20 行** | 可操作、有界（PRD 原写「完整 stderr」已按此修正） |
| DEBUG 日志 | **完整 stderr** | 深挖用；错误消息里塞上百行输入流转储反而埋掉真正的错误行 |
| 执行器的 WARN | 可执行文件名 + 退出码 + 耗时 + stderr 尾部 20 行 | 见 §五 的脱敏取舍 |

**退出码可能为负**：Windows 的退出码是无符号 DWORD，ffmpeg 用 `AVERROR(EINVAL) = -22` 这类**负值**返回参数错误，Java 的 `exitValue()` 因此会给出 `-22`（实测，研究 §8.6-8）。消息里**原样透出**该值——它对懂 ffmpeg 的人有意义（-22 即 EINVAL），**不做无符号归一化**。这也是研究 §二「退出码语义极弱」的又一例证。

**「不算失败、但必须可见」的输出是一类独立情况**（实测推翻了初稿假设）：yt-dlp 的格式不可用提示（B站 会员限制 `Format(s) 1080P 高码率 are missing…`）既**不在 stderr**、也**没有 `WARNING:` 前缀**——它在 **stdout** 上、以 `[BiliBili]` 开头，而且 **exit 0**。初稿只写「扫 stderr 的 WARNING 行」，**会漏掉这条最影响用户感知的通知**（画质莫名变差）。落地为两条并扫：
- stderr 的 `WARNING` 行
- **stdout 的格式不可用提示**（保守子串匹配，命不中不影响正常路径）

两者都**不改变成功/失败判定**，只记 WARN 日志。这条与「文案匹配要保守」不冲突：匹配失败只是少一条提示，不会误判成败。

## 九、配置项（`application.yml` 新增块）

```yaml
vidsage:
  tools:
    # 留空则从 PATH 解析；容器里 ffmpeg 未必在 PATH，交付时必须显式配置
    yt-dlp-path: ${YT_DLP_PATH:}
    # 一处配置、两处使用：Java 抽音频 + yt-dlp --ffmpeg-location
    ffmpeg-path: ${FFMPEG_PATH:}
    ffprobe-path: ${FFPROBE_PATH:}
    default-timeout-seconds: 300
    yt-dlp-freshness-warn-days: 90
  workspace:
    root: ${VIDSAGE_WORKSPACE_ROOT:./data/works}
  download:
    yt-dlp:
      # B站未登录时的风控边界未实测；需要时指定 cookies 文件
      cookies-file: ${YT_DLP_COOKIES_FILE:}
```

- **不写本机绝对路径进仓库**（研究 §七 的本机路径只作为文档记录），默认空 = 从 PATH 解析
- 密钥/路径一律 `${ENV:default}` 插值，`provider-pattern.md` 同一条精神

## 十、待实测项（实现时验证，结论写回 research）

| # | 待验 | 状态 |
|---|---|---|
| 1 | info json 里定位下载产物用哪个字段 | **已实测（2026-09-25）＝三项字段全缺**（`filepath` / `_filename` / `requested_downloads`）→ 定位改靠固定输出模板 + `ext`，见 §六 与笔记 §8.6-4 |
| 2 | `--ffmpeg-location` 吃目录还是二进制路径 | **已实测：两种都行**（各跑一次合并均成功）。我们传解析到的二进制路径，与「一处配置两处使用」一致 |
| 3 | YouTube 是否被 `JS runtimes: none` 阻塞 | **已实测（2026-09-25）＝比推断严重**：无 JS 的 YouTube 提取已被官方弃用，且被 bot 检测拦下（`Sign in to confirm you're not a bot`），需 cookies。结论与研究原始输出见笔记 §8.1；**是否读用户浏览器 cookies 属隐私决策，待用户定** |
| 4 | `-af aresample=async=1:first_pts=0` 是否必要 | **待对拍**：先不加，拿真实视频测时间戳漂移后再决定 |
| 5 | `destroyForcibly` 对 yt-dlp → ffmpeg 进程树的实际效果 | **未验**：超时是否留孤儿进程（已由有界 drain 保证不挂死，这一项只剩「会不会留残留进程」） |
| 6 | `--write-info-json` 的默认文件名与实际 `-o` 模板的组合结果 | **已实测**：info json 名由 `-o` 模板派生（`%(ext)s` → `info.json`），`<id>` 是 bvid；中间产物合并后自动删除，目录只剩最终产物 + info json。见笔记 §8.6-3 |
| 7 | 默认音频流选择会不会取错轨 | **已实测（2026-09-25）＝会**：默认选择跟随 `default` disposition，标在第 2 轨就抽第 2 轨。见笔记 §8.2 → 决策 5 由推断升级为结论 |
| 8 | `audioTrackIndex` 越界时 ffmpeg 的报错文案 | **已实测**：`Failed to set value '0:a:9' for option 'map'`，**不含总轨数** → §八 那条消息改为透传原文 |
| 9 | 真实 B站 文件的音轨情况 | **已实测（2026-09-25）**：所测那条**只有 1 条音轨**（aac 立体声、`default=1`）。多音轨常见但非必然 → §8.2 的合成文件结论仍是显式 `-map` 的硬依据，本例的 `default=1` 印证「disposition 驱动选择」机制 |
| 10 | B站 登录 / 风控边界（epic 未决问题） | **部分实测**：未登录可下载普通清晰度；**1080P 高码率需会员**（`Format(s) 1080P 高码率 are missing; you have to become a premium member`）。字幕路径的风控边界留给 2b |
| 11 | 2b 的素材缺口 | 所测 B站 视频 `subtitles: []`（无 CC 字幕）→ 2b 需要另找一条**真有 CC 字幕**的视频作「平台字幕优先」的测试素材 |

**步骤 0 的待验项到此全部结清**（1/2/3/6/7/8/9 + 新增 10/11）。仅剩第 4 项（时间戳对拍）与第 5 项（孤儿进程残留）留待后续，且第 5 项已由有界 drain 保证「不挂死」这一硬要求。

## 十一、回滚与兼容

- **纯新增**：新包、新配置项、新端点。不改任何既有 API 的行为（唯一触点是把 `AsrController` 的局部异常处理提到 advice，响应形状不变）
- 无数据库、无迁移、无外部状态；`data/works` 是本地可删目录
- 回滚 = 还原分支；残留只有 `data/works` 目录
- 向前兼容：`MediaAcquirer` / `AudioExtractor` 两个接口是 2b 的接入点；2b 只消费 `AcquiredMedia` / `AudioArtifact` / `AudioSpec` / `ExternalProcessRunner`，不碰 ffmpeg 参数与工具路径

## 十二、研究索引的使用纪律与许可证红线

`oss-references.md` 开篇自己写明：**它是索引，不是实现说明**；条目里的机制描述是导航线索，省略了源码中的边界处理与错误分支，**动手写代码前必须按条目给出的路径打开源码读原文，只读摘要等于二手转述**。

（本设计初稿曾把这个索引当规格用，漏掉了第 15 条的 `audio_track_index` 参数——多音轨视频会静默选错轨。教训即上段那句话。）

本任务相关的原文在 **`F:\learning_projects\asr_references\`** 下（不在本仓库）：

- `VideoCaptioner/videocaptioner/core/utils/video_utils.py` —— `video2audio` / `get_video_info`
- `VideoLingo/core/asr_backend/audio_preprocess.py` —— 抽音频的补充参数（如 `-af aresample=async=1:first_pts=0`）

**许可证红线（不可绕）**：

| 项目 | 许可证 | 对本任务的含义 |
|---|---|---|
| **VideoCaptioner** | **GPL-3.0** | **一行源码都不能抄**。它恰好是「ffmpeg 抽音频」与「平台字幕」两处的首选参考——凡是参考它的地方，只能**读懂后用自己的话重写**。vidSage 大概率公开，GPL 传染会污染整个仓库 |
| VideoLingo | Apache-2.0 | 可搬运，需保留版权与许可声明 |
| AI-Media2Doc | MIT | 同上 |

本任务**明确不搬运任何参考项目源码**：它是 Java，三个参考项目全是 Python/TS，本就只能抄设计与契约。这条纪律写给 implement / check 子 agent：**若发现自己正在逐行翻译 Python 源码，停下来。**
