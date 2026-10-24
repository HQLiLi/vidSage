<script setup lang="ts">
// 左列第一块：媒体预览位。
// 后端还没有提供媒体文件地址（产物端点是闭集，只有字幕与术语文件），
// 所以这里只放一个占位，不挂 <video>、不写播放地址——等后端补上端点，
// 只需在这个组件里接上地址与播放控件，别的组件不用动。
</script>

<template>
  <section
    class="flex aspect-video w-full shrink-0 flex-col items-center justify-center gap-2 bg-primary px-6 text-center"
    aria-label="媒体预览"
  >
    <svg
      viewBox="0 0 24 24"
      class="h-8 w-8 text-white/70"
      fill="none"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <rect x="2" y="4" width="20" height="16" rx="2" />
      <path d="m10 9 5 3-5 3V9Z" />
    </svg>
    <p class="text-sm font-medium text-white">媒体预览暂不可用</p>
    <p class="max-w-sm text-xs leading-relaxed text-white/60">
      后端暂未提供媒体地址，字幕仍可正常查看与搜索
    </p>
  </section>
</template>
