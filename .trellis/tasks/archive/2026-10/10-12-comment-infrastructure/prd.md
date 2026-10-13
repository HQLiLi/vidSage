# 注释收口 · infrastructure 层

> **状态：planning，未开工。** 父任务 `10-12-comment-consolidation` 的**子任务 3**。
> **粒度标准在 `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令），不在这里也不在父 `design.md`**
> ——父 `design.md` 曾复制过一份标准，2026-10-12 已删（复制必然漂移）。需求与跨子任务验收见父 `prd.md`。
> **门 G1 已通过（2026-10-12）**；本层 `implement.md` 已按**正典当前版本**写成，任务状态 in_progress。

## Goal

按冻结后的标准收口 infrastructure 层 31 个文件（注释占比 37.1%，约 1,672 行纯注释，是全项目
**绝对量最大**的一层）。

## 已知的层内重点

- **LLM worker** 是大块头：`LlmSubtitleCleanser.java`（684 行 / 263 行注释 / 38.5%）、
  `LlmSubtitleTranslator.java`（582 / 203）、`LlmSubtitleContextSummarizer.java`。
  其注释同时混着：提示词设计的为什么（→ `design.md`）、掉行/重试的实现备注（→ 行内留）、
  相似度守卫的判据（→ 属 spec 的「守卫型校验」一节，已存在）。
  注意：`LlmSubtitleCleanser#baselineOf` 那类**守卫判断**是**正典 §三「私有方法 / 行内注释」**
  点名的「例外必须留」。
- `ProcessToolDiagnostics.java`（368 行 / 140 注释）、`ExternalProcessRunner.java`：外部工具适配器，
  注释里多是与真实工具行为搏斗得来的坑——这类**多为该留的**，收口时别当噪音删。
- `ytdlp/`、`ffmpeg/`、`dashscope/` 三个 provider 包各有自己的 `package-info` 归属问题。

## 范围

**做**：`infrastructure/` 全包的注释收口。

**不做**：同父 `prd.md`「不做」四条。另**不动 pom 里 `<exclusion>` 的说明性 XML 注释**——
那是依赖冲突的负面约束，属「删了会踩坑」那一类。

## 验收标准（在父 AC 之外的本层条目）

- [ ] `infrastructure/` 下变更史 / 归档指针模式命中数为 0（清单见正典 §六）。
- [ ] `infrastructure/` 下的 Javadoc 块**只回答那四件事**（正典 §三 禁令 10）；`>15 行` 的块逐个复核，
      内容砍不动的进例外登记并写明理由。
- [ ] 提示词设计类说明已上移到任务卡 `design.md`（查得到），不是简单删掉。
- [ ] 外部工具坑位注释**未被误删**：抽查 `ExternalProcessRunner` / `YtDlpMediaAcquirer`，
      每条留下的行内注释都能说出「删了会踩什么坑」。
- [ ] `mvn clean test` 全绿。
