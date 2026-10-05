/**
 * 字幕稿产物的选择与渲染模型构建纯函数。
 *
 * 职责分野：这里只做产物筛选与段落合并的**数据加工**——不发请求（产物 JSON 的
 * HTTP 拉取在 @/api/tasks 的 fetchArtifactJson，与后端通信只进 api 层）、不持状态
 * （响应式状态与副作用在 @/stores/task）。
 */
import type { TaskArtifact, TranscriptionSegment, TranslatedSegment } from '@/types/task'
import type { TranscriptCue } from '@/types/transcript'

/**
 * 原文产物选择：artifacts 中的 segments-*.json（排除译文 -translate-），
 * 有 -clean. 后缀者优先（清洗后是展示口径），无清洗产物时取第一个。
 */
export function pickSourceArtifact(artifacts: TaskArtifact[]): TaskArtifact | null {
  const candidates = artifacts.filter(
    (a) => /^segments-.+\.json$/.test(a.name) && !a.name.includes('-translate-'),
  )
  return candidates.find((a) => a.name.includes('-clean.')) ?? candidates[0] ?? null
}

/** 译文产物选择：segments-*-translate-*.json；尚无翻译产物时为 null。 */
export function pickTranslatedArtifact(artifacts: TaskArtifact[]): TaskArtifact | null {
  return artifacts.find((a) => /^segments-.+-translate-.+\.json$/.test(a.name)) ?? null
}

/**
 * 按下标合并原文与译文段落 → 字幕稿渲染模型。防御性取 min(两边长度)：后端契约保证
 * 逐条下标配对、时间戳一致，长度不等属后端缺陷，不扩散到渲染层。
 * 译文为 null / 与原文相同（无需翻译）的条目视为未翻译，保持单行。
 * id 用「下标-时间戳」复合值：下标是后端配对键（字幕顺序永不变化），时间戳保证可读唯一。
 */
export function buildCues(
  source: TranscriptionSegment[],
  translated: TranslatedSegment[] | null,
): TranscriptCue[] {
  const count = translated ? Math.min(source.length, translated.length) : source.length
  const cues: TranscriptCue[] = []
  for (let i = 0; i < count; i++) {
    const segment = source[i]
    if (!segment) continue
    // translatedText 为 null / 空串 / 与原文相同 → 视为未翻译，不排双语第二行
    const trans = translated?.[i]
    const translatedText =
      trans?.translatedText && trans.translatedText !== trans.sourceText
        ? trans.translatedText
        : undefined
    cues.push({
      id: `${i}-${segment.beginTimeMs}-${segment.endTimeMs}`,
      startMs: segment.beginTimeMs,
      endMs: segment.endTimeMs,
      text: segment.text,
      translatedText,
    })
  }
  return cues
}
