import { computed, onScopeDispose, ref, toValue } from 'vue'
import type { MaybeRefOrGetter } from 'vue'
import type { TranscriptCue } from '@/types/transcript'

/**
 * 字幕联动播放器：媒体元素（video/audio）是唯一时间源，
 * rAF 循环让 currentTime 的更新频率高于 timeupdate 事件，波形进度才平滑。
 *
 * 时间口径：本 composable 一律用**秒**（媒体元素原生单位）；TranscriptCue 的
 * startMs / endMs 是毫秒，activeIndex 内部换算，调用方 seek 时传秒。
 * 时长格式化用 `@/utils/format` 的 formatTime（时长口径纯函数），不在本文件重复定义。
 *
 * @param cues 字幕数组，支持 ref / getter / 普通数组——字幕由 store 异步产出后注入，
 *             ref 形态保证 activeIndex 随字幕到达而更新
 */
export function useTranscriptPlayer(cues: MaybeRefOrGetter<readonly TranscriptCue[]>) {
  const currentTime = ref(0)
  const duration = ref(0)
  const playing = ref(false)

  let media: HTMLMediaElement | null = null
  let rafId = 0

  /** rAF 帧回调：以媒体元素为准刷新 currentTime（timeupdate 粒度太粗，波形会跳帧）。 */
  function tick(): void {
    if (media) currentTime.value = media.currentTime
    rafId = requestAnimationFrame(tick)
  }

  /** 停止 rAF 循环（幂等，未运行时空操作）。 */
  function stopTick(): void {
    cancelAnimationFrame(rafId)
    rafId = 0
  }

  /** timeupdate / durationchange 时的低频同步：刷 currentTime，时长有效才刷新 duration。 */
  const onTime = (): void => {
    if (media) {
      currentTime.value = media.currentTime
      if (Number.isFinite(media.duration)) duration.value = media.duration
    }
  }

  /** 播放开始：起 rAF 循环。 */
  const onPlay = (): void => {
    playing.value = true
    stopTick()
    rafId = requestAnimationFrame(tick)
  }

  /** 暂停 / 播放结束：停循环并做最后一次时间同步。 */
  const onPause = (): void => {
    playing.value = false
    stopTick()
    onTime()
  }

  /** 绑定媒体元素（已有绑定先解绑），挂时间/播放态监听并同步一次初始值。 */
  function attach(el: HTMLMediaElement): void {
    detach()
    media = el
    el.addEventListener('timeupdate', onTime)
    el.addEventListener('durationchange', onTime)
    el.addEventListener('play', onPlay)
    el.addEventListener('pause', onPause)
    el.addEventListener('ended', onPause)
    onTime()
  }

  /** 解绑媒体元素：摘掉监听、停循环、复位播放态（未绑定时空操作）。 */
  function detach(): void {
    if (!media) return
    removeListeners(media)
    media = null
    stopTick()
    playing.value = false
  }

  /** 摘除 attach 时挂的全部事件监听。 */
  function removeListeners(el: HTMLMediaElement): void {
    el.removeEventListener('timeupdate', onTime)
    el.removeEventListener('durationchange', onTime)
    el.removeEventListener('play', onPlay)
    el.removeEventListener('pause', onPause)
    el.removeEventListener('ended', onPause)
  }

  /**
   * 跳转到指定秒数：钳制到 [0, duration]（时长未知时不设上限），并同步 currentTime；
   * 未绑定媒体元素时不动作。
   */
  function seek(t: number): void {
    if (!media) return
    media.currentTime = Math.min(Math.max(t, 0), duration.value || t)
    currentTime.value = media.currentTime
  }

  /** 播放 / 暂停切换；未绑定媒体元素时不动作。 */
  function toggle(): void {
    if (!media) return
    if (media.paused) void media.play()
    else media.pause()
  }

  /** 当前句下标：currentTime 换算毫秒后按 [startMs, endMs) 匹配；句间空隙/未开始为 -1。 */
  const activeIndex = computed(() => {
    const nowMs = currentTime.value * 1000
    return toValue(cues).findIndex((cue) => nowMs >= cue.startMs && nowMs < cue.endMs)
  })

  onScopeDispose(() => {
    stopTick()
    detach()
  })

  return { currentTime, duration, playing, attach, detach, seek, toggle, activeIndex }
}

/** useTranscriptPlayer 的返回类型：媒体面板 / 字幕稿以它引用播放器（不就地标注，避免自引用）。 */
export type TranscriptPlayer = ReturnType<typeof useTranscriptPlayer>
