# 译文纠错待复核标记

> 父任务：`10-25-subtitle-manual-correction`。来源：父任务集成评审（G4）问题 1。

## Goal

把父 PRD R5 的后半句补上：「重翻后…某条的原文也变过时标『译文待复核』」。用户手改的译文
在原文改过、重翻之后仍然盖在新机器译文上，界面上必须明确提示这条译文需要用户自己核对。

## 背景（评审事实）

- 强翻后新译文文件的 `sourceText` 来自改后原文，与页面原文文本相同 → `translationStale` 为 `false`。
- 覆盖层按设计仍把手改译文叠在机器译文之上（R5 前半句是对的）。
- 后端 `sourceChanged` 比的是「纠错保存时记的 `source_text`」与「磁盘上未叠覆盖的机器原文」；
  用户改原文只是覆盖层记录，机器文件一个字节没变 → 永远 `false`。前端也从未消费这个字段。
- 父 AC1–AC8 没有一条覆盖「待复核」，所以三个子任务全绿也没暴露。

## Requirements

R1 **存储口径**：译文纠错的 `source_text` 存「用户保存这条纠错时看到的原文」，
即叠过原文纠错之后的文本；原文纠错仍存机器原文（改前的那份）。

R2 **判据**：`sourceChanged` 分字段判——
- 译文纠错：存储的 `source_text` 与**当前生效原文**（叠过原文纠错）不同 → `true`；
- 原文纠错：存储的 `source_text` 与**当前机器原文**不同 → `true`（维持现状，用于识别机器产物被重生成）。

R3 **前端标记**：与「原文已改，译文待更新」互斥——
- `translationStale=true`：照旧显示「原文已改，译文待更新」；
- `translationStale=false` 且该行当前语种有一条 `sourceChanged=true` 的译文纠错：显示「译文待复核」。

R4 **文档**：
- 父 PRD 追加一条集成 AC 覆盖本标记；
- 父设计 §4.2 的 `source_text` 口径改为现状，§6.3 补标记规则；
- 父设计 §5 写明问题 2 的裁定：译文产物的原文列**不**叠原文纠错（理由见下）。

R5 **不做**：不改 `translationStale` 判据、不做单条重翻、不改磁盘产物、不加新列、不改 HTTP 形状。

## 问题 2 的口径（一并落文档）

译文产物（`translated-segments-<lang>.json` / 双语 SRT）里的原文列保持机器原文，不叠原文纠错。
理由：页面判「译文待更新」靠的就是「译文自带 `sourceText` ≠ 当前原文」；一旦把原文列也叠上纠错，
两边相等，AC4 的过期提示直接失效。这类不一致只存在于「改了原文还没重翻」的窗口，此时该行本来就
标着「原文已改，译文待更新」，重翻一次即消失。单条重翻不在范围内。

## Acceptance Criteria

- [ ] AC1 保存译文纠错时，`source_text` 落的是生效原文（有原文纠错时是改后文本）；原文纠错仍落机器原文。（单测）
- [ ] AC2 先存译文纠错、后改原文 → 该译文纠错 `sourceChanged=true`；先改原文、后存译文纠错 → `false`。（单测 + 接口验证）
- [ ] AC3 原文纠错的 `sourceChanged` 口径不变（机器原文变了才为 `true`）。（单测）
- [ ] AC4 页面：`translationStale=true` 时只显示「原文已改，译文待更新」；`translationStale=false` 且有 `sourceChanged` 的译文纠错时显示「译文待复核」。
- [ ] AC5 改动该条译文后重存，「译文待复核」消失（保存幂等：原样重存不发请求，清不掉）。
- [ ] AC6 后端 `mvn clean test-compile` + `mvn test` 全绿（测试数只增不减）；前端 `type-check` + `lint` 绿。
- [ ] AC7 父 PRD / 父设计按 R4 更新完毕。

## 依赖

- 依赖已归档的 `10-25-corrections-data-api`（纠错表与 `sourceChanged` 字段）与
  `10-25-corrections-frontend`（行内编辑与译文行渲染）。
