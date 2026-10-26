<script setup lang="ts">
// 一条字幕行：原文、译文，以及各自的编辑态。
// 编辑状态机全在本地；保存走 store，成功后 store 改内存里的 track，界面自然跟着变。
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { CORRECTION_FIELD } from '@/enums/subtitle'
import { useWorkspaceStore } from '@/stores/workspace'
import { useWorkspaceSubtitlesStore } from '@/stores/workspaceSubtitles'
import { formatTime } from '@/utils/format'
import type { CorrectionField, SubtitleCue } from '@/types/subtitle'

const props = defineProps<{
  /** 这一行要显示的字幕。 */
  cue: SubtitleCue
  /** 是否被选中高亮。 */
  selected: boolean
  /** 是否有一次执行在跑；跑着时不渲染铅笔。 */
  busy: boolean
  /** 当前显示的译文语种；没有挂译文时是 null。 */
  language: string | null
}>()

const emit = defineEmits<{ select: [index: number] }>()

const subtitles = useWorkspaceSubtitlesStore()
const workspace = useWorkspaceStore()
const { taskNo } = storeToRefs(workspace)

/** 正在编辑哪一项；null = 不在编辑态。 */
const editing = ref<CorrectionField | null>(null)
/** 编辑框里的草稿。 */
const draft = ref('')
/** 这一行正在发请求（保存或恢复），期间不接第二次提交。 */
const saving = ref(false)
/** 行内错误文案；保存失败、恢复失败都走这里。 */
const error = ref<string | null>(null)
/** 编辑框；进入编辑态后手动聚焦。 */
const editor = useTemplateRef<HTMLTextAreaElement>('editor')

/** 这一段有没有原文纠错——决定编辑原文时给不给「恢复原文」。 */
const hasTextCorrection = computed(() =>
  subtitles.hasCorrection(props.cue.id, CORRECTION_FIELD.TEXT, ''),
)

/** 当前语种有没有这条译文纠错。 */
const hasTranslationCorrection = computed(
  () =>
    props.language !== null &&
    subtitles.hasCorrection(props.cue.id, CORRECTION_FIELD.TRANSLATION, props.language),
)

/** 这条译文纠错要不要复核：当初照着写译文的原文后来改过（此时机器译文已经是新的）。 */
const needsReview = computed(
  () =>
    props.language !== null &&
    subtitles.translationNeedsReview(props.cue.id, props.language),
)

/** 编辑态里要不要给恢复入口。 */
const canRevert = computed(() => {
  if (editing.value === CORRECTION_FIELD.TEXT) return hasTextCorrection.value
  if (editing.value === CORRECTION_FIELD.TRANSLATION) return hasTranslationCorrection.value
  return false
})

/** 编辑态里那一项的当前文本（保存成功后 store 改的就是它）。 */
function currentText(field: CorrectionField): string {
  if (field === CORRECTION_FIELD.TEXT) return props.cue.text
  return props.cue.translatedText ?? ''
}

/** 进入编辑态：草稿从当前文本开始，清掉旧错误，把焦点和光标放到末尾。 */
function startEdit(field: CorrectionField): void {
  // 上一次保存还没回来就换一项，两次请求会抢同一行的 saving 状态
  if (saving.value) return
  editing.value = field
  draft.value = currentText(field)
  error.value = null
  void nextTick(() => {
    const element = editor.value
    if (element === null) return
    element.focus()
    element.setSelectionRange(element.value.length, element.value.length)
  })
}

/** 退出编辑态，清掉行内错误。 */
function stopEdit(): void {
  editing.value = null
  saving.value = false
  error.value = null
}

/** 放弃本次编辑，一个字都不写。 */
function cancelEdit(): void {
  if (saving.value) return
  stopEdit()
}

/**
 * 提交草稿：失焦、保存按钮、Ctrl/⌘+Enter 三个入口共用。
 *
 * 文本没变就只退出；内容为空按非法输入处理，不发请求——后端对空值会让人去
 * 「删除纠错」，而那条路不在这一步的用户手里。
 */
