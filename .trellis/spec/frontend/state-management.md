# 状态管理

> 用 **Pinia**，且用 **Setup Store** 写法（在 `defineStore` 里写 Composition API）。
> store 目录：`src/stores/`，**扁平结构**——不建 `modules/` 子包、不建 `index.ts` barrel（Vuex 时代遗产），一律从具体文件导入：`import { useTaskStore } from '@/stores/task'`。当前目录为空（脚手架 counter 演示已删除）。

---

## 注册

Pinia 在 `src/main.ts` 中注册，**必须在 `mount` 之前**：

```ts
import { createPinia } from 'pinia'

const app = createApp(App)

app.use(createPinia())

app.mount('#app')
```

参照：`frontend/src/main.ts`

## 写 store：Setup Store 风格

统一用这种写法（示例沿用 Pinia 官方 counter 形态）：

```ts
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export const useCounterStore = defineStore('counter', () => {
  const count = ref(0)
  const doubleCount = computed(() => count.value * 2)
  function increment() {
    count.value++
  }

  return { count, doubleCount, increment }
})
```

约定：

- **第二个参数是函数**（Setup Store），不用带 `state` / `getters` / `actions` 的对象写法
- **state 用 `ref`**，**getter 用 `computed`**，**action 用普通函数**
- 最后**必须 `return` 所有需要暴露的成员**
- store 的 id（第一个参数）用小写单词，与 `useXxxStore` 对应：`'counter'` ↔ `useCounterStore`

## 命名

| 项 | 约定 | 例子 |
|---|---|---|
| 文件 | `<功能>.ts`，小写 | `counter.ts` |
| 导出 | `use` + PascalCase + `Store` | `useCounterStore` |
| store id | 与文件名一致的小写单词 | `'counter'` |

## 在组件中使用

```vue
<script setup lang="ts">
import { useCounterStore } from '@/stores/counter'

const counter = useCounterStore()
</script>

<template>
  <p>Count: {{ counter.count }}</p>
  <button type="button" @click="counter.increment()">Increment</button>
</template>
```

- 在 `<script setup>` **顶层**调用 `useXxxStore()`
- 模板中**直接访问**属性（`counter.count`），不需要 `.value`
- 调用 action 时**带括号**：`counter.increment()`

## 解构要用 `storeToRefs`

直接解构 store 会**丢失响应性**，因为 setup store 返回的是 `ref` 集合：

```ts
import { storeToRefs } from 'pinia'
import { useCounterStore } from '@/stores/counter'

const counter = useCounterStore()

// ✅ state 与 getter 用 storeToRefs
const { count, doubleCount } = storeToRefs(counter)

// ✅ action 是普通函数，可以直接解构
const { increment } = counter
```

**规则：`storeToRefs` 用于 state/getter，action 直接解构。**

## 什么时候该建 store

- **跨组件共享**且需要响应式 → 建 store
- 只在单个组件内使用 → 用组件自己的 `ref` / `reactive`，**不要建 store**
- 纯服务端数据获取缓存 → 现阶段无方案，用到时再定（不要提前引入数据请求库）

## 注释要求

项目要求**每个函数都要写注释**（见 [quality-guidelines.md](./quality-guidelines.md)）。

**新增的 action 必须写注释**（脚手架的 counter 曾以无注释为先例，已删除，不要效仿）：

```ts
/** 计数加一。 */
function increment() {
  count.value++
}
```

## 反模式

| 不要做 | 原因 |
|---|---|
| 用 Options Store（`state`/`getters`/`actions` 对象） | 统一 Setup Store 写法 |
| `defineStore` 回调里忘记 `return` 成员 | 组件拿不到 |
| 忘记 `storeToRefs` 就解构 state | 丢失响应性，是最高频的坑 |
| 在条件分支或函数体里调用 `useXxxStore()` | 必须在 `<script setup>` 顶层 |
| 为单组件内部状态建 store | 过度设计 |
| 组件里改 store state 而不通过 action | 逻辑分散，难追踪 |
