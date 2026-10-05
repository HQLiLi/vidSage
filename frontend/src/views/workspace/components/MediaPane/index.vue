<script setup lang="ts">
// 工作区媒体面板（页面私有组件）：视频黑场舞台 / 音频封面卡片双形态（design §3.3）。
// 形态判定：UPLOAD 且 sourceUri 扩展名命中音频白名单 → 音频卡；其余（含全部 URL 任务）→ 视频。
// 播放源统一是产物端点的 source.mp4（D2）；联动经 shell 注入的 useTranscriptPlayer 单例。
import { computed, useTemplateRef, watch } from 'vue'

import { formatTime } from '@/utils/format'
import AudioWaveform from '@/views/workspace/components/MediaPane/components/AudioWaveform.vue'
import type { TranscriptPlayer } from '@/composables/useTranscriptPlayer'
import type { TaskInputType } from '@/types/task'

const props = defineProps<{
  /** 对外任务号：拼接产物端点的媒体地址。 */
  taskNo: string
  /** 任务输入类型（URL / UPLOAD）：音频形态只在 UPLOAD 且扩展名命中时启用。 */
  inputType: TaskInputType
  /** 来源（URL 原文或上传文件原始名）：音频卡标题与形态判定依据。 */
  sourceUri: string
  /** shell 创建的字幕联动播放器：本面板负责把媒体元素 attach 上去。 */
  player: TranscriptPlayer
}>()

// player 实例整个页面生命周期内稳定，ref / 方法一次性解构不丢失响应性
const { currentTime, duration, playing, seek, toggle } = props.player

/** 音频扩展名白名单（design §3.3），统一小写比较。 */
const AUDIO_EXTENSIONS = new Set(['mp3', 'm4a', 'wav', 'aac', 'flac', 'ogg', 'opus'])

/** 是否按音频卡片渲染：UPLOAD 且 sourceUri 扩展名命中白名单；其余一律视频。 */
const isAudio = computed(() => {
  if (props.inputType !== 'UPLOAD') return false
  const dotIndex = props.sourceUri.lastIndexOf('.')
  if (dotIndex < 0) return false
  return AUDIO_EXTENSIONS.has(props.sourceUri.slice(dotIndex + 1).toLowerCase())
})

/** 媒体地址：产物端点的 source.mp4（上传与 URL 任务落盘同名，design §3.3）。 */
const mediaSrc = computed(() => `/api/tasks/${props.taskNo}/artifacts/source.mp4`)

/** 媒体元素模板引用：双形态 v-if 只存在其一，换绑（切换 / 卸载）时由 watch 同步播放器。 */
const mediaEl = useTemplateRef<HTMLMediaElement>('mediaEl')

// 元素换绑时先 detach 旧元素再 attach 新元素，避免监听残留（原型验证的绑定口径）
watch(mediaEl, (el, old) => {
  if (old) props.player.detach()
  if (el) props.player.attach(el)
})

/** 播放进度比例（0–1）：驱动波形点亮；时长未知（metadata 未就绪）时为 0。 */
const waveProgress = computed(() => (duration.value ? currentTime.value / duration.value : 0))

/** 波形点击 seek：把组件回抛的横轴比例折算为秒（媒体口径）；时长未知时不动作。 */
function onWaveSeek(ratio: number): void {
  if (duration.value) seek(ratio * duration.value)
}
</script>

<template>
  <div class="flex h-full min-h-0 w-full flex-col p-4 sm:p-6">
    <!-- 音频形态：封面 + 可点击波形 + 播控条 -->
    <div v-if="isAudio" class="flex flex-1 items-center justify-center">
      <div class="w-full max-w-lg rounded-2xl border border-border bg-background p-6 shadow-sm">
        <div class="flex items-center gap-4">
          <div class="flex h-16 w-16 shrink-0 items-center justify-center rounded-xl bg-sage-900">
            <svg
              viewBox="0 0 24 24"
              class="h-7 w-7 stroke-sage-100"
              fill="none"
              stroke-width="1.6"
              stroke-linecap="round"
              aria-hidden="true"
            >
              <path d="M4 10v4M8 7v10M12 4v16M16 7v10M20 10v4" />
            </svg>
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ sourceUri }}</p>
            <p class="mt-0.5 text-xs text-muted">音频 · {{ formatTime(duration) }}</p>
          </div>
        </div>

        <AudioWaveform class="mt-5 h-16 w-full" :progress="waveProgress" @seek="onWaveSeek" />

        <div class="mt-3 flex items-center gap-3">
          <button
            type="button"
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary text-background transition-colors hover:bg-primary-hover"
            :title="playing ? '暂停' : '播放'"
            @click="toggle()"
          >
            <svg
              v-if="!playing"
              viewBox="0 0 24 24"
              class="ml-0.5 h-4 w-4"
              fill="currentColor"
              aria-hidden="true"
            >
              <path d="M8 5.5v13l11-6.5z" />
            </svg>
            <svg v-else viewBox="0 0 24 24" class="h-4 w-4" fill="currentColor" aria-hidden="true">
              <path d="M7 5h3.4v14H7zM13.6 5H17v14h-3.4z" />
            </svg>
          </button>
          <span class="text-xs tabular-nums text-muted">
            {{ formatTime(currentTime) }} / {{ formatTime(duration) }}
          </span>
        </div>

        <audio ref="mediaEl" :src="mediaSrc" preload="metadata" class="hidden" />
      </div>
    </div>

    <!-- 视频形态：黑场舞台 -->
    <div v-else class="min-h-0 flex-1 overflow-hidden rounded-xl border border-border bg-primary">
      <video ref="mediaEl" :src="mediaSrc" controls class="h-full w-full object-contain"></video>
    </div>
  </div>
</template>
