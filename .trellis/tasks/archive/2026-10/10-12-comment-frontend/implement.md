# frontend 层 · 执行计划

> 父 `implement.md` §1 的标准动作（**A→E**）全程适用，本文件只补**本层的清单与门**。
> **粒度标准 = `.trellis/spec/guides/comment-guidelines.md`（正典 · 十三条禁令）**，此处不重复一字。
> 父 `prd.md` 的「不做」四条同样适用（尤其**不改一个表达式、不动一个签名**、**diff 里不许出现非注释行**）。
> 本层额外的「不做」（本层 `prd.md` 范围节）：**不改任何 TS 类型定义、组件模板、样式**；
> **不动 `src/lib/gsap.ts` 的统一入口约定说明**（属负面约束：动画必须走统一入口）。
>
> **状态：planning（2026-10-13 建）。** §0.1 六条筛选线已实测（只读扫描，未动文件）；§1–§3 的表随执行填。

## 0. 执行顺序

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工作集实测：六条筛选线 + 块统计（§0.1） | ✅ |
| 1 | **归档指针族 A · `design §N`**（14 处 / 9 文件） | ✅ |
| 2 | **归档指针族 B · 任务卡需求号 `R# / D# / AC#`**（8 处 / 4 文件）——本层新发现，父 prd 未列 | ✅ |
| 3 | **变更史族**（`2026-10-03` ×4 + 自然语言变体「按原型验证…重写」「原型验证的绑定口径」「原…修复先例」） | ✅ |
| 4 | **`@/types/*` 三文件**：删迁出史、**保准入规则**（prd 明确点名的保留项） | ✅ |
| 5 | **层级错位与自我辩护**：`request.ts` 门面长块、`useTaskEvents` 返回类型句、`useTranscriptPlayer` 返回类型句 | ✅ |
| 6 | **全层块全量复核**：`≥6 行` 的 19 块逐块读（其中 7 块不在上面各族） | ✅ |
| 7 | 自检（§5）+ 报审查（§6） | ✅ |

## 0.1 工作集（2026-10-13 实测）

**27 个 `.ts` / `.vue` 文件** / 2,440 行 / 595 注释行（**24.4%**）/ 210 个 JSDoc 块。
块分布：`1–5 行 ×191`、`6–11 行 ×13`、`12–15 行 ×4`、`≥16 行 ×2`。

> **收口后实测（2026-10-13）**：2,418 行 / 573 注释行（**23.7%**）/ 210 块不变；
> `1–5 ×193`、`6–11 ×13`、`12–15 ×4`、**`≥16 ×0`**。

> **与 `prd.md` 的差异**：prd 记的是「25 文件 / 2,413 行 / 598 行注释 / 24.8%」。实测 27 文件
> （`main.ts` 无注释、非 ts/vue 的 `assets/*` 不计入块统计），行数与占比差在 1% 内——**以本表实测值为准**，
> 不改 prd（占比本就是体量代理，验收不看它）。

**长尾不空转**：与前一层（test 层仅 1 块超 15 行）不同，本层 `≥6 行` 的块占 19 个，
`≥16 行` 有 2 个——**「长」与「变」两条线在本层都成立**，两条都要走。

### 七条筛选线（并集 **20 文件**）

> **第 ⑦ 条是审查期补入的（2026-10-13，独立质检发现）**：本层原把 `assets/*` 以「非 ts/vue」
> 整目录排除——而父 `prd.md` AC1 的扫描面**字面**写的是 `frontend/src` 全目录。
> 用户裁定**算违例**，已补清 `assets/tailwind.css` 的 1 处日期 + 1 处变更史散文
> （另开分支 `feature/comment-frontend-assets-clean`，见 §1）。
> 注：正典 §六 的钩子脚本**扫不到 `.css`**（`grep -E '\.(java|ts|vue|js)$'`），
> 所以机器闸本来就看不见这一处，是**人读**补上的。

