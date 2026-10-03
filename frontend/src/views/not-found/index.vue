<script setup lang="ts">
// 404 兜底页：catch-all 路由（/:pathMatch(.*)*）指向这里。
</script>

<template>
  <main class="flex min-h-screen flex-col items-center justify-center gap-4 bg-gray-50">
    <h1 class="text-3xl font-bold text-gray-900">404</h1>
    <p class="text-gray-500">页面不存在。</p>
    <RouterLink class="text-emerald-600 hover:underline" to="/">返回上传页</RouterLink>
  </main>
</template>
