/**
 * 字幕行的构建（纯函数，不发请求、不持状态）。
 *
 * 原文来自清洗后字幕的 segments，译文来自译文产物的 segments；两边按下标配对——
 * 后端保证逐条时间戳一致，对不上就整份译文都不挂，免得用户看到错位的双语。
 */
import type { SubtitleCue, SubtitleTrack, TranslatedTrack } from '@/types/subtitle'

/** buildCues 的结果：字幕行，外加一个「原文与译文对不上」的标记。 */
export interface BuildCuesResult {
  /** 字幕行。 */
  cues: SubtitleCue[]
  /** 原文与译文对不上（长度不等或时间戳不一致）。 */
  mismatch: boolean
}

/**
 * 把原文与译文合成页面要显示的字幕行。
 *
 * - 没有原文时返回空数组，空字幕是合法产出（纯音乐），不算错。
 * - 译文与原文长度不等、或任一配对的时间戳不一致时，mismatch 为 true 且整份译文不挂。
 * - 译文为空或与原文相同（源目标语种相同时后端会这样），视为没翻译。
 */
export function buildCues(
  cleaned: SubtitleTrack | null,
  translated: TranslatedTrack | null,
): BuildCuesResult {
  const segments = cleaned?.segments ?? []
  if (segments.length === 0) return { cues: [], mismatch: false }

  const translatedSegments = translated?.segments ?? null
  if (translatedSegments === null) {
    return { cues: segments.map((segment, index) => toCue(segment, index, null)), mismatch: false }
  }

  const aligned =
    translatedSegments.length === segments.length &&
    segments.every((segment, index) => {
      const other = translatedSegments[index]
      return other !== undefined && other.beginTimeMs === segment.beginTimeMs && other.endTimeMs === segment.endTimeMs
    })

  if (!aligned) {
    return { cues: segments.map((segment, index) => toCue(segment, index, null)), mismatch: true }
  }

  const cues = segments.map((segment, index) => {
    const other = translatedSegments[index]
    const text = other?.translatedText
    const translatedText = text && text !== other?.sourceText ? text : null
    return toCue(segment, index, translatedText)
  })
  return { cues, mismatch: false }
}

/** 一条原文段落转成字幕行。index 是后端段落下标，也是译文配对的键。 */
function toCue(
  segment: { beginTimeMs: number; endTimeMs: number; text: string },
  index: number,
  translatedText: string | null,
): SubtitleCue {
  return {
    index,
    startMs: segment.beginTimeMs,
    endMs: segment.endTimeMs,
    text: segment.text,
    translatedText,
  }
}
