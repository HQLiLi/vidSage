# MVP-2b 音频转字幕（ASR → SRT）— 技术方案

> 配套 `prd.md`（需求与验收）与 `implement.md`（执行顺序）。
> 依赖 2a 冻结的接口面，2a 已合入 `dev`（`cc111b8`）并归档。
>
> **本任务已把平台字幕路线整体移出**（见 `prd.md` 规划期决策 3）。设计里凡是提到「路线」的地方，都按四值枚举预留，但**本轮只实现 `asr-cloud` 一条**。

---

## 一、边界与契约

### 本任务拥有

| 类型 | 位置 | 职责 |
|---|---|---|
| `SubtitleRoute` | `model/subtitle/` | 字幕来源路线标记（四值枚举） |
| `SubtitleTrack` | `model/subtitle/` | 一份字幕的内容：路线 + 语种 + 逐句段落；**就是落盘的 `segments-*.json` 的形状** |
| `SubtitleArtifact` | `model/subtitle/` | 本任务的产出描述：两个产物路径 + 路线 + 语种 + 句数 + 字节数 |
| `SubtitleService` | `service/` | 编排：要音频 → 转写 → 写两个产物；断点复用 |
| `SubtitleController` | `web/controller/` | 临时 HTTP 面（见 §七） |
| `PrepareSubtitleRequest` / `PreparedSubtitleResponse` | `web/dto/` | 上述临时面的 HTTP 契约 |

### 本任务改动既有代码（唯一的接口变更）

`ASRProvider` 加两个方法，`DashScopeAsrProvider` 实现之。理由与取舍见 §三 决策 1。

**另有一处行为变更**：`DashScopeAsrProvider` 需把 `FAILED` + `code=ASR_RESPONSE_HAVE_NO_WORDS` 从「抛 `IllegalStateException`」改为「返回空 segments 的 `TranscriptionResult`」，否则决策 5 在云端不可达（见 §三 决策 5 的云端实现注记）。这属 provider 内部实现改动，不动接口签名。

### 本任务复用、不改动

`MediaInput` / `AcquiredMedia` / `AudioSpec` / `AudioArtifact` / `AudioPreparationService` / `WorkspaceService` / `TranscriptionService` / `StageEvent` / `ProgressListener` / `TranscriptionSegment`。

**不碰**：ffmpeg 参数、外部工具路径、`ExternalToolProperties`、`YtDlp*`（本轮不涉及 yt-dlp）、工作目录内的音频文件名规则。

---

## 二、包结构

```text
com.vidsage
├── model/
│   ├── asr/            # 已有
│   ├── media/          # 已有
│   └── subtitle/       # 新增：SubtitleRoute、SubtitleTrack、SubtitleArtifact
├── provider/
│   └── asr/
│       ├── ASRProvider.java          # 改：+2 方法
│       └── dashscope/DashScopeAsrProvider.java   # 改：实现这 2 个方法
├── service/
│   └── SubtitleService.java          # 新增
└── web/
    ├── controller/SubtitleController.java        # 新增
    └── dto/{PrepareSubtitleRequest,PreparedSubtitleResponse}.java   # 新增
```

判据依据 `directory-structure.md`：

- `model/` 要**领域二级包**（类名不带机制前缀，平铺会混杂）→ `model/subtitle/`
- `provider/asr/` 已有领域包，接口留在包根、实现进 `dashscope/`
- `service/` **平铺**——`SubtitleService` 类名自带领域语义，不再套一层
- `web/` 按**角色**分子包（controller / dto 两条独立轴）
- **目录随代码出现**：`model/subtitle/` 由第一个类带出，不预建空目录
- 本任务**不新建** `provider/subtitle/`：SRT 落盘是纯变换，不是「同一能力多种来源」的出站实现，不构成 Provider 四件套的前提

---

## 三、关键设计决策

### 决策 1：音频规格与路线由 `ASRProvider` 自述（加两个方法），不做独立映射表

```java
public interface ASRProvider {
    TranscriptionResult transcribe(TranscriptionCommand command);

    /** 本实现需要的音频规格；上层据此向音频准备阶段要音频，管线里不写死容器名。 */
    AudioSpec requiredAudioSpec();

    /** 本实现对应的字幕来源路线标记。 */
    SubtitleRoute route();
}
```

