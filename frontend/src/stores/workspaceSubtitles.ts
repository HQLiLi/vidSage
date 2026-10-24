import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchArtifactJson } from '@/api/artifacts'
import { artifactFileUrl } from '@/utils/artifacts'
import { buildCues } from '@/utils/transcript'
import type {
  ArtifactResponse,
  SubtitleContext,
  SubtitleCue,
  SubtitleTrack,
  TranslatedTrack,
} from '@/types/subtitle'

/**
 * 工作区的字幕域：清洗后的字幕、上下文与译文。
 *
 * 这里只管「拿到并持有字幕内容」；任务快照、产物清单与页面状态在
 * `stores/workspace.ts`，那边选好清洗产物后把仓库交给 `load()`。
 */
export const useWorkspaceSubtitlesStore = defineStore('workspaceSubtitles', () => {
  /** 清洗后字幕的段落内容。 */
  const cleanedTrack = ref<SubtitleTrack | null>(null)
  /** 字幕上下文（主题、源语种、术语）。 */
  const context = ref<SubtitleContext | null>(null)
  /** 译文段落内容；本任务恒为 null（显示译文是后一个任务的事）。 */
  const translatedTrack = ref<TranslatedTrack | null>(null)
  /** 当前译文语种；本任务恒为 null。 */
  const translatedLanguage = ref<string | null>(null)

  /** 加载的代际标记：切任务时靠它把上一次还没回来的请求作废。 */
  let loadToken = 0

  /** 原文与译文配对的结果。 */
  const builtCues = computed(() => buildCues(cleanedTrack.value, translatedTrack.value))

  /** 页面要显示的字幕行。 */
  const cues = computed<SubtitleCue[]>(() => builtCues.value.cues)

  /** 原文与译文对不上（长度不等或时间戳不一致）。 */
  const translationMismatch = computed(() => builtCues.value.mismatch)

  /**
   * 拉一版清洗后字幕的段落与上下文。
   *
   * 返回 null 表示成功或被后来的加载作废；返回字符串表示硬错误文案——
   * 段落是页面主体，拿不到要让调用方换成错误卡。上下文拿不到不算失败，
   * 主题区自己走「未能提取主题」空态。
   */
  async function load(artifact: ArtifactResponse): Promise<string | null> {
    const token = (loadToken += 1)
    const segmentsUrl = artifactFileUrl(artifact, 'segments')
    if (segmentsUrl === null) return '这版字幕产物里没有段落文件'

    const track = await fetchArtifactJson<SubtitleTrack>(segmentsUrl).catch(() => null)
    if (token !== loadToken) return null
    if (track === null) return '读取字幕失败，请稍后重试'
    cleanedTrack.value = track
    // 换了段落就说明上下文也可能变了，先清掉，拉到再写
    context.value = null

    const contextUrl = artifactFileUrl(artifact, 'context')
    if (contextUrl !== null) {
      const loaded = await fetchArtifactJson<SubtitleContext>(contextUrl).catch(() => null)
      if (token !== loadToken) return null
      context.value = loaded
    }
    return null
  }

  /** 所有状态回初值。换任务或离开页面时调用。 */
  function reset(): void {
    loadToken += 1
    cleanedTrack.value = null
    context.value = null
    translatedTrack.value = null
    translatedLanguage.value = null
  }

  return {
    // state
    cleanedTrack,
    context,
    translatedTrack,
    translatedLanguage,
    // computed
    cues,
    translationMismatch,
    // actions
    load,
    reset,
  }
})
