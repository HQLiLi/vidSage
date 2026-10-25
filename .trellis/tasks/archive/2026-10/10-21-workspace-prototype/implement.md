# 执行计划：工作区页原型

## 前置

- 工作目录：`prototype/`（**全程不碰 `frontend/`**）
- `prototype/node_modules` 已存在，无需 `npm ci`
- 技术栈自由：当前 Vue 脚手架可复用，但不要求 Vue，也不受正式前端规范约束；以快速验证布局与交互为优先
- 命令：`npm run dev` 起开发服务器；当前 Vue 脚手架可用 `npm run type-check` / `npm run build` 自查，但不把正式前端 lint 规则作为原型硬门槛
- 本任务产出**不进 git**，因此没有"提交 → 推分支"这一步；审查门是**跑起来看**

## 步骤

### S1 基线确认
先跑通现有原型，确认起点是好的。

**已完成**：原上传页、旧工作区模块和两份本地演示媒体可加载；媒体可播放，控制台无错误。

```bash
cd prototype && npm run dev
```
打开首页 → 点进工作区 → 确认旧布局能跑、演示媒体能播。**跑不通就先修环境，别在坏基线上动手。**

### S2 mock 数据模块
新建 `src/data/workspace.ts`，按 `design.md` §3 的形状产出全部 mock。
把 `src/data/transcript.ts` 的 `DEMO_CUES` 迁成**毫秒**口径（或在此模块重建，删掉旧的）。

- [x] `DEMO_TASK`（`TaskResponse` 形状，含 `lastRun`）
- [x] `DEMO_KEYWORDS`（`{keywords, fingerprint}`）
- [x] `DEMO_ARTIFACTS`（`ArtifactResponse[]`，`keywordsFingerprint` 与上一条**不同值**）
- [x] `DEMO_CONTEXT`（`{theme, sourceLanguage, terms, degraded}`）
- [x] `DEMO_SOURCE_SEGMENTS` / `DEMO_TRANSLATED_SEGMENTS`（毫秒）
- [x] 由两份 segments 按下标配对构建 `TranscriptCue[]`
- [x] 首轮不造进度 / 失败态 mock；等就绪态验收后再补

### S3 布局骨架
重写 `src/views/WorkspaceView.vue`：顶栏 + 左右分栏 + 左列三块的空壳（先放占位文案）。
按 `design.md` §2.1 落高度分配。

- [x] 左右分栏改为 3:2（`lg:basis-[60%]` / `lg:w-[40%]`）
- [x] 左列 `flex flex-col min-h-0`；① `flex-1 min-h-[240px]`；②③ `shrink-0` + `max-h` + 内部滚动
- [x] 左列整体 `overflow-y-auto` 兜底
- [x] **此时先验证 AC2**：1280×800 与 1920×1080 下不塌、不出现双滚动条。骨架阶段发现比例不对最省事

### S4 三个 pane
- [x] `components/MediaPane.vue` 改造：适配 ① 块（现有内部 padding 已能占满首块，无需改动）
- [x] `components/KeywordPane.vue` 调整：标题行「重新生成」；输入框在上，已有垂直列表在下；支持增删与空态（`design.md` §5.1）
- [x] 去除关键词区 30% 区块高度上限；列表本体改为限高内部滚动
- [x] 关键词垂直列表改为限高内部滚动（`max-h-60` + `overflow-y-auto`），并删除指纹不一致提示
- [x] 关键词区、主题区移除独立背景色，仅保留顶部边框分割
- [x] 工作区顶栏「重新上传」改为「返回」
- [x] 播放器改为固定 16:9，新增关键词不得改变播放器尺寸
- [x] 关键词区、主题区移除区块级分割线
- [x] `components/ThemePane.vue` 新增：主题 + `degraded` 空态（§5.2）

### S5 字幕区改造
`components/TranscriptPane.vue`：字幕行 hover / 聚焦显示修改图标，点击进入该条行级编辑态（§5.3）。
- [x] `WorkspaceView.vue` 把左右分栏改为 3:2
- [x] 字幕行 hover / focus-within 时右上角显示镂空修改图标；图标不改变正文布局
- [x] 点击图标进入该条原文编辑；支持保存到内存、取消，并明确提示后端未实现
- [x] 编辑交互不能误触发行级 seek，也不能破坏搜索、高亮与自动滚动
**搜索 / seek / 高亮 / 自动滚动跟随一律不许回归**——AC6 是硬线。

### S5.1 翻译控件与本地模拟翻译

