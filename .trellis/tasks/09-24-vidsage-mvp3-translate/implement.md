# MVP-3 LLM 翻译与双语 SRT — 实施计划

> 需求见 `prd.md`，技术设计见 `design.md`（下文 §n 均指该文件）。
> 每个阶段末尾是一个**审查门**：改完即停、给所有者看，批准后才进下一步。

## 环境与命令

```bash
# Git Bash 下必须显式指定 JDK 21，否则报「不支持发行版本 21」
cd backend
JAVA_HOME=/f/java_soft/java21 mvn -q compile
JAVA_HOME=/f/java_soft/java21 mvn test

# 注意：surefire-reports 会残留上一次的报告，统计用例数前先删目录
rm -rf backend/target/surefire-reports
```

基线（开工前先量一次，作为对照）：`mvn test` 全绿，用例数记进本文件末尾的「实测记录」。
冒烟实例起停见文末注意事项。

---

## 步骤 1：观测项（design §九）

**先做这个**：它是两个悬置决策的取证手段，且完全不改行为，风险最低。

1.1 `SubtitleCleanser.clean` 的批次 DEBUG 日志补「本批字数」——切批循环里已累加 `chars`，
带入日志即可，不动切批逻辑。

1.2 `validate` 的返回由 `String` 改为 `ValidationFailure(kind, detail)`：
`FailureKind { KEY_SET, TOO_MUCH_EDIT }`，通过时仍返回 `null`；
`agentLoop` 按 `kind()` 计数并记 DEBUG；`appendRetry` 用 `detail()`。
同步改 `SubtitleCleanserTest` 里对 `validate` 的直测断言。

**验证**：`mvn test` 全绿，用例数不少于基线（`validate` 的测试改断言不改条数）。

**审查门 1**：把 1.1 / 1.2 的 diff 给所有者看。**此时同步做一次真实素材读数**——
跑一条已有工作目录的 `POST /api/subtitles/clean`，从日志里取批次句数/字数分布，
结果填进文末「实测记录」，它将决定步骤 4 的批参数初值。

## 步骤 2：上下文缓存与源语种（design §三）

2.1 `SubtitleContext` 加 `sourceLanguage`（紧凑构造器兜底空串，`empty()` 同步）。
2.2 `ChatClientConfig.ContentSummarizer` 的 system prompt 加第 3 项输出：语种短码，拿不准给空串。
2.3 `SubtitleContextSummarizer.summarize` 返回 `Optional<SubtitleContext>`；成功才 present，
降级返回空 Optional。同步改 `SubtitleContextSummarizerTest` 与 `SubtitleCleanupService` 的调用点。
2.4 `ArtifactFiles` 加 `writeJsonAtomically` / `readJsonOrNull`（design §3.3 的语义边界：
`readJsonOrNull` 只服务断点形状的读，`readRaw` 不并入）。
2.5 `SubtitleArtifactNames.contextFileName(route)`。
2.6 `SubtitleCleanupService` 接上下文缓存（design §3.2 那段固定形状），替换现有的直接 `summarize` 调用。
2.7 `hitTerms` 从 `SubtitleCleanser` 提到 `utils/subtitle/TermMatcher`（design §4.3），
清洗侧改为调用它；`SubtitleCleanserTest` 里对应的直测迁到新的 `TermMatcherTest`。

**验证**：`mvn test` 全绿；`ArtifactFilesTest` / `SubtitleArtifactNamesTest` / `SubtitleContextSummarizerTest`
各补新方法的用例。

**审查门 2**：重点看 2.6 的三行分支形状与 2.7 的搬迁是否只搬不改行为。

## 步骤 3：翻译契约与渲染（design §二、§五、§六）

3.1 `model/subtitle/TranslatedSegment` / `TranslatedTrack` / `TranslatedSubtitle`。
3.2 `SrtRenderer.toBilingualSrt`（译文==原文时该条只渲染一行）。
3.3 `SubtitleArtifactNames` 加 `translateSegmentsFileName` / `translateSrtFileName`（带目标语种指纹）。

**验证**：`SrtRendererTest` / `SubtitleArtifactNamesTest` 补用例——
双语渲染、译文==原文单行、指纹串形状（`subtitles-asr-cloud-translate-zh.srt`）。

**审查门 3**：契约形状是冻结面，逐字段过一遍再往下。

## 步骤 4：翻译器（design §四）

