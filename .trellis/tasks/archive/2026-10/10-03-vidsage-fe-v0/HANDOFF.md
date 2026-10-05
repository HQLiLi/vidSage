# 会话交接快照（2026-10-05 刷新，供新会话续接）

> 本文件是审查期会话的交接文档：实现已完成并提交（`0b14df0`），其后的审查期改动
> （14 批）**未提交**；用户审查仍在进行，新会话从这里继续。

## 任务状态

- 任务：`.trellis/tasks/10-03-vidsage-fe-v0`（in_progress，epic 子任务，分支 dev）
- 已提交基线：`0b14df0 feat(frontend): 按原型落地两态页面…`（33 文件，v0 完整实现）
- 工作流位置：Phase 3 收尾段——**commit（3.4）未完成**（等用户审查通过），之后 finish-work

## 未提交改动清单（14 批审查期改动 + 用户调试残留）

| 批次 | 内容 | 触及文件 |
|---|---|---|
| A 面板遮挡修复 | header 加 `relative z-20`（GSAP transform 残留创建包含块+stacking context，main 按 DOM 序盖住下拉面板）；产物 wrapper 恢复 `relative` | `views/workspace/components/WorkspaceHeader.vue` |
| B baseURL 收敛（D8） | `frontend/.env`（`VITE_API_BASE_URL=/api`，新文件）+ env.d.ts 类型 + request.ts baseURL + 6 调用点去前缀 + api.md 同步 + 根 .gitignore 加 `!frontend/.env` | request.ts、api/tasks.ts、api/media.ts、stores/task.ts、api.md、.gitignore |
| C ApiError 归一化（D9） | 响应错误拦截器 + `ApiError` 类（status/message/existingTask）+ 三调用点删本地 isErrorBody/isConflictBody/isAxiosError 拷贝 + api.md 同步 | request.ts、stores/task.ts、views/upload/index.vue、WorkspaceHeader.vue、api.md |
| D 成功解包门面（D10） | 成功拦截器 `=> response.data`；不导出裸实例，导出类型化门面 `{ get, post }`（单泛型位）；6 调用点删 `.data`；门面边界 2 处受控 `as Promise<T>`（type-safety.md 增补唯一例外） | request.ts、api/tasks.ts、api/media.ts、stores/task.ts、api.md、type-safety.md |
| E Hero 波形抽组件 | 上传页 hero 声波 SVG（模板 + 波形数据 + 渐变 SCSS）抽为页面私有组件 `views/upload/components/HeroWave.vue`（单页组件不上移 src/components）；渐变 ID 随组件改名 `upload-wave-*` → `hero-wave-*`；波形由 computed 改为实例化时算一次；显示尺寸 class 透传留在页面 | views/upload/index.vue、views/upload/components/HeroWave.vue（新） |
| F store 辅助函数出 stores | task store 文件内 4 个模块级辅助移出：pickSourceArtifact / pickTranslatedArtifact / buildCues → `utils/transcript.ts`（纯函数进 utils，不发请求不持状态）；fetchArtifactJson → `api/tasks.ts`（HTTP 请求按 api.md 只进 api 层）；同步 types/transcript.ts 产出方注释、api.md tasks.ts 行、directory-structure.md 补 utils 目录职责 | stores/task.ts、utils/transcript.ts（新）、api/tasks.ts、types/transcript.ts、api.md、directory-structure.md |
| G 状态枚举常量化 | types/task.ts 加 `TASK_STATUS` / `TRANSLATION_STATUS` 常量对象（`as const`，键镜像后端枚举常量），TaskStatus / TranslationStatus 联合改为由对象派生；store 与 useTaskEvents 共 13 处状态比较字面量换命名常量；type-safety.md「类型写法」增补枚举形态约定（as const 对象 + 派生联合，不用 TS enum 语法） | types/task.ts、stores/task.ts、composables/useTaskEvents.ts、type-safety.md |
| H 枚举值迁 enums/（G 复议） | 用户定调 types/ 必须纯类型（构建期可擦除），G 的值对象不能留 types/：TASK_STATUS / TRANSLATION_STATUS 迁至新 `enums/task.ts`（只承载值）；types/task.ts 经 `import type` + `typeof` 派生联合恢复纯类型；store 与 useTaskEvents 值导入改指 `@/enums/task`；type-safety.md（枚举条目 + 类型定义放哪表）、directory-structure.md（目录树加 enums/、utils 行移回字母序 + 职责表）同步 | enums/task.ts（新）、types/task.ts、stores/task.ts、composables/useTaskEvents.ts、type-safety.md、directory-structure.md |
| I 私有类型不导出 | 新规范「type/interface 只在跨模块使用时才 export」落 type-safety.md（类型写法条目 + store 相关行措辞 + 反模式表）；全量清点导出类型：types/task.ts 的 TaskStatus / TranslationStatus / StageState / StageEvent / SubtitleRoute 去导出（仅被同文件契约主类型引用，头部注释说明）；TaskLoadError 去导出（store 内部使用）；useTaskEvents.ts 删除无人消费的 UseTaskEvents 别名（死代码）及关联注释 | types/task.ts、stores/task.ts、composables/useTaskEvents.ts、type-safety.md |
| J 音频波形抽组件 | MediaPane 的装饰波形（WaveBar / 常量 / waveBars / filledCount / 点击 seek + 模板 SVG，约 60 行）抽为同包组件 `views/workspace/components/AudioWaveform.vue`：几何与绘制（cursor/aria）归组件，播放比例 progress（0–1）prop 进、点击比例经 seek 事件回抛，秒数换算与时长未知兜底留在 MediaPane（onWaveSeek）；waveBars 同 HeroWave 改实例化算一次；封面/播控小图标保持内联 | views/workspace/components/MediaPane.vue、views/workspace/components/AudioWaveform.vue（新） |
| K 复合组件提升文件夹（J 复议） | 用户定调「组件拥有仅自用子组件 → 提升为同名文件夹」：`MediaPane.vue` → `MediaPane/index.vue`（git mv 保留改名），`AudioWaveform.vue` 下沉 `MediaPane/components/`；工作区壳导入改全路径 `…/MediaPane/index.vue`；eslint 的 multi-word 豁免 glob `src/views/**/index.vue` 天然覆盖嵌套，无需改配置；directory-structure.md 四处同步（views 行加提升规则、components 行、eslint 豁免注记、目录树） | views/workspace/components/MediaPane/（index.vue + components/AudioWaveform.vue）、views/workspace/index.vue、directory-structure.md |
| L TypeScript 类型规范清单 | 用户定调 8 条操作口径（先搜索再创建 / 优先复用 / 三档定义位置与 export 口径 / Props·Emits 不导出 / 禁语义重复类型 / import type）落 type-safety.md 新「TypeScript 类型规范」节；「类型定义放哪」表模块私有行拆为「仅当前文件（不导出）/ 模块内共享（types.ts 导出）」两档；类型写法 export 条目与反模式行对齐新口径。代码库已符合（无导出 Props/Emits，类型导入全 import type），零代码改动；eslint 无 consistent-type-imports，import type 靠人工 | type-safety.md |
| M AppHeader 抽共享 | 用户定调「upload 与 workspace 的 header 是一个东西」：三处裸/富 header（upload 页、workspace 非就绪态、WorkspaceHeader）收敛为跨页公共组件 `src/components/AppHeader.vue`（壳 h-14/border-b/bg-surface/px-4 sm:px-6/relative z-20 + BrandLogo + 默认插槽）；WorkspaceHeader 根元素改 AppHeader、只留页面差异内容（data-animate 经两层单根透传到壳）。统一口径取令牌注释意图：三处顶栏都上 bg-surface（原两处裸 header 为透明）、padding 统一 px-4 sm:px-6（原 workspace 就绪态 px-4）；spec 同步 directory-structure.md（App.vue 行改指 AppHeader）、component-guidelines.md 头部注记去「尚无业务组件」 | components/AppHeader.vue（新）、WorkspaceHeader.vue、views/upload/index.vue、views/workspace/index.vue、directory-structure.md、component-guidelines.md |
| N 入场滚动条闪烁修复 | 用户报 bug：workspace 进场动画期间出现文档滚动条、结束后消失，布局两次横移（Windows 经典滚动条占宽）。根因：就绪态根节点 h-screen 固定壳，gsap.from y:16 让窗格瞬时下探 16px 出视口 → 文档可滚动溢出 → 滚动条挤压布局，动画结束 transform 归零溢出消失弹回。修复：就绪态 main 加 `overflow-clip`（溢出只可能来自动画残影，剪在产生层；两个下拉面板在 header 壳层不受影响；非就绪态 main 不动，保留矮视口下文档滚动的可能）。已 Playwright 实弹验证：冒烟任务就绪态渲染下，DOMContentLoaded 起 rAF 采样 665 帧全程 scrollHeight−clientHeight 与滚动条占宽恒为 0 | views/workspace/index.vue |
| 用户调试残留 | `console.log('111')`（:49）与 `console.log('document click'…)`（:75）——**提交前用户自行摘除或经其同意摘除** | WorkspaceHeader.vue |

