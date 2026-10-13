<script setup lang="ts">
// 应用顶栏（跨页公共组件）：统一壳（高度 / 底边框 / 顶栏底色 / 内边距）+ 品牌 Logo，
// 页面差异内容经默认插槽追加在 Logo 右侧（上传页与工作区非就绪态为空壳，
// 工作区就绪态放返回 / 任务标题 / 翻译入口 / 产物面板）。
// relative z-20：GSAP transform 残留会在 main 一侧创建包含块 + stacking context，
// 页内绝对定位下拉面板要盖过 main 必须靠顶栏自身的层叠序。
import BrandLogo from '@/components/BrandLogo.vue'
</script>

<template>
  <header
    class="relative z-20 flex h-14 shrink-0 items-center gap-3 border-b border-border bg-surface px-4 sm:px-6"
  >
    <BrandLogo />
    <slot />
  </header>
</template>
