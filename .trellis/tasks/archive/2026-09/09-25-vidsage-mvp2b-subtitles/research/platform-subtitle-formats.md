# Research: yt-dlp 平台字幕（B站 / YouTube）真实行为实测

- **Query**: 实测 yt-dlp 拉取 B站 / YouTube 平台字幕的真实行为（格式、默认值、转换、无字幕时的退出码），为 MVP-2b「平台字幕 → SRT 归一」定设计
- **Scope**: external（第三方工具黑盒实测）
- **Date**: 2026-09-26

## 环境

| 项 | 值 |
|---|---|
| yt-dlp | `F:\personal_project\vidSage\tools\yt-dlp.exe`，`stable@2026.08.19 from yt-dlp/yt-dlp [594bd50c2] (win_exe)` |
| Python（内嵌） | 3.10.11 (CPython AMD64 64bit) |
| ffmpeg | `F:\app_soft\ffmpeg\bin`（在 PATH 上），`ffmpeg 9.0.2-essentials_build-www.gyan.dev` |
| shell | Git Bash（Windows 11）。yt-dlp 输出为 GBK：**`locale cp936, out gbk`**，本文所有原始输出经 `iconv -f gbk -t utf-8` 转码 |
| 网络 | 中国境内。B站直连可用；YouTube **部分可用**（见 §8） |
| yt-dlp 配置文件 | 无（已确认 `~/yt-dlp.conf`、`~/.config/yt-dlp/config`、`./yt-dlp.conf` 均不存在）→ 观察到的默认值是 yt-dlp 内置默认值 |

测试用 URL：
- B站：`http://www.bilibili.com/video/av117317904177698`（= BV1LLhW6uE12，任务给定）
- B站补充（3 条，见 §1）：BV1GahD6GEjC、BV1DpaT6pEfo、BV17BaA6dERY
- YouTube：`https://www.youtube.com/watch?v=dQw4w9WgXcQ`（唯一能稳定抓到的）

> 本文所有结论均标注 **[实测]**（有原始输出佐证）或 **[未验]**（推断/无法验证）。

---

## 结论速览（给 Java 侧设计用）

1. **B站未登录时 yt-dlp 根本拿不到平台字幕**。`subtitles` 里只有一个伪语言 `danmaku`（弹幕 XML），且 stderr 有明确 WARNING。**[实测]**
2. **默认不加 `--sub-langs` 只下 1 个语言**，不是全部；要人工字幕优先 + 自动兜底，必须显式传 `--sub-langs`。**[实测]**
3. **YouTube 的 VTT 才能可靠转 SRT**；`json3` 和 B站的 `xml` 转 SRT 会直接失败（exit 1）。**[实测]**
4. **`--convert-subs srt` 依赖 ffmpeg**，没有 ffmpeg 时即使 VTT→SRT 也失败（exit 1）。**[实测]**
5. **「平台无字幕」不是错误**：yt-dlp 打印 `[info] There are no subtitles for the requested languages` 并 **exit 0**，且不产出文件。Java 侧不能靠退出码判断，只能靠「文件是否存在 / 是否为 0 字节」。**[实测]**
6. **最大陷阱**：B站未登录时 `--write-subs` 会"成功"产出一个 `*.danmaku.xml`。若 Java 侧盲收 yt-dlp 产物，会把**弹幕**当成字幕吃进去。必须按语言码白名单过滤，把 `danmaku` 排除。**[实测]**

---

## §1 `--list-subs` 列出了什么？

### §1.1 B站给定视频（未登录）

**命令**

```bash
./tools/yt-dlp.exe --list-subs "http://www.bilibili.com/video/av117317904177698"
```

**原始输出**（已转 UTF-8）

