# Java 调外部进程的集成笔记（yt-dlp / ffmpeg）

> 用途：MVP-2 计划由 Java `ProcessBuilder` 直接调 `yt-dlp.exe` 与 `ffmpeg.exe`。
> 本文记录 2026-09-25 在本机（Windows 11 中文版 + Java 21.0.8）实测得到的坑与结论。
>
> **凡标「实测」的都附命令与原始输出；标「未验」的是推断，用前必须自测。**

---

## 一、子进程输出编码：yt-dlp 吐 GBK，Java 21 默认按 UTF-8 解 → 中文静默乱码

**这是本文最重要的一条。** 直觉会以为「yt-dlp 是跨平台工具，输出应该是 UTF-8」—— 不是，它跟随系统 locale；而 Java 18+ 又把默认字符集固定成 UTF-8。两边恰好错开。

### 实测：yt-dlp 的输出编码

```bash
$ ./tools/yt-dlp.exe -v --simulate asr-service/sample.m4a 2>&1 | grep Encodings
[debug] Encodings: locale cp936, fs utf-8, pref cp936, out gbk (No ANSI), error gbk (No ANSI), screen gbk (No ANSI)
```

光看 debug 行还不够，直接验字节：

```bash
$ ./tools/yt-dlp.exe --simulate "中文abc" 2>&1 | od -An -tx1 | head -4
 5b 67 65 6e 65 72 69 63 5d 20 45 78 74 72 61 63
 74 69 6e 67 20 55 52 4c 3a 20 d6 d0 ce c4 61 62
 63 0a 45 52 52 4f 52 3a 20 5b 67 65 6e 65 72 69
 63 5d 20 27 d6 d0 ce c4 61 62 63 27 20 69 73 20
#                          ^^^^^^^^^^^ 中文 = d6 d0 ce c4 → GBK
#                          （UTF-8 应为 e4 b8 ad e6 96 87）
```

**试过强制 UTF-8，无效** —— yt-dlp 自己绑定输出编码，不吃 `PYTHONIOENCODING`：

```bash
$ PYTHONIOENCODING=utf-8 ./tools/yt-dlp.exe --simulate "中文abc" 2>&1 | od -An -tx1 | head -4
# 字节与上面完全相同，仍是 d6 d0 ce c4
```

### 实测：Java 21 侧的编码

```bash
$ java -XshowSettings:properties -version 2>&1 | grep -iE "encoding|charset"
    file.encoding   = UTF-8    ← Java 21 默认字符集（JEP 400 起固定为 UTF-8）
    native.encoding = GBK      ← 操作系统原生编码
    stdout.encoding = GBK
    stderr.encoding = GBK
    sun.jnu.encoding = GBK
```

### 结论与正确写法

两边不一致 → **用 Java 默认字符集读 yt-dlp 的输出，中文标题必乱码**。

```java
// 对：跟随 OS 原生编码，跨平台自适应
Charset nativeCharset = Charset.forName(System.getProperty("native.encoding"));
String stdout = new String(bytes, nativeCharset);
```

- Windows 中文 → `GBK`；Linux 容器（`LANG=C.UTF-8`）→ `UTF-8`。**同一份代码两侧都对**
- **不要硬编码 `GBK`** —— 容器里会坏
- **不要用 `Charset.defaultCharset()`** —— Java 21 恒为 UTF-8，正是踩坑的写法
- `native.encoding` 是 JDK 18+ 的属性（本项目 Java 21，可用）。取不到时要有兜底
- 读 stdout 与 stderr **都要**这样处理

---

## 二、退出码语义极弱，错误分类只能靠 stderr 文案

| 场景 | 退出码 |
|---|---|
| 成功 | `0` |
| URL 无效 | `1` |
| `-U` 取版本信息失败（网络不通） | `100` |

`1` 是个「万能错误码」—— URL 不支持、平台风控、网络失败都可能是它。所以：

- **不要试图用退出码区分业务错误类型**
- 必须匹配 stderr 文案，而**文案属于外部实现细节、会随版本变** → 匹配规则要保守，命中不了就归为「未知失败」并**附完整 stderr**
- 具体匹配哪些文案，**须先实测收集真实失败案例的原始输出**，不要凭猜写。猜的规则改一次版本就失效

