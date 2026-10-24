<script setup lang="ts">
// 左列第三块：主题区。
// 只显示 context.json 里的 theme（一两句话）——terms 是模型抽的术语，
// 和用户关键词不是一回事，不在这块展示。提取失败时给明确的空态，不留白。
import { computed } from 'vue'
import { storeToRefs } from 'pinia'

import { useWorkspaceStore } from '@/stores/workspace'
import { useWorkspaceSubtitlesStore } from '@/stores/workspaceSubtitles'

const store = useWorkspaceSubtitlesStore()
const { context } = storeToRefs(store)

const workspace = useWorkspaceStore()
const { isBusy } = storeToRefs(workspace)

/** 有没有可展示的主题：没拿到上下文、降级标记、或主题是空串，都算没有。 */
const hasTheme = computed(() => {
  const value = context.value
  if (!value) return false
  if (value.degraded) return false
  return value.theme.trim().length > 0
})

/** 首次生成中：还没有上下文可看，说「正在生成」；重新生成时保留旧主题，不说这句。 */
const generating = computed(() => isBusy.value && context.value === null)
</script>

<template>
  <section class="flex min-h-0 shrink-0 flex-col overflow-hidden">
    <header class="flex shrink-0 items-center gap-2 px-4 py-3 sm:px-6">
      <h2 class="text-sm font-semibold">主题</h2>
    </header>

    <div class="px-4 pb-3 sm:px-6">
      <div
        v-if="generating"
        class="animate-pulse rounded-lg border border-dashed border-border bg-background px-3 py-2.5 motion-reduce:animate-none"
        role="status"
      >
        <p class="text-sm font-medium text-ink">正在生成主题…</p>
        <p class="mt-0.5 text-xs leading-relaxed text-muted">字幕完成后即可看到本次清洗提炼的主题。</p>
      </div>
      <p v-else-if="hasTheme" class="text-sm leading-relaxed text-ink">
        {{ context?.theme }}
      </p>
      <div
        v-else
        class="rounded-lg border border-dashed border-border bg-background px-3 py-2.5"
        role="status"
      >
        <p class="text-sm font-medium text-ink">未能提取主题</p>
        <p class="mt-0.5 text-xs leading-relaxed text-muted">
          本次清洗没有产出可用主题，字幕仍可正常查看。
        </p>
      </div>
    </div>
  </section>
</template>