```
[BiliBili] Extracting URL: http://www.bilibili.com/video/av117317904177698
[BiliBili] 117317904177698: Downloading webpage
[BiliBili] BV1LLhW6uE12: Extracting videos in anthology
[BiliBili] BV1LLhW6uE12: Downloading wbi sign
[BiliBili] BV1LLhW6uE12: Downloading video formats for cid 42124577069
[BiliBili] Format(s) 1080P 高码率 are missing; you have to become a premium member to download them. ...
[BiliBili] 117317904177698: Extracting chapters
[BiliBili] BV1LLhW6uE12: Extracting subtitle info 42124577069
WARNING: [BiliBili] Subtitles are only available when logged in. Use --cookies-from-browser or --cookies for the authentication. ...
[info] Available subtitles for BV1LLhW6uE12:
Language Formats
danmaku  xml
```

**结论 [实测]**
- 只列出 **1 行**：语言码 `danmaku`，格式 `xml`。
- **没有** `Available automatic captions` 区块（B站提取器根本不填 `automatic_captions`）。
- 无任何 `zh-CN / zh-Hans / en` 之类真实语言码，**格式里也没有 vtt / json3 / srt / ass / json / ttml**。
- yt-dlp 明确 WARNING：`Subtitles are only available when logged in.` → **B站平台字幕是登录态资源**。

### §1.2 B站补充视频（任务要求：换 2–3 条重测）

因为给定视频没字幕，按任务要求另测 3 条。

**命令**（示例）

```bash
./tools/yt-dlp.exe --list-subs --no-warnings "https://www.bilibili.com/video/BV1GahD6GEjC"
./tools/yt-dlp.exe --list-subs --no-warnings "https://www.bilibili.com/video/BV1DpaT6pEfo"
./tools/yt-dlp.exe --list-subs --no-warnings "https://www.bilibili.com/video/BV17BaA6dERY"
```

**原始输出（节选）**

```
# BV1GahD6GEjC（4 分 P 的番剧式视频）
[download] Downloading playlist: 《鸣潮》共鸣者战斗演示 | 心
[download] Downloading item 1 of 4
[info] Available subtitles for BV1GahD6GEjC_p1:
Language Formats
danmaku  xml
...（p2 / p3 / p4 完全相同）...

# BV1DpaT6pEfo
[info] Available subtitles for BV1DpaT6pEfo:
Language Formats
danmaku  xml

# BV17BaA6dERY（正片 + 番剧提取器，自动跳转 bangumi）
[BiliBiliBangumi] Extracting URL: https://www.bilibili.com/bangumi/play/ep3854817
[BiliBiliBangumi] 3854817: Extracting subtitle info 42162064891
[info] Available subtitles for 3854817:
Language Formats
danmaku  xml
```

**结论 [实测]**
- 4 条 B站视频（含普通投稿、多分 P、番剧 bangumi 提取器）**行为完全一致**：只有 `danmaku xml` + 登录 WARNING。
- **怎么判断这些视频"有字幕"**：无法判断，也无需判断——本次实测说明**未登录状态下 B站提取器一律不返回真实字幕**，与视频内容无关。这 3 条是从 B站 `popular` 接口随机取的头部热门（`curl https://api.bilibili.com/x/web-interface/popular`），并非特意挑选有 CC 的视频。
- 因此：**"换视频重测"这条路在无 cookies 时走不通**，不是选片问题，是登录态问题。**[实测]**

### §1.3 YouTube（能抓到的那条）

**命令**

```bash
./tools/yt-dlp.exe --list-subs --socket-timeout 15 --no-warnings "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出（结构）**

```
[info] Available automatic captions for dQw4w9WgXcQ:      <- 第 5 行
Language       Name                                                        Formats
en             English, English, English, ... , unknown                    vtt, srt, ttml, srv3, srv2, srv1, json3, vtt
de-DE                                                                      vtt
ja             Japanese, ... , unknown                                     vtt, srt, ttml, srv3, srv2, srv1, json3, vtt
pt-BR                                                                      vtt
es-419                                                                     vtt
ab-en          Abkhazian from English                                      vtt, srt, ttml, srv3, srv2, srv1, json3
aa-en          Afar from English                                           vtt, srt, ttml, srv3, srv2, srv1, json3
...（共 941 行自动字幕条目，绝大多数是 "<语言> from English" 的机器翻译轨）...

