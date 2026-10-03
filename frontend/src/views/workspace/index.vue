<script setup lang="ts">
// 工作区页（路由 `/workspace/:videoId`）：展示单个任务的转写进度与结果。占位实现，等待业务开发。
defineProps<{
  /** 路由参数：任务对应的视频 id，由路由配置的 props: true 注入。 */
  videoId: string
}>()
</script>

<template>
  <main class="flex min-h-screen flex-col items-center justify-center gap-4 bg-gray-50">
    <h1 class="text-2xl font-bold text-gray-900">工作区</h1>
    <p class="text-gray-500">占位：任务 {{ videoId }} 的工作区。</p>
  </main>
</template>
