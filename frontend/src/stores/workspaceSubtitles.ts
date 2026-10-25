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
  /** 当前显示的译文段落；null = 没有挂译文。 */
  const translatedTrack = ref<TranslatedTrack | null>(null)
  /** 当前显示的译文语种；null = 没有挂译文。 */
  const translatedLanguage = ref<string | null>(null)

  /** 加载原文的代际标记：切任务时靠它把上一次还没回来的请求作废。 */
  let loadToken = 0
  /** 加载译文的代际标记，与原文分开——切语种和刷新原文可能同时发生。 */
  let translationToken = 0

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

  /**
   * 拉一版译文段落挂上。
   *
   * 产物不存在或语种为空就清掉当前译文——宁可让双语行消失，也不能把上一版译文
   * 挂到新的原文上（时间轴可能已经变了）。
   */
  async function loadTranslation(
    artifact: ArtifactResponse | null,
    language: string | null,
  ): Promise<void> {
    const token = (translationToken += 1)
    const url =
      artifact === null || language === null ? null : artifactFileUrl(artifact, 'segments')
    if (url === null) {
      translatedTrack.value = null
      translatedLanguage.value = null
      return
    }

    const track = await fetchArtifactJson<TranslatedTrack>(url).catch(() => null)
    if (token !== translationToken) return
    if (track === null) {
      translatedTrack.value = null
      translatedLanguage.value = null
      return
    }
    translatedTrack.value = track
    translatedLanguage.value = language
  }

  /** 先把要显示的译文语种记下，段落稍后再拉；翻译收口时用。 */
  function setTranslatedLanguage(language: string | null): void {
    translatedLanguage.value = language
  }

  /** 清掉当前译文（切到没翻过的语种、重生成后原译文作废时用）。 */
  function clearTranslation(): void {
    translationToken += 1
    translatedTrack.value = null
    translatedLanguage.value = null
  }

  /** 所有状态回初值。换任务或离开页面时调用。 */
  function reset(): void {
    loadToken += 1
    translationToken += 1
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
    loadTranslation,
    setTranslatedLanguage,
    clearTranslation,
    reset,
  }
})