---

## 三、yt-dlp 不接受本地文件（实测）

```bash
$ ./tools/yt-dlp.exe --simulate asr-service/sample.m4a
[generic] Extracting URL: asr-service/sample.m4a
ERROR: [generic] 'asr-service/sample.m4a' is not a valid URL
exit=1
```

它是**下载器**，输入永远是 URL（会落到 `[generic]` extractor 上然后失败）。

**推论（对 MVP-2 是决定性的）：**「本地 mp4 / 音频 → 抽音频」这条路**只能 Java 自己调 ffmpeg**，不能指望 yt-dlp 的 `-x` 包办。所以抽音频的参数必须由 Java 侧定义在一处，由两条输入路径（URL / 本地文件）共用。

反面方案（让 yt-dlp 的 `-x` 处理 URL 路径）反而会**增加**重复：参数分裂成 `--postprocessor-args ExtractAudio:...` 与 Java 的 ffmpeg 命令两份。

---

## 四、ffmpeg / ffprobe 是 yt-dlp 的外部依赖，不是内置

yt-dlp 的 `-v` 会报告它探测到的外部二进制：

```
[debug] exe versions: ffmpeg 9.0.2-essentials_build-www.gyan.dev (setts),
                      ffprobe 9.0.2-essentials_build-www.gyan.dev
```

- 证实「yt-dlp 自带转音频」是误解 —— 它是**调用**外部的 ffmpeg（`-x` 那行 help 写明 `requires ffmpeg and ffprobe`）
- 尺寸佐证：`yt-dlp.exe` **17.8 MB** vs `ffmpeg.exe` **105.4 MB**，18 MB 里装不下 105 MB 的 ffmpeg
- `--ffmpeg-location PATH` 用于显式指定位置（可给二进制路径或其所在目录）—— **容器里 ffmpeg 未必在 PATH，交付时必须配**
- ffmpeg 因此有**两个调用方**（yt-dlp 内部合并 / Java 抽音频），路径应「一处配置、两处使用」
- **这个探测输出可直接用于诊断端点**，不必再另起一次 `ffmpeg --version`

---

## 五、音频格式：本地 wav、云端 m4a（两条 ASR 路径不同）

**格式选择取决于瓶颈在哪，与「无损」无关：**

| ASR 路径 | 音频去哪 | 瓶颈 | 最优格式 |
|---|---|---|---|
| 本地 FunASR / SenseVoice | 同机，直接读文件 | CPU（解码） | **wav PCM**（零解码；模型输入本就是 16k wav） |
| 云端 DashScope | **需上传** | **上传带宽** | **m4a（AAC）** |

体积差（30 分钟、16k 单声道）：wav PCM 16-bit ≈ **57.6 MB**；m4a/mp3 @32 kbps ≈ **7.2 MB**；@64 kbps ≈ **14.4 MB**。**差 4–8 倍。**

> 「ASR 对语音压缩不敏感」是常识性判断，**未做对拍实测**。若要严格论证，应拿同一段音频跑 wav/m4a 两版比对识别结果。

### DashScope 支持的音频格式（2026-09-25 查官方文档，非实测）

> aac、amr、avi、flac、flv、**m4a**、mkv、mov、**mp3**、**mp4**、mpeg、ogg、opus、**wav**、webm、wma、wmv

- `wav` / `mp3` / `m4a` **均在支持范围内**
- 采样率：`paraformer-v2` 要求 16 kHz；`sample_rate` 可选、默认 `16000`（会自动重采样）
- 单文件 ≤ 2 GB，时长 ≤ 12 小时，必须是公网 HTTP/HTTPS URL
- 官方建议：**单声道、≥16 kHz**
- 列表里含 `mp4`/`mkv`/`avi`/`flv` 等**视频容器**，理论上整段视频可直接提交、免抽音频；但上传几百 MB 视频远慢于几 MB 音频，**仍应抽音频（目的变成缩小体积）**
- 文档自警：「并非所有格式变体都经过测试，建议先测试文件以验证转写效果」→ 交付前须实测