另：`.playwright-mcp/` 为本会话 Playwright 产物（快照/console 日志），未跟踪，建议加入 .gitignore 或删除；`.zcode/tmp/` 含冒烟测试音频，同前。

## 决策台账（详情见 prd.md「Key Decisions」D1–D10）

审查期新增三项（均为用户定调，spec 已同步）：
- **D8** baseURL 收敛进 request（env 注入）——非 axios 出口保留全路径双拼法
- **D9** 错误归一化 ApiError 拦截器——调用点读字段不再拆 axios 错误
- **D10** 成功响应统一解包 + 类型化门面——门面边界受控断言是 no-as 唯一例外

10-05 会话新增定调（E–L 批，spec 均已同步）：
- 纯装饰 SVG 抽页面私有组件（不上移 src/components）；组件拥有仅自用子组件时提升为
  同名文件夹（`index.vue` + `components/`，引用写全路径 `…/index.vue`）
- 纯函数进 `utils/`（不发请求不持状态）；HTTP 请求只进 `api/`（fetchArtifactJson 迁 tasks.ts）
- 状态枚举：`enums/` 承载 `as const` 常量对象（键镜像后端枚举常量），`types/` 经
  `import type` + `typeof` 派生联合保持纯类型；不用 TS enum 语法
- type/interface 只在跨模块使用才 export；TypeScript 类型规范 8 条操作口径
  （type-safety.md「TypeScript 类型规范」节）

