<script setup lang="ts">
// 工作区顶栏：返回、任务标题、产物下拉。
// 外壳沿用跨页的 AppHeader，和上传页是同一条顶栏。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'

import AppHeader from '@/components/AppHeader.vue'
import ArtifactMenu from '@/views/workspace/components/ArtifactMenu.vue'
import { useWorkspaceStore } from '@/stores/workspace'

const router = useRouter()

const store = useWorkspaceStore()
const { snapshot } = storeToRefs(store)

/** 顶栏标题：任务来源（链接原文或上传文件名）。 */
const title = computed(() => snapshot.value?.sourceUri ?? '')

/** 回上传页。 */
function goUpload(): void {
  void router.push('/')
}
</script>

<template>
  <AppHeader>
    <button
      type="button"
      class="flex shrink-0 items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-muted transition-colors hover:bg-mist hover:text-foreground"
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
        <path d="M19 12H5m6-7-7 7 7 7" />
      </svg>
      返回
    </button>
    <span class="h-4 w-px bg-border" aria-hidden="true"></span>
    <p class="min-w-0 flex-1 truncate text-sm font-medium">{{ title }}</p>
    <!-- 进度胶囊的位置：订阅进度流是下一步的事，这里先留空 -->
    <ArtifactMenu />
  </AppHeader>
</template>
