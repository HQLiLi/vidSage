<script setup lang="ts">
// 右列：字幕区。搜索、计数、选中。
// 行尾的铅笔是编辑入口的占位：后端还没有「改单条字幕」的接口，先禁用展示。
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { useWorkspaceSubtitlesStore } from '@/stores/workspaceSubtitles'
import { formatTime } from '@/utils/format'

const store = useWorkspaceSubtitlesStore()
const { cues } = storeToRefs(store)

/** 搜索词。 */
const query = ref('')
/** 当前选中的段落下标；null = 没选中。 */
const selectedIndex = ref<number | null>(null)

/** 按搜索词过滤后的字幕行。搜索只匹配原文——本页还不显示译文。 */
const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return cues.value
  return cues.value.filter((cue) => cue.text.toLowerCase().includes(keyword))
})

/** 字幕条数变了就清掉选中：下标可能已经指到别的句子。 */
watch(
  () => cues.value.length,
  () => {
    selectedIndex.value = null
  },
)

/** 点整行：选中这一条（没有播放源，所以只是选中，不做跳转）。 */
function toggleSelect(index: number): void {
  selectedIndex.value = selectedIndex.value === index ? null : index
}
</script>

<template>
  <aside class="flex min-h-0 flex-1 flex-col bg-background">
    <div class="shrink-0 border-b border-border px-4 py-3">
      <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <div class="flex min-w-0 items-baseline gap-2">
          <h2 class="shrink-0 text-sm font-semibold">字幕稿</h2>
          <span class="text-xs text-faint">{{ filtered.length }}/{{ cues.length }} 条</span>
        </div>
        <!-- 语种选择与翻译按钮的位置：触发翻译是下一步的事，这里先留空 -->
      </div>

      <div class="relative mt-2.5">
        <svg
          viewBox="0 0 24 24"
          class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
        <input
          v-model="query"
          type="text"
          placeholder="搜索字幕内容…"
          aria-label="搜索字幕内容"
          class="w-full rounded-lg border border-border bg-surface py-2 pl-9 pr-8 text-sm outline-none transition-colors placeholder:text-faint focus:border-foreground/40 focus:bg-background"
        />
        <button
          v-if="query"
          type="button"
          aria-label="清空搜索"
          class="absolute right-1.5 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded text-faint transition-colors hover:text-foreground"
          @click="query = ''"
        >
          <svg
            viewBox="0 0 24 24"
            class="h-3.5 w-3.5"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            aria-hidden="true"
          >
            <path d="M6 6l12 12M18 6 6 18" />
          </svg>
        </button>
      </div>
    </div>

    <div
      class="overlay-scrollbar relative min-h-0 flex-1 overflow-y-auto overscroll-contain px-2 pb-40 pt-2"
    >
      <p v-if="cues.length === 0" class="px-3 py-10 text-center text-sm text-faint">
        这条任务还没有可用字幕
      </p>
      <p v-else-if="filtered.length === 0" class="px-3 py-10 text-center text-sm text-faint">
        没有匹配「{{ query }}」的字幕
      </p>

      <!--
        字幕行不是控件：点一下只是选中高亮，没有别的动作，所以不给 role="button"
        （那会让内嵌的修改按钮变成非法嵌套）。真正可键盘操作的是行尾的修改按钮。
      -->
      <ul v-if="filtered.length > 0" class="flex flex-col">
        <li
          v-for="cue in filtered"
          :key="cue.index"
          :aria-current="selectedIndex === cue.index ? 'true' : undefined"
          class="group relative flex w-full cursor-pointer items-start gap-3 rounded-lg px-3 py-2 pr-10 text-left transition-colors"
          :class="selectedIndex === cue.index ? 'bg-mist' : 'hover:bg-mist/60'"
          @click="toggleSelect(cue.index)"
        >
          <span
            v-if="selectedIndex === cue.index"
            class="absolute bottom-2.5 left-0 top-2.5 w-0.5 rounded-full bg-primary"
            aria-hidden="true"
          ></span>
          <span
            class="w-11 shrink-0 pt-0.75 text-[13px] tabular-nums"
            :class="selectedIndex === cue.index ? 'font-medium text-foreground' : 'text-muted'"
          >
            {{ formatTime(cue.startMs / 1000) }}
          </span>

          <div class="min-w-0 flex-1">
            <p
              class="text-[15px] leading-relaxed"
              :class="selectedIndex === cue.index ? 'font-medium text-foreground' : 'text-ink'"
            >
              {{ cue.text }}
            </p>
          </div>

          <!-- 编辑入口占位：后端还没有改单条字幕的接口，先禁用，等接口就绪再接 -->
          <button
            type="button"
            title="编辑功能暂未开放"
            :aria-label="`修改第 ${cue.index + 1} 条字幕原文（编辑功能暂未开放）`"
            class="absolute right-2 top-2 flex h-7 w-7 cursor-not-allowed items-center justify-center text-muted opacity-0 transition-[color,opacity] group-hover:opacity-100"
            disabled
            @click.stop
            @keydown.stop
          >
            <svg
              viewBox="0 0 24 24"
              class="h-3.5 w-3.5"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="m15.5 5.5 3 3M4 20l4.5-1 10-10a2.12 2.12 0 0 0-3-3l-10 10L4 20Z" />
            </svg>
          </button>
        </li>
      </ul>
    </div>
  </aside>
</template>
