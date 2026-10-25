<script setup lang="ts">
// 视频区上方的标题条：返回、任务标题。
// 顶栏只留进度胶囊与产物，标题跟着左列走；宽度上限交给 CSS，完整内容放原生悬浮提示。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'

import { useWorkspaceStore } from '@/stores/workspace'

const router = useRouter()

const store = useWorkspaceStore()
const { snapshot } = storeToRefs(store)

/** 完整标题：任务来源（链接原文或上传文件名）。 */
const fullTitle = computed(() => snapshot.value?.sourceUri ?? '')

/** 回上传页。 */
function goUpload(): void {
  void router.push('/')
}
</script>

<template>
  <!-- 行本身不留内边距，标题的 max-w-[80%] 才能按整条（= 视频宽）算，而不是按内缩后的内容宽 -->
  <div class="flex shrink-0 items-center gap-3 py-2">
    <button
      type="button"
      class="ml-4 flex shrink-0 items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-muted transition-colors hover:bg-mist hover:text-foreground sm:ml-6"
      @click="goUpload"
    >
      <svg
        viewBox="0 0 24 24"
        class="h-4 w-4"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="m15 18-6-6 6-6" />
      </svg>
      返回
    </button>
    <span class="h-4 w-px shrink-0 bg-border" aria-hidden="true"></span>
    <!-- 最宽占视频区八成，再长就被 truncate 打点；窄屏也不会把返回挤走（还能继续缩）。
         右边距用 margin 而不是行内边距，否则 max-w-[80%] 的基准会被内边距改小 -->
    <p class="mr-4 min-w-0 max-w-[80%] truncate text-sm font-medium sm:mr-6" :title="fullTitle">
      {{ fullTitle }}
    </p>
  </div>
</template>