### 为什么选 m4a 而不是 mp3

- **mp3 需要 `libmp3lame` 编码器** —— 本机 build 有，但**容器里的精简 ffmpeg 构建未必有**
- **m4a/AAC 用 ffmpeg 原生编码器**，普遍存在
- 同类教训见 `oss-references.md` 第 15 条（VideoLingo 在 `libmp3lame` 缺失时降级到 `pcm_s16le wav`）

### 抽音频命令（两条输入路线共用同一段逻辑）

```bash
# 本地 FunASR
ffmpeg -i <video> -vn -ac 1 -ar 16000 <out>.wav

# 云端 DashScope
ffmpeg -i <video> -vn -ac 1 -ar 16000 -c:a aac -b:a 48k <out>.m4a
```

参数定义**只有一处**，差异仅在输出格式/编码器。
建议由 **ASR provider 声明它要什么格式**（与 `AudioSource` 的契约中立思路一致），而不是把格式判断散在管线里。

---

## 六、未验事项（用前自测）

1. ~~**`JS runtimes: none`**~~ —— **已实测结清，且结论比下面的原推断严重两级 → 见 §8.1。**
   原推断：「YouTube 近期反爬需要 JS 运行时，**可能影响** YouTube 下载。B站 不受影响。**未实测。**」
   实测：不是「可能影响」，而是**官方已弃用无 JS 运行时的 YouTube 提取**（`YouTube extraction without a JS runtime has been deprecated`），且叠加 **bot 检测**（`Sign in to confirm you're not a bot`）直接 `ERROR` 退出；连元数据都拿不全。**本机当前状态下 YouTube 路线不可用**，需 cookies（是否读取用户浏览器 cookies 属隐私决策，待定）。
   「B站 不受影响」这一半**成立**——§8.6 实测 B站 无需 cookies 即可下载。
2. **文件占用与 `-U`** —— Windows 上正在运行的 exe 被文件锁占用，`-U` 能否与并发下载共存**未实测**（当时已是最新版，`-U` 走 up-to-date 分支不写文件）。设计上先按「不能并发」假设。
3. **`--postprocessor-args NAME:ARGS` 的冒号语法** —— help 确认后处理器名含 `ExtractAudio`，但实际传参行为**未实测**。
   **已作废**：MVP-2a 落地时采纳了 §三 的推论「Java 统一调 ffmpeg 抽音频」，本项不再需要。
4. **重定向 stdout 的编码** —— 上面所有实测都是在**管道/重定向**场景下做的（`| od`）。yt-dlp 在管道下可能走了与「直接写控制台」不同的分支。**Java 走 ProcessBuilder 属于管道场景**，与实测一致；但若将来用别的方式调用，须重新验。

---

## 七、工具版本（2026-09-25）

| 工具 | 版本 | 位置 / 备注 |
|---|---|---|
| yt-dlp | `2026.08.19`（stable，当前最新） | `tools/yt-dlp.exe`，18 MB，自带 Python 3.10.11，1744 个 extractor |
| ffmpeg | `9.0.2-essentials_build-www.gyan.dev` | `F:\app_soft\ffmpeg\bin`（已在 PATH 上） |
| Java | `21.0.8` | `F:\java_soft\java21` |

- `-U` 自更新**可用**（实测通过），但**需要能访问 GitHub**（本机依赖代理）
- yt-dlp 的版本号是日期式 `YYYY.MM.DD`，便于用发布距今多久做新鲜度启发式判断
- `tools/` 已在 `.gitignore` 中（根锚定 `/tools/`），不进仓库

---

## 八、2026-09-25 补记：MVP-2a 步骤 0 实测

> 本节由 MVP-2a 的实现期「步骤 0：先拿实测事实再写代码」产出。
> 工具版本同 §七（yt-dlp 2026.08.19 / ffmpeg 9.0.2 / Java 21.0.8）。

### 8.1 YouTube：无 JS 运行时已被官方弃用，且被 bot 检测拦截（实测）

