# 译文纠错待复核标记 — 技术设计

> 需求与验收见 `prd.md`；执行顺序见 `implement.md`。父任务 `../10-25-subtitle-manual-correction/design.md`
> 是全局正典，本任务落地后同步改它（§4.2 / §5 / §6.3）。

## 1. 为什么改存储口径而不是加字段

父设计 §4.2 原本写的是「译文纠错取**当时的原文**，用于差异展示与译文待复核判定」。
实现取成了「未经覆盖的机器原文」，于是用户改原文不会改变这个值，判定永远不翻转。

把译文纠错的 `source_text` 改成**保存时的生效原文**（叠过原文纠错），判定就退化成一次纯文本比较：

- 译文纠错：`source_text` ≠ 当前生效原文 → 用户当初照着的那句话变了 → 待复核；
- 原文纠错：仍比机器原文 —— 它要识别的是「机器产物被重新生成」，生效文本对它没有意义。

不新增列、不改表、不改 HTTP 形状：`source_text` 本来就是「这条纠错所依据的源文本」，
改成生效文本才是这句话的字面意思。

## 2. 后端改动

### 2.1 `SubtitleCorrectionService.save`

原文纠错不变（取机器 CLEANED 段文本）。译文纠错改成：

```java
SubtitleTrack effective = overlay.applyToCleanTrack(taskId, cleanTrack);
String basedOn = findSegment(effective, segmentId).map(TranscriptionSegment::text)
        .orElse(segment.text());
```

`cleanTrack` 与段存在性校验不变；`applyToCleanTrack` 已经有「没有可叠的纠错就原样返回」的契约，
不需要另判。

### 2.2 `SubtitleCorrectionOverlay.matchCorrections`

现在只拿机器 track 比一遍。改成两套文本：

- `machineTextById`：机器段文本（原文纠错比它）；
- `effectiveTextById`：机器文本 + 该任务的原文纠错（译文纠错比它）。

`matched`（id 还在不在）不变；`sourceChanged` 按字段分派。`matchWithoutCleanTrack` 保持
「一律 matched=true、sourceChanged=false」。

时序问题不存在：不靠 `updated_at` 排序判先后（`DATETIME` 只到秒），只比文本。

### 2.3 旧数据

回滚/迁移都不需要：库里历史译文纠错存的机器文本，语义上等价于「保存时生效原文 == 机器原文」，
只有用户当时已经改过原文的那些行会误报 `sourceChanged=true`；本机库里没有译文纠错行。
不写迁移脚本，设计里记一句即可。

## 3. 前端改动

- `stores/workspaceSubtitles.ts`：新增 computed `reviewCorrectionKeys`（`field=translation && sourceChanged`
  的 `项|语种|段 id` 集合）与函数 `translationNeedsReview(segmentId, lang)`，复用现有 `correctionKey`。
- `components/SubtitleCueRow.vue`：译文块末尾的提示改成互斥二选一——
  `cue.translationStale` → 「原文已改，译文待更新」；否则 `translationNeedsReview` → 「译文待复核：原文改过，请核对这条手改译文」。
- 保存后不用单独刷新列表：改动后重存译文纠错返回的 `sourceChanged=false` 会随 `applyCorrection` 进列表
  （保存幂等：原样重存不发请求，标记清不掉，这也是 AC5 只承诺「改动后重存」的原因）；
  改原文后该行处于 `translationStale=true`，标记本就不显示；重翻收口会走 `refresh()` 重拉纠错列表。

## 4. 失败模式

- 保存译文纠错时读不到 CLEANED：维持现状 409（不清洗就无从判定依据文本）。
- 译文纠错存的依据文本与当前生效文本相同：`sourceChanged=false`，不显示标记。
- 译文纠错本身失效（`matched=false`）：`sourceChanged` 一律 `false`（现状），标记不显示，失效由顶部提示接管。

## 5. 验证

- 后端：`mvn clean test-compile` + `mvn test`（全量），新增/调整单测覆盖两条判据与两种保存顺序。
- 前端：`npm run type-check` + `npm run lint`。
- 接口：在已完成任务上按「先存译文纠错 → 再改原文」的顺序走一遍，`GET /corrections` 应看到
  `sourceChanged=true`；反序为 `false`。
- UI：临时把某条译文纠错的 `source_text` 改旧（模拟「原文后来变过」），页面应出现「译文待复核」；
  改动后重存该条纠错，标记消失。
