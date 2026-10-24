<script setup lang="ts">
// 左列第二块：关键词区。
// 增删只改本页的清单，不发请求——「重新生成」时才会把整份清单提交上去。
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'

import {
  MAX_KEYWORD_LENGTH,
  MAX_KEYWORDS,
  useWorkspaceKeywordsStore,
} from '@/stores/workspaceKeywords'
import type { KeywordAddResult } from '@/stores/workspaceKeywords'

const store = useWorkspaceKeywordsStore()
const { keywords } = storeToRefs(store)
const { addKeyword, removeKeyword } = store

/** 输入框里的待添加内容。 */
const draft = ref('')
/** 加不进去时给的一句提示。 */
const feedback = ref('')

const canAdd = computed(() => draft.value.trim().length > 0 && keywords.value.length < MAX_KEYWORDS)

/** 每种失败原因对应的提示文案。 */
const FEEDBACK: Record<Exclude<KeywordAddResult, 'added' | 'empty'>, string> = {
  'too-long': `单条关键词最多 ${MAX_KEYWORD_LENGTH} 字。`,
  'too-many': `最多添加 ${MAX_KEYWORDS} 条关键词。`,
  duplicate: '这个关键词已经在清单里了。',
}

/** 提交输入框里的关键词。 */
function submit(): void {
  const result = addKeyword(draft.value)
  if (result === 'added') {
    draft.value = ''
    feedback.value = ''
    return
  }
  if (result === 'empty') return
  feedback.value = FEEDBACK[result]
}

/** 回车添加：中文输入法选词时的那次回车不算，别把词拆开加进去。 */
function onEnter(event: KeyboardEvent): void {
  if (event.isComposing) return
  event.preventDefault()
  submit()
}
</script>

<template>
  <section class="flex shrink-0 flex-col">
    <header class="flex shrink-0 items-center justify-between gap-3 px-4 py-3 sm:px-6">
      <div class="flex min-w-0 items-baseline gap-2">
        <h2 class="text-sm font-semibold">关键词</h2>
        <span class="text-xs tabular-nums text-faint"
          >{{ keywords.length }}/{{ MAX_KEYWORDS }}</span
        >
      </div>
      <!-- 「重新生成」按钮的位置：提交重新生成是下一步的事，这里先留空 -->
    </header>

    <div class="shrink-0 px-4 pb-2.5 sm:px-6">
      <div class="flex items-center gap-2">
        <input
          v-model="draft"
          type="text"
          :maxlength="MAX_KEYWORD_LENGTH"
          aria-label="输入关键词"
          placeholder="输入关键词，按回车添加"
          class="min-w-0 flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none transition-colors placeholder:text-faint focus:border-foreground/40"
          @keydown.enter="onEnter"
        />
        <button
          type="button"
          class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary text-white transition-colors hover:bg-primary-hover disabled:cursor-not-allowed disabled:bg-border disabled:text-faint"
          :disabled="!canAdd"
          title="添加关键词"
          aria-label="添加关键词"
          @click="submit"
        >
          <svg
            viewBox="0 0 24 24"
            class="h-4 w-4"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            aria-hidden="true"
          >
            <path d="M12 5v14M5 12h14" />
          </svg>
        </button>
      </div>
      <div class="mt-1.5 flex min-h-4 flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span class="text-xs text-faint">
          回车或点 + 添加，单条最多 {{ MAX_KEYWORD_LENGTH }} 字
        </span>
        <span class="ml-auto text-xs tabular-nums text-faint">
          已输入 {{ draft.length }}/{{ MAX_KEYWORD_LENGTH }} 字
        </span>
      </div>

      <p v-if="feedback" class="mt-1.5 text-xs leading-relaxed text-muted" role="status">
        {{ feedback }}
      </p>
    </div>

    <div class="overlay-scrollbar max-h-60 overflow-y-auto px-4 pb-3 pt-2.5 sm:px-6">
      <p v-if="keywords.length === 0" class="py-2 text-sm text-faint">
        还没有关键词，输入后按回车添加
      </p>
      <ul v-else class="flex flex-col divide-y divide-border/70" aria-label="已有关键词">
        <li
          v-for="(keyword, index) in keywords"
          :key="`${keyword}-${index}`"
          class="flex min-w-0 items-start gap-3 py-2.5 text-sm text-ink"
        >
          <span class="min-w-0 flex-1 wrap-break-word leading-relaxed">{{ keyword }}</span>
          <button
            type="button"
            class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-faint transition-colors hover:bg-mist hover:text-foreground"
            :aria-label="`删除关键词 ${keyword}`"
            @click="removeKeyword(keyword)"
          >
            <svg
              viewBox="0 0 24 24"
              class="h-3 w-3"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              aria-hidden="true"
            >
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>
