# MVP-3 验收证据（2026-09-28）

> 逐条对应 `prd.md` 的验收标准。环境：本地起 Spring Boot（8080），JDK 21，
> `DASHSCOPE_API_KEY` / `DEEPSEEK_API_KEY` 已设；素材取自 `backend/data/works/`（已 gitignore）。
>
> **前提说明**：手上两条素材都是**中文**视频，默认 `target-language: zh` 会走「跳过翻译」分支、
> 测不到翻译器。故跑真实翻译时把 `application-local.yml`（不进仓库）的目标语种临时改成 `en`，
> 跑完已还原。`zh → en` 是配置可达、但**非本项目主用例**的路径（主用例是外语视频 → 中文，
> 该方向本次无法验证——没有外语素材，见文末「未覆盖」）。

## 端到端四次运行

| # | 场景 | 输入 | 结果 | 耗时 |
|---|---|---|---|---|
| 1 | 跳过分支 | `BV1axNJ6REoa`（42 段），target=zh | `skipped=true`、两条产物照常落盘 | **0.087s**（未调 LLM） |
| 2 | 真实翻译 | 同一素材，target=en | 4 批（12/12/12/6 句），retry=0，untranslated=0 | **8.34s** |
| 3 | 断点复用 | 再跑一次场景 2 | 直接返回、统计归零、未调 LLM | **0.104s** |
| 4 | 长视频 | `BV1C2ae6DEqW`（319 段），target=en | 27 批（每批 12 句，末批 7），retry=0，untranslated=0 | **41.4s** |

产物：

- 场景 1：`segments-asr-cloud-translate-zh.json`（11713 B）+ `subtitles-asr-cloud-translate-zh.srt`（4859 B）
- 场景 2：`subtitles-asr-cloud-translate-en.srt`（8491 B）
- 场景 4：`subtitles-asr-cloud-translate-en.srt`（42165 B）

## 逐条验收

### 1. 产出双语 SRT；条数与起止时间与输入逐条一致 —— 通过

场景 2 的产物与 `-clean.srt` 做 `diff`：42 条**时间轴逐行完全相同**（无差异输出）。
场景 1 的产物与 `-clean.srt` **逐字节相同**（4859 B / md5 一致）——印证「译文==原文时该条只渲染一行」，
跳过的产物不会出现两行一模一样的字幕。

### 2. 译文使用目标语种；术语表的写法在译文里保留原文 —— 通过（但有已知边界）

译文确为目标语种（en）。术语保留方面**发现并记录了缺陷**：
42 条译文里 9 条把中文专有名词原样留在英文句子里（`弹刀`、`架势条`、`只狼`、`举顶格`…），
详见 `zh-to-en-remnant.txt`。补充做了对照实验（见下节），结论与后续处置见 `prd.md` 的「已知边界」。

### 3. 同一术语/称谓全片译法一致 —— 通过（抽查）

`架势条`、`弹刀`、`日耳曼` 在各自出现的多条里写法一致；跨批历史机制按设计生效
（日志里 `历史轮数` 逐批递增）。

### 4. 源语种 == 目标语种时不调 LLM、产物完整、响应带明确标记 —— 通过

场景 1：`skipped=true`，耗时 0.087s（同素材真实翻译是 8.34s，量级差两个数量级，
可佐证未发生 LLM 调用），两条产物照常落盘且 SRT 与 `-clean` 逐字节相同。

### 5. 上下文落盘；清洗跑完后再跑翻译不重复摘要 —— 通过

- 场景 2 的日志：`字幕上下文缓存命中: file=context-asr-cloud.json`，
  该目录的缓存在步骤 2 已生成，**未重跑摘要**。
- 场景 4 的目录没有缓存，摘要跑了一次并落盘（日志里 `字幕摘要结果` 恰一条），下次即命中。

### 6. 摘要失败时不落盘 —— 通过（单测覆盖，未在真实运行中制造失败）