- [x] 在 mock 中补 `LanguageOption[]`（`{code,label}`），固定四项：简体中文 / 繁体中文 / 英语 / 日语；源 / 目标共用。
- [x] 新增 `components/TranslationBar.vue`：两个原生 select、互换按钮、翻译按钮；源目标相同时禁用并显示原因。
- [x] 在 `TranscriptPane.vue` 的字幕标题与搜索框之间接入语言栏；保留窄宽度可用性，不允许横向溢出。
- [x] 在 `WorkspaceView.vue` 管理 `sourceLanguage` / `targetLanguage` / `translating` / `progress` / `translatedLanguage`；用本地计时器模拟约 2 秒翻译，不写网络请求。
- [x] 默认就绪态必须 `translatedLanguage = null`，不显示译文；目标语种默认英语，`?noTranslation=1` 仅保留兼容。
- [x] 翻译完成后按目标语种切换预置译文；明确显示“本地演示，未发起真实翻译请求”。
- [x] 重新生成令译文失效时保留语言选择、隐藏旧译文，并允许直接重新翻译。
- [x] 回归检查：搜索、seek、高亮、自动滚动、字幕行编辑均不因翻译控件接入而退化。
### S6 SSE / 非就绪态延后
首轮不实现状态切换器与 SSE 视觉。清洗结束的就绪态验收通过后，再追加进度 / 失败 / SSE 原型（§5.5）。

### S7 窄屏与空态收边
- [x] `lg` 以下上下堆叠，三块 + 字幕区都能滚到（AC8）
  - 2026-10-24 复验时发现真问题：800px 下左列高度被压到 719px，主题块溢出到字幕区下方并与之重叠（主题块 top 766/868，字幕区 top 868，修复前字幕区 top 776 直接压在主题块上）。
  - 修法：`WorkspaceView.vue` 左列补 `shrink-0`，窄屏下由内容撑高、整页滚动；`lg` 以上 60/40 与左列内部滚动不变。
  - 复验：800×900 下三块 + 字幕区依次可达、无重叠、单一滚动条；1280×800 下左右 768/512，`main` 不滚、左列内部滚。
- [x] 大屏允许左列整体滚动，确认没有关键词区内部第二滚动条
- [x] 左列与右侧字幕列表统一使用悬浮自动隐藏滚动条；默认不可见，悬停 / 滚动时仅显示当前区域的细滑块
- [x] 关键词为空、主题降级、无译文三个子形态都有明确空态（分别由 `?emptyKeywords=1`、`?degraded=1`、`?noTranslation=1` 触发）

### S8 自查
逐条过 `prd.md` 的 AC1–AC12，**每条都要真的在浏览器里点过**。
原型以“能打开、能操作、布局正确”为验收核心；若沿用 Vue 脚手架，再确保可构建/无运行时报错。

**当前进度**：静态检查、ESLint、type-check、build 已通过；翻译控件已在浏览器中验证默认未翻译、语种切换、进度、双语字幕、同语种禁用、重新生成失效与重新翻译。
**2026-10-24 收尾**：所有者已确认原型结束（见 `10-21-workspace-fe-rewrite/handoff.md` §2.1），AC1–AC12 按所有者验收通过计；本轮补做了窄屏 AC8 的实测与修复（S7 第一条）。

## 验证

| 项 | 方式 |
|---|---|
| 能跑 | `cd prototype && npm run dev` |
| 构建（可选，当前 Vue 脚手架） | `npm run type-check`、`npm run build` |
| AC1–AC12 | **浏览器里逐条点**（本任务无 diff 可审） |

## 审查门

**S3 后停一次**：布局骨架先给所有者看一眼，比例不对在这里改最便宜——别等四个 pane 都填满了再返工。
**S8 后停一次**：完整原型交审查，通过后才允许开子任务 B 的实现。

## 回滚

`prototype/` 不进 git，**没有版本回退**。所以：

- 动手前把要改的文件（`WorkspaceView.vue`、`MediaPane.vue`、`TranscriptPane.vue`、`data/transcript.ts`）
  复制一份到 `prototype/.backup-<日期>/`，改崩了能捞回来。
- 或者先 `cd prototype && git init` 建个本地临时仓（在 gitignore 的目录里，不影响主仓），拿到真正的回退能力。

**推荐后者**：改崩了 `git checkout .` 就回去，比手工备份可靠。

## 不做

- 不碰 `frontend/` 任何文件
- 不写真实网络请求
- 不修契约错位（属于子任务 B）
