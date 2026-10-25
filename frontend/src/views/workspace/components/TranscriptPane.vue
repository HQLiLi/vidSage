<script setup lang="ts">
// 右列：字幕区。搜索、计数、选中、双语行，以及运行中的进度与提示。
// 行尾的铅笔是编辑入口的占位：后端还没有「改单条字幕」的接口，先禁用展示。
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { TASK_RUN_STATUS, TASK_RUN_TYPE } from '@/enums/task'
import { useWorkspaceStore } from '@/stores/workspace'
import { useWorkspaceSubtitlesStore } from '@/stores/workspaceSubtitles'
import { formatTime } from '@/utils/format'
import TranslationBar from '@/views/workspace/components/TranslationBar.vue'

const subtitles = useWorkspaceSubtitlesStore()
const { cues, cleanedTrack, translationMismatch } = storeToRefs(subtitles)

const workspace = useWorkspaceStore()
const {
  isBusy,
  languages,
  selectedSourceLanguage,
  selectedTargetLanguage,
  activeRunType,
  runPercent,
  runMessage,
  streamError,
  translationInvalidated,
  lastRun,
} = storeToRefs(workspace)
const { selectSourceLanguage, selectTargetLanguage, swapLanguages, translate } = workspace

/** 搜索词。 */
const query = ref('')
/** 当前选中的段落下标；null = 没选中。 */
const selectedIndex = ref<number | null>(null)

/** 按搜索词过滤后的字幕行。原文与译文都参与匹配——两种文字都在屏幕上。 */
const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return cues.value
  return cues.value.filter(
    (cue) =>
      cue.text.toLowerCase().includes(keyword) ||
      (cue.translatedText?.toLowerCase().includes(keyword) ?? false),
  )
})

/** 首次生成中：还没有字幕可看，用骨架占位。重新生成时旧字幕还在，不走这里。 */
const generating = computed(() => isBusy.value && cleanedTrack.value === null)

/** 正在翻译（区别于重新生成）。 */
const translating = computed(() => isBusy.value && activeRunType.value === TASK_RUN_TYPE.TRANSLATE)

/** 最近一次翻译 / 重生成失败了；主任务失败且有字幕的情况不在这里管。 */
const runErrorMessage = computed(() => {
  const run = lastRun.value
  if (run === null) return null
  if (run.runType === TASK_RUN_TYPE.MAIN) return null
  if (run.status !== TASK_RUN_STATUS.FAILED && run.status !== TASK_RUN_STATUS.INTERRUPTED) return null
  return run.errorMessage ?? '本次执行失败，字幕内容未受影响。'
})

/** 错误条文案：触发被拒的请求错误优先，其次是最近一次非主 run 的失败原因。 */
const errorText = computed(() => streamError.value ?? runErrorMessage.value)

/** 错误条已被手动关掉。 */
const errorClosed = ref(false)
/** 换一条错误或换一条 run 就重新显示。 */
watch([streamError, () => lastRun.value?.runNo], () => {
  errorClosed.value = false
})
const showError = computed(() => !errorClosed.value && errorText.value !== null)

/** 译文失效提示已被手动关掉。 */
const invalidatedClosed = ref(false)
/** 每次重新置为「失效」时都要重新露出来。 */
watch(translationInvalidated, (value) => {
  if (value) invalidatedClosed.value = false
})
const showInvalidated = computed(() => translationInvalidated.value && !invalidatedClosed.value)

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

/** 换源语种。 */
function onSourceLanguage(code: string): void {
  selectSourceLanguage(code)
}

/** 换目标语种：有现成译文直接显示。 */
function onTargetLanguage(code: string): void {
  void selectTargetLanguage(code)
}

/** 互换源 / 目标语种。 */
function onSwapLanguages(): void {
  void swapLanguages()
}

