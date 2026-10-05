# 目录结构

## 结构示意

```
frontend/
├── src/
│   ├── api/               # 与后端通信的 HTTP 请求（request.ts 唯一 axios 实例 + 一域一文件）
│   ├── assets/            # 静态资源与全局样式入口
│   ├── components/        # 跨页面复用的公共组件（基础结构，.gitkeep 占位）
│   ├── composables/       # 可复用组合式逻辑（基础结构，.gitkeep 占位）
│   ├── enums/             # 契约枚举的运行时值对象（键镜像后端枚举常量，类型联合在 types/ 派生）
│   ├── layouts/           # 跨路由布局组件（基础结构，.gitkeep 占位）
│   ├── lib/               # 框架无关的单例模块（gsap / dayjs 注册入口）
│   ├── routes/            # vue-router：index.ts 聚合路由表并创建 router 实例
│   ├── stores/            # Pinia store，一域一文件（扁平结构）
│   ├── types/             # 全局共享类型（跨模块才准入，两级类型策略见 type-safety.md）
│   ├── utils/             # 纯函数工具：不发请求、不持状态的数据加工（format / transcript）
│   ├── views/             # 路由页面，每页一个子包（index.vue + components/，复合组件再下沉同名文件夹）
│   ├── App.vue            # 应用壳，仅承载 <RouterView />
│   └── main.ts            # 应用入口
├── public/                # 原样拷贝的公开资源（favicon 等固定 URL 文件）
├── index.html             # Vite 入口 HTML
├── env.d.ts
├── eslint.config.ts
├── vite.config.ts
└── tsconfig.json / tsconfig.app.json / tsconfig.node.json
```

