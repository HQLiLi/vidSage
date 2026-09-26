# Research: DashScope 云端 ASR 的实测行为（MVP-2b 步骤 0）

- **Query**: 用 2a 的既有端点把「真实输入 → 抽出音频 → 云端转写」串起来，问清 4 个必须在写代码前确定的行为项
- **Scope**: internal（本机实例 + 真实 DashScope key，非模拟）
- **Date**: 2026-09-26
- **状态**：完成（4 项全部实测，原始响应见文末）
- **触发**：`implement.md` 步骤 0，`design.md` §十 待实测项 1–4。

> ⚠️ **最重要的一条结论（与 design 冲突，已据此回改 design）**：**DashScope 对静音音频不返回空 segments，而是任务 FAILED（`ASR_RESPONSE_HAVE_NO_WORDS`）→ provider 抛 `IllegalStateException` → HTTP 500**。design 决策 5「空转写是合法产出」的前提（ASR 会返回空 arrays）在云端不成立。详见 §四。

---

## 一、实测环境

- 独立端口 18080 起实例（8080 被 IDE 的实例占用，不碰它）
- `application-local.yml` 的真 key + 三个工具绝对路径（yt-dlp / ffmpeg / ffprobe 全部解析成功）
- B站链接：`http://www.bilibili.com/video/av117317904177698`

## 二、待实测 1：DashScope 收不收 2a 抽出的 16k 单声道 M4A

**结论：收。** 2a 的 `POST /api/media/audio` 抽出 `audio-16k1ch48k-t0.m4a`（16000Hz/1ch/48kbps/304884B，即 `AudioSpec.M4A_AAC_48K_MONO`），交云端 ASR，**转写成功**，10 句全量返回。**不能用 `docs/prd/local-model-deployment.md` 那份 `sample.m4a` 旧结论替代——本次是逐字段对齐 2a 实际产出的规格，真跑通。**

- 这同时确认：云端路线上 2a 抽音频 → `DashScopeAsrProvider` 本地文件上传（`AudioSource.AudioFilePath`）→ 转写的链路是通的。

## 三、待实测 2 + 3：`language` 字段 + 异常段落

**结论 2：`language` 可为 `null`。** 中文音频返回 `"language": null`。`SubtitleTrack.language` 必须能表达 null（设计倾向直接透传 null 并在 JSON 落 `null`），MVP-3 侧需处理判空——**与 design §十.2 预设一致，无冲突。**

**结论 3：本轮实测没发现异常段落。** 10 句全部：
- `beginTimeMs`（递增：240→2040→2240→2960→…）恒 `< endTimeMs`
- 无负时间戳
- 无空白文本（每句均非空）

**判断**：不需为「Begin>End / 负值 / 空白文本」的归一写防御逻辑 —— 至少 DashScope 正常返回时不产生。但 `formatTimestamp` 仍按决策 5 要求对负值钳到 0（零成本，且本地 FunASR 将来可能产生，属可被单测直接覆盖的纯函数）。

## 四、待实测 4：空转写 / 静音音频 → 决定性结论 ⚠️

**结论：DashScope 对静音拒绝，不是返回空数组，而是任务 FAILED + 500。**

- 造 5 秒静音 `anullsrc=r=16000:cl=mono` wav（160078 字节），交云端 ASR
- 返回：`{"error":"DashScope 任务未成功：FAILED，output：{...\"code\":\"ASR_RESPONSE_HAVE_NO_WORDS\",\"message\":\"ASR_RESPONSE_HAVE_NO_WORDS\"}"}`
- app 日志确认它走 `DashScopeAsrProvider.transcribe` 的「任务未成功」分支 → 抛 `IllegalStateException` → `ApiExceptionHandler` 记 ERROR → HTTP 500

**对 design 的影响（关键）**：design 决策 5 的前提是「ASR 会返回**空 segments**，那是合法输入（纯音乐/无人声）」。实测推翻了这前提——**云端提供商对静音是「任务失败」而非「空结果」**。也就是说，在**当前唯一提供商（cloud）** 下，`SubtitleService` 的「空 segments → 0 字节 SRT + WARN」分支是**不可达代码**。