**为什么不把 `AudioSpec` 写死在 `SubtitleService` 里**：当前只有云端一个实现，写死 `M4A_AAC_48K_MONO` 也能跑通——但那是把「要什么格式」这个实现相关的判断散进管线，正是 epic design 明确否掉的形态。加本地实现时会出现「管线里躺着一个只对云端成立的常量」这种静默错误。

**为什么不做独立映射表**（如 `Map<provider, AudioSpec>`）：映射与实现分离后，新增 provider 会忘记改映射，且失败是静默取错格式。放在接口上，漏实现直接编译不过。

**为什么不拆成两个 `@Bean` 或配置项**：能让配置写错的东西就不要做成配置——配置里的非法组合比代码里的编译错误晚得多才暴露。

> **关于「跨包」的澄清（2026-09-26，经所有者追问后订正）**：
>
> **跨包引用不是问题，分层本身依赖它。** 「按技术分层」规定的是**类住在哪个包**，不规定谁能引用谁；被约束的是**引用方向**（`service/` 不得依赖实现类、`provider/` 不得反向依赖 `service/`、SDK 类型不得外泄到公开签名）。既有代码即是证据：`ASRProvider.transcribe(...)` 返回的 `TranscriptionCommand` 就在 `model/asr/`，**本身就是跨包**。
>
> `provider-pattern.md` §1 那句「方法只接收和返回本包定义的契约类型」，其**规范意图**写在同一节与反模式表里——「**不出现 SDK 类型**」，原因是「换实现时要改上层」。它防的是 SDK 类型，不是跨包。本决策的两个返回类型都是本项目自己的中立契约，不含任何 SDK 词汇，不损可替换性。
>
> **`AudioSpec` 一条根本不算破例**：`model/media/AudioSpec` 的 Javadoc 原文写着「本环节不猜测『要什么规格』，一律由调用方传入（**例如由 ASR provider 声明它需要 WAV 还是 M4A**）」——这个跨领域引用方向是当初设计该类型时就预留好的。
>
> 例外**只有 `SubtitleRoute` 一处**需要判断：它算不算 ASR 环节的契约。本设计的答案是**算**——`ASR_CLOUD` / `ASR_LOCAL` 本就在命名 ASR 的实现；更重要的是四条路线是「这份字幕哪来的」这**同一个问题**的答案，拆成两个枚举等于同一概念两处真相源，将来接平台字幕时还得在服务层补映射。方向上 `provider/asr` → `model/subtitle` 也是「出站实现依赖中立契约」，与 `provider/asr` → `model/asr` 同一条方向，无倒置。

### 决策 2：字幕内容契约放 `model/subtitle/`，段落复用 `TranscriptionSegment`

```java
public record SubtitleTrack(SubtitleRoute route, String language,
                            List<TranscriptionSegment> segments) { }
```

**为什么不新建 `SubtitleSegment`**：字段（`beginTimeMs` / `endTimeMs` / `text`）与 `TranscriptionSegment` 完全一致，复制一份就是 provider-pattern 反模式表里那条「为第二个实现复制一份契约类」。本轮唯一来源就是 ASR，`TranscriptionSegment` 的名字此刻是准确的。

**将来接平台字幕时的已知后果**：那时段落的来源不再只是「转写」，`TranscriptionSegment` 这个名字会偏。届时的处理是**改名**（纯机械重命名，无行为变化），而不是新增一个并行类型。此后果在此显式记账，免得将来被当成设计失误。

**`segments` 用 `List.of()` 表达空，不返回 `null`**（`provider-pattern.md` 契约约定）。

### 决策 3：路线标记四值一次定死，本轮只产出 `ASR_CLOUD`

```java
public enum SubtitleRoute {
    PLATFORM_MANUAL,   // 平台人工字幕（本轮不产出）
    PLATFORM_AUTO,     // 平台自动字幕（本轮不产出）
    ASR_LOCAL,         // 本地 FunASR（本轮不产出）
    ASR_CLOUD          // 云端 DashScope
}
```

**为什么保留两个本轮产不出的值**：路线标记会写进产物 JSON 的取值域。将来加平台字幕时若从两值扩到四值，那是**改契约**——已落盘的产物、MVP-3 的消费代码、评测脚本都可能要跟着改。一次定死四个值，将来只加产线、不改取值域。

