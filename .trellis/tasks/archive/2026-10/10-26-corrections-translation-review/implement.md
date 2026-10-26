# 译文纠错待复核标记 — 执行计划

## 前置

- 从 `dev` 开 `feature/corrections-translation-review`，合回 `dev`。
- 后端判编译 `mvn clean test-compile`、跑测试 `mvn test`（不用增量 `compile` 判绿）。
- 基线：本任务开始时全量 `mvn test` 为 91 类 / 842 tests（0 fail / 1 skipped），只增不减。

## 步骤 1：后端

- [ ] `SubtitleCorrectionService.save`：译文纠错的 `source_text` 取生效原文；原文纠错保持机器原文。
- [ ] `SubtitleCorrectionOverlay.matchCorrections`：原文纠错比机器文本、译文纠错比生效文本。
- [ ] 更新相应 Javadoc（`source_text` 与 `sourceChanged` 的口径）。
- [ ] 单测：
      - 保存：有原文纠错时译文纠错落生效文本；无时落机器文本。
      - 判定：先存译文后改原文 → `true`；先改原文后存译文 → `false`。
      - 原文纠错：机器文本变化才 `true`（维持）。

**门 G1**：`mvn clean test-compile` 与 `mvn test` 全绿。

## 步骤 2：前端

- [ ] store 加 `translationNeedsReview(segmentId, lang)`（`field=translation && sourceChanged`）。
- [ ] `SubtitleCueRow` 的译文提示改成互斥：待更新 / 待复核。
- [ ] `npm run type-check` + `npm run lint`。

**门 G2**：双检查绿；页面走查一次标记切换。

## 步骤 3：文档

- [ ] 父 PRD 追加 AC9（待复核出现与清除）。
- [ ] 父设计 §4.2 改 `source_text` 口径、§6.3 补标记规则、§5 写明问题 2 的裁定。
- [ ] 本任务 `design.md` 与实现保持一致（有偏差就改）。

## 步骤 4：验收与收尾

- [ ] 接口走查：两种保存顺序的 `sourceChanged`。
- [ ] UI 走查：标记出现 / 改动后重存消失（原样重存不发请求）。
- [ ] 清理临时数据，确认磁盘产物哈希未变。
- [ ] 提交、合并、归档（逐门批准）。