4.1 `config/SubtitleTranslateProperties`（§七 的字段）。
4.2 `application.yml` + `src/test/resources/application.yml` 加 `vidsage.subtitle.translate.*`
（批参数初值取步骤 1 的读数；若读数显示清洗的句数先到，翻译的字符初值按 §4.2 的膨胀系数折算）。
4.3 `ChatClientConfig.subtitleTranslateChatClient`（§4.4 的提示词）。
4.4 `provider/llm/SubtitleTranslator`：切批（双判据）、三路上下文、跨批历史按对裁剪、
多轮重试、键集与空译文校验、用尽重试回退原文并计 `untranslatedCount`。

**验证**：`SubtitleTranslatorTest`（mock `ChatClient`，不起容器）覆盖：
键集不一致触发重试、空译文触发重试、用尽重试回退原文、跨批历史成对裁剪、
术语参考只列本批命中项、失败往返不进跨批历史。

**审查门 4**：翻译器是全任务的核心，逐个用例对着 design §4.5 核。

## 步骤 5：编排与 HTTP 面（design §八）

5.1 `service/SubtitleTranslationService`（§8.1 的 12 步）。
5.2 `web/dto/TranslatedSubtitleResponse`（展平 + `@Schema`）。
5.3 `SubtitleController` 加 `POST /api/subtitles/translate` + 类注释扩写「临时面」范围。

**验证**：`SubtitleTranslationServiceTest` 覆盖断点复用、`-clean` 缺失报错、跳过分支、
摘要降级不落盘；`SubtitleControllerTest` 覆盖请求体歧义（url/localPath 都传/都不传）。

**审查门 5**：全部单测绿后停，给所有者看整体 diff。

## 步骤 6：端到端真实调用

依次跑（同一媒体输入）：

```bash
# 1) 产原始字幕（若工作目录已有可跳过）
# 2) 清洗
curl -X POST localhost:8080/api/subtitles/clean -H 'Content-Type: application/json' -d '{"localPath":"<media>"}'
# 3) 翻译
curl -X POST localhost:8080/api/subtitles/translate -H 'Content-Type: application/json' -d '{"localPath":"<media>"}'
# 4) 再跑一次翻译，验证断点复用（LLM 不应被调用，日志里无批次行）
```

逐条核 `prd.md` 的验收标准，特别是：时间轴逐条对齐、术语保留原文、术语译法全片一致、
第二次翻译不重复摘要（日志里 `SubtitleContextSummarizer` 无调用）、跳过分支。

证据落 `learning-records`（真实运行记录，含耗时与统计）。

**审查门 6**：验收标准逐条过，未过的写明原因（不勾）。

## 步骤 7：规范回写与收尾

7.1 若步骤 2.7 的 `TermMatcher` 改变了 `utils/` 的示例清单，
同步 `directory-structure.md` 的「参照」一行。
7.2 若 `ArtifactFiles` 的职责描述因 Jackson 依赖变化，同步该类的 Javadoc 与
`directory-structure.md` 中「`utils/` 允许 Jackson」的既有表述（若原文已覆盖则不改）。
7.3 把步骤 1 的实测读数与步骤 6 的真实运行记录填进本文件文末。
7.4 规范回写提交与实现提交分开（沿用既有惯例）。

**审查门 7**：Phase 3 收尾，随后按分支工作流合并回 `dev`。

---

## 实测记录

> 开工后逐步填。空白即「未做」。

### 基线

- 开工前 `mvn test`：_待填_ 类 / _待填_ 用例，全绿

### 步骤 1：清洗批次的句数与字数分布（决定翻译批参数）

| 素材 | 批次序号 | 句数 | 字数 | 触发的判据 |
|---|---|---|---|---|
| _待填_ | | | | |

结论：_待填_

### 步骤 1：清洗校验失败分类计数

| 素材 | `KEY_SET` 次数 | `TOO_MUCH_EDIT` 次数 | 说明 |
|---|---|---|---|
| _待填_ | | | |

结论（守卫去留的取证）：_待填_

### 步骤 6：端到端真实运行

- 媒体输入：_待填_
- 清洗耗时 / 统计：_待填_
- 翻译耗时 / 统计：_待填_
- 断点复用第二次：_待填_
- 验收标准逐条结果：_待填_

---

## 注意事项

- **冒烟实例的进程清理**：起过 `spring-boot:run` 之后，`TaskStop` 杀不掉它派生的子 JVM，
  必须按 PID `taskkill`（`netstat -ano | grep 8080` 找 PID）。
- **不要动 `.codex/config.toml`**（所有者自己改权限时动的，未提交）。
- 每次测量用例数前先 `rm -rf target/surefire-reports`，否则会读进陈旧报告。
- 翻译端点同步阻塞，长视频会跑很久；实测优先用短视频素材。