[info] Available subtitles for dQw4w9WgXcQ:               <- 第 947 行
Language Name                    Formats
en       English                 vtt, srt, ttml, srv3, srv2, srv1, json3
de-DE    German (Germany)        vtt, srt, ttml, srv3, srv2, srv1, json3
ja       Japanese                vtt, srt, ttml, srv3, srv2, srv1, json3
pt-BR    Portuguese (Brazil)     vtt, srt, ttml, srv3, srv2, srv1, json3
es-419   Spanish (Latin America) vtt, srt, ttml, srv3, srv2, srv1, json3
```

**结论 [实测]**
- **人工字幕（`subtitles`）与自动字幕（`automatic_captions`）被分成两个独立区块**，用 `[info] Available subtitles for ...` / `[info] Available automatic captions for ...` 两行区分。
- 人工字幕：5 个语言码（`en, de-DE, ja, pt-BR, es-419`），格式 `vtt, srt, ttml, srv3, srv2, srv1, json3`（**含 srt，但无 ass / json**）。
- 自动字幕：约 941 条，格式基本是 `vtt` 或 `vtt, srt, ttml, srv3, srv2, srv1, json3`。
- `automatic_captions` 里同语言有多条 track（如 `en` 出现 8 次，Name 字段重复 "English, English, ... unknown"）——**[未验]** 这是 yt-dlp 对 ASR track 的合并展示还是重复，未深查。

---

## §2 实际下载：文件真的存在吗？非空吗？

### §2.1 B站（唯一可下的是 danmaku）

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs danmaku -P data/tmp/sub-probe/D -o "d.%(ext)s" \
  "http://www.bilibili.com/video/av117317904177698"
```

**原始输出**

```
WARNING: [BiliBili] Subtitles are only available when logged in. ...
[info] BV1LLhW6uE12: Downloading subtitles: danmaku
[info] BV1LLhW6uE12: Downloading 1 format(s): 100026+30280
[info] Writing video subtitles to: data\tmp\sub-probe\D\d.danmaku.xml
[download] Destination: data\tmp\sub-probe\D\d.danmaku.xml
[download] 100% of    5.14KiB in 00:00:00 at 40.37KiB/s
```

**产物**

```
-rw-r--r-- 1 ... 5262  9月 26 15:34 d.danmaku.xml
```

**文件内容（节选，真实非空）**

```xml
<?xml version="1.0" encoding="UTF-8"?><i><chatserver>chat.bilibili.com</chatserver><chatid>42124577069</chatid>
<mission>0</mission><maxlimit>300</maxlimit><state>0</state><real_name>0</real_name><source>k-v</source>
<d p="29.04400,5,25,16707842,1790143514,0,62eb901c,2206642751134310400,10">小学经历不同，毕业就不一样了</d>
<d p="17.13300,1,25,16777215,1790145604,0,d7d13984,2206660278861646592,10">不愧是b站</d>
...
```

**结论 [实测]**
- 文件**真实存在、非空（5262 字节）**。
- 但它**不是字幕**，是 B站弹幕：根节点 `<i>`，每条 `<d p="时间,模式,字号,颜色,...,">文本</d>`。`p` 属性第 1 段是秒数（如 `29.04400`），第 2 段是弹幕类型（1/4/5 = 滚动/底部/顶部），**没有结束时间**（弹幕是瞬时点，不是区间）。
- **没有出现"字幕是空壳文件"的现象**——因为 `subtitles` 里压根没有 `zh-CN` 等真实语言，请求真实语言时 yt-dlp 直接报「无字幕」（见 §6），不会产出空文件。
- 三份 danmaku XML（A/B/D 三次运行）md5 完全一致：`dbe1498fd50c07ba7934f2f7f9d2aa53`。

### §2.2 YouTube

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs en -P data/tmp/sub-probe/Y1 -o "y1.%(ext)s" \
  "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**产物与大小**

| 目录 | 文件 | 大小 |
|---|---|---|
| Y1 | `y1.en.vtt` | 4263 B |
| Y2（`--sub-format vtt`） | `y2.en.vtt` | 4263 B |
| Y3（`--sub-format json3`） | `y3.en.json3` | 8325 B |
| Y4（`--sub-format vtt --convert-subs srt`） | `y4.en.srt` | 4401 B |
| Y5（`--write-auto-subs`） | `y5.en.vtt` | 14807 B |
| G1（`--sub-format srt`） | `g1.en.srt` | 4.30 KiB |

