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

1. **`JS runtimes: none`** —— `-v` 显示本机无独立 JS 运行时（库 `yt_dlp_ejs-0.8.0` 已加载）。
   YouTube 近期反爬需要 JS 运行时，**可能影响 YouTube 下载**。B站 不受影响。**未实测。**
2. **文件占用与 `-U`** —— Windows 上正在运行的 exe 被文件锁占用，`-U` 能否与并发下载共存**未实测**（当时已是最新版，`-U` 走 up-to-date 分支不写文件）。设计上先按「不能并发」假设。
3. **`--postprocessor-args NAME:ARGS` 的冒号语法** —— help 确认后处理器名含 `ExtractAudio`，但实际传参行为**未实测**。（若采纳「Java 统一调 ffmpeg 抽音频」，则不需要它。）
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