> **与「不为将来造抽象」的关系（诚实记录）**：这与项目一贯纪律有张力，是**用户明确拍板**的取舍（`prd.md` 规划期决策 2）。区别在于成本：这里多的是两个枚举常量，不是一层接口或一个类；而收益是避免契约取值域变更。记录在案，便于将来复盘这次判断。

### 决策 4：产物名带路线指纹；**检查点选 segments JSON，不选 SRT**

产物名：

```
subtitles-<路线 token>.srt      # 如 subtitles-asr-cloud.srt
segments-<路线 token>.json      # 如 segments-asr-cloud.json
```

token 由枚举名派生（`ASR_CLOUD` → `asr-cloud`），派生规则只有服务层知道——与 `AudioPreparationService.audioFileName` 同构，那里也是从 `AudioContainer.name()` 派生出 `.m4a`。

**为什么必须带路线指纹**：与 2a 同一条理由。工作目录按媒体内容寻址（`WorkspaceService.workspaceFor`），同一个媒体下唯一会变的就是「谁转写的」。若产物叫 `subtitles.srt`，把 `vidsage.asr.provider` 从 cloud 换成 local 后重跑，会命中上一次云端产出的文件被当成已完成而跳过，**返回一份与当前配置不符的字幕且不报错**。

> token 用 kebab（`asr-cloud`）、JSON 里的路线值用枚举名（`ASR_CLOUD`），两者风格不同不是疏忽——与既有的 `AudioContainer` 一致：契约里是 `M4A`，文件名里是 `.m4a`。同源、两种媒材各自的惯例。

**为什么检查点是 JSON 而不是 SRT**：

2a 传下来的断点判据是「产物存在且**字节数 > 0**」（0 字节视为不存在）。但**空转写会产出 0 字节的 SRT**（没有 cue 的 SRT 就是空文件，见决策 5）。若拿 SRT 当检查点，空转写这条路径会每次都判「未完成」→ 反复重下、重抽音频、重调 ASR，每次都得到同样的空结果——一个不会自愈的死循环。

`segments-*.json` 在任何情况下都非空（至少含 `route` / `language` 两个字段），所以它是可靠的检查点。**SRT 是 JSON 的派生视图**，两者同一次写入产出。

### 决策 5：空转写是**合法产出**，不是错误

ASR 返回 `segments` 为空时：写 0 字节的 SRT + 内容正常的 JSON（`segments: []`），发 `SUCCEEDED` 事件（阶段本身确实成功了），并记 **WARN** 日志说明「ASR 未识别出任何内容，可能是纯音乐或无语音音频」。

**为什么不当成失败**：`error-handling.md` 的判据是「合法的『没有内容』→ 空集合；不该出现的『没有内容』→ 抛异常」。纯音乐 / 无人声的视频是**合法输入**，ASR 对它给出空结果是正确行为，不是服务故障。把它报成 500 会让用户以为系统坏了。

**为什么不静默**：对 vidSage 的目标场景（知识类视频），空字幕几乎总是「用户传错了东西」的信号。产出如实落盘 + WARN 日志，既不掩盖也不误报。

> **云端实现注记（2026-09-26 步骤 0 实测后补）**：DashScope **不会**直接返回空 `segments`——对静音 / 无语音音频，它返回的是**任务失败** `code=ASR_RESPONSE_HAVE_NO_WORDS`（`taskStatus=FAILED`）。因此「空 segments」这条合法产出的前提，在云端要靠 **`DashScopeAsrProvider` 把它降级成空结果** 才成立：检测到 `FAILED` 且 `output.code == "ASR_RESPONSE_HAVE_NO_WORDS"` 时，返回 `TranscriptionResult(List.of(), null)`，**不抛异常**（错误码可从 `getOutput()` 的 `JsonObject` 结构化取到，非字符串匹配）。这样「无语音 → 空 SRT + WARN」在云端也成立，且对将来本地 FunASR 的静音行为套同一契约。见 `research/asr-cloud-behavior.md` §四。

### 决策 6：把 2a 的进度区间线性映射进本任务的区间

`AudioPreparationService` 自己发 `acquire` 与 `extract-audio` 两个阶段，进度是绝对的 `0 → 50 → 100`。若把调用方的 `ProgressListener` 直接透传给它，用户会在 ASR 还没开始时就看到 100%。

处理：本任务把 2a 的 `0..100` 线性映射进 `0..60`，自己的两个阶段接在后面。

