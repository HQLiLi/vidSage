# 前端开发规范

> VidSage 前端（Vue 3.5 + TypeScript 6 + Vite 8）的编码约定。
> 规则以 `frontend/` 下的真实配置与代码为依据。

---

## 规范索引

| 文档 | 内容 | 何时读 |
|------|------|--------|
| [directory-structure.md](./directory-structure.md) | `src/` 目录组织、路径别名 | 新增目录或文件时 |
| [component-guidelines.md](./component-guidelines.md) | 组件写法、`<script setup>`、composables | 写组件或抽逻辑时 |
| [state-management.md](./state-management.md) | Pinia store 组织与命名 | 需要跨组件共享状态时 |
| [type-safety.md](./type-safety.md) | TS 配置、类型组织、严格模式约束 | 写类型或遇到类型报错时 |
| [styling.md](./styling.md) | Tailwind vs SCSS 的分工 | 写样式时 |
| [animation.md](./animation.md) | GSAP 动画：生命周期、性能分层、缓动基线 | 写任何 GSAP 动效时 |
| [api.md](./api.md) | axios 实例、api 包组织、错误处理约定 | 写与后端通信的请求时 |
| [quality-guidelines.md](./quality-guidelines.md) | 双 linter、格式化、注释要求 | 写任何代码前 |

---

## 技术栈基线

**以 `frontend/package.json` 为准，不要凭记忆写版本号。**

| 项 | 值 |
|---|---|
| Vue | `^3.5.42` |
| TypeScript | `~6.0.0` |
| Vite | `^8.2.2` |
| Pinia | `^4.0.3` |
| Tailwind CSS | `^4.3.3`（经 `@tailwindcss/vite` 插件） |
| SASS | `^1.105.0` |
| GSAP | `^3.15.0`（动画，统一入口 `src/lib/gsap.ts`，规范见 [animation.md](./animation.md)） |
| axios | `^1.20.0`（HTTP 请求，统一入口 `src/api/request.ts`，规范见 [api.md](./api.md)） |
| ESLint | `^10.10.0` |
| oxlint | `~1.82.0` |
| Prettier | `3.9.6` |
| Node | `^22.18.0 \|\| >=24.12.0`（`engines` 字段强制） |

> 注意本项目是 **Vite 8 + TypeScript 6 + ESLint 10**，都属较新的大版本。写代码前先确认 API 没有变化，不要照搬旧版本教程。

---

## 常用命令

在 `frontend/` 目录下执行：

```bash
npm run dev          # 开发服务器
npm run build        # 类型检查 + 构建（并行执行）
npm run type-check   # 仅 vue-tsc --build
npm run lint         # oxlint --fix 然后 eslint --fix --cache
npm run format       # prettier 格式化 src/
npm run preview      # 预览构建产物
```

**提交前至少跑通 `npm run type-check` 和 `npm run lint`。**

## 编辑器配置

`.vscode/settings.json` 已配置保存时自动格式化（Prettier）与 `source.fixAll`。推荐扩展见 `.vscode/extensions.json`：Volar、ESLint、EditorConfig、oxc、Prettier。
