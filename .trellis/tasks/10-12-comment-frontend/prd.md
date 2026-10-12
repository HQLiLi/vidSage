# 注释收口 · 前端 src

> **状态：planning，未开工。** 父任务 `10-12-comment-consolidation` 的**子任务 6**，也是最后一个。
> **粒度标准在 `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令；§四 有 TS/Vue 例子），
> 不在这里也不在父 `design.md`**——父 `design.md` 曾复制过一份标准，2026-10-12 已删（复制必然漂移）。
> 需求与跨子任务验收见父 `prd.md`。
> **本层的 `implement.md` 在门 G1 通过、标准冻结之后才写**，按**正典当前版本**执行。

## Goal

按同一份正典的前端分节，收口 `frontend/src` 25 个文件（2,413 行、598 行注释，24.8%）。

## 已知的层内重点

- **变更史 5 处已定位**（前端全部命中）：

  | 位置 | 内容 |
  |---|---|
  | `api/request.ts:42` | 「全仓唯一的受控断言（2026-10-03 定调）」 |
  | `types/media.ts:4` | 「自原 src/api/types.ts 迁出（该文件已删除，2026-10-03 两级类型策略）」 |
  | `types/task.ts:4` | 同上 |
  | `types/transcript.ts:4` | 「准入依据（2026-10-03 两级类型策略）」 |
  | `views/workspace/index.vue:4` | 「入场动效见 design §4 #2」——指向任务卡的 design 章节号 |

- 密度最高的：`lib/gsap.ts`（90.9%）、`types/transcript.ts`（63.6%）、`types/media.ts`（60%）、
  `lib/day.ts`（57.9%）、`types/task.ts`（48.4%，157 行）。`types/` 三个文件注释里带着
  「两级类型策略」的**决策说明**——那属决策，移任务卡 `design.md`；但**准入规则本身**
  （`src/types/` 只收跨模块共享类型）是会被人踩的约束，留一句。

- **删除「design §N」这类章节号引用**：任务卡归档后章节号会漂移，留着是坏指针。

## 范围

**做**：`frontend/src` 全部注释收口。

**不做**：同父 `prd.md`「不做」四条；另**不改任何 TS 类型定义、组件模板、样式**；
**不动 `src/lib/gsap.ts` 的统一入口约定说明**（属负面约束：动画必须走统一入口）。

## 验收标准（在父 AC 之外的本层条目）

- [ ] `frontend/src` 下变更史 / 归档指针模式命中数为 0（清单见正典 §六，**不要硬编条数**）。
- [ ] `npm run type-check` 与 `npm run lint` 均通过（注释改动不应引入 lint 报错）。
- [ ] 「两级类型策略」的准入规则仍在 `types/` 里有一句可执行表述，决策说明能回查到任务卡。
- [ ] 前端 quality-guidelines 的注释章节已指向正典（此条实际由子任务 1 交付，本任务只核验）。
- [ ] 全部完成后装钩子并验 AC7（父 `prd.md`）。
