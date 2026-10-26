<script setup lang="ts">
// 字幕区头部的翻译控件：源 / 目标语种、翻译按钮与翻译进度。
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
    /** 源语种是否锁死：原文语种是从字幕里识别出来的，不能改。 */
    sourceLocked?: boolean
    /** 是否有一次执行在跑（任何类型）；跑着时控件全禁用。 */
    busy?: boolean
    /** 正在翻译。 */
    translating?: boolean
    /** 翻译进度，0–100。 */
    progress?: number
    /** 当前语种有译文过期（原文改过），这次点下去会强制重跑。 */
    retranslate?: boolean
  }>(),
  { sourceLocked: false, busy: false, translating: false, progress: 0, retranslate: false },
)

const emit = defineEmits<{
  'update:sourceLanguage': [value: string]
  'update:targetLanguage': [value: string]
  translate: []
}>()

/** 进度收成 0–100 的整数。 */
const progressPercent = computed(() => {
  const value = Number.isFinite(props.progress) ? props.progress : 0
  return Math.round(Math.min(100, Math.max(0, value)))
})

/** 源目标相同：翻不出东西，按钮禁用并说明。 */
const sameLanguage = computed(() => props.sourceLanguage === props.targetLanguage)

/** 源语种的展示名；锁死后以纯文本显示它。 */
const sourceLanguageLabel = computed(
  () => props.languages.find((language) => language.code === props.sourceLanguage)?.label ?? props.sourceLanguage,
)

/** 目标语种的展示名。 */
const targetLanguageLabel = computed(
  () => props.languages.find((language) => language.code === props.targetLanguage)?.label ?? props.targetLanguage,
)

/** 按钮与 title 的文案：有译文过期时说「重新翻译」，别让人以为这次会跳过。 */
const translateLabel = computed(() => (props.retranslate ? '重新翻译' : '翻译'))

/** 按钮禁用时的说明。 */
const translateTitle = computed(() => {
  if (sameLanguage.value) return '源语言与目标语言相同，请选择其他目标语种'
  if (props.busy) return '正在处理，完成后再翻译'
  return props.retranslate ? '原文有改动，将重新翻译当前语种' : '开始翻译'
})

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
      <div v-if="sourceLocked" class="min-w-0">
        <span class="sr-only">源语言</span>
        <span
          :title="`原文语种由字幕识别得出（${sourceLanguageLabel}），不可修改`"
          class="flex h-9 w-full min-w-0 items-center rounded-lg bg-mist px-2.5 text-sm text-ink"
        >
          <span class="truncate">{{ sourceLanguageLabel }}</span>
        </span>
      </div>
      <label v-else class="min-w-0">
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

      <span class="text-center text-xs text-faint" aria-hidden="true">to</span>

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
        :title="translateTitle"
        @click="emit('translate')"
      >
        {{ translating ? `${progressPercent}%` : translateLabel }}
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
  </div>
</template>