```bash
$ ./tools/yt-dlp.exe --simulate --print "%(title)s" "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
WARNING: [youtube] No supported JavaScript runtime could be found. Only deno is enabled by default;
         to use another runtime add  --js-runtimes RUNTIME[:PATH]  to your command/config.
         YouTube extraction without a JS runtime has been deprecated, and some formats may be missing.
         See https://github.com/yt-dlp/yt-dlp/wiki/EJS for details on installing one
[youtube] aqz-KE-bpKQ: Downloading visionos player API JSON
WARNING: [youtube] No title found in player responses; falling back to title from initial data.
         Other metadata may also be missing
ERROR: [youtube] aqz-KE-bpKQ: Sign in to confirm you��re not a bot. Use --cookies-from-browser
       or --cookies for the authentication.
```

（`you��re` 的乱码就是 §一 那条编码坑在本机终端里的复现。）

**结论（比 §六-1 的推断严重两级）**：

1. §六-1 猜的是「JS 运行时缺失**可能**影响下载」——实际是**官方已弃用无 JS 的 YouTube 提取**，并明确「some formats may be missing」。
2. 更硬的拦路是 **bot 检测**：`Sign in to confirm you're not a bot`，直接 `ERROR` 退出。
3. 连元数据都拿不全（`No title found in player responses`）。

**所以 YouTube 路线在本机当前状态下不可用**，需要 cookies（`--cookies-from-browser <浏览器>` 或 `--cookies <文件>`）。
**是否读取用户浏览器 cookies 属隐私决策，待用户定**；在定之前，YouTube 相关验收项按「未通过 + 有实测依据的降级说法」记录，不假装通过。

### 8.2 多音轨：ffmpeg 的默认音轨选择跟随 `default` disposition（实测，决定性）

合成两份多音轨文件（无版权问题，纯 lavfi），对比「不写 `-map`」与「显式 `-map`」的产物指纹：

```bash
# 两轨参数相同（都是 aac 单声道），均无 default 标记
$ ffmpeg -f lavfi -i "sine=frequency=440:duration=3" -f lavfi -i "sine=frequency=880:duration=3" \
    -f lavfi -i "testsrc=size=320x240:rate=10:duration=3" -map 2:v -map 0:a -map 1:a \
    -c:v mpeg4 -c:a aac -shortest multitrack.mp4
$ ffmpeg -i multitrack.mp4 -vn -ac 1 -ar 16000 -c:a aac -b:a 48k default.m4a   # 不写 -map
$ ffmpeg -i multitrack.mp4 -vn -map 0:a:0 ... t0.m4a
$ ffmpeg -i multitrack.mp4 -vn -map 0:a:1 ... t1.m4a
$ md5sum default.m4a t0.m4a t1.m4a
f8b1f5b1969b55554857f831deef6cff *default.m4a
f8b1f5b1969b55554857f831deef6cff *t0.m4a      ← 默认 = a:0（两轨平手时取第一条）
49ba338ca48bb9567abed9b21d7dbcd4 *t1.m4a
```

```bash
# 两轨参数不同（a:0 单声道 32k / a:1 立体声 192k）——默认**仍然**取 a:0
$ md5sum d2.m4a e0.m4a e1.m4a
8865884b45ef715cbd501bdbf9e234ee *d2.m4a      ← 默认 = a:0
8865884b45ef715cbd501bdbf9e234ee *e0.m4a
2a437fede29493048b9c98fdb505c6cc *e1.m4a      ← 立体声 192k 没抢过第一条
```

```bash
# 关键一步：把第 2 轨标为 default（真实站点多轨文件的常见做法）
$ ffmpeg -i multi2.mp4 -map 0 -c copy -disposition:a:0 0 -disposition:a:1 default multi3.mp4
$ ffprobe -select_streams a -show_entries stream=index:stream_disposition=default -of json multi3.mp4
    { "index": 1, "disposition": { "default": 0 } },
    { "index": 2, "disposition": { "default": 1 } }
$ ffmpeg -i multi3.mp4 -vn -ac 1 -ar 16000 -c:a aac -b:a 48k d3.m4a   # 仍然不写 -map
$ md5sum d3.m4a e0.m4a e1.m4a
2a437fede29493048b9c98fdb505c6cc *d3.m4a      ← 默认 = a:1！与显式 -map 0:a:1 完全一致
8865884b45ef715cbd501bdbf9e234ee *e0.m4a
2a437fede29493048b9c98fdb505c6cc *e1.m4a
```

