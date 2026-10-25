/**
 * 字幕行的构建（纯函数，不发请求、不持状态）。
 *
 * 原文来自清洗后字幕的 segments，译文来自译文产物的 segments；两边先按 id 配对，
 * 旧数据配不上再退回「同下标 + 时间戳一致」。对不上就整份译文都不挂，
 * 免得用户看到错位的双语。
 */
import type {
  SubtitleCue,
  SubtitleTrack,
  TranscriptionSegment,
  TranslatedSegment,
  TranslatedTrack,
} from '@/types/subtitle'

/** 一条段落里用来算 id 的那几个字段；新旧产物都适用。 */
interface SegmentIdentity {
  /** 起始时间（毫秒）。 */
  beginTimeMs: number
  /** 结束时间（毫秒）。 */
  endTimeMs: number
  /** 产物里自带的 id；旧产物没有这个字段。 */
  id?: string
}

/** buildCues 的结果：字幕行，外加一个「原文与译文对不上」的标记。 */
export interface BuildCuesResult {
  /** 字幕行。 */
  cues: SubtitleCue[]
  /** 原文与译文对不上（长度不等或时间戳不一致）。 */
  mismatch: boolean
}

/**
 * 算一条字幕的稳定标识。
 *
 * 新产物落盘就带 id；旧产物磁盘上没有这个字段，而后端「零纠错」时又直发原文件，
 * 所以前端得按同一套规则现算。规则与后端 `TranscriptionSegment.syntheticId` 一致，
 * 两处必须一起改——不然纠错会存到后端找不到的 id 上。
 */
export function buildSegmentId(segment: SegmentIdentity): string {
  const id = segment.id?.trim()
  return id ? id : `seg-${segment.beginTimeMs}-${segment.endTimeMs}`
}

/**
 * 把原文与译文合成页面要显示的字幕行。
 *
 * - 没有原文时返回空数组，空字幕是合法产出（纯音乐），不算错。
 * - 译文与原文长度不等、或配对的时间戳不一致时，mismatch 为 true 且整份译文不挂。
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

  const pairs = pairSegments(segments, translatedSegments)
  if (pairs === null) {
    return { cues: segments.map((segment, index) => toCue(segment, index, null)), mismatch: true }
  }

  const cues = segments.map((segment, index) => toCue(segment, index, pairs[index] ?? null))
  return { cues, mismatch: false }
}

/**
 * 给每条原文找到对应的译文，返回与原文下标对齐的一张表；对不上返回 null。
 *
 * 先按 id 配：id 是纠错的锚点，也是跨重生成稳定的键。旧数据两边都可能没有 id，
 * 但合成规则一致，所以 id 一般也能配上。配不上再退回「同下标 + 时间戳一致」——
 * 那是这次改造前的判据，留着兜底。
 */
function pairSegments(
  segments: TranscriptionSegment[],
  translated: TranslatedSegment[],
): (TranslatedSegment | null)[] | null {
  if (translated.length === segments.length) {
    const byId = new Map<string, TranslatedSegment>()
    let unique = true
    for (const segment of translated) {
      const id = buildSegmentId(segment)
      // 同一份字幕里 id 重复时按 id 配对会命中错的行，退回下标配对
      if (byId.has(id)) {
        unique = false
        break
      }
      byId.set(id, segment)
    }
    if (unique) {
      const pairs = segments.map((segment) => byId.get(buildSegmentId(segment)) ?? null)
      const aligned = pairs.every((pair, index) => {
        const segment = segments[index]
        return (
          segment !== undefined &&
          pair !== null &&
          pair.beginTimeMs === segment.beginTimeMs &&
          pair.endTimeMs === segment.endTimeMs
        )
      })
      if (aligned) return pairs
    }
  }

  const aligned =
    translated.length === segments.length &&
    segments.every((segment, index) => {
      const other = translated[index]
      return (
        other !== undefined &&
        other.beginTimeMs === segment.beginTimeMs &&
        other.endTimeMs === segment.endTimeMs
      )
    })
  return aligned ? segments.map((_, index) => translated[index] ?? null) : null
}

/** 一条原文段落（配一条译文）转成字幕行。 */
function toCue(
  segment: TranscriptionSegment,
  index: number,
  pair: TranslatedSegment | null,
): SubtitleCue {
  const sourceText = pair?.sourceText ?? null
  const translated = pair?.translatedText ?? null
  // 空串与「源目标语种相同时后端抄一份原文」都不算有译文
  const translatedText = translated && translated !== sourceText ? translated : null
  return {
    id: buildSegmentId(segment),
    index,
    startMs: segment.beginTimeMs,
    endMs: segment.endTimeMs,
    text: segment.text,
    translatedText,
    sourceText: translatedText === null ? null : sourceText,
    // 源文本比不了（旧产物没有 sourceText）也按过期算：宁可多提示，不静默当没事
    translationStale: translatedText !== null && sourceText !== segment.text,
  }
}
