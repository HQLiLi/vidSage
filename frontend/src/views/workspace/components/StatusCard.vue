<script setup lang="ts">
// 非就绪态的引导卡：加载中 / 任务不存在 / 加载失败 / 执行中 / 执行失败。
// 一个组件承包五种形态，页面只传 kind，具体文案与按钮在这里统一。
import { computed } from 'vue'
import { storeToRefs } from 'pinia'

import dayjs from '@/lib/day'
import { TASK_RUN_STATUS } from '@/enums/task'
import { WORKSPACE_LOAD_ERROR_KIND, WORKSPACE_PHASE } from '@/enums/workspace'
import { useWorkspaceStore } from '@/stores/workspace'
import type { WorkspaceStatusKind } from '@/stores/workspace'

const props = defineProps<{
  /** 要显示哪种卡（就绪态走双栏，不进这里）。 */
  kind: WorkspaceStatusKind
}>()

const emit = defineEmits<{
  /** 点「重试」或「刷新」：重新拉一次任务内容。 */
  retry: []
  /** 点「返回上传」：回首页。 */
  back: []
}>()

const store = useWorkspaceStore()
const { taskNo, loadError, lastRun } = storeToRefs(store)

/** 失败或中断时刻的显示格式：带月日，跨天任务也不歧义。 */
const CALENDAR_FORMAT = 'MM-DD HH:mm'

/** 失败卡的正文：后端给的失败原因优先，没有就给一句兜底说明。 */
const failedMessage = computed(() => {
  const message = lastRun.value?.errorMessage
  if (message) return message
  return lastRun.value?.status === TASK_RUN_STATUS.INTERRUPTED
    ? '上次执行被中断，请重新提交'
    : '处理过程中出现错误，未返回具体原因'
})

/** 失败时刻；快照里没有时显示占位。 */
const finishedAtText = computed(() => {
  const finishedAt = lastRun.value?.finishedAt
  return finishedAt ? dayjs(finishedAt).format(CALENDAR_FORMAT) : '--'
})

/** 加载失败的文案；没有具体原因时给一句兜底。 */
const errorMessage = computed(() => {
  const error = loadError.value
  return error?.kind === WORKSPACE_LOAD_ERROR_KIND.ERROR ? error.message : '加载失败，请稍后重试'
})
</script>

<template>
  <div
    v-if="props.kind === WORKSPACE_PHASE.LOADING"
    class="animate-pulse text-center text-sm text-muted motion-reduce:animate-none"
    role="status"
  >
    正在加载任务…
  </div>

  <div
    v-else-if="props.kind === WORKSPACE_PHASE.NOT_FOUND"
    class="rounded-2xl border border-border bg-background p-5"
    role="alert"
  >
    <h2 class="text-sm font-semibold">任务不存在</h2>
    <p class="mt-2 text-sm leading-relaxed text-ink">
      没有找到任务 {{ taskNo }}，链接可能输入有误，或任务已被清理。
    </p>
    <button
      type="button"
      class="mt-4 rounded-lg border border-border px-4 py-2 text-sm font-medium transition-colors hover:border-foreground"
      @click="emit('back')"
    >
      返回上传
    </button>
  </div>

  <div
    v-else-if="props.kind === WORKSPACE_PHASE.ERROR"
    class="rounded-2xl border border-border bg-background p-5"
    role="alert"
  >
    <h2 class="text-sm font-semibold">加载失败</h2>
    <p class="mt-2 text-sm leading-relaxed text-ink">
      {{ errorMessage }}
    </p>
    <div class="mt-4 flex flex-wrap gap-2">
      <button
        type="button"
        class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-primary-hover"
        @click="emit('retry')"
      >
        重试
      </button>
      <button
        type="button"
        class="rounded-lg border border-border px-4 py-2 text-sm font-medium transition-colors hover:border-foreground"
        @click="emit('back')"
      >
        返回上传
      </button>
    </div>
  </div>

  <div
    v-else-if="props.kind === WORKSPACE_PHASE.RUNNING"
    class="rounded-2xl border border-border bg-background p-5"
    role="status"
  >
    <h2 class="text-sm font-semibold">执行中，请稍后刷新</h2>
    <p class="mt-2 text-sm leading-relaxed text-ink">
      任务正在后台处理，完成后刷新本页即可看到字幕。
    </p>
    <div class="mt-4 flex flex-wrap gap-2">
      <button
        type="button"
        class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-primary-hover"
        @click="emit('retry')"
      >
        刷新
      </button>
      <button
        type="button"
        class="rounded-lg border border-border px-4 py-2 text-sm font-medium transition-colors hover:border-foreground"
        @click="emit('back')"
      >
        返回上传
      </button>
    </div>
  </div>

  <div
    v-else
    class="rounded-2xl border border-border bg-background p-5"
    role="alert"
  >
    <h2 class="text-sm font-semibold">
      {{ lastRun?.status === TASK_RUN_STATUS.INTERRUPTED ? '任务已中断' : '任务处理失败' }}
    </h2>
    <p class="mt-2 text-sm leading-relaxed text-ink">{{ failedMessage }}</p>
    <p class="mt-3 text-xs text-muted">结束于 {{ finishedAtText }}</p>
    <button
      type="button"
      class="mt-4 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-primary-hover"
      @click="emit('back')"
    >
      重新提交
    </button>
  </div>
</template>
