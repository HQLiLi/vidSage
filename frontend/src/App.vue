<script setup lang="ts">
import { useCounterStore } from '@/stores/counter'

const counter = useCounterStore()
</script>

<template>
  <div class="flex min-h-screen flex-col items-center justify-center gap-6 bg-gray-50">
    <img alt="Vue logo" class="h-24 w-24" src="@/assets/logo.svg" width="96" height="96" />

    <h1 class="text-3xl font-bold text-gray-900">vidSage Frontend</h1>

    <p class="text-gray-500">Vue 3 + TypeScript + Pinia + Tailwind CSS + SCSS</p>

    <div class="rounded-xl bg-white p-8 shadow-md">
      <p class="mb-4 text-2xl font-semibold text-gray-800">
        Count: <span class="text-emerald-600">{{ counter.count }}</span>
        <span class="ml-2 text-sm text-gray-400">(double: {{ counter.doubleCount }})</span>
      </p>
      <button class="app-button" type="button" @click="counter.increment()">Increment</button>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use 'sass:color';
/* SCSS 示例：嵌套 + 变量 */
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