| 线 | 命中 | 说明 |
|---|---|---|
| ① 变更史 / 归档指针（正典 §六 模式清单） | 4 处 / 4 文件 | `request.ts:42` 的日期 + `types/` 三文件的「自原 `src/api/types.ts` 迁出」 |
| ② **`design §N` 章节号指针** | **14 处 / 9 文件** | 机械线拦不住（模式只列 `design\.md`）——**父 prd 只点了 1 处** |
| ③ **任务卡需求号 `R# / D# / AC#`** | **8 处 / 4 文件** | 本层新发现；指向归档的 `10-03-vidsage-fe-v0/prd.md` |
| ④ 层级错位（父 Step A 第 3 批） | 3 处 | `types/task.ts:114`「逐条一致」**是误报**（语义非叙事）；`:131`「同构」待裁 |
| ⑤ 解释既成安排（父 Step A 第 4 批，尺子形态） | 命中 12 处，**多数是误报** | 「唯一入口 / 唯一时间源 / 唯一依据」一族多是**真负面约束**（删了会有人把动画绕过统一入口） |
| ⑥ 变更史自然语言变体（人读） | 3 处 | 「按原型验证的联动机制**重写**」「（原型验证的绑定口径）」「（原工作区产物面板修复先例）」 |
| ⑦ **`.css` 面**（**审查期补入**，见上注） | 2 处 / 1 文件 | `assets/tailwind.css` 的「定稿：2026-10-02」与「（按原型验证的方案重写，非搬运）」 |

**工作集 20 文件**：

```
assets/tailwind.css                           （⑦ · 审查期补入）
api/request.ts                                （①⑤ + 长尾 16/12）
api/tasks.ts                                  （② + 长尾 12）
lib/day.ts                                    （② + 长尾 11）
lib/gsap.ts                                   （长尾 10 · prd 明文不动，阶段 6 复核）
stores/task.ts                                （② + 长尾 8/8/7/6）
utils/format.ts                               （② + 长尾 7）
utils/transcript.ts                           （长尾 7/6）
enums/task.ts                                 （长尾 8）
types/media.ts                                （① + 长尾 6）
types/task.ts                                 （① + 长尾 15）
types/transcript.ts                           （① + 长尾 8）
composables/useTaskEvents.ts                  （长尾 16/9）
composables/useTranscriptPlayer.ts            （⑥ + 长尾 13）
components/AppHeader.vue                      （⑥）
views/upload/index.vue                        （②⑥）
views/workspace/index.vue                     （②③）
views/workspace/components/MediaPane/index.vue        （②③⑥）
views/workspace/components/TranscriptPane.vue         （②③）
views/workspace/components/WorkspaceHeader.vue        （③）
```

### 人读复核候选（未命中机械线，阶段 6 一并读，别因为「没命中」就跳过）

`routes/index.ts`（禁令 6「当前为单文件，超过约 5 条路由再拆分」是**预言**）、`App.vue:2`
（「两态视觉差异不抽共享外框」）、`components/BrandLogo.vue:3`（「深绿不进 logo」）、
`views/upload/components/HeroWave.vue:106`（SCSS 走 token 的 why）、
`views/workspace/components/MediaPane/components/AudioWaveform.vue:2`（「与上传页 hero 同一视觉语言」）、
`views/not-found/index.vue:2`。

### 三条误报（复核时跳过，别顺手改）

| 位置 | 命中模式 | 为什么是误报 |
|---|---|---|
| `types/task.ts:114` | `逐条一致` | 是配对契约（后端按下标配对、时间戳逐条一致），不是「迁移前逐字节一致」那类变更史 |
| `utils/format.ts:4` | `不与…走` | 「两者都不是日历时间，不走 dayjs」是边界陈述 |
| `useTaskEvents.ts:6` | `连续失败` | 阈值 why（连败 2 次≈6s 才提示），非叙事 |

### `≥6 行` 的块全清单（19 块，阶段 6 必读）

