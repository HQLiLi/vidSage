# MVP-2b 音频转字幕（ASR → SRT）— 执行计划

> 技术方案见 `design.md`（下文的「决策 N」「§N」都指它）；需求见 `prd.md`。
> **本文件是执行顺序 + 验证命令 + 审查门的清单**，不是设计文档。

## 前置

- **分支**：从 `dev` 开 `feature/mvp2b-subtitles`（规则 1；本任务改代码，无例外可援引）
- **基线**：开工前先跑一次全量测试确认起点全绿。2026-09-26 最近一次全量是 **14 个测试类 / 164 个用例**，量出来对不上先查原因再动手
- **本机依赖**：JDK 21、ffmpeg（`F:\app_soft\ffmpeg\bin` 在 PATH 上）、`DASHSCOPE_API_KEY`
- **平台字幕不在本任务范围**：不需要 yt-dlp、不需要 cookies、不读 `research/platform-subtitle-formats.md`

```bash
# 基线（Git Bash 下必须显式给 JAVA_HOME，否则报「不支持发行版本 21」）
# surefire-reports 会保留上一轮的报告，量之前先删目录，否则读到的是陈旧数据
rm -rf backend/target/surefire-reports
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/target/surefire-reports/*.xml
```

## 步骤

### 步骤 0：先用**现有端点**问清楚实测项，再写代码（**不要跳过**）

`design.md` §十 有 6 个待实测项，其中前三个**不用写任何新代码**就能问清楚——两个既有端点串起来就是答案。启动一个带工具路径的实例：

```bash
cd backend && YT_DLP_PATH=../tools/yt-dlp.exe FFMPEG_PATH=F:/app_soft/ffmpeg/bin/ffmpeg.exe \
  FFPROBE_PATH=F:/app_soft/ffmpeg/bin/ffprobe.exe JAVA_HOME=/f/java_soft/java21 mvn spring-boot:run
```

```bash
# 待实测 1：2a 实际抽出的 16k 单声道 M4A，DashScope 收不收？
curl -s -X POST localhost:8080/api/media/audio -H 'Content-Type: application/json' \
  -d '{"url":"http://www.bilibili.com/video/av117317904177698","audioContainer":"M4A"}'
# → 记下返回的 audioPath

# 待实测 2 + 3：拿上一步的 audioPath 直接调既有 ASR 端点，看 language 字段与时间戳边界
curl -s -X POST localhost:8080/api/asr/transcriptions -H 'Content-Type: application/json' \
  -d '{"audioFilePath":"<上一步的 audioPath>"}'

# 空转写：造一段 5 秒静音喂进去，看 segments 是空数组还是报错（决策 5 的前提）
ffmpeg -f lavfi -i anullsrc=r=16000:cl=mono -t 5 -c:a pcm_s16le /tmp/silence.wav -y
curl -s -X POST localhost:8080/api/asr/transcriptions -H 'Content-Type: application/json' \
  -d '{"audioFilePath":"/tmp/silence.wav"}'
```

**必须回答**：

1. DashScope 对 2a 抽出的 M4A 的接受度（**不能拿 `docs/prd/local-model-deployment.md` 里 `sample.m4a` 的旧结论替代**——那次的规格是否与 `AudioSpec.M4A_AAC_48K_MONO` 逐字段一致未核）
2. `language` 字段实际返回什么；是否可能缺失或为空
3. 是否存在 `beginTimeMs > endTimeMs` / 负时间戳 / 空白文本的异常段
4. 静音音频的实际行为——`segments` 是空数组，还是抛错，还是返回一句空文本？**这直接决定决策 5 的写法**

> ⚠️ 起冒烟实例后**不要用 TaskStop 收尾**——它杀不掉 Maven 拉起的子 JVM。按 PID 收：
> `taskkill //F //PID <java.exe 的 PID>`（`jps -l` 或任务管理器查）。

**产出**：把原始响应与结论写进 `research/asr-cloud-behavior.md`（新建，属纯文档改动）。**若结论与 `design.md` 冲突 → 停下来改 design，不要按冲突的方案继续写代码。**

### 步骤 1：契约层（`model/subtitle/`，3 个类）

- `SubtitleRoute`：四值枚举，**无字段、无方法**（与 `AudioContainer` 同形；Kebab token 由服务层从 `name()` 派生）
- `SubtitleTrack`：`record(SubtitleRoute route, String language, List<TranscriptionSegment> segments)`，复用 `model/asr/TranscriptionSegment`（决策 2）
- `SubtitleArtifact`：`record(Path srtPath, Path segmentsPath, SubtitleRoute route, String language, int segmentCount, long srtSizeBytes)`，形状对照 `AudioArtifact`

