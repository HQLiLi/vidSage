<script setup lang="ts">
// 字幕区头部的翻译控件：源 / 目标语种、互换、翻译按钮与翻译进度。
// 纯展示组件：语种、进度与完成态都由父组件算好传进来，这里只抛事件。
import { computed } from 'vue'

import type { LanguageOption } from '@/types/subtitle'

const props = withDefaults(
  defineProps<{
    /** 可选语种。 */
    languages: LanguageOption[]
    /** 当前源语种。 */
    sourceLanguage: string
    /** 当前目标语种。 */
    targetLanguage: string
    /** 是否有一次执行在跑（任何类型）；跑着时控件全禁用。 */
    busy?: boolean
    /** 正在翻译。 */
    translating?: boolean
    /** 翻译进度，0–100。 */
    progress?: number
    /** 当前显示的译文语种；null = 没挂译文。 */
    translatedLanguage?: string | null
  }>(),
  { busy: false, translating: false, progress: 0, translatedLanguage: null },
)

const emit = defineEmits<{
  'update:sourceLanguage': [value: string]
  'update:targetLanguage': [value: string]
  swap: []
  translate: []
}>()

/** 进度收成 0–100 的整数。 */
const progressPercent = computed(() => {
  const value = Number.isFinite(props.progress) ? props.progress : 0
  return Math.round(Math.min(100, Math.max(0, value)))
})

/** 源目标相同：翻不出东西，按钮禁用并说明。 */
const sameLanguage = computed(() => props.sourceLanguage === props.targetLanguage)

/** 目标语种的展示名。 */
const targetLanguageLabel = computed(
  () => props.languages.find((language) => language.code === props.targetLanguage)?.label ?? props.targetLanguage,
)

/** 当前译文语种的展示名。 */
const translatedLanguageLabel = computed(
  () =>
    props.languages.find((language) => language.code === props.translatedLanguage)?.label ??
    props.translatedLanguage,
)

/** 能不能点翻译：没有 run 在跑、两个语种都选了、且不相同。 */
const canTranslate = computed(
  () =>
    !props.busy &&
    !sameLanguage.value &&
    props.sourceLanguage !== '' &&
    props.targetLanguage !== '',
)

/** 读源语种下拉的值。 */
function updateSourceLanguage(event: Event): void {
  if (!(event.target instanceof HTMLSelectElement)) return
  emit('update:sourceLanguage', event.target.value)
}

/** 读目标语种下拉的值。 */
function updateTargetLanguage(event: Event): void {
  if (!(event.target instanceof HTMLSelectElement)) return
  emit('update:targetLanguage', event.target.value)
}
</script>

<template>
  <div class="min-w-0" :aria-busy="busy || undefined">
    <div
      class="grid min-w-0 grid-cols-[minmax(0,1fr)_2.25rem_minmax(0,1fr)] items-center gap-2 sm:grid-cols-[minmax(0,1fr)_2.25rem_minmax(0,1fr)_auto]"
    >
      <label class="min-w-0">
        <span class="sr-only">源语言</span>
        <select
          :value="sourceLanguage"
          :disabled="busy"
          aria-label="源语言"
          class="h-9 w-full min-w-0 rounded-lg border border-border bg-background px-2.5 text-sm text-ink outline-none transition-colors focus:border-foreground/40 disabled:cursor-not-allowed disabled:bg-mist disabled:text-faint"
          @change="updateSourceLanguage"
        >
          <option v-for="language in languages" :key="language.code" :value="language.code">
            {{ language.label }}
          </option>
        </select>
      </label>

      <button
        type="button"
        title="互换源语言与目标语言"
        aria-label="互换源语言与目标语言"
        class="flex h-9 w-9 items-center justify-center rounded-lg border border-border bg-background text-muted transition-colors hover:bg-mist hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="busy"
        @click="emit('swap')"
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
          <path d="M7 7h11m-3-3 3 3-3 3M17 17H6m3-3-3 3 3 3" />
        </svg>
      </button>

      <label class="min-w-0">
        <span class="sr-only">目标语言</span>
        <select
          :value="targetLanguage"
          :disabled="busy"
          aria-label="目标语言"
          class="h-9 w-full min-w-0 rounded-lg border border-border bg-background px-2.5 text-sm text-ink outline-none transition-colors focus:border-foreground/40 disabled:cursor-not-allowed disabled:bg-mist disabled:text-faint"
          @change="updateTargetLanguage"
        >
          <option v-for="language in languages" :key="language.code" :value="language.code">
            {{ language.label }}
          </option>
        </select>
      </label>

      <button
        type="button"
        class="col-span-3 h-9 shrink-0 rounded-lg bg-primary px-3 text-sm font-medium text-white transition-colors hover:bg-primary-hover disabled:cursor-not-allowed disabled:bg-border disabled:text-faint sm:col-span-1"
        :disabled="!canTranslate"
        :title="
          sameLanguage
            ? '源语言与目标语言相同，请选择其他目标语种'
            : busy
              ? '正在处理，完成后再翻译'
              : '开始翻译'
        "
        @click="emit('translate')"
      >
        {{ translating ? `${progressPercent}%` : '翻译' }}
      </button>
    </div>

    <div
      v-if="translating"
      class="mt-2 h-1 overflow-hidden rounded-full bg-mist"
      role="progressbar"
      aria-label="翻译进度"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuenow="progressPercent"
      :aria-valuetext="`正在翻译为${targetLanguageLabel}，${progressPercent}%`"
    >
      <div
        class="h-full rounded-full bg-primary transition-[width] duration-100 ease-linear"
        :style="{ width: `${progressPercent}%` }"
      ></div>
    </div>

    <p
      class="mt-2 text-xs leading-relaxed"
      :class="
        sameLanguage && !translating
          ? 'text-muted'
          : translatedLanguage && !translating
            ? 'text-primary'
            : 'text-faint'
      "
      role="status"
      aria-live="polite"
    >
      <template v-if="translating"> 正在翻译为{{ targetLanguageLabel }}，{{ progressPercent }}% </template>
      <template v-else-if="sameLanguage"> 源语言与目标语言相同，请选择其他目标语种。 </template>
      <template v-else-if="translatedLanguage"> 已显示{{ translatedLanguageLabel }}译文。 </template>
      <template v-else> 译文按条与原文对齐显示。 </template>
    </p>
  </div>
</template>