| 文件:行 | 行数 | 是否在工作集 |
|---|---|---|
| `api/request.ts:6` | 16 | ✅ |
| `composables/useTaskEvents.ts:11` | 16 | ✅ |
| `types/task.ts:1` | 15 | ✅ |
| `composables/useTranscriptPlayer.ts:5` | 13 | ✅ |
| `api/request.ts:37` | 12 | ✅ |
| `api/tasks.ts:13` | 12 | ✅ |
| `lib/day.ts:1` | 11 | ✅ |
| `lib/gsap.ts:1` | 10 | ✅ |
| `composables/useTaskEvents.ts:36` | 9 | ✅ |
| `enums/task.ts:1` | 8 | ✅ |
| `stores/task.ts:24` | 8 | ✅ |
| `stores/task.ts:115` | 8 | ✅ |
| `types/transcript.ts:1` | 8 | ✅ |
| `stores/task.ts:132` | 7 | ✅ |
| `utils/format.ts:1` | 7 | ✅ |
| `utils/transcript.ts:1` | 7 | ✅ |
| `stores/task.ts:175` | 6 | ✅ |
| `types/media.ts:1` | 6 | ✅ |
| `utils/transcript.ts:27` | 6 | ✅ |

> 本层 19 块**全部在工作集内**（长尾线与机械线在本层高度重合）——与 test 层相反。
> 阶段 6 的价值不在「捞新文件」，在**逐块过一遍尺子**：块长 ≠ 有毛病，`lib/gsap.ts:1`（10 行）
> 就是 prd 点名「不动」的干净块。

## 0.2 从上五层继承的教训（五份 `implement.md` 均已归档）

1. **机械线漏整档**：`>15` 会放过 12–15 的噪音块 → 本层把 **`≥6` 行全部 dump 逐块读**（19 块）。
2. **成稿要当整体读一遍**：逐条对着规则打勾 ≠ 做完。
3. **上移优先于删除**：搬得走的先搬，再从代码里删本地副本（本层主要发生在 `request.ts`）。
4. **不代写**：缺注释、原因又推不出来的，登记进 §3，不自己编一句（假注释比没注释糟）。
5. **只改注释**：diff 里不出现非注释行。
6. **报审查时 §4（标准补充）比 diff 更值得看。**
7. **用户审查时会自己动手删**他认为属废话的段；**删掉的不登记、不还原**（照 application 层 `implement.md` §2「收口后复核」的写法留一行说明即可）。
8. **grep 只捞典型词**：第 ⑤ 批拦的是自然语言，误报与漏检都多——真判据是人读一遍（正典 §六「已知缺口」）。
9. **上一层两条挂账不带到本层**：`@TempDir(cleanup=NEVER)` 的约定性注释与测试类覆盖清单**都是后端测试层的**，
   前端无测试文件，不适用；两条「建议回写正典」仍待裁定（见 §4）。

## 0.3 本层已知重点与预判