要求：全 `record`（枚举除外）；Javadoc 写语义与**负面约束**；**不写行为方法**；`model/` 的框架注解仅限零运行时影响的 `@Schema`，本组类型**不直接对外**（HTTP 响应走 `web/dto/`），故**不加 `@Schema`**。

**验证**：`mvn -q compile`；`grep -rn "class \|void \|public .*(" model/subtitle/` 确认没有行为方法。

### 步骤 2：接口变更——`ASRProvider` 加两个方法 + cloud provider 错误码降级

```java
AudioSpec requiredAudioSpec();   // 本实现要什么音频
SubtitleRoute route();           // 本实现产出的是哪条路线
```

`DashScopeAsrProvider` 实现为 `AudioSpec.M4A_AAC_48K_MONO` 与 `SubtitleRoute.ASR_CLOUD`。

**同时（步骤 0 实测结论，design §三 决策 5 云端注记 + research/asr-cloud-behavior.md §四）**：在 `transcribe(...)` 的任务失败分支里，识别 `FAILED` 且 `finished.getOutput()` 的 `JsonObject` 中 `code == "ASR_RESPONSE_HAVE_NO_WORDS"` → **返回 `TranscriptionResult(List.of(), null)` 而非抛异常**。放在现有 `!SUCCEEDED || taskResults.isEmpty()` 那一支之前判断。用 gson 的 `.get("code")` 结构化取值，不要 `toString()` + 字符串匹配。给现有 `DashScopeAsrProvider` 单测补一条「HAVE_NO_WORDS → 返回空 segments 不抛」用例（可构造一个装死 `Transcription` / 注入假 `finished`，照现有 mock 风格）。

**验证**：`mvn -q compile` 必须过——若有测试里的匿名实现漏了这两个方法，编译会直接报出来，这正是决策 1 选「放接口上」而非「映射表」的收益。

### 步骤 3：`SubtitleService`（本任务主体）

`service/SubtitleService.java`，构造函数注入 `WorkspaceService` / `AudioPreparationService` / `TranscriptionService` / `ASRProvider` / `ObjectMapper`。

> `ObjectMapper` 必须是 **`tools.jackson.databind.ObjectMapper`**（Boot 4 是 Jackson 3）。`com.fasterxml.jackson.databind.ObjectMapper` 在 Boot 4 里**没有 bean**，注入会直接失败——2a 步骤 5 已实测踩过。

对外一个方法：`SubtitleArtifact prepare(MediaInput input, ProgressListener listener)`（另给一个 `prepare(input)` 两参重载走 `noop()`，与 2a 同形）。

内部拆成可直测的小块（**包级可见**，`quality-guidelines.md` 要求映射逻辑不起 Spring 容器就能测）：

| 成员 | 形态 | 职责 |
|---|---|---|
| `routeToken(SubtitleRoute)` | 包级 `static` | 枚举名 → `asr-cloud` 形式（对照 `AudioPreparationService.audioFileName` 从 `name()` 派生 `.m4a`） |
| `srtFileName(SubtitleRoute)` / `segmentsFileName(SubtitleRoute)` | 包级 `static` | 产物名，带路线指纹（决策 4） |
| `formatTimestamp(long ms)` | 包级 `static` | → `HH:MM:SS,mmm`；**负值钳到 0** |
| `toSrt(List<TranscriptionSegment>)` | 包级 `static` | 渲染整份 SRT（决策 7：自己写，不引库） |
| `writeAtomically(byte[] / String, Path)` | `private` | 临时文件 + 原子替换（决策 7） |

编排顺序按 `design.md` §四，要点：

1. 取 `asrProvider.requiredAudioSpec()`
2. 查检查点：`segments-<token>.json` 存在且 > 0 → 反序列化直接返回（**检查点是 JSON 不是 SRT**，决策 4）
3. `audioPreparationService.prepare(input, spec, remap(listener, 0, 60))`
4. `transcriptionService.transcribe(new AudioSource.AudioFilePath(音频绝对路径), null)`
5. 组 `SubtitleTrack` → 先写 JSON（检查点），再写 SRT
6. 空 `segments` → 正常产出 + **WARN**（决策 5），不抛异常

进度：自建 `remap(listener, from, to)` 把 2a 的 `0..100` 线性映射进 `0..60`，自己的 `transcribe` 占 60→95、`write-subtitles` 占 95→100（决策 6）。

**验证**：`mvn -q compile` + 步骤 5 的单测。

### 步骤 4：HTTP 层（临时面）

- `web/dto/PrepareSubtitleRequest(String url, String localPath)`
- `web/dto/PreparedSubtitleResponse(...)` + `static from(SubtitleArtifact)`（展平 `Path` 与枚举，故必须自持于 `web/dto/`）
- `web/controller/SubtitleController`：`POST /api/subtitles`