**结论**：**ffmpeg 9.0.2 的默认音频流选择跟随 `default` disposition 标记**，不是「取第一条」，也不是「取参数最好的」。

- 两轨都无标记 → 取第一条（前两组实测）
- 第 2 轨标了 `default` → 取第 2 轨（第三组实测，md5 与 `-map 0:a:1` 相同）
- 立体声/高码率**不影响**选择（第二组实测推翻了「best 流」的直觉）

→ **必须显式 `-map 0:a:<index>`**。B站 这类源的多音轨文件带 `default` 标记时，不写 `-map` 会静默抽到非预期的轨，而且产物完全合法、不报错。

### 8.3 越界音轨的报错文案（实测）

```bash
$ ffmpeg -hide_banner -i multitrack.mp4 -map 0:a:9 -f null -
Failed to set value '0:a:9' for option 'map': Invalid argument
Error parsing options for output file -.
Error opening output files: Invalid argument
```

**报错里没有总轨数**。所以 Java 侧的错误消息**不能**写「该媒体没有第 N 条音轨（共 M 条）」——那个 M 拿不到，除非额外跑一次 ffprobe 数轨。结论：**透传 ffmpeg 原文 + 一句中文说明**，不假装知道轨数。

### 8.4 编码器存在性（实测）

```bash
$ ffmpeg -hide_banner -encoders | grep -E " (aac|pcm_s16le) "
 A....D aac                  AAC (Advanced Audio Coding)
 A....D pcm_s16le            PCM signed 16-bit little-endian
```

本机 build（gyan.dev essentials）**两者都有**。研究 §五「m4a 用 ffmpeg 原生编码器，普遍存在」在本机成立；但容器里的精简构建仍可能缺，`OpenSource` 标记为 `A`（音频）而非 `A....D` 的差异说明**动态加载**选项存在，不能外推到别的构建。

### 8.5 仍未验（缺一条真实 B站 链接）

| 项 | 内容 |
|---|---|
| 待验 1 | info json 里定位下载产物的字段（`filepath` / `_filename` / `requested_downloads[0].filepath`） |
| 待验 2 | `--ffmpeg-location` 传目录还是二进制路径 |
| 待验 6 | `--write-info-json` 的默认文件名与实际 `-o` 模板的组合结果 |
| 新增 | 真实 B站 多音轨文件的轨数与 `default` 标记情况（8.2 是合成文件上的结论） |

**这四项都卡在同一个前置上：需要一条可下载的真实 B站 链接。** YouTube 因 8.1 不可用，不能替代。

> **2026-09-25 已全部结清** —— B站 通路跑通，结论见下面 §8.6。

### 8.6 续：B站 通路全流程实测（待验 1 / 2 / 6 / 9 结清）

```bash
$ ./tools/yt-dlp.exe --no-playlist --write-info-json --ffmpeg-location "F:/app_soft/ffmpeg/bin" \
    -o "%(id)s.%(ext)s" -P <dir> "http://www.bilibili.com/video/av117317904177698"
[BiliBili] BV1LLhW6uE12: Downloading video formats for cid 42124577069
[BiliBili] Format(s) 1080P 高码率 are missing; you have to become a premium member to download them.
           Use --cookies-from-browser or --cookies for the authentication.
[info] BV1LLhW6uE12: Downloading 1 format(s): 100026+30280
[info] Writing video metadata as JSON to: <dir>\BV1LLhW6uE12.info.json
[download] Destination: <dir>\BV1LLhW6uE12.f100026.mp4
[download] Destination: <dir>\BV1LLhW6uE12.f30280.m4a
[Merger] Merging formats into "<dir>\BV1LLhW6uE12.mp4"
Deleting original file <dir>\BV1LLhW6uE12.f30280.m4a (pass -k to keep)
Deleting original file <dir>\BV1LLhW6uE12.f100026.mp4 (pass -k to keep)
```

