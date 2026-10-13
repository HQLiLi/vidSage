# 注释收口 · web / shared / config 层

> **状态：planning，未开工。** 父任务 `10-12-comment-consolidation` 的**子任务 4**。
> **粒度标准在 `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令），不在这里也不在父 `design.md`**
> ——父 `design.md` 曾复制过一份标准，2026-10-12 已删（复制必然漂移）。需求与跨子任务验收见父 `prd.md`。
> **门 G1 已通过（2026-10-12，domain 试点与 application 两层均已收口归档）**；本层开工时按**正典当前版本**写 `implement.md`。

## Goal

按冻结后的标准收口三个包共 23 个文件：`web/`（10，39.9%）、`shared/`（8，45.4%）、
`config/`（5，36.2%）。

## 已知的层内重点

- **`web/dto/` 是 `@param` × `@Schema` 重复的集中地**（`TaskResponse` 12 字段、`TaskArtifactResponse` 3、
  `SubtitleKeywordsResponse` 3、`SubmitUrlRequest` 2、`TaskConflictResponse` 2、`RegenerateSubtitlesRequest` 1）。
  按父 `prd.md`「不做」：**不主动删任一侧**，只在两份文案已经不一致或腐坏时订正。
- **已确认的腐坏**：`web/dto/TaskResponse.java:45-47` 的 `from(...)` Javadoc 是断句残句——本层必订正。
- `web/dto/TaskResponse.java:19` 有变更史「（2026-10-02 两段式…）」。
- `api/` `ApiExceptionHandler`（104 行 / 55 注释）把异常 → HTTP 状态的映射理由写得很细：
  那属**负向契约**（哪类异常映射哪个码），多半是该留的，但「为什么不提前造异常体系」属决策，移 `design.md`。
- `config/` 的四个 `@ConfigurationProperties`：`@param` 里的**校准依据**（为什么是这个量级）移 `design.md`，
  但「缺席时绑定成 0 = 静默关闭，yml 里必须显式写」这类**坑**要留。
- `shared/subtitle/`、`shared/fs/`、`shared/progress/` 是三组工具，其中 `TermMatcher`（54.8%）、
  `SubtitleSegmentSplitter`（40.9%）注释密度高，且 `TermMatcher#hitTermsByAsr` 被 spec 点名为
  「命中判定只有一处实现」——它是**被 spec 引用的单源**，收口时保留判据描述。

## 范围

**做**：`web/`、`shared/`、`config/` 三包全部注释收口。

**不做**：同父 `prd.md`「不做」四条；另本层**不动 OpenAPI 文档产物**（`/v3/api-docs` 是运行时生成）。

## 验收标准（在父 AC 之外的本层条目）

- [ ] 三包下变更史 / 归档指针模式命中数为 0（清单见正典 §六）。
- [ ] 三包下的 Javadoc 块**只回答那四件事**（正典 §三 禁令 10）；`>15 行` 的块逐个复核，
      内容砍不动的进例外登记并写明理由。
- [ ] `TaskResponse.from` 的残句已订正（补全或整句删除），并登记在待订正清单。
- [ ] `@Schema` / `@param` 重复面**未被扩大**，且发现的不一致已订正。
- [ ] spec 点名引用的单源（`TermMatcher#hitTermsByAsr`、`CheckpointPolicy` 的调用方）判据描述仍在。
- [ ] `mvn clean test` 全绿。
