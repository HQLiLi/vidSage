# 字幕纠错与断句优化（翻译前置）— PRD

> Epic 路线图见 `../09-24-vidsage/prd.md`。本任务位于 **MVP-2（字幕获取）之后、MVP-3（LLM 翻译）之前**，是为翻译提供一份「干净」的输入。
> 参考实现（设计依据，非源码抄取）：`../09-24-vidsage/research/oss-references.md` 第三节 + 本地 VideoCaptioner 源码
> `/f/learning_projects/asr_references/VideoCaptioner/videocaptioner/core/split/` 与
> `/f/learning_projects/asr_references/VideoCaptioner/videocaptioner/core/optimize/`。

## 背景

MVP-2b（已归档）产出的字幕来自云端 ASR，实测（`backend/data/works/BV1axNJ6.../subtitles-asr-cloud.srt`）暴露两类短板：

1. **ASR 错字 / 口语残渣**：如「我防关这边露出另外一破绽」（应为「防到/防过」「另外一个」）、
   「好的纳尔人」（术语误识别）等；字面错误会原样带进双语 SRT。
2. **超长段**：如 `00:00:50,910 --> 00:01:06,270`（15 秒一句、约 70 字）。翻译以 segment 为基本单元，
   超长段让「上下文整段感知」和「双语排版对齐」都难以做好。

参考项目 VideoCaptioner 的字幕处理顺序是 **Split（断句）→ Optimize（LLM 纠错）→ Translate（翻译）**，
`optimize` 默认开启。本任务照该设计对齐，但用 vidSage 自己的实现与其对话，不抄 GPL-3.0 源码。

## 范围（源需求）

对一个「原始 segments」（MVP-2b 产出的 `segments-<route>.json`）做两步处理，产出清洗后的字幕：

1. **断句切分（split）**：把超长段按「时长上限 + 标点/空格断点」切分为多个可读段，时间戳按比例或按静音映射。
2. **LLM 纠错（optimize）**：只改识别错误、清口头语残渣、规范标点；**保持原语言、不改写原义**（相似度守卫）；支持可选参考术语表纠正误识别术语。

## 规划期决策（每条需项目所有者拍板）

| # | 决策 | 推荐结论 | 理由 |
|---|---|---|---|
| 1 | 落点 | **独立 `SubtitleCleanupService`**，读原始 segments → 产出 cleaned segments+SRT | 独立可验证、不污染 2b 原始产物与断点判据；MVP-3 以 cleaned 为输入 |
| 2 | 切分算法 | **第一版确定性切分**（时长上限 + 标点/空格断点），不引 LLM split | 对付 15s 超长段最便宜有效；LLM 语义切分暂省 |
| 3 | 纠错 LLM | DeepSeek（复用已装配 spring-ai deepseek） | 全片唯一 LLM 引擎已定（epic PRD 源需求 3） |
| 4 | 守卫 | 键全集一致 + 相似度阈值（≥70%，≤10 字 ≥30%），agent loop 重试 ≤3，失败保留原文 | 强制「最小改动」，防 LLM 二次创作 |
| 5 | 参考术语 | 第一版支持**可选** reference 术语表（纠正「纳尔人」这类） | 是纠错核心能力；缺省为空不影响主流程 |

## 不做

- **LLM 语义切分**（第二版再议；第一版确定性切分不够再上）。
- **说话人分离 / diarization**：epic 未决产品题，`TranscriptionSegment` 不预留 `speakerId`。
- **内容寻址缓存**：本任务只用「产物存在即跳过」粗粒度断点。
- **翻译**（MVP-3 的事）。
- **本地 FunASR / 平台字幕路线**：同源需求，非本任务。

## 验收标准

- [ ] 输入一份 MVP-2b 原始 segments JSON，产出 cleaned segments JSON + SRT，文件名带 `-clean` 指纹，且不覆盖原始产物。
- [ ] 超长段（如 >8 秒）被切分为多个可读段，时间戳合理、总和与原段一致。
- [ ] 从真实 SRT 抽取的错例（`防关`→`防到/防过`、`纳尔人`、行内冗余口语）在清理后被修正；验证用真实运行证据而非 mock。
- [ ] LLM 不改写原义：清理后与原文本相似度守卫生效（有回归测试覆盖阈值兜底与失败保留原文）。
- [ ] 断点复用：已有 cleaned segments 时跳过重跑（切分/纠错都不重复执行）。
- [ ] 全流程留至少一条真实运行证据。

## 参考纪律（照搬自 big epic PRD）

1. **索引不是规格**：动手前按 oss-references.md 给路径读源码原文（split/optimize 两模块），只读摘要等于二手转述。
2. **许可证红线**：VideoCaptioner 是 GPL-3.0，**一行都不能抄**；只抄「思路 / 算法 / prompt 文案语义 / 接口契约」——读懂后用自己的话 + Java 重写。video 引用提示词照语义重写，不整段复制。
3. **实测优先于推断**：切分与纠错的边界/守卫行为以真实 SRT 实测为准，结论回写本任务研究或 learning-records。