| 位置 | 预判 |
|---|---|
| **`design §N` 族（14 处）** | **删括注，保正文**：`（design §2.1：…）` 里的**章节号**是坏指针（`10-03-vidsage-fe-v0` 已归档、章节会漂移），后面的**口径本身**若读者要用则留。逐处判：`lib/day.ts:9`、`utils/format.ts:4`、`workspace/index.vue:48` 的「职责分野」正文**留**（它是「别把时长交给 dayjs」这条负面约束） |
| **`R# / D# / AC#` 族（8 处）** | **整处删**（正典 §二「什么时候改的 → 不留」+ §五 归档指针表：任务号/需求号是归档品）。例：`（R5）`、`（D1）`、`（D2）`、`（R6）`、`（AC6 / AC7）`、`（AC5 要求不遮挡进度）` |
| **`@/types/*` 三文件** | 删「自原 `src/api/types.ts` 迁出（该文件已删除，2026-10-03 两级类型策略）」整段；**保**「`src/types/` 只收**跨模块共享**类型，模块私有类型留模块内」（prd 明文要留的准入规则、可执行的负面约束）。`types/transcript.ts` 同办（「准入依据」句后的三方共享论证 = 解释既成安排 → 压缩） |
| `api/request.ts:37`（12 行门面块） | 删「（2026-10-03 定调）」；**axios 条件类型那段推导上移已完成**——`type-safety.md:95` 就是它的家 → 代码里压成「唯一受控断言的 why」一句 + 指针「（见 type-safety.md 的唯一例外）」；末句「（type-safety.md 已增补此例外）」的「已增补」是变更史味道，改现状表述 |
| `api/request.ts:6`（16 行类注释） | **大部分留**：禁手写 `.data`、双拼法「刻意保留而非遗漏」、单入口不另建实例——都是**读者本会做错**的事。`（约定见 api.md）` 是 spec 指针 → 留 |
| `api/request.ts:40` | 「需要 put / delete 等其余方法时按同款签名在此追加」→ **留**（扩展点指引，删了会有人另建实例，与同段的「instance 不导出」配套） |
| `composables/useTaskEvents.ts:11`（16 行） | 逐条过尺子：`latest` 初始 null / 重订阅复位、`fatal` 语义、心跳不触发 `progress`、作用域销毁自动 close → **留**（消费方不敢自己假设）；末行「返回类型不具名标注…（当前无具名消费需求）」= 解释既成安排 → **删** |
| `composables/useTaskEvents.ts:36`（9 行） | 「复位放这里而不是只放致命分支，是因为 `close()` 还有别的调用方…」→ **留但压缩**：防的是「把复位挪回致命分支」（真会有人做），属禁令 6 判据 |
| `composables/useTranscriptPlayer.ts:6` | 删「（按原型验证的联动机制**重写**）」（变更史）；**留**「媒体元素是唯一时间源」+「rAF 让 currentTime 更新频率高于 `timeupdate`，波形才平滑」（删了会有人把 rAF 换成监听 `timeupdate`） |
| `composables/useTranscriptPlayer.ts:16` | 「返回类型不就地标注…避免循环引用」→ 与 `:121` 的类型别名注释**同一件事写两遍**（禁令 10）→ 两处合一 |
| `stores/task.ts:27` | 删「（design §1.1）」。**数据流箭头链**（`load() → subscribe() → …`）按正典 §二 两条边界第 1 条：**构件内部的步骤序列与实现逐句重复 → 直接删**；「加载中 = taskView 与 loadError 均为 null」这条**约定留**（壳页据此渲染，是真契约） |
| `stores/task.ts:117-120` | 「主任务终态时原 SSE 连接已被收口（useTaskEvents 终态即 close，后端 `TaskSseConnectionManager` 同步断开），故 202 更新本地视图后必须重开订阅…」→ **留**（这是「为什么必须重开订阅」的真 why，删了会有人删掉那行 `subscribe`） |
| `stores/task.ts:197` | 「终态判据由后端算好经 `message.terminal` 送出，本处不再自己拼状态组合」→ **留**（防人本地重算终态） |
| `enums/task.ts:1`（8 行） | 「这里只承载值，不含类型——类型联合由 `@/types/task` 经 `import type` + `typeof` 派生」**留**（`type-safety.md` 口径的本地落点，防人写 TS `enum`）；「尚无比较消费的枚举…不在此建对象」是现状快照（禁令 2）→ 改写为职责边界 |
| `utils/transcript.ts:31` | 「id 用「下标-时间戳」复合值：下标是后端配对键（字幕顺序永不变化），时间戳保证可读唯一」→ 压缩成一句 id 契约；括号里的推演删 |
| `routes/index.ts:4`（人读） | 「当前为单文件，超过约 5 条路由再按域拆分」= 现状快照（禁令 2）+ 预言（禁令 6）→ 删；**留**「页面组件一律动态导入：每个 `views/` 子包独立成 chunk」 |
| `App.vue:2` / `components/AppHeader.vue:6` | `App.vue` 的「不抽共享外框」**留**（防人抽公共外框）；`AppHeader.vue:6` 删「（原工作区产物面板修复先例）」（事故先例），**留** `relative z-20` 的机制解释（删了会有人把这行当冗余删掉） |
| `views/workspace/index.vue:148` | 「`overflow-clip`：入场 y 位移让窗格瞬时下探出视口，`h-screen` 应用壳上会多出一条…」→ **留**（真坑）；同处「（AC5 要求不遮挡进度）」删 |
| `MediaPane/index.vue:43` | 删「（原型验证的绑定口径）」；**留**「换绑时先 `detach` 旧元素再 `attach` 新元素，避免监听残留」 |
| `BrandLogo.vue:3` / `HeroWave.vue:106` / `AudioWaveform.vue:2` | **均判留**：「深绿不进 logo」是约束；SCSS 走 token 的 why 与「同上传页 hero 同一视觉语言」防的是**改一处忘另一处**（删了会真的漂移） |
| `views/upload/index.vue:315` / `workspace/index.vue:257` | 两处「进度条填充」SCSS 注释几乎同文 → 删「复用原型的进度条视觉」，**留**「原生 `progress` + `:value`，归零 `appearance` 后给 WebKit/Firefox 伪元素上色」的实现 why；**跨文件同文是否算违例**（禁令 5 只管同文件）→ 登记 §4 |
| `lib/gsap.ts:1`（10 行） | **一字不动**（prd 明文）。实测复核：「当前无插件使用场景，故暂无注册调用」**仍真**——全仓仅 `upload/index.vue` 与 `workspace/index.vue` 从 `@/lib/gsap` 导入，无任何 `registerPlugin` 调用 |

