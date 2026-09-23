# 目录结构

## 当前结构

```
frontend/
├── src/
│   ├── assets/
│   │   ├── logo.svg
│   │   ├── main.scss      # 全局 SCSS
│   │   └── tailwind.css   # Tailwind 入口
│   ├── stores/
│   │   └── counter.ts
│   ├── App.vue            # 根组件
│   └── main.ts            # 应用入口
├── public/
├── env.d.ts
├── eslint.config.ts
├── vite.config.ts
└── tsconfig.json / tsconfig.app.json / tsconfig.node.json
```

## 路径别名

**`@` 指向 `src/`**，两侧都配置好了：

- 构建期：`vite.config.ts` 的 `resolve.alias`
- 类型检查：`tsconfig.app.json` 的 `compilerOptions.paths`

```ts
// 正确
import { useCounterStore } from '@/stores/counter'
import logo from '@/assets/logo.svg'

// 不要用相对路径跨目录
import { useCounterStore } from '../../stores/counter'
```

参照：`frontend/src/App.vue` 与 `frontend/src/main.ts` —— 现有代码全部使用 `@/`。

## 目录职责

| 目录 | 放什么 |
|---|---|
| `src/assets/` | 静态资源与全局样式入口 |
| `src/stores/` | Pinia store，一个 store 一个文件 |
| `src/components/` | 可复用组件（**尚未创建**，出现第二个使用方时再建） |
| `src/composables/` | 可复用逻辑（**尚未创建**，见 [component-guidelines.md](./component-guidelines.md)） |
| `src/views/` 或 `src/pages/` | 路由页面（**尚未创建**，路由未引入） |

> 上表中标注「尚未创建」的目录**当前不存在**。需要时再建，不要提前铺空目录。

## 入口文件

`src/main.ts` 的顺序是固定的：

```ts
import './assets/tailwind.css'
import './assets/main.scss'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'

const app = createApp(App)

app.use(createPinia())

app.mount('#app')
```

约定：

1. **样式入口在最前**（先 Tailwind，再全局 SCSS）
2. 然后是框架与库
3. 再是本地组件
4. `createPinia()` 必须在 `mount` 之前注册

参照：`frontend/src/main.ts`

## tsconfig 三件套

项目使用 Vite 官方脚手架的三段式 TS 配置，**不要合并成一个**：

| 文件 | 作用域 |
|---|---|
| `tsconfig.json` | 仅引用下面两个（`references`） |
| `tsconfig.app.json` | `src/` 下的应用代码 |
| `tsconfig.node.json` | `vite.config.ts` 等 Node 侧脚本 |

应用代码的配置在 `tsconfig.app.json` 中改，规则见 [type-safety.md](./type-safety.md)。