> **这是示意，不是文件清单。** 树只列到目录与入口文件这一级，不逐个登记资源文件；**新增文件不需要回头改这份文档**。各目录放什么见下方[目录职责](#目录职责)。

## 路径别名

**`@` 指向 `src/`**，两侧都配置好了：

- 构建期：`vite.config.ts` 的 `resolve.alias`
- 类型检查：`tsconfig.app.json` 的 `compilerOptions.paths`

```ts
// 正确
import router from '@/routes'
import UploadPage from '@/views/upload/index.vue'

// 不要用相对路径跨目录
import UploadPage from '../../views/upload/index.vue'
```

参照：`frontend/src/main.ts` 与 `frontend/src/routes/index.ts` —— 现有代码全部使用 `@/`。

## 目录职责

| 目录 | 放什么 |
|---|---|
| `src/api/` | 与后端通信的非 SSE HTTP 请求：`request.ts` 唯一 axios 实例 + 一域一文件（`tasks.ts` / `media.ts`）。**不建 barrel**，导入写具体文件；SSE 走 `composables/` 不进这里（规则见 [api.md](./api.md)） |
| `src/types/` | **全局共享类型**：只收跨模块使用的类型（后端契约镜像 + 跨模块视图模型），按域一文件（`task.ts` / `media.ts` / `transcript.ts`）。**准入门槛 = 跨模块共享**；单模块私有类型就地放（SFC 内联、推断类型跟随源模块），需要成文件时建模块内 `types/`，不上移。规则见 [type-safety.md](./type-safety.md) |
| `src/enums/` | 契约枚举的**运行时值**（有比较消费才建）：`as const` 对象、键镜像后端枚举常量（如 `task.ts` 的 `TASK_STATUS`）；只承载值不承载类型，类型联合在 `types/` 经 `import type` + `typeof` 派生，比较处用命名常量不写裸字面量 |
| `src/lib/` | 框架无关的单例模块。当前只有 `gsap.ts`（GSAP 全项目唯一注册入口）；"一个库全项目共用一份配置"的模块放这里，每个库一个文件 |
| `src/utils/` | 纯函数工具（现有 `format.ts` 时长/体积格式化、`transcript.ts` 字幕稿产物选择与 Cue 构建）：只做数据加工，**不发请求**（HTTP 只进 `api/`）、**不持状态**（有状态逻辑进 `composables/`） |
| `src/views/` | 路由页面。**每页一个子包**（kebab-case，与路由 path 同名）：子包内 `index.vue` 是页面主文件，仅本页使用的组件放 `index.vue` 旁的 `components/`（PascalCase）。**复合组件同样下沉一级**：组件拥有仅被自己使用的子组件时，提升为同名文件夹（PascalCase/`index.vue` + `components/`），子组件进其 `components/`，引用写全路径 `…/MediaPane/index.vue`（例：`workspace/components/MediaPane/`） |
| `src/components/` | 跨页面复用的公共组件。扁平放 PascalCase 文件，数量多了再按域分子目录；只被一个页面使用的组件留在该页面子包内，不上移。复合组件（拥有仅自用子组件）的提升规则与 views 相同：同名文件夹 `index.vue` + `components/` |
| `src/layouts/` | 跨路由布局组件，命名 PascalCase + `Layout` 后缀（如 `DefaultLayout.vue`）。**目录属基础结构（.gitkeep 占位），布局文件等第二个真实布局出现时再建**；页面私有的排布留在页面子包，不上升为 layout |
| `src/routes/` | vue-router。`index.ts` 聚合路由表并创建导出 router 实例；单文件，超过约 5 条路由再按域拆分 |
| `src/stores/` | Pinia store，一域一文件，**扁平结构**（当前为空）。不建 `modules/` 子包、不建 `index.ts` barrel，导入一律写具体文件 `@/stores/xxx` |
| `src/composables/` | 可复用的组合式逻辑，`use` 前缀、文件名与导出函数同名（规则见 [component-guidelines.md](./component-guidelines.md)）。基础结构目录，文件按需再建 |

> `src/components/`、`src/layouts/` 与 `src/composables/` 属于**基础项目结构**，空目录以 `.gitkeep` 占位进版本库，内容文件按需再建。

## 路由约定

| 项 | 约定 |
|---|---|
| 库 | vue-router 4（官方标准，不引 unplugin-vue-router 等文件路由层） |
| 路由表 | `src/routes/index.ts` 单文件聚合，创建并 `export default` router 实例；超过约 5 条再按域拆分 |
| history | `createWebHistory`。生产部署需配 SPA fallback（nginx `try_files` 等）；dev 由 vite 兜底 |
| 页面加载 | 一律 `() => import(...)` 动态导入，每页独立 chunk |
| 页面引用 | 写全路径 `@/views/xx/index.vue`——Vite 默认 `resolve.extensions` 不含 `.vue`，目录简写 `@/views/xx` 解析不到 `index.vue` |
| 命名 | path 用 kebab-case 且与 `views/` 文件夹同名；route name 用驼峰；`/` 即上传页，不设 redirect |
| 路由参数 | 带参路由开 `props: true`，页面用 `defineProps` 接参，不在组件内 `useRoute()` 取参；param 合法性由数据层校验（接口 404 → 页内提示），路由层不加正则约束 |
| 404 | catch-all `/:pathMatch(.*)*` → `views/not-found/` |
| 守卫 | 暂无全局守卫与离开拦截（后端任务持久，离开后凭 videoId 回来可重连） |
| 布局接线 | 多布局切换用**嵌套路由**：布局组件作为父路由 `component`，页面挂 `children`，布局内部放 `<RouterView />`；不用 `route.meta` 动态切换 |
| App.vue | 仅 `<RouterView />`，不抽共享布局外框；页头顶栏由跨页公共组件 `AppHeader` 统一承载（壳 + BrandLogo + 默认插槽），页面差异内容经插槽注入 |

`eslint.config.ts` 对 `src/views/**/index.vue` 关闭 `vue/multi-word-component-names`：index.vue 作页面主文件或复合组件主文件（`**` 覆盖任意嵌套层级）都是该约定的组成部分，单词数规则不适用。

## 入口文件

`src/main.ts` 的顺序是固定的：

```ts
import './assets/tailwind.css'
import './assets/main.scss'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import router from '@/routes'

import App from './App.vue'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
```

约定：

1. **样式入口在最前**（先 Tailwind，再全局 SCSS）
2. 然后是框架与库
3. 再是本地模块与组件
4. 插件注册（`createPinia()`、`router`）必须在 `mount` 之前

参照：`frontend/src/main.ts`

## tsconfig 三件套

项目使用 Vite 官方脚手架的三段式 TS 配置，**不要合并成一个**：

| 文件 | 作用域 |
|---|---|
| `tsconfig.json` | 仅引用下面两个（`references`） |
| `tsconfig.app.json` | `src/` 下的应用代码 |
| `tsconfig.node.json` | `vite.config.ts` 等 Node 侧脚本 |

应用代码的配置在 `tsconfig.app.json` 中改，规则见 [type-safety.md](./type-safety.md)。

## 原型包（仓库根的 `prototype/`）

设计方向探索用的**独立 Vite 项目**，位于仓库根目录、与 `frontend/` 平级，**已被根 `.gitignore` 排除，不进版本库**。

约定：

1. **原型代码只能写在 `prototype/`，禁止写进 `frontend/`**。`frontend/` 是正式代码，任何改动必须走 Trellis 任务并在规范约束下进行。
2. 原型不受 `.trellis/spec/frontend/` 约束（这是它存在的意义：快速试错），但技术栈与 `frontend/` 保持一致（Vue 3 + Vite 8 + Tailwind 4），验证通过的方案才值得重写进正式代码。
3. **验证通过的代码要"重写"进 `frontend/`，不要整目录搬运**——重写的过程就是按正式规范落实命名、类型与目录的过程。
4. 原型可以有 mock 数据与演示媒体（如 `public/demo/`），这些永远不会出现在 `frontend/`。

> 2026-10 设计方向验证原型（黑白灰 UI + 深绿氛围色，两态布局：上传态/工作区态）即出自该包。