async function commitEdit(): Promise<void> {
  const field = editing.value
  if (field === null || saving.value) return

  const value = draft.value.trim()
  if (value === '') {
    draft.value = currentText(field)
    error.value = '内容不能为空'
    return
  }
  if (value === currentText(field).trim()) {
    stopEdit()
    return
  }

  saving.value = true
  const failure = await subtitles.saveCorrection(taskNo.value, {
    segmentId: props.cue.id,
    field,
    lang: field === CORRECTION_FIELD.TRANSLATION ? (props.language ?? '') : '',
    value,
  })
  saving.value = false
  if (failure !== null) {
    // 按口径还原编辑前文本；编辑态留着，改完能直接重试
    if (editing.value === field) {
      draft.value = currentText(field)
      error.value = failure
    }
    return
  }
  if (editing.value === field) stopEdit()
}

/**
 * 恢复机器文本：删掉这一项的纠错，再重拉字幕（机器文本只在后端）。
 * 先收掉编辑态，免得重拉回来的数据把没提交的草稿混进去。
 */
async function revertEdit(): Promise<void> {
  const field = editing.value
  if (field === null || saving.value) return
  if (field === CORRECTION_FIELD.TRANSLATION && props.language === null) return

  const lang = field === CORRECTION_FIELD.TRANSLATION ? (props.language ?? '') : ''
  saving.value = true
  editing.value = null
  error.value = null
  const failure = await workspace.revertCorrection(props.cue.id, field, lang)
  saving.value = false
  if (failure !== null) error.value = failure
}

/** 编辑框里的键盘：Esc 放弃，Ctrl/⌘+Enter 立即保存；输入法选词时不拦。 */
function onEditorKeydown(event: KeyboardEvent): void {
  if (event.isComposing) return
  if (event.key === 'Escape') {
    event.preventDefault()
    cancelEdit()
    return
  }
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    void commitEdit()
  }
}

/** 有新的执行开始（比如点了重新翻译）就放弃编辑：跑完数据会整份换掉。 */
watch(
  () => props.busy,
  (busy) => {
    if (busy) stopEdit()
  },
)
</script>