## 1. 逐文件登记表（收口后）

**改动面**：**20 个文件改**——19 个 `.ts` / `.vue`（工作集 19 − prd 点名「不动」的 `lib/gsap.ts`
+ 人读补上的 `routes/index.ts`）为 `+47 −70`；另加**审查期补入**的 `assets/tailwind.css`
（两行文本订正，行数不变，分支 `feature/comment-frontend-assets-clean`）。**非注释行 0**。
另 5 个人读复核后**未动**：`App.vue`（「两态不抽共享外框」是约束）、`BrandLogo.vue:3`（「深绿不进 logo」是约束）、
`HeroWave.vue`、`AudioWaveform.vue`（「与上传页/工作区波形同一视觉语言」防的是改一处忘另一处）、`views/not-found/index.vue`。

| # | 文件 | 命中线 | 处置 | 上移去向 |
|---|---|---|---|---|
| 1 | `api/request.ts` | ①⑤ + 长尾 16/12 | 门面块删「（2026-10-03 定调）」与 axios 条件类型整段推导 → 压成一句 why + 指针；类注释删「导入方式：`import …`」复述行（导出就在下面），其余四条约束未动；拦截器块与 `ApiError` 契约未动 | `type-safety.md:95`（**未补写**——原文已完整，是代码里那份重复，删代码侧） |
| 2 | `api/tasks.ts` | ② | 删 `design §3.2`；删 `onUploadProgress` 上方纯复述的行内注释 | — |
| 3 | `lib/day.ts` | ② | 删 `（design §2.1）`；「本入口只管日历时间 / 媒体时长走 `@/utils/format`」的边界留 | — |
| 4 | `lib/gsap.ts` | 长尾 10 | **一字未动**（prd 明文；复核「当前无插件使用场景」仍真——全仓只有两个 view 从 `@/lib/gsap` 导入，无 `registerPlugin` 调用） | — |
| 5 | `stores/task.ts` | ② + 长尾 8/8/7/6 | 类注释删 `（design §1.1）` 与整条数据流箭头链（§二 两条边界 1：构件内部步骤序列），留「加载中 = taskView 与 loadError 均为 null」约定；`TaskLoadError` 删落位依据（`type-safety.md` 指针）与可见性辩护；`translate()` 行内删跨文件指针（`loadThenSubscribe`） | — |
| 6 | `utils/format.ts` | ② | 删 `（design §2.1）`；「不走 dayjs（会静默丢小时位）」的 why 留 | — |
| 7 | `utils/transcript.ts` | 长尾 7/6 | `id` 那行删「（字幕顺序永不变化）」解释性括注 | — |
| 8 | `enums/task.ts` | 长尾 8 | 「尚无比较消费的枚举（`TaskInputType` / `State` / `SubtitleRoute`）不在此建对象」= 现状快照（禁令 2）→ 改为「无比较消费的枚举不在这里建对象」 | — |
| 9 | `types/media.ts` | ① | 删「自原 `src/api/types.ts` 迁出（该文件已删除，2026-10-03 两级类型策略）」整句，**留**准入规则 | — |
| 10 | `types/task.ts` | ① | 同 #9；另把「仅被同文件引用的从属类型（4 个名字）…」的名字枚举压成一句边界（`export` 只给跨模块消费的类型） | — |
| 11 | `types/transcript.ts` | ① | 删「准入依据（2026-10-03 两级类型策略）」与三方共享论证（禁令 7 索引）→ 一句准入规则 | — |
| 12 | `composables/useTaskEvents.ts` | 长尾 16/9 | 类注释删「返回类型不具名标注…（当前无具名消费需求）」句，并删 `subscribe` / `close` 两条与成员注释重复的 bullet（禁令 10），把「断线不手动重连」升为独立 bullet；`close()` 块压缩（why 留：复位不能挪回致命分支） | — |
| 13 | `composables/useTranscriptPlayer.ts` | ⑥ + 长尾 13 | 删「（按原型验证的联动机制**重写**）」；「返回类型不就地标注（避免自引用）」并入文件尾别名注释（禁令 10 同处写两遍） | — |
| 14 | `components/AppHeader.vue` | ⑥ | 删「（原工作区产物面板修复先例）」；`relative z-20` 的机制解释留 | — |
| 15 | `routes/index.ts` | 人读 | 「当前为单文件，超过约 5 条路由再…」= 现状快照 + 预言（禁令 2/6）→「路由超过约 5 条时按域拆到 routes/ 下的独立文件」 | — |
| 16 | `views/upload/index.vue` | ②⑥ | 删 `（design §3.5：…）`；SCSS 块删「，复用原型的进度条视觉」（解释既成安排），WebKit/Firefox 伪元素那段 why 留 | — |
| 17 | `views/workspace/index.vue` | ②③ | 删 `design §3.4 ×2`、`§4 #2`、`§2.1`（「日历时间口径，走 `@/lib/day`」替代）与 `（AC5）`/`（AC6 / AC7）`/`（AC7）`；`overflow-clip` 那条真坑留 | — |
| 18 | `views/workspace/components/MediaPane/index.vue` | ②③⑥ | 删 `design §3.3 ×3`、`（D2）`、「（原型验证的绑定口径）」；detach→attach 防监听残留那条 why 留 | — |
| 19 | `views/workspace/components/TranscriptPane.vue` | ②③ | 删 `design §3.6 / R4 / R5`、`（R5）`、`（D1）` | — |
| 20 | `views/workspace/components/WorkspaceHeader.vue` | ③ | 删 `（R6）` ×2；`state-management.md` 与 `component-guidelines` 两处 **spec 指针留** | — |
| 21 | `assets/tailwind.css` | ⑦（**审查期补入**） | 删 `:4`「（按原型验证的方案重写，非搬运）」与 `:22`「定稿：2026-10-02，」；「深绿仅作氛围素材，不进 UI 强调」「组件只允许引用 token」两条约束留 | — |