**1. B站 可用，无需 cookies**（与 8.1 的 YouTube 形成鲜明对比）。这条是 epic「未决问题」里 B站 边界的**第一份实测数据**：

- 未登录**可以下载**，拿到普通清晰度
- `1080P 高码率 are missing; you have to become a premium member` —— 高码率要**会员**，不是「登录」就有
- 字幕部分（2b 的平台字幕路径）仍待实测

**2. 待验 2 结清**：`--ffmpeg-location` **传目录与传二进制路径都可以**——两种形式各跑一次，`[Merger]` 均成功。

**3. 待验 6 结清**：`--write-info-json` 产出 `<id>.info.json`，`<id>` 是 **bvid**（`BV1LLhW6uE12`，不是 aid）；info json 的文件名由 `-o` 模板派生（模板里的 `%(ext)s` 位置变成 `info.json`）。中间产物 `.<format_id>.mp4/.m4a` 在合并后被删除，**目录最终只剩最终产物 + info json**。

**4. 待验 1 结清——结论是负面的，改了设计**：info json **不含任何产物路径字段**：

```python
# parse BV1LLhW6uE12.info.json
id -> 'BV1LLhW6uE12'    ext -> 'mp4'    duration -> 47.717
filepath            -> None   ← 字段缺失
_filename           -> None   ← 字段缺失
requested_downloads -> None   ← 字段缺失
subtitles -> []        automatic_captions -> None
```

→ **定位产物不能靠 info json**。改为靠**我们自己的输出模板**（模板固定 → 文件名可预测）+ info json 的 `ext`（合并后的最终扩展名），再留一层目录扫描兜底。
为此把 `-o` 模板从 `%(id)s.%(ext)s` 改为 **`source.%(ext)s`**，工作目录因此固定为 `source.<ext>` + `source.info.json`，与设计 §六 的文件名表一致。

**5. 待验 9 结清**：这条真实 B站 文件**只有 1 条音轨**（aac 立体声，`default=1`）。

```json
{"index": 1, "codec_name": "aac", "channels": 2, "disposition": {"default": 1}}
```

多音轨是常见但非必然。§8.2 的合成文件结论仍是显式 `-map` 的唯一硬依据；本例 `default=1` 出现在唯一一轨上，正好印证「disposition 驱动选择」的机制——**若哪天有第二轨也带 `default`，默认选择就会漂移**。

**6. 这条视频没有 CC 字幕**（`subtitles: []`，字段存在但为空）。→ 2b「平台字幕优先」路径需要另找一条**真有 CC 字幕**的 B站 视频作素材。

**7. 编码坑再次现场复现**：未加 `iconv -f GBK -t UTF-8` 时，yt-dlp 输出的中文标题在本机终端里是一片乱码——与 §一 同源，Java 侧必须按 `native.encoding` 解码。
（另注：Python 是 Windows 原生的，不认 Git Bash 的 `/tmp` 映射；`ls /tmp/x` 通不代表 `python open('/tmp/x')` 通。）

**8. 退出码可以是负值（对 §二 的补充）**：`-map` 越界时 ffmpeg 的真实退出码是 **`-22`**，不是 `1`：

```
Failed to set value '0:a:9' for option 'map': Invalid argument
→ Java Process.exitValue() = -22
```

原因是 Windows 的退出码是无符号 DWORD，而 ffmpeg 用 `AVERROR(EINVAL) = -22` 这类**负值**返回参数错误；`ExitProcess` 之后 Java 读回来就是一个负 int。所以：

- §二「退出码语义极弱」再添一例——连**符号**都可能与直觉相反，别指望用退出码判类别
- 消息里应**原样透出**这个负值（对懂 ffmpeg 的人 `-22 == EINVAL` 是有信息量的），**不要**做无符号归一化
- 这也说明「断言 exitCode == 1」这类测试假设是错的：断言应针对「非零」而非某个具体值
