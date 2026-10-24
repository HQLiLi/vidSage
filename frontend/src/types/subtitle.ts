/**
 * 字幕产物相关的后端返回形状与页面用的视图模型。
 *
 * 字段名与后端 Java record 逐字对齐：
 * ArtifactResponse / SubtitleTrack / SubtitleContext / TranslatedTrack / LanguageOption。
 */
import type { ARTIFACT_TYPE } from '@/enums/subtitle'

/** 产物类型。 */
export type ArtifactType = (typeof ARTIFACT_TYPE)[keyof typeof ARTIFACT_TYPE]

/** 产物里的一个文件：下载地址与当前字节数。 */
export interface ArtifactFileResponse {
  /** 下载地址，相对本站的全路径（如 /api/tasks/...）。 */
  url: string
  /** 文件当前字节数。 */
  sizeBytes: number
}

/**
 * 一版产物。files 的键是短键：segments / subtitles / context / terms，
 * 不带文件后缀；具体有哪些键取决于产物类型。
 */
export interface ArtifactResponse {
  /** 产物类型。 */
  type: ArtifactType
  /** 译文语种；非译文产物这里是空串，不是 null。 */
  lang: string
  /** 版本号，每次重新生成字幕会加一。 */
  revision: number
  /** 生成这版时用的关键词指纹；只有清洗后字幕有值，其余是 null。 */
  keywordsFingerprint: string | null
  /** 这版产物里的文件。 */
  files: Record<string, ArtifactFileResponse>
}

/** 一条字幕段落。时间戳是毫秒。 */
export interface TranscriptionSegment {
  /** 起始时间（毫秒）。 */
  beginTimeMs: number
  /** 结束时间（毫秒）。 */
  endTimeMs: number
  /** 字幕文本。 */
  text: string
}

/** 一份字幕段落文件的内容。是个信封，不是裸数组。 */
export interface SubtitleTrack {
  /** 字幕语种；识别不出来时是 null。 */
  language: string | null
  /** 字幕段落。 */
  segments: TranscriptionSegment[]
  /** 生成这版时用的关键词指纹。 */
  keywordsFingerprint: string | null
}

/** 一条术语修正记录。 */
export interface SubtitleTerm {
  /** 识别出来的写法。 */
  asr: string
  /** 修正后的写法。 */
  fixed: string
  /** 修正理由。 */
  note: string
}

/** 字幕上下文：主题、源语种、术语表。 */
export interface SubtitleContext {
  /** 一两句话的主题；提取失败时是空串。 */
  theme: string
  /** 源语种；由模型写出，不保证是白名单里的码。 */
  sourceLanguage: string
  /** 术语表。 */
  terms: SubtitleTerm[]
  /** 提取失败时为 true，落盘留痕用。 */
  degraded: boolean
}

/** 一条译文段落。时间戳与原文逐条一致。 */
export interface TranslatedSegment {
  /** 起始时间（毫秒）。 */
  beginTimeMs: number
  /** 结束时间（毫秒）。 */
  endTimeMs: number
  /** 原文；可能是 null。 */
  sourceText: string | null
  /** 译文；可能是 null。源目标语种相同时与原文相同。 */
  translatedText: string | null
}

/** 一份译文段落文件的内容。 */
export interface TranslatedTrack {
  /** 源语种。 */
  sourceLanguage: string
  /** 目标语种。 */
  targetLanguage: string
  /** 源目标语种相同而跳过翻译时为 true。 */
  skipped: boolean
  /** 译文段落。 */
  segments: TranslatedSegment[]
}

/** 一个可选语种。 */
export interface LanguageOption {
  /** 语种码，如 zh-Hans / en。 */
  code: string
  /** 展示名，如 简体中文。 */
  label: string
}

/** 关键词清单与其指纹。 */
export interface SubtitleKeywordsResponse {
  /** 关键词，按用户填写顺序。 */
  keywords: string[]
  /** 指纹，后端单源算的。 */
  fingerprint: string
}

/**
 * 页面上一行字幕的视图模型。
 *
 * index 是后端段落下标——它是原文与译文的配对键，也是本地改写覆盖表的键，
 * 不是 v-for 的渲染位置。
 */
export interface SubtitleCue {
  /** 后端段落下标。 */
  index: number
  /** 起始时间（毫秒）。 */
  startMs: number
  /** 结束时间（毫秒）。 */
  endMs: number
  /** 原文。 */
  text: string
  /** 译文；没有译文时是 null。 */
  translatedText: string | null
}
