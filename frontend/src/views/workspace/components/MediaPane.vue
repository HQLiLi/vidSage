<script setup lang="ts">
// 左列第一块：媒体预览位，能播就放播放器。
// 播放地址不走 axios（<video> 只认 src），所以地址由 api/media.ts 拼成全路径。
// 媒体可能在任务跑完前还没产出：先出「正在加载」，失败就退回占位，等转成就绪再重挂一次，
// 这样运行中打开页面不会一直停在占位上，也不必让用户手动刷新。
// 视频用绝对定位铺满容器：竖屏 / 超宽素材只能被 object-contain 收进黑框，撑不动 16:9 的区块。
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { mediaSourceUrl } from '@/api/media'
import { WORKSPACE_PHASE } from '@/enums/workspace'
import { useWorkspaceStore } from '@/stores/workspace'

const store = useWorkspaceStore()
const { taskNo, phase } = storeToRefs(store)

/** 元数据已加载：播放器可以露出来了。 */
const loaded = ref(false)
/** 播放器加载失败（媒体还没产出、容器浏览器解不了等）：退回占位。 */
const failed = ref(false)
/** 重挂计数：递增即换 <video> 的 key，让它重新请求一次媒体。 */
const loadAttempt = ref(0)

/** 占位标题：运行中媒体还没产出时说的是「准备中」，别说成「不可用」。 */
const placeholderTitle = computed(() => {
  if (failed.value && phase.value === WORKSPACE_PHASE.RUNNING) return '媒体准备中…'
  return failed.value ? '媒体预览暂不可用' : '正在加载媒体…'
})

/** 占位副文案：运行中说的是「还在解析」，别把「还没产出」说成「没有媒体」。 */
const placeholderHint = computed(() => {
  if (phase.value === WORKSPACE_PHASE.RUNNING) return '任务解析中，媒体准备好后会自动出现'
  if (failed.value) return '该任务暂无可播放的媒体，字幕仍可正常查看'
  return ''
})

/** 元数据到手，收起加载态。 */
function handleLoaded(): void {
  loaded.value = true
}

/** 加载失败立刻退占位，别把浏览器的破图留在页面上。 */
function handleError(): void {
  failed.value = true
}

/** 重新请求媒体：先把状态退回「加载中」，再递增 key 换掉 <video>。 */
function reloadVideo(): void {
  loaded.value = false
  failed.value = false
  loadAttempt.value += 1
}

// 切任务：上一个任务的失败态不能带过来。
// 同一任务转成就绪：只有之前加载失败过才重挂——运行中媒体可能还没下完，这时再给一次机会；
// 已经在播的不动它，否则用户正看着的时候 run 一收口，视频会跳回 0 秒。
watch([taskNo, phase], ([nextTaskNo, nextPhase], [previousTaskNo]) => {
  if (nextTaskNo !== previousTaskNo) {
    reloadVideo()
    return
  }
  if (nextPhase === WORKSPACE_PHASE.READY && failed.value) {
    reloadVideo()
  }
})
</script>

<template>
  <section class="relative aspect-video w-full shrink-0 overflow-hidden" aria-label="媒体预览">
    <video
      v-if="!failed && taskNo"
      :key="loadAttempt"
      :src="mediaSourceUrl(taskNo)"
      class="absolute inset-0 h-full w-full bg-black object-contain"
      controls
      preload="metadata"
      aria-label="源媒体播放器"
      @loadedmetadata="handleLoaded"
      @error="handleError"
    ></video>

    <!-- 加载中与失败共用一个占位层：视频铺在底下，元数据到手就把它盖住 -->
    <div
      v-if="failed || !loaded"
      class="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-primary px-6 text-center"
    >
      <svg
        viewBox="0 0 24 24"
        class="h-8 w-8 text-white/70"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <rect x="2" y="4" width="20" height="16" rx="2" />
        <path d="m10 9 5 3-5 3V9Z" />
      </svg>
      <p class="text-sm font-medium text-white">{{ placeholderTitle }}</p>
      <p v-if="placeholderHint" class="max-w-sm text-xs leading-relaxed text-white/60">
        {{ placeholderHint }}
      </p>
    </div>
  </section>
</template>