**结论 [实测]**
- YouTube 侧文件**全部真实存在且非空**，内容是有意义的人文字幕（见下方 VTT / SRT 片段）。
- 人工字幕 VTT（Y1）：

```
WEBVTT
Kind: captions
Language: en

00:00:01.360 --> 00:00:03.040
[♪♪♪]

00:00:18.640 --> 00:00:21.880
♪ We're no strangers to love ♪
```

- 转出的 SRT（Y4）格式正确：

```
1
00:00:01,360 --> 00:00:03,040
[♪♪♪]

2
00:00:18,640 --> 00:00:21,880
♪ We're no strangers to love ♪
```

- 自动字幕 VTT（Y5）**体积是人工的 3.5 倍**（14807 vs 4263），且带滚动重复：

```
00:00:00.320 --> 00:00:18.790 align:start position:0%
 
[Music]

00:00:18.790 --> 00:00:18.800 align:start position:0%
 
 

00:00:18.800 --> 00:00:21.790 align:start position:0%
```

  → **[实测]** 自动字幕含大量零时长/重复 cue 和 `align:` `position:` 注释，归一到 SRT 时必须去重、去嵌套（VTT 的 inline timing 标签）。**[未验]** 是否所有 YouTube 自动字幕都长这样，只测了 1 条。

---

## §3 不加 `--sub-langs` 的默认行为

**命令**

```bash
# A：B站
./tools/yt-dlp.exe --skip-download --write-subs -P data/tmp/sub-probe/A -o "a.%(ext)s" "http://www.bilibili.com/video/av117317904177698"
# G2：YouTube（该视频有 5 个人工字幕语言）
./tools/yt-dlp.exe --skip-download --write-subs -P data/tmp/sub-probe/G2 -o "g2.%(ext)s" "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出**

```
# A（B站）
WARNING: [BiliBili] Subtitles are only available when logged in. ...
[info] BV1LLhW6uE12: Downloading subtitles: danmaku
[info] Writing video subtitles to: data\tmp\sub-probe\A\a.danmaku.xml

