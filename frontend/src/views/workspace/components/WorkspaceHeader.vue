<script setup lang="ts">
// 工作区顶栏：返回、任务标题、进度胶囊、产物下拉。
// 外壳沿用跨页的 AppHeader，和上传页是同一条顶栏。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'

import AppHeader from '@/components/AppHeader.vue'
import { TASK_RUN_TYPE } from '@/enums/task'
import { RUN_STREAM_STATE } from '@/enums/workspace'
import { useWorkspaceStore } from '@/stores/workspace'
import type { TaskRunType } from '@/types/task'
import ArtifactMenu from '@/views/workspace/components/ArtifactMenu.vue'

const router = useRouter()

const store = useWorkspaceStore()
const { snapshot, streamState, streamError, runPercent, activeRunType, activeRunNo, isBusy } =
  storeToRefs(store)
const { retryStream } = store

/** 顶栏标题：任务来源（链接原文或上传文件名）。 */
const title = computed(() => snapshot.value?.sourceUri ?? '')

/** 每种 run 在胶囊里怎么说。 */
const RUN_LABELS: Record<TaskRunType, string> = {
  [TASK_RUN_TYPE.MAIN]: '处理中',
  [TASK_RUN_TYPE.RECLEAN]: '重新生成中',
  [TASK_RUN_TYPE.TRANSLATE]: '翻译中',
}

/** 胶囊文案：重连中文案换掉，其余按 run 类型。 */
const statusLabel = computed(() => {
  if (streamState.value === RUN_STREAM_STATE.RECONNECTING) return '连接中断，正在重连…'
  const type = activeRunType.value
  return type === null ? '处理中' : RUN_LABELS[type]
})

/** 只在有活干或流断了才占位；触发被拒（没有 run 可跟）留给字幕区的错误条。 */
const showsStatus = computed(
  () => isBusy.value || (activeRunNo.value !== null && streamError.value !== null),
)

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
    <div
      v-if="showsStatus"
      class="flex shrink-0 items-center gap-2 rounded-full border border-border bg-mist/60 px-2.5 py-1"
      role="status"
    >
      <template v-if="streamError">
        <span class="text-xs text-muted">{{ streamError }}</span>
        <button
          type="button"
          class="rounded-full px-1.5 text-xs font-medium text-primary transition-colors hover:bg-background"
          @click="retryStream()"
        >
          重试
        </button>
      </template>
      <template v-else>
        <span
          class="h-1.5 w-1.5 rounded-full bg-primary"
          :class="
            streamState === RUN_STREAM_STATE.RECONNECTING
              ? 'animate-pulse motion-reduce:animate-none'
              : ''
          "
          aria-hidden="true"
        ></span>
        <span class="text-xs text-muted">{{ statusLabel }}</span>
        <span class="text-xs tabular-nums text-ink">{{ runPercent }}%</span>
      </template>
    </div>
    <ArtifactMenu />
  </AppHeader>
</template>