**怎么处理**（见 `design.md` §五 决策 5 的修订，本次实测后与项目所有者确认）：
1. `DashScopeAsrProvider` 把 `ASR_RESPONSE_HAVE_NO_WORDS` 从「抛异常」改为「返回空 segments 的空 `TranscriptionResult`」，让「无语音是合法输入」在云端也成立 —— provider 是**唯一知道啥叫「没识别出内容」**的地方（`error-handling.md` 的「谁之过」判据：此处内容确实是「无语音」，不是服务故障）。
2. `SubtitleService` 保留「空 segments → 正常产出 + WARN」分支（决策 5 原样），现在它通过 provider 转换后**可达**。

> 这条改动让 `ASRProvider` 的语义更清晰：**转写成功 ≠ 一定有内容**。`segments==[]` 是合法结果，「转写 API 外部调用失败」才是异常。

## 五、原始响应（留档）

### 抽音频（实测 1）
```
{"audioPath":"F:\\...\\http___www.bilibili.com_video_av117317904177698-efea85c04089\\audio-16k1ch48k-t0.m4a","container":"M4A","sampleRateHz":16000,"channels":1,"bitrateKbps":48,"audioTrackIndex":0,"sizeBytes":304884}
```

### 云端 ASR（实测 2+3，成功）
```json
{"segments":[
  {"beginTimeMs":240,"endTimeMs":2040,"text":"女生阴道都长得一模一样吗？"},
  {"beginTimeMs":2240,"endTimeMs":2960,"text":"当然不是了。"},
  {"beginTimeMs":3080,"endTimeMs":5480,"text":"那么刷到的男生可以出去一下。"},
  {"beginTimeMs":5680,"endTimeMs":12920,"text":"其实呢，每个人的私密部位外形都是独一无二的，都存在明显的个体差异，尤其是外阴的差别最明显。"},
  {"beginTimeMs":13200,"endTimeMs":27960,"text":"大小阴唇的长短、形态、颜色也都各不相同，阴道的深浅、宽窄、弯曲角度呢，也因人而异，有的偏直，有的微微弯曲，且左右两侧轻微不对称，是绝大多数女性的常态，并非缺陷。"},
  {"beginTimeMs":28280,"endTimeMs":31160,"text":"但这些差异呢，都是正常的生理现象。"},
  {"beginTimeMs":31520,"endTimeMs":35880,"text":"主要受遗传、年龄、激素水平、生育经历等因素的影响。"},
  {"beginTimeMs":36000,"endTimeMs":39240,"text":"总的来说，就像人的指纹一样，都是独一无二的。"},
  {"beginTimeMs":39640,"endTimeMs":46360,"text":"只要在正常的生理变异范围内，均属于正常的生理范围，无需因形态差异而生产焦虑。"},
  {"beginTimeMs":46560,"endTimeMs":47480,"text":"大家清楚了吗？"}],
  "language":null}
```

> 注：示例视频是医学科普，转录文本含妇科术语；本任务只关心时间戳/语种行为，不评估内容。

### 静音（实测 4）
```
{"error":"DashScope 任务未成功：FAILED，output：{\"task_id\":\"4fff6c33-6430-429d-998e-ccf98d10aeb6\",\"task_status\":\"FAILED\",\"submit_time\":\"2026-09-26 16:10:13.838\",\"scheduled_time\":\"2026-09-26 16:10:13.901\",\"end_time\":\"2026-09-26 16:10:14.208\",\"code\":\"ASR_RESPONSE_HAVE_NO_WORDS\",\"message\":\"ASR_RESPONSE_HAVE_NO_WORDS\"}"}
```

## 六、未验证项（留给后续 / 不阻塞本任务）

- 本地 FunASR 对静音的行为（本任务不实现本地），但 provider 转换后的空-segments 契约对它同样成立
- 平台字幕路线的静音行为（平台字幕整体移出本任务，见 `prd.md`）
- 超长音频（> 分钟级）DashScope 的 chunk 行为（`prd.md` 「不做」清单：长音频分块转写不在范围）