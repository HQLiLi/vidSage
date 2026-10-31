/**
 * 字幕产物相关的后端返回形状与页面用的视图模型。
 *
 * 字段名与后端 Java record 逐字对齐：
 * ArtifactResponse / SubtitleTrack / SubtitleContext / TranslatedTrack / LanguageOption。
 */
import type { ARTIFACT_TYPE, CORRECTION_FIELD } from '@/enums/subtitle'

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
  /**
   * 这句字幕的稳定标识，形如 seg-0-3520。
   * 新产物落盘就带；旧产物磁盘上没有这个字段，而「零纠错」时后端直发原文件，
   * 所以这里写成可缺省，取值一律走 `buildSegmentId`。
   */
  id?: string
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
  /** 与原文同一套规则算出的稳定标识；旧产物里没有这个字段。 */
  id?: string
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
 * index 是后端段落下标——搜索过滤后它仍是这一句的稳定位置。id 是保存纠错时用的锚点。
 */
export interface SubtitleCue {
  /** 这句字幕的稳定标识，形如 seg-0-3520。 */
  id: string
  /** 后端段落下标。 */
  index: number
  /** 起始时间（毫秒）。 */
  startMs: number
  /** 结束时间（毫秒）。 */
  endMs: number
  /** 原文（若改过，这里是纠错后的文本）。 */
  text: string
  /** 译文；没有译文时是 null。 */
  translatedText: string | null
  /** 当前语种译文自带的源文本；没挂译文时是 null。 */
  sourceText: string | null
  /** 译文拿的是旧原文翻的：原文改过而译文还没更新。 */
  translationStale: boolean
}

/** 一条纠错改的是哪一项。 */
export type CorrectionField = (typeof CORRECTION_FIELD)[keyof typeof CORRECTION_FIELD]

/**
 * 一条字幕纠错。字段名与后端 SubtitleCorrectionResponse 逐字对齐。
 *
 * 它只用来数失效条数和判断某条有没有存过纠错；页面上的文本以后端套过纠错的产物为准。
 */
export interface SubtitleCorrection {
  /** 字幕段 id，形如 seg-0-3520。 */
  segmentId: string
  /** 改的是原文还是译文。 */
  field: CorrectionField
  /** 译文纠错的目标语种短码；原文纠错是空串。 */
  lang: string
  /** 保存这条纠错时用户看到的源文本：原文纠错是改前的机器原文，译文纠错是当时叠过原文纠错的原文。 */
  sourceText: string
  /** 用户改后的文本。 */
  value: string
  /** 当前字幕里还有没有这一条；false = 字幕重新生成后已失效。 */
  matched: boolean
  /** 保存时依据的源文本是否已与当前文本不同；译文纠错用它提示「译文待复核」。 */
  sourceChanged: boolean
  /** 最近一次保存时间（ISO-8601 字符串）。 */
  updatedAt: string
}

/** 纠错列表响应：外面包一层 items，空列表时是空数组。 */
export interface SubtitleCorrectionListResponse {
  /** 该任务的全部纠错，按首次保存顺序。 */
  items: SubtitleCorrection[]
}

/** 保存一条纠错的请求体。 */
export interface SaveCorrectionRequest {
  /** 改的是原文还是译文。 */
  field: CorrectionField
  /** 译文纠错的目标语种短码；原文纠错不带或传空串。 */
  lang?: string
  /** 用户改后的文本；调用方已去掉首尾空白。 */
  value: string
}

/** 清理失效纠错的结果。 */
export interface ClearStaleCorrectionsResponse {
  /** 实际删掉的条数。 */
  removed: number
}
