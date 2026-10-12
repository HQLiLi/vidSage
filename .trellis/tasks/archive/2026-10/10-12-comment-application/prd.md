# 注释收口 · application 层

> **状态：planning，未开工。** 父任务 `10-12-comment-consolidation` 的**子任务 2**。
> **粒度标准在 `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令），不在这里也不在父 `design.md`**
> ——父 `design.md` 曾复制过一份标准，2026-10-12 已删（复制必然漂移，它停在了七条禁令的版本）。
> 需求与跨子任务验收见父 `prd.md`。**本层的 `implement.md` 在子任务 1
> 通过门 G1、标准冻结之后才写**——按**正典当前版本**执行，不是按父 `design.md` 的旧副本。

## Goal

按冻结后的标准收口 application 层 34 个文件（注释占比 41.7%，22,305 行中约 1,246 行纯注释）。

## 已知的层内重点

- **编排类**（`*Coordinator` / `SubtitlePipeline` / `VideoTaskService`）是重灾区：
  类注释里写管线形状、方法注释里写进度事件序列——前者上移 `package-info`，后者移 spec。
  已知实例：`SubtitleCleanupCoordinator` 类注释的「管线形状」、其 `clean(...)` 里的进度事件 `<ul>`。
- `SubtitleCleanupCoordinator#regenerate` 的注释里有「design 决策 2 / 5」与「2026-10-12 起」，
  决策正文在任务卡 `10-11-subtitle-keyword-regenerate/design.md` 里（已核实存在）。
- 本层有 34 个文件、多个 `package-info.java` 与 `message/`、`notify/`、`subscribe/` 等子包。

## 范围

**做**：`application/` 全包的注释收口。

**不做**：同父 `prd.md`「不做」四条；另**不动 `TaskProgressMessage` 的载荷契约描述**——
它是 SSE 快照形状的单源，改前先确认 `web` 层没有复述它。

## 验收标准（在父 AC 之外的本层条目）

- [ ] `application/` 下变更史 / 归档指针模式命中数为 0（清单见正典 §六，**不要硬编条数**）。
- [ ] `application/` 下的 Javadoc 块**只回答那四件事**（正典 §三 禁令 10）；`>15 行` 的块逐个复核，
      内容砍不动的进例外登记并写明理由。
- [ ] 编排类被移出的管线/事件描述，能在 `package-info.java` 或 `.trellis/spec/` 找到归宿。
- [ ] `mvn clean test` 全绿。