`SubtitleCleanupServiceTest.summarizeDegradeDoesNotWriteContextFile`：
桩 `Optional.empty()` 后跑真实 `clean`，断言 `Files.exists(contextFile())` 为 `false`，
同时断言清洗照常完成。代码路径上，空 Optional 分支**没有** `writeJsonAtomically` 调用。

### 7. 断点复用 —— 通过

场景 3：0.104s 返回，产物路径不变，`retrySteps` 与 `untranslatedCount` 归零。

### 8. `-clean` 缺失时报错指明缺哪个文件、先跑哪一步 —— 通过（单测覆盖）

`SubtitleTranslationServiceTest`：断言抛 `IllegalStateException`，消息含缺失文件名与
`POST /api/subtitles/clean`。

### 9. 观测项落地并有真实素材读数 —— 通过

**批次句数/字数分布**（清洗，两条素材）：

| 素材 | 批数 | 每批句数 | 每批字数 | 触发的判据 |
|---|---|---|---|---|
| `BV1axNJ6REoa` | 3 | 20 / 20 / 2 | 542 / 545 / 59 | 句数 |
| `BV1C2ae6DEqW` | 16 | 均 20，末批 19 | 197 ~ 413，均值 ≈ 285 | 句数 |

**校验失败分类计数**：两条素材共 **19 批，`KEY_SET` 与 `TOO_MUCH_EDIT` 均为 0 次**。

### 10. 至少一条真实运行证据（learning-records）—— 通过

即本文档。另有 `zh-to-en-remnant.txt`（译文残留中文清单）与 `term-injection-ab.txt`（对照实验差异）。

## 附：术语注入的对照实验

**动机**：场景 2 发现 9/42 条译文含中文。要判断这是「规则的代价」还是「注入本身有害」，
做了对照：同一素材、同一参数、**只把术语表的 `terms` 清空**，重跑一次。

**结果**：42 条里 26 条不同。关键术语：

| 原文 | 有术语表 | 无术语表 |
|---|---|---|
| 震刀、弹刀、打铁 | `震刀、弹刀、打铁` | `Deflecting, parrying, and clashing blades` |
| 架势条 | `架势条` | `posture meter` |
| 气绝 | `气绝` | `staggered` |
| 弹刀（在只狼里） | `弹刀 in 只狼` | `deflecting in Sekiro` |
| 日耳曼 | `日耳曼` | `German` |
| 举顶格 | `举顶格` | `raising the sword overhead to block` |

**读法（含反面提醒）**：temperature 0.3，26 条差异里有一部分只是措辞抖动
（`these techniques useful` vs `these techniques actually useful`）。
但**关键术语的差异不是抖动**——`弹刀` vs `Deflect` 是两条不同指令导致的系统性差异。

**结论**：在**中文 → 英文**方向上，术语注入是净负面——模型本来会译对知名专有名词
（`弹刀 → Deflect`、`架势条 → posture meter`、`只狼 → Sekiro`），注入把它们钉成了中文。
但**这不否定术语注入本身**：外语 → 中文方向（主用例）的诉求恰是「保留原文」，
而本次实验没有覆盖那个方向。

## 未覆盖 / 已知缺口

- **外语 → 中文（主用例）方向未验证**：手上只有两条中文视频。这是本次最大的缺口，
  且它正是术语规则改动最需要验证的方向。补法：找一条英文或日文素材，先立验收基线。
- **术语是否有官方译名，模型判断不了**：实验显示它对知名词准、对冷门词靠猜
  （`举顶格` 给的是描述性意译）。项目所有者已定：这一项要靠**联网搜索**解决，
  另立任务，本次不动规则。
- 摘要失败路径、`-clean` 缺失路径未在真实运行中制造（仅单测覆盖）。
- 两条素材都是同一领域的解析类视频（剑术 / 游戏），词汇分布不代表全貌。