# G2（YouTube）
[info] dQw4w9WgXcQ: Downloading subtitles: en
[info] Writing video subtitles to: data\tmp\sub-probe\G2\g2.en.vtt
```

**对照：显式 `--sub-langs all`**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs all -P data/tmp/sub-probe/G4 -o "g4.%(ext)s" \
  "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

```
[info] dQw4w9WgXcQ: Downloading subtitles: en, de-DE, ja, pt-BR, es-419
```

产物：`g4.de-DE.vtt`、`g4.en.vtt`、`g4.es-419.vtt`、`g4.ja.vtt`、`g4.pt-BR.vtt`（5 个）。

**结论 [实测]**
- **默认只下 1 个语言**，不是全部。
  - YouTube 有 5 个人工字幕语言，默认只下了 `en`。
  - `--sub-langs all` 才下全部 5 个。
- 选中的是哪个语言：dQw4w9WgXcQ 上默认选中 `en`。该视频的 `--list-subs` 顺序也是 `en, de-DE, ja, pt-BR, es-419`，且 en 同时是**原语言**——两个假设（"取列表第一个" vs "取视频原语言"）**无法区分**。
- **[未验]** 想把「默认 = 原语言」坐实，需再测一条原语言非英语的视频；尝试 `jNQXAC9IVRw`（Me at the zoo）和 `kJQP7kiw5Fk`（Despacito，原语言西语）均被 YouTube 反爬拦下（见 §8），**未能验证**。
- **对设计的硬结论**：**永远不要依赖默认**，MVP-2b 必须显式传 `--sub-langs`（如 `zh-Hans,zh-CN,zh,en` 或 `all`），否则行为依站点而定。

---

## §4 `--convert-subs srt` 生效吗？要 ffmpeg 吗？json / json3 呢？

### §4.1 VTT → SRT（正例）

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs en --sub-format vtt --convert-subs srt \
  -P data/tmp/sub-probe/Y4 -o "y4.%(ext)s" "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出**

```
[info] Writing video subtitles to: data\tmp\sub-probe\Y4\y4.en.vtt
[download] 100% of    4.16KiB in 00:00:00
[SubtitlesConvertor] Converting subtitles
Deleting original file data\tmp\sub-probe\Y4\y4.en.vtt (pass -k to keep)
```

**结论 [实测]**
- **生效**：产出 `y4.en.srt`（4401 B），格式正确（见 §2.2）。
- **默认会删掉原始 .vtt**，要保留得加 `-k` / `--keep-video`。

### §4.2 `--convert-subs` 依赖 ffmpeg

**命令**（把 ffmpeg 位置指向不存在的目录）

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs en --sub-format vtt --convert-subs srt \
  --ffmpeg-location /c/nonexistent-dir -P data/tmp/sub-probe/F1b -o "f1b.%(ext)s" \
  "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出**

```
[info] Writing video subtitles to: data\tmp\sub-probe\F1b\f1b.en.vtt
[download] 100% of    4.16KiB in 00:00:00
[SubtitlesConvertor] Converting subtitles
ERROR: Preprocessing: ffmpeg not found. Please install or provide the path using --ffmpeg-location
ERROR: Preprocessing: ffmpeg not found. Please install or provide the path using --ffmpeg-location
```

退出码 **1**，产物只剩未转换的 `f1b.en.vtt`（4263 B）。

**结论 [实测]**
- **`--convert-subs srt` 必须要有 ffmpeg**，连 VTT→SRT 这种简单转换也走 ffmpeg。
- 缺 ffmpeg 时 **exit 1**，**且原始 .vtt 会留在磁盘上**（不会因为转换失败而回滚）。
- 本机 ffmpeg 在 PATH 上（`which ffmpeg` → `/f/app_soft/ffmpeg/bin/ffmpeg`），正常场景下转换可用。

### §4.3 json3 → SRT（反例，失败）

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs en --sub-format json3 --convert-subs srt \
  -P data/tmp/sub-probe/X1 -o "x1.%(ext)s" "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出**

```
[info] Writing video subtitles to: data\tmp\sub-probe\X1\x1.en.json3
[download] 100% of    8.13KiB in 00:00:10
[SubtitlesConvertor] Converting subtitles
ERROR: Preprocessing: Error opening input files: Invalid data found when processing input
ERROR: Preprocessing: Error opening input files: Invalid data found when processing input
```

退出码 **1**，磁盘上留下未转换的 `x1.en.json3`（8325 B）。

json3 内容长这样（`events[].tStartMs / dDurationMs / segs[].utf8`，**时间单位是毫秒，可解析**）：

```json
{
  "wireMagic": "pb3",
  "pens": [ { } ],
  "wsWinStyles": [ { } ],
  "wpWinPositions": [ { } ],
  "events": [ {
    "tStartMs": 1360,
    "dDurationMs": 1680,
    "segs": [ { "utf8": "[♪♪♪]" } ]
  }, {
    "tStartMs": 18640,
    "dDurationMs": 3240,
    "segs": [ { "utf8": "♪ We're no strangers to love ♪" } ]
  }, ...
```

**结论 [实测]**
- **json3 无法被 yt-dlp 转成 SRT**（ffmpeg 不认 json3，exit 1）。
- 但 json3 **本身结构清晰、含起止时间（毫秒）**，Java 侧自己解析不难——**[未验]** 只是 yt-dlp 不做，需要自研转换。

### §4.4 B站 `xml`（danmaku）→ SRT（失败）

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs danmaku --sub-format xml --convert-subs srt \
  -P data/tmp/sub-probe/X2 -o "x2.%(ext)s" "http://www.bilibili.com/video/av117317904177698"
```

**原始输出**

```
[info] Writing video subtitles to: data\tmp\sub-probe\X2\x2.danmaku.xml
[download] 100% of    5.14KiB in 00:00:00
[SubtitlesConvertor] Converting subtitles
ERROR: Preprocessing: Error opening input files: Invalid data found when processing input
ERROR: Preprocessing: Error opening files: Invalid data found when processing input
```

退出码 **1**，留下未转换的 `x2.danmaku.xml`。

**结论 [实测]**：danmaku XML 也不能转 SRT（本来也不是字幕）。

### §4.5 不存在的格式会静默降级

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs en --sub-format ass --convert-subs srt \
  -P data/tmp/sub-probe/X3 -o "x3.%(ext)s" "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出**

```
WARNING: No subtitle format found matching "ass" for language en, using vtt. Use --list-subs for a list of available subtitles
[info] Writing video subtitles to: data\tmp\sub-probe\X3\x3.en.vtt
[SubtitlesConvertor] Converting subtitles
Deleting original file data\tmp\sub-probe\X3\x3.en.vtt (pass -k to keep)
```

退出码 **0**，产出 `x3.en.srt`。

**结论 [实测]**
- `--sub-format` 请求的格式**不存在时不报错**，只打 WARNING 然后回落到可用格式（这里是 vtt），流程继续。
- `--sub-format` 的语法是**优先级列表**（`srt/vtt/best`），不是"必须是这个"。
- **默认 `--sub-format` 的值 [未验]**：只观察到不加该参数时 YouTube 产出 **vtt**（Y1/G2，尽管该语言同时提供 srt）。因此默认偏好里 **vtt 优先于 srt**（推断，未读源码）。

### §4.6 小结（Q4）

| 输入格式 | `--convert-subs srt` 能否成功 | 说明 |
|---|---|---|
| `vtt` | **能 [实测]** | 需 ffmpeg |
| `srt` | 不需要转换 | YouTube 原生提供 srt（`--sub-format srt` 直接下 .srt，无 "Converting" 日志）**[实测]** |
| `ass` | 能（但 YouTube 不提供 ass，实际回落到 vtt 再转）**[实测]** | `--convert-subs` 文档声明支持 ass/lrc/srt/vtt |
| `json3` | **不能，exit 1 [实测]** | ffmpeg 不认 |
| `xml`（danmaku） | **不能，exit 1 [实测]** | ffmpeg 不认 |
| `json` | **未验** | 本次没有任何站点提供 `json` 格式，无从测 |

---

## §5 `--write-auto-subs` 是什么结果？

### §5.1 YouTube（该视频有自动字幕）

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-auto-subs --sub-langs en -P data/tmp/sub-probe/Y5 -o "y5.%(ext)s" \
  "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出**

```
[info] dQw4w9WgXcQ: Downloading subtitles: en
[info] Writing video subtitles to: data\tmp\sub-probe\Y5\y5.en.vtt
```

**产物**：`y5.en.vtt`，**14807 B**（人工的 `y1.en.vtt` 只有 4263 B）。

**结论 [实测]**
- `--write-auto-subs` **生效**，能下到 ASR 自动字幕。
- 文件名/扩展名与人工字幕**完全一样**（都是 `<id>.en.vtt`）——**只靠文件名分不出人工还是自动**。
- 自动字幕 VTT 体积约为人工的 3.5 倍，含大量零时长 cue、`align:start position:0%` 注释、以及逐词滚动的重复文本（见 §2.2 片段）。
- `--no-write-auto-subs` 是默认值（`--help` 明示 `(default)`）**[实测]**

### §5.2 B站（没有任何自动字幕）

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-auto-subs -P data/tmp/sub-probe/C -o "c.%(ext)s" \
  "http://www.bilibili.com/video/av117317904177698"
```

**原始输出**

```
[info] BV1LLhW6uE12: Downloading 1 format(s): 100026+30280
[info] There are no subtitles for the requested languages
```

退出码 **0**，`data/tmp/sub-probe/C/` **目录为空**（0 个文件）。

**结论 [实测]**
- B站提取器**不提供自动字幕**，`--write-auto-subs` 直接报「无字幕」，exit 0，无产物。
- 注意：B站的 `automatic_captions` 是**空的**，所以 `--write-auto-subs` 在 B站上永远拿不到东西（未登录时）。

### §5.3 「人工优先，自动兜底」在 yt-dlp 层能否一次完成？

**[未验]** 本次未实测 `--write-subs --write-auto-subs` 同时给（两者一起传时的去重/优先级行为）。只验证了二者单独使用。

---

## §6 视频没有字幕时，退出码与输出是什么？

这是决定 Java 侧「怎么判定平台无字幕」的关键。

### §6.1 B站：显式请求一个不存在的语言

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs zh-CN -P data/tmp/sub-probe/F2 -o "f2.%(ext)s" \
  "http://www.bilibili.com/video/av117317904177698"
```

**原始输出**（尾部）

```
WARNING: [BiliBili] Subtitles are only available when logged in. ...
[info] BV1LLhW6uE12: Downloading 1 format(s): 100026+30280
[info] There are no subtitles for the requested languages
```

**退出码：0**。`data/tmp/sub-probe/F2/` 未创建（**无任何产物文件**）。

### §6.2 YouTube：请求一个不存在的语言码

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs zz -P data/tmp/sub-probe/F3 -o "f3.%(ext)s" \
  "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**原始输出**（尾部）

```
[info] dQw4w9WgXcQ: Downloading 1 format(s): 401+251
[info] There are no subtitles for the requested languages
```

**退出码：0**，无产物。

### §6.3 B站：`--sub-langs all` 但只有 danmaku

**命令**

```bash
./tools/yt-dlp.exe --skip-download --write-subs --sub-langs all --no-write-auto-subs \
  -P data/tmp/sub-probe/F4 -o "f4.%(ext)s" "http://www.bilibili.com/video/av117317904177698"
```

**原始输出**（尾部）：`[info] BV1LLhW6uE12: Downloading subtitles: danmaku`，退出码 **0**，产出 `f4.danmaku.xml`。

**结论 [实测]**
- **「平台无字幕」既不是报错也不是异常退出**：yt-dlp 打印一行 `[info] There are no subtitles for the requested languages` 到 **stdout**（不是 stderr），**退出码恒为 0**，**不产出任何文件**。
- 因此 **Java 侧不能用退出码判断**。可行的判定信号（按可靠性排序）：
  1. **检查输出目录里是否出现了预期的字幕文件**（最可靠，实测无字幕时目录为空/不存在）；
  2. 解析 stdout 里的 `There are no subtitles for the requested languages` 字样（[未验] 该文案是否随版本/语言变化，且中文 locale 下 stdout 内容是英文——本机 `locale cp936` 下仍是英文文案，[实测]）；
  3. 检查文件是否 **0 字节**（本次实测**没遇到**空文件场景，但 B站历史上以空壳字幕闻名，见下）。
- **[未验]** "B站未登录时字幕常是空壳"这一说法：本次实测**根本没走到那一步**——请求真实语言时 yt-dlp 连 `subtitles` 条目都没有，直接报无字幕。**空壳文件场景未能复现**。

---

## §7 未登录 vs 带 cookies

**结论：未测。**

- 本机**没有任何 cookies 文件**（已查：仓库内无 `*cookie*` 相关字幕/认证文件，`~/cookies.txt` 不存在）。
- 尝试用 `--cookies-from-browser` 读取浏览器 cookie 存储的行为**被沙箱权限策略拒绝**（归类为读取用户凭据），未执行。

因此以下问题**全部 [未验]**：
- 登录后 B站 `--list-subs` 会列出哪些语言码 / 格式（预期是 `zh-CN` 等 + `json`/`srt`，未证实）；
- 登录后 B站字幕能否成功下载、是否为空壳；
- 登录后 YouTube 是否不再触发 §8 的反爬拦截。

**已实测到的间接证据 [实测]**：yt-dlp 自己在两处提示登录，说明这是提取器的设计行为而非偶发——
- `WARNING: [BiliBili] Subtitles are only available when logged in.`
- `WARNING: [BiliBili] Format(s) 1080P 高码率 are missing; you have to become a premium member...`

---

## §8 YouTube 可达性（任务说"大概率不可达"，实测部分可达）

**命令**

```bash
./tools/yt-dlp.exe --list-subs --socket-timeout 15 --no-warnings "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**结果 [实测]**

| 视频 | 结果 |
|---|---|
| `dQw4w9WgXcQ`（Rick Astley） | **成功**，能列出 941 条自动字幕 + 5 条人工字幕。多次重跑均成功。 |
| `jNQXAC9IVRw`（Me at the zoo） | **失败**：`ERROR: [youtube] jNQXAC9IVRw: Sign in to confirm you're not a bot.` |
| `kJQP7kiw5Fk`（Despacito） | **失败**：同上 `Sign in to confirm you're not a bot.` |

**结论 [实测]**
- YouTube **不是完全不可达**——本机对中国大陆网络下，个别视频能正常抽取（dQw4w9WgXcQ 稳定成功）。
- 但**大多数视频会被反爬拦下**，错误文案是 `Sign in to confirm you're not a bot. Use --cookies-from-browser or --cookies ...`，**退出码 1**。
- 这属于 YouTube 的 IP/会话级风控，**与本项目代码无关**；Java 侧若把 YouTube 当一等信息源，需预期较高的失败率，且失败是 **exit 1 + ERROR 行**（与"无字幕"的 exit 0 可区分）。
- 附带观察 [实测]：verbose 输出有 `WARNING: [youtube] No supported JavaScript runtime could be found. Only deno is enabled by default ...`（本机无 deno/node，`JS runtimes: none`），yt-dlp 提示"部分格式可能缺失"。这可能与反爬失败有关，**[未验]** 未测试安装 JS runtime 后是否改善。

---

## 附 A：本次实测产物清单（均在 `data/tmp/sub-probe/`，测试后已删除）

| 路径 | 大小 | 说明 |
|---|---|---|
| `q1-listsubs.raw` | 1001 B | §1.1 B站 list-subs 原始输出（GBK） |
| `A/a.danmaku.xml` | 5262 B | §3 默认 `--write-subs` 产物 |
| `B/b.danmaku.xml` | 5262 B | §3 `--sub-langs all` 产物 |
| `D/d.danmaku.xml` | 5262 B | §2.1 显式 `--sub-langs danmaku` 产物 |
| `Y1/y1.en.vtt` | 4263 B | §2.2 人工字幕 VTT |
| `Y3/y3.en.json3` | 8325 B | §4.3 json3 原始 |
| `Y4/y4.en.srt` | 4401 B | §4.1 VTT→SRT 成功 |
| `Y5/y5.en.vtt` | 14807 B | §5.1 自动字幕 VTT |
| `X1/x1.en.json3` | 8325 B | §4.3 json3→SRT 失败后残留 |
| `X2/x2.danmaku.xml` | 5262 B | §4.4 xml→SRT 失败后残留 |
| `X3/x3.en.srt` | — | §4.5 ass 回落 vtt 后转 SRT 成功 |
| `F1b/f1b.en.vtt` | 4263 B | §4.2 无 ffmpeg 时转换失败后的残留 |
| `G1/g1.en.srt` | 4.30 KiB | §4.6 YouTube 原生 srt |
| `G2/g2.en.vtt` | — | §3 默认只下 en |
| `G4/g4.{en,de-DE,ja,pt-BR,es-419}.vtt` | — | §3 `--sub-langs all` 下满 5 个 |

## 附 B：未验证项汇总（供后续补测）

1. **B站登录态行为**（§7）：`--list-subs` 语言码/格式、能否下载、是否空壳 —— 需 cookies。
2. **YouTube 登录态**是否绕过反爬（§8）。
3. **`json` 格式**（非 json3）能否转换（§4.6）——本次无站点提供。
4. **`--write-subs` 与 `--write-auto-subs` 同时传**时的去重/优先级（§5.3）。
5. **默认 `--sub-langs` 的选取规则**到底是"列表首个"还是"视频原语言"（§3）——需一条原语言非英语且能抓到的视频。
6. **默认 `--sub-format` 的官方值**（§4.5）——仅实测观察到偏好 vtt。
7. **`--convert-subs` 是否走 ffmpeg 以外路径**（如 lrc）（§4）。
8. **自动字幕 VTT 的重复 cue 是否普遍**（§2.2）——只测了 1 条。
