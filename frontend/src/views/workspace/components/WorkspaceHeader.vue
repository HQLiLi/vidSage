<script setup lang="ts">
// 工作区顶栏：进度胶囊、产物抽屉入口。
// 外壳沿用跨页的 AppHeader，和上传页是同一条顶栏；返回与任务标题在视频区上方的
// WorkspaceTitleBar 里。
import { computed } from 'vue'
import { storeToRefs } from 'pinia'

import AppHeader from '@/components/AppHeader.vue'
import { TASK_RUN_TYPE } from '@/enums/task'
import { RUN_STREAM_STATE } from '@/enums/workspace'
import { useWorkspaceStore } from '@/stores/workspace'
import type { TaskRunType } from '@/types/task'
import ArtifactDrawer from '@/views/workspace/components/ArtifactDrawer.vue'

const store = useWorkspaceStore()
const { streamState, streamError, runPercent, activeRunType, activeRunNo, isBusy } =
  storeToRefs(store)
const { retryStream } = store

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
</script>

<template>
  <AppHeader>
    <!-- 标题挪走后靠 ml-auto 顶住右侧，和顶栏 logo 之间留白 -->
    <div class="ml-auto flex items-center gap-3">
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
      <ArtifactDrawer />
    </div>
  </AppHeader>
</template>
