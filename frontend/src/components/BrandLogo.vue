<script setup lang="ts">
// 品牌 logo：黑底声波图标 + 字标，上传页与工作区顶栏共用（跨页公共组件，扁平放 components/）。
// 图形仅用设计令牌（bg-primary / stroke-background）；深绿不进 logo——黑白灰单色方向的约束。
</script>

<template>
  <span class="flex items-center gap-2.5">
    <span class="flex h-7 w-7 items-center justify-center rounded-lg bg-primary">
      <svg
        viewBox="0 0 20 20"
        class="h-4 w-4 stroke-background"
        fill="none"
        stroke-width="1.8"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M2.5 11.5 6 5.5l3 7.5 3-9 2.5 5.5" />
        <path d="M3 16.5h14" opacity="0.35" />
      </svg>
    </span>
    <span class="text-[15px] font-semibold tracking-tight">vidSage</span>
  </span>
</template>