> **两处按正典保留、没顺手删的**：`api/request.ts` 的「（约定见 api.md）」与
> `lib/gsap.ts` 的「（详见 `.trellis/spec/frontend/animation.md`）」——spec 随仓库走，是 §五 明文的一等指针。

## 2. 例外登记表（滚动填）

> `≥16 行` 的块在这里逐条登记。**收口后本层空表**——`≥16` 行块 **2 → 0**。
> 唯一的 `request.ts:6`（16 行）砍掉的是「导入方式：`import { request } …`」那一行复述（导出就在正下方），
> 不是拆行凑数；剩下的 15 行是「是什么 + 四条调用方真会踩错的约束」，逐条过了尺子。

| 文件 | 块位置 | 行数 | 理由 |
|---|---|---|---|
| （空） | | | |

## 3. 待订正清单

> 断句残句 / 失实表述。**本层空表**——通读未发现断句残句或与代码矛盾的表述。
> 三处「疑似失实」都查证为**属实**，记录在此备查（免得下一个人再查一遍）：

| # | 位置 | 疑似 | 查证结果 |
|---|---|---|---|
| 1 | `api/tasks.ts:52` | 指针「信封结构见 `@/types/task` 的 `SubtitleArtifactFile`」 | 类型确实存在（`types/task.ts:132`，收口后行号；收口前为 `:134`）→ **非失实** |
| 2 | `stores/task.ts` | 「主任务终态时…后端 `TaskSseConnectionManager` 同步断开」 | `backend/src/main/java/com/vidsage/web/sse/TaskSseConnectionManager.java` 存在 → **非失实** |
| 3 | `lib/gsap.ts:9` | 「当前无插件使用场景，故暂无注册调用」 | 全仓 `grep registerPlugin` 只命中该注释本身 → **非失实** |