<template>
  <!--
    字幕行本身不是控件：点一下只是选中高亮，没有别的动作，所以不给 role="button"。
    真正可键盘操作的是行尾的铅笔与编辑框。
  -->
  <li
    :aria-current="selected ? 'true' : undefined"
    class="group relative flex w-full cursor-pointer items-start gap-3 rounded-lg px-3 py-2 text-left transition-colors"
    :class="selected ? 'bg-mist' : 'hover:bg-mist/60'"
    @click="emit('select', cue.index)"
  >
    <span
      v-if="selected"
      class="absolute bottom-2.5 left-0 top-2.5 w-0.5 rounded-full bg-primary"
      aria-hidden="true"
    ></span>
    <span
      class="w-11 shrink-0 pt-0.75 text-[13px] tabular-nums"
      :class="selected ? 'font-medium text-foreground' : 'text-muted'"
    >
      {{ formatTime(cue.startMs / 1000) }}
    </span>

    <div class="min-w-0 flex-1">
      <!-- 原文：编辑态把文字换成 textarea，其余时候是只读文本 + 行尾铅笔 -->
      <div v-if="editing === CORRECTION_FIELD.TEXT" class="relative" @click.stop>
        <textarea
          ref="editor"
          v-model="draft"
          rows="2"
          :readonly="saving"
          :aria-label="`编辑第 ${cue.index + 1} 条字幕的原文`"
          class="w-full resize-y rounded-lg border border-border bg-surface px-2 py-1 text-[15px] leading-relaxed text-ink outline-none transition-colors focus:border-foreground/40 focus:bg-background"
          @blur="commitEdit"
          @keydown="onEditorKeydown"
        ></textarea>
        <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
          <button
            type="button"
            class="rounded bg-primary px-2 py-0.5 text-white transition-colors hover:bg-primary-hover disabled:cursor-not-allowed disabled:bg-border disabled:text-faint"
            :disabled="saving"
            @mousedown.prevent
            @click="commitEdit"
          >
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button
            type="button"
            class="text-faint transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
            :disabled="saving"
            @mousedown.prevent
            @click="cancelEdit"
          >
            取消
          </button>
          <button
            v-if="canRevert"
            type="button"
            class="text-faint transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
            :disabled="saving"
            @mousedown.prevent
            @click="revertEdit"
          >
            恢复原文
          </button>
          <span class="text-faint">Esc 取消 · Ctrl/⌘+Enter 保存</span>
        </div>
      </div>
      <div v-else class="relative pr-7">
        <p
          class="text-[15px] leading-relaxed"
          :class="selected ? 'font-medium text-foreground' : 'text-ink'"
        >
          {{ cue.text }}
        </p>
        <button
          v-if="!busy"
          type="button"
          :title="`修改第 ${cue.index + 1} 条字幕的原文`"
          :aria-label="`修改第 ${cue.index + 1} 条字幕的原文`"
          class="absolute right-0 top-0 flex h-6 w-6 items-center justify-center text-muted opacity-0 transition-[color,opacity] group-hover:opacity-100 hover:text-foreground focus-visible:opacity-100"
          @click.stop="startEdit(CORRECTION_FIELD.TEXT)"
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
      </div>

      <!-- 译文：只在当前语种有译文时出现；铅笔与编辑态跟原文同一套 -->
      <template v-if="cue.translatedText !== null">
        <div v-if="editing === CORRECTION_FIELD.TRANSLATION" class="relative mt-1" @click.stop>
          <textarea
            ref="editor"
            v-model="draft"
            rows="2"
            :readonly="saving"
            :aria-label="`编辑第 ${cue.index + 1} 条字幕的译文`"
            class="w-full resize-y rounded-lg border border-border bg-surface px-2 py-1 text-[13px] leading-relaxed text-ink outline-none transition-colors focus:border-foreground/40 focus:bg-background"
            @blur="commitEdit"
            @keydown="onEditorKeydown"
          ></textarea>
          <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
            <button
              type="button"
              class="rounded bg-primary px-2 py-0.5 text-white transition-colors hover:bg-primary-hover disabled:cursor-not-allowed disabled:bg-border disabled:text-faint"
              :disabled="saving"
              @mousedown.prevent
              @click="commitEdit"
            >
              {{ saving ? '保存中…' : '保存' }}
            </button>
            <button
              type="button"
              class="text-faint transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="saving"
              @mousedown.prevent
              @click="cancelEdit"
            >
              取消
            </button>
            <button
              v-if="canRevert"
              type="button"
              class="text-faint transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="saving"
              @mousedown.prevent
              @click="revertEdit"
            >
              恢复译文
            </button>
            <span class="text-faint">Esc 取消 · Ctrl/⌘+Enter 保存</span>
          </div>
        </div>
        <div v-else class="relative mt-1 pr-7">
          <p class="text-[13px] leading-relaxed text-muted">{{ cue.translatedText }}</p>
          <button
            v-if="!busy"
            type="button"
            :title="`修改第 ${cue.index + 1} 条字幕的译文`"
            :aria-label="`修改第 ${cue.index + 1} 条字幕的译文`"
            class="absolute right-0 top-0 flex h-6 w-6 items-center justify-center text-muted opacity-0 transition-[color,opacity] group-hover:opacity-100 hover:text-foreground focus-visible:opacity-100"
            @click.stop="startEdit(CORRECTION_FIELD.TRANSLATION)"
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
        </div>

        <!--
          两种提示互斥：还没重翻时是「待更新」；重翻之后机器译文已经跟上，
          但手改译文当初照着的是旧原文，改叫「待复核」。
        -->
        <p v-if="cue.translationStale" class="mt-1 text-[11px] leading-relaxed text-faint">
          原文已改，译文待更新
        </p>
        <p v-else-if="needsReview" class="mt-1 text-[11px] leading-relaxed text-faint">
          译文待复核：原文改过，请核对这条手改译文
        </p>
      </template>

      <p v-if="error !== null" class="mt-1 text-[11px] leading-relaxed text-muted" role="alert">
        {{ error }}
      </p>
    </div>
  </li>
</template>
