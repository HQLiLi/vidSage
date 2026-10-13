/**
 * 字幕稿渲染模型（前端视图模型，非后端契约）。
 *
 * `src/types/` 只收**跨模块共享**类型——本条由字幕稿构建产出、播放器与媒体/字幕两面板
 * 共同消费，故入全局层。
 */

/** 一条字幕行：毫秒时间戳 + 原文 + 可选译文（双语双行展示，未翻译条目单行）。 */
export interface TranscriptCue {
  /** 稳定唯一标识，用于 v-for key 与自动滚动定位 */
  id: string
  /** 开始时间（毫秒） */
  startMs: number
  /** 结束时间（毫秒） */
  endMs: number
  /** 原文 */
  text: string
  /** 译文；不存在（或该句无需翻译）时保持单行展示 */
  translatedText?: string
}