## 4. 标准补充（本层对正典的反馈）

> 各层执行中遇到正典没覆盖的情形，逐条记在这里，**用户裁定后回写正典**（正典逐层沉淀，不是一次冻结）。
> **报审查时这一节比 diff 更值得看。**

**继承自上两层的挂账（均属后端测试层，本层不适用，仅记录待裁）**：

| # | 情形 | 现状 |
|---|---|---|
| ① | `@TempDir(cleanup=NEVER)` 的约定性注释含归档指针与事故频次叙事 | 父 `prd.md` 明文「不动」→ 原样保留；待裁定是否剥掉指针与频次句 |
| ② | 测试类的「覆盖清单」（`<p>覆盖「…」`）算不算禁令 7 的索引 | 上一层按保守办并建议正典补一句；待裁定 |

**本层新发现（执行中填）**：

| # | 遇到的情形 | 正典怎么说 | 实际怎么定 | 建议回写正典？ |
|---|---|---|---|---|
| Ⓐ | `design §N` / `R# / D# / AC#` 这类**任务卡内部编号**算不算归档指针？ | §五 表只写了「`design.md`、`research/**`、任务号、日期」 | 按归档品**删**（判据明确，本层清了 22 处） | **建议**：§六 模式清单补一行「`design §N` / `（R# / D# / AC#）` 这类任务卡内部编号」，否则每层都要靠人眼——本层的机械线只报出 4 处，人眼捞出 22 处 |
| Ⓑ | 同一段 SCSS 说明在**两个 `.vue` 文件里几乎同文** | 禁令 5（同一文件内多处一致）、禁令 10（同一处细节只写一遍）**都只管文件内** | **不对称处置**：两份都删了「来自原型」的出处句；`workspace/index.vue` 保留「与上传页的进度条同一视觉」（跨组件不变量，删了会漂移），`upload/index.vue` 侧无对句——原件本来也不是对称的 | 待裁：是否需要一条「跨文件重复」的口径 |
| Ⓒ | 「返回类型不就地标注，由文件尾别名承载」这类**类型标注策略**说明 | §一 尺子（解释既成安排 → 删）；禁令 10（同一处细节只写一遍） | 两处（`useTaskEvents:11` 末行、`useTranscriptPlayer:16`）按禁令 10 各留一处 | 正典已覆盖，**无需补** |
| Ⓓ | 「需要 put / delete 等其余方法时按同款签名在此追加」这类**扩展点指引** | §三 禁令 6 判「不为没发生的事写辩护句」 | **判留**（两处：`request.ts` 门面、`routes/index.ts` 拆分指引）——它防的是读者另建实例 / 把单文件撑爆，与「辩护句」不同：读者本来就会去做那件事 | 待裁：禁令 6 下是否需要一句区分「辩护句」与「扩展点指引」 |

