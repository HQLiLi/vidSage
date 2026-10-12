# 注释收口 · domain 层试点 + 注释写法正典定稿

> **状态：planning，未开工。** 父任务 `10-12-comment-consolidation` 的**子任务 1**，也是全任务的**门 G1**。
> 需求与跨子任务验收见父 `prd.md`；**粒度标准见 `.trellis/spec/guides/comment-guidelines.md`（正典）**
> ——本层不另写标准，父 `design.md` 里那份副本已于 2026-10-12 删除。
> 本文件只写本子任务的目标、边界、工作集与验收。

## Goal

在 domain 层——全项目注释最重的一层（56 文件，53.1%）——做一轮**真实收口**，用真实结果把粒度标准
钉死；并顺手把由它派生的**注释写法正典**与两份 `quality-guidelines` 的指针改到位。

**它的第二重身份是校准轮**：标准写进 `design.md` 时还是纸面假设，跑完 19 个文件才知道哪条规则
在真实代码上站不住、哪条会被误用。跑完请用户过目，确认后其余五个子任务才开工。

## 工作集（19 + 3，2026-10-12 摸底）

**A 组 · 带变更史/决策标记（14 文件）**

```
subtitle/package-info.java            task/package-info.java          media/package-info.java
subtitle/port/SubtitleContextSummarizer.java
subtitle/port/SubtitleCleanser.java
subtitle/model/cleanup/SubtitleTrack.java
subtitle/model/keyword/SubtitleKeywords.java
subtitle/model/common/SubtitleArtifactSpec.java
subtitle/policy/CheckpointPolicy.java         subtitle/policy/TermVerification.java
media/model/AudioArtifact.java
task/VideoTask.java                   task/policy/TaskStageEvent.java
task/model/TranslationStatus.java
```

**B 组 · 有 >15 行 Javadoc 块（12 文件；与 A 组并集 19）**

```
subtitle/model/term/VerifiedTerm.java         media/port/MediaAcquirer.java
shared/model/SourceUri.java                   shared/model/WorkspaceKey.java
media/model/AudioSpec.java
```

**C 组 · 对照面（3 文件，中位数那批）**——用来验证标准是否会「误伤」本来合格的注释：

```
transcription/model/TranscriptionCommand.java   ← 既有 spec 拿它当正面例子
transcription/model/AudioSource.java            ← 同上
transcription/port/ASRProvider.java             ← 同上
```

C 组的期望结论是**基本不动**。如果收口时发现它们也得大改，说明标准写歪了——那正是校准轮要暴露的。

## 范围

**做**：上述 22 个文件的注释收口；新建 `.trellis/spec/guides/comment-guidelines.md`；
两份 `quality-guidelines.md` 的注释章节改为指针。

**不做**：

- **不改任何代码行为**（父 `prd.md`「不做」全适用）。
- **不动 `@Schema` 落位**：`domain/transcription/model/TranscriptionSegment.java`、
  `TranscriptionResult.java`、`ToolDiagnosticReport.java` 等有 `@param` / `@Schema` 重复的，
  只在**两者不一致**时订正，不主动删任一侧。
- **不碰 domain 里 C 组以外的中位数文件**（37 个），除非收口时正好发现腐坏。

## 验收标准

- [ ] **本层 AC1**：`domain/` 下变更史 / 归档指针模式的 `grep -rn` 命中数为 0（清单见正典 §六）。
- [ ] **本层 AC2**：`domain/` 下无 >15 行的 Javadoc 块，或已在父 `implement.md` 例外登记表登记。
- [ ] **本层 AC3**：被移出的设计理由可在 `package-info.java` / 任务卡 `design.md` 找到对应表述
      （三个 `package-info` 本就在工作集里，正好承接）。
- [ ] **本层 AC4**：腐坏与失实逐处订正，清单写进本子任务 `implement.md`。
- [ ] **AC5 正典落地**：`guides/comment-guidelines.md` 含父 `implement.md` §4 骨架的六节；
      两份 `quality-guidelines.md` 已改指针。
- [ ] **AC6 行为不变**：`mvn clean test` 全绿（基线实测）。
- [ ] **G1**：用户审过 domain 的 diff 与正典草稿并确认——**这一条不过，其余子任务不开工**。

## Notes

- 本层是全任务的**标准冻结依据**。收口过程中凡遇到 `design.md` 没覆盖的情形，就地记进
  本子任务 `implement.md` 的「标准补充」小节，不要默默按自己的判断处理。