**卫语句全部排在 HTTP 边界、早于一切服务调用**（`error-handling.md` 的硬规则；2026-09-26 实测证明排在服务层之后会让参数错误被前面的 I/O 失败掩盖成 500）。照 `MediaController.toMediaInput` 的写法：二选一、非空白、`Path.of` 非法路径换成中文可操作消息。

类注释照 `MediaController` 写「临时面」免责声明（同步端点、MVP-1 落地后改为触发入口或删除）。

**验证**：`mvn -q compile`；切片测试覆盖 400 路径（卫语句在 Controller 里，Service 被 mock 也能测到）。

### 步骤 5：单元测试（**不起 Spring 容器**）

| 测试类 | 覆盖 |
|---|---|
| `SubtitleRouteTest`（若需要） | token 派生对四个值都成立——**把两个本轮用不到的值也测掉**，否则将来接平台字幕时它们从没被执行过 |
| `SubtitleServiceTest` | SRT 时间格式（**含 0 / 999ms / 超过 1 小时 / 负值钳到 0**）、空 segments 产出、块间空行与序号、路线指纹命名、检查点判定（JSON 在 → 跳过；SRT 在但 JSON 不在 → **不跳过**，这条是决策 4 的回归测试） |
| `SubtitleControllerTest` | 400 路径（`@WebMvcTest`；Boot 4 的切片测试需要 `spring-boot-starter-webmvc-test`，已在 pom） |

写法照 `quality-guidelines.md`：JUnit 5、静态导入断言、方法名描述行为（不用 `testXxx`）、**会写文件的用 `@TempDir(cleanup = CleanupMode.NEVER)`**（Windows 上清理偶发失败，已两次独立复现）。

### 步骤 6：全量验证 + 真实运行证据

```bash
rm -rf backend/target/surefire-reports
cd backend && JAVA_HOME=/f/java_soft/java21 mvn -q test
grep -h -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/target/surefire-reports/*.xml
```

真实运行（**必须真跑，不是单元测试**）：

1. 一条 **B站链接** → `POST /api/subtitles` → 产出 SRT，`route=ASR_CLOUD`
2. 一个**本地 mp4** → 同上，产出结构一致的 SRT
3. 一个**本地纯音频**（`asr-service/sample.wav`）→ 产出 SRT
4. **重放第 1 条** → 确认断点复用跳过，不重下、不重抽、不重调 ASR
5. **换 provider 不误用产物**：把 `segments-asr-cloud.json` 改名成 `segments-asr-local.json`，重跑确认**不命中**（验证路线指纹真的生效，而不是「恰好没被检查」）
6. 打开 SRT 与 JSON 肉眼核对：时间戳连续、文本与音频对得上、两文件句数一致

证据记录照 2a 的写法追加到本文件「步骤 6 实测记录」小节。

## 步骤 6 实测记录（2026-09-26）

> 实例：`SERVER_PORT=18080` 独立实例（8080 被 IDE 实例占用，不碰它），真 DashScope key（`application-local.yml`）。
> 全量单测前：16 类 / 181 用例，全绿 0 失败（基线 14/164 → +SubtitleServiceTest 10 +SubtitleControllerTest 3 +DashScopeAsrProviderTest +4）。

**验证 1 — B站链接** ✅
```
POST /api/subtitles {"url":"http://www.bilibili.com/video/av117317904177698"}
→ {"route":"ASR_CLOUD","language":null,"segmentCount":10,"srtSizeBytes":1142}
```
产物 `segments-asr-cloud.json`（10 句）+ `subtitles-asr-cloud.srt`（10 块）。SRT 时间戳连续、与 JSON 逐句对应。

**验证 2 — 本地 mp4** ✅
```
{"localPath":"F:/.../https___www.bilibili.com_video_BV1axNJ6REoa.../source.mp4"}
→ route=ASR_CLOUD, segmentCount=36, srtSizeBytes=4681
```
**验证 3 — 本地纯音频 wav** ✅
```
{"localPath":"F:/.../sample.m4a.../audio-16k1ch-t0.wav"}
→ route=ASR_CLOUD, segmentCount=1, srtSizeBytes=94
```

**验证 4 — 断点复用（重放第 1 条）** ✅
重放同一 B站链接，`real 0m0.098s` 返回相同结果；日志 `字幕断点复用跳过: segments=segments-asr-cloud.json`，无新增 DashScope 提交（全程仅 1 次，来自首次）。

**验证 5 — 换 provider 不误用产物** ✅
把 `segments-asr-cloud.json` 改名 `segments-asr-local.json` → 重跑同一 B站链接 → **未命中**，DashScope 提交计数 3→4（重新转写），重跑成功重新产出 `segments-asr-cloud.json`。指纹隔离真实生效。改名单据事后已删，现场复原。

