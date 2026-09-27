# 真实运行证据 — 字幕清洗（2026-09-27）

> 验收标准「全流程留至少一条真实运行证据」的取证。同步结论回写 `prd.md` 验收与 `design.md`。

## 环境与输入

- 服务：本地起 Spring Boot，`--server.port=8081`（8080 被占，避开）。JDK 21。
- 输入：B站 视频 `https://www.bilibili.com/video/BV1axNJ6REoa?spm_id_from=333.1391.0.0&vd_source=-ff34f9d27240`
  的**已产出原始 segments**（2b 产物 `segments-asr-cloud.json`，36 段，语种 `null`）。
- 调用：`POST /api/subtitles/clean`，body `{"url":"..."}`（无 reference）。

## 结果

```json
{"route":"ASR_CLOUD","language":null,"segmentCount":42,
 "srtPath":"...\\subtitles-asr-cloud-clean.srt","segmentsPath":"...\\segments-asr-cloud-clean.json",
 "srtSizeBytes":4837,"correctedCount":13,"retrySteps":0}
```
HTTP 200。

## 断言逐条

| 验收点 | 实测 |
|---|---|
| 超长段被切开 | 原始 36 段 → 清洗 **42 段**；`00:00:50,910→01:06,270`（15 秒）切成 3 段，时间连续无重叠 |
| 错字被修 | `correctedCount=13`；实例：「零件→剑」、「啊」口头语去除；`retrySteps=0`（守卫一次通过） |
| 断点复用 | 二次调用返回 `correctedCount=0, retrySteps=0`，日志 `字幕清洗断点复用跳过: clean=segments-asr-cloud-clean.json`，LLM 未再调 |
| 原始产物未覆盖 | 原始工作目录仍只有 `segments-asr-cloud.json` + `subtitles-asr-cloud.srt`，**无 `-clean`** |

## 发现（值得记）

1. **无 reference 时 LLM 保守、漏修误识别术语**：`防关`（应为「防到/防过」）未被改——它「读着像词」，通用纠错不肯动。印证 design 的「`reference` 可选，但纠正术语是核心能力」：给术语表才修得了这类。**产品级结论，非代码缺陷。**
2. **raw 缺失返回 500**：未先跑 `/api/subtitles` 时抛 `IllegalStateException`→500。语义更贴 409/400，但本轮接受 500（2026-09-27 决策，见 design §6）。

## 复现要点（给后来者）

- `/clean` 的 workspace 由 `WorkspaceService.workspaceFor(input)` 按「安全名(截 80) + sha256 前 12」算；URL 重建若与已有目录对不上，会新建空目录 → 报「缺 raw」。复现时把原始 `segments-<route>.json` 放进解析出的目录即可。
- 服务进程用 `taskkill //F //T //PID <java_pid>` 收（`mvn spring-boot:run` 的父进程杀不干净子 JVM）。