## 验证状态

- 三命令（type-check / lint / build）全绿（最后一批 L 后实测）。
- E–L 批仅过三命令门禁，未做浏览器实弹验证：E/J 为 DOM 等价搬移，F/G/H/I/L 零行为
  改动，K 仅路径迁移；需要时可对上传页 hero 与工作区音频卡做一次目检回归。
- 浏览器已验证（Playwright + 用户运行中的后端）：就绪工作区渲染、404 → 任务不存在卡、产物面板展开与下载链接、baseURL 线上拼接、SSE 终态静默刷新链路。
- **未实弹验证**：翻译链路（真实 LLM，正弦波冒烟无文本）、409 冲突（需 RUNNING 任务占坑）——首传真实素材时顺路验证。
- 已实证：产物端点 Range 返回 206（播放器 seek 可用，无需媒体端点，design.md 风险表已销）。

## 新会话建议执行序

1. 读本文件 + prd.md（D1–D10）→ 继续用户审查（用户主导，可能继续提改动）。
   10-05 会话已落 E–L 批（组件抽取 / enums·utils·api 层级收敛 / 类型规范增补），
   详情见上方批次表与决策台账；三命令全绿。
2. 审查通过后：摘除/确认摘除 WorkspaceHeader 两处 console.log（:49、:75）→ 提交全部
   未提交改动（建议一个 commit：审查期修复与架构收敛；需用户明确同意提交）。
3. `.playwright-mcp/` 处置（ignore 或删）随该 commit 一并。
4. 遗留 P2（可选，见 execution-log 与质检记录）：终态谓词两处编码待统一（P2-2）、
   SSE 重连 404 静默（P2-6）、进度条 SCSS 两页重复（可后续抽 main.scss）。
5. 本会话讨论过、用户未点头的候选（新会话不要主动做）：出现第三个波形消费方时抽
   utils/waveform.ts 归一化生成器；knip/ts-prune 扫死导出；eslint consistent-type-imports。
6. 走 finish-work：journal 记录 → 归档（archive 会校验分支，当前任务 base_branch=dev 需留意其提示）。

## 环境备忘

- 用户侧常驻：后端 :8080、vite dev :5173（本会话验证用的临时实例均已关闭）。
- 冒烟任务号 `8d0edf09aeb343f0b34293292aa41b69`（2s 正弦 wav，SUCCEEDED，segments 为空，
  产物 4 件含 0 字节 srt——空产物是合法态）可用于工作区页回归。
- 子代理 token 台账：`research/execution-log.md`（全任务累计 ≈18.96M，含审查期 4 批 ≈4.77M）。