**验证 6 — 肉眼核对** ✅
SRT 序号 1..N、块间空行、时间戳 `HH:MM:SS,mmm` 连续；JSON `segments` 字段与 SRT 句数与文本一一对应。

**额外 — 空转写端到端（错误码降级在真实链路生效）** ✅
```
{"localPath":"F:/.../silence-test.wav"}   // 5 秒静音
→ {"route":"ASR_CLOUD","language":null,"segmentCount":0,"srtSizeBytes":0}
```
非 500。`segments-asr-cloud.json` = `{"route":"ASR_CLOUD","language":null,"segments":[]}`，SRT 0 字节；日志两条 WARN：provider「DashScope 未识别出任何语音（code=ASR_RESPONSE_HAVE_NO_WORDS）返回空转写结果」+ service「ASR 未识别出任何内容，产出空字幕」。设计决策 5 与步骤 2 的错误码降级双双端到端验证通过。

## 审查门与回滚点

| 门 | 时机 | 动作 |
|---|---|---|
| **门 1（硬门）** | 步骤 6 完成、证据齐 | **立刻停下**，交项目所有者审查：改了什么 / 怎么验证 / 哪些没验证。未获批准不得 `git commit` |
| 门 2 | 用户批准提交后 | `git commit` + `git push -u origin feature/mvp2b-subtitles` |
| 门 3 | 用户明确批准合并后 | `git merge --no-ff` 进 `dev`、删本地与远端分支 |
| 门 4 | 用户主动归档时 | `/trellis:finish-work`；归档后做会话归档（规则 9，先问用户二选一） |

**回滚点**（`design.md` §十一：本任务几乎纯新增，唯一接口变更是 `ASRProvider` 加两个方法）

- 步骤 1 完成：可独立回滚（3 个契约类无人依赖）
- 步骤 2 完成：可独立回滚（还原接口与 `DashScopeAsrProvider` 一处）
- 步骤 3 之后：`SubtitleService` 与 HTTP 层一起回滚；既有 `/api/media/*` 与 `/api/asr/*` 不受影响
- 任一步骤发现设计缺陷 → 回 Phase 1.1 改 `design.md`，改完再继续（workflow 允许 Plan 回退）

## Phase 3.3 要回写的规范（实现落地后做，不在本任务步骤内）

1. `provider-pattern.md`：本轮把 `ASRProvider` 扩成了「转写 + 自述规格 + 自述路线」。若最终采纳决策 1，需补一条**「Provider 自述其运行需求」**的形态说明。**顺带订正 §1 的措辞**：那句「方法只接收和返回**本包定义的**契约类型」按字面读连现有代码都不合格（`transcribe` 返回的 `TranscriptionCommand` 就在 `model/asr/`），应改成它真正的意思——「只接收和返回**本环节的中立契约类型**，不出现 SDK 类型」。否则下一个人照字面读，会以为跨包引用违规，而分层本来就靠跨包引用运转
2. `provider-pattern.md`：把 `SubtitleRoute` 的「契约枚举值可一次定死、含暂未产出的值」作为一条**显式允许的例外**记下来，与「不为将来造抽象」并列，写明判据（成本是两个常量 vs 契约取值域变更）
3. `directory-structure.md`：`model/subtitle/` 落地后，若「领域二级包」的例子仍只举 `asr/`，补上第二个例子
4. 若步骤 0 的实测结论推翻了 `docs/prd/local-model-deployment.md` 或 `research/` 里的任何一条，同步修正该文档

> 下面两条**只在实现过程中真的踩到时才写进规范**，不要预先补：
> - `ObjectMapper` 的 Jackson 3 包名坑已在 `provider-pattern.md` 记着，本轮只是复用
> - 原子替换写盘（临时文件 + 原子替换）若本轮首次落到代码里，考虑在 `quality-guidelines.md` 补一条「产物落盘一律原子替换」——**先看 2a 是否已记，别写重复条目**

## 交给 MVP-3 的接口面（本任务完成即冻结）

- **`segments-<route>.json` 的形状**（即 `SubtitleTrack` 序列化结果）——这是 MVP-3 翻译的**唯一输入**，不必解析 SRT
- `SubtitleArtifact` / `SubtitleRoute` / `SubtitleTrack`
- `SubtitleService.prepare(MediaInput, ProgressListener)`
- **MVP-3 不碰**：音频准备、ASR 调用、断点复用判定、产物命名规则

> `SubtitleTrack.segments` 的段落类型是 `model/asr/TranscriptionSegment`。将来接平台字幕时该类型可能要改名（`design.md` 决策 2 已记账），MVP-3 侧的消费代码届时同步改名即可，**不要为此预先造一个并行类型**。