/** 触发翻译。 */
function onTranslate(): void {
  void translate()
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
      </div>

      <!-- 运行中：当前阶段与百分比。重新生成 / 翻译时字幕留在下面，只换头部 -->
      <div v-if="isBusy" class="mt-2">
        <div class="flex items-center justify-between gap-3 text-xs">
          <span class="min-w-0 truncate text-muted">{{ runMessage || '正在处理…' }}</span>
          <span class="shrink-0 tabular-nums text-muted">{{ runPercent }}%</span>
        </div>
        <div
          class="mt-1.5 h-1 overflow-hidden rounded-full bg-mist"
          role="progressbar"
          aria-label="任务进度"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="runPercent"
        >
          <div
            class="h-full rounded-full bg-primary transition-[width] duration-300 ease-out"
            :style="{ width: `${runPercent}%` }"
          ></div>
        </div>
      </div>

      <TranslationBar
        v-if="cleanedTrack !== null"
        class="mt-2.5"
        :languages="languages"
        :source-language="selectedSourceLanguage"
        :target-language="selectedTargetLanguage"
        :busy="isBusy"
        :translating="translating"
        :progress="runPercent"
        @update:source-language="onSourceLanguage"
        @update:target-language="onTargetLanguage"
        @swap="onSwapLanguages"
        @translate="onTranslate"
      />

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

    <!-- 顶部提示条：时间轴不一致 / 译文失效 / 执行或连接错误 -->
    <div
      v-if="translationMismatch || showInvalidated || showError"
      class="shrink-0 space-y-2 px-4 pt-2"
    >
      <p
        v-if="translationMismatch"
        class="rounded-lg border border-border bg-mist/60 px-3 py-2 text-xs leading-relaxed text-muted"
      >
        译文与原文时间轴不一致，已隐藏译文。
      </p>

      <div
        v-if="showInvalidated"
        class="flex items-start justify-between gap-3 rounded-lg border border-border bg-mist/60 px-3 py-2"
        role="status"
      >
        <p class="text-xs leading-relaxed text-muted">
          字幕已重新生成，原译文已失效，需要重新翻译。
        </p>
        <button
          type="button"
          class="shrink-0 text-xs text-faint transition-colors hover:text-foreground"
          aria-label="关闭译文失效提示"
          @click="invalidatedClosed = true"
        >
          关闭
        </button>
      </div>

      <div
        v-if="showError"
        class="flex items-start justify-between gap-3 rounded-lg border border-border bg-mist/60 px-3 py-2"
        role="alert"
      >
        <p class="text-xs leading-relaxed text-muted">{{ errorText }}</p>
        <button
          type="button"
          class="shrink-0 text-xs text-faint transition-colors hover:text-foreground"
          aria-label="关闭错误提示"
          @click="errorClosed = true"
        >
          关闭
        </button>
      </div>
    </div>

    <div
      class="overlay-scrollbar relative min-h-0 flex-1 overflow-y-auto overscroll-contain px-2 pb-40 pt-2"
    >
      <!-- 首次生成中：骨架。真实后端只给完整产物，拿不到半成品字幕，所以不逐条放出 -->
      <ul v-if="generating" class="flex flex-col gap-1 px-1 pt-1" aria-hidden="true">
        <li v-for="n in 5" :key="n" class="flex items-start gap-3 px-2 py-2">
          <span class="h-3.5 w-11 shrink-0 animate-pulse rounded bg-mist motion-reduce:animate-none"></span>
          <span
            class="h-3.5 animate-pulse rounded bg-mist motion-reduce:animate-none"
            :class="n % 2 === 0 ? 'w-2/3' : 'w-11/12'"
          ></span>
        </li>
      </ul>

      <p v-else-if="cues.length === 0" class="px-3 py-10 text-center text-sm text-faint">
        这条任务还没有可用字幕
      </p>
      <p v-else-if="filtered.length === 0" class="px-3 py-10 text-center text-sm text-faint">
        没有匹配「{{ query }}」的字幕
      </p>

      <!--
        字幕行不是控件：点一下只是选中高亮，没有别的动作，所以不给 role="button"
        （那会让内嵌的修改按钮变成非法嵌套）。真正可键盘操作的是行尾的修改按钮。
      -->
      <ul v-if="!generating && filtered.length > 0" class="flex flex-col">
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
            <!-- 译文比原文小一号、浅一档，两条挨着读仍然分得清主次 -->
            <p v-if="cue.translatedText" class="mt-1 text-[13px] leading-relaxed text-muted">
              {{ cue.translatedText }}
            </p>
          </div>

          <!-- 编辑入口占位：后端还没有改单条字幕的接口，先禁用；运行中直接收起 -->
          <button
            v-if="!isBusy"
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
