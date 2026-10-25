/**
 * 字幕产物类型的运行时值。
 *
 * 键名与后端 ArtifactType 一致。取值在「按类型挑产物」和「按类型分组展示」两处比较，
 * 所以值放 enums/，类型联合由 types/subtitle.ts 用 typeof 派生。
 */

/** 产物类型：原始转写 / 清洗后字幕 / 译文。 */
export const ARTIFACT_TYPE = {
  ORIGINAL: 'ORIGINAL',
  CLEANED: 'CLEANED',
  TRANSLATED: 'TRANSLATED',
} as const
