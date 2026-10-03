# 组件规范

> 当前已有 `App.vue` 壳与 `views/` 下的页面壳，**尚无业务组件**。
> 本文件记录已确认的组件约定；业务组件落地后补充。

---

## 一律用 `<script setup lang="ts">`

统一这种写法：

```vue
<script setup lang="ts">
import { useTaskStore } from '@/stores/task'

const task = useTaskStore()
</script>
```

- 用 **Composition API + `<script setup>`**，不用 Options API
- **必须带 `lang="ts"`**
- 不要写 `export default {}`

参照：`frontend/src/views/upload/index.vue`

## 单文件组件里的顺序

固定为 `<script setup>` → `<template>` → `<style>`：

参照：`frontend/src/App.vue`

## 组件内引用 store

store 在 `<script setup>` 顶层调用并赋给一个变量，模板里直接用：

```vue
<script setup lang="ts">
import { useTaskStore } from '@/stores/task'

const task = useTaskStore()
</script>

<template>
  <p>状态：{{ task.status }}</p>
  <button type="button" @click="task.start()">开始</button>
</template>
```

- **`useXxxStore()` 在 setup 顶层调用**，不要放进函数体或条件分支
- 模板中调用 action 时**带括号**（`task.start()`）
- 解构 store 需用 `storeToRefs`，见 [state-management.md](./state-management.md)

## 模板中的样式类

样式类直接写在 `class` 上，用 Tailwind 工具类，**不写行内 `style`**：

```vue
<div class="flex min-h-screen flex-col items-center justify-center gap-6 bg-gray-50">
```

分工规则见 [styling.md](./styling.md)。

## 按钮必须声明 `type`

```vue
<button type="button" @click="task.start()">开始</button>
```

HTML 中 `<button>` 默认 `type="submit"`，放进表单会意外提交。**每个 `<button>` 都显式写 `type`。**

## 列表渲染必须给 `key`

项目暂无列表组件，但这是硬性要求：`v-for` 必须带 `:key`，且 key 用**稳定唯一值**（业务 id），不要用数组下标。

## 图片带显式尺寸

```vue
<img alt="视频封面" class="h-24 w-24" src="@/assets/cover.svg" width="96" height="96" />
```

同时给：`alt`（无障碍）、Tailwind 尺寸类、`width`/`height` 属性（防布局抖动）。

## composables（可复用逻辑）

`src/composables/` 已建立（.gitkeep 占位，尚无文件）。当同一段有状态逻辑被第二个组件复用时，抽到该目录：

- 文件名与导出函数同名，用 **`use` 前缀**：`src/composables/useVideoTask.ts` → `export function useVideoTask()`
- 只放**有状态逻辑**（ref/reactive/watch/生命周期），纯函数放普通 `utils`
- 返回值用对象而非数组，便于按名取用：

  ```ts
  export function useVideoTask() {
    const status = ref<'idle' | 'running' | 'done'>('idle')
    // ...
    return { status, start, cancel }
  }
  ```

- 涉及副作用清理（定时器、EventSource、事件监听）时，**在 composable 内部 `onScopeDispose` 处理**，不要留给调用方

> 项目已有 `<script setup>` 的复用依赖注入模式，composables 直接沿用同一套 Composition API，不要引入 mixin。

## 反模式

| 不要做 | 原因 |
|---|---|
| Options API / `export default` | 现有代码统一 `<script setup>` |
| `<script setup>` 不带 `lang="ts"` | 项目全面 TS |
| 行内 `style="..."` | 用 Tailwind 类或 scoped SCSS |
| `<button>` 不写 `type` | 默认 `submit`，易误触发 |
| `v-for` 不写 `:key` 或用下标 | 渲染错乱 |
| 跨目录相对路径导入 | 用 `@/` 别名 |
| 为一次性使用抽 composable | 出现第二个使用方再抽 |
