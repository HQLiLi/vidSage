<script setup lang="ts">
// 工作区字幕稿（页面私有组件）：搜索（原文 + 译文）、双行双语渲染、当前句高亮、
// 点击行 seek、自动滚动跟随（用户滚动后暂停 3 秒，design §3.6 / R4 / R5）。
import { computed, ref, useTemplateRef, watch } from 'vue'

import { formatTime } from '@/utils/format'
import type { TranscriptPlayer } from '@/composables/useTranscriptPlayer'
import type { TranscriptCue } from '@/types/transcript'

const props = defineProps<{
  /** 字幕稿渲染模型（store 产出、shell 注入）：原文 + 可选译文。 */
  cues: TranscriptCue[]
  /** shell 创建的字幕联动播放器：提供当前句下标与 seek（秒口径）。 */
  player: TranscriptPlayer
}>()

const { activeIndex, seek } = props.player

/** 搜索词：同时匹配原文与译文（R5）。 */
const query = ref('')

/** 过滤后的字幕：大小写不敏感的包含匹配，命中原文或译文任一即保留。 */
const filtered = computed<TranscriptCue[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.cues
  return props.cues.filter(
    (cue) =>
      cue.text.toLowerCase().includes(q) || (cue.translatedText ?? '').toLowerCase().includes(q),
  )
})

/** 当前句对象：activeIndex 是全量 cues 的下标（非搜索态下与过滤视图一一对应）。 */
const activeCue = computed(() => {
  if (activeIndex.value < 0) return null
  return props.cues[activeIndex.value] ?? null
})

// —— 自动滚动跟随 ——

/** 用户手动滚动后自动跟随的暂停时长（毫秒）。 */
const USER_SCROLL_PAUSE_MS = 3000

const listEl = useTemplateRef<HTMLElement>('listEl')
let lastUserScrollAt = 0

/** 用户手动滚动打点：3 秒内暂停自动跟随，避免与播放跟随抢滚动。 */
function markUserScroll(): void {
  lastUserScrollAt = Date.now()
}

// 当前句变化时滚动到列表垂直居中；搜索过滤中与用户刚手动滚动时不动
watch(activeIndex, (index) => {
  if (index < 0 || query.value.trim() !== '') return
  if (Date.now() - lastUserScrollAt < USER_SCROLL_PAUSE_MS) return
  const container = listEl.value
  const cue = props.cues[index]
  if (!container || !cue) return
  // data-cue-id 属性选择器定位：cue.id 以数字开头，# 形式的 ID 选择器不合法
  const el = container.querySelector(`[data-cue-id="${cue.id}"]`)
  if (!(el instanceof HTMLElement)) return
  const target = el.offsetTop - container.clientHeight / 2 + el.clientHeight / 2
  container.scrollTo({ top: Math.max(target, 0), behavior: 'smooth' })
})

/** 点击字幕行：跳到该句开始时间（cue 时间戳为毫秒，seek 口径为秒，见 useTranscriptPlayer）。 */
function seekToCue(cue: TranscriptCue): void {
  seek(cue.startMs / 1000)
}
</script>

<template>
  <aside class="flex min-h-0 flex-1 flex-col bg-background lg:flex-none">
    <div class="shrink-0 border-b border-border px-4 py-3">
      <div class="flex items-baseline gap-2">
        <h2 class="text-sm font-semibold">字幕稿</h2>
        <span class="text-xs text-faint">{{ filtered.length }}/{{ cues.length }} 条</span>
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
          placeholder="搜索原文或译文…"
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
      ref="listEl"
      class="relative min-h-0 flex-1 overflow-y-auto px-2 pb-40 pt-2"
      @wheel="markUserScroll"
      @touchstart="markUserScroll"
      @pointerdown="markUserScroll"
    >
      <p v-if="cues.length === 0" class="px-3 py-10 text-center text-sm text-faint">
        暂无字幕内容
      </p>
      <p v-else-if="filtered.length === 0" class="px-3 py-10 text-center text-sm text-faint">
        没有匹配「{{ query }}」的字幕
      </p>

      <button
        v-for="cue in filtered"
        :key="cue.id"
        type="button"
        :data-cue-id="cue.id"
        class="relative flex w-full items-start gap-3 rounded-lg px-3 py-2 text-left transition-colors"
        :class="cue === activeCue ? 'bg-mist' : 'hover:bg-mist/60'"
        @click="seekToCue(cue)"
      >
        <span
          v-if="cue === activeCue"
          class="absolute bottom-2.5 left-0 top-2.5 w-0.5 rounded-full bg-primary"
          aria-hidden="true"
        ></span>
        <span
          class="w-11 shrink-0 pt-[3px] text-[13px] tabular-nums"
          :class="cue === activeCue ? 'font-medium text-foreground' : 'text-muted'"
        >{{ formatTime(cue.startMs / 1000) }}</span>
        <span class="min-w-0 flex-1">
          <!-- 双行双语（D1）：原文在上、译文在下（稍强调）；未翻译条目保持单行 -->
          <span
            class="block text-[15px] leading-relaxed"
            :class="cue === activeCue ? 'font-medium text-foreground' : 'text-ink'"
          >{{ cue.text }}</span>
          <span
            v-if="cue.translatedText"
            class="block text-[15px] leading-relaxed text-foreground"
            :class="cue === activeCue ? 'font-medium' : ''"
          >{{ cue.translatedText }}</span>
        </span>
      </button>
    </div>
  </aside>
</template>