| 阶段 | 区间 | 谁发 |
|---|---|---|
| `acquire` | 0 → 30 | 2a（经映射） |
| `extract-audio` | 30 → 60 | 2a（经映射） |
| `transcribe` | 60 → 95 | 本任务 |
| `write-subtitles` | 95 → 100 | 本任务 |

> 两套权重都是**过渡态**。2a 的 design 已写明「真正的进度形状应由 MVP-1 的异步管线 / SSE payload 定锤」，本任务的映射只是让现在不出现倒退的进度条，**不构成 MVP-1 的约束**。

### 决策 7：SRT 自己写，不引字幕库；写盘用临时文件 + 原子替换
> 本条经项目所有者拍板确认（2026-09-26），非仅由实现方提议。

**不引库**：本任务**只写不读**——ASR 给出的是结构化的 `TranscriptionSegment`，不需要解析 vtt / json3 / ass 任何一种文本格式，只需要把毫秒格式化成 `HH:MM:SS,mmm` 并拼块。整段实现约 15 行（序号 / 时间行 / 文本 / 空行四行循环 + 一个 `String.format`）。

**这一条已实测核实过，不是推断**（2026-09-26，Maven Central 直连查证）：

| 候选 | 结论 |
|---|---|
| `fr.noop:subtitle:0.9.0` | 能写 SRT，在 Maven Central 上。但**许可证是 LGPL-3.0**（撞本项目「只引 Apache-2.0 / MIT / BSD」的红线）；Central 上只有这一个版本，编译目标 **Java 1.7**，是 2015 年的 CLI 转换工具而非库；自带 `fr.noop:charset` + `commons-lang3:3.4` + `commons-cli:1.3.1` 三个老依赖 |
| `fredyw/jsrt` | MIT，但是 Gradle 项目、以解析/编辑为主，**未在 Maven Central 上找到** |

**关键判据不是「库能不能写」，而是「引库省下了什么」**：我们的输入类型是 `TranscriptionSegment`，库认的是它自己的模型（如 `SrtObject`），**映射代码无论如何都要自己写**。引库省掉的只有那 15 行格式化，换来一个新依赖 + 3 个传递依赖 + 一次 LGPL 例外审批 + 一个 Java 7 编译产物。这笔账是亏的。

> 此结论**只对本任务成立**。库的真正价值在**读**不在写：将来接平台字幕时要解析 vtt / json3，自动字幕的 VTT 有滚动重复行、零时长 cue、内联时间标签，那才是有大量边界处理、值得找库的地方。届时必须重做一轮「引库 vs 自写」的调研，且必须查许可证（`VideoCaptioner` 是 GPL-3.0，只可读思路不可抄代码）。

**原子替换**：与 2a 的 `AudioArtifact` 同一条纪律——「路径存在即代表文件完整」。先写 `<name>.tmp`，再原子替换到目标名。否则断电 / 崩溃会留下「非空但被截断」的半成品，而按字节数 > 0 的判据它会被当成已完成。

---

## 四、数据流

```text
MediaInput（URL 或本地路径）
   │
   ├─ AudioSpec spec = asrProvider.requiredAudioSpec()        ← 决策 1
   │
   ├─ AudioPreparationService.prepare(input, spec, 映射后的 listener)
   │     └─> AudioArtifact（音频文件路径）
   │
   ├─ TranscriptionService.transcribe(AudioFilePath(音频路径), null)
   │     └─> TranscriptionResult（segments + language）
   │
   ├─ SubtitleTrack track = new SubtitleTrack(asrProvider.route(),
   │                                          result.language(), result.segments())
   │
   └─ 写两个产物（原子替换）
         ├─ segments-asr-cloud.json   ← SubtitleTrack 序列化，检查点
         └─ subtitles-asr-cloud.srt   ← 由 track 渲染，0 句时为 0 字节
```

**语种提示一律传 `null`**：本任务的临时 HTTP 面不暴露 language 参数，交给模型自动判断。理由与 `MediaController` 不开放采样率 / 声道自由组合一致——当前没有真实需求，开放参数只会引入非法输入面。

---

## 五、产物格式

### `segments-<token>.json`

直接序列化 `SubtitleTrack`（缩进美化，便于人工核对与留证据）：

