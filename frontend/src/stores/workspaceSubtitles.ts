import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchArtifactJson } from '@/api/artifacts'
import { ApiError } from '@/api/request'
import {
  clearStaleCorrections as clearStaleCorrectionsRequest,
  deleteCorrection as deleteCorrectionRequest,
  listCorrections,
  saveCorrection as saveCorrectionRequest,
} from '@/api/tasks'
import { CORRECTION_FIELD } from '@/enums/subtitle'
import { artifactFileUrl } from '@/utils/artifacts'
import { buildCues, buildSegmentId } from '@/utils/transcript'
import type {
  ArtifactResponse,
  CorrectionField,
  SubtitleContext,
  SubtitleCorrection,
  SubtitleCue,
  SubtitleTrack,
  TranslatedTrack,
} from '@/types/subtitle'

/** 保存一条纠错要交的东西；调用方负责把 value 去掉首尾空白。 */
interface SaveCorrectionInput {
  /** 字幕段 id。 */
  segmentId: string
  /** 改原文还是译文。 */
  field: CorrectionField
  /** 译文纠错的目标语种；原文纠错传空串或不传。 */
  lang?: string
  /** 用户改后的文本。 */
  value: string
}

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
  /** 该任务的全部纠错；只用来数失效条数与判断某条能不能恢复。 */
  const corrections = ref<SubtitleCorrection[]>([])

  /** 加载原文的代际标记：切任务时靠它把上一次还没回来的请求作废。 */
  let loadToken = 0
  /** 加载译文的代际标记，与原文分开——切语种和刷新原文可能同时发生。 */
  let translationToken = 0
  /** 加载纠错列表的代际标记，与上面两个互不影响。 */
  let correctionToken = 0

  /** 原文与译文配对的结果。 */
  const builtCues = computed(() => buildCues(cleanedTrack.value, translatedTrack.value))

  /** 页面要显示的字幕行。 */
  const cues = computed<SubtitleCue[]>(() => builtCues.value.cues)

  /** 原文与译文对不上（长度不等或时间戳不一致）。 */
  const translationMismatch = computed(() => builtCues.value.mismatch)

  /** 当前字幕里已经对不上的纠错条数；0 时顶部不提示。 */
  const staleCorrectionCount = computed(
    () => corrections.value.filter((correction) => !correction.matched).length,
  )

  /** 纠错的存在性索引，键是「项|语种|段 id」；行组件据此决定要不要给恢复入口。 */
  const correctionKeys = computed(
    () =>
      new Set(
        corrections.value.map((correction) =>
          correctionKey(correction.field, correction.lang, correction.segmentId),
        ),
      ),
  )

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

  /**
   * 拉该任务的纠错列表。
   *
   * 纠错是附属信息：拉不到就保留现有清单，不把整页拖进错误卡。
   */
  async function loadCorrections(taskNo: string): Promise<void> {
    const token = (correctionToken += 1)
    const result = await listCorrections(taskNo).catch(() => null)
    if (token !== correctionToken) return
    if (result === null) return
    corrections.value = result.items
  }

  /** 这一条（段 + 项 + 语种）有没有存过纠错——决定编辑态要不要给「恢复」入口。 */
  function hasCorrection(segmentId: string, field: CorrectionField, lang: string): boolean {
    return correctionKeys.value.has(correctionKey(field, lang, segmentId))
  }

  /**
   * 保存一条纠错，成功后把新文本写进内存里的 track，列表里也补上这一条。
   *
   * 返回 null 表示成功，返回字符串表示后端给的失败文案（与 `load()` 同款口径，
   * 不往调用方抛异常）。
   */
  async function saveCorrection(
    taskNo: string,
    input: SaveCorrectionInput,
  ): Promise<string | null> {
    let saved: SubtitleCorrection
    try {
      saved = await saveCorrectionRequest(taskNo, input.segmentId, {
        field: input.field,
        lang: input.lang,
        value: input.value,
      })
    } catch (error) {
      return errorMessageOf(error, '保存失败，请稍后重试')
    }
    applyCorrection(saved)
    return null
  }

  /**
   * 删掉一条纠错。
   *
   * 只发请求、不动内存：删除后要重新拉产物才能拿到机器原文 / 机器译文，
   * 调用方负责接着重拉。
   */
  async function deleteCorrection(
    taskNo: string,
    segmentId: string,
    field: CorrectionField,
    lang: string,
  ): Promise<string | null> {
    try {
      await deleteCorrectionRequest(taskNo, segmentId, field, lang)
      return null
    } catch (error) {
      return errorMessageOf(error, '删除失败，请稍后重试')
    }
  }

  /** 清理失效纠错：成功后本地剔掉 matched=false 的条目，条数与提示一起消失。 */
  async function clearStaleCorrections(taskNo: string): Promise<string | null> {
    try {
      await clearStaleCorrectionsRequest(taskNo)
    } catch (error) {
      return errorMessageOf(error, '清除失败，请稍后重试')
    }
    corrections.value = corrections.value.filter((correction) => correction.matched)
    return null
  }

  /** 把保存结果写进内存：改对应的 track，并把列表里的条目更新（没有就追加到末尾）。 */
  function applyCorrection(saved: SubtitleCorrection): void {
    if (saved.field === CORRECTION_FIELD.TEXT) {
      const track = cleanedTrack.value
      if (track !== null) {
        cleanedTrack.value = {
          ...track,
          segments: track.segments.map((segment) =>
            buildSegmentId(segment) === saved.segmentId
              ? { ...segment, text: saved.value }
              : segment,
          ),
        }
      }
    } else if (translatedLanguage.value?.toLowerCase() === saved.lang.toLowerCase()) {
      const track = translatedTrack.value
      if (track !== null) {
        translatedTrack.value = {
          ...track,
          segments: track.segments.map((segment) =>
            buildSegmentId(segment) === saved.segmentId
              ? { ...segment, translatedText: saved.value }
              : segment,
          ),
        }
      }
    }

    const index = corrections.value.findIndex((item) => sameCorrection(item, saved))
    corrections.value =
      index === -1
        ? [...corrections.value, saved]
        : corrections.value.map((item, itemIndex) => (itemIndex === index ? saved : item))
  }

  /** 所有状态回初值。换任务或离开页面时调用。 */
  function reset(): void {
    loadToken += 1
    translationToken += 1
    correctionToken += 1
    cleanedTrack.value = null
    context.value = null
    translatedTrack.value = null
    translatedLanguage.value = null
    corrections.value = []
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
    staleCorrectionCount,
    // actions
    load,
    loadTranslation,
    setTranslatedLanguage,
    clearTranslation,
    loadCorrections,
    hasCorrection,
    saveCorrection,
    deleteCorrection,
    clearStaleCorrections,
    reset,
  }
})

/** 纠错的比较键：项 + 语种 + 段 id；语种按大小写不敏感比较。 */
function correctionKey(field: CorrectionField, lang: string, segmentId: string): string {
  return `${field}|${lang.toLowerCase()}|${segmentId}`
}

/** 两条纠错说的是不是同一段同一项同一语种。 */
function sameCorrection(left: SubtitleCorrection, right: SubtitleCorrection): boolean {
  return (
    correctionKey(left.field, left.lang, left.segmentId) ===
    correctionKey(right.field, right.lang, right.segmentId)
  )
}

/** 把请求错误收成一句给用户看的话：后端文案优先，其余用兜底文案。 */
function errorMessageOf(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}
