# vidSage Frontend

Vue 3 + TypeScript 前端项目，基于官方脚手架 [create-vue](https://github.com/vuejs/create-vue) 创建。

## 技术栈

- Vue 3.5 + TypeScript + Vite 8
- 状态管理：[Pinia](https://pinia.vuejs.org/zh/)
- 样式：Tailwind CSS v4（`@tailwindcss/vite` 插件）+ SCSS
- 代码质量：ESLint（含 oxlint）+ Prettier

## 命令

```bash
npm install      # 安装依赖
npm run dev      # 启动开发服务器
npm run build    # 类型检查 + 生产构建
npm run lint     # oxlint + eslint（自动修复）
npm run format   # Prettier 格式化 src/
```

## 样式约定

- Tailwind 入口：`src/assets/tailwind.css`（内容为 `@import 'tailwindcss'`，必须保持为独立 `.css` 文件，不要放进 SCSS）
- 全局 SCSS：`src/assets/main.scss`
- 组件内样式使用 `<style scoped lang="scss">`

## 目录

```
src/
├── assets/       # 样式与静态资源
├── components/   # 通用组件
├── stores/       # Pinia store
├── App.vue
└── main.ts
```
