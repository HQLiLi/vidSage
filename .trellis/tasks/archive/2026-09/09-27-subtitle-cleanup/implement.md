# 字幕纠错与断句优化 — 实现清单

> 本文是执行顺序与验收门径。**实现者 = 项目所有者本人**（协作模式：你写码，我按门径审查 + 陪你验证）。
> 每步完成跑对应验证命令再进下一步；完成全部后回头做 `2.2` 全范围检查与 commit。

前置约定（本任务内不变）：
- 分支 `feature/subtitle-cleanup`（已开），base = `dev`。
- **不修改** `SubtitleService`/`SubtitleController` 现有路径；本任务全部新增。
- 许可证红线：VideoCaptioner（GPL-3.0）只学思路，**prompt 与代码全用自己话重写**。
- 构建环境：Git Bash 下需显式 JDK 21（`JAVA_HOME=F:\java_soft\java21`），见 memory。

---

## 1. 规划定稿

- [x] `prd.md` 已定（决策 5 含可选 `reference` 术语表——实现时先接可选字段，缺省空走主流程）。
- [x] `design.md` 已定。

## 2. 准备上下文字（spec / 研究，供写码参考）

- [x] 读 backend spec：`provider-pattern.md`、`quality-guidelines.md`、`error-handling.md`、`directory-structure.md`（写新类前必读）。
- [x] 读参考源码（设计依据）：`VideoCaptioner/core/split/sentence.md`、`core/split/semantic.md`、`core/optimize/optimize.py`、`core/prompts/optimize/subtitle.md`。
- [ ] `implement.jsonl` / `check.jsonl` 各写入 ≥1 条真实 spec/研究路径（子 agent 上下文；本任务主要由你本人写、可空，但 `task.py start` 校验会查）。

## 3. 代码实现（按依赖序，每步可单测）

### 3.1 `SubtitleCleanupProperties`（配置 ✅ 已完+测绿）
- [x] `config/SubtitleCleanupProperties`：`vidsage.subtitle.cleanup`，字段 `maxSegmentMs=8000`/`batchSize=20`/`maxSteps=3`/`similarityNormal=0.7`/`similarityShortText=0.3`（基本类型，前缀与 yml `subtitle.cleanup` 对齐）。
- [x] main + test 两处 `application.yml` 补 `vidsage.subtitle.cleanup.*`。
- [x] 验证：`VidSageApplicationTests`（contextLoads）确认前缀能绑定不报错。

### 3.2 相似度工具 `TextSimilarity`（✅ 已由主会话写完+测绿）
- [x] `com.vidsage.utils.subtitle.TextSimilarity.ratio(a,b)`：LCS `2*M/(len-a+len-b)`，对齐 difflib；`null`→空串，双空→1.0。包私有（仅同包 Correcter 用）。
- [x] `TextSimilarityTest` 5 例绿。

### 3.3 切分器 `SubtitleSegmentSplitter`（✅ 已由主会话写完+测绿）
- [x] `com.vidsage.utils.subtitle.SubtitleSegmentSplitter`（构造传 `maxSegmentMs`，非 Spring bean，由服务 new；`public` 供跨包）：预算字符窗+标点断点+字符比硬切兜底，时间按字符比例映射、末段收口守恒，空文本段丢弃。
- [x] 测试含：超长有分隔符 / 无分隔符硬切 / 短段保留 / 空段丢弃 / 升序——6 例绿。

### 3.4 LLM 纠错 `SubtitleCorrector`（✅ 全完+测绿）
- [x] `SubtitleCorrector`（`com.vidsage.utils.subtitle`，`public` 供跨包）+ 嵌套 `Llm` 函数式接口 + `CorrectionResult` record：system/user prompt（自己话重写）、KEY JSON 进出、守卫（键一致 + 相似度阈值 normal/shortText）、agent loop ≤ maxSteps、全败保留原文、剥 ```json 围栏、统计 correctedCount/retrySteps。7 例测绿。
- [x] `config/SubtitleCleanupConfig`：`@Bean SubtitleCorrector.Llm` 接 spring-ai `ChatModel`（API 2.0.1：`chatModel.call(new SystemMessage(s), new UserMessage(u))`）。容器装配由 `VidSageApplicationTests` 实测通过。

### 3.5 `SubtitleCleanupService`（编排 ✅ 已完+测绿）
- [x] 依赖注入 `WorkspaceService/ASRProvider/ObjectMapper/Props/Llm`；卫语句早于 I/O。
- [x] raw 缺失 → ISE「先跑 POST /api/subtitles」；断点复用在 `-clean.json` 非空时跳过。
- [x] 写 `-clean.json`（检查点）→ `-clean.srt`（原子替换）；复用 `SubtitleService` 的 `segmentsFileName/routeToken/toSrt`。
- [x] `SubtitleCleanupServiceTest` 4 例绿（写产物 / 断点复用跳过 LLM / raw 缺失 / 空输入）。

### 3.6 HTTP 入口（✅ 已完+测绿）
- [x] `POST /api/subtitles/clean`（复用 `toMediaInput`；`reference` 走可选 `@RequestParam`）。
- [x] `CleanedSubtitleResponse`（`web/dto/`，展平映射）+ model 侧 `CleanedSubtitle`。
- [x] `SubtitleControllerTest` 补 `/clean` 用例 + `@MockitoBean SubtitleCleanupService`。4 例绿。

## 4. 全量验证（门径）

- [ ] `mvn test` 全绿（先删 `surefire-reports` 再量，防陈旧报告——见 memory）。
- [ ] 真实运行证据：用 `backend/data/works/BV1axNJ6...` 的原始 segments，调用 `POST /api/subtitles/clean`：
  - 断言：`-clean` 产物生成、原始产物未被覆盖、超长段被切开、`防关`/`纳尔人` 类错字被修、`-clean` 可断点复用（二次调用跳过）。
- 命令（示例）：`JAVA_HOME=F:\java_soft\java21 mvn spring-boot:run` 后 curl POST /api/subtitles/clean。

## 5. 收尾

- [ ] 写 learning-records / 回写本任务研究 few 实测结论（间隔断点、时间映射是否好用）。
- [ ] `2.2` 全范围检查（spec 合规 / 类型 / lint / 跨层数据流 / 断点复现）。
- [ ] 我按分支规矩走：**在此停下等你审查 → 你批准后 commit → 你批准后合并 feature 回 dev → 你批准后归档**。未归档前每轮回复提醒。

## 回滚点

- 任一步出错：删本任务新增类即可回到 2b 原状（不碰现有多改路径）；产物回滚删 `-clean` 文件；断点回滚删 `-clean.json`。