```json
{
  "route" : "ASR_CLOUD",
  "language" : "zh",
  "segments" : [ {
    "beginTimeMs" : 0,
    "endTimeMs" : 3520,
    "text" : "今天天气不错"
  } ]
}
```

- 不引入自定义序列化器，不手拼 JSON——用注入的 `ObjectMapper`（**Boot 4 是 Jackson 3，包名 `tools.jackson.databind.ObjectMapper`**，`com.fasterxml.jackson.databind` 那个在 Boot 4 里没有 bean，注入会直接失败）
- 时间单位恒为毫秒（`TranscriptionSegment` 的既有约定）
- 该文件是 MVP-3 的输入，**形状即契约**，改动需走接口变更流程

### `subtitles-<token>.srt`

```text
1
00:00:00,000 --> 00:00:03,520
今天天气不错

2
00:00:03,520 --> 00:00:07,040
明天也是
```

- 序号从 1 起，块间空一行，`\n` 换行
- 时间戳 `HH:MM:SS,mmm`（逗号分隔毫秒，SRT 的规范形式）
- **不做任何显示层打磨**：不断句、不拆分超长行、不做 `optimize_timing` 那类消闪烁调整（`prd.md` 明确排除）
- 句数为 0 时是 **0 字节文件**（决策 5）

---

## 六、工作目录与断点复用

沿用 2a 的约定，产物落 `WorkspaceService.workspaceFor(input)` 返回的同一目录（该目录按媒体内容寻址）。

```text
<workDir>/
├── source.mp4                    ← 2a：下载或本地文件
├── audio-16k1ch48k-t0.m4a        ← 2a：抽取的音频
├── segments-asr-cloud.json       ← 2b：检查点
└── subtitles-asr-cloud.srt       ← 2b：字幕
```

**判据**：`segments-<token>.json` 存在且字节数 > 0 → 跳过整个 `transcribe` 与 `write-subtitles`，直接据已有 JSON 反序列化出 `SubtitleTrack` 并返回 `SubtitleArtifact`。

> **注意这里与 2a 的一处不对称**：2a 的 `reuseExisting` 由适配器实现（只有它知道文件名推导规则），本任务的判据由 `SubtitleService` 自己判——文件名规则只有它知道。两者共同点是「谁定的名字谁判复用」。

跳过时发 `SKIPPED` 事件并记 **INFO** 日志（含文件名与字节数）：断点复用必须可观测，否则「跳过了」与「没跑」分不出来。

---

## 七、HTTP 层（临时面，明确标记）

`POST /api/subtitles`

```json
{ "url": "http://www.bilibili.com/video/av...", "localPath": null }
```

响应（由 `SubtitleArtifact` 展平而来——它含 `Path` 与枚举，形状不可直接对外，故走 `web/dto/`）：

```json
{
  "route": "ASR_CLOUD",
  "language": "zh",
  "segmentCount": 12,
  "srtPath": "…/works/…/subtitles-asr-cloud.srt",
  "segmentsPath": "…/works/…/segments-asr-cloud.json",
  "srtSizeBytes": 1024
}
```

**与 `POST /api/media/audio` 同一条免责声明**：这是**同步**端点，存在的唯一理由是在 MVP-1（任务提交 / 落库 / SSE）尚未落地时提供一个能被真实调用的端到端入口。长视频会顶到 HTTP 客户端超时；真正的形态是 MVP-1 的异步管线，**该管线落地后本端点应改为它的触发入口或直接删除**。因此本控制器不引入任务 ID、不落库、不排队。

**边界卫语句必须在 HTTP 边界、早于一切 I/O**（`error-handling.md` 的硬规则，2026-09-26 实测对照换来的）：`url` 与 `localPath` 二选一、非空白、`localPath` 是合法路径——全部在 Controller 里判完再调服务。否则参数错误会被前面的下载 / 抽音频失败掩盖成 500。

`audioTrackIndex` **不在本端点的契约里**：音频规格由 provider 声明（决策 1），当前恒为轨 0。多音轨选择是 2a 已单独开放的能力，本任务的定位是「一键出字幕」，不重复暴露。

---

## 八、错误处理

沿用 `error-handling.md` 的「谁之过」判据，不新增自定义异常类。