## 5. 自检（实测值 2026-10-13）

> **订正（2026-10-13，独立质检后）**：本节最初抄的三条 grep **漏写了 `--include` 过滤**——
> 实跑的是 `--include=*.ts --include=*.vue`，文档里的命令按**字面**跑会命中 `assets/tailwind.css`
> 的 2 处（§0.1 第 ⑦ 条），复现不出记录里的「0」。已补全命令，并另记一次**全目录无过滤**的扫描。
> 教训：自检命令要写成**实际跑的那一条**，否则下一个人复现不出来。

```bash
cd f:/personal_project/vidSage && git diff -w -- frontend/src
#   → 19 files changed, +47 −70（另 assets/tailwind.css 两行文本订正，行数不变）
#   → **非注释行 0**（逐行核过 +/- 行：全是 //、*、<!-- --> 行）

# ① 变更史 / 归档指针归零（模式清单只住正典 §六，照抄那一段）
grep -rnE '<§六 模式清单>' --include=*.ts --include=*.vue frontend/src
#   → **0**（收口前 4 处）
#   不带过滤扫 frontend/src 全目录（父 prd AC1 的字面扫描面）：
#   收口前 1 处（assets/tailwind.css:22）→ 补清后 **0**

# ② 归档指针第二、三族（本层新查法——机械线拦不住，但判据在正典 §二/§五）
grep -rnE 'design §|（R[0-9]|（D[0-9]|（AC[0-9]|R[0-9] /' --include=*.ts --include=*.vue frontend/src
#   → **0**（收口前 22 处）

# ③ 变更史自然语言变体
grep -rnE '原型|先例|原为|原先|此前|重写|迁出|已删除' --include=*.ts --include=*.vue frontend/src
#   → **0**（收口前 3 处）；不带过滤时收口前 1 处（assets/tailwind.css:4）→ 补清后 **0**

# 块统计（脚本见 §0.1）：210 块不变；1–5 191→**193**、6–11 13→13、12–15 4→4、**≥16 2→0**
# 删完回查两件事（正典 §一 末尾）：① 悬空引用——0（留下的两处 *.md 指针是 §五 明文保留项）；
#                                ② 注释块内的裸空行——脚本扫过，**0**

cd frontend && npm run type-check && npm run lint
#   → 均无输出（全绿）；lint 带 --fix 但**未改动任何文件**（git status 与收口前一致）
```

**行为不变**：前端无单测基线，`type-check` + 双 linter（oxlint / ESLint）全绿即本层的「行为不变」替身。
这条的价值在于排除「手滑改了代码」——注释不参与编译，所以另行为主依据是逐行核过的 diff：非注释行 0。

## 6. 门 G6 · 报审查

按 ① 改动文件清单 ② 例外登记（§2）③ 待订正清单（§3）④ 标准补充（§4）⑤ `git diff -w` 摘要 五段输出，
**停下等审查，不自行提交**（分支工作流硬规则）。

## 7. 分支

执行时从当前分支 `dev` 开 `feature/comment-frontend`；改完停等审查，通过后 `--no-ff` 合回 `dev`。

## 8. 本层之后的收尾（父 `implement.md` §5，**不属本层**）

本层是最后一个注释子任务。本层归档后，父任务还需：全量 AC1 三面复核（`backend/src/{main,test}/java`
与 `frontend/src`）→ 装钩子验 AC7 → **三层回归扫描**（domain / application / infrastructure 按正典 §一
那把尺子回扫，**不新增子任务**）。执行步骤见父 `implement.md` §5。

> **AC7（装 pre-commit 钩子）的归属已裁定（2026-10-13，独立质检提出后由用户定）：归父任务收尾。**
> 本层 `prd.md` 的 AC 列表把它写成「全部完成后装钩子并验 AC7（父 `prd.md`）」，字面像是本层的活；
> 两处打架，裁定以本节为准——**本层归档时 `.git/hooks/` 里只有 `*.sample`，钩子确实没装**，
> 这一条是父任务收尾的欠账，不是本层的漏做。
