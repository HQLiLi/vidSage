# 样式规范

> 项目**同时装了 Tailwind CSS 4 与 SCSS**，两者都在用且都有明确分工。
> 本文规定什么时候用哪个，避免同一处样式两套写法混用。

---

## 分工

| 用途 | 用什么 | 例子 |
|---|---|---|
| 布局、间距、尺寸、颜色、圆角、阴影等**一次性工具样式** | **Tailwind 工具类**（写在模板 `class` 上） | `flex min-h-screen flex-col items-center gap-6 bg-gray-50` |
| **需要嵌套、变量、mixins** 的组件内部样式 | **scoped SCSS**（`<style scoped lang="scss">`） | hover 态、主题色变量、复杂选择器 |
| 全局样式 / 设计令牌 | `src/assets/main.scss` | 全局 reset、CSS 变量 |

**同一个元素不要一半用 Tailwind 一半用 SCSS 描述同一件事。**

## Tailwind 用法

### 版本说明

项目用的是 **Tailwind CSS 4**，经 `@tailwindcss/vite` 插件接入：

```ts
// vite.config.ts
plugins: [vue(), vueDevTools(), tailwindcss()],
```

**Tailwind 4 是 CSS-first 配置**——项目里**没有** `tailwind.config.js` / `.ts`，这是正常的，不要为了"配置主题"新建它。主题定制写在 CSS 里。

### 入口

`src/assets/tailwind.css` 是 Tailwind 入口，在 `src/main.ts` 中**第一个**被 import：

```ts
import './assets/tailwind.css'
import './assets/main.scss'
```

顺序不能颠倒，否则全局 SCSS 会被 Tailwind 的基础层覆盖。

## SCSS 用法

### 组件内

用 `<style scoped lang="scss">`，**默认加 `scoped`**：

```vue
<style scoped lang="scss">
@use 'sass:color';

$primary-color: #059669;

.app-button {
  padding: 0.5rem 1.5rem;
  border-radius: 0.5rem;
  font-weight: 600;
  color: white;
  background-color: $primary-color;
  transition: background-color 0.2s;

  &:hover {
    background-color: color.scale($primary-color, $lightness: -8%);
  }
}
</style>
```

### 用 `@use`，不用 `@import`

```scss
// 正确
@use 'sass:color';

// 废弃写法，不要用
@import 'sass:color';
```

`@import` 在 Dart Sass 中已废弃，且会污染全局命名空间。

### 内置模块显式引入

用 `sass:` 命名空间的内置模块（`color`、`math`、`map`、`string` 等）必须显式 `@use`，不要依赖隐式全局函数。

```scss
@use 'sass:color';
color.scale($primary-color, $lightness: -8%)
```

## 命名

- **优先 Tailwind 类**，不要为每个元素发明 SCSS 类名
- 确实需要自定义类时，用**语义化 kebab-case**，不用 BEM 双下划线风格：`app-button` ✅、`app__button--primary` ❌
- 当前代码中还没有自定义类，首个出现时跟随上面的风格

## 反模式

| 不要做 | 原因 |
|---|---|
| 新建 `tailwind.config.js` | Tailwind 4 是 CSS-first，项目未使用该文件 |
| 颠倒 `main.ts` 的样式 import 顺序 | 层叠顺序会错 |
| `@import` 引入 SCSS 模块 | 已废弃，用 `@use` |
| `<style>` 不加 `scoped` | 污染全局 |
| 行内 `style="..."` | 破坏 Tailwind/SCSS 的分工 |
| 同一元素混用 Tailwind 与 SCSS 表达同一属性 | 覆盖关系难追踪 |
| 用 `@apply` 把大段工具类塞进 SCSS | 失去 Tailwind 的可读性优势；项目现有代码未用 |