| 情形 | 异常 | 底层 | 状态码 |
|---|---|---|---|
| 请求体 url/path 都传或都不传、为空白、路径非法 | `IllegalArgumentException`（Controller 卫语句） | 400 | WARN |
| 媒体获取 / 抽音频失败（工具缺失、外部命令非零退出） | 由 2a 抛 `IllegalStateException`，**原样上抛不吞** | 500 | ERROR |
| ASR 配置缺失（无 API key）、外部服务未按预期返回 | `IllegalStateException`（`DashScopeAsrProvider` 既有） | 500 | ERROR |
| 产物写入失败（磁盘、权限、序列化） | `IllegalStateException`（包装 `IOException` / `JacksonException`） | 500 | ERROR |
| **ASR 返回空 segments** | **不抛**，合法产出 + WARN 日志 | 200 | WARN |

错误信息一律「中文说明 + 专有名词保留原文」，且必须含「缺什么 + 怎么补」（如「字幕产物写入失败：<路径>；请检查磁盘空间与目录写权限」）。

**日志脱敏**：按 `logging-guidelines.md`，不打媒体完整 URL、不打转写全文、只打句数 / 字节数 / 耗时等度量；`SubtitleTrack` 这类含用户内容的 record **整体不进日志**。

---

## 九、配置项

**本任务不新增任何配置项。**

- ASR 走哪家：已有 `vidsage.asr.provider`
- DashScope 的 key 与模型：已有 `vidsage.asr.dashscope.*`
- 工作目录根、工具路径：已有

这是刻意的结果而非巧合——音频规格由 provider 在代码里声明（决策 1），产物名由服务层从枚举派生（决策 4），没有需要用户调的东西。

---

## 十、待实测项（实现时验证，结论写回 `research/`）

1. **DashScope 对 16k 单声道 M4A 的实际接受度**——`docs/prd/local-model-deployment.md` 记的是 `sample.m4a` 上传转写通过，但那次的规格与 `AudioSpec.M4A_AAC_48K_MONO` 是否逐字段一致未核。**必须用 2a 实际抽出的 `.m4a` 跑通一次**，不能拿旧结论替代。
2. **`language` 字段可能为空**——`TranscriptionResult.language()` 为 `null` 或空白时，`SubtitleTrack.language` 怎么表达？倾向直接透传 `null` 并在 JSON 里落 `null`（而不是空串），但这会让 MVP-3 多一处判空。实测确认 DashScope 是否真的可能不给。
3. **异常段落**：`beginTimeMs > endTimeMs`、时间戳为负、单句文本为空白——DashScope 是否会产生？产生时是照搬进 SRT 还是归一？先实测，再定要不要处理。
4. **端到端真实运行**：一条 B站 链接 + 一个本地 mp4 + 一个本地纯音频，各产出 SRT（真运行证据）。
5. **断点复用实跑**：同一输入重放，确认不重下、不重抽、不重调 ASR。
6. **换 provider 不误用产物**：把 `segments-asr-cloud.json` 改名成 `segments-asr-local.json`，重跑确认**不会**命中它（验证路线指纹真的生效，而不是「恰好没被检查」）。

---

## 十一、回滚与兼容

**回滚**：删掉 `model/subtitle/` 的 3 个类、`SubtitleService`、`SubtitleController`、2 个 DTO；还原 `ASRProvider` 的两个方法与 `DashScopeAsrProvider` 的实现。既有 HTTP 契约（`/api/media/*`、`/api/asr/*`）一字不动，回滚不影响它们。

**兼容影响**：

- `ASRProvider` 加两个抽象方法 → 所有实现必须实现。当前只有 `DashScopeAsrProvider` 一个，改一处即可；测试里若有匿名实现也要补
- **接口加方法属接口变更**：`prd.md` 已记载，将来写 `LocalFunAsrProvider` 时这两个方法就是它的必答项
- `subtitle` 是新的领域子包，与既有包零冲突

---

## 十二、被移出本任务的范围

平台字幕整条路线（`yt-dlp --write-subs` / `--write-auto-subs`、vtt/json3 归一、语言选择策略、B站 cookies）。实测结论已留档在 `research/platform-subtitle-formats.md`，要点摘录见 `prd.md`「被移出的范围」。

**本设计为它留的唯一接口位**是 `SubtitleRoute` 的两个 `PLATFORM_*` 值（决策 3），此外不做任何预留——不建 `provider/subtitle/`，不抽「字幕来源」接口。将来重启时新开任务，届时按当时的真实需求决定要不要抽。
